package io.drivegrowth.app.ui.presentation

import android.animation.ValueAnimator
import android.content.Intent
import android.app.Activity
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
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
import io.drivegrowth.app.isMainPageShown
import io.drivegrowth.app.mainNavigation
import io.drivegrowth.app.exposeMainPage
import io.drivegrowth.app.openMainSettings
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.showSimulationControls
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.app.ui.delivery.DeliveryScreen
import io.drivegrowth.app.ui.delivery.DeliveryView
import io.drivegrowth.app.ui.delivery.DeliveryPreviewStates
import io.drivegrowth.app.ui.gacha.GachaView
import io.drivegrowth.app.ui.gacha.GachaEvent
import io.drivegrowth.app.ui.gacha.GachaPreviewStates
import io.drivegrowth.app.ui.gacha.GachaScreen
import io.drivegrowth.app.ui.gacha.GachaOrigin
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.app.ui.onboarding.OnboardingView
import io.drivegrowth.app.ui.results.ResultsView
import io.drivegrowth.app.ui.shop.ShopView
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.garage.FirstGrant
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 격리 시연 APK에서만 실행한다. 제품 패키지의 저장 데이터는 사용하지 않는다. */
@Suppress("DEPRECATION")
class PrototypeMainFlowDeviceTest : InstrumentationTestCase() {
    fun testCaptureRareLegendAndMissingDestinationFromFixedInputs() {
        val context = instrumentation.targetContext
        checkIsolatedPackage(context.packageName)
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        val host = instrumentation.startActivitySync(Intent(context, CollectionScrollTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
        try {
            val delivery = DeliveryView(host, {})
            onUi {
                host.setContentView(delivery)
                delivery.render(DeliveryPreviewStates.offers, true)
                delivery.findViewWithTag<Button>("delivery:tab:purchase").performClick()
            }
            capture(host, "fixture-delivery-purchase-offers")
            onUi { delivery.findViewWithTag<Button>("delivery:offer:offer-1").performClick() }
            capture(host, "fixture-delivery-purchase-confirm")
            onUi { delivery.render(DeliveryPreviewStates.missing, true) }
            capture(host, "fixture-delivery-missing")
            onUi { delivery.render(DeliveryPreviewStates.failure.copy(version = 4), true) }
            capture(host, "fixture-delivery-failure")

            assertTrue("켜진 Android 애니메이션으로 공개 타이밍 확인", ValueAnimator.areAnimatorsEnabled())
            for (rarity in listOf(Rarity.RARE, Rarity.LEGEND)) {
                val name = rarity.name.lowercase()
                val actions = mutableListOf<GachaEvent>()
                lateinit var view: GachaView
                var waitAudio: Pair<Rarity, Boolean>? = null
                onUi {
                    view = GachaView(host, actions::add).apply {
                        onGachaAudio = { rank, opening -> waitAudio = rank to opening }
                    }
                    host.setContentView(view)
                }
                await("포커스를 얻은 $name 시험 창") {
                    view.hasWindowFocus() && view.isShown && view.isLaidOut
                }
                instrumentation.waitForIdleSync()
                onUi {
                    view.render(GachaPreviewStates.ready(), true)
                    checkNotNull(view.findViewWithTag<Button>("gacha:option:paid")).performClick()
                    checkNotNull(view.findViewWithTag<Button>("gacha:confirm")).performClick()
                    val frame = GachaPreviewStates.result()
                    val result = (frame.screen as GachaScreen.Result).value
                    val vehicle = checkNotNull(result.settlement.changedVehicle).copy(rarity = rarity)
                    view.render(frame.copy(screen = GachaScreen.Result(result.copy(
                        settlement = result.settlement.copy(changedVehicle = vehicle), origin = GachaOrigin.STORED,
                    ))), true)
                }
                await("$name 셔터 대기 종료", diagnostic = {
                    "focus=${view.hasWindowFocus()} shown=${view.isShown} audio=$waitAudio actions=${actions.size}"
                }) { view.findViewWithTag<Button>("gacha:reveal")?.isEnabled == true }
                capture(host, "fixture-gacha-$name-wait")
                onUi {
                    val reveal = checkNotNull(view.findViewWithTag<Button>("gacha:reveal"))
                    val point = IntArray(2).also(reveal::getLocationInWindow)
                    dispatchTap(host.window.decorView, point[0] + reveal.width / 2f, point[1] + reveal.height / 2f)
                }
                await("$name 셔터 공개 결과") { view.findViewWithTag<Button>("gacha:garage")?.isEnabled == true }
                capture(host, "fixture-gacha-$name-reveal")
                onUi { assertEquals(1, actions.filterIsInstance<GachaEvent.Draw>().size) }
            }
        } finally {
            onUi { host.finishAndRemoveTask() }
        }
    }

    fun testLauncherTitleStartsExistingGameAndCanReopen() {
        checkIsolatedPackage(instrumentation.targetContext.packageName)
        val host = launchActivity(Intent(instrumentation.targetContext,
            UnifiedSimulationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        try {
            await("포커스를 받은 런처 시작 버튼", timeoutMillis = 30_000) {
                host.window.decorView.findViewWithTag<Button>("presentation:start")?.isEnabled == true && host.hasWindowFocus()
            }
            onUi {
                assertTitleCoversGame(host)
                assertEquals("소리 꺼짐", (button(host, "presentation:title-sound") as Button).text.toString())
                assertFalse("타이틀 진입은 기본 음소거", titleMusicPlaying(host))
            }
            click(host, "presentation:title-sound")
            try {
                await("타이틀 BGM이 Android 음악 플레이어에서 시작") { titleMusicPlaying(host) }
                capture(host, "main-title")
            } finally { click(host, "presentation:title-sound") }
            await("타이틀 터치 음소거 뒤 플레이어 중지") { !titleMusicPlaying(host) }
            touchStart(host)
            await { host.window.decorView.findViewWithTag<View>("presentation:title") == null }
            await { host.window.decorView.findViewWithTag<Button>(io.drivegrowth.app.R.string.onboarding_start)?.isEnabled == true }
            onUi {
                assertTrue(host.window.decorView.findViewWithTag<View>("presentation:home-title").isShown)
                assertFalse(host.window.decorView.mainNavigation()?.isShown == true)
            }
            capture(host, "main-game")
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
    private fun titleMusicPlaying(host: UnifiedSimulationActivity): Boolean {
        val manager = host.getSystemService(android.media.AudioManager::class.java)
        return manager.isMusicActive
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
        assertFalse(host.window.decorView.mainNavigation()?.isShown == true)
    }

    fun testSkipClosesShutterWithoutAcknowledgingGachaResult() {
        val context = instrumentation.targetContext
        checkIsolatedPackage(context.packageName)
        check(ValueAnimator.areAnimatorsEnabled())
        val games = GameSessionFactory(context, mapOf(InputSource.SIMULATION to "unified-simulation.db"), InputSource.SIMULATION)
        val host = launchActivity(Intent(context, UnifiedSimulationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await("새 격리 프로필의 첫 안내", timeoutMillis = 30_000) {
                button(host, R.string.onboarding_start)?.isEnabled == true
            }
            for (tag in listOf(R.string.onboarding_start, VehicleId("hyundai_casper"),
                R.string.onboarding_review, R.string.onboarding_confirm)) clickOnboarding(host, tag)
            clickOnboarding(host, R.string.onboarding_to_shop)
            openPage(host, HostPage.GACHA)
            await("뽑기 선택 표시") { button(host, "gacha:option:paid")?.isEnabled == true }
            capture(host, "main-gacha-options")
            click(host, "gacha:option:paid")
            await("뽑기 확인 표시") { button(host, "gacha:confirm")?.isEnabled == true }
            capture(host, "main-gacha-confirm")
            click(host, "gacha:confirm")
            await("가챠 확정 셔터") { button(host, "gacha:reveal")?.isShown == true && host.hasWindowFocus() }
            val original = ok(games.open()).use { game ->
                assertEquals(GameMoney(500), ok(game.store.balance()))
                assertEquals(2, ok(game.store.listOwnedVehicles()).size)
                checkNotNull(ok(game.store.hostCommands.load(game.identity.profileId)))
            }
            onUi {
                val skip = checkNotNull(button(host, "gacha:skip-reveal"))
                assertTrue(skip.isEnabled && skip.isShown)
                touchView(host, skip)
            }
            await("건너뛰기 뒤 정적 원결과") { button(host, "gacha:garage")?.isEnabled == true }
            onUi { assertNull(button(host, "gacha:reveal")) }
            ok(games.open()).use { game ->
                assertEquals(original, ok(game.store.hostCommands.load(game.identity.profileId)))
                assertEquals(GameMoney(500), ok(game.store.balance()))
                assertEquals(2, ok(game.store.listOwnedVehicles()).size)
            }
            onUi { touchView(host, checkNotNull(button(host, "host:refresh"))) }
            await("다음 새로고침은 기존 원결과 확인과 후보 재조회") {
                button(host, "gacha:option:paid")?.isEnabled == true
            }
            ok(games.open()).use { game ->
                assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
                assertEquals(GameMoney(500), ok(game.store.balance()))
                assertEquals(2, ok(game.store.listOwnedVehicles()).size)
            }
        } finally { finishHost(host) }
    }

    fun testStoredFirstGrantSettlementAndGachaCelebrateOnlyOnFirstDisplay() {
        val context = instrumentation.targetContext
        checkIsolatedPackage(context.packageName)
        val games = GameSessionFactory(context, mapOf(InputSource.SIMULATION to "unified-simulation.db"), InputSource.SIMULATION)
        var host = launchActivity(Intent(context, UnifiedSimulationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
            clickOnboarding(host, R.string.onboarding_start)
            await("첫 차량 후보 표시") { button(host, vehicle)?.isEnabled == true }
            capture(host, "main-first-choosing")
            clickOnboarding(host, vehicle)
            clickOnboarding(host, R.string.onboarding_review)
            clickOnboarding(host, R.string.onboarding_confirm)
            await("첫 지급 원결과", timeoutMillis = 30_000) { button(host, R.string.onboarding_to_shop)?.isEnabled == true }
            awaitCelebration(host, RewardCelebrationKind.FIRST_GRANT, starts)
            capture(host, "main-first-grant")
            val first = ok(games.open()).use { game ->
                assertEquals(FirstGrant.INITIAL_MONEY, ok(game.store.balance()))
                assertEquals(vehicle, ok(game.store.listOwnedVehicles()).single().vehicleId)
                ok(game.store.listUnviewedSettlements()).single().settlementId
            }
            clickOnboarding(host, R.string.onboarding_to_shop)
            await("가게 지도와 캐스퍼 조회 완료") {
                val root = host.window.decorView
                root.findViewWithTag<View>("shop:scene")?.isShown == true &&
                    root.findViewWithTag<Button>("shop:map-settings")?.isShown == true &&
                    descendants(root).any { it.isShown && it.tag?.toString()?.startsWith("shop:map-vehicle:") == true }
            }
            capture(host, "main-home")
            verifyMenuTouches(host)
            verifyRestStopPreview(host, games)
            verifyRestPreparationDoesNotStartAfterBack(host)
            onUi { assertNotNull(host.window.decorView.openMainSettings()) }
            val sound = "presentation:sound"
            onUi { assertEquals("게임 소리 켜기", (button(host, sound) as? Button)?.text.toString()) }
            click(host, sound)
            onUi { assertEquals("게임 소리 끄기", (button(host, sound) as? Button)?.text.toString()) }
            click(host, sound)
            click(host, "host:settings-close")
            openPage(host, HostPage.RESULTS)
            onUi {
                val results = descendants(host.window.decorView).filterIsInstance<ResultsView>().single()
                val prior = results.onCelebration
                results.onCelebration = { starts.add(it); prior?.invoke(it) }
            }
            click(host, "results:open:${first.value}")
            awaitCelebration(host, RewardCelebrationKind.SETTLEMENT, starts)
            capture(host, "main-settlement")
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
            await("뽑기 선택 표시") { button(host, "gacha:option:paid")?.isEnabled == true }
            capture(host, "main-gacha-options")
            click(host, "gacha:option:paid")
            await("뽑기 확인 표시") { button(host, "gacha:confirm")?.isEnabled == true }
            capture(host, "main-gacha-confirm")
            click(host, "gacha:confirm")
            if (ValueAnimator.areAnimatorsEnabled()) {
                await("확정 결과의 닫힌 셔터") {
                    button(host, "gacha:reveal")?.isShown == true && host.hasWindowFocus()
                }
                onUi {
                    assertNull(button(host, "gacha:garage"))
                    assertEquals(0, starts.count { it == RewardCelebrationKind.GACHA })
                }
                capture(host, "main-gacha-shutter")
                click(host, "gacha:reveal")
                awaitCelebration(host, RewardCelebrationKind.GACHA, starts)
                SystemClock.sleep(510)
                capture(host, "main-gacha-reveal")
            }
            await { button(host, "gacha:garage")?.isEnabled == true }
            capture(host, "main-gacha")
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
                host.window.decorView.isMainPageShown(HostPage.GARAGE)
            }
            host = verifyMapManagement(host, games)
            verifyDeliveryDeparture(host, games)
            verifyDriveProgress(host)
            // 탁송을 떠나지 않고 모의주행 경계를 검증한다.
            onUi { host.window.decorView.showSimulationControls(host) }
            click(host, "simulation:start")
            await("모의주행 조작 제한") {
                host.window.decorView.findViewWithTag<View>("simulation:blocked") != null &&
                    descendants(host.window.decorView).none { it is RewardCelebrationView } &&
                    button(host, sound)?.isShown != true &&
                    button(host, "drive-progress:bar")?.isShown != true &&
                    button(host, "drive-progress:chip")?.isShown != true &&
                    host.window.decorView.findViewWithTag<View>("delivery:highway") == null
            }
            await("모의주행 후 확정 수익 탁송 도착", timeoutMillis = 100_000) {
                host.window.decorView.findViewWithTag<View>("delivery:scene:INCOME_ARRIVAL")?.isShown == true
            }
            capture(host, "main-delivery-arrival")
            ok(games.open()).use { game ->
                val delivered = ok(game.store.listUnviewedSettlements()).filter { it.transaction.kind == TransactionKind.DELIVERY }
                assertTrue(delivered.single().transaction.money?.amount?.units?.let { it > 0 } == true)
                assertNull(ok(game.store.delivery.inProgress()))
            }
            val canAnimate = ValueAnimator.areAnimatorsEnabled()
            if (canAnimate) {
                // 장면 진입 직후와 별도로 도장·동전·잔액 변화와 하단 확인을 영상에 남긴다.
                SystemClock.sleep(2_200)
                capture(host, "main-delivery-arrival-finished")
            }
            click(host, "delivery:skip-motion")
            click(host, "delivery:acknowledge")
            await("탁송 결과 다시 표시하지 않음") {
                button(host, "delivery:acknowledge") == null &&
                    host.window.decorView.findViewWithTag<View>("delivery:scene:INCOME_ARRIVAL") == null
            }
        } finally { finishHost(host) }
    }

    private fun verifyRestStopPreview(host: UnifiedSimulationActivity, games: GameSessionFactory) {
        val root = host.window.decorView
        val scene = "rest-stop:scene"
        val preview = "simulation:rest-scene"
        val before = ok(games.open()).use { ok(it.store.balance()) }
        onUi { root.showSimulationControls(host) }
        await("미리보기 버튼에 키보드 포커스") {
            button(host, preview)?.takeIf { it.isShown }?.requestFocusFromTouch() == true
        }
        click(host, preview)
        await("휴게소 미리보기") { root.findViewWithTag<View>(scene)?.isShown == true }
        capture(host, "main-rest-preview")
        if (ValueAnimator.areAnimatorsEnabled()) click(host, "rest-stop:skip")
        await("미리보기 닫기 포커스") { button(host, "rest-stop:close")?.isFocused == true }
        val display = host.display?.displayId ?: 0
        val commands = listOf("keyevent KEYCODE_TAB", "keycombination KEYCODE_SHIFT_LEFT KEYCODE_TAB", "keyevent KEYCODE_TAB")
        for (command in commands) {
            val result = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand("input -d $display $command")
            val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(result).bufferedReader().use { it.readText() }
            assertTrue("실제 화면에 키 이벤트 전달: $output", output.isBlank())
            instrumentation.waitForIdleSync()
            onUi {
                val overlay = checkNotNull(root.findViewWithTag<View>(scene))
                assertTrue("Tab 포커스는 휴게소 미리보기 안에 머무른다",
                    descendants(overlay).any { it.isFocused })
            }
        }
        capture(host, "main-rest-preview-loop")
        click(host, "rest-stop:close")
        await("미리보기 닫기 뒤 원래 도구 포커스") { button(host, preview)?.isFocused == true }
        click(host, preview)
        await("휴게소 미리보기 재진입") { root.findViewWithTag<View>(scene)?.isShown == true }
        onUi { host.onBackPressed() }
        await("Back으로 미리보기만 닫히고 모의 도구 유지") {
            root.findViewWithTag<View>(scene) == null && button(host, preview)?.isShown == true
        }
        onUi { host.onBackPressed() }
        assertEquals(before, ok(games.open()).use { ok(it.store.balance()) })
    }

    private fun verifyMenuTouches(host: UnifiedSimulationActivity) {
        fun tapVisible(tag: String) {
            var tapped = false
            await("실제 창의 터치 대상 $tag") {
                val target = button(host, tag)?.takeIf { it.isShown && it.isEnabled } ?: return@await false
                val bounds = Rect()
                if (!host.hasWindowFocus() || !target.isLaidOut || !target.getGlobalVisibleRect(bounds) || bounds.isEmpty) {
                    return@await false
                }
                touchView(host, target)
                tapped = true
                true
            }
            assertTrue(tapped)
        }

        await("홈의 메뉴와 설정 버튼 배치") {
            host.window.decorView.mainNavigation()?.isLaidOut == true &&
                button(host, "shop:map-settings")?.isLaidOut == true
        }
        instrumentation.waitForIdleSync()
        tapVisible("shop:map-settings")
        await("설정에서 하단 메뉴 숨김", diagnostic = {
            "설정=${button(host, "host:settings-close")?.isShown}, 하단 메뉴=${host.window.decorView.mainNavigation()?.isShown}"
        }) {
            button(host, "host:settings-close")?.isShown == true &&
                host.window.decorView.mainNavigation()?.isShown != true
        }
        tapVisible("host:settings-close")
        val pages = listOf(HostPage.GARAGE, HostPage.COLLECTION, HostPage.GACHA, HostPage.DELIVERY,
            HostPage.RESULTS, HostPage.RANKING)
        for (page in pages) {
            tapVisible("host:page:${page.name}")
            await("터치로 이동한 $page") {
                host.window.decorView.isMainPageShown(page) &&
                    button(host, "host:back")?.isLaidOut == true &&
                    button(host, "host:refresh")?.isLaidOut == true
            }
            onUi {
                val root = host.window.decorView
                assertTrue("일반 기능의 픽셀 창이 보여야 합니다", root.findViewWithTag<View>("host:feature-window")?.isShown == true)
                assertFalse("일반 기능에는 접힌 모의 입력 버튼이 없어야 합니다",
                    button(host, "simulation:controls")?.isShown == true)
            }
            onUi {
                assertNull(button(host, "host:page-settings"))
                val title = host.window.decorView.findViewWithTag<View>("host:current-page:${page.name}")
                assertTrue(title.isShown)
                val refresh = checkNotNull(button(host, "host:refresh"))
                val close = checkNotNull(button(host, "host:back"))
                assertEquals(refresh.measuredHeight, close.measuredHeight)
                assertEquals(refresh.paddingTop, close.paddingTop)
                assertEquals(refresh.paddingStart, close.paddingStart)
                assertTrue("닫기 터치 면적은 56dp 이상", close.measuredHeight >= (56 * close.resources.displayMetrics.density).toInt())
            }
            tapVisible("host:back")
            await("닫기 터치 뒤 HOME") { host.window.decorView.isMainPageShown(HostPage.HOME) }
        }
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
        await("HOME 지도 차량 상세") { button(original, "map:vehicle-manage")?.isEnabled == true }
        capture(original, "main-map-focus")
        click(original, "map:vehicle-manage")
        click(original, VehicleAction.LEVEL_UP)
        await("관리 비용 검토") { visibleTextButton(original, "확정")?.isEnabled == true }
        capture(original, "main-map-review")
        host = recreate(original)
        await("재생성 후 HOME 차량 선택") {
            host.window.decorView.findViewWithTag<View>("shop:focus-panel") != null
        }
        clickText(host, "관리 조건 다시 조회")
        await("새 개체 revision으로 관리 비용 검토") { visibleTextButton(host, "확정")?.isEnabled == true }
        onUi { host.onBackPressed() }
        await("Back으로 관리 닫고 차량 지도에 머묾") {
            button(host, "map:vehicle-close")?.isEnabled == true && visibleTextButton(host, "확정") == null
        }
        ok(games.open()).use { game ->
            assertEquals(initial, ok(game.store.listOwnedVehicles()).single { it.ownedVehicleId == initial.ownedVehicleId })
            assertEquals(GameMoney(500), ok(game.store.balance()))
        }
        click(host, "map:vehicle-manage")
        click(host, VehicleAction.LEVEL_UP)
        clickText(host, "확정")
        await("레벨업 확정 원결과") { visibleTextButton(host, "결과 확인")?.isEnabled == true }
        host = recreate(host)
        clickText(host, "보관 요청 처리 · 결과 확인")
        clickText(host, "결과 확인")
        await("재생성된 관리 원결과 확인 뒤 HOME 재조회") { button(host, "map:vehicle-manage")?.isEnabled == true }
        ok(games.open()).use { game ->
            assertEquals(2, ok(game.store.listOwnedVehicles()).single { it.ownedVehicleId == initial.ownedVehicleId }.level)
            assertEquals(GameMoney(480), ok(game.store.balance()))
        }
        click(host, "map:vehicle-manage")
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
        capture(host, "main-map-after-sale")
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

    private fun verifyRestPreparationDoesNotStartAfterBack(host: UnifiedSimulationActivity) {
        val session = host.onRetainNonConfigurationInstance() as HostSession
        val simulation = checkNotNull(session.simulation)
        val initialClock = simulation.clock()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val prepared = CountDownLatch(1)
        onUi { host.window.decorView.showSimulationControls(host) }
        await("휴식 포함 모의주행 준비 버튼") { button(host, "simulation:rest-start")?.isEnabled == true }
        session.expansionWorker {
            entered.countDown()
            release.await()
        }
        try {
            assertTrue("원장 worker가 확인을 지연 중", entered.await(5, TimeUnit.SECONDS))
            click(host, "simulation:rest-start")
            onUi {
                assertNull(host.window.decorView.mainNavigation())
                host.onBackPressed()
                assertTrue(button(host, "simulation:controls")?.isShown == false)
                assertNotNull(host.window.decorView.mainNavigation())
            }
            session.expansionWorker { prepared.countDown() }
        } finally {
            release.countDown()
        }
        assertTrue("준비 작업이 끝나고 콜백이 게시됨", prepared.await(5, TimeUnit.SECONDS))
        onUi {
            assertEquals(initialClock, simulation.clock())
            assertFalse(simulation.restRecording)
            assertNotNull(host.window.decorView.mainNavigation())
            assertTrue(button(host, "simulation:controls")?.isShown == false)
        }
    }

    private fun verifyDeliveryDeparture(host: UnifiedSimulationActivity, games: GameSessionFactory) {
        openPage(host, HostPage.DELIVERY)
        val delivery = (host.onRetainNonConfigurationInstance() as HostSession).delivery
        await("첫 시연 목적지 확인") {
            delivery.state?.screen is DeliveryScreen.Destination &&
                button(host, "simulation:destination")?.let { it.isEnabled && it.isShown } == true
        }
        capture(host, "main-delivery-destination")
        click(host, "simulation:destination")
        var lastOfferState = ""
        var offerTag = ""
        await("저장된 수익 탁송 후보 열기", diagnostic = { lastOfferState }) {
            val current = delivery.state ?: return@await false
            lastOfferState = current.screen.javaClass.simpleName + " discoveryPending=" + (current.displayedProposal != null)
            if (delivery.isSavingDisplayedOffers) return@await false
            val proposal = (current.screen as? DeliveryScreen.Offers)?.proposal ?: return@await false
            val income = proposal.proposed.offers.filterIsInstance<DeliveryOffer.Income>()
                .firstOrNull { proposal.availability[it.id]?.allowed == true } ?: return@await false
            offerTag = "delivery:offer:${income.id.value}"
            button(host, offerTag)?.let { it.isEnabled && it.isShown } == true
        }
        capture(host, "main-delivery-offers")
        click(host, offerTag)
        await("수익 탁송 수락과 후보 복귀 확인") {
            button(host, "delivery:confirm")?.let { it.isShown && it.isEnabled } == true &&
                button(host, "delivery:back")?.isShown == true
        }
        capture(host, "main-delivery-confirm")
        click(host, "delivery:confirm")
        await("확정 수익 탁송 출발") {
            host.window.decorView.findViewWithTag<View>("delivery:scene:DEPARTURE")?.isShown == true
        }
        capture(host, "main-delivery-departure")
        // 영상과 정지 프레임에는 출발 띠가 들어온 뒤의 실제 장면도 남긴다.
        SystemClock.sleep(2_500)
        capture(host, "main-delivery-departure-banner")
        onUi { host.onBackPressed() }
        await("출발 연출 닫고 고속도로 표시") {
            host.window.decorView.findViewWithTag<View>("delivery:highway")?.isShown == true &&
                host.window.decorView.findViewWithTag<View>("host:feature-window")?.isShown == true
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
        capture(host, "main-delivery-highway")
        // 통과 단언과 별도로, 여러 장식 차량이 진행하는 연속 영상을 확보할 시간이다.
        SystemClock.sleep(16_000)
        capture(host, "main-delivery-highway-traffic")
    }

    private fun verifyDriveProgress(host: UnifiedSimulationActivity) {
        val barTag = "drive-progress:bar"
        val chipTag = "drive-progress:chip"
        fun barShown() = button(host, barTag)?.isShown == true
        fun chipShown() = button(host, chipTag)?.isShown == true
        openPage(host, HostPage.DELIVERY)
        await("탁송 창의 진행바 중복 방지") { !barShown() && !chipShown() }
        val session = host.onRetainNonConfigurationInstance() as HostSession
        await("미결 명령이 없는 진행 의뢰 조회") {
            val read = session.delivery.state
            read != null && read.pending == null && read.screen is DeliveryScreen.Active &&
                host.window.decorView.findViewWithTag<View>("delivery:highway")?.isShown == true
        }
        openPage(host, HostPage.HOME)
        val hostFields = listOf("deliveryFrame", "hiddenDriveVersion", "mapModalOpen", "managementVisible",
            "homeVehicleManagement", "navigation", "simulationControlsExpanded", "homeSettings",
            "presentationFocused", "titlePending", "collapsedDriveKey", "active").associateWith {
            io.drivegrowth.app.MainActivity::class.java.getDeclaredField(it).apply { isAccessible = true }
        }
        val main = io.drivegrowth.app.MainActivity::class.java
        val visibleProgress = main.getDeclaredMethod("visibleDriveProgress").apply { isAccessible = true }
        var progressDiagnostic = ""
        await("HOME의 마지막 탁송 조회 표시", diagnostic = { progressDiagnostic }) {
            val read = session.delivery.state
            progressDiagnostic = "조회=${read?.screen?.javaClass?.simpleName} 저장중=${read?.pending != null}" +
                " 동일프로필=${read?.identity == session.onboarding.uiState.identity}" +
                " 포커스=${host.hasWindowFocus()} 표시=${button(host, barTag)?.visibility}" +
                " 모델=${visibleProgress.invoke(host) != null} 출처=${(read?.screen as? DeliveryScreen.Active)
                    ?.delivery?.active?.proposed?.estimated?.source}/${read?.identity?.source}" +
                " 설명=${button(host, barTag)?.contentDescription}" +
                " " + hostFields.entries.joinToString(" ") { (name, field) ->
                    val value = field.get(host)
                    val summary = when (value) {
                        is io.drivegrowth.app.ui.delivery.DeliveryFrame ->
                            "${value.version}:${value.screen.javaClass.simpleName}:${value.screen == read?.screen}"
                        is io.drivegrowth.app.host.HostNavigation -> value.page.name
                        else -> value
                    }
                    "$name=$summary"
                }
            barShown() && button(host, barTag)?.contentDescription?.contains("마지막 탁송 조회") == true
        }
        capture(host, "main-drive-progress")
        for (page in listOf(HostPage.GARAGE, HostPage.GACHA, HostPage.RANKING)) {
            openPage(host, page)
            await("$page 화면 하단에 같은 마지막 탁송 조회 표시") {
                barShown() && button(host, barTag)?.contentDescription?.contains("마지막 탁송 조회") == true
            }
            capture(host, "main-drive-progress-${page.name.lowercase()}")
        }
        openPage(host, HostPage.HOME)
        await("지도 복귀 뒤 진행바") { barShown() }
        onUi { assertTrue(checkNotNull(button(host, barTag)).performLongClick()) }
        await("탁송 진행바를 접으면 차량 버튼 표시") { !barShown() && chipShown() }
        onUi { assertNotNull(host.window.decorView.openMainSettings()) }
        await("설정은 진행바와 차량 버튼을 모두 숨김") { !barShown() && !chipShown() }
        click(host, "host:settings-close")
        await("설정 닫기 뒤 같은 의뢰의 접힘 유지") { chipShown() }
        click(host, chipTag)
        await("차량 버튼으로 진행바 복원") { barShown() && !chipShown() }
        click(host, barTag)
        await("진행바로 탁송 열기 및 중복 표시 방지") {
            host.window.decorView.isMainPageShown(HostPage.DELIVERY) && !barShown() && !chipShown()
        }
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
            val root = host.window.decorView
            if (root.isMainPageShown(page)) return@await true
            if (page == HostPage.HOME) root.openMainPage(page)
            else root.exposeMainPage(page)?.performClick()
            root.isMainPageShown(page)
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

    private fun launchActivity(intent: Intent): UnifiedSimulationActivity {
        val displays = instrumentation.targetContext.getSystemService(DisplayManager::class.java)
        val display = displays.displays.singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        val host = instrumentation.startActivitySync(intent, options) as UnifiedSimulationActivity
        if (display != null) assertEquals(display.displayId, host.display?.displayId)
        return host
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

    private fun capture(host: Activity, name: String) {
        instrumentation.waitForIdleSync()
        val done = java.util.concurrent.CountDownLatch(1)
        var result = android.view.PixelCopy.ERROR_UNKNOWN
        lateinit var screenshot: Bitmap
        onUi {
            val decor = host.window.decorView
            check(decor.isShown && decor.width > 0 && decor.height > 0)
            screenshot = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(host.window, screenshot, {
                result = it
                done.countDown()
            }, android.os.Handler(host.mainLooper))
        }
        try {
            assertTrue("실제 앱 창 캡처 응답", done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("실제 앱 창 캡처 $name", android.view.PixelCopy.SUCCESS, result)
            File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            screenshot.recycle()
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
