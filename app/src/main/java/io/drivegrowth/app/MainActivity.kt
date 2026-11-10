package io.drivegrowth.app

import android.app.Activity
import android.widget.ScrollView
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
import io.drivegrowth.app.host.shop.ShopExpansionHostController
import io.drivegrowth.app.host.shop.ShopExpansionAccess
import io.drivegrowth.app.host.shop.SessionShopExpansionSource
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

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val interaction = ForegroundInteraction()
    private var input: VehicleInput? = null
    private var navigation = HostNavigation()
    private var screen: HostQueryState = HostQueryState.Restricted
    private lateinit var management: GarageCommandHost
    private lateinit var managementView: GarageCommandView
    private var managementState: GarageCommandState = GarageCommandState.Idle
    private var recoveredManagement = false
    private var managementVisible = false
    private lateinit var expansionSource: SessionShopExpansionSource
    private lateinit var expansionHost: ShopExpansionHostController
    private lateinit var expansionAccess: ShopExpansionAccess
    private var expansionState: ShopScreenState = ShopScreenState.Loading
    private var pendingPage: HostPage? = null
    private var pendingRouteGeneration = 0L
    private var pendingRouteFailed = false
    private var pendingRouteEnabled = false
    private lateinit var shop: ShopView
    private lateinit var ranking: RankingView
    private var rankingState: RankingState = RankingState.Restricted
    private lateinit var delivery: DeliveryView
    private var deliveryAccessEnabled = false
    private var deliveryReceipt: DeliveryHostReceipt? = null
    private lateinit var deliveryBody: LinearLayout
    private var deliveryFrame = DeliveryFrame(0, DeliveryScreen.Loading)
    private lateinit var gacha: GachaView
    private var navigationGeneration = 0L
    private val destinations = listOf(HostPage.HOME to "가게", HostPage.GARAGE to "보유 차량", HostPage.COLLECTION to "도감",
        HostPage.GACHA to "가챠", HostPage.DELIVERY to "탁송", HostPage.RANKING to "기록·랭킹", HostPage.RESULTS to "미확인 정산", HostPage.ONBOARDING to "첫 차량 안내")
    private val mapper = GarageHostMapper()
    private val resultsMapper = ResultsHostMapper()
    private lateinit var results: ResultsView
    private lateinit var onboarding: OnboardingView
    private var onboardingState: OnboardingScreenState = OnboardingScreenState.Loading
    private var routedNewProfile: String? = null
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
        val shopMode = ShopMode.entries.firstOrNull { it.name == savedInstanceState?.getString("shopMode") } ?: ShopMode.OVERALL
        shop.restoreUiState(ShopUiState(mode = shopMode, sceneScrollY = savedInstanceState?.getInt("shopSceneY", 0) ?: 0,
            controlsScrollY = savedInstanceState?.getInt("shopControlsY", 0) ?: 0))
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
        val deliveryHandler = handler
        session = lastNonConfigurationInstance as? HostSession ?: HostSession(applicationContext, { deliveryHandler.post(it) },
            ResultsStateBundle.decode(savedInstanceState?.getBundle("results")),
            OnboardingStateBundle.decode(savedInstanceState?.getBundle("onboardingHost")))
        deliveryFrame = session.deliveryUi.frame ?: DeliveryFrame(0, DeliveryScreen.Loading)
        delivery = DeliveryView(this, { request ->
            session.deliveryUi = delivery.saveUiState()
            session.delivery.onRequest(request)
        }, session.deliveryUi)
        deliveryBody = style.column()
        gacha = GachaView(this, ::onGachaEvent, session.gacha.uiState)
        results.restoreUiState(session.results.uiState)
        queries = session.queries
        expansionSource = SessionShopExpansionSource(applicationContext, session.games)
        expansionHost = ShopExpansionHostController(expansionSource, session::expansionWorker,
            { handler.post(it) }, { state, _ ->
                expansionState = state
                if (state is ShopScreenState.Content && shop.uiState.pending != null)
                    shop.restoreUiState(shop.uiState.copy(pending = null, selection = null, message = "최신 저장 상태를 표시합니다."))
                if (::body.isInitialized) render()
            }, shop::complete, shop::completeRetry, committed = { if (allowed()) queries.refresh() },
            initialRevision = shop.uiState.latestRevision ?: Revision(0))
        expansionAccess = ShopExpansionAccess(expansionHost)
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
            if (state is OnboardingScreenState.Intro && profile != null && routedNewProfile != profile) {
                routedNewProfile = profile
                navigation.page = HostPage.ONBOARDING
            }
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
        })
        session.deliveryNavigation.changed = { if (active && allowed() && navigation.page == HostPage.DELIVERY) render() }
        queries.attach {
            screen = it
            if (it is HostQueryState.Content) refreshPendingRoute()
            render()
            if (it is HostQueryState.Content || it is HostQueryState.Failure) {
                session.results.completeQuery()
                results.completeRetry()
                shop.completeRetry()
            }
        }
        if (savedInstanceState == null) {
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
        setVisible(true)
        if (active) return
        active = true
        session.ranking.attach { rankingState = it; ranking.render(it, allowed() && navigation.page == HostPage.RANKING) }
        if (results.uiState != session.results.uiState) results.restoreUiState(session.results.uiState)
        session.results.attach { id, result ->
            updateAccess()
            results.completeMarkViewed(id, result)
        }
        try {
            input = VehicleInput(this, { if (active) { interaction.accept(it); updateAccess() } }, {}).also { it.start() }
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

    override fun onDestroy() {
        session.deliveryUi = delivery.saveUiState()
        session.delivery.detach()
        session.deliveryNavigation.changed = null
        delivery.release()
        pendingRouteGeneration++
        session.ranking.detach()
        expansionHost.release()
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
        session.results.remember(results.uiState)
        outState.putBundle("results", ResultsStateBundle.encode(session.results.uiState))
        outState.putBundle("onboardingHost", OnboardingStateBundle.encode(session.onboarding.uiState))
        outState.putString("onboardingRoutedProfile", routedNewProfile)
        outState.putString("rankingMode", ranking.uiState.mode.name)
        outState.putString("rankingMetric", ranking.uiState.metric.name)
        outState.putInt("rankingY", ranking.uiState.scrollY)
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

    private fun allowed() = active &&
        interaction.state(SystemClock.elapsedRealtime()) == Observation.Available(VehicleInteraction.ALLOWED)

    private fun updateAccess() {
        val enabled = allowed()
        queries.setControlsEnabled(enabled)
        val deliveryEnabled = enabled && navigation.page == HostPage.DELIVERY
        val deliveryGained = deliveryEnabled && !deliveryAccessEnabled
        deliveryAccessEnabled = deliveryEnabled
        session.deliveryNavigation.setControlsEnabled(deliveryEnabled)
        session.delivery.setControlsEnabled(deliveryEnabled)
        if (deliveryGained) session.delivery.refresh()
        session.gacha.setControlsEnabled(enabled && navigation.page == HostPage.GACHA)
        session.ranking.setControlsEnabled(enabled && navigation.page == HostPage.RANKING)
        session.onboarding.setControlsEnabled(enabled)
        expansionAccess.setControlsEnabled(enabled && navigation.page == HostPage.HOME)
        management.controller.setControlsEnabled(enabled)
        val routeGained = enabled && !pendingRouteEnabled
        pendingRouteEnabled = enabled
        if (routeGained) refreshPendingRoute()
        if (enabled && !recoveredManagement) { recoveredManagement = true; management.controller.recover() }
    }

    private fun refresh() {
        if (!allowed()) { updateAccess(); return }
        session.onboarding.refresh()
        if (navigation.page == HostPage.DELIVERY) { session.deliveryNavigation.refresh(); session.delivery.refresh() }
        else if (navigation.page == HostPage.HOME) expansionHost.load()
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
        val shopState = when (val current = expansionState) {
            is ShopScreenState.Content -> current.copy(name = current.name + when (expansionSource.identity?.source) {
                InputSource.SIMULATION -> " · 모의 원장"
                InputSource.VEHICLE -> " · 차량 원장"
                null -> " · 출처 확인 필요"
            })
            else -> current
        }
        shop.render(shopState, enabled)
        garage.render(garageState, enabled)
        collection.render(collectionState, enabled)
        results.render(resultsMapper.map(screen), enabled)
        onboarding.render(onboardingState, enabled)
        renderDelivery(enabled && navigation.page == HostPage.DELIVERY)
        chrome.removeAllViews()
        if (!enabled) {
            showBody(style.text("주차 후 조작이 허용되면 이용할 수 있습니다."))
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
        if (navigation.page == HostPage.HOME && expansionState is ShopScreenState.Failure)
            chrome.addView(style.button("보관한 미실행 확장 요청 취소") { expansionHost.cancelSaved() })
        if (pendingRouteFailed)
            chrome.addView(style.button("보관 요청 종류를 다시 확인") { refreshPendingRoute() })
        pendingPage?.takeIf { it != navigation.page }?.let { target ->
            chrome.addView(style.button(when (target) {
                HostPage.GACHA -> "보관한 가챠 요청 확인"
                HostPage.GARAGE -> "보관한 차량 관리 요청 확인"
                HostPage.DELIVERY -> "보관한 탁송 요청 확인"
                else -> "보관한 가게 확장 요청 확인"
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
            HostPage.HOME -> showBody(shop)
            HostPage.GACHA -> showBody(gacha)
            HostPage.DELIVERY -> {
                showBody(deliveryBody)
                val shown = deliveryFrame.version
                delivery.post {
                    if (active && allowed() && navigation.page == HostPage.DELIVERY && delivery.isShown &&
                        deliveryFrame.version == shown) session.delivery.acknowledgeDisplayed(shown)
                }
            }

            HostPage.RANKING -> showBody(ranking)
        }
    }

    private fun renderDelivery(enabled: Boolean) {
        delivery.render(deliveryFrame, enabled)
        session.deliveryUi = delivery.saveUiState()
        deliveryBody.removeAllViews()
        val details = style.column()
        if (enabled) {
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
            val state = session.delivery.state
            val names = (screen as? HostQueryState.Content)?.snapshot?.content?.catalog?.vehicles
                ?.associate { it.vehicleId to it.displayName }.orEmpty()
            val date = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))
            for (held in state?.held.orEmpty()) {
                val name = names[held.vehicle.vehicleId] ?: "차량 정보 확인 중"
                details.addView(style.text("임시 보관 · $name · ${style.rarity(held.vehicle.rarity)} · 낸 금액 ${held.price.units}"))
                details.addView(style.text("보관 기한 ${date.format(Instant.ofEpochMilli(held.deadline.value))} (한국시간)"))
            }
            if (state?.held?.isNotEmpty() == true)
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
                details.addView(style.button("처리 결과 확인하고 새로 조회") { session.delivery.acknowledge() })
            }
            if (session.delivery.canCancelUnstarted == true)
                details.addView(style.button("미실행 요청 취소 확인") { session.delivery.cancelUnstarted() })
        }
        if (details.childCount > 0) deliveryBody.addView(ScrollView(this).apply { addView(details) },
            LinearLayout.LayoutParams(-1, style.dp(144)))
        deliveryBody.addView(delivery, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showBody(view: View) {
        if (body.childCount == 1 && body.getChildAt(0) === view) return
        body.removeAllViews()
        body.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun select(page: HostPage) {
        if (!allowed()) { updateAccess(); return }
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
        if (!allowed()) {
            updateAccess()
            if (event is ShopEvent.Submit) shop.complete(event.request, ContractResult.Failure(ContractError.UX_RESTRICTED))
            else shop.completeRetry()
            return
        }
        when (event) {
            ShopEvent.Retry -> if (!expansionHost.load()) shop.completeRetry()
            is ShopEvent.Submit -> expansionHost.submit(event.request)
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
        if (!allowed()) {
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
            if (navigation.pendingGarageRequest != null) { cancelPreparation(); return }
            if (navigation.page == HostPage.GARAGE && garage.closeDetail()) return
            if (navigation.page == HostPage.RESULTS && results.closeDetail()) return
            if (navigation.page == HostPage.ONBOARDING && onboarding.handleBack()) return
            if (!navigation.back()) { updateAccess(); render(); return }
        }
        // 다중 창에서 Task를 뒤로 보내도 RESUMED·표시가 유지되는 경우를 함께 처리한다.
        stopObserving()
        setVisible(false)
        moveTaskToBack(true)
    }
}
