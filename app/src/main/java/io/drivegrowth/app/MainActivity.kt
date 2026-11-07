package io.drivegrowth.app

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import io.drivegrowth.app.host.GarageHostMapper
import io.drivegrowth.app.host.HostNavigation
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.host.HostQueryController
import io.drivegrowth.app.host.HostQueryState
import io.drivegrowth.app.host.HostSession
import io.drivegrowth.app.ui.garage.CollectionScreenState
import io.drivegrowth.app.ui.garage.CollectionView
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.app.ui.garage.GarageScreenState
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.garage.GarageView
import io.drivegrowth.app.vehicle.ForegroundInteraction
import io.drivegrowth.app.vehicle.VehicleInput
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
    private val mapper = GarageHostMapper()
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
        val deliveryHandler = handler
        session = lastNonConfigurationInstance as? HostSession ?: HostSession(applicationContext) { deliveryHandler.post(it) }
        queries = session.queries
        panel = style.column().apply { setBackgroundColor(style.base) }
        chrome = style.column().apply { setPadding(style.dp(16), 0, style.dp(16), 0) }
        body = FrameLayout(this)
        panel.addView(chrome)
        panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(panel)
        queries.attach { screen = it; render() }
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
        active = false
        handler.removeCallbacks(watchdog)
        interaction.reset()
        updateAccess()
        input?.stop()
        input = null
    }

    override fun onDestroy() {
        queries.detach()
        if (!isChangingConfigurations) session.release()
        super.onDestroy()
    }

    override fun onRetainNonConfigurationInstance(): Any = session

    override fun onSaveInstanceState(outState: Bundle) {
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
        queries.setControlsEnabled(allowed())
    }

    private fun refresh() {
        if (allowed() && screen != HostQueryState.Loading) queries.refresh() else updateAccess()
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
        garage.render(garageState, enabled)
        collection.render(collectionState, enabled)
        chrome.removeAllViews()
        if (!enabled) {
            showBody(style.text("주차 후 조작이 허용되면 이용할 수 있습니다."))
            return
        }
        val links = style.row()
        links.addView(style.button("뒤로") { onBackPressed() })
        listOf(HostPage.HOME to "가게", HostPage.GARAGE to "보유 차량", HostPage.COLLECTION to "도감",
            HostPage.RESULTS to "미확인 정산", HostPage.ONBOARDING to "첫 차량 안내").forEach { (page, label) ->
            links.addView(style.button(label, primary = navigation.page == page) { select(page) })
        }
        chrome.addView(HorizontalScrollView(this).apply { addView(links) })
        navigation.pendingGarageRequest?.takeIf { navigation.page == HostPage.GARAGE }?.let {
            chrome.addView(style.text("관리 명령이 연결되지 않아 실행할 수 없어요. 이 요청은 실행되지 않았어요."))
            chrome.addView(style.button("준비 요청 닫기") { cancelPreparation() })
        }
        when (navigation.page) {
            HostPage.GARAGE -> showBody(garage)
            HostPage.COLLECTION -> showBody(collection)
            else -> {
                val page = style.column().apply { setPadding(style.dp(24), style.dp(16), style.dp(24), style.dp(16)) }
                when (val current = screen) {
                    HostQueryState.Restricted -> page.addView(style.text("주차 후 조작이 허용되면 이용할 수 있습니다."))
                    HostQueryState.Loading -> page.addView(style.text("콘텐츠를 불러오는 중입니다."))
                    is HostQueryState.Failure -> {
                        page.addView(style.text("콘텐츠 또는 저장 데이터를 읽지 못했습니다. (${current.error})"))
                        page.addView(style.button("다시 시도", onClick = ::refresh))
                    }
                    is HostQueryState.Content -> {
                        val data = current.snapshot
                        when (navigation.page) {
                            HostPage.HOME -> {
                                page.addView(style.text("드라이브 키우기", 32, bold = true))
                                page.addView(style.text("모의 원장 · ${data.content.catalog.contentVersion.value}"))
                                page.addView(style.text("잔액 ${data.balance.units} · 보유 차량 ${data.ownedVehicles.size}대"))
                            }
                            HostPage.RESULTS -> {
                                // #54의 정식 View 연결 전까지 기존 요약만 원본 정산으로 표시한다.
                                if (data.unviewedSettlements.isEmpty()) page.addView(style.text("미확인 정산이 없습니다."))
                                data.unviewedSettlements.forEach {
                                    page.addView(style.text("정산 ${it.settlementId.value} · 잔액 ${it.balance.units}"))
                                }
                            }
                            HostPage.ONBOARDING -> page.addView(style.text("초기 지급 이력 조회가 연결되지 않아 신규 프로필 여부를 확인할 수 없어요"))
                            else -> Unit
                        }
                        if (navigation.page != HostPage.ONBOARDING)
                            page.addView(style.button("새로고침", onClick = ::refresh))
                    }
                }
                showBody(style.scroll(page))
            }
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
        render()
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
        return HostNavigation(page, selected, pending)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (allowed()) {
            if (navigation.pendingGarageRequest != null) { cancelPreparation(); return }
            if (navigation.page == HostPage.GARAGE && garage.closeDetail()) return
            if (!navigation.back()) { render(); return }
        }
        // 다중 창에서 Task를 뒤로 보내도 RESUMED·표시가 유지되는 경우를 함께 처리한다.
        stopObserving()
        setVisible(false)
        moveTaskToBack(true)
    }
}
