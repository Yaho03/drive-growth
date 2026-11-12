package io.drivegrowth.app.simulation

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.Button
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.host.driving.DrivingFrame
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.VehicleId
import java.util.UUID
import java.util.concurrent.FutureTask

@Suppress("DEPRECATION")
class UnifiedSimulationRestRecoveryTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val name = "unified-rest-recovery-${UUID.randomUUID()}"

    override fun tearDown() {
        context.getSharedPreferences(name, 0).edit().clear().commit()
        super.tearDown()
    }

    fun testOrdinaryCheckpointAfterRestKeepsItsFrameAndExplicitRetry() {
        val restTerminal = generateSequence(checkNotNull(SimulationTimeline().beginRest())) { it.next() }.last()
        val ordinary = generateSequence(checkNotNull(restTerminal.begin())) { it.next() }.drop(16).first()
        val prefs = context.getSharedPreferences(name, 0)
        assertTrue(prefs.edit().putLong("elapsed", ordinary.elapsedMs).putInt("stage", ordinary.stage)
            .putBoolean("restRecording", true).putBoolean("restRun", false).commit())
        val reopened = UnifiedSimulation(context, name)
        val frames = mutableListOf<DrivingFrame>()
        onUi {
            reopened.attach { frame, done ->
                frames += frame
                reopened.observed(frame, ContractResult.Failure(ContractError.STORAGE_FAILURE))
                done()
            }
            assertEquals(listOf(ordinary.frame()), frames)
            assertTrue(reopened.canRetry)
            reopened.retry()
            assertEquals(listOf(ordinary.frame(), ordinary.frame()), frames)
            assertEquals(ContractResult.Success(ordinary.clock), reopened.clock())
            reopened.stop()
        }
    }

    fun testLostRestInputDoesNotReplayAsOrdinaryDriving() {
        val rest = generateSequence(checkNotNull(SimulationTimeline().beginRest())) { it.next() }.drop(63).first()
        val prefs = context.getSharedPreferences(name, 0)
        assertTrue(prefs.edit().putLong("elapsed", rest.elapsedMs).putInt("stage", rest.stage)
            .putBoolean("restRecording", true).putBoolean("restRun", true).commit())
        val reopened = UnifiedSimulation(context, name)
        val before = prefs.all.toMap()
        val frames = mutableListOf<DrivingFrame>()
        onUi {
            reopened.attach { frame, _ -> frames += frame }
            reopened.retry()
            reopened.begin()
            reopened.beginRest()
            reopened.stop()
        }
        assertTrue(frames.isEmpty())
        assertFalse(reopened.controlsAllowed)
        assertFalse(reopened.canRetry)
        assertEquals(before, prefs.all)
    }

    fun testCorruptRestFlagKeepsCheckpointAndBlocksStart() {
        val prefs = context.getSharedPreferences(name, 0)
        assertTrue(prefs.edit().putInt("stage", 0).putLong("elapsed", 0)
            .putString("restRecording", "broken").commit())
        val before = prefs.all.toMap()
        val ledger = context.getDatabasePath("unified-simulation.db")
        val ledgerBefore = ledger.takeIf { it.exists() }?.readBytes()?.toList()
        val reopened = UnifiedSimulation(context, name)
        val frames = mutableListOf<DrivingFrame>()
        onUi {
            reopened.attach { frame, _ -> frames += frame }
            reopened.begin()
            reopened.beginRest()
            reopened.stop()
        }
        assertTrue(frames.isEmpty())
        assertFalse(reopened.controlsAllowed)
        assertFalse(reopened.canRetry)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), reopened.clock())
        assertEquals(before, prefs.all)
        assertEquals(ledgerBefore, ledger.takeIf { it.exists() }?.readBytes()?.toList())
    }

    fun testCorruptRestClockAllowsActualMainBackgroundAndFinishWithoutWrites() {
        assertTrue(context.packageName.startsWith("io.drivegrowth.verify."))
        val ledger = context.getDatabasePath("unified-simulation.db")
        val prefs = context.getSharedPreferences("unified-simulation-v1", 0)
        assertFalse("새 격리 프로필에서 실행합니다", ledger.exists())
        assertTrue("기존 시연 설정을 보존합니다", prefs.all.isEmpty())
        val games = GameSessionFactory(context, mapOf(InputSource.SIMULATION to "unified-simulation.db"),
            InputSource.SIMULATION)
        val onboarding = StoredOnboardingHostSource(games)
        val identity = ok(onboarding.read()).identity
        ok(onboarding.choose(identity, VehicleId("hyundai_casper")))
        val original = ledger.readBytes()
        assertTrue(prefs.edit().putInt("stage", 0).putLong("elapsed", 0)
            .putString("restRecording", "broken").commit())
        val before = prefs.all.toMap()

        val activity = instrumentation.startActivitySync(Intent(context, UnifiedSimulationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UnifiedSimulationActivity
        try {
            instrumentation.waitForIdleSync()
            onUi {
                assertFalse(activity.isFinishing)
                val start = activity.window.decorView.findViewWithTag<Button>("simulation:rest-start")
                assertTrue(start == null || start.visibility != View.VISIBLE || !start.isEnabled)
                activity.moveTaskToBack(true)
            }
            instrumentation.waitForIdleSync()
        } finally {
            onUi { activity.finish() }
        }
        val limit = SystemClock.uptimeMillis() + 10_000
        while (!onUi { activity.isDestroyed } && SystemClock.uptimeMillis() < limit) SystemClock.sleep(30)
        assertTrue("실제 Activity 종료 콜백을 통과해야 합니다", onUi { activity.isDestroyed })
        assertEquals(before, prefs.all)
        assertTrue("보존 원장이 바뀌지 않아야 합니다", original.contentEquals(ledger.readBytes()))
    }

    private fun <T> ok(result: ContractResult<T>): T = when (result) {
        is ContractResult.Success -> result.value
        is ContractResult.Failure -> error(result.toString())
    }

    private fun <T> onUi(action: () -> T): T {
        val task = FutureTask<T> { action() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
