package io.drivegrowth.app.simulation

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.RentalProductJson
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.driving.DrivingFrame
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor

/** 실제 attach 검사를 통과한 입력을 fake consumer로 관측한다. */
@Suppress("DEPRECATION")
class SimulationTerminalProofTest : InstrumentationTestCase() {
    fun testMissingOriginalReplaysExactFrameAndStoppedAttachDoesNotEmit() {
        val context = instrumentation.targetContext
        val name = "simulation-terminal-proof-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        val terminal = SimulationTimeline(3_605_000, 65)
        val unproven = JSONObject().put("clock", RentalProductJson.clock(terminal.clock)).put("drive", "absent")
        assertTrue(prefs.edit().putInt("stage", terminal.stage).putLong("elapsed", terminal.elapsedMs)
            .putString("terminal-consumed-v2", unproven.toString()).commit())
        val tasks = ArrayDeque<Runnable>()
        val executor = Executor { tasks.addLast(it) }
        val frames = mutableListOf<DrivingFrame>()
        val simulator = UnifiedSimulation(context, name)
        val consumer: (DrivingFrame, () -> Unit) -> Unit = { frame, done ->
            frames += frame
            simulator.observed(frame, ContractResult.Failure(ContractError.DATA_UNAVAILABLE))
            done()
        }
        try {
            instrumentation.runOnMainSync {
                simulator.navigation(GameSessionFactory(context, emptyMap(), InputSource.SIMULATION), executor)
                simulator.attach(executor, consumer)
                assertFalse(simulator.controlsAllowed)
                assertTrue(frames.isEmpty())
                simulator.stop()
                simulator.attach(executor, consumer)
            }
            assertEquals(2, tasks.size)
            tasks.removeFirst().run()
            instrumentation.runOnMainSync {
                assertTrue("정지된 attach의 응답은 관측을 보낼 수 없다", frames.isEmpty())
                assertFalse(simulator.controlsAllowed)
            }
            tasks.removeFirst().run()
            instrumentation.runOnMainSync {
                assertEquals(listOf(terminal.frame()), frames)
                assertEquals(ContractResult.Success(terminal.clock), simulator.clock())
                assertTrue(simulator.canRetry)
                assertFalse(simulator.controlsAllowed)
            }
        } finally {
            instrumentation.runOnMainSync { simulator.stop() }
            prefs.edit().clear().commit()
        }
    }
}
