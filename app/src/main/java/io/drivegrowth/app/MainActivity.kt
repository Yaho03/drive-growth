package io.drivegrowth.app

import android.app.Activity
import io.drivegrowth.app.ui.rest.RestStopMotionView
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.Typeface
import android.widget.ImageView
import android.view.Gravity
import io.drivegrowth.app.ui.presentation.PrototypeTitleView
import io.drivegrowth.app.ui.presentation.PrototypeAudio
import io.drivegrowth.app.ui.presentation.RewardCelebrationKind
import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.app.host.delivery.DeliveryHostReceipt
import io.drivegrowth.core.delivery.DeliveryOutcome
import io.drivegrowth.core.delivery.HoldOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import io.drivegrowth.app.host.garage.GarageCommandHost
import io.drivegrowth.app.host.garage.GarageCommandState
import io.drivegrowth.app.host.garage.GarageCommandView
import io.drivegrowth.app.host.garage.GarageActionAvailability
import io.drivegrowth.app.host.facility.FacilityHostMapper
import io.drivegrowth.app.ui.shop.ShopScreenState
import io.drivegrowth.app.ui.shop.ShopView
import io.drivegrowth.app.ui.shop.ShopEvent
import io.drivegrowth.app.ui.shop.FacilityControlsEvent
import io.drivegrowth.app.ui.shop.ShopMode
import io.drivegrowth.app.ui.shop.ShopUiState
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.app.ui.gacha.GachaView
import io.drivegrowth.app.ui.gacha.GachaEvent
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GarageHostMapper
import io.drivegrowth.app.host.HostNavigation
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.host.HostQueryController
import io.drivegrowth.app.host.HostQueryState
import io.drivegrowth.app.host.onboarding.OnboardingStateBundle
import io.drivegrowth.app.host.ranking.RankingState
import io.drivegrowth.app.host.ranking.RankingMetric
import io.drivegrowth.app.ui.ranking.RankingView
import io.drivegrowth.app.ui.ranking.RankingMode
import io.drivegrowth.app.ui.ranking.RankingUiState
import io.drivegrowth.app.host.HostSession
import io.drivegrowth.app.host.SimulationInputState
import io.drivegrowth.app.host.ResultsHostMapper
import io.drivegrowth.app.host.ResultsStateBundle
import io.drivegrowth.app.ui.results.ResultsEvent
import io.drivegrowth.app.ui.results.ResultsScope
import io.drivegrowth.app.ui.results.ResultsView
import io.drivegrowth.app.ui.onboarding.OnboardingEvent
import io.drivegrowth.app.ui.onboarding.OnboardingScreenState
import io.drivegrowth.app.ui.onboarding.OnboardingView
import io.drivegrowth.app.ui.garage.CollectionScreenState
import io.drivegrowth.app.ui.garage.CollectionView
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.app.ui.garage.GarageScreenState
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.garage.GarageView
import io.drivegrowth.app.runtime.rental.product.RentalAppExit
import io.drivegrowth.app.vehicle.ForegroundInteraction
import io.drivegrowth.app.vehicle.VehicleInput
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.ProfileId
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.VehicleInteraction
import io.drivegrowth.core.progression.VehicleAction

