package io.drivegrowth.core.contract

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/** #121 계약/같은 메모리 원장 시험이다. SQLite·앱 재실행 시험이 아니다. */
class GachaChangeStoreTest {
    private val vehicleId = VehicleId("hyundai_casper")
    private val authority = GachaAuthority(InputSource.SIMULATION, ContentVersion("content-1"), RuleVersion("rules-1"),
        1, 3, GameMoney(500), 30, Rarity.RARE, setOf(vehicleId), Rarity.entries.toSet())
    private fun store(tickets: Long = 1, balance: Long = 2_000, cars: Int = 0) = InMemoryGameChangeStore(
        (0 until cars).map { award("seed-$it").vehicle }, GameMoney(balance),
        gachaState = GachaState(authority, GachaProgress(Revision(0), 0, tickets)))
    private fun award(id: String, cost: Long = 500, rarity: Rarity = Rarity.COMMON, next: Int = 1, guaranteed: Boolean = false) =
        GachaAward(OwnedVehicle(OwnedVehicleId(id), vehicleId, 1, Revision(0), VehicleLifecycle.OWNED,
            rarity, 0, GameMoney(cost), VehicleAcquisitionSource.GACHA), next, guaranteed)
    private fun command(id: String = "draw-1", ticket: GachaTicketUse = GachaTicketUse.PURCHASED) =
        GachaCommand(RequestId(id), authority.source, authority.contentVersion, authority.ruleVersion,
            Revision(0), 1, 3, 0, ticket, award("gacha-$id", if (ticket == GachaTicketUse.FREE) 0 else 500))
    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun state(store: GachaChangeStore) = ok(store.readGachaState())
    private fun owned(store: GameChangeStore) = ok(store.listOwnedVehicles())

    @Test fun `유료 명령은 재화 개체 카운터와 최초 전체 결과를 함께 확정한다`() {
        val store = store()
        val request = command()
        val committed = store.commitGacha(request)
        assertTrue("전체 가챠 명령 성공", committed is ContractResult.Success)
        val first = ok(committed)
        assertEquals(request, first.command)
        assertEquals(GameMoney(1500), first.settlement.balance)
        assertEquals(request.award.vehicle, first.settlement.changedVehicle)
        assertEquals(GachaProgress(Revision(1), 1, 1), first.progress)
        assertEquals(first.progress, state(store).progress)
        assertEquals(first, ok(store.commitGacha(request)))
        assertEquals(1, owned(store).size)
    }

    @Test fun `무료권은 실제0 취득액으로 한 장만 소비하고 0원 거래를 만들지 않는다`() {
        val store = store(balance = 0)
        val request = command(ticket = GachaTicketUse.FREE)
        val result = ok(store.commitGacha(request))
        assertNull(result.settlement.transaction.money)
        assertEquals(GameMoney(0), result.settlement.balance)
        assertEquals(0L, result.progress.freeTickets)
        assertEquals(GameMoney(0), result.settlement.changedVehicle!!.acquisitionCost)
        assertEquals(result, ok(store.commitGacha(request)))
    }

