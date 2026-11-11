package io.drivegrowth.app.simulation

import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.driving.DrivingFrame
import io.drivegrowth.core.contract.*
import java.util.UUID

/** 명시 fake consumer의 전송/완료 수명 시험이다. 실제 SQLite 정산·SDK 이동·도착 성공 근거가 아니다. */
@Suppress("DEPRECATION")
class SimulationTerminalReattachTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    override fun setUp() { super.setUp(); name = "simulation-terminal-${UUID.randomUUID()}" }
    override fun tearDown() { context.getSharedPreferences(name,0).edit().clear().commit(); super.tearDown() }
    private fun seed(stage: Int) {
        val elapsed = if (stage == 65) 3_605_000L else 3_604_000L
        assertTrue(context.getSharedPreferences(name,0).edit().putInt("stage",stage).putLong("elapsed",elapsed).commit())
    }
    fun testConfirmedTerminalDoneReattachesWithoutSendingAnotherObservation() {
        seed(65);val frames=mutableListOf<DrivingFrame>();var changes=0
        instrumentation.runOnMainSync {
            val simulation=UnifiedSimulation(context,name);simulation.changed={changes++}
            simulation.attach { frame,done -> frames+=frame;done() }
            assertEquals(1,frames.size);assertTrue(simulation.controlsAllowed)
            val clock=simulation.clock();simulation.stop()
            simulation.attach { frame,done -> frames+=frame;done() }
            assertEquals("확인 대기 중인 다른 기능에 완료 입력을 다시 보내지 않는다",1,frames.size)
            assertEquals(clock,simulation.clock());assertTrue(simulation.controlsAllowed);assertFalse(simulation.canRetry)
            assertTrue(changes>=2);simulation.stop()
        }
        val prefs=context.getSharedPreferences(name,0)
        assertEquals(65,prefs.getInt("stage",-1));assertEquals(3_605_000L,prefs.getLong("elapsed",-1))
    }
    fun testFailedTerminalKeepsExactInputAndOnlyExplicitSuccessfulRetryCompletesIt() {
        seed(65);val frames=mutableListOf<DrivingFrame>()
        instrumentation.runOnMainSync {
            val simulation=UnifiedSimulation(context,name)
            simulation.attach { frame,done -> frames+=frame
                simulation.rentalResult(ContractResult.Failure(ContractError.STATE_CONFLICT));done()
            }
            assertEquals(1,frames.size);assertFalse(simulation.controlsAllowed);assertTrue(simulation.canRetry)
            simulation.stop();simulation.attach { frame,done -> frames+=frame;done() }
            assertEquals("실패 입력은 attach만으로 재시도/성공 처리하지 않는다",1,frames.size)
            assertTrue(simulation.canRetry);simulation.retry()
            assertEquals(2,frames.size);assertEquals(frames[0],frames[1]);assertTrue(simulation.controlsAllowed)
            simulation.stop();simulation.attach { frame,done -> frames+=frame;done() }
            assertEquals(2,frames.size);assertTrue(simulation.controlsAllowed);simulation.stop()
        }
    }
    fun testStage65WithoutDoneIsNotAssumedCompleted() {
        seed(65);val frames=mutableListOf<DrivingFrame>()
        instrumentation.runOnMainSync {
            val simulation=UnifiedSimulation(context,name)
            simulation.attach { frame,_ -> frames+=frame }
            assertFalse(simulation.controlsAllowed);simulation.stop()
            simulation.attach { frame,done -> frames+=frame;done() }
            assertEquals(2,frames.size);assertEquals(frames[0],frames[1]);assertTrue(simulation.controlsAllowed);simulation.stop()
        }
    }
    fun testConfirmedIntermediateFrameStillReplaysAfterCancelledNextStageSchedule() {
        seed(64);val frames=mutableListOf<DrivingFrame>()
        instrumentation.runOnMainSync {
            val simulation=UnifiedSimulation(context,name)
            simulation.attach { frame,done -> frames+=frame;done() }
            simulation.stop() // 다음 단계의 250ms UI 예약을 취소한다.
            simulation.attach { frame,done -> frames+=frame;done() }
            assertEquals(2,frames.size);assertEquals(frames[0],frames[1]);assertFalse(simulation.controlsAllowed);simulation.stop()
        }
    }
}
