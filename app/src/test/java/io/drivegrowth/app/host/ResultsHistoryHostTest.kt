package io.drivegrowth.app.host

import io.drivegrowth.app.data.*
import io.drivegrowth.app.ui.results.*
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class ResultsHistoryHostTest {
    private class Tasks:Executor {val tasks=ArrayDeque<Runnable>();override fun execute(r:Runnable){tasks.add(r)}}
    private fun pump(worker:Tasks,main:Tasks){while(worker.tasks.isNotEmpty() || main.tasks.isNotEmpty()){if(worker.tasks.isNotEmpty())worker.tasks.removeFirst().run();if(main.tasks.isNotEmpty())main.tasks.removeFirst().run()}}
    private val saved=ResultsHostTestData.settlement()
    private val identity=GameIdentity(ProfileId("actual"),saved.transaction.source)
    private fun history(viewed:Boolean)=SettlementHistorySnapshot(identity,listOf(SettlementHistoryEntry(saved,viewed)))
    @Test fun actualHistoryEligibilityBlocksAlreadyViewedAndKeepsAcknowledgedDetail() {
        val worker=Tasks();val main=Tasks();var viewed=false;var writes=0
        val host=ResultsHostController(worker,main,{writes++;viewed=true;ContractResult.Success(Unit)},{},readHistory={ContractResult.Success(history(viewed))})
        host.setControlsEnabled(true);pump(worker,main);host.remember(ResultsUiState(selectedId=saved.settlementId,scope=ResultsScope.ALL))
        assertTrue(host.markViewed(saved.settlementId,null,true));pump(worker,main)
        val content=host.historyState as ResultsScreenState.Content
        assertEquals(saved,content.results.single().settlement);assertTrue(content.results.single().viewed);assertEquals(saved.settlementId,host.uiState.selectedId)
        assertFalse(host.markViewed(saved.settlementId,null,true));assertEquals(1,writes);assertEquals(ResultsScope.ALL,host.uiState.scope)
    }
    @Test fun gateLostBeforeQueuedReadOrMarkDoesNotCallBackend() {
        val worker=Tasks();val main=Tasks();var reads=0;var writes=0
        val host=ResultsHostController(worker,main,{writes++;ContractResult.Success(Unit)},{},readHistory={reads++;ContractResult.Success(history(false))})
        host.setControlsEnabled(true);host.setControlsEnabled(false);pump(worker,main);assertEquals(0,reads)
        host.setControlsEnabled(true);pump(worker,main);host.remember(ResultsUiState(selectedId=saved.settlementId));host.markViewed(saved.settlementId,null,true)
        host.setControlsEnabled(false);pump(worker,main);assertEquals(0,writes)
    }
    @Test fun reopeningGateDoesNotRunPreviouslyQueuedMarkViewed() {
        val worker = Tasks()
        val main = Tasks()
        var writes = 0
        val host = ResultsHostController(worker, main, {
            writes++
            ContractResult.Success(Unit)
        }, {}, readHistory = { ContractResult.Success(history(writes > 0)) })
        host.setControlsEnabled(true)
        pump(worker, main)
        host.remember(ResultsUiState(selectedId = saved.settlementId, scope = ResultsScope.ALL))
        assertTrue(host.markViewed(saved.settlementId, null, true))

        host.setControlsEnabled(false)
        host.setControlsEnabled(true)
        pump(worker, main)

        assertEquals(0, writes)
        assertEquals(ResultsReadState.Failed(ContractError.UX_RESTRICTED), host.uiState.readStates[saved.settlementId])
        assertEquals(saved.settlementId, host.uiState.selectedId)
        assertFalse((host.historyState as ResultsScreenState.Content).results.single().viewed)

        assertTrue(host.markViewed(saved.settlementId, null, true))
        pump(worker, main)
        assertEquals(1, writes)
        assertEquals(ResultsReadState.Done, host.uiState.readStates[saved.settlementId])
        assertTrue((host.historyState as ResultsScreenState.Content).results.single().viewed)
    }

    @Test fun failedHistoryRefreshCannotMarkPreviousSnapshot() {
        val worker=Tasks();val main=Tasks();var fails=false
        val host=ResultsHostController(worker,main,{fail("과거 snapshot 쓰기 금지");ContractResult.Success(Unit)},{},readHistory={if(fails)ContractResult.Failure(ContractError.STORAGE_FAILURE) else ContractResult.Success(history(false))})
        host.setControlsEnabled(true);pump(worker,main);host.remember(ResultsUiState(selectedId=saved.settlementId));fails=true;host.refreshHistory();pump(worker,main)
        assertTrue(host.historyState is ResultsScreenState.Error);assertFalse(host.markViewed(saved.settlementId,null,true))
    }
}
