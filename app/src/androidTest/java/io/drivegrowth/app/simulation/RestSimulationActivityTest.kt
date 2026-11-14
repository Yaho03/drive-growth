package io.drivegrowth.app.simulation

import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.showSimulationControls
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostSession
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource

/** 시연 런처의 사용자 진입과 같은 프로세스 안의 Activity·DB 재개방을 확인한다. */
@Suppress("DEPRECATION")
class RestSimulationActivityTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {
    fun testLaunchRunsAndReopensTheSeparateRestLedger() {
        val context = instrumentation.targetContext
        assertFalse("새 격리 시연 앱에서 실행합니다.", context.packageName == "io.drivegrowth.app")
        assertFalse("기존 시연 원장을 보존합니다.", context.getDatabasePath(RestSimulationActivity.DATABASE).exists())
        assertFalse(context.getDatabasePath("unified-simulation.db").exists())
        assertFalse(context.getSharedPreferences("unified-simulation-v1", 0).contains("stage"))

        val main = activity
        instrumentation.runOnMainSync {
            assertFalse(button(main.window.decorView, "rest-simulation:entry").isShown)
            main.window.decorView.showSimulationControls(main)
        }
        assertEntryHidesWhileRestricted(main)
        assertEntryHidesWhenMainStopsObservingWithoutPausing(main)
        val first = openRest(main)
        try {
            await("휴식 시연 시작") { button(first.window.decorView, "rest-simulation:run").isEnabled }
            assertFalse("화면을 열기만 하면 휴식 DB를 생성하지 않습니다.", context.getDatabasePath(RestSimulationActivity.DATABASE).exists())
            instrumentation.runOnMainSync {
                assertTrue(texts(first.window.decorView).any { it.contains("SIMULATION") && it.contains("시험 장소") })
                assertTrue(button(first.window.decorView, "rest-simulation:run").performClick())
            }
            await("원입력 처리 중 Back") {
                if (texts(first.window.decorView).none { it == "논리 주행을 기록 중입니다." }) false
                else {
                    assertFalse(button(first.window.decorView, "rest-simulation:run").isEnabled)
                    first.onBackPressed()
                    true
                }
            }
        } finally {
            instrumentation.runOnMainSync { if (!first.isFinishing) first.finish() }
        }
        val reopened = openRest(main)
        try {
            assertNotSame(first, reopened)
            await("Back 뒤 자동 실행 없이 같은 시연 재개를 제공") {
                button(reopened.window.decorView, "rest-simulation:run").let {
                    it.isEnabled && it.text.toString() == "같은 시연 재개"
                }
            }
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), readRestDemo(reader()))
            instrumentation.runOnMainSync {
                assertTrue(button(reopened.window.decorView, "rest-simulation:run").performClick())
            }
            assertResults(reopened)
            val original = readStoredResult()
            instrumentation.runOnMainSync {
                assertFalse(button(reopened.window.decorView, "rest-simulation:run").isEnabled)
                button(reopened.window.decorView, "rest-simulation:run").performClick()
            }
            assertEquals(original, readStoredResult())
        } finally {
            instrumentation.runOnMainSync { reopened.finish() }
        }
    }

    private fun assertEntryHidesWhileRestricted(main: UnifiedSimulationActivity) {
        await("모의 주차 중 휴식 진입") { button(main.window.decorView, "rest-simulation:entry").isShown }
        instrumentation.runOnMainSync {
            // 실제 SDK 없이 Debug 게이트에 제한 단계만 공급한다. 모의 주행 표본도 원장에 보내지 않는다.
            val session = MainActivity::class.java.getDeclaredField("session")
                .apply { isAccessible = true }.get(main) as HostSession
            val demo = session.simulation as UnifiedSimulation
            val progress = UnifiedSimulation::class.java.getDeclaredField("progress").apply { isAccessible = true }
            val parked = progress.get(demo) as SimulationTimeline
            val entry = button(main.window.decorView, "rest-simulation:entry")
            try {
                progress.set(demo, checkNotNull(parked.begin()))
                checkNotNull(demo.changed).invoke()
                assertEquals(View.GONE, entry.visibility)
                assertFalse(entry.isEnabled)
            } finally {
                progress.set(demo, parked)
                checkNotNull(demo.changed).invoke()
            }
            assertEquals(View.VISIBLE, entry.visibility)
            assertTrue(entry.isEnabled)
        }
    }

    private fun assertEntryHidesWhenMainStopsObservingWithoutPausing(main: UnifiedSimulationActivity) {
        val stop = MainActivity::class.java.getDeclaredMethod("stopObserving").apply { isAccessible = true }
        val start = MainActivity::class.java.getDeclaredMethod("startObserving").apply { isAccessible = true }
        instrumentation.runOnMainSync {
            val entry = button(main.window.decorView, "rest-simulation:entry")
            assertTrue(entry.isShown)
            try {
                // 최상위 Back이 쓰는 경계만 실행한다. 다중 창에서 lifecycle 콜백이 오지 않는 경우다.
                stop.invoke(main)
                assertEquals(View.GONE, entry.visibility)
                assertFalse(entry.isEnabled)
            } finally {
                start.invoke(main)
            }
        }
        await("Main이 관측을 다시 시작한 뒤 진입 복원") {
            button(main.window.decorView, "rest-simulation:entry").let { it.isShown && it.isEnabled }
        }
    }

    private fun reader() = GameSessionFactory(instrumentation.targetContext,
        mapOf(InputSource.SIMULATION to RestSimulationActivity.DATABASE), InputSource.SIMULATION)

    private fun readStoredResult(): RestDemoSaved {
        val result = readRestDemo(reader())
        assertTrue(result.toString(), result is ContractResult.Success)
        return checkNotNull((result as ContractResult.Success).value)
    }

    private fun openRest(main: UnifiedSimulationActivity): RestSimulationActivity {
        val monitor = instrumentation.addMonitor(RestSimulationActivity::class.java.name, null, false)
        try {
            await("Debug 휴식 시연 진입") {
                button(main.window.decorView, "rest-simulation:entry").let { it.isShown && it.isEnabled }
            }
            instrumentation.runOnMainSync {
                val entry = button(main.window.decorView, "rest-simulation:entry")
                assertTrue(entry.performClick())
                assertEquals(View.GONE, entry.visibility)
                assertFalse(entry.isEnabled)
                entry.performClick()
            }
            val opened = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 15_000))
            assertEquals("연속 클릭도 Activity를 한 번만 연다", 1, monitor.hits)
            return opened as RestSimulationActivity
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun assertResults(rest: RestSimulationActivity) {
        await("저장된 휴식 결과") {
            val labels = texts(rest.window.decorView)
            labels.any { it.contains("확정 주행 2건") && it.contains("부스트 사용 100초") && it.contains("정산 15 게임머니") } &&
                labels.contains("원장 잔여 1100초") && !button(rest.window.decorView, "rest-simulation:run").isEnabled
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 90_000
        var done = false
        while (!done && SystemClock.uptimeMillis() < end) {
            instrumentation.runOnMainSync { done = condition() }
            if (!done) SystemClock.sleep(50)
        }
        assertTrue(message, done)
    }

    private fun button(view: View, tag: String): Button = checkNotNull(view.findViewWithTag(tag))

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
