package io.drivegrowth.app

import android.app.Activity
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
import io.drivegrowth.app.host.shop.ShopHostMapper
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
    private val shopMapper = ShopHostMapper()
    private lateinit var shop: ShopView
    private lateinit var gacha: GachaView
    private var shopRevision = 0
    private var navigationGeneration = 0L
    private val destinations = listOf(HostPage.HOME to "가게", HostPage.GARAGE to "보유 차량", HostPage.COLLECTION to "도감",
        HostPage.GACHA to "가챠", HostPage.RESULTS to "미확인 정산", HostPage.ONBOARDING to "첫 차량 안내")
    private val mapper = GarageHostMapper()
    private val resultsMapper = ResultsHostMapper()
    private lateinit var results: ResultsView
    private lateinit var onboarding: OnboardingView
    private var pendingOnboardingId: VehicleId? = null
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
        gacha = GachaView(this, ::onGachaEvent)
        onboarding = OnboardingView(this, ::onOnboardingEvent)
        onboarding.restoreSelection(savedInstanceState?.getString("onboardingSelection")?.let(::VehicleId))
        pendingOnboardingId = savedInstanceState?.getString("onboardingPending")?.let(::VehicleId)
        val deliveryHandler = handler
        session = lastNonConfigurationInstance as? HostSession ?: HostSession(applicationContext, { deliveryHandler.post(it) },
            ResultsStateBundle.decode(savedInstanceState?.getBundle("results")))
        results.restoreUiState(session.results.uiState)
        queries = session.queries
        panel = style.column().apply { setBackgroundColor(style.base) }
        chrome = style.column().apply { setPadding(style.dp(16), 0, style.dp(16), 0) }
        body = FrameLayout(this)
        panel.addView(chrome)
        panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(panel)
        session.gacha.attach { frame, enabled ->
            gacha.render(frame, enabled && allowed())
            if (frame.screen != io.drivegrowth.app.ui.gacha.GachaScreen.Loading)
                gacha.uiState.reloadToken?.let(gacha::completeReload)
        }
        queries.attach {
            screen = it
            if (it is HostQueryState.Content) shopRevision = Math.addExact(shopRevision, 1)
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
        session.results.detach()
        active = false
        handler.removeCallbacks(watchdog)
        interaction.reset()
        updateAccess()
        input?.stop()
        input = null
    }

    override fun onDestroy() {
        queries.detach()
        session.gacha.detach()
        gacha.release()
        session.results.detach()
        if (!isChangingConfigurations) session.release()
        super.onDestroy()
    }

    override fun onRetainNonConfigurationInstance(): Any = session

    override fun onSaveInstanceState(outState: Bundle) {
        session.results.remember(results.uiState)
        outState.putBundle("results", ResultsStateBundle.encode(session.results.uiState))
        outState.putString("onboardingSelection", onboarding.selectedVehicleId?.value)
        outState.putString("onboardingPending", (onboarding.pendingVehicleId ?: pendingOnboardingId)?.value)
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
        session.gacha.setControlsEnabled(enabled && navigation.page == HostPage.GACHA)
    }

    private fun refresh() {
        if (!allowed()) { updateAccess(); return }
        if (navigation.page == HostPage.GACHA) session.gacha.refresh()
        else if (screen != HostQueryState.Loading) queries.refresh()
    }

    private fun render() {
        val enabled = allowed()
        val garageState = when (val current = screen) {
            is HostQueryState.Content -> mapper.garage(current.snapshot)
            is HostQueryState.Failure -> GarageScreenState.Error("조회 실패 (${current.error})")
            else -> GarageScreenState.Loading
        }
        val collectionState = when (val current = screen) {
            is HostQueryState.Content -> mapper.collection(current.snapshot, current.snapshot.collectionHistory)
            is HostQueryState.Failure -> CollectionScreenState.Error("조회 실패 (${current.error})")
            else -> CollectionScreenState.Loading
        }
        shop.render(shopMapper.map(screen, Revision(shopRevision)), enabled)
        garage.render(garageState, enabled)
        collection.render(collectionState, enabled)
        results.render(resultsMapper.map(screen), enabled)
        onboarding.render(unavailableOnboarding(), enabled)
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
        navigation.pendingGarageRequest?.takeIf { navigation.page == HostPage.GARAGE }?.let {
            chrome.addView(style.text("관리 명령이 연결되지 않아 실행할 수 없어요. 이 요청은 실행되지 않았어요."))
            chrome.addView(style.button("준비 요청 닫기") { cancelPreparation() })
        }
        when (navigation.page) {
            HostPage.GARAGE -> showBody(garage)
            HostPage.COLLECTION -> showBody(collection)
            HostPage.RESULTS -> showBody(results)
            HostPage.ONBOARDING -> showBody(onboarding)
            HostPage.HOME -> showBody(shop)
            HostPage.GACHA -> showBody(gacha)
        }
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

    private fun onShopEvent(event: ShopEvent) {
        if (!allowed()) { updateAccess(); return }
        when (event) {
            ShopEvent.Retry -> refresh()
            is ShopEvent.Submit -> shop.complete(event.request, ContractResult.Failure(ContractError.DATA_UNAVAILABLE))
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
            is GachaEvent.Reload -> session.gacha.refresh()
            is GachaEvent.Draw -> gacha.complete(event.request, ContractResult.Failure(ContractError.DATA_UNAVAILABLE))
            is GachaEvent.OpenGarage -> {
                navigation.openAward(event.request.ownedVehicleId)
                garage.restoreSelection(navigation.selectedVehicleId)
                gacha.completeGarageNavigation(event.request)
                updateAccess(); render()
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

    private fun unavailableOnboarding() = OnboardingScreenState.Unavailable(
        if (pendingOnboardingId != null) "이전 요청의 지급 상태를 확인할 수 없어요. 초기 지급 조회가 아직 연결되지 않았어요."
        else "초기 지급 이력 조회가 연결되지 않아 신규 프로필 여부를 확인할 수 없어요",
    )

    private fun onOnboardingEvent(event: OnboardingEvent) {
        if (!allowed()) { updateAccess(); return }
        when (event) {
            OnboardingEvent.GoToShop, OnboardingEvent.Previous -> select(HostPage.HOME)
            else -> Unit // 초기 지급 계약 미지원에서는 선택·확정·재시도를 실행하지 않는다.
        }
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
                render()
            }
        }
    }

    private fun cancelPreparation() {
        if (!allowed()) { updateAccess(); return }
        navigation.pendingGarageRequest = null
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
            if (navigation.page == HostPage.HOME && shop.handleBack()) return
            if (navigation.page == HostPage.GACHA && gacha.handleBack()) return
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
