package io.drivegrowth.app.host.ranking

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class RankingControllerTest {
    private class Queue:Executor{val jobs=java.util.ArrayDeque<Runnable>();override fun execute(r:Runnable){jobs.add(r)};fun run(){jobs.removeFirst().run()}}
    @Test fun restrictedAndDetachedLateReadsNeverPublishAndResumeReadsFresh(){
        val worker=Queue();val main=Queue();var reads=0;val states=mutableListOf<RankingState>()
        val controller=RankingController(worker,main,{reads++;ContractResult.Success(RankingSnapshot(InputSource.SIMULATION,EpochMillis(reads.toLong()),ContentVersion("c"),RuleVersion("r"),emptyMap()))})
        controller.attach(states::add);controller.refresh();assertEquals(0,reads)
        controller.setControlsEnabled(true);worker.run();controller.setControlsEnabled(false);main.run();assertFalse(states.any{it is RankingState.Content})
        controller.setControlsEnabled(true);worker.run();main.run();assertEquals(2L,(states.last() as RankingState.Content).snapshot.asOf.value)
        controller.refresh();worker.run();controller.detach();main.run();assertEquals(3,states.count{it is RankingState.Loading})
    }
    @Test fun failuresRemainFailuresAndConcurrentRefreshIsCoalesced(){
        val worker=Queue();val main=Queue();val states=mutableListOf<RankingState>();var reads=0
        val c=RankingController(worker,main,{reads++;ContractResult.Failure(ContractError.STORAGE_FAILURE)})
        c.attach(states::add);c.setControlsEnabled(true);c.refresh();c.refresh();assertEquals(1,worker.jobs.size)
        worker.run();main.run();assertEquals(1,worker.jobs.size);worker.run();main.run()
        assertEquals(2,reads);assertEquals(RankingState.Failure(ContractError.STORAGE_FAILURE),states.last())
    }
}
