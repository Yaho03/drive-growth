package io.drivegrowth.app.host

import io.drivegrowth.app.ui.results.*
import io.drivegrowth.core.contract.*
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class ResultsHostControllerTest {
    private class Tasks : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.addLast(command) }
        fun runNext() { pending.removeFirst().run() }
    }
    private val saved = ResultsHostTestData.settlement()
    private val data = ResultsHostTestData.snapshot(saved)

    @Test fun writeRunsOffCallerAndOnlyRealSuccessCompletesThenRefreshesOnce() {
        val worker = Tasks(); val main = Tasks()
        val writes = mutableListOf<SettlementId>(); val replies = mutableListOf<ContractResult<Unit>>()
        var refreshes = 0
        val host = ResultsHostController(worker, main, { writes.add(it); ContractResult.Success(Unit) }, { refreshes++ })
        host.attach { _, result -> replies.add(result) }
        host.remember(ResultsUiState(selectedId = saved.settlementId))
        assertTrue(host.markViewed(saved.settlementId, data, true))
        assertTrue(writes.isEmpty())
        assertEquals(ResultsReadState.Pending, host.uiState.readStates[saved.settlementId])
        repeat(100) { assertFalse(host.markViewed(saved.settlementId, data, true)) }
        assertEquals(1, worker.pending.size)
        worker.runNext()
        assertTrue(replies.isEmpty())
        assertEquals(0, refreshes)
        main.runNext()
        assertEquals(listOf(saved.settlementId), writes)
        assertEquals(listOf(ContractResult.Success(Unit)), replies)
        assertEquals(ResultsReadState.Done, host.uiState.readStates[saved.settlementId])
        assertEquals(1, refreshes)
        assertFalse(host.markViewed(saved.settlementId, data, true))
    }

    @Test fun failureAllowsSameIdRetryAndRestrictedOrMissingInputNeverWrites() {
        val worker = Tasks(); val main = Tasks()
        var succeeds = false
        val host = ResultsHostController(worker, main, {
            if (succeeds) ContractResult.Success(Unit) else ContractResult.Failure(ContractError.STORAGE_FAILURE)
        }, {})
        host.remember(ResultsUiState(selectedId = saved.settlementId))
        assertFalse(host.markViewed(saved.settlementId, data, false))
        assertFalse(host.markViewed(SettlementId("unknown"), data, true))
        assertTrue(worker.pending.isEmpty())
        assertTrue(host.markViewed(saved.settlementId, data, true))
        worker.runNext(); main.runNext()
        assertEquals(ResultsReadState.Failed(ContractError.STORAGE_FAILURE), host.uiState.readStates[saved.settlementId])
        succeeds = true
        assertTrue(host.markViewed(saved.settlementId, data, true))
        worker.runNext(); main.runNext()
        assertEquals(ResultsReadState.Done, host.uiState.readStates[saved.settlementId])
    }

    @Test fun detachedScreenGetsNoLateCallbackButRecreatedScreenSeesStoredOutcome() {
        val worker = Tasks(); val main = Tasks()
        var oldReplies = 0; var nextReplies = 0
        val host = ResultsHostController(worker, main, { ContractResult.Success(Unit) }, {})
        host.attach { _, _ -> oldReplies++ }
        host.remember(ResultsUiState(selectedId = saved.settlementId))
        host.markViewed(saved.settlementId, data, true)
        host.detach()
        worker.runNext(); main.runNext()
        host.attach { _, _ -> nextReplies++ }
        assertEquals(0, oldReplies)
        assertEquals(0, nextReplies)
        assertEquals(ResultsReadState.Done, host.uiState.readStates[saved.settlementId])
        assertEquals(saved.settlementId, host.uiState.selectedId)
    }

    @Test fun processRestoredPendingIsUnknownFailureAndNeverAutomaticallyReissuedOrCompleted() {
        val worker = Tasks(); val main = Tasks()
        val restored = ResultsUiState(saved.settlementId, mapOf(saved.settlementId to ResultsReadState.Pending), true)
        val host = ResultsHostController(worker, main, { fail("자동 재전송 금지"); ContractResult.Success(Unit) }, {}, restored)
        assertEquals(saved.settlementId, host.uiState.selectedId)
        assertEquals(ResultsReadState.Failed(ContractError.DATA_UNAVAILABLE), host.uiState.readStates[saved.settlementId])
        assertFalse(host.uiState.retryPending)
        assertTrue(worker.pending.isEmpty())
    }

    @Test fun lateResultUpdatesOnlyOriginalIdAndDoesNotReplaceNewSelection() {
        val worker = Tasks(); val main = Tasks()
        val other = ResultsHostTestData.settlement("other")
        val both = ResultsHostTestData.snapshot(saved, other)
        val replies = mutableListOf<SettlementId>()
        val host = ResultsHostController(worker, main, { ContractResult.Success(Unit) }, {})
        host.remember(ResultsUiState(selectedId = saved.settlementId))
        host.markViewed(saved.settlementId, both, true)
        host.detach()
        host.remember(ResultsUiState(selectedId = other.settlementId))
        host.attach { id, _ -> replies.add(id) }
        assertFalse(host.markViewed(other.settlementId, both, true))
        worker.runNext(); main.runNext()
        assertEquals(listOf(saved.settlementId), replies)
        assertEquals(other.settlementId, host.uiState.selectedId)
        assertEquals(ResultsReadState.Done, host.uiState.readStates[saved.settlementId])
        assertNull(host.uiState.readStates[other.settlementId])
    }

    @Test fun releaseDropsCallbacksAndRefreshAfterWriteWithoutPretendingWriteWasCancelled() {
        val worker = Tasks(); val main = Tasks()
        var writes = 0; var callbacks = 0
        val host = ResultsHostController(worker, main, { writes++; ContractResult.Success(Unit) }, { callbacks++ })
        host.attach { _, _ -> callbacks++ }
        host.remember(ResultsUiState(selectedId = saved.settlementId))
        host.markViewed(saved.settlementId, data, true)
        worker.runNext()
        host.release()
        main.runNext()
        assertEquals(1, writes)
        assertEquals(0, callbacks)
        assertFalse(host.markViewed(saved.settlementId, data, true))
    }
}
