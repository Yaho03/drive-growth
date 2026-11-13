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
    @Test fun completionWaitsForAcceptedWorkerWorkAndRunsAfterOldUiIsDetached() {
        val worker=Queue();val main=Queue();val calls=mutableListOf<String>()
        val host=ObservedDrivingHost(worker,main,{RecordingClock(200,EpochMillis(1100))},
            {_,_,_->calls+="stored";ContractResult.Failure(ContractError.DATA_UNAVAILABLE)},
            {_,_->ContractResult.Failure(ContractError.DATA_UNAVAILABLE)},{"receiver"})
        host.start{calls+="old-ui"};host.accept(frame()){calls+="completed"};host.stop()
        assertTrue(calls.isEmpty());worker.drain();assertEquals(listOf("stored"),calls)
        main.drain();assertEquals(listOf("stored","completed"),calls)
    }

    @Test fun missingClockSkipsObservationAndPauseButClosesReceiver() {
        val worker = Queue()
        val main = Queue()
        var clock: RecordingClock? = RecordingClock(200, EpochMillis(1100))
        val results = mutableListOf<ContractResult<DrivingRecordingResult>>()
        var completed = false
        val host = ObservedDrivingHost(worker, main, { clock },
            { _, _, _ -> error("시각이 없는 관측을 저장하지 않습니다") },
            { _, _ -> error("시각이 없는 중단을 저장하지 않습니다") })
        host.start(results::add)
        host.accept(frame()) { completed = true }
        clock = null
        worker.drain()
        main.drain()
        assertEquals(listOf(ContractResult.Failure(ContractError.DATA_UNAVAILABLE)), results)
        assertTrue(completed)
        host.stop()
        assertTrue(worker.jobs.isEmpty())
        host.start { error("이전 응답이 다시 전달되면 안 됩니다") }
        host.stop()
        assertTrue(worker.jobs.isEmpty())
    }

}
