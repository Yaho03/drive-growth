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
    @Test fun allTerminalFramesAreAcceptedByTheRealInteraction() {
        val offers = GachaOffers(null,null,null,null,null,GameMoney(900),null,emptyList(),GachaAvailability.READY,GachaOrigin.STORED)
        for (result in listOf(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), ContractResult.Failure(ContractError.STORAGE_FAILURE), ContractResult.Success(offers))) {
            val source = Source(); source.result = result
            val interaction = GachaInteraction({})
            val frames = mutableListOf<GachaFrame>()
            val host = GachaBrowseHost(source,Executor{it.run()},Executor{it.run()})
            host.attach { frame, enabled -> frames.add(frame); assertTrue(interaction.render(frame,enabled)) }
            host.setControlsEnabled(true)
            assertFalse(interaction.frame!!.screen is GachaScreen.Loading)
            assertTrue(frames.zipWithNext().all { (a,b) -> a.generation < b.generation })
        }
    }
    @Test fun refreshDuringInflightReadDiscardsOldReplyAndPublishesOnlyLatestTerminal() {
        val source=Source(); val worker=Queue(); val main=Queue(); val interaction=GachaInteraction({})
        val frames=mutableListOf<GachaFrame>()
        val host=GachaBrowseHost(source,worker,main)
        host.attach { frame,enabled -> frames.add(frame);assertTrue(interaction.render(frame,enabled)) }
        host.setControlsEnabled(true);val old=frames.last();worker.run()
        host.refresh();main.run() // 이전 응답이 새 Loading을 덮지 못한다.
        assertTrue(interaction.frame!!.screen is GachaScreen.Loading)
        source.result=ContractResult.Failure(ContractError.STORAGE_FAILURE);worker.run();main.run()
        assertEquals(2,source.reads);assertTrue(interaction.frame!!.screen is GachaScreen.Failure)
        assertEquals(1,frames.count{it.screen is GachaScreen.Failure});assertFalse(frames.any{it.screen is GachaScreen.Missing})
        assertFalse(interaction.render(old,true)) // 기존 stale-frame 거절을 유지한다.
    }
    @Test fun detachAndReattachDoNotLetOldWorkPublishOrReuseThePreviousGate() {
        val source=Source(); val worker=Queue(); val main=Queue(); val first=mutableListOf<GachaFrame>()
        val host=GachaBrowseHost(source,worker,main)
        host.attach { frame,_ -> first.add(frame) };host.setControlsEnabled(true);worker.run()
        host.detach();val count=first.size
        val current=GachaInteraction({})
        host.attach { frame,enabled -> assertTrue(current.render(frame,enabled)) }
        assertFalse(current.controlsEnabled)
        host.setControlsEnabled(true);main.run();assertTrue(current.frame!!.screen is GachaScreen.Loading)
        worker.run();main.run()
        assertEquals(count,first.size);assertEquals(2,source.reads)
        assertTrue(current.frame!!.screen is GachaScreen.Missing);assertTrue(current.controlsEnabled)
    }
    @Test fun consecutiveReadsHaveIncreasingDisplayFramesWithoutCancellingTheirOwnWork() {
        val source=Source();val interaction=GachaInteraction({});val frames=mutableListOf<GachaFrame>()
        val host=GachaBrowseHost(source,Executor{it.run()},Executor{it.run()})
        host.attach { frame,enabled -> frames.add(frame);assertTrue(interaction.render(frame,enabled)) }
        host.setControlsEnabled(true)
        repeat(3) {host.refresh();assertTrue(interaction.frame!!.screen is GachaScreen.Missing)}
        assertEquals(4,source.reads)
        assertTrue(frames.zipWithNext().all{(a,b)->a.generation < b.generation})
        assertTrue(interaction.controlsEnabled)
    }
}