open class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val interaction = ForegroundInteraction()
    private var input: VehicleInput? = null
    private var drivingStatus: String? = null
    private var rentalFingerprint: Any? = null
    private val rentalAppExit = RentalAppExit()
    private var navigation = HostNavigation()
    private var screen: HostQueryState = HostQueryState.Restricted
    private lateinit var management: GarageCommandHost
    private lateinit var managementView: GarageCommandView
    private var managementState: GarageCommandState = GarageCommandState.Idle
    private var recoveredManagement = false
    private var managementVisible = false
    private var homeVehicleManagement = false
    private lateinit var facilityControls: io.drivegrowth.app.ui.shop.FacilityControlsView
    private var pendingPage: HostPage? = null
    private var pendingRouteGeneration = 0L
    private var pendingRouteFailed = false
    private var pendingRouteEnabled = false
    private var failedShopRestore: Bundle? = null
    private val shopRestoreFailed get() = failedShopRestore != null
    private lateinit var shop: ShopView
    private lateinit var progression: io.drivegrowth.app.ui.progress.ProgressView
    private var progressionFrame: io.drivegrowth.app.host.progression.ProgressionFrame? = null
    private var lastProgressionScreen: io.drivegrowth.app.ui.progress.ProgressScreenState? = null
    private var lastProgressionAccess: Boolean? = null
    private var progressionRestoreFailed = false
    private lateinit var ranking: RankingView
    private var rankingState: RankingState = RankingState.Restricted
    private lateinit var delivery: DeliveryView
    private var deliveryAccessEnabled = false
    private var restStartTicket: Any? = null
    private val simulationStartBoundary = SimulationStartBoundary()
    private var deliveryReceipt: DeliveryHostReceipt? = null
    private var deliveryFrame = DeliveryFrame(0, DeliveryScreen.Loading)
    private lateinit var driveProgressBar: DriveProgressBarView
    private lateinit var driveProgressChip: DriveProgressChipView
    private var collapsedDriveKey: DriveProgress.Key? = null
    private var hiddenDriveVersion = -1L
    private lateinit var gacha: GachaView
    private var navigationBar: View? = null
    private var mapModalOpen = false
    private var homeSettings: FrameLayout? = null
    private var homeSoundButton: Button? = null
    private var simulationControlsExpanded = false
    private var simulationControls: View? = null
    private var restStopPreview: RestStopMotionView? = null
    private var restStopPreviousFocus: View? = null
    private var restAreaScene: io.drivegrowth.app.ui.rest.RestAreaSceneView? = null
    private var simulationToggle: Button? = null
    private var simulationDetails: ScrollView? = null
    private var simulationStatus: TextView? = null
    private var simulationStart: Button? = null
    private var simulationRetry: Button? = null
    private val mapper = GarageHostMapper()
    private val resultsMapper = ResultsHostMapper()
    private lateinit var results: ResultsView
    private lateinit var onboarding: OnboardingView
    private var onboardingState: OnboardingScreenState = OnboardingScreenState.Loading
    private var routedNewProfile: String? = null
    private var automaticIntroAllowed = true
    private lateinit var queries: HostQueryController
    private lateinit var session: HostSession
    private lateinit var style: GarageStyle
    private lateinit var featureStyle: GarageStyle
    private lateinit var garage: GarageView
    private lateinit var collection: CollectionView
    private lateinit var body: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var chrome: LinearLayout
    private lateinit var titleView: PrototypeTitleView
    private var titleSoundButton: Button? = null
    private lateinit var presentationAudio: PrototypeAudio
    private lateinit var pageHeading: LinearLayout
    private lateinit var pageTitle: TextView
    private lateinit var pageRefresh: Button
    private lateinit var firstPlayTitle: Button
    private lateinit var statusOverlay: ScrollView
    private var titlePending = false
    private var presentationFocused = false
    private var active = false
    private val backObservation = BackObservationBoundary()
    private lateinit var panel: LinearLayout
    private lateinit var appRoot: FrameLayout
    private lateinit var windowMap: ImageView
    private var featureWindow = false
    private var headedPage: HostPage? = null
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            updateAccess()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigation = restoreNavigation(savedInstanceState)
        homeVehicleManagement = savedInstanceState?.getBoolean("homeVehicleManagement") == true &&
            navigation.pendingGarageRequest != null
        simulationControlsExpanded = savedInstanceState?.getBoolean("simulationControlsExpanded") == true
        savedInstanceState?.getBundle("collapsedDeliveryProgress")?.let { saved ->
            val profile = saved.getString("profile")?.takeIf(String::isNotBlank)
            val source = InputSource.entries.firstOrNull { it.name == saved.getString("source") }
            val request = saved.getString("request")?.takeIf(String::isNotBlank)
            if (profile != null && source != null && request != null) {
                collapsedDriveKey = DriveProgress.Key(GameIdentity(ProfileId(profile), source), RequestId(request))
            }
        }
        style = GarageStyle(this, pixelPanel = true)
        featureStyle = GarageStyle(this, pixelPanel = true, creamPanel = true)
        titlePending = savedInstanceState?.getBoolean("presentationTitlePending")
            ?: intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        presentationAudio = PrototypeAudio(applicationContext)
        titleView = PrototypeTitleView(this, onStarting = {
            if (allowed() && presentationFocused && titlePending) presentationAudio.play(PrototypeAudio.Cue.START)
        }) {
            if (allowed() && presentationFocused && titlePending) showPresentationTitle(false)
        }
        garage = GarageView(this, ::onGarageEvent).apply {
            showHeading = false
            restoreSelection(navigation.selectedVehicleId)
        }
        collection = CollectionView(this, ::refresh).apply { showHeading = false }
        results = ResultsView(this, ::onResultsEvent).apply { showHeading = false }
        shop = ShopView(this, ::onShopEvent).apply {
            onOpenVehicle = ::openVehicleFromShop
            onFocusedVehicleAction = ::openFocusedVehicleAction
            onFocusedVehicleRetry = { if (gameControlsAllowed() && navigation.page == HostPage.HOME) queries.refresh() }
            onFocusedRest = ::onFacilityEvent
            val previous = savedInstanceState?.getString("homeFocusedVehicle")?.takeIf { it.isNotBlank() }?.let(::OwnedVehicleId)
            restoreFocusedVehicle(previous ?: navigation.pendingGarageRequest?.id?.takeIf { homeVehicleManagement })
        }
        facilityControls = io.drivegrowth.app.ui.shop.FacilityControlsView(this, ::onFacilityEvent)
        shop.facilityPanel = facilityControls
        val shopMode = ShopMode.entries.firstOrNull { it.name == savedInstanceState?.getString("shopMode") } ?: ShopMode.OVERALL
        var restoredFacility: io.drivegrowth.app.host.facility.FacilityCommand.Buy? = null
        val restoredShop = if (savedInstanceState?.containsKey("shopUi") == true) {
            val bundle = savedInstanceState.getBundle("shopUi")
            val decoded = io.drivegrowth.app.host.shop.ShopUiStateBundle.decodePresent(bundle)
            val original = io.drivegrowth.app.host.shop.ShopUiStateBundle.decodeFacility(bundle)
            if (decoded is ContractResult.Success && original is ContractResult.Success) {
                restoredFacility = original.value
                decoded.value
            } else {
                // 잘못된 shopUi만 보존하고 다른 화면의 저장 상태는 현재 값으로 갱신한다.
                failedShopRestore = Bundle(savedInstanceState).apply {
                    keySet().filter { it != "shopUi" }.forEach(::remove)
                }
                ShopUiState()
            }
        } else ShopUiState(mode = shopMode, sceneScrollY = savedInstanceState?.getInt("shopSceneY", 0) ?: 0,
            controlsScrollY = savedInstanceState?.getInt("shopControlsY", 0) ?: 0)
        ranking = RankingView(this) {
            if (gameControlsAllowed() && navigation.page == HostPage.RANKING) session.ranking.refresh()
            else { updateAccess(); ranking.render(RankingState.Restricted, false) }
        }
        ranking.showHeading = false
        ranking.restoreUiState(RankingUiState(
            RankingMode.entries.firstOrNull { it.name == savedInstanceState?.getString("rankingMode") } ?: RankingMode.MY_RECORD,
            RankingMetric.entries.firstOrNull { it.name == savedInstanceState?.getString("rankingMetric") } ?: RankingMetric.SHOP_LEVEL,
            (savedInstanceState?.getInt("rankingY", 0) ?: 0).coerceAtLeast(0)))
        onboarding = OnboardingView(this, ::onOnboardingEvent).also { it.showHeading = false }
        routedNewProfile = savedInstanceState?.getString("onboardingRoutedProfile")
        automaticIntroAllowed = if (savedInstanceState?.containsKey("onboardingAutoEntryAllowed") == true)
            savedInstanceState.getBoolean("onboardingAutoEntryAllowed") else savedInstanceState == null && lastNonConfigurationInstance == null
        val deliveryHandler = handler
        session = lastNonConfigurationInstance as? HostSession ?: HostSession(
            context = applicationContext,
            main = { deliveryHandler.post(it) },
            restoredResults = ResultsStateBundle.decode(savedInstanceState?.getBundle("results")),
            restoredOnboarding = OnboardingStateBundle.decode(savedInstanceState?.getBundle("onboardingHost")),
            restoredShop = restoredShop,
            restoredFacility = restoredFacility,
            failedShopRestore = failedShopRestore,
            simulation = io.drivegrowth.app.host.HostSimulationProvider.create(this),
        )
        if (session.failedShopRestore == null) session.failedShopRestore = failedShopRestore
        failedShopRestore = session.failedShopRestore
        shop.restoreUiState(session.home.uiState)
        progression = io.drivegrowth.app.ui.progress.ProgressView(this) { event ->
            if (gameControlsAllowed() && navigation.page == HostPage.PROGRESSION) session.progression.handle(event) else updateAccess()
        }
        if (lastNonConfigurationInstance == null) {
            when (val decoded = io.drivegrowth.app.host.progression.ProgressionUiStateBundle.decode(savedInstanceState?.getBundle("progression"))) {
                is ContractResult.Success -> session.progressionUi = decoded.value
                is ContractResult.Failure -> progressionRestoreFailed = true
            }
        }
        progression.showHeading = false
        progression.restoreUiState(session.progressionUi)
        deliveryFrame = session.deliveryUi.frame ?: DeliveryFrame(0, DeliveryScreen.Loading)
        delivery = DeliveryView(this, { request ->
            if (gameControlsAllowed()) {
                session.deliveryUi = delivery.saveUiState()
                session.delivery.onRequest(request)
            }
        }, session.deliveryUi, navigationControls = ::deliveryNavigationControls, hostStatus = ::deliveryHostStatus,
            canConfirmOffer = { !session.delivery.isSavingDisplayedOffers },
            onPresentationCue = ::playDeliveryCue,
            onPresentationCancelled = presentationAudio::stopEffectPlayback).apply {
            showHeading = false
            onReturnToShop = {
                if (navigation.page == HostPage.DELIVERY && gameControlsAllowed()) select(HostPage.HOME)
            }
        }
        gacha = GachaView(this, ::onGachaEvent, session.gacha.uiState).apply { showHeading = false }
        results.restoreUiState(session.results.uiState)
        queries = session.queries
        session.home.attach({ _, _ -> if (::body.isInitialized) render() }, { request, result ->
            shop.complete(request, result)
            if (result is ContractResult.Success && allowed()) { queries.refresh(); refreshPendingRoute() }
            if (::body.isInitialized) render()
        }, shop::completeRetry)
        management = GarageCommandHost(applicationContext, { handler.post(it) }, session.games) { state ->
            val prior = managementState
            managementState = state
            if (state == GarageCommandState.Idle) {
                managementVisible = false
                if (prior is GarageCommandState.Confirmed) {
                    navigation.pendingGarageRequest = null
                    garage.completeRequest()
                    if (homeVehicleManagement && allowed()) {
                        session.home.refresh()
                        refreshPendingRoute()
                    }
                    homeVehicleManagement = false
                }
            }
            if (state is GarageCommandState.Review || state is GarageCommandState.Running || state is GarageCommandState.Confirmed)
                managementVisible = true
            if ((state is GarageCommandState.Confirmed || state is GarageCommandState.Rejected) && allowed()) queries.refresh()
            if (state is GarageCommandState.Rejected && homeVehicleManagement && allowed()) {
                session.home.refresh()
                refreshPendingRoute()
            }
            if (::body.isInitialized) render()
        }
        managementView = GarageCommandView(this,
            { if (gameControlsAllowed()) management.controller.confirm() }, ::cancelPreparation,
            { if (gameControlsAllowed()) management.controller.retry() },
            { if (gameControlsAllowed()) management.controller.cancelPending() },
            { if (gameControlsAllowed()) management.controller.chooseMaterial(it) },
            { if (gameControlsAllowed()) management.controller.acknowledge() })
        panel = style.column().apply { setBackgroundColor(PixelStyle.background) }
        if (session.simulation != null) {
            simulationControls = createSimulationControls()
        }
        val sign = ReferenceHudStyle(this)
        chrome = style.column()
        body = FrameLayout(this)
        stage = FrameLayout(this).apply {
            tag = "host:game-stage"
            addView(body, FrameLayout.LayoutParams(-1, -1))
        }
        if (session.simulation != null) {
            restAreaScene = io.drivegrowth.app.ui.rest.RestAreaSceneView(this).apply {
                tag = "simulation:rest-area-scene"
                stage.addView(this, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = style.dp(16)
                })
            }
        }
        pageTitle = style.text("", 22).apply {
            tag = "host:page-title"
            setTextColor(sign.signInk)
            setTypeface(typeface, Typeface.BOLD)
            compoundDrawablePadding = style.dp(8)
            isAccessibilityHeading = true
        }
        val pageClose = style.button("닫기") {
            if (gameControlsAllowed() && navigation.page != HostPage.HOME) onBackPressed()
        }.apply { tag = "host:back"; stylePageAction(sign) }
        pageRefresh = style.button("새로고침") {
            if (gameControlsAllowed() && navigation.page != HostPage.HOME && homeSettings == null) refresh()
        }.apply { stylePageAction(sign) }
        firstPlayTitle = style.button("시작 화면") {
            if (gameControlsAllowed() && navigation.page == HostPage.ONBOARDING && homeSettings == null) showPresentationTitle(true)
        }.apply { stylePageAction(sign) }
        val pageActions = style.row().apply {
            gravity = Gravity.END
            fun action(button: Button) {
                val params = LinearLayout.LayoutParams(-2, -1)
                if (childCount > 0) params.marginStart = style.dp(8)
                addView(button, params)
            }
            action(firstPlayTitle)
            action(pageRefresh)
            action(pageClose)
        }
        pageHeading = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val stacked = width < style.dp(560) || resources.configuration.fontScale >= 1.3f
                val direction = if (stacked) VERTICAL else HORIZONTAL
                if (orientation != direction) orientation = direction
                val titleLayout = pageTitle.layoutParams as LayoutParams
                titleLayout.width = if (stacked) -1 else 0
                titleLayout.weight = if (stacked) 0f else 1f
                titleLayout.marginEnd = if (stacked) 0 else style.dp(12)
                val actionsLayout = pageActions.layoutParams as LayoutParams
                actionsLayout.width = if (stacked) -1 else -2
                for (index in 0 until pageActions.childCount) {
                    val actionLayout = pageActions.getChildAt(index).layoutParams as LayoutParams
                    actionLayout.width = if (stacked) 0 else -2
                    actionLayout.weight = if (stacked) 1f else 0f
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(style.dp(12), style.dp(6), style.dp(12), style.dp(6))
            addView(pageTitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pageActions, LinearLayout.LayoutParams(-2, -2))
        }
        statusOverlay = ScrollView(this).apply {
            tag = "host:status"
            addView(chrome)
        }
        shop.onMapModalChanged = { open ->
            mapModalOpen = open
            updateNavigationVisibility()
            if (::statusOverlay.isInitialized && statusOverlay.parent === stage) {
                updateHomeStatusVisibility()
            }
        }
        addDriveProgressViews()
        panel.addView(pageHeading)
        panel.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        simulationControls?.let { panel.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        windowMap = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        appRoot = FrameLayout(this).apply {
            setBackgroundColor(PixelStyle.background)
            addView(windowMap, FrameLayout.LayoutParams(-1, -1))
            addView(panel, FrameLayout.LayoutParams(-1, -1))
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    applyFeatureWindowInsets(right - left, bottom - top)
                    if (simulationControlsExpanded) renderSimulationControls(allowed())
                }
            }
        }
        setContentView(appRoot)
        gacha.onGachaAudio = { rarity, opening ->
            if (gameControlsAllowed() && presentationFocused && navigation.page == HostPage.GACHA) {
                presentationAudio.playGacha(rarity, opening)
            }
        }
        gacha.onGachaAudioCancelled = {
            if (navigation.page == HostPage.GACHA) presentationAudio.stopEffectPlayback()
        }
        results.onCelebration = ::playCelebration
        onboarding.onCelebration = ::playCelebration
        shop.onCelebration = ::playCelebration
        session.gacha.attach({ frame, enabled ->
            gacha.render(frame, enabled && gameControlsAllowed())
            session.gacha.remember(gacha.uiState)
            if (::chrome.isInitialized) render()
        }, { request, result ->
            gacha.complete(request, result); session.gacha.remember(gacha.uiState)
        }, { token ->
            gacha.completeReload(token); session.gacha.remember(gacha.uiState)
        }, { key ->
            gacha.resolveFailedRequest(key); session.gacha.remember(gacha.uiState)
        })
        session.onboarding.attach({ state, enabled ->
            onboardingState = state
            if (!onboarding.render(state, enabled && gameControlsAllowed())) {
                // 기존 View의 Completed 역행 방지와 새 저장 조회 실패/프로필 세대를 분리한다.
                onboarding = OnboardingView(this, ::onOnboardingEvent).also { it.showHeading = false }.apply { onCelebration = ::playCelebration }
                onboarding.restoreSelection(session.onboarding.uiState.selected)
                onboarding.render(state, enabled && gameControlsAllowed())
            }
            onboarding.restoreSelection(session.onboarding.uiState.selected)
            val profile = session.onboarding.uiState.identity?.profileId?.value
            if (state is OnboardingScreenState.Intro)
                routedNewProfile = navigation.acceptInitialIntro(profile, routedNewProfile, automaticIntroAllowed)
            render()
        }, {
            // Completed의 이동 입력 잠금은 새 View에서 초기화하고 저장 결과를 다시 공급한다.
            onboarding = OnboardingView(this, ::onOnboardingEvent).also { it.showHeading = false }
            onboarding.restoreSelection(session.onboarding.uiState.selected)
            onboarding.render(onboardingState, gameControlsAllowed())
            select(HostPage.HOME)
        }, {
            queries.refresh()
            session.onboarding.refresh()
        if (navigation.page == HostPage.GACHA) session.gacha.refresh()
        })
        session.delivery.attach({ frame, enabled ->
            deliveryFrame = frame
            val read = session.delivery.state
            if (read != null && read.screen == frame.screen) {
                val request = when (val current = frame.screen) {
                    is DeliveryScreen.Active -> current.delivery.active.requestId
                    is DeliveryScreen.AwaitingResult -> current.delivery.active.requestId
                    else -> null
                }
                collapsedDriveKey?.let { key ->
                    if (key.identity != read.identity || key.requestId != request) collapsedDriveKey = null
                }
            }
            val receipt = read?.receipt
            if (receipt != null && receipt != deliveryReceipt) { deliveryReceipt = receipt; queries.refresh() }
            delivery.render(frame, enabled && gameControlsAllowed() && navigation.page == HostPage.DELIVERY, session.delivery.state)
            session.deliveryUi = delivery.saveUiState()
            if (::chrome.isInitialized) render()
        }, { request, frame ->
            delivery.complete(request, frame)
            session.deliveryUi = delivery.saveUiState()
        }, onDiscoverySaved = queries::refresh, onOriginalResolved = ::refreshPendingRoute)
        session.deliveryNavigation.changed = { if (active && allowed() && navigation.page == HostPage.DELIVERY) render() }
        queries.attach {
            screen = it
            if (it is HostQueryState.Content) refreshPendingRoute()
            render()
            if (it is HostQueryState.Content || it is HostQueryState.Failure) {
                if (!session.results.hasHistorySource) {
                    session.results.completeQuery()
                    results.completeRetry()
                }
                shop.completeRetry()
            }
        }
        if (savedInstanceState == null && session.simulation == null) {
            val permissions = arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_POWERTRAIN")
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        backObservation.resumed()
        startObserving()
    }

    @android.annotation.TargetApi(29)
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (backObservation.topResumedChanged(isTopResumedActivity)) startObserving()
    }

    private fun startObserving() {
        rentalAppExit.clear()
        setVisible(true)
        if (active) return
        active = true
        rentalFingerprint = null
        session.simulation?.changed = { if (active) { updateAccess(); if (::chrome.isInitialized) render() } }
        if (session.simulation?.clock() is ContractResult.Failure) { updateAccess(); return }
        val rentalReceiver = session.driving.start { result ->
            val status = when (result) {
                is ContractResult.Failure -> {
                    android.util.Log.w("DriveGrowthDriving", "recording_failure code=${result.error}")
                    "주행 기록을 확인하지 못했어요. 원래 기록은 다시 연결할 때 확인합니다."
                }
                is ContractResult.Success -> {
                    if (result.value.committed.isNotEmpty()) {
                        queries.refresh()
                        session.progression.refresh()
                        session.ranking.refresh()
                    }
                    if (result.value.waiting != null) "주행 기록을 확인하고 있어요."
                    else null
                }
            }
            if (drivingStatus != status) { drivingStatus = status; if (::chrome.isInitialized) render() }
        }
        session.rental.start(rentalReceiver) { result ->
            when (result) {
                is ContractResult.Failure -> if (rentalFingerprint != result.error) {
                    rentalFingerprint = result.error
                    android.util.Log.w("DriveGrowthRental", "driver_deferred code=${result.error}")
                    queries.refresh(); session.onboarding.refresh()
                }
                is ContractResult.Success -> {
                    val snapshot = result.value
                    val fingerprint = listOf(snapshot.pending.map { it.id to it.phase }, snapshot.firstCompleted?.rentalId,
                        snapshot.deferred.toMap())
                    if (rentalFingerprint != fingerprint) {
                        rentalFingerprint = fingerprint
                        queries.refresh(); session.progression.refresh(); session.ranking.refresh(); session.onboarding.refresh()
                        if (navigation.page == HostPage.RESULTS) session.results.refreshHistory()
                        if (snapshot.deferred.isNotEmpty()) android.util.Log.w("DriveGrowthRental", "motion_ranges_deferred")
                    }
                }
            }
        }
        session.progression.attach { frame ->
            progressionFrame = frame
            if (frame.retryComplete) progression.completeRetry()
            if (frame.resolved.isNotEmpty()) {
                progression.completePendingRecovery(frame.resolved)
                if (allowed()) queries.refresh()
            }
            renderProgression()
            session.progressionUi = progression.uiState
        }
        session.progression.restoreUiState(progression.uiState)
        session.ranking.attach { rankingState = it; ranking.render(it, gameControlsAllowed() && navigation.page == HostPage.RANKING) }
        if (results.uiState != session.results.uiState) results.restoreUiState(session.results.uiState)
        session.results.attach { id, result ->
            updateAccess()
            results.completeMarkViewed(id, result)
        }
        session.results.attachHistory { state ->
            if (state !is io.drivegrowth.app.ui.results.ResultsScreenState.Loading) results.completeRetry()
            if (::chrome.isInitialized) render()
        }
        if (session.simulation != null) {
            session.attachSimulation { frame, done ->
                if (active) { session.driving.accept(frame, done); updateAccess() }
            }
        } else try {
            input = VehicleInput(this, { observation ->
                val zone = session.drivingCalendar.take(observation)
                if (active) {
                    session.driving.accept(io.drivegrowth.app.host.driving.DrivingFrame(observation, zone))
                    interaction.accept(observation)
                    updateAccess()
                }
            }, {}, session.drivingCalendar).also { it.start() }
        } catch (_: LinkageError) {
            updateAccess()
        }
        handler.post(watchdog)
    }

    override fun onPause() {
        closeRestStopPreview(updateAudio = false)
        titleView.setPresentationAllowed(false)
        presentationAudio.setPresentationAllowed(false)
        stopObserving()
        super.onPause()
    }

    private fun stopObserving() {
        simulationStartBoundary.cancel()
        restStartTicket = null
        session.simulation?.stop()
        session.simulation?.changed = null
        active = false // 새 UI/관측 입구를 먼저 닫고 SDK queue를 중단한다.
        renderRestAreaScene()
        input?.stop()
        input = null
        session.driving.stop() // 같은 serial worker 뒤에 실제 중단 시각과 receiver 경계를 보존한다.
        session.rental.pause()
        session.progressionUi = progression.uiState
        session.progression.setControlsEnabled(false)
        session.progression.detach()
        lastProgressionAccess = null
        progression.render(io.drivegrowth.app.ui.progress.ProgressScreenState.Loading, false)
        session.results.remember(results.uiState)
        session.ranking.detach()
        session.results.detach()
        active = false
        handler.removeCallbacks(watchdog)
        interaction.reset()
        updateAccess()
        input?.stop()
        input = null
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (active && isTaskRoot) rentalAppExit.userLeaveHint()
    }

    override fun onStop() {
        if (rentalAppExit.consumeStop(isChangingConfigurations)) {
            session.rental.inactive(false, session.rentalClock.read())
        }
        super.onStop()
    }

    override fun onDestroy() {
        titleView.setPresentationAllowed(false)
        presentationAudio.close()
        session.progressionUi = progression.uiState
        session.progression.detach()
        session.deliveryUi = delivery.saveUiState()
        session.delivery.detach()
        session.deliveryNavigation.changed = null
        delivery.release()
        pendingRouteGeneration++
        session.ranking.detach()
        session.home.remember(shop.uiState)
        session.home.detach()
        management.release()
        queries.detach()
        session.gacha.remember(gacha.uiState)
        session.gacha.detach()
        session.onboarding.detach()
        gacha.release()
        session.results.detach()
        if (!isChangingConfigurations) session.release()
        super.onDestroy()
    }

    override fun onRetainNonConfigurationInstance(): Any = session

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("presentationTitlePending", titlePending)
        session.progressionUi = progression.uiState
        outState.putBundle("progression", io.drivegrowth.app.host.progression.ProgressionUiStateBundle.encode(progression.uiState))
        session.results.remember(results.uiState)
        outState.putBundle("results", ResultsStateBundle.encode(session.results.uiState))
        outState.putBundle("onboardingHost", OnboardingStateBundle.encode(session.onboarding.uiState))
        outState.putString("onboardingRoutedProfile", routedNewProfile)
        outState.putBoolean("onboardingAutoEntryAllowed", automaticIntroAllowed)
        outState.putString("rankingMode", ranking.uiState.mode.name)
        outState.putString("rankingMetric", ranking.uiState.metric.name)
        outState.putInt("rankingY", ranking.uiState.scrollY)
        failedShopRestore?.let(outState::putAll) ?: run {
            session.home.remember(shop.uiState)
            outState.putBundle("shopUi", io.drivegrowth.app.host.shop.ShopUiStateBundle.encode(shop.uiState, session.home.facilityOriginalCommand))
        }
        outState.putString("shopMode", shop.uiState.mode.name)
        outState.putInt("shopSceneY", shop.uiState.sceneScrollY)
        outState.putInt("shopControlsY", shop.uiState.controlsScrollY)
        outState.putString("returnPage", navigation.returnPage.name)
        outState.putString("page", navigation.page.name)
        collapsedDriveKey?.let { key ->
            outState.putBundle("collapsedDeliveryProgress", Bundle().apply {
                putString("profile", key.identity.profileId.value)
                putString("source", key.identity.source.name)
                putString("request", key.requestId.value)
            })
        }
        if (session.simulation != null) outState.putBoolean("simulationControlsExpanded", simulationControlsExpanded)
        outState.putString("selectedVehicle", navigation.selectedVehicleId?.value)
        outState.putString("homeFocusedVehicle", shop.focusedVehicleId?.value)
        outState.putBoolean("homeVehicleManagement", homeVehicleManagement)
        navigation.pendingGarageRequest?.let {
            outState.putString("pendingVehicle", it.id.value)
            outState.putInt("pendingRevision", it.revision.value)
            outState.putString("pendingAction", it.action.name)
        }
        super.onSaveInstanceState(outState)
    }

    protected fun allowed() = active && restStartTicket == null &&
        (session.simulation?.controlsAllowed ?: (interaction.state(SystemClock.elapsedRealtime()) == Observation.Available(VehicleInteraction.ALLOWED)))

    private fun gameControlsAllowed() = allowed() && !titlePending

    private fun vehicleRestrictionMessage(): String {
        val missing = interaction.state(SystemClock.elapsedRealtime()) as? Observation.Missing
        val message = when (missing?.reason) {
            MissingReason.PERMISSION_DENIED -> R.string.vehicle_restriction_permission
            MissingReason.UNSUPPORTED -> R.string.vehicle_restriction_unsupported
            else -> R.string.vehicle_restriction_park
        }
        return getString(message)
    }


    private fun showPresentationTitle(show: Boolean) {
        if (titlePending == show) return
        if (show) simulationStartBoundary.cancel()
        titlePending = show
        updateAccess()
        render()
    }

    protected fun canStartRestSimulation(): Boolean =
        session.simulation != null && simulationControlsExpanded &&
            !titlePending && navigation.page != HostPage.ONBOARDING && allowed()

    protected fun startRestSimulation() {
        if (!canStartRestSimulation()) return
        val simulation = session.simulation ?: return
        val ticket = Any()
        val page = navigation.page
        restStartTicket = ticket
        // 같은 UI 이벤트에서 새 탁송 요청을 닫고, 앞선 거래 뒤 worker에서 원장을 확인한다.
        updateAccess()
        session.prepareSimulation(rest = true) { result ->
            if (restStartTicket !== ticket) return@prepareSimulation
            restStartTicket = null
            if (simulationControlsExpanded && page == navigation.page && gameControlsAllowed()) when (result) {
                is ContractResult.Failure -> simulation.rejected(result.error)
                is ContractResult.Success -> { collapseSimulationControls(); simulation.beginRest() }
            }
            if (active) updateAccess()
        }
    }

    protected open fun onControlsAvailabilityChanged(allowed: Boolean) = Unit

    protected open fun addSimulationActions(content: LinearLayout) = Unit

    private fun createSimulationControls(): View {
        val simulation = checkNotNull(session.simulation)
        val sign = ReferenceHudStyle(this)
        val content = style.column()
        content.setPadding(0, style.dp(4), 0, style.dp(4))
        simulationStatus = style.text("", 16).apply {
            tag = "simulation:status"
            setTextColor(sign.signInk)
            content.addView(this)
        }
        simulationStart = style.button("모의주행 시작") {
            if (!simulationControlsExpanded || !gameControlsAllowed() ||
                navigation.page == HostPage.ONBOARDING) return@button
            simulationStartBoundary.prepare({ session.prepareSimulation(done = it) }) { result ->
                if (gameControlsAllowed() && navigation.page != HostPage.ONBOARDING) when (result) {
                    is ContractResult.Failure -> simulation.rejected(result.error)
                    is ContractResult.Success -> { collapseSimulationControls(); simulation.begin() }
                }
            }
        }.apply {
            tag = "simulation:start"
            content.addView(this)
        }
        addSimulationActions(content)
        val retry = style.button("같은 모의 입력 다시 시도") {
            if (active && simulationControlsExpanded && simulation.canRetry) simulation.retry()
        }.apply {
            tag = "simulation:retry"
            visibility = View.GONE
        }
        simulationRetry = retry
        content.addView(retry)
        simulationStart?.simulationButtonStyle(sign)
        retry.simulationButtonStyle(sign)
        val details = ScrollView(this).apply {
            addView(content)
            visibility = View.GONE
        }
        simulationDetails = details
        val toggle = style.button("닫기", onClick = ::collapseSimulationControls).apply {
            tag = "simulation:controls"
            simulationButtonStyle(sign)
        }
        simulationToggle = toggle
        return style.column().apply {
            background = sign.signSurface()
            setPadding(style.dp(8), style.dp(4), style.dp(8), style.dp(6))
            addView(style.row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(style.text("모의 입력 모드", 16).apply { setTextColor(sign.signInk) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(toggle, LinearLayout.LayoutParams(-2, -2))
            })
            addView(details, LinearLayout.LayoutParams(-1, style.dp(166)))
        }
    }

    private fun Button.simulationButtonStyle(sign: ReferenceHudStyle) {
        backgroundTintList = null
        background = sign.signButton()
        setTextColor(sign.signInk)
        minimumHeight = style.dp(44)
        minHeight = minimumHeight
        textSize = 16f
        setPadding(style.dp(10), 0, style.dp(10), 0)
    }

    private fun collapseSimulationControls() {
        simulationControlsExpanded = false
        simulationStartBoundary.cancel()
        if (restStartTicket != null) {
            restStartTicket = null
            updateAccess()
            render()
            return
        }
        renderSimulationControls(allowed())
        onControlsAvailabilityChanged(false)
        renderRestAreaScene()
        renderDriveProgress()
    }

    private fun renderSimulationControls(enabled: Boolean) {
        val simulation = session.simulation ?: return
        val show = simulationControlsExpanded && active && !titlePending &&
            (!referenceSceneFillsApp() || !enabled)
        simulationControls?.visibility = if (show) View.VISIBLE else View.GONE
        simulationToggle?.isSelected = show
        simulationDetails?.let { details ->
            details.visibility = if (show) View.VISIBLE else View.GONE
            val height = minOf(style.dp(166), (appRoot.height.takeIf { it > 0 } ?: style.dp(332)) / 2)
            val layout = details.layoutParams
            if (layout.height != height) {
                layout.height = height
                details.layoutParams = layout
            }
        }
        simulationStatus?.let { status ->
            val message = simulation.status
            if (status.text != message) status.text = message
        }
        simulationStart?.visibility = if (enabled && navigation.page != HostPage.ONBOARDING) View.VISIBLE else View.GONE
        simulationStart?.isEnabled = show && enabled && navigation.page != HostPage.ONBOARDING
        simulationRetry?.visibility = if (show && simulation.canRetry) View.VISIBLE else View.GONE
        simulationRetry?.isEnabled = show && simulation.canRetry
    }

    private fun Button.stylePageAction(sign: ReferenceHudStyle) {
        backgroundTintList = null
        background = sign.signButton()
        setTextColor(sign.signInk)
        textSize = 18f
        minWidth = style.dp(64)
        minimumWidth = minWidth
        minHeight = style.dp(56)
        minimumHeight = minHeight
        setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(8))
    }

    private fun updateAccess() {
        val enabled = allowed()
        if (!enabled && session.simulation == null) {
            body.findViewWithTag<TextView>("vehicle:blocked")?.let {
                val message = vehicleRestrictionMessage()
                if (it.text != message) it.text = message
            }
        }
        if (!enabled) simulationStartBoundary.cancel()
        val gameEnabled = enabled && !titlePending
        updateNavigationBar(gameEnabled)
        updatePresentation(enabled)
        if (!enabled) statusOverlay.visibility = View.GONE
        session.results.setControlsEnabled(enabled && navigation.page == HostPage.RESULTS)
        queries.setControlsEnabled(enabled)
        session.progression.setControlsEnabled(enabled && navigation.page == HostPage.PROGRESSION)
        renderProgression()
        val deliveryEnabled = enabled && navigation.page == HostPage.DELIVERY
        val deliveryGained = deliveryEnabled && !deliveryAccessEnabled
        deliveryAccessEnabled = deliveryEnabled
        session.deliveryNavigation.setControlsEnabled(deliveryEnabled)
        session.delivery.setControlsEnabled(deliveryEnabled)
        if (deliveryGained) session.delivery.refresh()
        session.gacha.setControlsEnabled(enabled && navigation.page == HostPage.GACHA)
        session.ranking.setControlsEnabled(enabled && navigation.page == HostPage.RANKING)
        session.onboarding.setControlsEnabled(enabled)
        session.home.setControlsEnabled(enabled && navigation.page == HostPage.HOME && !shopRestoreFailed)
        management.controller.setControlsEnabled(enabled)
        val routeGained = enabled && !pendingRouteEnabled
        pendingRouteEnabled = enabled
        if (routeGained) refreshPendingRoute()
        if (enabled && !recoveredManagement) { recoveredManagement = true; management.controller.recover() }
    }

    private fun refresh() {
        if (!gameControlsAllowed()) { updateAccess(); return }
        if (navigation.page == HostPage.GACHA && gacha.closeReveal()) return
        session.onboarding.refresh()
        if (navigation.page == HostPage.RESULTS) { session.results.remember(results.uiState); session.results.refreshHistory() }
        else if (navigation.page == HostPage.PROGRESSION) session.progression.refresh()
        else if (navigation.page == HostPage.DELIVERY) { session.deliveryNavigation.refresh(); session.delivery.refresh() }
        else if (navigation.page == HostPage.HOME) { session.facility.refresh(); session.home.refresh(); queries.refresh() }
        else if (navigation.page == HostPage.GACHA) session.gacha.refresh()
        else if (navigation.page == HostPage.RANKING) session.ranking.refresh()
        else if (screen != HostQueryState.Loading) queries.refresh()
    }

    private fun updateNavigationBar(enabled: Boolean) {
        val showHome = enabled && navigation.page == HostPage.HOME
        if (!showHome) {
            if (!enabled && !(active && session.simulation?.canRetry == true)) closeHomeSettings()
            navigationBar?.let(stage::removeView)
            navigationBar = null
            return
        }
        if (navigationBar != null) {
            updateNavigationVisibility()
            return
        }
        lateinit var bar: View
        val content = MainNavigationBar(this) { target ->
            if (navigationBar === bar && bar.visibility == View.VISIBLE && navigation.page == HostPage.HOME &&
                homeSettings == null && !mapModalOpen && gameControlsAllowed()
            ) {
                if (target == HostPage.RESULTS && (unviewedCount() ?: 0) > 0) {
                    results.restoreUiState(results.uiState.copy(selectedId = null, scope = ResultsScope.UNVIEWED))
                    session.results.remember(results.uiState)
                }
                select(target)
            }
        }
        bar = object : android.widget.HorizontalScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(MeasureSpec.getSize(widthMeasureSpec), style.dp(880))
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            addView(content, FrameLayout.LayoutParams(-1, -2))
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) renderDriveProgress()
            }
        }
        navigationBar = bar
        stage.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            marginStart = style.dp(4)
            marginEnd = style.dp(4)
            bottomMargin = style.dp(6)
        })
        updateNavigationVisibility()
    }

    private fun updateNavigationVisibility() {
        val visible = if (homeSettings == null && !mapModalOpen) View.VISIBLE else View.GONE
        navigationBar?.visibility = visible
        renderDriveProgress()
    }

    private fun openHomeSettings() {
        val map = navigation.page == HostPage.HOME && gameControlsAllowed()
        val simulation = session.simulation
        if (homeSettings != null || !active || (!map && simulation?.canRetry != true)) return
        val overlay = FrameLayout(this).apply {
            tag = "host:settings-panel"
            isClickable = true
            setBackgroundColor(0x990b101e.toInt())
        }
        val page = navigation.page
        fun isCurrent() = homeSettings === overlay && navigation.page == page && active &&
            (gameControlsAllowed() || simulation?.canRetry == true)
        val sign = ReferenceHudStyle(this)
        val card = style.column().apply {
            background = sign.signSurface()
            setPadding(style.dp(12), style.dp(10), style.dp(12), style.dp(10))
        }
        val heading = style.row().apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(style.text("설정", 19).apply {
            setTextColor(sign.signInk)
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val items = style.column()
        fun action(label: String, key: String, onClick: () -> Unit) = style.button(label) {
            if (isCurrent()) onClick()
        }.apply {
            tag = key
            compact(style)
            backgroundTintList = null
            background = sign.signButton()
            setTextColor(sign.signInk)
            setPadding(style.dp(10), 0, style.dp(10), 0)
            val isClose = key == "host:settings-close"
            (if (isClose) heading else items).addView(this, LinearLayout.LayoutParams(if (isClose) -2 else -1, -2))
        }
        val close = action("닫기", "host:settings-close") { closeHomeSettings() }
        if (map) {
            homeSoundButton = action(soundSettingLabel(), "presentation:sound") {
                presentationAudio.enabled = !presentationAudio.enabled
                updatePresentation(allowed())
            }
            action("지도 보기·시설·꾸미기", "shop:map-facilities") { closeHomeSettings(); shop.openMapTools() }
            action("퀘스트·업적·휴식", "host:page:PROGRESSION") { select(HostPage.PROGRESSION) }
            action("첫 차량 안내", "host:page:ONBOARDING") { select(HostPage.ONBOARDING) }
            action("시작 화면", "presentation:home-title") { closeHomeSettings(); showPresentationTitle(true) }
            action("새로고침", "host:refresh") { closeHomeSettings(); refresh() }
        }
        if (simulation != null) {
            action("모의 입력 모드", "host:simulation-settings") {
                closeHomeSettings()
                openSimulationTool()
            }
            items.addView(style.text(
                "차량과 목적지는 모의 입력을 사용해요. 주행·영업 수치는 테스트용이며 기록은 별도로 저장돼요.", 14)
                .apply { setTextColor(sign.signInk) })
        }
        card.addView(heading)
        card.addView(ScrollView(this).apply { addView(items) }, LinearLayout.LayoutParams(-1, -2, 1f))
        overlay.addView(card, FrameLayout.LayoutParams(
            minOf(appRoot.width - style.dp(24), style.dp(420)).coerceAtLeast(0),
            -2, Gravity.CENTER,
        ).apply {
            val inset = style.dp(12)
            setMargins(inset, inset, inset, inset)
        })
        homeSettings = overlay
        shop.setHostOverlayOpen(true)
        statusOverlay.visibility = View.GONE
        panel.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        panel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        appRoot.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        updateNavigationVisibility()
        close.requestFocus()
    }

    internal fun openSimulationTool() {
        val simulation = session.simulation ?: return
        if (!active || titlePending || (!gameControlsAllowed() && !simulation.canRetry)) return
        simulationControlsExpanded = true
        renderSimulationControls(allowed())
        onControlsAvailabilityChanged(allowed())
        renderRestAreaScene()
        renderDriveProgress()
    }

    private fun closeHomeSettings(): Boolean {
        val overlay = homeSettings ?: return false
        homeSettings = null
        homeSoundButton = null
        appRoot.removeView(overlay)
        shop.setHostOverlayOpen(false)
        if (statusOverlay.parent === stage) updateHomeStatusVisibility()
        else if (statusOverlay.parent === panel) {
            statusOverlay.visibility = if (gameControlsAllowed() && !referenceSceneFillsApp()) View.VISIBLE else View.GONE
        }
        panel.descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        panel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        updateNavigationVisibility()
        return true
    }

    private fun unviewedCount(): Int? = (screen as? HostQueryState.Content)?.snapshot?.let { snapshot ->
        snapshot.unviewedSettlements.asSequence().filter { it.transaction.source == snapshot.source }
            .map { it.settlementId }.distinct().count()
    }

    private fun render() {
        val enabled = allowed()
        val gameEnabled = enabled && !titlePending
        updateNavigationBar(gameEnabled)
        updatePresentation(enabled)
        val garageState = when (val current = screen) {
            is HostQueryState.Content -> GarageActionAvailability.map(mapper.garage(current.snapshot), current.snapshot)
            is HostQueryState.Failure -> GarageScreenState.Error("차량 정보를 읽지 못했어요. 다시 조회해 주세요.")
            else -> GarageScreenState.Loading
        }
        val collectionState = when (val current = screen) {
            is HostQueryState.Content -> mapper.collection(current.snapshot, current.snapshot.collectionHistory)
            is HostQueryState.Failure -> CollectionScreenState.Error("도감을 읽지 못했어요. 다시 조회해 주세요.")
            else -> CollectionScreenState.Loading
        }
        ranking.render(rankingState, gameEnabled && navigation.page == HostPage.RANKING)
        managementView.render(managementState, gameEnabled)
        val homeEnabled = gameEnabled && navigation.page == HostPage.HOME && !shopRestoreFailed
        val facility = session.facility.state
        val facilityEnabled = homeEnabled && session.home.facilityControlsEnabled
        val showHomeManagement = navigation.page == HostPage.HOME && homeVehicleManagement && managementVisible
        shop.focusManagementPanel = managementView.takeIf { showHomeManagement }
        val restRecovery = facility.receipt != null || facility.pending != null || facility.error != null
        val restActions = if (facilityEnabled && !facility.busy && !facility.foreign && !facility.unsupported && !restRecovery) {
            facility.facts?.let(FacilityHostMapper::restingRows).orEmpty().filter { it.activity != null }.associate { row ->
                row.vehicle.ownedVehicleId to FacilityControlsEvent.SetResting(facility.version, row.vehicle, row.resting, !row.resting)
            }
        } else emptyMap()
        val unviewed = unviewedCount()
        navigationBar?.findViewWithTag<MainNavigationBar>("host:destination")?.showUnviewedSettlements(unviewed)
        val homeContent = session.home.state.screen as? ShopScreenState.Content
        val operatingStatus = homeContent?.let { content ->
            val activities = content.vehicles.mapNotNull { it.activity }
            when {
                activities.size != content.ownedCount -> "영업 상태 확인 중"
                io.drivegrowth.core.progression.VehicleActivity.RENTED in activities -> "대여 중"
                io.drivegrowth.core.progression.VehicleActivity.RETURNING in activities -> "반납 중"
                else -> "대여 대기"
            }
        }
        val projected = homeContent?.takeIf { it.origin == io.drivegrowth.app.ui.shop.ShopOrigin.STORED }?.let { content ->
            val balance = content.balance ?: return@let emptyMap()
            (listOfNotNull(content.expansion) + content.facilities.map { it.option } + content.decorations)
                .mapNotNull { option ->
                    val price = option.price ?: return@mapNotNull null
                    if (option.availability != io.drivegrowth.app.ui.shop.ShopAvailability.AVAILABLE ||
                        price.units > balance.units
                    ) null else option.id to io.drivegrowth.core.contract.GameMoney(balance.units - price.units)
                }.toMap()
        }.orEmpty()
        shop.updateMapProjectedBalance(projected)
        shop.updateMapHud(operatingStatus, ::openHomeSettings)
        shop.onActivateDecoration = {
            if (gameControlsAllowed() && navigation.page == HostPage.HOME && !shopRestoreFailed &&
                session.home.state.activationRequired
            ) session.home.activateDecoration()
        }.takeIf { homeEnabled && session.home.state.activationRequired }
        shop.render(if (shopRestoreFailed) ShopScreenState.Failure("가게 화면 상태를 복원하지 못해 요청을 보존하고 조작을 제한합니다.")
            else session.home.state.screen, homeEnabled, (garageState as? GarageScreenState.Content)?.data?.vehicles,
            focusRest = restActions, focusRestRecovery = facilityEnabled && restRecovery,
            focusError = (garageState as? GarageScreenState.Error)?.message,
            managementBusy = showHomeManagement,
            vehicleActionsBlocked = managementVisible || navigation.pendingGarageRequest != null ||
                pendingPage != null || pendingRouteFailed || facility.busy || restRecovery)
        val embeddedHomeManagement = showHomeManagement && shop.containsManagementPanel(managementView)
        val names = (screen as? HostQueryState.Content)?.snapshot?.content?.catalog?.vehicles
            ?.associate { it.vehicleId to it.displayName }.orEmpty()
        facilityControls.render(facility, facilityEnabled, names)
        garage.render(garageState, gameEnabled && navigation.page == HostPage.GARAGE)
        collection.render(collectionState, gameEnabled)
        results.render(if (session.results.hasHistorySource) session.results.historyState else resultsMapper.map(screen), gameEnabled)
        onboarding.render(onboardingState, gameEnabled && navigation.page == HostPage.ONBOARDING)
        renderDelivery(gameEnabled && navigation.page == HostPage.DELIVERY)
        renderSceneChrome(enabled)
        renderDriveProgress()
        statusOverlay.parent?.let { (it as android.view.ViewGroup).removeView(statusOverlay) }
        chrome.removeAllViews()
        if (gameEnabled && navigation.page == HostPage.HOME && (shopRestoreFailed || homeContent == null)) {
            chrome.addView(style.button("설정", onClick = ::openHomeSettings).apply {
                tag = "host:home-settings-fallback"
                compact(style)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        if (!enabled) {
            val simulation = session.simulation
            val message = when (simulation?.inputState) {
                null -> vehicleRestrictionMessage()
                SimulationInputState.PROCESSING -> "진행 상황을 처리하고 있어요. 잠시 기다려 주세요."
                SimulationInputState.RETRYABLE_FAILURE -> "진행이 중단됐어요. 설정에서 다시 시도해 주세요."
                SimulationInputState.UNAVAILABLE -> "진행 상황을 확인할 수 없어요. 기존 저장 기록은 그대로예요."
                SimulationInputState.READY -> "지금은 조작할 수 없어요. 현재 상태를 확인해 주세요."
            }
            val notice = style.text(message).apply {
                tag = if (simulation == null) "vehicle:blocked" else "simulation:blocked"
            }
            showBody(if (simulation?.canRetry == true) style.column().apply {
                addView(notice)
                addView(featureStyle.button("설정", onClick = ::openHomeSettings).apply { tag = "host:simulation-recovery-settings" })
            } else notice)
            return
        }
        if (titlePending) {
            showBody(titleView)
            return
        }
        if (session.simulation == null && navigation.page != HostPage.HOME)
            drivingStatus?.let { chrome.addView(style.text(it)) }
        if (navigation.page == HostPage.HOME && homeEnabled) {
            val home = session.home.state
            (home.decoration?.receipt ?: (home.original as? io.drivegrowth.app.data.HostCheckpointResult.Decoration)?.value)?.let { receipt ->
                val cost = if (receipt.settlement == null) "비용 없음" else "사용한 게임머니 ${receipt.command.expectedPrice.units}"
                chrome.addView(style.text("꾸미기 적용 완료 · $cost · 적용 당시 잔액 ${receipt.state.balance.units}")
                    .apply { tag = "decoration:receipt" })
            }
            val recovered = when (val original = home.original) {
                is io.drivegrowth.app.data.HostCheckpointResult.Expand -> "확장" to original.value
                is io.drivegrowth.app.data.HostCheckpointResult.FacilityPurchase -> "시설" to original.value
                else -> null
            }
            recovered?.let { (kind, receipt) ->
                chrome.addView(style.text("$kind 완료 · 적용 당시 잔액 ${receipt.balance.units}")
                    .apply { tag = "shop:original-receipt" })
            }
            if (home.decoration?.pending != null && home.decoration.receipt?.command != home.decoration.pending)
                chrome.addView(style.button("아직 적용되지 않은 꾸미기 취소") {
                    if (gameControlsAllowed() && navigation.page == HostPage.HOME) session.home.cancelDecorationSaved()
                }.apply { tag = "decoration:cancel-saved"; isEnabled = homeEnabled })
            if (home.screen is ShopScreenState.Failure) chrome.addView(style.button("아직 적용되지 않은 확장 취소") {
                if (gameControlsAllowed() && navigation.page == HostPage.HOME) session.home.cancelExpansionSaved()
            }.apply { isEnabled = homeEnabled })
        }
        if (navigation.page == HostPage.PROGRESSION) {
            if (progressionRestoreFailed) chrome.addView(style.text("화면 상태를 복원하지 못해 저장 기록을 다시 확인합니다."))
            chrome.addView(style.button("아직 처리되지 않은 보상 요청 취소") { if (gameControlsAllowed()) session.progression.cancelSaved() })
        }
        if (pendingRouteFailed)
            chrome.addView(style.button("처리 중인 요청 다시 확인") { if (gameControlsAllowed()) refreshPendingRoute() })
        pendingPage?.takeIf { it != navigation.page && !(it == HostPage.GARAGE && embeddedHomeManagement) }?.let { target ->
            chrome.addView(style.button(when (target) {
                HostPage.GACHA -> "보관한 가챠 요청 확인"
                HostPage.GARAGE -> "보관한 차량 관리 요청 확인"
                HostPage.DELIVERY -> "보관한 탁송 요청 확인"
                HostPage.PROGRESSION -> "보관한 보상 청구 확인"
                else -> "보관한 가게 요청 확인"
            }) {
                if (!gameControlsAllowed()) return@button
                select(target)
                if (target == HostPage.GARAGE) { managementVisible = true; management.controller.recover() }
            })
        }
        if (navigation.page == HostPage.GACHA && session.gacha.failed)
            chrome.addView(style.button("보관한 미실행 가챠 요청 취소") { if (gameControlsAllowed()) session.gacha.cancelUnstarted() })
        val showPreparation = navigation.page == HostPage.GARAGE || navigation.page == HostPage.HOME && homeVehicleManagement
        navigation.pendingGarageRequest?.takeIf { showPreparation && !managementVisible }?.let { request ->
            val current = (screen as? HostQueryState.Content)?.snapshot?.ownedVehicles?.firstOrNull { it.ownedVehicleId == request.id }
            val recoverable = managementState != GarageCommandState.Loading && (!homeVehicleManagement || current != null)
            chrome.addView(style.text("이전 관리 준비 요청입니다. 최신 상태와 비용을 다시 확인해 주세요."))
            chrome.addView(style.button("관리 조건 다시 조회", enabled = recoverable) {
                if (!gameControlsAllowed() || navigation.pendingGarageRequest != request || managementVisible ||
                    managementState == GarageCommandState.Loading) return@button
                val retry = if (homeVehicleManagement && navigation.page == HostPage.HOME) {
                    val actual = (screen as? HostQueryState.Content)?.snapshot?.ownedVehicles?.firstOrNull {
                        it.ownedVehicleId == request.id && it.lifecycle == io.drivegrowth.core.contract.VehicleLifecycle.OWNED
                    } ?: return@button
                    shop.restoreFocusedVehicle(actual.ownedVehicleId)
                    request.copy(revision = actual.revision)
                } else if (navigation.page == HostPage.GARAGE) request else return@button
                navigation.pendingGarageRequest = retry
                managementVisible = true
                management.controller.open(retry)
            }.apply { tag = "host:management-retry" })
            chrome.addView(style.button("준비 요청 닫기") { cancelPreparation() })
        }
        placeHostStatus()
        when (navigation.page) {
            HostPage.GARAGE -> showBody(if (managementVisible) managementView else garage)
            HostPage.COLLECTION -> showBody(collection)
            HostPage.RESULTS -> showBody(results)
            HostPage.ONBOARDING -> showBody(onboarding)
            HostPage.HOME -> showBody(if (showHomeManagement && !embeddedHomeManagement) managementView else shop)
            HostPage.GACHA -> showBody(gacha)
            HostPage.DELIVERY -> showBody(delivery)
            HostPage.RANKING -> showBody(ranking)
            HostPage.PROGRESSION -> { renderProgression(); showBody(progression) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        presentationFocused = hasFocus
        if (::session.isInitialized && ::pageHeading.isInitialized) updatePresentation(allowed())
    }

    protected fun showRestStopPreview() {
        if (!gameControlsAllowed() || !presentationFocused || !simulationControlsExpanded ||
            session.simulation == null || restStopPreview != null) return
        val preview = RestStopMotionView(this, presentationAudio::setRestSound) { closeRestStopPreview() }
        preview.bind(getString(R.string.rest_scene_preview_note))
        preview.isClickable = true
        restStopPreview = preview
        restStopPreviousFocus = panel.findFocus()
        panel.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        panel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        windowMap.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        updatePresentation(allowed())
        appRoot.addView(preview, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeRestStopPreview(updateAudio: Boolean = true): Boolean {
        val preview = restStopPreview ?: return false
        val previousFocus = restStopPreviousFocus
        restStopPreview = null
        restStopPreviousFocus = null
        appRoot.removeView(preview)
        panel.descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        panel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        windowMap.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (updateAudio) {
            updatePresentation(allowed())
            previousFocus?.takeIf { it.isShown && it.isEnabled && gameControlsAllowed() }?.requestFocus()
        }
        return true
    }

    private fun updatePresentation(enabled: Boolean) {
        if (!enabled || titlePending || !simulationControlsExpanded) closeRestStopPreview(updateAudio = false)
        val showingTitle = enabled && titlePending
        updateTitleSound(showingTitle)

        val showingPage = enabled && !showingTitle && navigation.page != HostPage.HOME
        renderSceneChrome(enabled)
        renderSimulationControls(enabled)
        renderRestAreaScene()
        if (!enabled) hiddenDriveVersion = maxOf(hiddenDriveVersion, deliveryFrame.version)
        renderDriveProgress()
        onControlsAvailabilityChanged(enabled && !titlePending && simulationControlsExpanded)
        titleView.setPresentationAllowed(showingTitle)
        presentationAudio.setScene(when {
            showingTitle -> PrototypeAudio.Scene.TITLE
            restStopPreview != null -> PrototypeAudio.Scene.REST
            navigation.page == HostPage.GACHA -> PrototypeAudio.Scene.GACHA
            navigation.page == HostPage.DELIVERY -> PrototypeAudio.Scene.DELIVERY
            navigation.page == HostPage.RESULTS -> PrototypeAudio.Scene.SETTLE
            else -> PrototypeAudio.Scene.SHOP
        })
        presentationAudio.setPresentationAllowed(enabled && presentationFocused)
        pageRefresh.visibility = if (showingPage) View.VISIBLE else View.GONE
        pageRefresh.tag = if (showingPage) "host:refresh" else null
        pageRefresh.text = if (navigation.page == HostPage.GACHA) "다시 보기" else "새로고침"
        val showFirstPlayTitle = showingPage && navigation.page == HostPage.ONBOARDING
        firstPlayTitle.visibility = if (showFirstPlayTitle) View.VISIBLE else View.GONE
        firstPlayTitle.tag = if (showFirstPlayTitle) "presentation:home-title" else null
        pageHeading.tag = "host:current-page:${navigation.page.name}"
        if (showingPage && headedPage != navigation.page) {
            headedPage = navigation.page
            val (title, icon) = when (navigation.page) {
                HostPage.HOME -> "" to ReferenceHudIcon.GARAGE
                HostPage.GARAGE -> "내 차고" to ReferenceHudIcon.GARAGE
                HostPage.COLLECTION -> "차량 도감" to ReferenceHudIcon.COLLECTION
                HostPage.GACHA -> "차량 뽑기" to ReferenceHudIcon.GACHA
                HostPage.DELIVERY -> getString(delivery.headingRes) to ReferenceHudIcon.TRUCK
                HostPage.RESULTS -> "영업 정산" to ReferenceHudIcon.SETTLEMENT
                HostPage.RANKING -> "기록" to ReferenceHudIcon.RECORD
                HostPage.ONBOARDING -> "가게 시작하기" to ReferenceHudIcon.GARAGE
                HostPage.PROGRESSION -> "퀘스트·업적·휴식" to ReferenceHudIcon.RECORD
            }
            pageTitle.text = title
            pageTitle.setCompoundDrawablesRelative(ReferenceHudStyle(this).icon(icon, 27), null, null, null)
        }
        homeSoundButton?.text = soundSettingLabel()
    }

    private fun referenceSceneFillsApp() = gameControlsAllowed() && when (navigation.page) {
        HostPage.GACHA -> gacha.fillsAppStage
        HostPage.DELIVERY -> delivery.fillsAppStage
        else -> false
    }

    private fun renderSceneChrome(enabled: Boolean) {
        val fillsApp = referenceSceneFillsApp()
        val changed = (body.tag != null) != fillsApp
        pageHeading.visibility = if (enabled && !titlePending && navigation.page != HostPage.HOME && !fillsApp)
            View.VISIBLE else View.GONE
        body.tag = "host:scene-page:${navigation.page.name}".takeIf { fillsApp }
        updateFeatureWindow(enabled && !titlePending && navigation.page != HostPage.HOME && !fillsApp)
        if (statusOverlay.parent === panel) {
            statusOverlay.visibility = if (enabled && !fillsApp) View.VISIBLE else View.GONE
        }
        if (changed) renderSimulationControls(enabled)
    }

    private fun updateFeatureWindow(show: Boolean) {
        if (featureWindow == show) return
        featureWindow = show
        if (show && windowMap.drawable == null) {
            val map = assets.open("pixel/town.png").use { BitmapFactory.decodeStream(it) }
            windowMap.setImageDrawable(BitmapDrawable(resources, map).apply { isFilterBitmap = false })
        }
        windowMap.visibility = if (show) View.VISIBLE else View.GONE
        panel.background = if (show) ReferenceHudStyle(this).signSurface() else null
        panel.tag = "host:feature-window".takeIf { show }
        val border = if (show) style.dp(7) else 0
        panel.setPadding(border, border, border, border)
        applyFeatureWindowInsets(appRoot.width, appRoot.height)
    }

    private fun applyFeatureWindowInsets(width: Int, height: Int) {
        val params = panel.layoutParams as FrameLayout.LayoutParams
        val horizontal = if (featureWindow) minOf(style.dp(44), width / 24) else 0
        val vertical = if (featureWindow) minOf(style.dp(26), height / 25) else 0
        if (params.leftMargin == horizontal && params.topMargin == vertical) return
        params.setMargins(horizontal, vertical, horizontal, vertical)
        panel.layoutParams = params
    }

    private fun renderRestAreaScene() {
        val view = restAreaScene ?: return
        val simulation = session.simulation ?: return
        val focused = active && stage.hasWindowFocus()
        simulation.setRestPresentationFocused(focused)
        val scene = simulation.parkedRestScene
        val visible = focused && !titlePending && !simulationControlsExpanded
        val arrival = visible && scene != null && simulation.takeRestArrival(scene.stopId)
        view.render(scene, visible, animateArrival = arrival)
    }

    private fun updateTitleSound(showingTitle: Boolean) {
        if (!showingTitle) {
            titleSoundButton?.let(stage::removeView)
            titleSoundButton = null
            return
        }
        val button = titleSoundButton ?: createTitleSoundButton().also { next ->
            titleSoundButton = next
            stage.addView(next, FrameLayout.LayoutParams(-2, style.dp(48), Gravity.TOP or Gravity.END).apply {
                topMargin = style.dp(8)
                marginEnd = style.dp(8)
            })
        }
        val enabled = presentationAudio.enabled
        button.isEnabled = presentationFocused
        val label = if (enabled) "소리 켜짐" else "소리 꺼짐"
        if (button.text != label) button.text = label
        button.contentDescription = soundSettingLabel()
    }

    private fun createTitleSoundButton(): Button {
        val sign = ReferenceHudStyle(this)
        return style.button("소리 꺼짐") {
            if (titlePending && allowed() && presentationFocused) {
                presentationAudio.enabled = !presentationAudio.enabled
                updateTitleSound(true)
            }
        }.apply {
            tag = "presentation:title-sound"
            backgroundTintList = null
            background = sign.signButton()
            setTextColor(sign.signInk)
            compact(style)
            setPadding(style.dp(10), 0, style.dp(10), 0)
        }
    }

    private fun soundSettingLabel() = if (presentationAudio.enabled) "게임 소리 끄기" else "게임 소리 켜기"

    private fun playCelebration(kind: RewardCelebrationKind) {
        if (!allowed() || titlePending || !presentationFocused) return
        presentationAudio.play(when (kind) {
            RewardCelebrationKind.GACHA -> PrototypeAudio.Cue.GACHA
            RewardCelebrationKind.FIRST_GRANT -> PrototypeAudio.Cue.FIRST_GRANT
            RewardCelebrationKind.SETTLEMENT -> PrototypeAudio.Cue.SETTLEMENT
            RewardCelebrationKind.EXPANSION -> PrototypeAudio.Cue.EXPAND
        })
    }

    private fun playDeliveryCue(cue: DeliveryPresentationCue) {
        if (!gameControlsAllowed() || !presentationFocused || navigation.page != HostPage.DELIVERY) return
        presentationAudio.play(when (cue) {
            DeliveryPresentationCue.DEPARTURE -> PrototypeAudio.Cue.DELIVERY_DEPARTURE
            DeliveryPresentationCue.INCOME_ARRIVAL -> PrototypeAudio.Cue.DELIVERY_INCOME_ARRIVAL
            DeliveryPresentationCue.VEHICLE_ARRIVAL -> PrototypeAudio.Cue.DELIVERY_VEHICLE_ARRIVAL
            DeliveryPresentationCue.HELD -> PrototypeAudio.Cue.DELIVERY_HELD
        })
    }

    private fun renderProgression() {
        if (!::progression.isInitialized) return
        val frame = progressionFrame
        val state = frame?.screen ?: io.drivegrowth.app.ui.progress.ProgressScreenState.Loading
        val access = gameControlsAllowed() && navigation.page == HostPage.PROGRESSION && frame?.controlsEnabled == true
        if (state == lastProgressionScreen && access == lastProgressionAccess) return
        lastProgressionScreen = state
        lastProgressionAccess = access
        progression.render(state, access)
    }

    private fun addDriveProgressViews() {
        driveProgressBar = DriveProgressBarView(this,
            onOpen = { captured ->
                if (driveProgressBar.isShown && visibleDriveProgress() == captured) select(HostPage.DELIVERY)
            },
            onCollapse = { captured -> toggleDriveProgress(captured, true) },
        )
        driveProgressChip = DriveProgressChipView(this) { captured -> toggleDriveProgress(captured, false) }
        stage.addView(driveProgressBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        stage.addView(driveProgressChip, FrameLayout.LayoutParams(style.dp(52), style.dp(52), Gravity.BOTTOM or Gravity.END))
        driveProgressBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) renderDriveProgress()
        }
        stage.viewTreeObserver.addOnPreDrawListener {
            if (navigation.page == HostPage.DELIVERY && gameControlsAllowed() && delivery.isShown &&
                session.delivery.state?.displayedProposal != null && !session.delivery.isSavingDisplayedOffers) {
                val shown = deliveryFrame.version
                val ids = delivery.displayedPurchaseIds()
                if (ids.isNotEmpty()) stage.post {
                    if (navigation.page == HostPage.DELIVERY && gameControlsAllowed() && delivery.isShown) {
                        session.delivery.acknowledgeDisplayed(shown, ids)
                    }
                }
            }
            true
        }
        // Confirmation can change the local view without a new host frame.
        stage.viewTreeObserver.addOnGlobalLayoutListener {
            if (navigation.page == HostPage.GACHA) renderDriveProgress()
            if (pageHeading.isShown && navigation.page == HostPage.DELIVERY) {
                val title = getString(delivery.headingRes)
                if (pageTitle.text.toString() != title) pageTitle.text = title
            }
            if ((body.tag != null) != referenceSceneFillsApp()) renderSceneChrome(allowed())
        }
    }

    private fun visibleDriveProgress(): DriveProgress? {
        if (!gameControlsAllowed() || !stage.hasWindowFocus() || homeSettings != null ||
            simulationControlsExpanded || deliveryFrame.version <= hiddenDriveVersion) return null
        val unobstructed = when (navigation.page) {
            HostPage.HOME -> !mapModalOpen && homeSettings == null && !simulationControlsExpanded &&
                !(managementVisible && homeVehicleManagement)
            HostPage.GARAGE -> !managementVisible && garage.selectedVehicleId == null && navigation.pendingGarageRequest == null
            HostPage.GACHA -> gacha.uiState.let { it.frame?.screen is io.drivegrowth.app.ui.gacha.GachaScreen.Offers &&
                it.selected == null && it.pending == null && it.reloadToken == null }
            HostPage.RANKING -> true
            else -> false
        }
        if (!unobstructed) return null
        val identity = session.onboarding.uiState.identity ?: return null
        return DriveProgress.from(deliveryFrame, session.delivery.state, identity)
    }

    private fun toggleDriveProgress(captured: DriveProgress, collapsed: Boolean) {
        if (visibleDriveProgress() != captured) return
        if (!(if (collapsed) driveProgressBar else driveProgressChip).isShown) return
        collapsedDriveKey = captured.key.takeIf { collapsed }
        renderDriveProgress()
        (if (collapsed) driveProgressChip else driveProgressBar).requestFocus()
    }

    private fun renderDriveProgress() {
        if (!::driveProgressBar.isInitialized) return
        val progress = visibleDriveProgress()
        val collapsed = progress != null && collapsedDriveKey == progress.key
        val barShown = progress != null && !collapsed
        val home = navigation.page == HostPage.HOME
        val dock = navigationBar
        val dockHeight = dock?.height?.takeIf { it > 0 } ?: style.dp(80)
        val dockMargin = (dock?.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin ?: style.dp(6)
        val bottom = if (home) dockHeight + dockMargin + style.dp(6) else style.dp(8)
        fun place(view: View, side: Int) {
            val layout = view.layoutParams as FrameLayout.LayoutParams
            if (layout.bottomMargin != bottom || layout.marginEnd != side || layout.marginStart != side) {
                layout.bottomMargin = bottom
                layout.marginStart = side
                layout.marginEnd = side
                view.layoutParams = layout
            }
        }
        driveProgressBar.render(progress.takeIf { barShown })
        driveProgressChip.render(progress.takeIf { collapsed })
        place(driveProgressBar, style.dp(10))
        place(driveProgressChip, style.dp(12))
        val barHeight = driveProgressBar.height.takeIf { it > 0 } ?: style.dp(94)
        val inset = when {
            home || progress == null -> 0
            collapsed -> bottom + style.dp(52)
            else -> bottom + barHeight
        }
        if (body.paddingBottom != inset) body.setPadding(0, 0, 0, inset)
    }

    private fun renderDelivery(enabled: Boolean) {
        delivery.render(deliveryFrame, enabled, session.delivery.state)
        session.deliveryUi = delivery.saveUiState()
    }

    private fun deliveryNavigationControls(): View? {
        val style = featureStyle
        val details = style.column()
        if (session.simulation != null) {
            val estimate = session.delivery.state?.navigation?.estimated as? Observation.Available
            if (estimate == null) {
                details.addView(style.button("모의 목적지 다시 확인") {
                    if (gameControlsAllowed()) { session.deliveryNavigation.refresh(); session.delivery.refresh() }
                })
                return details
            }
            details.addView(style.button("목적지 확인") {
                if (gameControlsAllowed() && session.deliveryNavigation.confirm() is ContractResult.Success) session.delivery.refresh()
            }.apply { tag = "simulation:destination" })
        } else {
            val raw = session.deliveryNavigation.snapshot
            val destination = raw?.destination as? io.drivegrowth.app.vehicle.navigation.NavigationField.Value
            val distance = raw?.remainingEstimatedMeters as? io.drivegrowth.app.vehicle.navigation.NavigationField.Value
            if (destination == null || distance == null || destination.stamp != distance.stamp) {
                details.addView(style.text("목적지를 확인할 수 없어요. 내비에서 목적지를 설정한 뒤 다시 확인해 주세요."))
            }
            details.addView(style.button("내비 목적지 다시 조회") { if (gameControlsAllowed()) session.deliveryNavigation.refresh() })
            if (destination != null && distance != null && destination.stamp == distance.stamp) {
                val name = destination.value.name ?: destination.value.address ?: "이름 없음"
                details.addView(style.text("$name · 남은 예상 거리 ${deliveryDistance(distance.value)}"))
                details.addView(style.button("이 목적지와 남은 예상거리 확인") {
                    if (gameControlsAllowed()) when (session.deliveryNavigation.confirm()) {
                        is ContractResult.Success -> session.delivery.refresh()
                        is ContractResult.Failure -> { session.deliveryNavigation.refresh(); render() }
                    }
                })
            }
        }
        return details
    }

    private fun deliveryHostStatus(): View? {
        val state = session.delivery.state
        val heldVehicles = state?.held.orEmpty()
        val canCancel = session.delivery.canCancelUnstarted
        if (heldVehicles.isEmpty() && state?.receipt == null && !canCancel) return null
        val style = featureStyle
        val details = style.column()
        val names = (screen as? HostQueryState.Content)?.snapshot?.content?.catalog?.vehicles
            ?.associate { it.vehicleId to it.displayName }.orEmpty()
        val date = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))
        for (held in heldVehicles) {
            val name = names[held.vehicle.vehicleId] ?: "차량 정보 확인 중"
            details.addView(style.text("임시 보관 · $name · ${style.rarity(held.vehicle.rarity)} · 낸 금액 ${held.price.units}"))
            details.addView(style.text("보관 기한 ${date.format(Instant.ofEpochMilli(held.deadline.value))} (한국시간)"))
        }
        if (heldVehicles.isNotEmpty())
            details.addView(style.button("보관 차량 처리 조건 다시 확인", enabled = !session.delivery.isSavingDisplayedOffers) { if (gameControlsAllowed()) session.delivery.refresh() })
        state?.receipt?.let { receipt ->
            val message = when (receipt) {
                is DeliveryHostReceipt.Selected -> "의뢰 선택이 확정됐어요."
                is DeliveryHostReceipt.Changed -> "목적지 변경이 확정됐어요."
                is DeliveryHostReceipt.Abandoned -> "의뢰 포기가 확정됐어요."
                is DeliveryHostReceipt.Arrived -> when (receipt.value.outcome) {
                    is DeliveryOutcome.AwaitingEvidence -> "도착 근거를 확인하고 있어요."
                    is DeliveryOutcome.IncomeCompleted -> "수익 탁송 보상이 확정됐어요."
                    is DeliveryOutcome.VehicleDelivered -> "구매 차량 지급이 확정됐어요."
                    is DeliveryOutcome.VehicleHeld -> "구매 차량을 임시 보관했어요."
                    is DeliveryOutcome.IncomeExpired -> "도착 기한이 지나 의뢰가 종료됐어요."
                    is DeliveryOutcome.PurchaseRefunded -> "미도착 의뢰의 구매 대금 환급이 확정됐어요."
                }
                is DeliveryHostReceipt.Held -> when (receipt.value.outcome) {
                    is HoldOutcome.StillHeld -> "차량을 계속 보관하고 있어요."
                    is HoldOutcome.Claimed -> "보관 차량 수령이 확정됐어요."
                    is HoldOutcome.Refunded -> "보관 만료 환급이 확정됐어요."
                }
            }
            details.addView(style.text(message))
            details.addView(style.button("처리 결과 확인하고 새로 조회", enabled = session.delivery.canAcknowledge) {
                if (gameControlsAllowed()) session.delivery.acknowledge()
            }.apply { tag = "delivery:acknowledge" })
        }
        if (canCancel)
            details.addView(style.button("미실행 요청 취소 확인") { if (gameControlsAllowed()) session.delivery.cancelUnstarted() })
        return details
    }

    private fun placeHostStatus() {
        if (chrome.childCount == 0) return
        if (navigation.page == HostPage.HOME) {
            val sign = ReferenceHudStyle(this)
            statusOverlay.background = sign.signSurface()
            statusOverlay.setPadding(style.dp(9), style.dp(5), style.dp(9), style.dp(5))
            for (index in 0 until chrome.childCount) {
                val child = chrome.getChildAt(index)
                if (child is Button) {
                    child.backgroundTintList = null
                    child.background = sign.signButton()
                    child.setTextColor(sign.signInk)
                    child.minHeight = style.dp(48)
                    child.minimumHeight = child.minHeight
                } else if (child is TextView) child.setTextColor(sign.signInk)
            }
            val height = if (chrome.childCount == 1) -2 else style.dp(108)
            val width = if (stage.width == 0) -2 else minOf(stage.width - style.dp(16), style.dp(440))
            stage.addView(statusOverlay, FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                marginStart = style.dp(8)
                marginEnd = style.dp(8)
                topMargin = style.dp(78)
            })
            updateHomeStatusVisibility()
            homeSettings?.bringToFront()
        } else {
            val sign = ReferenceHudStyle(this)
            statusOverlay.background = sign.detailSurface(false)
            statusOverlay.setPadding(style.dp(6), style.dp(4), style.dp(6), style.dp(4))
            for (index in 0 until chrome.childCount) when (val child = chrome.getChildAt(index)) {
                is Button -> child.simulationButtonStyle(sign)
                is TextView -> child.setTextColor(sign.signInk)
            }
            panel.addView(statusOverlay, panel.indexOfChild(stage), LinearLayout.LayoutParams(-1, -2))
            statusOverlay.visibility = if (referenceSceneFillsApp()) View.GONE else View.VISIBLE
        }
    }

    private fun updateHomeStatusVisibility() {
        val recoverVehicle = chrome.findViewWithTag<View>("host:management-retry") != null
        statusOverlay.visibility =
            if (homeSettings == null && (!mapModalOpen || recoverVehicle)) View.VISIBLE else View.GONE
    }

    private fun showBody(view: View) {
        if (body.childCount == 1 && body.getChildAt(0) === view) return
        body.removeAllViews()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        body.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun select(page: HostPage) {
        if (!gameControlsAllowed()) { updateAccess(); return }
        automaticIntroAllowed = false
        navigation.page = page
        navigation.returnPage = HostPage.HOME
        closeHomeSettings()
        if (simulationControlsExpanded) collapseSimulationControls()
        updateAccess()
        render()
    }

    private fun refreshPendingRoute() {
        if (!allowed()) return
        val generation = ++pendingRouteGeneration
        session.pendingRoute { result ->
            if (!isDestroyed && generation == pendingRouteGeneration && allowed()) {
                when (result) {
                    is ContractResult.Failure -> pendingRouteFailed = true
                    is ContractResult.Success -> { pendingRouteFailed = false; pendingPage = result.value }
                }
                if (::body.isInitialized) render()
            }
        }
    }

    private fun openVehicleFromShop(id: OwnedVehicleId, revision: Revision) {
        if (!gameControlsAllowed() || navigation.page != HostPage.HOME || shopRestoreFailed) return
        val current = session.home.state.screen as? ShopScreenState.Content ?: return
        if (current.revision != revision || current.vehicles.none { it.id == id } ||
            shop.uiState.pending != null || navigation.pendingGarageRequest != null) return
        navigation.selectedVehicleId = id
        garage.restoreSelection(id)
        select(HostPage.GARAGE)
        queries.refresh()
    }

    private fun openFocusedVehicleAction(event: GarageEvent.RequestAction) {
        val facility = session.facility.state
        if (!gameControlsAllowed() || navigation.page != HostPage.HOME || shopRestoreFailed ||
            shop.focusedVehicleId != event.id || shop.uiState.pending != null || managementVisible ||
            pendingPage != null || pendingRouteFailed || navigation.pendingGarageRequest != null ||
            facility.busy || facility.pending != null || facility.receipt != null || facility.error != null
        ) return
        val current = (screen as? HostQueryState.Content)?.snapshot?.ownedVehicles?.firstOrNull {
            it.ownedVehicleId == event.id && it.revision == event.revision
        } ?: return
        if (current.lifecycle != io.drivegrowth.core.contract.VehicleLifecycle.OWNED) return
        homeVehicleManagement = true
        prepareGarageAction(event)
    }

    private fun onShopEvent(event: ShopEvent) {
        session.home.remember(shop.uiState)
        if (!gameControlsAllowed() || navigation.page != HostPage.HOME || shopRestoreFailed) {
            updateAccess()
            if (event is ShopEvent.Submit) shop.complete(event.request, ContractResult.Failure(ContractError.UX_RESTRICTED))
            else shop.completeRetry()
            return
        }
        when (event) {
            ShopEvent.Retry -> if (!session.home.refresh()) shop.completeRetry()
            is ShopEvent.Submit -> session.home.submit(event.request)
        }
    }

    private fun onFacilityEvent(event: io.drivegrowth.app.ui.shop.FacilityControlsEvent) {
        if (!gameControlsAllowed() || navigation.page != HostPage.HOME) { updateAccess(); return }
        when (event) {
            is io.drivegrowth.app.ui.shop.FacilityControlsEvent.SetResting -> session.facility.setResting(
                io.drivegrowth.app.host.facility.RestingRequest(event.version,
                    io.drivegrowth.core.contract.RequestId("facility-rest-" + java.util.UUID.randomUUID()),
                    event.vehicle, event.expected, event.desired))
            io.drivegrowth.app.ui.shop.FacilityControlsEvent.Retry -> session.facility.refresh()
            io.drivegrowth.app.ui.shop.FacilityControlsEvent.Acknowledge -> if (session.facility.acknowledge()) {
                session.home.refresh(); queries.refresh(); refreshPendingRoute()
            }
            io.drivegrowth.app.ui.shop.FacilityControlsEvent.CancelSaved -> if (session.facility.cancelSaved()) {
                session.home.refresh(); refreshPendingRoute()
            }
        }
    }

    private fun onGachaEvent(event: GachaEvent) {
        if (!gameControlsAllowed() || navigation.page != HostPage.GACHA) {
            updateAccess()
            when (event) {
                is GachaEvent.Reload -> gacha.completeReload(event.token)
                is GachaEvent.Draw -> gacha.complete(event.request, ContractResult.Failure(ContractError.UX_RESTRICTED))
                is GachaEvent.OpenGarage -> gacha.completeGarageNavigation(event.request)
            }
            return
        }
        when (event) {
            is GachaEvent.Reload -> {
                session.gacha.remember(gacha.uiState)
                val receipt = event.settlementId
                if (receipt == null) session.gacha.refresh(event.token)
                else session.gacha.refreshAfterResult(event.token, receipt)
            }
            is GachaEvent.Draw -> { session.gacha.remember(gacha.uiState); session.gacha.draw(event.request) }
            is GachaEvent.OpenGarage -> {
                session.gacha.acknowledge { result ->
                    if (isDestroyed) return@acknowledge
                    if (active && navigation.page == HostPage.GACHA && gacha.uiState.garagePending == event.request && gameControlsAllowed() && result is ContractResult.Success) {
                        navigation.openAward(event.request.ownedVehicleId)
                        garage.restoreSelection(navigation.selectedVehicleId)
                        queries.refresh()
                        gacha.completeGarageNavigation(event.request)
                        session.gacha.remember(gacha.uiState)
                        updateAccess(); render()
                    } else {
                        gacha.completeGarageNavigation(event.request)
                        session.gacha.remember(gacha.uiState)
                    }
                }
            }
        }
    }

    private fun onResultsEvent(event: ResultsEvent) {
        if (!gameControlsAllowed() || navigation.page != HostPage.RESULTS) {
            updateAccess()
            // View가 callback 전에 만든 Pending은 실제 제출 상태로 되돌린다.
            results.restoreUiState(session.results.uiState)
            return
        }
        session.results.remember(results.uiState)
        when (event) {
            is ResultsEvent.SelectResult, ResultsEvent.CloseDetail -> Unit
            ResultsEvent.Retry -> refresh()
            is ResultsEvent.MarkViewed -> {
                val snapshot = (screen as? HostQueryState.Content)?.snapshot
                if (!session.results.markViewed(event.id, snapshot, gameControlsAllowed())) {
                    results.completeMarkViewed(event.id, ContractResult.Failure(ContractError.STATE_CONFLICT))
                }
            }
        }
    }

    private fun onOnboardingEvent(event: OnboardingEvent) {
        if (!gameControlsAllowed() || navigation.page != HostPage.ONBOARDING) { updateAccess(); return }
        session.onboarding.onEvent(event)
    }

    private fun onGarageEvent(event: GarageEvent) {
        if (!gameControlsAllowed() || navigation.page != HostPage.GARAGE) { updateAccess(); return }
        when (event) {
            is GarageEvent.SelectVehicle -> { navigation.selectedVehicleId = event.id; renderDriveProgress() }
            GarageEvent.CloseDetail -> { navigation.selectedVehicleId = null; renderDriveProgress() }
            GarageEvent.Retry -> refresh()
            GarageEvent.OpenHeldDeliveries -> select(HostPage.DELIVERY)
            is GarageEvent.RequestAction -> prepareGarageAction(event)
        }
    }

    private fun prepareGarageAction(event: GarageEvent.RequestAction) {
        val current = (screen as? HostQueryState.Content)?.snapshot?.ownedVehicles
            ?.find { it.ownedVehicleId == event.id && it.revision == event.revision &&
                it.lifecycle == io.drivegrowth.core.contract.VehicleLifecycle.OWNED }
        if (current == null || navigation.pendingGarageRequest != null) {
            garage.completeRequest()
            return
        }
        navigation.pendingGarageRequest = event
        managementVisible = true
        management.controller.open(event)
        render()
    }

    private fun cancelPreparation() {
        if (!gameControlsAllowed()) { updateAccess(); return }
        if (managementState != GarageCommandState.Idle && !management.controller.cancel()) return
        navigation.pendingGarageRequest = null
        managementVisible = false
        homeVehicleManagement = false
        garage.completeRequest()
        render()
    }

    private fun restoreNavigation(saved: Bundle?): HostNavigation {
        val page = HostPage.entries.firstOrNull { it.name == saved?.getString("page") } ?: HostPage.HOME
        val selected = saved?.getString("selectedVehicle")?.takeIf { it.isNotBlank() }?.let(::OwnedVehicleId)
        val id = saved?.getString("pendingVehicle")?.takeIf { it.isNotBlank() }?.let(::OwnedVehicleId)
        val action = VehicleAction.entries.firstOrNull { it.name == saved?.getString("pendingAction") }
        val revision = saved?.getInt("pendingRevision", -1) ?: -1
        val pending = if (id != null && action != null && revision >= 0)
            GarageEvent.RequestAction(id, Revision(revision), action) else null
        val returnPage = HostPage.entries.firstOrNull { it.name == saved?.getString("returnPage") }
            ?.takeIf { it == HostPage.GACHA } ?: HostPage.HOME
        return HostNavigation(page, selected, pending, returnPage)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (closeRestStopPreview()) return
        if (titlePending && allowed()) { showPresentationTitle(false); return }
        automaticIntroAllowed = false
        if (closeHomeSettings()) return
        if (simulationControlsExpanded) {
            collapseSimulationControls()
            return
        }
        if (allowed()) {
            if ((navigation.page == HostPage.GARAGE || navigation.page == HostPage.HOME && homeVehicleManagement) &&
                managementVisible && managementState != GarageCommandState.Idle
            ) {
                if (managementState is GarageCommandState.Confirmed) management.controller.acknowledge()
                else cancelPreparation()
                return
            }
            if (navigation.page == HostPage.HOME && homeVehicleManagement && navigation.pendingGarageRequest != null) {
                cancelPreparation()
                return
            }
            if (navigation.page == HostPage.HOME && shop.handleBack()) return
            if (navigation.page == HostPage.GACHA && gacha.handleBack()) return
            if (navigation.page == HostPage.DELIVERY && delivery.handleBack()) return
            if (navigation.page == HostPage.RANKING && ranking.handleBack()) return
            if (navigation.page == HostPage.PROGRESSION && progression.handleBack()) return
            if (navigation.pendingGarageRequest != null) { cancelPreparation(); return }
            if (navigation.page == HostPage.GARAGE && garage.closeDetail()) {
                navigation.selectedVehicleId = garage.selectedVehicleId
                renderDriveProgress()
                return
            }
            if (navigation.page == HostPage.RESULTS && results.closeDetail()) return
            if (navigation.page == HostPage.ONBOARDING && onboarding.handleBack()) return
            if (!navigation.back()) { updateAccess(); render(); return }
        }
        // 다중 창에서 Task를 뒤로 보내도 RESUMED·표시가 유지되는 경우를 함께 처리한다.
        backObservation.sentToBack()
        stopObserving()
        setVisible(false)
        rentalAppExit.clear()
        session.rental.inactive(false, session.rentalClock.read())
        moveTaskToBack(true)
    }
}
