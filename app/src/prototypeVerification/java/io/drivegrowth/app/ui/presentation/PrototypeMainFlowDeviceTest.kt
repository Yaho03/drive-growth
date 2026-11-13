package io.drivegrowth.app.ui.presentation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import io.drivegrowth.app.R
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.mainPage
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.showSimulationControls
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.app.ui.gacha.GachaView
import io.drivegrowth.app.ui.onboarding.OnboardingView
import io.drivegrowth.app.ui.results.ResultsView
import io.drivegrowth.app.ui.shop.ShopView
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.garage.FirstGrant
import java.io.File

/** 격리 시연 APK에서만 실행한다. 제품 패키지의 저장 데이터는 사용하지 않는다. */
@Suppress("DEPRECATION")
class PrototypeMainFlowDeviceTest : InstrumentationTestCase() {
    fun testLauncherTitleStartsExistingGameAndCanReopen() {
        checkIsolatedPackage(instrumentation.targetContext.packageName)
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            UnifiedSimulationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)) as UnifiedSimulationActivity
        try {
            await { host.window.decorView.findViewWithTag<Button>("presentation:start")?.isEnabled == true && host.hasWindowFocus() }
            capture("main-title")
            touchStart(host)
            await { host.window.decorView.findViewWithTag<View>("presentation:title") == null }
            await { host.window.decorView.findViewWithTag<Button>(io.drivegrowth.app.R.string.onboarding_start)?.isEnabled == true }
            capture("main-game")
            lateinit var previousStart: Button
            onUi {
                previousStart = host.window.decorView.findViewWithTag(io.drivegrowth.app.R.string.onboarding_start)
                host.window.decorView.findViewWithTag<Button>("presentation:home-title").performClick()
            }
            await { host.window.decorView.findViewWithTag<Button>("presentation:start")?.isEnabled == true }
            onUi {
                previousStart.performClick()
                val start = host.window.decorView.findViewWithTag<Button>("simulation:start")
                if (start != null) assertFalse(start.isEnabled)
                host.window.decorView.findViewWithTag<Button>("presentation:start").performClick()
            }
            await { host.window.decorView.findViewWithTag<Button>(io.drivegrowth.app.R.string.onboarding_start)?.isEnabled == true }
            onUi {
                assertNull(host.window.decorView.findViewWithTag<Button>(io.drivegrowth.app.R.string.onboarding_review))
            }
        } finally { onUi { host.finish() } }
    }
    fun testStoredFirstGrantSettlementAndGachaCelebrateOnlyOnFirstDisplay() {
        val context = instrumentation.targetContext
        checkIsolatedPackage(context.packageName)
        val games = GameSessionFactory(context, mapOf(InputSource.SIMULATION to "unified-simulation.db"), InputSource.SIMULATION)
        val host = instrumentation.startActivitySync(Intent(context, UnifiedSimulationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UnifiedSimulationActivity
        val starts = mutableListOf<RewardCelebrationKind>()
        val vehicle = VehicleId("hyundai_casper")
        try {
            await("아직 지급하지 않은 격리 프로필의 안내 화면") {
                button(host, R.string.onboarding_start)?.isEnabled == true && host.hasWindowFocus()
            }
            onUi {
                val onboarding = descendants(host.window.decorView).filterIsInstance<OnboardingView>().single()
                val prior = onboarding.onCelebration
                onboarding.onCelebration = { starts.add(it); prior?.invoke(it) }
            }
            click(host, R.string.onboarding_start)
            click(host, vehicle)
            click(host, R.string.onboarding_review)
            click(host, R.string.onboarding_confirm)
            await { button(host, R.string.onboarding_to_shop)?.isEnabled == true }
            awaitCelebration(host, RewardCelebrationKind.FIRST_GRANT, starts)
            capture("main-first-grant")
            val first = ok(games.open()).use { game ->
                assertEquals(FirstGrant.INITIAL_MONEY, ok(game.store.balance()))
                assertEquals(vehicle, ok(game.store.listOwnedVehicles()).single().vehicleId)
                ok(game.store.listUnviewedSettlements()).single().settlementId
            }
            click(host, R.string.onboarding_to_shop)
            await { descendants(host.window.decorView).any { it is ShopView } }
            openPage(host, HostPage.RESULTS)
            onUi {
                val results = descendants(host.window.decorView).filterIsInstance<ResultsView>().single()
                val prior = results.onCelebration
                results.onCelebration = { starts.add(it); prior?.invoke(it) }
            }
            click(host, "results:open:${first.value}")
            awaitCelebration(host, RewardCelebrationKind.SETTLEMENT, starts)
            capture("main-settlement")
            click(host, "results:close")
            click(host, "results:open:${first.value}")
            drawCelebration(host)
            onUi { assertEquals(1, starts.count { it == RewardCelebrationKind.SETTLEMENT }) }

            openPage(host, HostPage.GACHA)
            onUi {
                val gacha = descendants(host.window.decorView).filterIsInstance<GachaView>().single()
                val prior = gacha.onCelebration
                gacha.onCelebration = { starts.add(it); prior?.invoke(it) }
            }
            click(host, "gacha:option:paid")
            click(host, "gacha:confirm")
            await { button(host, "gacha:garage")?.isEnabled == true }
            awaitCelebration(host, RewardCelebrationKind.GACHA, starts)
            capture("main-gacha")
            drawCelebration(host)
            onUi { assertEquals(1, starts.count { it == RewardCelebrationKind.GACHA }) }
            ok(games.open()).use { game ->
                assertEquals(GameMoney(500), ok(game.store.balance()))
                assertEquals(2, ok(game.store.listOwnedVehicles()).size)
            }
            click(host, "gacha:garage")
            await("확정 가챠 결과 확인 후 차고") {
                host.window.decorView.mainPage(HostPage.GARAGE)?.isSelected == true
            }
            val sound = "presentation:sound"
            onUi { assertEquals("소리 꺼짐", button(host, sound)?.text.toString()) }
            click(host, sound)
            onUi { assertEquals("소리 켜짐", button(host, sound)?.text.toString()) }
            click(host, sound)
            onUi {
                assertEquals("소리 꺼짐", button(host, sound)?.text.toString())
                host.window.decorView.showSimulationControls()
            }
            click(host, "simulation:start")
            await("모의주행 조작 제한") {
                host.window.decorView.findViewWithTag<View>("simulation:blocked") != null &&
                    descendants(host.window.decorView).none { it is RewardCelebrationView } &&
                    button(host, sound)?.isShown == false
            }
        } finally { onUi { host.finish() } }
    }

    private fun awaitCelebration(host: UnifiedSimulationActivity, kind: RewardCelebrationKind, starts: List<RewardCelebrationKind>) {
        await("확정 뒤 연출 표시 $kind") {
            val effect = descendants(host.window.decorView).filterIsInstance<RewardCelebrationView>().singleOrNull()
            effect?.requestRectangleOnScreen(Rect(0, 0, effect.width, effect.height), true)
            starts.count { it == kind } == 1
        }
    }

    private fun drawCelebration(host: UnifiedSimulationActivity) {
        instrumentation.waitForIdleSync()
        onUi {
            val effect = descendants(host.window.decorView).filterIsInstance<RewardCelebrationView>().single()
            assertTrue(effect.isShown && effect.hasWindowFocus())
            assertTrue(effect.width > 0 && effect.height > 0)
            effect.requestRectangleOnScreen(Rect(0, 0, effect.width, effect.height), true)
            val bitmap = Bitmap.createBitmap(effect.width, effect.height, Bitmap.Config.ARGB_8888)
            try { effect.draw(android.graphics.Canvas(bitmap)) } finally { bitmap.recycle() }
        }
    }

    private fun openPage(host: UnifiedSimulationActivity, page: HostPage) {
        await("메뉴 $page") { host.window.decorView.mainPage(page)?.isEnabled == true }
        onUi { host.window.decorView.openMainPage(page) }
    }

    private fun click(host: UnifiedSimulationActivity, tag: Any) {
        await("버튼 $tag") { button(host, tag)?.isEnabled == true }
        onUi { assertTrue(checkNotNull(button(host, tag)).performClick()) }
    }

    private fun button(host: UnifiedSimulationActivity, tag: Any): Button? = host.window.decorView.findViewWithTag(tag)

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun <T> ok(result: ContractResult<T>): T = when (result) {
        is ContractResult.Success -> result.value
        is ContractResult.Failure -> error(result.toString())
    }

    private fun checkIsolatedPackage(packageName: String) {
        check(Regex("""io\.drivegrowth\.verify\.prototype(\.[a-z][a-z0-9]*)?""").matches(packageName))
    }

    private fun touchStart(host: UnifiedSimulationActivity) = onUi {
        val button = host.window.decorView.findViewWithTag<Button>("presentation:start")
        val decor = host.window.decorView
        val point = IntArray(2).also(button::getLocationInWindow)
        val x = point[0] + button.width / 2f
        val y = point[1] + button.height / 2f
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { assertTrue(decor.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    private fun await(label: String = "기대 상태", condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) {
            var matched = false
            onUi { matched = condition() }
            if (matched) return
            SystemClock.sleep(100)
        }
        fail("실제 Main 화면에서 $label 확인 실패")
    }
    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
}
