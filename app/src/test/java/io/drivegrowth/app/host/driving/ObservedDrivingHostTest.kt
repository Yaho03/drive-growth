package io.drivegrowth.app.host.driving

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class ObservedDrivingHostTest {
    private class Queue:Executor {val jobs=java.util.ArrayDeque<Runnable>();override fun execute(r:Runnable){jobs.add(r)};fun drain(){while(jobs.isNotEmpty())jobs.removeFirst().run()}}
    private fun frame()=DrivingFrame(VehicleObservation(InputSource.VEHICLE,100,EpochMillis(1000),
        Observation.Missing(MissingReason.NO_DATA),Observation.Missing(MissingReason.NO_DATA),Observation.Missing(MissingReason.NO_DATA)),Observation.Missing(MissingReason.NO_DATA))
    @Test fun stopPreservesAcceptedObservationAndPauseButDiscardsOldUiCallback() {
        val w=Queue();val m=Queue();val calls=mutableListOf<String>();var now=200L;var callbacks=0
        val host=ObservedDrivingHost(w,m,{RecordingClock(now,EpochMillis(now+900))},
            {token,frame,at->calls+="$token:observe:${frame.observation.receivedElapsedMs}:${at.elapsed}";ContractResult.Failure(ContractError.DATA_UNAVAILABLE)},
            {token,at->calls+="$token:pause:${at.elapsed}";ContractResult.Failure(ContractError.DATA_UNAVAILABLE)},{"receiver-1"})
        host.start{callbacks++};host.accept(frame());host.stop();host.stop();now=400
        host.accept(frame());w.drain();m.drain()
        assertEquals(listOf("receiver-1:observe:100:400","receiver-1:pause:200"),calls);assertEquals(0,callbacks)
    }
    @Test fun restartUsesNewReceiverAndNeverReattachesOldResponses() {
        val w=Queue();val m=Queue();var next=0;val tokens=mutableListOf<String>();var callbacks=0
        val host=ObservedDrivingHost(w,m,{RecordingClock(200,EpochMillis(1100))},
            {token,_,_->tokens+=token;ContractResult.Failure(ContractError.DATA_UNAVAILABLE)},
            {_,_->ContractResult.Failure(ContractError.DATA_UNAVAILABLE)},{"receiver-${++next}"})
        host.start{callbacks++};host.accept(frame());host.stop();host.start{callbacks++};host.accept(frame())
        w.drain();m.drain();assertEquals(listOf("receiver-1","receiver-2"),tokens);assertEquals(1,callbacks)
    }
}
