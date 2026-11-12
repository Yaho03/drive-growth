package io.drivegrowth.app

import android.app.Activity
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
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.LinearLayout
import io.drivegrowth.app.host.garage.GarageCommandHost
import io.drivegrowth.app.host.garage.GarageCommandState
import io.drivegrowth.app.host.garage.GarageCommandView
import io.drivegrowth.app.host.garage.GarageActionAvailability
import io.drivegrowth.app.ui.shop.ShopScreenState
import io.drivegrowth.app.ui.shop.ShopView
import io.drivegrowth.app.ui.shop.ShopEvent
import io.drivegrowth.app.ui.shop.ShopMode
import io.drivegrowth.app.ui.shop.ShopUiState
import io.drivegrowth.app.ui.gacha.GachaView
import io.drivegrowth.app.ui.gacha.GachaEvent
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
import io.drivegrowth.app.host.ResultsHostMapper
import io.drivegrowth.app.host.ResultsStateBundle
import io.drivegrowth.app.ui.results.ResultsEvent
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
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.OwnedVehicleId
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
    private lateinit var facilityControls: io.drivegrowth.app.ui.shop.FacilityControlsView
    private lateinit var facilityHome: LinearLayout
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
    private var deliveryReceipt: DeliveryHostReceipt? = null
    private var deliveryFrame = DeliveryFrame(0, DeliveryScreen.Loading)
    private lateinit var gacha: GachaView
    private var navigationGeneration = 0L
    private val destinations = listOf(HostPage.HOME to "가게", HostPage.GARAGE to "보유 차량", HostPage.COLLECTION to "도감",
        HostPage.GACHA to "가챠", HostPage.PROGRESSION to "퀘스트·진행", HostPage.DELIVERY to "탁송", HostPage.RANKING to "기록·랭킹", HostPage.RESULTS to "미확인 정산", HostPage.ONBOARDING to "첫 차량 안내")
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
    private lateinit var garage: GarageView
    private lateinit var collection: CollectionView
    private lateinit var body: FrameLayout
    private lateinit var chrome: LinearLayout
    private var active = false
    private lateinit var panel: LinearLayout
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
        style = GarageStyle(this)
        garage = GarageView(this, ::onGarageEvent).also { it.restoreSelection(navigation.selectedVehicleId) }
        collection = CollectionView(this, ::refresh)
        results = ResultsView(this, ::onResultsEvent)
        shop = ShopView(this, ::onShopEvent)
        facilityControls = io.drivegrowth.app.ui.shop.FacilityControlsView(this, ::onFacilityEvent)
        val facilityPanel = android.widget.ScrollView(this).apply { addView(facilityControls) }
        facilityHome = object : LinearLayout(this) {
            init {
                orientation = VERTICAL
                addView(shop, LayoutParams(-1, 0, 1f))
                addView(facilityPanel, LayoutParams(-1, style.dp(220)))
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = MeasureSpec.getSize(heightMeasureSpec)
                if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                    // Leave room for the shop header and controls in a short app window.
                    facilityPanel.layoutParams.height = minOf(style.dp(220),
                        maxOf(available / 3, available - style.dp(150)))
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
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
            if (allowed() && navigation.page == HostPage.RANKING) session.ranking.refresh()
            else { updateAccess(); ranking.render(RankingState.Restricted, false) }
        }
        ranking.restoreUiState(RankingUiState(
            RankingMode.entries.firstOrNull { it.name == savedInstanceState?.getString("rankingMode") } ?: RankingMode.MY_RECORD,
            RankingMetric.entries.firstOrNull { it.name == savedInstanceState?.getString("rankingMetric") } ?: RankingMetric.SHOP_LEVEL,
            (savedInstanceState?.getInt("rankingY", 0) ?: 0).coerceAtLeast(0)))
        onboarding = OnboardingView(this, ::onOnboardingEvent)
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
            if (allowed() && navigation.page == HostPage.PROGRESSION) session.progression.handle(event) else updateAccess()
        }
        if (lastNonConfigurationInstance == null) {
            when (val decoded = io.drivegrowth.app.host.progression.ProgressionUiStateBundle.decode(savedInstanceState?.getBundle("progression"))) {
                is ContractResult.Success -> session.progressionUi = decoded.value
                is ContractResult.Failure -> progressionRestoreFailed = true
            }
        }
        progression.restoreUiState(session.progressionUi)
        deliveryFrame = session.deliveryUi.frame ?: DeliveryFrame(0, DeliveryScreen.Loading)
        delivery = DeliveryView(this, { request ->
            if (restStartTicket == null) {
                session.deliveryUi = delivery.saveUiState()
                session.delivery.onRequest(request)
            }
        }, session.deliveryUi, navigationControls = ::deliveryNavigationControls, hostStatus = ::deliveryHostStatus)
        gacha = GachaView(this, ::onGachaEvent, session.gacha.uiState)
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
            if (state == GarageCommandState.Idle) managementVisible = false
            if (state is GarageCommandState.Review || state is GarageCommandState.Running || state is GarageCommandState.Confirmed)
                managementVisible = true
            if ((state is GarageCommandState.Confirmed || state is GarageCommandState.Rejected) && allowed()) queries.refresh()
            if (state == GarageCommandState.Idle && prior is GarageCommandState.Confirmed) {
                navigation.pendingGarageRequest = null
                managementVisible = false
                garage.completeRequest()
            }
            if (::body.isInitialized) render()
        }
        managementView = GarageCommandView(this, { management.controller.confirm() }, ::cancelPreparation,
            { management.controller.retry() }, { management.controller.cancelPending() }, { management.controller.chooseMaterial(it) }, { management.controller.acknowledge() })
        panel = style.column().apply { setBackgroundColor(style.base) }
        chrome = style.column().apply { setPadding(style.dp(16), 0, style.dp(16), 0) }
        body = FrameLayout(this)
        panel.addView(chrome)
        panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(panel)
        session.gacha.attach({ frame, enabled ->
            gacha.render(frame, enabled && allowed())
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
            if (!onboarding.render(state, enabled && allowed())) {
                // 기존 View의 Completed 역행 방지와 새 저장 조회 실패/프로필 세대를 분리한다.
                onboarding = OnboardingView(this, ::onOnboardingEvent)
                onboarding.restoreSelection(session.onboarding.uiState.selected)
                onboarding.render(state, enabled && allowed())
            }
            onboarding.restoreSelection(session.onboarding.uiState.selected)
            val profile = session.onboarding.uiState.identity?.profileId?.value
            if (state is OnboardingScreenState.Intro)
                routedNewProfile = navigation.acceptInitialIntro(profile, routedNewProfile, automaticIntroAllowed)
            render()
        }, {
            // Completed의 이동 입력 잠금은 새 View에서 초기화하고 저장 결과를 다시 공급한다.
            onboarding = OnboardingView(this, ::onOnboardingEvent)
            onboarding.restoreSelection(session.onboarding.uiState.selected)
            onboarding.render(onboardingState, allowed())
            select(HostPage.HOME)
        }, {
            queries.refresh()
            session.onboarding.refresh()
        if (navigation.page == HostPage.GACHA) session.gacha.refresh()
        })
        session.delivery.attach({ frame, enabled ->
            deliveryFrame = frame
            val receipt = session.delivery.state?.receipt
            if (receipt != null && receipt != deliveryReceipt) { deliveryReceipt = receipt; queries.refresh() }
            delivery.render(frame, enabled && allowed() && navigation.page == HostPage.DELIVERY)
            session.deliveryUi = delivery.saveUiState()
            if (::chrome.isInitialized) render()
        }, { request, frame ->
            delivery.complete(request, frame)
            session.deliveryUi = delivery.saveUiState()
        }, queries::refresh)
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
        startObserving()
    }

    @android.annotation.TargetApi(29)
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        if (isTopResumedActivity) startObserving()
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
                    if (result.value.waiting != null) "주행 기록의 정산 근거를 확인하고 있어요."
                    else "수신한 주행 관측을 기록하고 있어요."
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
        session.ranking.attach { rankingState = it; ranking.render(it, allowed() && navigation.page == HostPage.RANKING) }
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
        stopObserving()
        super.onPause()
    }

    private fun stopObserving() {
        restStartTicket = null
        session.simulation?.stop()
        session.simulation?.changed = null
        active = false // 새 UI/관측 입구를 먼저 닫고 SDK queue를 중단한다.
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
        outState.putString("selectedVehicle", navigation.selectedVehicleId?.value)
        navigation.pendingGarageRequest?.let {
            outState.putString("pendingVehicle", it.id.value)
            outState.putInt("pendingRevision", it.revision.value)
            outState.putString("pendingAction", it.action.name)
        }
        super.onSaveInstanceState(outState)
    }

    protected fun allowed() = active && restStartTicket == null &&
        (session.simulation?.controlsAllowed ?: (interaction.state(SystemClock.elapsedRealtime()) == Observation.Available(VehicleInteraction.ALLOWED)))

    protected fun canStartRestSimulation(): Boolean =
        session.simulation != null && navigation.page != HostPage.ONBOARDING && allowed()

    protected fun startRestSimulation() {
        if (!canStartRestSimulation()) return
        val simulation = session.simulation ?: return
        val ticket = Any()
        restStartTicket = ticket
        // 같은 UI 이벤트에서 새 탁송 요청을 닫고, 앞선 거래 뒤 worker에서 원장을 확인한다.
        updateAccess()
        session.prepareSimulation(rest = true) { result ->
            if (restStartTicket !== ticket) return@prepareSimulation
            try {
                if (active && simulation.controlsAllowed && navigation.page != HostPage.ONBOARDING) when (result) {
                    is ContractResult.Failure -> simulation.rejected(result.error)
                    is ContractResult.Success -> simulation.beginRest()
                }
            } finally {
                restStartTicket = null
                if (active) updateAccess()
            }
        }
    }

    protected open fun onControlsAvailabilityChanged(allowed: Boolean) = Unit

    private fun updateAccess() {
        val enabled = allowed()
        onControlsAvailabilityChanged(enabled)
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
        if (!allowed()) { updateAccess(); return }
        session.onboarding.refresh()
        if (navigation.page == HostPage.RESULTS) { session.results.remember(results.uiState); session.results.refreshHistory() }
        else if (navigation.page == HostPage.PROGRESSION) session.progression.refresh()
        else if (navigation.page == HostPage.DELIVERY) { session.deliveryNavigation.refresh(); session.delivery.refresh() }
        else if (navigation.page == HostPage.HOME) { session.facility.refresh(); session.home.refresh() }
        else if (navigation.page == HostPage.GACHA) session.gacha.refresh()
        else if (navigation.page == HostPage.RANKING) session.ranking.refresh()
        else if (screen != HostQueryState.Loading) queries.refresh()
    }

    private fun render() {
        val enabled = allowed()
        val garageState = when (val current = screen) {
            is HostQueryState.Content -> GarageActionAvailability.map(mapper.garage(current.snapshot), current.snapshot)
            is HostQueryState.Failure -> GarageScreenState.Error("조회 실패 (${current.error})")
            else -> GarageScreenState.Loading
        }
        val collectionState = when (val current = screen) {
            is HostQueryState.Content -> mapper.collection(current.snapshot, current.snapshot.collectionHistory)
            is HostQueryState.Failure -> CollectionScreenState.Error("조회 실패 (${current.error})")
            else -> CollectionScreenState.Loading
        }
        ranking.render(rankingState, enabled && navigation.page == HostPage.RANKING)
        managementView.render(managementState, enabled)
        val homeEnabled = enabled && navigation.page == HostPage.HOME && !shopRestoreFailed
        shop.render(if (shopRestoreFailed) ShopScreenState.Failure("가게 화면 상태를 복원하지 못해 요청을 보존하고 조작을 제한합니다.")
            else session.home.state.screen, homeEnabled)
        val facility = session.facility.state
        val names = (screen as? HostQueryState.Content)?.snapshot?.content?.catalog?.vehicles
            ?.associate { it.vehicleId to it.displayName }.orEmpty()
        facilityControls.render(facility, homeEnabled && session.home.facilityControlsEnabled, names)
        garage.render(garageState, enabled)
        collection.render(collectionState, enabled)
        results.render(if (session.results.hasHistorySource) session.results.historyState else resultsMapper.map(screen), enabled)
        onboarding.render(onboardingState, enabled)
        renderDelivery(enabled && navigation.page == HostPage.DELIVERY)
        chrome.removeAllViews()
        session.simulation?.let { simulation ->
            chrome.addView(style.text(simulation.status).apply { tag = "simulation:status" })
            chrome.addView(style.button("모의주행 시작 · 논리 1시간") {
                if (allowed() && navigation.page != HostPage.ONBOARDING) session.prepareSimulation { result ->
                    if (allowed()) when (result) {
                        is ContractResult.Failure -> simulation.rejected(result.error)
                        is ContractResult.Success -> simulation.begin()
                    }
                }
            }.apply {
                tag = "simulation:start"; isEnabled = enabled && navigation.page != HostPage.ONBOARDING
            })
            if (simulation.canRetry) chrome.addView(style.button("같은 모의 입력 재시도") { simulation.retry() }.apply { tag = "simulation:retry" })
        }
        if (!enabled) {
            showBody(style.text(if (session.simulation == null) "주차 후 조작이 허용되면 이용할 수 있습니다." else "명시 모의주행 중입니다. 완료 후 모의 주차 화면에서 결과를 확인하세요."))
            return
        }
        val token = ++navigationGeneration
        val links = style.row()
        links.addView(style.button("뒤로") { if (token == navigationGeneration) onBackPressed() })
        val destination = Spinner(this).apply {
            tag = "host:destination"
            contentDescription = "화면 선택"
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, destinations.map { it.second })
            setSelection(destinations.indexOfFirst { it.first == navigation.page }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val target = destinations.getOrNull(position)?.first ?: return
                    if (token == navigationGeneration && target != navigation.page) select(target)
                }
            }
        }
        links.addView(destination, LinearLayout.LayoutParams(0, style.dp(56), 1f))
        links.addView(style.button("새로고침") { if (token == navigationGeneration) refresh() })
        chrome.addView(links)
        drivingStatus?.let { chrome.addView(style.text(it)) }
        if (navigation.page == HostPage.HOME && homeEnabled) {
            val home = session.home.state
            if (home.activationRequired) chrome.addView(style.button("꾸미기 기능 사용 시작") {
                if (allowed() && navigation.page == HostPage.HOME && !shopRestoreFailed) session.home.activateDecoration()
            }.apply { tag = "decoration:activate"; isEnabled = homeEnabled })
            (home.decoration?.receipt ?: (home.original as? io.drivegrowth.app.data.HostCheckpointResult.Decoration)?.value)?.let { receipt ->
                chrome.addView(style.text("꾸미기 원결과 · ${receipt.command.decorationId} · 비용 ${receipt.command.expectedPrice.units} · " +
                    (if (receipt.settlement == null) "비금융 결과" else "확정 금융 정산") + " · 당시 잔액 ${receipt.state.balance.units}")
                    .apply { tag = "decoration:receipt" })
            }
            val recovered = when (val original = home.original) {
                is io.drivegrowth.app.data.HostCheckpointResult.Expand -> "확장" to original.value
                is io.drivegrowth.app.data.HostCheckpointResult.FacilityPurchase -> "시설" to original.value
                else -> null
            }
            recovered?.let { (kind, receipt) ->
                chrome.addView(style.text("$kind 원결과 확인 · 당시 잔액 ${receipt.balance.units} · 현재 상태는 별도 조회합니다.")
                    .apply { tag = "shop:original-receipt" })
            }
            if (home.decoration?.pending != null && home.decoration.receipt?.command != home.decoration.pending)
                chrome.addView(style.button("보관한 미실행 꾸미기 요청 취소") {
                    if (allowed() && navigation.page == HostPage.HOME) session.home.cancelDecorationSaved()
                }.apply { tag = "decoration:cancel-saved"; isEnabled = homeEnabled })
            if (home.screen is ShopScreenState.Failure) chrome.addView(style.button("보관한 미실행 확장 요청 취소") {
                if (allowed() && navigation.page == HostPage.HOME) session.home.cancelExpansionSaved()
            }.apply { isEnabled = homeEnabled })
        }
        if (navigation.page == HostPage.PROGRESSION) {
            if (progressionRestoreFailed) chrome.addView(style.text("화면 상태를 복원하지 못해 저장 기록을 다시 확인합니다."))
            chrome.addView(style.button("미시작 보상 청구 취소 확인") { if (allowed()) session.progression.cancelSaved() })
        }
        if (pendingRouteFailed)
            chrome.addView(style.button("보관 요청 종류를 다시 확인") { refreshPendingRoute() })
        pendingPage?.takeIf { it != navigation.page }?.let { target ->
            chrome.addView(style.button(when (target) {
                HostPage.GACHA -> "보관한 가챠 요청 확인"
                HostPage.GARAGE -> "보관한 차량 관리 요청 확인"
                HostPage.DELIVERY -> "보관한 탁송 요청 확인"
                HostPage.PROGRESSION -> "보관한 보상 청구 확인"
                else -> "보관한 가게 요청 확인"
            }) {
                select(target)
                if (target == HostPage.GARAGE) { managementVisible = true; management.controller.recover() }
            })
        }
        if (navigation.page == HostPage.GACHA && session.gacha.failed)
            chrome.addView(style.button("보관한 미실행 가챠 요청 취소") { session.gacha.cancelUnstarted() })
        navigation.pendingGarageRequest?.takeIf { navigation.page == HostPage.GARAGE && !managementVisible }?.let {
            chrome.addView(style.text("이전 관리 준비 요청입니다. 최신 상태와 비용을 다시 확인해 주세요."))
            chrome.addView(style.button("관리 조건 다시 조회") { managementVisible = true; management.controller.open(it) })
            chrome.addView(style.button("준비 요청 닫기") { cancelPreparation() })
        }
        when (navigation.page) {
            HostPage.GARAGE -> showBody(if (managementVisible) managementView else garage)
            HostPage.COLLECTION -> showBody(collection)
            HostPage.RESULTS -> showBody(results)
            HostPage.ONBOARDING -> showBody(onboarding)
            HostPage.HOME -> showBody(facilityHome)
            HostPage.GACHA -> showBody(gacha)
            HostPage.DELIVERY -> {
                showBody(delivery)
                val shown = deliveryFrame.version
                delivery.post {
                    if (active && allowed() && navigation.page == HostPage.DELIVERY && delivery.isShown &&
                        deliveryFrame.version == shown) session.delivery.acknowledgeDisplayed(shown)
                }
            }

            HostPage.RANKING -> showBody(ranking)
            HostPage.PROGRESSION -> { renderProgression(); showBody(progression) }
        }
    }

    private fun renderProgression() {
        if (!::progression.isInitialized) return
        val frame = progressionFrame
        val state = frame?.screen ?: io.drivegrowth.app.ui.progress.ProgressScreenState.Loading
        val access = allowed() && navigation.page == HostPage.PROGRESSION && frame?.controlsEnabled == true
        if (state == lastProgressionScreen && access == lastProgressionAccess) return
        lastProgressionScreen = state
        lastProgressionAccess = access
        progression.render(state, access)
    }

    private fun renderDelivery(enabled: Boolean) {
        delivery.render(deliveryFrame, enabled)
        session.deliveryUi = delivery.saveUiState()
    }

    private fun deliveryNavigationControls(): View {
        val details = style.column()
        if (session.simulation != null) {
            details.addView(style.text(session.deliveryNavigation.description.orEmpty()))
            details.addView(style.button("시연 목적지 확인") {
                if (allowed() && session.deliveryNavigation.confirm() is ContractResult.Success) session.delivery.refresh()
            }.apply { tag = "simulation:destination" })
        } else {
            val raw = session.deliveryNavigation.snapshot
            val destination = raw?.destination as? io.drivegrowth.app.vehicle.navigation.NavigationField.Value
            val distance = raw?.remainingEstimatedMeters as? io.drivegrowth.app.vehicle.navigation.NavigationField.Value
            details.addView(style.text("내비에서 목적지를 먼저 설정한 뒤 여기서 조회하고 확인해 주세요."))
            details.addView(style.button("내비 목적지 다시 조회") { session.deliveryNavigation.refresh() })
            if (destination != null && distance != null && destination.stamp == distance.stamp) {
                details.addView(style.text("내비 반환 목적지: ${destination.value.name ?: "이름 없음"} · ${destination.value.address ?: "주소 없음"}"))
                details.addView(style.text("남은 예상거리 ${distance.value}m · 수신 UTC ${destination.stamp.receivedAtMillis}ms"))
                details.addView(style.text("남은 예상거리는 실제 이동거리·도착 증거와 별개예요."))
                details.addView(style.button("이 목적지와 남은 예상거리 확인") {
                    when (session.deliveryNavigation.confirm()) {
                        is ContractResult.Success -> session.delivery.refresh()
                        is ContractResult.Failure -> { session.deliveryNavigation.refresh(); render() }
                    }
                })
            }
            details.addView(style.text("실제 이동거리와 도착 상관 근거는 확인 대기예요. 프로필만으로 실차 주행을 확정하지 않아요."))
        }
        return details
    }

    private fun deliveryHostStatus(): View? {
        val state = session.delivery.state
        val heldVehicles = state?.held.orEmpty()
        val canCancel = session.delivery.canCancelUnstarted
        if (heldVehicles.isEmpty() && state?.receipt == null && !canCancel) return null
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
            details.addView(style.button("보관 차량 처리 조건 다시 확인") { session.delivery.refresh() })
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
            details.addView(style.button("처리 결과 확인하고 새로 조회") { session.delivery.acknowledge() }.apply { tag = "delivery:acknowledge" })
        }
        if (canCancel)
            details.addView(style.button("미실행 요청 취소 확인") { session.delivery.cancelUnstarted() })
        return details
    }

    private fun showBody(view: View) {
        if (body.childCount == 1 && body.getChildAt(0) === view) return
        body.removeAllViews()
        body.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun select(page: HostPage) {
        if (!allowed()) { updateAccess(); return }
        automaticIntroAllowed = false
        navigation.page = page
        navigation.returnPage = HostPage.HOME
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

    private fun onShopEvent(event: ShopEvent) {
        session.home.remember(shop.uiState)
        if (!allowed() || navigation.page != HostPage.HOME || shopRestoreFailed) {
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
        if (!allowed() || navigation.page != HostPage.HOME) { updateAccess(); return }
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
        if (!allowed()) {
            updateAccess()
            when (event) {
                is GachaEvent.Reload -> gacha.completeReload(event.token)
                is GachaEvent.Draw -> gacha.complete(event.request, ContractResult.Failure(ContractError.UX_RESTRICTED))
                is GachaEvent.OpenGarage -> gacha.completeGarageNavigation(event.request)
            }
            return
        }
        when (event) {
            is GachaEvent.Reload -> { session.gacha.remember(gacha.uiState); session.gacha.refresh(event.token) }
            is GachaEvent.Draw -> { session.gacha.remember(gacha.uiState); session.gacha.draw(event.request) }
            is GachaEvent.OpenGarage -> {
                session.gacha.acknowledge { result ->
                    if (isDestroyed) return@acknowledge
                    if (active && navigation.page == HostPage.GACHA && gacha.uiState.garagePending == event.request && allowed() && result is ContractResult.Success) {
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
        if (!allowed() || navigation.page != HostPage.RESULTS) {
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
                if (!session.results.markViewed(event.id, snapshot, allowed())) {
                    results.completeMarkViewed(event.id, ContractResult.Failure(ContractError.STATE_CONFLICT))
                }
            }
        }
    }

    private fun onOnboardingEvent(event: OnboardingEvent) {
        if (!allowed()) { updateAccess(); return }
        session.onboarding.onEvent(event)
    }

    private fun onGarageEvent(event: GarageEvent) {
        if (!allowed()) { updateAccess(); return }
        when (event) {
            is GarageEvent.SelectVehicle -> navigation.selectedVehicleId = event.id
            GarageEvent.CloseDetail -> navigation.selectedVehicleId = null
            GarageEvent.Retry -> refresh()
            is GarageEvent.RequestAction -> {
                val current = (screen as? HostQueryState.Content)?.snapshot?.ownedVehicles
                    ?.find { it.ownedVehicleId == event.id && it.revision == event.revision }
                if (current == null || navigation.pendingGarageRequest != null) {
                    garage.completeRequest()
                    return
                }
                navigation.pendingGarageRequest = event
                managementVisible = true
                management.controller.open(event)
                render()
            }
        }
    }

    private fun cancelPreparation() {
        if (!allowed()) { updateAccess(); return }
        if (managementState != GarageCommandState.Idle && !management.controller.cancel()) return
        navigation.pendingGarageRequest = null
        managementVisible = false
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
        automaticIntroAllowed = false
        if (allowed()) {
            if (navigation.page == HostPage.GARAGE && managementVisible && managementState != GarageCommandState.Idle) {
                if (managementState is GarageCommandState.Confirmed) management.controller.acknowledge()
                else cancelPreparation()
                return
            }
            if (navigation.page == HostPage.HOME && shop.handleBack()) return
            if (navigation.page == HostPage.GACHA && gacha.handleBack()) return
            if (navigation.page == HostPage.DELIVERY && delivery.handleBack()) return
            if (navigation.page == HostPage.RANKING && ranking.handleBack()) return
            if (navigation.page == HostPage.PROGRESSION && progression.handleBack()) return
            if (navigation.pendingGarageRequest != null) { cancelPreparation(); return }
            if (navigation.page == HostPage.GARAGE && garage.closeDetail()) return
            if (navigation.page == HostPage.RESULTS && results.closeDetail()) return
            if (navigation.page == HostPage.ONBOARDING && onboarding.handleBack()) return
            if (!navigation.back()) { updateAccess(); render(); return }
        }
        // 다중 창에서 Task를 뒤로 보내도 RESUMED·표시가 유지되는 경우를 함께 처리한다.
        stopObserving()
        setVisible(false)
        rentalAppExit.clear()
        session.rental.inactive(false, session.rentalClock.read())
        moveTaskToBack(true)
    }
}