    @Test fun `같은 ID의 의미 입력 하나라도 다르면 충돌하고 원본 전체 결과가 유지된다`() {
        val store = store()
        val request = command()
        val first = ok(store.commitGacha(request))
        val alternatives = listOf(
            request.copy(source = InputSource.VEHICLE), request.copy(contentVersion = ContentVersion("other")),
            request.copy(ruleVersion = RuleVersion("other")), request.copy(expectedRevision = Revision(1)),
            request.copy(shopLevel = 2), request.copy(garageCapacity = 4), request.copy(drawsSinceGuarantee = 1),
            request.copy(drawsSinceGuarantee = 1, award = request.award.copy(nextCounter = 2)),
            request.copy(ticket = GachaTicketUse.FREE), request.copy(award = request.award.copy(nextCounter = 2)),
            request.copy(award = request.award.copy(guaranteed = true)),
            request.copy(award = request.award.copy(vehicle = request.award.vehicle.copy(rarity = Rarity.RARE))),
            request.copy(award = request.award.copy(vehicle = request.award.vehicle.copy(acquisitionCost = GameMoney(350))))
        )
        alternatives.forEach { assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGacha(it)) }
        assertEquals(first.progress, state(store).progress)
        assertEquals(1, owned(store).size)
        assertEquals(first, ok(store.commitGacha(request)))
    }

    @Test fun `성장 처분 후에도 예전 가챠는 현재 개체를 덮지 않고 당시 snapshot을 재생한다`() {
        val store = store()
        val request = command()
        val first = ok(store.commitGacha(request))
        val grown = ok(store.commitGameChange(GameChange(RequestId("grow"), TransactionKind.GARAGE, authority.source,
            authority.ruleVersion, vehicle = VehicleWrite.Update(request.award.vehicle.ownedVehicleId, Revision(0), 2,
                VehicleLifecycle.OWNED, 40))))
        assertEquals(first, ok(store.commitGacha(request)))
        assertEquals(grown.changedVehicle, owned(store).single())
        ok(store.commitGameChange(GameChange(RequestId("dispose"), TransactionKind.GARAGE, authority.source,
            authority.ruleVersion, vehicle = VehicleWrite.Update(request.award.vehicle.ownedVehicleId, Revision(1), 2,
                VehicleLifecycle.SOLD, 40))))
        assertEquals(first, ok(store.commitGacha(request)))
        assertTrue(owned(store).isEmpty())
        assertEquals(Rarity.COMMON, first.settlement.changedVehicle!!.rarity)
        assertEquals(GameMoney(500), first.settlement.changedVehicle!!.acquisitionCost)
    }

    @Test fun `마지막 칸에 두 가챠 명령이 경쟁해도 한 개체만 지급된다`() {
        val store = store(cars = 2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val results = pool.invokeAll(listOf(Callable { barrier.await(5, TimeUnit.SECONDS); store.commitGacha(command("a")) },
                Callable { barrier.await(5, TimeUnit.SECONDS); store.commitGacha(command("b")) }), 5, TimeUnit.SECONDS).map { it.get() }
            assertEquals(1, results.count { it is ContractResult.Success })
            assertEquals(3, owned(store).size)
            assertEquals(GachaProgress(Revision(1), 1, 1), state(store).progress)
            val success = ok(results.first { it is ContractResult.Success })
            assertEquals(GameMoney(1500), success.settlement.balance)
            assertEquals(success, ok(store.commitGacha(success.command)))
        } finally { pool.shutdownNow() }
    }

    @Test fun `과거에 준비한 용량이나 카운터를 현재 권위 상태로 신뢰하지 않는다`() {
        val store = store()
        val before = state(store)
        for (request in listOf(command().copy(garageCapacity = 100), command().copy(shopLevel = 2),
            command().copy(drawsSinceGuarantee = 1), command().copy(expectedRevision = Revision(1)))) {
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGacha(request))
        }
        assertEquals(before, state(store))
        assertTrue(owned(store).isEmpty())
    }

    @Test fun `다른 종류 요청과 ID를 양방향으로 재사용할 수 없다`() {
        val money = GameChange(RequestId("draw-1"), TransactionKind.GARAGE, authority.source, authority.ruleVersion,
            money = MoneyChange(MoneyDirection.CREDIT, GameMoney(100)))
        val legacyFirst = store()
        ok(legacyFirst.commitGameChange(money))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), legacyFirst.commitGacha(command()))
        val gachaFirst = store()
        val first = ok(gachaFirst.commitGacha(command()))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), gachaFirst.commitGameChange(money))
        assertEquals(first, ok(gachaFirst.commitGacha(command())))
    }

    @Test fun `잔액 무료권 부족과 저장 실패는 개체 카운터 티켓을 전혀 바꾸지 않는다`() {
        val cases = listOf(store(balance = 499) to command(), store(tickets = 0) to command(ticket = GachaTicketUse.FREE))
        for ((store, request) in cases) {
            val before = state(store)
            assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS), store.commitGacha(request))
            assertEquals(before, state(store)); assertTrue(owned(store).isEmpty())
        }
        val store = store()
        val before = state(store)
        store.nextCommitFailure = ContractError.STORAGE_FAILURE
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.commitGacha(command()))
        assertEquals(before, state(store)); assertTrue(owned(store).isEmpty())
        assertEquals(GameMoney(1500), ok(store.commitGacha(command())).settlement.balance)
    }

    @Test fun `예전 Add 경로와 새 가챠도 같은 현재 공간을 검사한다`() {
        val store = store(cars = 2)
        val first = ok(store.commitGacha(command()))
        val legacy = GameChange(RequestId("legacy-add"), TransactionKind.GARAGE, authority.source, authority.ruleVersion,
            vehicle = VehicleWrite.Add(award("legacy").vehicle))
        assertEquals(ContractResult.Failure(ContractError.CAPACITY_EXCEEDED), store.commitGameChange(legacy))
        assertEquals(3, owned(store).size)
        assertEquals(first, ok(store.commitGacha(command())))
    }

    @Test fun `설정 없는 기존 저장소에 가챠 상태를 임의 생성하지 않는다`() {
        val store = InMemoryGameChangeStore(emptyList())
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), store.readGachaState())
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), store.commitGacha(command()))
    }

    @Test fun `해금 후보는 생성 시 복사되고 외부에서 수정되지 않는다`() {
        val keys = mutableSetOf(vehicleId)
        val a = GachaAuthority(authority.source, authority.contentVersion, authority.ruleVersion, 1, 3,
            GameMoney(500), 30, Rarity.RARE, keys, Rarity.entries.toSet())
        keys.clear()
        assertEquals(setOf(vehicleId), a.unlockedVehicles)
        try { (a.unlockedVehicles as MutableSet).clear(); fail("수정 가능한 후보") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun `보장 회차와 희귀 이상 결과의 카운터는 기존 계산 규칙과 일치해야 한다`() {
        val store = InMemoryGameChangeStore(emptyList(), GameMoney(2000),
            gachaState = GachaState(authority, GachaProgress(Revision(0), 29, 1)))
        val before = state(store)
        val base = command().copy(drawsSinceGuarantee = 29)
        for (bad in listOf(base, base.copy(award = award("rare", rarity = Rarity.RARE, next = 0)),
            base.copy(award = award("rare", rarity = Rarity.RARE, next = 1, guaranteed = true)))) {
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), store.commitGacha(bad))
            assertEquals(before, state(store)); assertTrue(owned(store).isEmpty())
        }
        val good = base.copy(award = award("rare", rarity = Rarity.RARE, next = 0, guaranteed = true))
        assertEquals(0, ok(store.commitGacha(good)).progress.drawsSinceGuarantee)
        val earlyRare = store()
        assertEquals(0, ok(earlyRare.commitGacha(command().copy(award = award("early", rarity = Rarity.LEGEND, next = 0)))).progress.drawsSinceGuarantee)
    }

    @Test fun `잘못된 새 개체 성장 출처 취득액과 후보는 부분 반영 없이 거절된다`() {
        val store = store()
        val base = command()
        val vehicle = base.award.vehicle
        val invalid = listOf(vehicle.copy(vehicleId = VehicleId("locked")), vehicle.copy(revision = Revision(1)),
            vehicle.copy(lifecycle = VehicleLifecycle.SOLD), vehicle.copy(level = 2), vehicle.copy(levelProgressPercent = 1),
            vehicle.copy(acquisitionSource = VehicleAcquisitionSource.PURCHASE_DELIVERY), vehicle.copy(acquisitionCost = GameMoney(0)))
        for (v in invalid) {
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), store.commitGacha(base.copy(award = base.award.copy(vehicle = v))))
            assertTrue(owned(store).isEmpty())
            assertEquals(GachaProgress(Revision(0), 0, 1), state(store).progress)
        }
        assertEquals(GameMoney(1500), ok(store.commitGacha(base)).settlement.balance)
    }

    @Test fun `새 요청의 출처 버전과 revision 초과도 무변경이다`() {
        val store = store()
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), store.commitGacha(command().copy(source = InputSource.VEHICLE)))
        for (bad in listOf(command().copy(contentVersion = ContentVersion("old")), command().copy(ruleVersion = RuleVersion("old"))))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGacha(bad))
        val max = InMemoryGameChangeStore(emptyList(), GameMoney(2000),
            gachaState = GachaState(authority, GachaProgress(Revision(Int.MAX_VALUE), 0, Long.MAX_VALUE)))
        val before = state(max)
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), max.commitGacha(command().copy(expectedRevision = Revision(Int.MAX_VALUE))))
        assertEquals(before, state(max)); assertTrue(owned(max).isEmpty())
    }

    @Test fun `확정 재생은 대기 중인 실패를 소모하지 않으며 다음 새 요청만 실패한다`() {
        val store = store()
        val first = ok(store.commitGacha(command()))
        store.nextCommitFailure = ContractError.STORAGE_FAILURE
        assertEquals(first, ok(store.commitGacha(command())))
        val next = command("next").copy(expectedRevision = Revision(1), drawsSinceGuarantee = 1,
            award = award("next", next = 2))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.commitGacha(next))
        assertEquals(first.progress, state(store).progress)
        assertEquals(1, owned(store).size)
        val second = ok(store.commitGacha(next))
        assertEquals(GameMoney(1000), second.settlement.balance)
        assertEquals(first, ok(store.commitGacha(command())))
        assertEquals(second.progress, state(store).progress)
        assertEquals(2, owned(store).size)
    }

    @Test fun `예전 Add가 먼저 마지막 칸을 채워도 가챠는 현재 공간을 읽는다`() {
        val store = store(cars = 2)
        ok(store.commitGameChange(GameChange(RequestId("legacy"), TransactionKind.GARAGE, authority.source,
            authority.ruleVersion, vehicle = VehicleWrite.Add(award("legacy").vehicle))))
        val before = state(store)
        assertEquals(ContractResult.Failure(ContractError.CAPACITY_EXCEEDED), store.commitGacha(command()))
        assertEquals(before, state(store)); assertEquals(3, owned(store).size)
    }

    @Test fun `유료 명령을 예전 Add 명령으로 바꾸어 재생하거나 다른 종류로 우회할 수 없다`() {
        val store = store()
        val command = command()
        val first = ok(store.commitGacha(command))
        val legacy = GameChange(command.requestId, TransactionKind.GARAGE, command.source, command.ruleVersion,
            MoneyChange(MoneyDirection.DEBIT, GameMoney(500)), VehicleWrite.Add(command.award.vehicle))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGameChange(legacy))
        assertEquals(first, ok(store.commitGacha(command)))
    }

    @Test fun `희귀도 가용 집합도 복사하며 비활성 희귀도와 빈 차종은 거절한다`() {
        val rarities = mutableSetOf(Rarity.RARE)
        val a = GachaAuthority(authority.source, authority.contentVersion, authority.ruleVersion, 1, 3,
            GameMoney(500), 30, Rarity.RARE, setOf(vehicleId), rarities)
        rarities.add(Rarity.COMMON)
        assertEquals(setOf(Rarity.RARE), a.eligibleRarities)
        val store = InMemoryGameChangeStore(emptyList(), GameMoney(2000),
            gachaState = GachaState(a, GachaProgress(Revision(0), 0, 1)))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), store.commitGacha(command()))
        val empty = GachaAuthority(authority.source, authority.contentVersion, authority.ruleVersion, 1, 3,
            GameMoney(500), 30, Rarity.RARE, emptySet(), Rarity.entries.toSet())
        val noCandidates = InMemoryGameChangeStore(emptyList(), GameMoney(2000),
            gachaState = GachaState(empty, GachaProgress(Revision(0), 0, 1)))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), noCandidates.commitGacha(command()))
        assertTrue(owned(noCandidates).isEmpty())
    }


    @Test fun `소비 예제는 조회한 권위 상태와 기존 추첨을 명령으로 보관하고 저장 결과만 사용한다`() {
        val gateway: GachaChangeStore = store()
        val snapshot = ok(gateway.readGachaState())
        val a = snapshot.authority
        val draw = io.drivegrowth.core.progression.Gacha(io.drivegrowth.core.progression.GachaRules(
            pity = a.pity, guaranteedFrom = a.guaranteedFrom)).drawRarity(snapshot.progress.drawsSinceGuarantee, kotlin.random.Random(33))
        val retained = GachaCommand(RequestId("consumer"), a.source, a.contentVersion, a.ruleVersion,
            snapshot.progress.revision, a.shopLevel, a.garageCapacity, snapshot.progress.drawsSinceGuarantee,
            GachaTicketUse.PURCHASED, award("consumer", rarity = draw.rarity, next = draw.drawsSinceGuarantee, guaranteed = draw.guaranteed))
        val first = ok(gateway.commitGacha(retained))
        assertEquals(retained, first.command)
        assertEquals(draw.rarity, first.settlement.changedVehicle!!.rarity)
        assertEquals(draw.drawsSinceGuarantee, first.progress.drawsSinceGuarantee)
        // 응답 유실 뒤에는 새 추첨을 붙이지 않고 보관한 명령의 당시 전체 결과를 받는다.
        assertEquals(first, ok(gateway.commitGacha(retained)))
    }

    @Test fun `동시에 같은 요청을 두 번 내도 무료권과 개체는 한 번만 변한다`() {
        val store = store(balance = 0)
        val request = command(ticket = GachaTicketUse.FREE)
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val results = pool.invokeAll((1..2).map { Callable { barrier.await(5, TimeUnit.SECONDS); store.commitGacha(request) } },
                5, TimeUnit.SECONDS).map { ok(it.get()) }
            assertEquals(results[0], results[1])
            assertEquals(1, owned(store).size)
            assertEquals(GachaProgress(Revision(1), 1, 0), state(store).progress)
        } finally { pool.shutdownNow() }
    }

    @Test fun `서로 다른 두 무료 요청이 마지막 한 장을 같이 쓸 수 없다`() {
        val store = store(balance = 0)
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val results = pool.invokeAll(listOf("a", "b").map { id -> Callable {
                barrier.await(5, TimeUnit.SECONDS); store.commitGacha(command(id, GachaTicketUse.FREE))
            } }, 5, TimeUnit.SECONDS).map { it.get() }
            assertEquals(1, results.count { it is ContractResult.Success })
            assertEquals(1, owned(store).size)
            assertEquals(GachaProgress(Revision(1), 1, 0), state(store).progress)
        } finally { pool.shutdownNow() }
    }

}
