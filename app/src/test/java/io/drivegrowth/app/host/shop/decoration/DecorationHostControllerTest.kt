package io.drivegrowth.app.host.shop.decoration

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class DecorationHostControllerTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Backend : DecorationBackend {
        var facts = decorationFacts()
        var entry: DecorationPending = DecorationPending.Empty
        var readError: ContractError? = null
        var saveError: ContractError? = null
        var executeError: ContractError? = null
        var finishError: ContractError? = null
        var forcedReceipt: DecorationSettlement? = null
        val calls = mutableListOf<String>()
        val commands = mutableListOf<DecorationCommand>()
        val originals = mutableMapOf<RequestId, DecorationSettlement>()
        override fun activate(): ContractResult<Unit> { calls += "activate"; return ContractResult.Success(Unit) }
        override fun pending(): ContractResult<DecorationPending> { calls += "pending"; return ContractResult.Success(entry) }
        override fun read(): ContractResult<DecorationFacts> { calls += "read"; return readError?.let { ContractResult.Failure(it) } ?: ContractResult.Success(facts) }
        override fun save(command: DecorationCommand): ContractResult<Unit> {
            calls += "save"; commands += command
            saveError?.let { return ContractResult.Failure(it) }
            entry = DecorationPending.Own(command); return ContractResult.Success(Unit)
        }
        override fun execute(command: DecorationCommand): ContractResult<DecorationSettlement> {
            calls += "execute"; commands += command
            executeError?.let { return ContractResult.Failure(it) }
            forcedReceipt?.let { return ContractResult.Success(it) }
            originals[command.requestId]?.let { return ContractResult.Success(it) }
            val plan = when (val result = prepareDecorationChange(command, facts.state, facts.definitions)) {
                is ContractResult.Failure -> return result
                is ContractResult.Success -> result.value
            }
            val receipt = DecorationSettlement(command, plan.state, plan.money?.let {
                Settlement(SettlementId("s"), Transaction(TransactionId("t"), command.requestId,
                    TransactionKind.FACILITY, command.source, command.ruleVersion, it), plan.state.balance, null, EpochMillis(1))
            })
            originals[command.requestId] = receipt
            facts = facts.copy(state = plan.state, shop = facts.shop.copy(balance = plan.state.balance))
            return ContractResult.Success(receipt)
        }
        override fun finish(command: DecorationCommand): ContractResult<Unit> {
            calls += "finish"; finishError?.let { return ContractResult.Failure(it) }
            entry = DecorationPending.Empty; return ContractResult.Success(Unit)
        }
        override fun cancelSaved(command: DecorationCommand): ContractResult<Unit> { calls += "cancel"; entry = DecorationPending.Empty; return ContractResult.Success(Unit) }
    }
    private class Harness(val backend: Backend = Backend(), val worker: Executor = Executor { it.run() }, val reply: Executor = Executor { it.run() }) {
        val completions = mutableListOf<Pair<ShopRequest, ContractResult<Unit>>>()
        var changed = 0
        val controller = DecorationHostController(GameIdentity(ProfileId("shop-owner"), InputSource.SIMULATION), backend,
            decorationMapper(), worker, reply, { request, result -> completions += request to result })
        init { controller.changed = { _, _ -> changed++ } }
        fun start() { controller.setControlsEnabled(true) }
        fun request() = request(controller.state.screen as ShopScreenState.Content)
    }

    @Test fun `미리보기와 취소는 무저장이고 확정은 원명령 저장 뒤 실행 정리 재조회 순서다`() {
        val worker = Queue(); val h = Harness(worker = worker); h.start(); worker.drain()
        val c = h.controller; val r = h.request(); val ui = ShopInteraction({})
        val original = c.state.screen as ShopScreenState.Content
        ui.render(original, true); ui.select(r.selection); assertTrue(ui.cancel())
        assertEquals(listOf("pending", "read"), h.backend.calls)
        assertEquals("기존 간판", (ui.state as ShopScreenState.Content).appearance.sign)
        assertTrue(c.submit(r)); assertFalse(c.submit(r)); assertEquals(0, h.completions.size)
        assertTrue(h.backend.facts.state.ownedIds.isEmpty())
        worker.drain()
        assertEquals(listOf("pending", "read", "save", "execute", "finish", "read"), h.backend.calls)
        assertEquals(Revision(41), h.backend.commands.first().expectedRevision)
        assertEquals(GameMoney(100), h.backend.commands.first().expectedPrice)
        assertEquals(listOf(r to ContractResult.Success(Unit)), h.completions)
        assertEquals("간판 색상", (c.state.screen as ShopScreenState.Content).appearance.sign)
        assertEquals(GameMoney(900), c.state.current!!.balance)
        assertEquals(Revision(42), c.state.current!!.revision)
        assertNotNull(c.state.receipt)
        assertNull(c.state.pending)
    }

    @Test fun `원래 저장 요청 복구는 현재 정의나 잔액 조회보다 먼저다`() {
        val b = Backend(); val facts = b.facts
        val command = success(decorationMapper().command(facts, Revision(7), request(success(decorationMapper().map(facts, Revision(7))))))
        val receipt = success(b.execute(command)); b.calls.clear(); b.entry = DecorationPending.Own(command)
        b.facts = b.facts.copy(state = b.facts.state.copy(balance = GameMoney(77)), shop = b.facts.shop.copy(balance = GameMoney(77)))
        val h = Harness(b); h.start()
        assertEquals(listOf("pending", "execute", "finish", "read"), b.calls)
        assertSame(receipt, h.controller.state.receipt)
        assertEquals(GameMoney(900), h.controller.state.receipt!!.state.balance)
        assertEquals(GameMoney(77), h.controller.state.current!!.balance)
        assertEquals(GameMoney(77), (h.controller.state.screen as ShopScreenState.Content).balance)
        assertEquals(0, b.calls.count { it == "save" })
    }

    @Test fun `다른 종류 pending은 실행하거나 정리하지 않는다`() {
        val b = Backend(); b.entry = DecorationPending.Foreign
        val h = Harness(b); h.start()
        assertTrue(h.controller.state.foreign)
        assertEquals(listOf("pending"), b.calls)
        assertTrue(h.controller.state.screen is ShopScreenState.Unsupported)
    }

    @Test fun `worker 시작 전 제한되면 저장 실행이 없고 다시 허용되면 새 조회한다`() {
        val queue = Queue(); val h = Harness(worker = queue); h.start(); queue.drain()
        val request = h.request(); h.controller.submit(request); h.controller.setControlsEnabled(false); queue.drain()
        assertFalse(h.backend.calls.contains("save")); assertFalse(h.backend.calls.contains("execute"))
        h.controller.setControlsEnabled(true); queue.drain()
        assertTrue(h.controller.state.screen is ShopScreenState.Content)
        assertEquals(ContractResult.Failure(ContractError.UX_RESTRICTED), h.completions.single().second)
    }

    @Test fun `조회 뒤 제한 전환한 늦은 reply는 선택과 화면을 되살리지 않는다`() {
        val reply = Queue(); val h = Harness(reply = reply); h.start()
        h.controller.setControlsEnabled(false); reply.drain()
        assertTrue(h.controller.state.screen is ShopScreenState.Loading)
        assertNull(h.controller.state.current)
        h.controller.setControlsEnabled(true); reply.drain()
        assertTrue(h.controller.state.screen is ShopScreenState.Content)
    }

    @Test fun `해제된 controller의 늦은 결과는 callback이나 저장을 만들지 않는다`() {
        val worker = Queue(); val h = Harness(worker = worker); h.start(); val before = h.changed
        h.controller.release(); worker.drain()
        assertEquals(before, h.changed)
        assertTrue(h.completions.isEmpty()); assertTrue(h.backend.calls.isEmpty())
    }

    @Test fun `표시 갱신 뒤 옛 선택과 외형을 변조한 선택은 저장하지 않는다`() {
        val h = Harness(); h.start(); val old = h.request(); h.controller.refresh()
        assertFalse(h.controller.submit(old)); assertEquals(0, h.backend.calls.count { it == "save" })
        val fresh = h.request(); val bad = fresh.copy(selection = fresh.selection.copy(option = fresh.selection.option.copy(price = GameMoney(0))))
        assertFalse(h.controller.submit(bad)); assertEquals(0, h.backend.calls.count { it == "save" })
    }

    @Test fun `명확한 save 거절은 pending을 만들지 않지만 불명 실패는 원명령을 보존한다`() {
        val h = Harness(); h.start(); h.backend.saveError = ContractError.STATE_CONFLICT
        h.controller.submit(h.request()); assertNull(h.controller.state.pending)
        h.backend.saveError = null; h.controller.refresh(); val next = h.request(); h.backend.saveError = ContractError.STORAGE_FAILURE
        h.controller.submit(next); val command = h.controller.state.pending; assertNotNull(command)
        h.backend.saveError = null; h.controller.refresh()
        assertEquals(ContractError.STORAGE_FAILURE, h.controller.state.error)
        assertEquals(command, h.controller.state.pending)
        assertEquals(2, h.backend.calls.count { it == "save" })
    }

    @Test fun `실행 실패 재시도는 새 가격으로 저장하지 않고 원래 전체 명령을 쓴다`() {
        val h = Harness(); h.start(); h.backend.executeError = ContractError.DATA_UNAVAILABLE
        h.controller.submit(h.request()); val command = h.controller.state.pending!!
        h.backend.executeError = null; h.controller.refresh()
        assertEquals(command, h.controller.state.receipt!!.command)
        assertEquals(1, h.backend.calls.count { it == "save" })
        assertEquals(2, h.backend.calls.count { it == "execute" })
    }

    @Test fun `확정 후 finish 실패는 원결과와 pending을 보존하고 재시도에서 재차감하지 않는다`() {
        val h = Harness(); h.start(); h.backend.finishError = ContractError.STORAGE_FAILURE
        h.controller.submit(h.request()); val receipt = h.controller.state.receipt
        assertNotNull(receipt); assertNotNull(h.controller.state.pending)
        assertEquals(ContractResult.Success(Unit), h.completions.single().second)
        h.backend.finishError = null; h.controller.refresh()
        assertSame(receipt, h.controller.state.receipt)
        assertEquals(GameMoney(900), h.controller.state.current!!.balance)
        assertNull(h.controller.state.pending)
    }

    @Test fun `확정 뒤 현재 조회 실패는 원결과를 현재 사실로 채우지 않고 재조회한다`() {
        val h = Harness(); h.start(); val r = h.request(); h.backend.readError = ContractError.DATA_UNAVAILABLE
        h.controller.submit(r)
        assertNotNull(h.controller.state.receipt); assertNull(h.controller.state.current)
        assertTrue(h.controller.state.screen is ShopScreenState.Failure)
        assertEquals(ContractResult.Success(Unit), h.completions.single().second)
        h.backend.readError = null; h.controller.refresh()
        assertEquals(GameMoney(900), h.controller.state.current!!.balance)
    }

    @Test fun `backend의 다른 원명령 결과와 다른 프로필 조회는 거절한다`() {
        val h = Harness(); h.start(); val facts = h.backend.facts
        val original = success(decorationMapper().command(facts, Revision(1), h.request()))
        val receipt = success(h.backend.execute(original)); h.backend.forcedReceipt = receipt.copy(command = original.copy(requestId = RequestId("wrong")))
        h.backend.facts = facts; h.backend.entry = DecorationPending.Empty
        h.controller.submit(h.request())
        assertEquals(ContractError.STATE_CONFLICT, h.controller.state.error)
        assertFalse(h.backend.calls.contains("finish"))
        val other = Harness(); other.backend.facts = facts.copy(state = facts.state.copy(profileId = ProfileId("foreign")))
        other.start(); assertEquals(ContractError.STATE_CONFLICT, other.controller.state.error)
    }

    @Test fun `미지원 journal은 사실 표시만 허용하고 신규 저장은 거절한다`() {
        val b = Backend(); b.entry = DecorationPending.Unsupported; val h = Harness(b); h.start()
        assertTrue(h.controller.state.unsupported)
        assertTrue((h.controller.state.screen as ShopScreenState.Content).decorations.all { it.availability != ShopAvailability.AVAILABLE })
        assertFalse(h.controller.submit(request(success(decorationMapper().map(b.facts, h.controller.state.displayRevision)))))
        assertFalse(b.calls.contains("save"))
    }

    @Test fun `명시 활성화만 backend 쓰기를 호출하고 preview 조회 재시도는 활성화하지 않는다`() {
        val h = Harness(); h.start(); h.controller.refresh()
        assertFalse(h.backend.calls.contains("activate"))
        assertTrue(h.controller.activate())
        assertEquals(1, h.backend.calls.count { it == "activate" })
        assertTrue(h.backend.facts.state.ownedIds.isEmpty())
        assertEquals(GameMoney(1000), h.controller.state.current!!.balance)
    }

    @Test fun `저장됐지만 실행 실패한 원명령의 명시 취소는 현재 사실을 다시 읽는다`() {
        val h = Harness(); h.start(); h.backend.executeError = ContractError.DATA_UNAVAILABLE
        h.controller.submit(h.request()); assertNotNull(h.controller.state.pending)
        assertTrue(h.controller.cancelSaved())
        assertNull(h.controller.state.pending)
        assertEquals(1, h.backend.calls.count { it == "cancel" })
        assertEquals(GameMoney(1000), h.controller.state.current!!.balance)
        assertTrue(h.controller.state.current!!.ownedIds.isEmpty())
    }

    @Test fun `같은 요청 ID라도 실제 revision이 다른 새 요청의 거절을 과거 receipt 성공으로 바꾸지 않는다`() {
        val b = Backend()
        b.facts = b.facts.copy(state = b.facts.state.copy(ownedIds = setOf("neutral-sign", "blue-sign"), selectedId = "blue-sign"))
        val h = Harness(b); h.start()
        val first = request(h.controller.state.screen as ShopScreenState.Content, "neutral-sign")
        h.controller.submit(first)
        assertEquals(ContractResult.Success(Unit), h.completions.single().second)
        b.facts = b.facts.copy(state = b.facts.state.copy(revision = Revision(43), selectedId = "blue-sign"))
        h.controller.refresh()
        val different = request(h.controller.state.screen as ShopScreenState.Content, "neutral-sign")
        assertEquals(first.requestId, different.requestId)
        b.saveError = ContractError.STATE_CONFLICT
        h.controller.submit(different)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), h.completions.last().second)
        assertEquals(ContractError.STATE_CONFLICT, h.controller.state.error)
        assertEquals(Revision(42), h.controller.state.receipt!!.state.revision)
    }

    @Test fun `P 허용 중 작업 대기는 제한으로 표시하지 않고 Loading으로 구분한다`() {
        val queue = Queue(); val h = Harness(worker = queue)
        val events = mutableListOf<Pair<DecorationHostState, Boolean>>()
        h.controller.changed = { state, gate -> events += state to gate }
        h.start()
        val loading = events.single { it.first.busy }
        assertTrue(loading.first.screen is ShopScreenState.Loading)
        assertTrue("주차 허용 gate를 단순 busy 때문에 false로 바꾸면 안 됨", loading.second)
        queue.drain()
    }

    @Test fun `확정 뒤 숨김 동안 늦은 결과는 표시하지 않고 복귀 재조회 뒤 원요청만 완료한다`() {
        val replies = Queue(); val h = Harness(reply = replies); h.start(); replies.drain()
        h.controller.submit(h.request()); h.controller.setControlsEnabled(false); replies.drain()
        assertTrue(h.controller.state.screen is ShopScreenState.Loading)
        assertNull(h.controller.state.current); assertTrue(h.completions.isEmpty())
        h.controller.setControlsEnabled(true); replies.drain()
        assertEquals(ContractResult.Success(Unit), h.completions.single().second)
        assertEquals(GameMoney(900), h.controller.state.current!!.balance)
        assertEquals(1, h.backend.calls.count { it == "save" })
        assertEquals(1, h.backend.calls.count { it == "execute" })
    }

    @Test fun `재생성에서 복원한 UI pending은 실제 저장 원명령 결과와 연결하고 현재 조회 실패를 분리한다`() {
        val b = Backend(); val mapper = decorationMapper()
        val savedRequest = request(success(mapper.map(b.facts, Revision(12))))
        val command = success(mapper.command(b.facts, Revision(12), savedRequest))
        val receipt = success(b.execute(command)); b.calls.clear(); b.entry = DecorationPending.Own(command)
        b.readError = ContractError.DATA_UNAVAILABLE
        val completed = mutableListOf<Pair<ShopRequest, ContractResult<Unit>>>()
        val controller = DecorationHostController(GameIdentity(ProfileId("shop-owner"), InputSource.SIMULATION), b, mapper,
            Executor { it.run() }, Executor { it.run() }, { request, result -> completed += request to result },
            initialDisplayRevision = Revision(12), restoredRequest = savedRequest)
        controller.setControlsEnabled(true)
        assertEquals(listOf(savedRequest to ContractResult.Success(Unit)), completed)
        assertSame(receipt, controller.state.receipt); assertNull(controller.state.current)
        assertTrue(controller.state.screen is ShopScreenState.Failure)
        assertEquals(listOf("pending", "execute", "finish", "read"), b.calls)
    }
}
