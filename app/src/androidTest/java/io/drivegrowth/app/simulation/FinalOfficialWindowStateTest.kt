package io.drivegrowth.app.simulation

import io.drivegrowth.app.isMainPageShown
import io.drivegrowth.app.exposeMainPage
import io.drivegrowth.app.exposeMainRefresh
import io.drivegrowth.app.openMainPage
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
import io.drivegrowth.app.R
import io.drivegrowth.app.data.HostCheckpointPhase
import io.drivegrowth.app.data.HostCommandCheckpoint
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.host.garage.CheckpointGarageCommandAdapter
import io.drivegrowth.app.host.garage.GaragePreparation
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.ui.garage.CollectionView
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.app.ui.garage.GarageView
import io.drivegrowth.app.ui.shop.ShopMode
import io.drivegrowth.app.ui.shop.ShopView
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.rental.Facility
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
        openOfficialWindow("io.drivegrowth.verify.finalwindow28") { current ->
            checkHomeModes(current)
            checkGarageRecreation(current)
        }
    }

    fun testCollectionReloadAndSavedRequestSurviveActivityRecreation() {
        openOfficialWindow("io.drivegrowth.verify.collectionwindow28", ::checkCollectionRecreation)
    }

    private fun openOfficialWindow(packageName: String,
        exercise: (UnifiedSimulationActivity) -> UnifiedSimulationActivity) {
        assertEquals(packageName, context.packageName)
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
            current = exercise(current)
            assertEquals(display.displayId, ui { current.display?.displayId })
            assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
            Log.i(TAG, "PASS display=${display.displayId} width=${metrics.widthPixels} height=${metrics.heightPixels} dpi=${metrics.densityDpi} source=SIMULATION")
        } finally {
            ui { current.finish() }
        }
    }

    private fun checkCollectionRecreation(first: UnifiedSimulationActivity): UnifiedSimulationActivity {
        val adapter = CheckpointGarageCommandAdapter(context, sessions = games)
        val vehicle = ok(games.open()).use { ok(it.store.listOwnedVehicles()).single() }
        val saved = ok(adapter.prepare(
            GarageEvent.RequestAction(vehicle.ownedVehicleId, vehicle.revision, VehicleAction.LEVEL_UP),
            RequestId("official-window-saved-level"),
        )) as GaragePreparation.Ready
        ok(adapter.save(saved.pending))
        click(first) { first.root.exposeMainRefresh() }
        select(first, HostPage.COLLECTION)
        await("도감에 보유와 미발견 표시, 저장 요청 진입") {
            collectionCount(R.string.garage_ever_owned) == 1 &&
                collectionCount(R.string.garage_undiscovered) > 0 &&
                labeledButton("보관한 차량 관리 요청 확인")?.isEnabled == true
        }
        assertEquals(0, ui { collectionCount(R.string.garage_discovered) })

        // 보유 여부와 분리되는 발견 기록만 기존 원장 API로 추가한다.
        val discovery = CollectionKey(VehicleId("hyundai_avante"), Rarity.COMMON)
        ok(games.open()).use { game ->
            ok(game.store.recordDiscoveries(ok(game.content).catalog, setOf(discovery)))
        }
        val before = collectionSnapshot()
        assertEquals(HostCheckpointPhase.SAVED, before.checkpoint?.phase)
        assertEquals(setOf(discovery), before.discoveries)
        val monitor = instrumentation.addMonitor(UnifiedSimulationActivity::class.java.name, null, false)
        val next = try {
            await("도감 새로고침이 실제 Loading으로 전환된 시점에 재생성") {
                val refresh = first.root.exposeMainRefresh() ?: return@await false
                if (!reveal(refresh)) return@await false
                assertTrue(refresh.performClick())
                // 공용 조회 응답이 메인 큐로 돌아오기 전에 도감으로 복귀해 Loading 상태를 검증한다.
                first.root.openMainPage(HostPage.COLLECTION)
                assertEquals(1, collectionCount(R.string.garage_collection_loading))
                first.recreate()
                true
            }
            checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? UnifiedSimulationActivity)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        try {
            screen = next
            assertNotSame(first, next)
            await("도감 페이지·보유·공용 대기 요청 복원") {
                next.root.isMainPageShown(HostPage.COLLECTION) &&
                    collectionCount(R.string.garage_ever_owned) == 1 &&
                    collectionCount(R.string.garage_undiscovered) > 0 &&
                    labeledButton("보관한 차량 관리 요청 확인")?.isEnabled == true
            }
            click(next) { button("collection:filter:DISCOVERED") }
            await("발견 필터에 별도 차종과 상태 표시") {
                collectionCount(R.string.garage_discovered) == 1 &&
                    collectionCount(R.string.garage_undiscovered) > 0
            }
            click(next) { next.root.exposeMainRefresh() }
            await("도감 재조회 뒤 발견 필터와 보관 요청 보존") {
                collectionCount(R.string.garage_discovered) == 1 &&
                    labeledButton("보관한 차량 관리 요청 확인")?.isEnabled == true
            }
            assertEquals("도감 재조회·재생성 뒤 원장과 저장 요청 동일", before, collectionSnapshot())
            click(next) { labeledButton("보관한 차량 관리 요청 확인") }
            expose(next, "원 관리 요청의 결과 확인 진입") { labeledButton("보관 요청 처리 · 결과 확인") }
            assertEquals("저장 요청을 열기만 하면 원장 불변", before, collectionSnapshot())
            val restored = checkNotNull(ok(adapter.load()))
            assertEquals(saved.pending.command, restored.command)
            assertEquals(saved.pending.request, restored.request)
            Log.i(TAG, "COLLECTION activity=recreated page=restored loading=requested discoveries=1 owned=1 saved_request=preserved ledger=unchanged")
            return next
        } catch (error: Throwable) {
            ui { next.finish() }
            throw error
        }
    }

    private data class CollectionSnapshot(
        val identity: GameIdentity,
        val balance: GameMoney,
        val vehicles: List<OwnedVehicle>,
        val unviewed: List<Settlement>,
        val discoveries: Set<CollectionKey>,
        val checkpoint: HostCommandCheckpoint?,
    )

    private fun collectionSnapshot() = ok(games.open()).use { game ->
        val store = game.store
        assertEquals(InputSource.SIMULATION, game.identity.source)
        CollectionSnapshot(game.identity, ok(store.balance()), ok(store.listVehicleHistory()),
            ok(store.listUnviewedSettlements()), ok(store.discoveredVehicles()),
            ok(store.hostCommands.load(game.identity.profileId)))
    }

    private fun collection() = views(checkNotNull(screen).root).filterIsInstance<CollectionView>().singleOrNull()

    private fun collectionCount(label: Int) = collection()?.let { view ->
        views(view).filterIsInstance<TextView>().count { it.isShown && it.text.toString() == context.getString(label) }
    } ?: 0

    private fun checkHomeModes(activity: UnifiedSimulationActivity) {
        select(activity, HostPage.HOME)
        click(activity) { button("shop:map-facilities") }
        click(activity) { button("decoration:activate") }
        val decorationTag = "shop:map-preview:decoration:neutral-sign"
        await("꾸미기 기능이 연결된 가게의 지도와 보조 메뉴") {
            shop()?.let { it.findViewWithTag<Button>("shop:map-decorations") != null ||
                it.findViewWithTag<Button>("shop:map-facilities") != null } == true
        }
        val before = snapshot()
        for (mode in ShopMode.entries) {
            if (!ui { button("shop:mode:$mode")?.isShown == true }) click(activity) { button("shop:map-facilities") }
            click(activity) { button("shop:mode:$mode") }
            await("가게 보기 전환 $mode") { shop()?.uiState?.mode == mode && button("shop:mode:$mode")?.isSelected == true }
            for (facility in Facility.entries) {
                expose(activity, "시설 메뉴 ${facility.name}") { button("shop:menu-facility:${facility.name}") }
            }
            click(activity) { button("shop:map-sheet-close") }
            for (label in listOf("가게 Lv1", "P 1 / 3", "● 1,000")) expose(activity, label) { text(label) }

            click(activity) { button("shop:map-facilities") }
            click(activity) { button("shop:map-decorations") }
            click(activity) { button(decorationTag) }
            await("활성화된 꾸미기 확인창이 지도보다 앞에 있고 버튼이 가시 영역에 들어온다") {
                val confirm = button("shop:map-confirm") ?: return@await false
                if (!reveal(confirm) || !confirm.isEnabled || shop()?.uiState?.selection == null) return@await false
                val overlay = activity.root.findViewWithTag<ViewGroup>("shop:map-overlay") ?: return@await false
                val card = overlay.getChildAt(0) ?: return@await false
                val cardBounds = Rect()
                val buttonBounds = Rect()
                val map = activity.root.findViewWithTag<View>("shop:scene") ?: return@await false
                if (!card.getGlobalVisibleRect(cardBounds) || !confirm.getGlobalVisibleRect(buttonBounds)) return@await false
                assertTrue("확정 버튼 전체가 확인창 안에 있습니다", cardBounds.contains(buttonBounds))
                assertEquals("확인창 뒤 지도의 접근성 입력을 막습니다",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, map.importantForAccessibility)
                true
            }
            click(activity) { button("shop:map-close") }
            await("선택한 보기에서 꾸미기 취소") { shop()?.uiState?.mode == mode && shop()?.uiState?.selection == null }
            click(activity) { button("shop:map-close") }
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
                next.root.isMainPageShown(HostPage.GARAGE) &&
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
        if (!ui { activity.root.isMainPageShown(page) }) click(activity) { activity.root.exposeMainPage(page) }
        await("현재 $page") { activity.root.isMainPageShown(page) }
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
