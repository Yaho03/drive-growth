package io.drivegrowth.app.ui.presentation

import android.animation.ValueAnimator
import android.content.Intent
import android.app.Activity
import android.app.Application
import android.os.Bundle
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
import io.drivegrowth.app.host.HostSession
import io.drivegrowth.app.host.onboarding.OnboardingHostController
import io.drivegrowth.app.mainPage
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.showSimulationControls
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.app.ui.delivery.DeliveryScreen
import io.drivegrowth.app.ui.gacha.GachaView
import io.drivegrowth.app.ui.onboarding.OnboardingView
import io.drivegrowth.app.ui.results.ResultsView
import io.drivegrowth.app.ui.shop.ShopView
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.progression.VehicleAction
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
            onUi { assertTitleCoversGame(host) }
            capture("main-title")
            touchStart(host)
            await { host.window.decorView.findViewWithTag<View>("presentation:title") == null }
            await { host.window.decorView.findViewWithTag<Button>(io.drivegrowth.app.R.string.onboarding_start)?.isEnabled == true }
            onUi {
                assertTrue(host.window.decorView.findViewWithTag<View>("presentation:home-title").isShown)
                assertTrue(host.window.decorView.findViewWithTag<View>("simulation:controls").isShown)
            }
            capture("main-game")
            lateinit var previousStart: Button
            onUi {
                previousStart = host.window.decorView.findViewWithTag(io.drivegrowth.app.R.string.onboarding_start)
                host.window.decorView.findViewWithTag<Button>("presentation:home-title").performClick()
            }
            await { host.window.decorView.findViewWithTag<Button>("presentation:start")?.isEnabled == true }
            onUi {
                assertTitleCoversGame(host)
                previousStart.performClick()
                val start = host.window.decorView.findViewWithTag<Button>("simulation:start")
                if (start != null) assertFalse(start.isEnabled)
            }
            touchStart(host, background = true)
            await { host.window.decorView.findViewWithTag<Button>(io.drivegrowth.app.R.string.onboarding_start)?.isEnabled == true }
            onUi {
                assertNull(host.window.decorView.findViewWithTag<Button>(io.drivegrowth.app.R.string.onboarding_review))
            }
        } finally { onUi { host.finish() } }
    }
    private fun assertTitleCoversGame(host: UnifiedSimulationActivity) {
        val content = host.findViewById<View>(android.R.id.content)
        val title = content.findViewWithTag<View>("presentation:title")
        assertNotNull(title)
        val appBounds = Rect()
        val titleBounds = Rect()
        assertTrue(content.getGlobalVisibleRect(appBounds))
        assertTrue(title.getGlobalVisibleRect(titleBounds))
        assertEquals("시작 화면이 앱 콘텐츠 영역 전체를 덮어야 한다", appBounds, titleBounds)
        for (tag in listOf("presentation:home-title", "presentation:balance", "presentation:sound", "simulation:controls")) {
            assertFalse("시작 화면에서 $tag 표시됨", content.findViewWithTag<View>(tag)?.isShown == true)
        }
        assertFalse(host.window.decorView.mainPage(HostPage.HOME)?.isShown == true)
    }

    fun testRefreshClosesShutterWithoutAcknowledgingGachaResult() {
        val context = instrumentation.targetContext
        checkIsolatedPackage(context.packageName)
        check(ValueAnimator.areAnimatorsEnabled())
        val games = GameSessionFactory(context, mapOf(InputSource.SIMULATION to "unified-simulation.db"), InputSource.SIMULATION)
        val host = instrumentation.startActivitySync(Intent(context, UnifiedSimulationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UnifiedSimulationActivity
        try {
            await("새 격리 프로필의 첫 안내", timeoutMillis = 30_000) {
                button(host, R.string.onboarding_start)?.isEnabled == true && host.hasWindowFocus()
            }
            for (tag in listOf(R.string.onboarding_start, VehicleId("hyundai_casper"),
                R.string.onboarding_review, R.string.onboarding_confirm)) clickOnboarding(host, tag)
            clickOnboarding(host, R.string.onboarding_to_shop)
            openPage(host, HostPage.GACHA)
            click(host, "gacha:option:paid")
            click(host, "gacha:confirm")
            await("가챠 확정 셔터") { button(host, "gacha:reveal")?.isShown == true }
            val original = ok(games.open()).use { game ->
                assertEquals(GameMoney(500), ok(game.store.balance()))
                assertEquals(2, ok(game.store.listOwnedVehicles()).size)
                checkNotNull(ok(game.store.hostCommands.load(game.identity.profileId)))
            }
            onUi {
                val refresh = checkNotNull(button(host, "host:refresh"))
                assertTrue(refresh.isEnabled && refresh.isShown)
                touchView(host, refresh)
            }
            await("새로고침 뒤 정적 원결과") { button(host, "gacha:garage")?.isEnabled == true }
            onUi { assertNull(button(host, "gacha:reveal")) }
            ok(games.open()).use { game ->
                assertEquals(original, ok(game.store.hostCommands.load(game.identity.profileId)))
                assertEquals(GameMoney(500), ok(game.store.balance()))
                assertEquals(2, ok(game.store.listOwnedVehicles()).size)
            }
        } finally { finishHost(host) }
    }

    fun testStoredFirstGrantSettlementAndGachaCelebrateOnlyOnFirstDisplay() {
        val context = instrumentation.targetContext
        checkIsolatedPackage(context.packageName)
        val games = GameSessionFactory(context, mapOf(InputSource.SIMULATION to "unified-simulation.db"), InputSource.SIMULATION)
        var host = instrumentation.startActivitySync(Intent(context, UnifiedSimulationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UnifiedSimulationActivity
        val starts = mutableListOf<RewardCelebrationKind>()
        val vehicle = VehicleId("hyundai_casper")
        try {
            await("아직 지급하지 않은 격리 프로필의 안내 화면", timeoutMillis = 30_000) {
                button(host, R.string.onboarding_start)?.isEnabled == true && host.hasWindowFocus()
            }
            onUi {
                val onboarding = descendants(host.window.decorView).filterIsInstance<OnboardingView>().single()
                val prior = onboarding.onCelebration
                onboarding.onCelebration = { starts.add(it); prior?.invoke(it) }
            }
            for (tag in listOf(R.string.onboarding_start, vehicle, R.string.onboarding_review, R.string.onboarding_confirm)) {
                clickOnboarding(host, tag)
            }
            await("첫 지급 원결과", timeoutMillis = 30_000) { button(host, R.string.onboarding_to_shop)?.isEnabled == true }
            awaitCelebration(host, RewardCelebrationKind.FIRST_GRANT, starts)
            capture("main-first-grant")
            val first = ok(games.open()).use { game ->
                assertEquals(FirstGrant.INITIAL_MONEY, ok(game.store.balance()))
                assertEquals(vehicle, ok(game.store.listOwnedVehicles()).single().vehicleId)
                ok(game.store.listUnviewedSettlements()).single().settlementId
            }
            clickOnboarding(host, R.string.onboarding_to_shop)
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
            if (ValueAnimator.areAnimatorsEnabled()) {
                await("확정 결과의 닫힌 셔터") {
                    button(host, "gacha:reveal")?.isShown == true && host.hasWindowFocus()
                }
                onUi {
                    assertNull(button(host, "gacha:garage"))
                    assertEquals(0, starts.count { it == RewardCelebrationKind.GACHA })
                }
                capture("main-gacha-shutter")
                click(host, "gacha:reveal")
                awaitCelebration(host, RewardCelebrationKind.GACHA, starts)
                SystemClock.sleep(510)
                capture("main-gacha-reveal")
            }
            await { button(host, "gacha:garage")?.isEnabled == true }
            capture("main-gacha")
            onUi {
                assertNull(button(host, "gacha:reveal"))
                assertEquals(if (ValueAnimator.areAnimatorsEnabled()) 1 else 0,
                    starts.count { it == RewardCelebrationKind.GACHA })
            }
            ok(games.open()).use { game ->
                assertEquals(GameMoney(500), ok(game.store.balance()))
                assertEquals(2, ok(game.store.listOwnedVehicles()).size)
            }
            click(host, "gacha:garage")
            await("확정 가챠 결과 확인 후 차고") {
                host.window.decorView.mainPage(HostPage.GARAGE)?.isSelected == true
            }
            host = verifyMapManagement(host, games)
            verifyDeliveryDeparture(host, games)
            val sound = "presentation:sound"
            onUi { assertEquals("소리 꺼짐", (button(host, sound) as? Button)?.text.toString()) }
            click(host, sound)
            onUi { assertEquals("소리 켜짐", (button(host, sound) as? Button)?.text.toString()) }
            click(host, sound)
            onUi {
                assertEquals("소리 꺼짐", (button(host, sound) as? Button)?.text.toString())
                host.window.decorView.showSimulationControls()
            }
            click(host, "simulation:start")
            await("모의주행 조작 제한") {
                host.window.decorView.findViewWithTag<View>("simulation:blocked") != null &&
                    descendants(host.window.decorView).none { it is RewardCelebrationView } &&
                    button(host, sound)?.isShown == false &&
                    host.window.decorView.findViewWithTag<View>("delivery:highway") == null
            }
            await("모의주행 후 확정 수익 탁송 도착", timeoutMillis = 100_000) {
                host.window.decorView.findViewWithTag<View>("delivery:scene:INCOME_ARRIVAL")?.isShown == true
            }
            capture("main-delivery-arrival")
            ok(games.open()).use { game ->
                val delivered = ok(game.store.listUnviewedSettlements()).filter { it.transaction.kind == TransactionKind.DELIVERY }
                assertTrue(delivered.single().transaction.money?.amount?.units?.let { it > 0 } == true)
                assertNull(ok(game.store.delivery.inProgress()))
            }
            click(host, "delivery:skip-motion")
            click(host, "delivery:acknowledge")
            await("탁송 결과 다시 표시하지 않음") {
                button(host, "delivery:acknowledge") == null &&
                    host.window.decorView.findViewWithTag<View>("delivery:scene:INCOME_ARRIVAL") == null
            }
        } finally { finishHost(host) }
    }

    private fun finishHost(host: UnifiedSimulationActivity) {
        onUi { host.finishAndRemoveTask() }
        await("계측 Activity 종료") { host.isDestroyed }
    }

    private fun verifyMapManagement(original: UnifiedSimulationActivity, games: GameSessionFactory): UnifiedSimulationActivity {
        var host: UnifiedSimulationActivity
        val initial = ok(games.open()).use { game -> ok(game.store.listOwnedVehicles()).single { it.acquisitionCost.units == 0L } }
        val mapTag = "shop:map-vehicle:${initial.ownedVehicleId.value}"
        openPage(original, HostPage.HOME)
        click(original, mapTag)
        await("HOME 지도 차량 관리") { button(original, VehicleAction.LEVEL_UP)?.isEnabled == true }
        capture("main-map-focus")
        click(original, VehicleAction.LEVEL_UP)
        await("관리 비용 검토") { visibleTextButton(original, "확정")?.isEnabled == true }
        capture("main-map-review")
        host = recreate(original)
        await("재생성 후 HOME 차량 선택") {
            host.window.decorView.findViewWithTag<View>("shop:focus-panel") != null
        }
        clickText(host, "관리 조건 다시 조회")
        await("새 개체 revision으로 관리 비용 검토") { visibleTextButton(host, "확정")?.isEnabled == true }
        onUi { host.onBackPressed() }
        await("Back으로 관리 닫고 차량 지도에 머묾") {
            button(host, "shop:focus-back")?.isEnabled == true && visibleTextButton(host, "확정") == null
        }
        ok(games.open()).use { game ->
            assertEquals(initial, ok(game.store.listOwnedVehicles()).single { it.ownedVehicleId == initial.ownedVehicleId })
            assertEquals(GameMoney(500), ok(game.store.balance()))
        }
        click(host, VehicleAction.LEVEL_UP)
        clickText(host, "확정")
        await("레벨업 확정 원결과") { visibleTextButton(host, "결과 확인")?.isEnabled == true }
        host = recreate(host)
        clickText(host, "보관 요청 처리 · 결과 확인")
        clickText(host, "결과 확인")
        await("재생성된 관리 원결과 확인 뒤 HOME 재조회") { button(host, VehicleAction.SELL)?.isEnabled == true }
        ok(games.open()).use { game ->
            assertEquals(2, ok(game.store.listOwnedVehicles()).single { it.ownedVehicleId == initial.ownedVehicleId }.level)
            assertEquals(GameMoney(480), ok(game.store.balance()))
        }
        click(host, VehicleAction.SELL)
        clickText(host, "확정")
        clickText(host, "결과 확인")
        await("처분한 차량의 지도 선택 해제") {
            button(host, mapTag) == null &&
                host.window.decorView.findViewWithTag<View>("shop:focus-panel") == null &&
                descendants(host.window.decorView).any { it.tag?.toString()?.startsWith("shop:map-vehicle:") == true }
        }
        ok(games.open()).use { game ->
            assertTrue(ok(game.store.listOwnedVehicles()).none { it.ownedVehicleId == initial.ownedVehicleId })
            val sold = ok(game.store.listUnviewedSettlements()).single {
                it.changedVehicle?.let { car -> car.ownedVehicleId == initial.ownedVehicleId && car.lifecycle == VehicleLifecycle.SOLD } == true
            }
            assertEquals(TransactionKind.GARAGE, sold.transaction.kind)
            assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
        }
        capture("main-map-after-sale")
        return host
    }

    private fun recreate(old: UnifiedSimulationActivity): UnifiedSimulationActivity {
        var replacement: UnifiedSimulationActivity? = null
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (activity is UnifiedSimulationActivity && activity !== old) replacement = activity
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        onUi {
            old.application.registerActivityLifecycleCallbacks(callbacks)
            old.recreate()
        }
        try {
            await("Activity 재생성") { replacement != null }
            return checkNotNull(replacement)
        } finally { onUi { old.application.unregisterActivityLifecycleCallbacks(callbacks) } }
    }

    private fun verifyDeliveryDeparture(host: UnifiedSimulationActivity, games: GameSessionFactory) {
        openPage(host, HostPage.DELIVERY)
        val delivery = (host.onRetainNonConfigurationInstance() as HostSession).delivery
        await("첫 시연 목적지 확인") {
            delivery.state?.screen is DeliveryScreen.Destination &&
                button(host, "simulation:destination")?.takeIf { it.isEnabled && it.isShown }?.performClick() == true
        }
        var lastOfferState = ""
        await("저장된 수익 탁송 후보 열기", diagnostic = { lastOfferState }) {
            val current = delivery.state ?: return@await false
            lastOfferState = current.screen.javaClass.simpleName + " discoveryPending=" + (current.displayedProposal != null)
            if (current.displayedProposal != null || delivery.isSavingDisplayedOffers) return@await false
            val proposal = (current.screen as? DeliveryScreen.Offers)?.proposal ?: return@await false
            val income = proposal.proposed.offers.filterIsInstance<DeliveryOffer.Income>()
                .firstOrNull { proposal.availability[it.id]?.allowed == true } ?: return@await false
            button(host, "delivery:offer:${income.id.value}")?.takeIf { it.isEnabled && it.isShown }?.performClick() == true
        }
        click(host, "delivery:confirm")
        await("확정 수익 탁송 출발") {
            host.window.decorView.findViewWithTag<View>("delivery:scene:DEPARTURE")?.isShown == true
        }
        capture("main-delivery-departure")
        onUi { host.onBackPressed() }
        await("출발 연출 닫고 고속도로 표시") {
            host.window.decorView.findViewWithTag<View>("delivery:highway")?.isShown == true
        }
        click(host, "delivery:review-abandon")
        await("포기 검토") { button(host, "delivery:abandon")?.isEnabled == true }
        onUi { host.onBackPressed() }
        await("포기 검토 취소 후 원 의뢰") {
            button(host, "delivery:abandon") == null && button(host, "delivery:review-abandon")?.isEnabled == true
        }
        ok(games.open()).use { game ->
            assertTrue(ok(game.store.delivery.inProgress())?.active?.offer is DeliveryOffer.Income)
        }
        await("탁송 선택 원명령 확인 후 진행 상태") {
            host.window.decorView.findViewWithTag<View>("delivery:highway") != null
        }
        ok(games.open()).use { game ->
            assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
        }
        capture("main-delivery-highway")
    }

    private fun clickText(host: UnifiedSimulationActivity, text: String) {
        await("버튼 $text") {
            visibleTextButton(host, text)?.takeIf { it.isEnabled }?.performClick() == true
        }
    }

    private fun visibleTextButton(host: UnifiedSimulationActivity, text: String): Button? =
        descendants(host.window.decorView).filterIsInstance<Button>().firstOrNull { it.isShown && it.text.toString() == text }

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
        await("메뉴 $page") {
            val destination = host.window.decorView.mainPage(page)
            if (destination?.isEnabled != true || !destination.isShown) false
            else { host.window.decorView.openMainPage(page); true }
        }
    }

    private fun clickOnboarding(host: UnifiedSimulationActivity, tag: Any) {
        // A retained main refresh can briefly be in flight while the prior button is still drawn.
        val controller = (host.onRetainNonConfigurationInstance() as HostSession).onboarding
        val inFlight = OnboardingHostController::class.java.getDeclaredField("inFlight").apply { isAccessible = true }
        await("첫 안내 버튼 $tag", timeoutMillis = 30_000) {
            !inFlight.getBoolean(controller) &&
                button(host, tag)?.takeIf { it.isEnabled && it.isShown }?.performClick() == true
        }
    }

    private fun click(host: UnifiedSimulationActivity, tag: Any) {
        await("버튼 $tag") {
            button(host, tag)?.takeIf { it.isEnabled && it.isShown }?.performClick() == true
        }
    }

    private fun button(host: UnifiedSimulationActivity, tag: Any): View? = host.window.decorView.findViewWithTag(tag)

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

    private fun touchStart(host: UnifiedSimulationActivity, background: Boolean = false) = onUi {
        val button = host.window.decorView.findViewWithTag<Button>("presentation:start")
        val decor = host.window.decorView
        val target = if (background) decor.findViewWithTag<View>("presentation:title") else button
        val point = IntArray(2).also(target::getLocationInWindow)
        val x = point[0] + if (background) 2f else button.width / 2f
        val y = point[1] + if (background) 2f else button.height / 2f
        if (background) {
            val buttonOrigin = IntArray(2).also(button::getLocationInWindow)
            assertFalse(Rect(buttonOrigin[0], buttonOrigin[1],
                buttonOrigin[0] + button.width, buttonOrigin[1] + button.height).contains(x.toInt(), y.toInt()))
        }
        dispatchTap(decor, x, y)
    }

    private fun touchView(host: UnifiedSimulationActivity, view: View) {
        val point = IntArray(2).also(view::getLocationInWindow)
        dispatchTap(host.window.decorView, point[0] + view.width / 2f, point[1] + view.height / 2f)
    }

    private fun dispatchTap(decor: View, x: Float, y: Float) {
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
    private fun await(label: String = "기대 상태", timeoutMillis: Long = 15_000,
        diagnostic: () -> String = { "" }, condition: () -> Boolean,
    ) {
        val end = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < end) {
            var matched = false
            onUi { matched = condition() }
            if (matched) return
            SystemClock.sleep(100)
        }
        fail("실제 Main 화면에서 $label 확인 실패 ${diagnostic()}".trimEnd())
    }
    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
}
