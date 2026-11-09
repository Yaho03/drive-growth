package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GachaHostTest {
    private val content get() = (AssetGameContent { File("src/main/assets/$it").readText() }.load() as ContentLoad.Loaded).snapshot
    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private val authority get() = GachaAuthority(InputSource.SIMULATION, content.catalog.contentVersion, content.ruleVersion,
        1, 3, GameMoney(500), content.gacha.pity, content.gacha.guaranteedFrom,
        content.unlockedAt(1).map { it.vehicleId }.toSet(), Rarity.entries.filter { content.gacha.weights.getValue(it) > 0 }.toSet())
    private val state get() = GachaState(authority, GachaProgress(Revision(0), 0, 1))
    private fun offers(s: GachaState = state, balance: Long = 1000, owned: Int = 0) =
        mapGachaOffers(content, s, GameMoney(balance), owned, RequestId("paid"), RequestId("free"), GachaOrigin.FIXTURE)

    @Test fun `실제 콘텐츠와 권위 상태로 구입 즉시 차감과 무료권 옵션을 표시한다`() {
        val value = ok(offers())
        assertEquals(8, value.candidates!!.size)
        assertEquals("74.7", value.appliedPercent!!.getValue(Rarity.COMMON).stripTrailingZeros().toPlainString())
        assertNull(value.displayProblem())
        assertEquals(GameMoney(500), value.options[0].moneyCost)
        assertEquals(0, value.options[0].ticketCost)
        assertEquals(GameMoney(0), value.options[1].moneyCost)
        assertEquals(1, value.options[1].ticketCost)
    }

    @Test fun `공간 잔액 무료권 부족은 서로 다른 표시 상태다`() {
        assertEquals(GachaAvailability.NO_SPACE, ok(offers(owned = 3)).availability)
        assertEquals(GachaAvailability.INSUFFICIENT_MONEY, ok(offers(balance = 499)).options[0].availability)
        val noTickets = state.copy(progress = state.progress.copy(freeTickets = 0))
        assertEquals(GachaAvailability.INSUFFICIENT_TICKETS, ok(offers(noTickets)).options[1].availability)
    }

    @Test fun `보장 회차의 순환소수는 원시 비율로 정확히 표시하고 제출할 수 있다`() {
        val guaranteed = state.copy(progress = state.progress.copy(drawsSinceGuarantee = 29))
        val value = ok(offers(guaranteed))
        assertNull(value.appliedPercent)
        assertEquals(53L,value.appliedRatio!!.denominator)
        assertEquals(50L,value.appliedRatio.numerators.getValue(Rarity.RARE))
        assertEquals(3L,value.appliedRatio.numerators.getValue(Rarity.LEGEND))
        assertEquals(0L,value.appliedRatio.numerators.getValue(Rarity.COMMON))
        assertNull(value.displayProblem())
    }

    @Test fun `버전 불일치와 표현 범위 초과 무료권은 명시 거절한다`() {
        val wrong = GachaAuthority(InputSource.SIMULATION, ContentVersion("wrong"), content.ruleVersion, 1, 3, GameMoney(500),
            30, Rarity.RARE, authority.unlockedVehicles, authority.eligibleRarities)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), offers(GachaState(wrong, state.progress)))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), offers(state.copy(progress = state.progress.copy(freeTickets = Long.MAX_VALUE))))
    }

    private inner class Harness(restored: GachaHostState = GachaHostState()) {
        val tasks = ArrayDeque<() -> Unit>()
        val replies = ArrayDeque<() -> Unit>()
        var reads = 0; var prepares = 0; val commands = mutableListOf<GachaCommand>()
        var failure: ContractError? = null; var prepareFailure: ContractError? = null; var saveFailure = false
        val frames = mutableListOf<GachaFrame>(); val enabled = mutableListOf<Boolean>()
        val completed = mutableListOf<Pair<GachaRequest, ContractResult<Unit>>>()
        val saves = mutableListOf<GachaHostState>()
        val initial = ok(offers())
        val fixed = GachaCommand(RequestId("paid"), InputSource.SIMULATION, content.catalog.contentVersion, content.ruleVersion,
            Revision(0), 1, 3, 0, GachaTicketUse.PURCHASED,
            GachaAward(OwnedVehicle(OwnedVehicleId("award"), VehicleId("hyundai_casper"), 1, Revision(0), VehicleLifecycle.OWNED,
                Rarity.RARE, 0, GameMoney(500), VehicleAcquisitionSource.GACHA), 0, false))
        val settled = GachaSettlement(fixed, Settlement(SettlementId("result"), Transaction(TransactionId("tx"), fixed.requestId,
            TransactionKind.GARAGE, fixed.source, fixed.ruleVersion, MoneyChange(MoneyDirection.DEBIT, GameMoney(500))),
            GameMoney(500), fixed.award.vehicle, EpochMillis(100)), GachaProgress(Revision(1), 0, 1))
        val source = object : GachaHostSource {
            override fun read(paid: RequestId, free: RequestId): ContractResult<GachaOffers> { reads++; return ContractResult.Success(initial) }
            override fun prepare(input: GachaDrawInput): ContractResult<GachaCommand> { prepares++; return prepareFailure?.let { ContractResult.Failure(it) } ?: ContractResult.Success(fixed) }
            override fun commit(command: GachaCommand): ContractResult<GachaSettlement> {
                commands += command
                return failure?.let { ContractResult.Failure(it) } ?: ContractResult.Success(settled)
            }
        }
        val host = GachaHostController(source, { tasks.addLast(it) }, { replies.addLast(it) },
            { value -> saves += value; if (saveFailure) ContractResult.Failure(ContractError.STORAGE_FAILURE) else ContractResult.Success(Unit) },
            { frame, gate -> frames += frame; enabled += gate }, { r, result -> completed += r to result }, {}, restored)
        fun drain() { while (tasks.isNotEmpty() || replies.isNotEmpty()) { if (tasks.isNotEmpty()) tasks.removeFirst()(); if (replies.isNotEmpty()) replies.removeFirst()() } }
        fun request(attempt: String = "one") = GachaRequest(1, GachaDrawInput(initial, initial.options[0]), attempt)
    }

    @Test fun `반복 탭은 한 번만 준비 확정하고 실제 반환 정산으로 새 결과를 만든다`() {
        val h = Harness();h.host.setControlsEnabled(true)
        assertTrue(h.host.draw(h.request()));assertFalse(h.host.draw(h.request("two")))
        h.drain()
        assertEquals(1, h.prepares);assertEquals(listOf(h.fixed), h.commands)
        val result = h.frames.last().screen as GachaScreen.Result
        assertEquals(h.settled.settlement, result.value.settlement)
        assertTrue(h.saves.any { it.pending?.command == h.fixed })
    }

    @Test fun `응답 유실 실패와 복원 뒤 재시도는 같은 전체 명령만 재제출한다`() {
        val h = Harness();h.failure = ContractError.STORAGE_FAILURE;h.host.setControlsEnabled(true)
        h.host.draw(h.request());h.drain()
        val restored = h.host.snapshot()
        val next = Harness(restored);next.host.setControlsEnabled(true);assertTrue(next.host.retry("reload"));next.drain()
        assertEquals(0, next.prepares);assertEquals(listOf(h.fixed), next.commands)
        assertEquals(h.fixed, next.host.snapshot().pending!!.command)
    }

    @Test fun `제한이 준비 중 켜지면 확정을 시작하지 않고 명령을 보존한다`() {
        val h = Harness();h.host.setControlsEnabled(true);h.host.draw(h.request())
        h.tasks.removeFirst()();h.host.setControlsEnabled(false);h.replies.removeFirst()();h.drain()
        assertTrue(h.commands.isEmpty());assertEquals(h.fixed, h.host.snapshot().pending!!.command)
        assertEquals(ContractResult.Failure(ContractError.UX_RESTRICTED), h.completed.last().second)
    }

    @Test fun `확정 중 제한과 release 뒤 응답은 gate를 풀거나 새 세션을 덮지 않는다`() {
        val h = Harness();h.host.setControlsEnabled(true);h.host.draw(h.request())
        h.tasks.removeFirst()();h.replies.removeFirst()();h.tasks.removeFirst()();h.host.setControlsEnabled(false);h.drain()
        assertEquals(1,h.commands.size);assertFalse(h.enabled.last())
        val next = Harness();next.host.setControlsEnabled(true);next.host.draw(next.request())
        next.tasks.removeFirst()();next.replies.removeFirst()();next.host.release()
        val count = next.frames.size;next.drain();assertEquals(count,next.frames.size)
    }

    @Test fun `명령 보존 실패는 실제 확정을 호출하지 않는다`() {
        val h = Harness();h.host.setControlsEnabled(true);h.host.draw(h.request())
        h.tasks.removeFirst()();h.saveFailure = true;h.replies.removeFirst()();h.drain()
        assertTrue(h.commands.isEmpty())
    }

    @Test fun `미확정 입력의 다른 키 내용은 충돌이고 재시도는 재추첨하지 않는다`() {
        val h = Harness();h.failure = ContractError.STATE_CONFLICT;h.host.setControlsEnabled(true)
        h.host.draw(h.request());h.drain()
        val other = h.request().copy(input = GachaDrawInput(h.initial,h.initial.options[1]))
        assertFalse(h.host.draw(other));assertEquals(1,h.prepares)
        h.host.retry("retry");h.drain();assertEquals(1,h.prepares);assertEquals(listOf(h.fixed,h.fixed),h.commands)
    }
    @Test fun `복원된 명령이 확인한 입력과 다르면 확정을 호출하지 않는다`() {
        val prior = Harness()
        val wrong = prior.fixed.copy(requestId = RequestId("other"))
        val h = Harness(GachaHostState(7, GachaHostPending(prior.request(), wrong)))
        h.host.setControlsEnabled(true);h.host.retry("restored");h.drain()
        assertTrue(h.commands.isEmpty())
    }

    @Test fun `완료 callback에서 해제한 세션은 뒤이어 결과를 그리지 않는다`() {
        val h = Harness();val frames = mutableListOf<GachaFrame>()
        lateinit var host: GachaHostController
        host = GachaHostController(h.source, { it() }, { it() }, { ContractResult.Success(Unit) },
            { frame, _ -> frames += frame }, { _, _ -> host.release() }, {})
        host.setControlsEnabled(true);host.draw(h.request())
        assertEquals(1,frames.size)
    }

    @Test fun `실제 GachaPull 준비 경로는 확인한 비용과 키를 소비하고 stale 표시를 거절한다`() {
        val store = object : GachaChangeStore {
            override fun readGachaState(): ContractResult<GachaState> = ContractResult.Success(state)
            override fun listOwnedVehicles(): ContractResult<List<OwnedVehicle>> = ContractResult.Success(emptyList())
            override fun commitGameChange(change: GameChange): ContractResult<Settlement> = error("호스트가 직접 일반 거래를 만들면 안 됨")
            override fun commitGacha(command: GachaCommand): ContractResult<GachaSettlement> = error("이 시험은 prepare만 실행")
        }
        val source = GachaStoreHostSource(content, store, { ContractResult.Success(GameMoney(1000)) }, GachaOrigin.FIXTURE)
        val value = ok(source.read(RequestId("paid"), RequestId("free")))
        val command = ok(source.prepare(GachaDrawInput(value, value.options[0])))
        assertEquals(RequestId("paid"),command.requestId)
        assertEquals(OwnedVehicleId("gacha-paid"),command.award.vehicle.ownedVehicleId)
        assertEquals(GameMoney(500),command.award.vehicle.acquisitionCost)
        val changed = value.copy(balance = GameMoney(999))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),source.prepare(GachaDrawInput(changed,changed.options[0])))
    }

    @Test fun `명령 없는 준비 실패를 retry에서 새 추첨으로 바꾸지 않는다`() {
        val h = Harness();h.prepareFailure = ContractError.DATA_UNAVAILABLE;h.host.setControlsEnabled(true)
        h.host.draw(h.request());h.drain();assertEquals(1,h.prepares)
        h.prepareFailure = null
        assertFalse(h.host.retry("retry"));h.drain()
        assertEquals(1,h.prepares);assertTrue(h.commands.isEmpty())
    }

    @Test fun `확정 실패는 저장 응답 종류를 유지하고 성공 결과를 만들지 않는다`() {
        for (error in listOf(ContractError.STATE_CONFLICT, ContractError.CAPACITY_EXCEEDED,
            ContractError.INSUFFICIENT_FUNDS,ContractError.DATA_UNAVAILABLE,ContractError.STORAGE_FAILURE)) {
            val h = Harness();h.failure=error;h.host.setControlsEnabled(true);h.host.draw(h.request());h.drain()
            assertEquals(ContractResult.Failure(error),h.completed.last().second)
            assertFalse(h.frames.last().screen is GachaScreen.Result)
            assertEquals(h.fixed,h.host.snapshot().pending!!.command)
        }
    }

    @Test fun `명령 없는 미제출 요청만 명시적으로 해소하고 새 조회를 허용한다`() {
        val h = Harness();h.prepareFailure=ContractError.DATA_UNAVAILABLE;h.host.setControlsEnabled(true)
        h.host.draw(h.request());h.drain()
        assertEquals(RequestId("paid"),h.host.clearUnprepared())
        assertNull(h.host.snapshot().pending)
        val committed = Harness();committed.failure=ContractError.STORAGE_FAILURE;committed.host.setControlsEnabled(true)
        committed.host.draw(committed.request());committed.drain()
        assertNull(committed.host.clearUnprepared())
        assertEquals(committed.fixed,committed.host.snapshot().pending!!.command)
    }

    @Test fun `새 조회 뒤 이전 표시 세대에서 온 확인 요청은 준비하지 않는다`() {
        val h = Harness();h.host.setControlsEnabled(true);h.host.load();h.drain()
        val old = h.request().copy(generation = h.frames.last().generation)
        h.host.load();h.drain()
        assertFalse(h.host.draw(old));h.drain()
        assertEquals(0,h.prepares);assertTrue(h.commands.isEmpty())
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),h.completed.last().second)
    }

}
