package io.drivegrowth.app.simulation

import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import java.util.UUID

/** 명시 모의 실패 입력의 adapter 시험. 실제 SDK·금융 성공으로 표시하지 않는다. */
@Suppress("DEPRECATION")
class SimulationRecoveryTest : InstrumentationTestCase() {
    fun testFailedFramePinsClockAndRetriesExactlySameInputWithoutReset() {
        val context=instrumentation.targetContext
        val name="simulation-recovery-${UUID.randomUUID()}"
        val received=mutableListOf<VehicleObservation>()
        val simulator=UnifiedSimulation(context,name)
        try {
            instrumentation.runOnMainSync {
                simulator.attach { frame,done ->
                    received+=frame.observation
                    simulator.observed(frame,ContractResult.Failure(if(received.size==1) ContractError.DATA_UNAVAILABLE else ContractError.STORAGE_FAILURE))
                    done()
                }
                assertTrue(simulator.controlsAllowed)
                simulator.begin()
                assertEquals(2,received.size)
                assertTrue(simulator.canRetry)
                assertFalse(simulator.controlsAllowed)
                val first=simulator.clock()
                simulator.retry()
                assertEquals(3,received.size)
                assertEquals(received[1],received[2])
                assertEquals(first,simulator.clock())
                assertTrue(simulator.canRetry)
                simulator.stop()
                val reopened=UnifiedSimulation(context,name)
                assertEquals(first,reopened.clock())
                reopened.stop()
            }
        } finally { context.getSharedPreferences(name,0).edit().clear().commit() }
    }
}
