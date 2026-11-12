package io.drivegrowth.app.simulation

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.mainPage
import io.drivegrowth.app.ui.garage.GarageView
import io.drivegrowth.app.ui.shop.ShopMode
import io.drivegrowth.app.ui.shop.ShopView
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.progression.VehicleAction
import java.util.concurrent.FutureTask

/** 공식 창에 실제 시연 Main을 띄워 가게 조작과 확인 전 차량 관리 복원을 검사한다. */
@Suppress("DEPRECATION")
class FinalOfficialWindowStateTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val database = "unified-simulation.db"
    private val target = OwnedVehicleId("initial-grant")
    private val games by lazy {
        GameSessionFactory(context, mapOf(InputSource.SIMULATION to database), InputSource.SIMULATION)
    }

    fun testHomeModesAndPreparedGarageCommandInOfficialWindow() {
        assertEquals("io.drivegrowth.verify.finalwindow28", context.packageName)
        assertFalse("시험 전용 새 설치가 필요합니다", context.getDatabasePath(database).exists())
        val timeline = SimulationTimeline()
        val preferences = context.getSharedPreferences("unified-simulation-v1", 0)
        assertTrue(preferences.edit().putInt("stage", timeline.stage).putLong("elapsed", timeline.elapsedMs).commit())
        val onboarding = StoredOnboardingHostSource(games)
        ok(onboarding.choose(ok(onboarding.read()).identity, VehicleId("hyundai_casper")))

        val display = context.getSystemService(DisplayManager::class.java).displays.single {
            it.displayId != 0 && it.name.contains("Emulator 2D Display")
        }
        val metrics = DisplayMetrics().also(display::getRealMetrics)
        val size = Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        assertTrue("공식 창 프리셋이 필요합니다: $size",
            size == Triple(840, 1184, 160) || size == Triple(1700, 1185, 200))
        var current = instrumentation.startActivitySync(
            Intent(context, UnifiedSimulationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle(),
        ) as UnifiedSimulationActivity
        try {
            assertEquals(display.displayId, ui { current.display?.displayId })
            checkHomeModes(current)
            current = checkGarageRecreation(current)
            assertEquals(display.displayId, ui { current.display?.displayId })
            assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
            Log.i(TAG, "PASS display=${display.displayId} width=${metrics.widthPixels} height=${metrics.heightPixels} dpi=${metrics.densityDpi} source=SIMULATION")
        } finally {
            ui { current.finish() }
        }
    }

    private fun checkHomeModes(activity: UnifiedSimulationActivity) {
        select(activity, HostPage.HOME)
        click(activity) { button("decoration:activate") }
        val decorationTag = "shop:preview:decoration:neutral-sign"
        await("사용 가능한 꾸미기 품목") { button(decorationTag)?.isEnabled == true }
        val before = snapshot()
        for (mode in ShopMode.entries) {
            click(activity) { button("shop:mode:$mode") }
            await("가게 보기 전환 $mode") { shop()?.uiState?.mode == mode && button("shop:mode:$mode")?.isSelected == true }
            for (label in listOf("가게 레벨 · 1 · 보유 / 공간 · 1 / 3", "확정 잔액 · 1,000 게임머니",
                "주유소", "보험 시설", "세차장", "꾸미기")) {
                expose(activity, label) { text(label) }
            }
            click(activity) { button(decorationTag) }
            expose(activity, "꾸미기 확정 버튼") { button("shop:confirm") }
            await("활성화된 꾸미기 미리보기") {
                val confirm = button("shop:confirm") ?: return@await false
                if (!reveal(confirm) || !confirm.isEnabled || shop()?.uiState?.selection == null) return@await false
                val mapBounds = Rect()
                val buttonBounds = Rect()
                assertTrue(confirm.getGlobalVisibleRect(buttonBounds))
                val map = activity.root.findViewWithTag<View>("shop:scene")
                if (map?.getGlobalVisibleRect(mapBounds) == true) {
                    assertFalse("마을 전경과 확인 버튼의 화면 영역이 겹치면 안 됩니다", Rect.intersects(mapBounds, buttonBounds))
                }
                true
            }
            click(activity) { button("shop:cancel") }
            await("선택한 보기에서 꾸미기 취소") { shop()?.uiState?.mode == mode && shop()?.uiState?.selection == null }
            assertEquals("미리보기·취소 뒤 원장 동일", before, snapshot())
            Log.i(TAG, "HOME mode=$mode summary=visible facilities=3 decoration=preview_cancel ledger=unchanged")
        }
    }

    private fun checkGarageRecreation(first: UnifiedSimulationActivity): UnifiedSimulationActivity {
        select(first, HostPage.GARAGE)
        click(first) { button(target) }
        await("차고 상세에서 같은 개체 선택") { garage()?.selectedVehicleId == target }
        val before = snapshot()
        click(first) { button(VehicleAction.LEVEL_UP) }
        expose(first, "관리 조건 조회") { text("차감 20 게임머니") }
        expose(first, "확정 전 관리 화면") { labeledButton("확정") }
        assertEquals("확정 전 원장 동일", before, snapshot())

        val monitor = instrumentation.addMonitor(UnifiedSimulationActivity::class.java.name, null, false)
        val next = try {
            ui { first.recreate() }
            checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? UnifiedSimulationActivity)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        try {
            screen = next
            assertNotSame(first, next)
            await("같은 차고에 준비 요청이 돌아옴") {
                next.root.mainPage(HostPage.GARAGE)?.isSelected == true &&
                    garage()?.selectedVehicleId == target &&
                    labeledButton("관리 조건 다시 조회")?.isEnabled == true &&
                    labeledButton("확정") == null
            }
            assertEquals("재생성 뒤 자동 확정 없음", before, snapshot())
            click(next) { labeledButton("관리 조건 다시 조회") }
            expose(next, "같은 대상의 최신 비용 재조회") { text("차감 20 게임머니") }
            click(next) { labeledButton("취소 / 화면 닫기") }
            await("같은 상세와 관리 버튼 복귀") {
                garage()?.selectedVehicleId == target && button(VehicleAction.LEVEL_UP)?.isEnabled == true
            }
            assertEquals("다시 조회·취소 뒤 원장 동일", before, snapshot())
            Log.i(TAG, "GARAGE activity=recreated selection=preserved prepared_request=revalidated ledger=unchanged")
            return next
        } catch (error: Throwable) {
            ui { next.finish() }
            throw error
        }
    }

    private fun snapshot() = ok(games.open()).use { game ->
        assertEquals(InputSource.SIMULATION, game.identity.source)
        val store = game.store
        assertNull(ok(store.hostCommands.load(game.identity.profileId)))
        listOf(game.identity, ok(store.balance()), ok(store.listVehicleHistory()),
            ok(store.listUnviewedSettlements()), ok(store.decorations.state(game.identity.profileId)))
    }

    private val UnifiedSimulationActivity.root get() = window.decorView

    private var screen: UnifiedSimulationActivity? = null

    private fun shop() = views(checkNotNull(screen).root).filterIsInstance<ShopView>().singleOrNull()
    private fun garage() = views(checkNotNull(screen).root).filterIsInstance<GarageView>().singleOrNull()
    private fun button(tag: Any) = checkNotNull(screen).root.findViewWithTag<Button>(tag)
    private fun text(label: String) = views(checkNotNull(screen).root).filterIsInstance<TextView>()
        .firstOrNull { it.text.toString() == label && it.isShown }
    private fun labeledButton(label: String) = views(checkNotNull(screen).root).filterIsInstance<Button>()
        .firstOrNull { it.text.toString() == label && it.isShown }

    private fun select(activity: UnifiedSimulationActivity, page: HostPage) {
        if (!ui { activity.root.mainPage(page)?.isSelected == true }) click(activity) { activity.root.mainPage(page) }
        await("현재 $page") { activity.root.mainPage(page)?.isSelected == true }
    }

    private fun click(activity: UnifiedSimulationActivity, find: () -> Button?) {
        screen = activity
        await("가시 영역의 활성 버튼 입력") {
            val button = find()?.takeIf { it.isEnabled } ?: return@await false
            reveal(button) && button.performClick()
        }
    }

    private fun expose(activity: UnifiedSimulationActivity, label: String, find: () -> View?) {
        screen = activity
        await(label) { find()?.let(::reveal) == true }
    }

    private fun reveal(view: View): Boolean {
        if (!view.isLaidOut || !view.isShown || view.width <= 0 || view.height <= 0) return false
        if (!visible(view)) view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        return visible(view)
    }

    private fun visible(view: View): Boolean {
        val rect = Rect()
        return view.isShown && view.width > 0 && view.height > 0 &&
            view.getLocalVisibleRect(rect) && rect.width() == view.width && rect.height() == view.height
    }

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }

    private fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        do {
            if (ui(ready)) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        fail(label)
    }

    private fun <T> ui(block: () -> T): T =
        FutureTask<T> { block() }.also(instrumentation::runOnMainSync).get()

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    private companion object {
        const val TAG = "DriveGrowthFinalWindow"
    }
}
