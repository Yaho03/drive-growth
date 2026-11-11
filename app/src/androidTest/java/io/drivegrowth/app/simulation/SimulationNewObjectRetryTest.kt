package io.drivegrowth.app.simulation

import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.driving.DrivingFrame
import io.drivegrowth.core.contract.*
import java.util.UUID

/** 전송 adapter의 명시 실패 입력만 시험한다. prefs 단계는 금융·영업 완료 근거가 아니다. */
@Suppress("DEPRECATION")
class SimulationNewObjectRetryTest : InstrumentationTestCase() {
    fun testOnlySavedTerminalWithFailedConsumerReplaysSameFrameAfterNewObject() {
        val context = instrumentation.targetContext
        val name = "simulation-new-object-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        val timeline = SimulationTimeline(3_605_000, 65)
        assertTrue(prefs.edit().putInt("stage", timeline.stage).putLong("elapsed", timeline.elapsedMs).commit())
        val frames = mutableListOf<DrivingFrame>()
        val first = UnifiedSimulation(context, name)
        var reopened: UnifiedSimulation? = null
        try {
            instrumentation.runOnMainSync {
                first.attach { frame, done ->
                    frames += frame
                    first.observed(frame, ContractResult.Failure(ContractError.STORAGE_FAILURE))
                    done()
                }
                assertTrue(first.canRetry)
                assertFalse(first.controlsAllowed)
                assertEquals(listOf(timeline.frame()), frames)
                first.stop()
                reopened = UnifiedSimulation(context, name).also { next ->
                    next.attach { frame, done ->
                        frames += frame
                        next.observed(frame, ContractResult.Failure(ContractError.STORAGE_FAILURE))
                        done()
                    }
                    assertTrue(next.canRetry)
                    assertFalse(next.controlsAllowed)
                    assertEquals(ContractResult.Success(timeline.clock), next.clock())
                }
                assertEquals(listOf(timeline.frame(), timeline.frame()), frames)
            }
        } finally {
            instrumentation.runOnMainSync { first.stop(); reopened?.stop() }
            prefs.edit().clear().commit() // 이 시험이 만든 UUID preference만 정리한다.
        }
    }
}
