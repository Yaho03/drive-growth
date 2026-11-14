package io.drivegrowth.app.simulation

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.host.HostSession
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

    fun testCollapsedMainShowsRecoverableInputFailureAndRetriesTheSameFrame() {
        assertTrue(context.packageName.startsWith("io.drivegrowth.verify."))
        assertFalse(context.getDatabasePath("unified-simulation.db").exists())
        assertTrue(context.getSharedPreferences("unified-simulation-v1", 0).all.isEmpty())
        val activity = instrumentation.startActivitySync(Intent(context, UnifiedSimulationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UnifiedSimulationActivity
        try {
            instrumentation.waitForIdleSync()
            onUi {
                val field = MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
                val simulation = (field.get(activity) as HostSession).simulation as UnifiedSimulation
                val frames = mutableListOf<DrivingFrame>()
                // Save failure is supplied at the existing input callback. Main still renders real simulator state.
                simulation.attach { frame, done ->
                    frames += frame
                    simulation.observed(frame, ContractResult.Failure(ContractError.STORAGE_FAILURE))
                    done()
                }
                val root = activity.window.decorView
                assertFalse(root.findViewWithTag<Button>("simulation:controls").isSelected)
                assertFalse(root.findViewWithTag<TextView>("simulation:status").isShown)
                val restricted = checkNotNull(root.findViewWithTag<View>("host:restricted-screen"))
                assertEquals("진행이 중단됐어요. 설정에서 다시 시도해 주세요.",
                    restricted.findViewWithTag<TextView>("simulation:blocked").text.toString())
                assertNull(root.findViewWithTag<View>("host:destination"))
                assertNull(root.findViewWithTag<View>("shop:scene"))
                val retry = root.findViewWithTag<Button>("simulation:retry")
                assertFalse(retry.isShown)
                val recovery = checkNotNull(root.findViewWithTag<Button>("host:simulation-recovery-settings"))
                assertTrue(recovery.performClick())
                val settings = checkNotNull(root.findViewWithTag<View>("host:settings-panel"))
                val items = checkNotNull(settings.findViewWithTag<View>("host:settings-items"))
                assertNotNull(settings.findViewWithTag<View>("host:settings-close"))
                assertNull(items.findViewWithTag<View>("presentation:sound"))
                assertTrue(items.findViewWithTag<Button>("host:simulation-settings").performClick())
                assertNull(root.findViewWithTag<View>("host:settings-panel"))
                assertTrue(retry.isShown && retry.isEnabled)
                assertFalse(root.findViewWithTag<Button>("simulation:start").isShown)
                assertTrue(retry.performClick())
                assertSame(restricted, root.findViewWithTag<View>("host:restricted-screen"))
                assertSame(recovery, restricted.findViewWithTag<View>("host:simulation-recovery-settings"))
                assertEquals(2, frames.size)
                assertEquals(frames.first(), frames.last())
                activity.onBackPressed()
                assertFalse(root.findViewWithTag<Button>("simulation:controls").isShown)
            }
        } finally {
            onUi { activity.finish() }
        }
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
                val root = activity.window.decorView
                assertEquals("진행 상황을 확인할 수 없어요. 기존 저장 기록은 그대로예요.",
                    root.findViewWithTag<TextView>("simulation:blocked").text.toString())
                assertFalse(root.findViewWithTag<Button>("simulation:retry").isShown)
                val start = root.findViewWithTag<Button>("simulation:rest-start")
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
