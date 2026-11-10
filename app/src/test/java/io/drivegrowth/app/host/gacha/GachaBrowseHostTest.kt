package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class GachaBrowseHostTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(task: Runnable) { tasks.add(task) }
        fun run() { tasks.removeFirst().run() }
    }
    private class Source : GachaHostSource {
        var reads = 0
        var result: ContractResult<GachaOffers> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        override fun read(paid: RequestId, free: RequestId): ContractResult<GachaOffers> { reads++; return result }
        override fun prepare(input: GachaDrawInput): ContractResult<GachaCommand> = error("조회 호스트는 추첨 준비를 실행하면 안 됨")
        override fun commit(command: GachaCommand): ContractResult<GachaSettlement> = error("조회 호스트는 거래를 실행하면 안 됨")
    }
    @Test fun missingAuthorityIsNotFilledAndReadingRequiresCurrentGate() {
        val source = Source(); val worker = Queue(); val frames = mutableListOf<GachaFrame>()
        val host = GachaBrowseHost(source, worker, Executor { it.run() })
        host.attach { frame, _ -> frames.add(frame) }; host.refresh(); assertTrue(worker.tasks.isEmpty())
        host.setControlsEnabled(true); worker.run()
        assertTrue(frames.last().screen is GachaScreen.Missing); assertEquals(1, source.reads)
    }
    @Test fun oldRepliesCannotPublishAfterRestrictionOrDetach() {
        val worker = Queue(); val main = Queue(); val frames = mutableListOf<GachaFrame>()
        val host = GachaBrowseHost(Source(), worker, main)
        host.attach { f, _ -> frames.add(f) }; host.setControlsEnabled(true); worker.run()
        host.setControlsEnabled(false); val count = frames.size; main.run(); assertEquals(count, frames.size)
        host.setControlsEnabled(true); worker.run(); host.detach(); main.run(); assertEquals(count + 1, frames.size)
    }
    @Test fun realOffersRemainVisibleButCannotDrawWithoutDurableCheckpoint() {
        val source = Source()
        source.result = ContractResult.Success(GachaOffers(null, null, null, null, null, GameMoney(900), null,
            listOf(GachaOption("paid", "유료", GachaOptionKind.PAID, GameMoney(500), 0, 0, RequestId("paid"), GachaAvailability.READY, "")),
            GachaAvailability.READY, GachaOrigin.STORED))
        val frames = mutableListOf<GachaFrame>()
        val host = GachaBrowseHost(source, Executor { it.run() }, Executor { it.run() })
        host.attach { f, _ -> frames.add(f) }; host.setControlsEnabled(true)
        val offers = (frames.last().screen as GachaScreen.Offers).value
        assertEquals(GameMoney(900), offers.balance); assertEquals(GachaOrigin.STORED, offers.origin)
        assertEquals(GachaAvailability.UNSUPPORTED, offers.displayProblem())
        assertEquals(GachaAvailability.UNSUPPORTED, offers.options.single().displayProblem())
        assertTrue(offers.options.single().description.contains("보존"))
    }
    @Test fun storageFailureKeepsItsErrorInsteadOfLookingLikeMissingAuthority() {
        val source = Source(); source.result = ContractResult.Failure(ContractError.STORAGE_FAILURE)
        var frame: GachaFrame? = null
        val host = GachaBrowseHost(source, Executor { it.run() }, Executor { it.run() })
        host.attach { f, _ -> frame = f }; host.setControlsEnabled(true)
        assertEquals(ContractError.STORAGE_FAILURE, (frame!!.screen as GachaScreen.Failure).error)
    }
}
