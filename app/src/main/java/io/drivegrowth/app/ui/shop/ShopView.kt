package io.drivegrowth.app.ui.shop

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.GarageContent
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.app.ui.garage.GarageScreenState
import io.drivegrowth.app.ui.garage.MapVehicleDetailView
import io.drivegrowth.app.ui.garage.VehiclePresentation
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.app.ui.presentation.RewardCelebrationKind
import io.drivegrowth.app.ui.vehicleDisplayName
import io.drivegrowth.app.ui.vehicleInstanceLabels
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.rental.Facility
import java.text.NumberFormat
import java.util.Locale

/** 주차 gate·저장·거래는 호스트 책임. 단독 생성해 fixture를 render할 수 있다. */
class ShopView(context: Context, onEvent: (ShopEvent) -> Unit) : LinearLayout(context) {
    private val style = ShopStyle(context)
    internal var onCelebration: ((RewardCelebrationKind) -> Unit)? = null
    private var submittedExpansion: ShopRequest? = null
    private var committedExpansion: ShopRequest? = null
    private val interaction = ShopInteraction({ event ->
        if (event is ShopEvent.Submit) rememberExpansion(event.request)
        onEvent(event)
    })

    private val sprites = VehicleSprites(context)
    private val townBackground by lazy {
        requireNotNull(context.assets.open("pixel/town.png").use { BitmapFactory.decodeStream(it) })
    }
    private var sceneGeneration = 0L
    internal var facilityPanel: View? = null
    internal var onOpenVehicle: ((OwnedVehicleId, Revision) -> Unit)? = null
    internal var onFocusedVehicleAction: ((GarageEvent.RequestAction) -> Unit)? = null
    internal var onFocusedVehicleRetry: (() -> Unit)? = null
    private val vehicleDetail = MapVehicleDetailView(context) { event ->
        when (event) {
            GarageEvent.CloseDetail -> selectFocusedVehicle(null)
            GarageEvent.Retry -> onFocusedVehicleRetry?.invoke()
            is GarageEvent.RequestAction -> onFocusedVehicleAction?.invoke(event)
            is GarageEvent.SelectVehicle -> Unit
        }
    }
    internal var onFocusedRest: ((FacilityControlsEvent) -> Unit)? = null
    internal var focusedVehicleId: OwnedVehicleId? = null
        private set
    private var focusVehicles: Map<OwnedVehicleId, VehiclePresentation>? = null
    private var focusGarageError: String? = null
    private var focusScene: ShopScreenState.Content? = null
    private var focusRest: Map<OwnedVehicleId, FacilityControlsEvent.SetResting> = emptyMap()
    private var focusRestRecovery = false
    private var managementBusy = false
    private var vehicleActionsBlocked = false
    private var focusFrom: OwnedVehicleId? = null
    private var focusStartedAt: Long? = null
    internal var focusManagementPanel: View? = null
    private var panelTarget: MapPanel? = null
    private var mapOverlay: ShopMapOverlayView? = null
    private var dismissedPending: ShopRequest? = null
    private var mapPan = ShopMapOffset()
    private var viewedMode = ShopMode.OVERALL
    private var unviewedCount: Int? = null
    private var operatingStatus: String? = null
    private var openResults: (() -> Unit)? = null
    private var openSettings: (() -> Unit)? = null

    /** 호스트가 확인한 요약만 표시한다. 다음 render에서 같은 화면 세대로 함께 배치한다. */
    internal fun updateMapHud(
        unviewedCount: Int?,
        operatingStatus: String? = null,
        onResults: (() -> Unit)? = null,
        onSettings: (() -> Unit)? = null,
    ) {
        this.unviewedCount = unviewedCount
        this.operatingStatus = operatingStatus
        openResults = onResults
        openSettings = onSettings
    }

    internal fun showMapPanel(target: ShopMapTarget) = openMapPanel(MapPanel.Details(target))

    private sealed interface MapPanel {
        data class Details(val target: ShopMapTarget) : MapPanel
        data object VehicleList : MapPanel
        data object FacilityMenu : MapPanel
        data object SettingsMenu : MapPanel
    }

    private fun openMapPanel(target: MapPanel) {
        if (!interaction.controlsEnabled || interaction.state !is ShopScreenState.Content ||
            uiState.pending != null || managementBusy
        ) return
        if (interaction.selection != null && panelTarget != target) interaction.cancel()
        panelTarget = target
        focusedVehicleId = null
        focusScene = null
        focusFrom = null
        focusStartedAt = null
        controlsY = 0
        controlsScroll = null
        drawScreen()
    }

    private var controlsScroll: ScrollView? = null
    private var sceneY = 0
    private var controlsY = 0
    private var projectedBalance: Map<String, GameMoney> = emptyMap()

    internal fun updateMapProjectedBalance(value: Map<String, GameMoney>) {
        projectedBalance = value.toMap()
    }

    val uiState: ShopUiState get() = interaction.uiState.copy(
        sceneScrollY = sceneY,
        controlsScrollY = controlsScroll?.takeIf { it.isLaidOut }?.scrollY ?: controlsY,
    )

    init { orientation = VERTICAL; setBackgroundColor(style.base) }
    fun render(state: ShopScreenState, controlsEnabled: Boolean) = render(state, controlsEnabled, null)

    internal fun render(
        state: ShopScreenState,
        controlsEnabled: Boolean,
        focusVehicles: List<VehiclePresentation>?,
        focusRest: Map<OwnedVehicleId, FacilityControlsEvent.SetResting> = emptyMap(),
        focusRestRecovery: Boolean = false,
        managementBusy: Boolean = false,
        vehicleActionsBlocked: Boolean = false,
        focusError: String? = null,
    ) {
        val stale = state is ShopScreenState.Content &&
            state.revision.value < (interaction.uiState.latestRevision?.value ?: -1)
        interaction.render(state, controlsEnabled)
        if (!stale) {
            this.focusVehicles = focusVehicles?.takeIf { focusError == null }
                ?.filter { it.vehicle.lifecycle == VehicleLifecycle.OWNED }?.associateBy { it.vehicle.ownedVehicleId }
            focusGarageError = focusError
            this.focusRest = focusRest
            this.focusRestRecovery = focusRestRecovery
            this.managementBusy = managementBusy
            this.vehicleActionsBlocked = vehicleActionsBlocked
        }
        drawScreen()
    }
    fun restoreUiState(state: ShopUiState) {
        interaction.restoreUiState(state)
        sceneY = state.sceneScrollY.coerceAtLeast(0); controlsY = state.controlsScrollY.coerceAtLeast(0)
        controlsScroll = null
        drawScreen()
    }
    internal fun restoreFocusedVehicle(id: OwnedVehicleId?) {
        focusedVehicleId = id
        if (id != null) panelTarget = null
        focusScene = null
        focusFrom = null
        focusStartedAt = null
        drawScreen()
    }
    internal fun containsManagementPanel(panel: View): Boolean {
        var container = panel.parent
        while (container != null) {
            if (container === this) return true
            container = container.parent
        }
        return false
    }

    private fun rememberExpansion(request: ShopRequest) {
        val offer = (request.selection.option.command as? ShopCommand.Expand)?.offer ?: return
        committedExpansion = null
        val current = interaction.state as? ShopScreenState.Content
        submittedExpansion = request.takeIf {
            current?.origin == ShopOrigin.STORED && current.level == offer.fromLevel &&
                current.capacity == offer.currentCapacity
        }
    }
    fun complete(request: ShopRequest, result: ContractResult<Unit>) {
        if (request == submittedExpansion && request == uiState.pending) {
            submittedExpansion = null
            committedExpansion = request.takeIf { result is ContractResult.Success }
        }
        val current = request == uiState.pending
        interaction.complete(request, result)
        if (current) {
            panelTarget = null
            dismissedPending = null
        }
        drawScreen()
    }
    fun completeRetry() { interaction.completeRetry(); drawScreen() }
    /** 부모 탐색 전에 가게 미리보기 또는 지도 차량 선택을 닫는다. */
    fun handleBack(): Boolean {
        if (!interaction.controlsEnabled) return false
        if (mapOverlay?.handleBack() == true) return true
        if (interaction.cancel()) {
            drawScreen()
            return true
        }
        if (panelTarget != null) {
            if (uiState.pending == null) {
                panelTarget = null
                drawScreen()
            }
            return true
        }
        if (focusedVehicleId == null) return false
        if (!managementBusy && !vehicleDetail.handleBack()) selectFocusedVehicle(null)
        return true
    }

    private fun selectFocusedVehicle(id: OwnedVehicleId?) {
        if (id == focusedVehicleId) return
        focusFrom = focusedVehicleId
        if (id != null) panelTarget = null
        focusedVehicleId = id
        if (id == null) focusScene = null
        focusStartedAt = SystemClock.uptimeMillis()
        drawScreen()
    }

    private fun acceptsFocusInput(generation: Long) =
        sceneGeneration == generation && interaction.controlsEnabled && uiState.pending == null && !managementBusy

    private fun focusPanel(c: ShopScreenState.Content, selected: ShopVehicle, generation: Long): View {
        val display = withVehicleLabels(c)
        val vehicles = shopSceneModel(display).vehicles
        val camera = cameraScene(display, generation, onVehicle = { id ->
            if (acceptsFocusInput(generation) && focusVehicles?.containsKey(id) != false) selectFocusedVehicle(id)
        })
        val facts = focusVehicles?.get(selected.id)
        val canManage = facts != null && interaction.state is ShopScreenState.Content && !vehicleActionsBlocked &&
            !managementBusy && uiState.pending == null
        val rest = focusRest[selected.id]?.takeIf {
            onFocusedRest != null && it.vehicle.ownedVehicleId == selected.id &&
                it.vehicle.revision == facts?.vehicle?.revision
        }
        val management = focusManagementPanel
        val recovery = facilityPanel?.takeIf { focusRestRecovery }
        if (management != null || recovery != null) {
            vehicleDetail.render(GarageScreenState.Loading, null, false, true)
            val content = management ?: checkNotNull(recovery)
            (content.parent as? ViewGroup)?.removeView(content)
            return FrameLayout(context).apply {
                tag = "shop:focus-panel"
                addView(camera, FrameLayout.LayoutParams(-1, -1))
                val sheet = ShopMapSheet(context, content, controlsY, !managementBusy) {
                    if (acceptsFocusInput(generation)) selectFocusedVehicle(null)
                }
                controlsScroll = sheet.scroll
                addView(sheet, FrameLayout.LayoutParams(-1, -1))
            }
        }
        if (!managementBusy) vehicleDetail.completeRequest()
        val detailState = focusGarageError?.let { GarageScreenState.Error(it) }
            ?: focusVehicles?.values?.toList()?.let { GarageScreenState.Content(GarageContent(it, null, "")) }
            ?: GarageScreenState.Loading
        vehicleDetail.render(detailState, selected.id, interaction.controlsEnabled, !canManage)
        vehicleDetail.updateRest(rest?.let { if (it.expected) "영업 쉬기 끄기" else "영업 쉬기 켜기" }, canManage) {
            if (acceptsFocusInput(generation) && canManage && focusedVehicleId == selected.id && rest != null) {
                onFocusedRest?.invoke(rest)
            }
        }
        (vehicleDetail.parent as? ViewGroup)?.removeView(vehicleDetail)
        return FrameLayout(context).apply {
            tag = "shop:focus-panel"
            addView(camera, FrameLayout.LayoutParams(-1, -1))
            addView(vehicleDetail, FrameLayout.LayoutParams(-1, -1))
        }
    }
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun money(value: GameMoney?) = value?.let { s(R.string.shop_money, NumberFormat.getIntegerInstance(Locale.KOREA).format(it.units)) } ?: s(R.string.shop_unknown)
    private fun drawScreen(): Unit = with(style) {
        controlsScroll?.takeIf { it.isLaidOut }?.let { controlsY = it.scrollY }
        val focus = findFocus()?.tag
        val generation = ++sceneGeneration
        // 교체된 overlay의 버튼도 현재 ShopInteraction으로 명령을 보내지 못하게 한다.
        mapOverlay?.visibility = View.GONE
        mapOverlay = null
        controlsScroll = null; removeAllViews()
        setPadding(0, 0, 0, 0)
        if (!interaction.controlsEnabled) {
            vehicleDetail.render(GarageScreenState.Loading, null, false, true)
            focusScene = null
            panelTarget = null
            focusFrom = null
            focusStartedAt = null
            submittedExpansion = null
            committedExpansion = null
            addView(text(s(R.string.shop_restricted), 28))
            return@with
        }
        val state = interaction.state
        val selectedId = focusedVehicleId
        val focusContent = if (state == ShopScreenState.Loading) focusScene else state as? ShopScreenState.Content
        val selected = focusContent?.let { content ->
            shopSceneModel(content).vehicles.firstOrNull { it.id == selectedId && focusVehicles?.containsKey(it.id) != false }
        }
        if (focusContent != null && selected != null) {
            focusScene = focusContent
            addView(focusPanel(focusContent, selected, generation), LayoutParams(-1, 0, 1f))
            if (focus != null) findViewWithTag<View>(focus)?.takeIf { it.isEnabled }?.requestFocus()
            return@with
        }
        vehicleDetail.render(GarageScreenState.Loading, null, false, true)
        if (selectedId != null && (state is ShopScreenState.Content || focusVehicles?.containsKey(selectedId) == false)) {
            focusedVehicleId = null
            focusScene = null
            focusFrom = null
            focusStartedAt = null
        }
        when (state) {
            ShopScreenState.Loading -> {
                val content = column().apply {
                    addView(text(s(R.string.shop_title), 32, bold = true))
                    addView(text(s(R.string.shop_loading)))
                    addFacilityPanel(this)
                }
                addView(scroll(content, controlsY), LayoutParams(-1, 0, 1f))
            }
            is ShopScreenState.Empty -> problem(s(R.string.shop_empty), state.message, true)
            is ShopScreenState.Failure -> problem(s(R.string.shop_failure), state.message, true)
            is ShopScreenState.Unsupported -> problem(s(R.string.shop_unsupported), state.message, false)
            is ShopScreenState.Content -> addView(mapScreen(state, generation), LayoutParams(-1, 0, 1f))
        }
        if (focus != null) findViewWithTag<View>(focus)?.takeIf { it.isEnabled }?.requestFocus()
    }
    private fun problem(title: String, body: String, retry: Boolean) = with(style) {
        val content = column().apply {
            addView(text(s(R.string.shop_title), 32, bold = true))
            addView(text(title, 28, bold = true)); addView(text(body))
            addFacilityPanel(this)
            if (retry) add(this, button(s(if (uiState.retryPending) R.string.shop_retry_pending else R.string.shop_retry), !uiState.retryPending) {
                interaction.retry(); drawScreen()
            }.apply { tag = "shop:retry" })
        }
        addView(scroll(content, 0), LayoutParams(-1, 0, 1f))
    }
    private fun withVehicleLabels(content: ShopScreenState.Content): ShopScreenState.Content {
        val labels = vehicleInstanceLabels(content.vehicles.map { it.id to it.name })
        return if (labels.isEmpty()) content else content.copy(vehicles = content.vehicles.map { vehicle ->
            vehicle.copy(name = vehicleDisplayName(vehicle.name, labels[vehicle.id]))
        })
    }

    private fun cameraScene(
        c: ShopScreenState.Content,
        generation: Long,
        onVehicle: ((OwnedVehicleId) -> Unit)?,
        onMapTarget: ((ShopMapTarget) -> Unit)? = null,
    ): ShopSceneView {
        val scene = ShopSceneView(context, c, uiState.mode, townBackground, sprites, onVehicle,
            fillViewport = true, initialPan = mapPan, onPan = { mapPan = it }, onMapTarget = onMapTarget)
        scene.focus(focusedVehicleId, focusFrom, focusStartedAt) { acceptsFocusInput(generation) }
        return scene
    }

    private fun mapScreen(c: ShopScreenState.Content, generation: Long) = with(style) {
        val frame = FrameLayout(context).apply { tag = "shop:map-screen" }
        if (viewedMode != uiState.mode) {
            viewedMode = uiState.mode
            mapPan = ShopMapOffset()
        }
        val openVehicle: ((OwnedVehicleId) -> Unit)? =
            if (onFocusedVehicleAction == null && onOpenVehicle == null) null else { id ->
                if (acceptsFocusInput(generation)) {
                    if (onFocusedVehicleAction != null && shopSceneModel(c).vehicles.any { it.id == id } &&
                        focusVehicles?.containsKey(id) != false
                    ) selectFocusedVehicle(id)
                    else if (onOpenVehicle != null) {
                        panelTarget = null
                        onOpenVehicle?.invoke(id, c.revision)
                        drawScreen()
                    }
                }
            }
        val display = withVehicleLabels(c)
        val scene = cameraScene(display, generation, openVehicle) { target ->
            if (acceptsFocusInput(generation)) showMapPanel(target)
        }
        frame.addView(scene, FrameLayout.LayoutParams(-1, -1))
        val request = committedExpansion
        val offer = (request?.selection?.option?.command as? ShopCommand.Expand)?.offer
        if (request != null && offer != null && c.origin == ShopOrigin.STORED &&
            c.revision.value > request.selection.revision.value
        ) {
            if (c.level == offer.fromLevel + 1 && c.capacity == offer.nextCapacity) {
                scene.highlightExpansion(offer.currentCapacity until minOf(offer.nextCapacity, townSlots.size)) {
                    if (sceneGeneration != generation || committedExpansion?.requestId != request.requestId ||
                        !interaction.controlsEnabled
                    ) return@highlightExpansion false
                    committedExpansion = null
                    onCelebration?.invoke(RewardCelebrationKind.EXPANSION)
                    true
                }
            } else if (c.level != null && c.capacity != null &&
                (c.level != offer.fromLevel || c.capacity != offer.currentCapacity)
            ) {
                committedExpansion = null
            }
        }
        val review = interaction.selection
        val pending = uiState.pending
        val target = when {
            pending != null -> pending.takeUnless { it == dismissedPending }
                ?.let { MapPanel.Details(it.selection.option.mapTarget()) }
            review != null -> MapPanel.Details(review.option.mapTarget())
            focusRestRecovery && panelTarget == null -> MapPanel.FacilityMenu
            else -> panelTarget
        }
        if (target != null) {
            scene.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (target is MapPanel.Details) {
                val overlay = ShopMapOverlayView(context,
                    onClose = {
                        if (sceneGeneration == generation) {
                            panelTarget = null
                            dismissedPending = uiState.pending
                            drawScreen()
                        }
                    },
                    onChanged = { if (sceneGeneration == generation) drawScreen() },
                )
                mapOverlay = overlay
                overlay.render(target.target, interaction, projectedBalance)
                frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            } else {
                val dim = View(context).apply {
                    setBackgroundColor(0x880d1423.toInt())
                    isClickable = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                frame.addView(dim, FrameLayout.LayoutParams(-1, -1))
                val content = when (target) {
                    MapPanel.VehicleList -> vehicleList(display, openVehicle)
                    MapPanel.SettingsMenu -> style.column().apply {
                        add(this, mapButton("꾸미기", "shop:map-decorations") { showMapPanel(ShopMapTarget.Decorations) })
                    }
                    else -> facilityMenu()
                }
                val sheet = ShopMapSheet(context, content, controlsY, uiState.pending == null) {
                    if (sceneGeneration == generation) closeMapPanel()
                }
                controlsScroll = sheet.scroll
                frame.addView(sheet, FrameLayout.LayoutParams(-1, -1))
            }
        } else {
            val hud = ShopMapHud(context, c, money(c.balance), operatingStatus, unviewedCount,
                openResults?.let { action -> { if (acceptsFocusInput(generation)) action() } },
                { if (acceptsFocusInput(generation)) {
                    val settings = openSettings
                    if (settings != null) settings() else openMapPanel(MapPanel.SettingsMenu)
                } })
            frame.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                marginStart = dp(8); marginEnd = dp(8); topMargin = dp(8)
            })
            val toolbar = row().apply {
                for (mode in ShopMode.entries) {
                    val selected = uiState.mode == mode
                    val button = mapButton(
                        if (mode == ShopMode.OVERALL) "전체 보기" else "차량 중심 보기",
                        "shop:mode:$mode", selected,
                    ) { if (!selected) { interaction.mode(mode); drawScreen() } }
                    button.isSelected = selected
                    addView(button, LayoutParams(0, -2, 1f))
                }
                addView(mapButton("시설", "shop:map-facilities") {
                    openMapPanel(MapPanel.FacilityMenu)
                }, LayoutParams(0, -2, 1f))
                addView(mapButton("차량 목록", "shop:map-vehicles") {
                    openMapPanel(MapPanel.VehicleList)
                }, LayoutParams(0, -2, 1f))
            }
            frame.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply {
                marginStart = dp(8); marginEnd = dp(8); bottomMargin = dp(8)
            })
            val notice = uiState.message ?: uiState.acknowledged?.takeIf { it.revision == c.revision }
                ?.let { s(R.string.shop_wait_refresh) }
            notice?.let { message ->
                frame.addView(text(message, 16, accent).apply { background = shape(panel) },
                    FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                        marginStart = dp(8); marginEnd = dp(8); bottomMargin = dp(62)
                    })
            }
        }
        frame
    }

    private fun closeMapPanel() {
        if (!interaction.controlsEnabled || uiState.pending != null) return
        interaction.cancel()
        panelTarget = null
        drawScreen()
    }

    private fun mapButton(label: String, key: String, selected: Boolean = false, action: () -> Unit) =
        style.button(label, primary = selected, action = guardedMapAction(action)).apply {
            tag = key
            textSize = 15f
            minHeight = style.dp(48)
            minimumHeight = style.dp(48)
            setPadding(style.dp(4), 0, style.dp(4), 0)
        }

    private fun guardedMapAction(action: () -> Unit): () -> Unit {
        val generation = sceneGeneration
        return { if (acceptsFocusInput(generation)) action() }
    }

    private fun ShopOption.mapTarget(): ShopMapTarget = when (val option = command) {
        is ShopCommand.Expand -> ShopMapTarget.Expansion
        is ShopCommand.BuyFacility -> ShopMapTarget.FacilityLot(option.offer.facility)
        else -> ShopMapTarget.Decorations
    }

    private fun vehicleList(c: ShopScreenState.Content, openVehicle: ((OwnedVehicleId) -> Unit)?) = with(style) {
        column().apply {
            addView(text("차량 목록", 24, bold = true))
            if (c.vehicles.isEmpty()) addView(text(s(R.string.shop_no_vehicles)))
            for (vehicle in c.vehicles) {
                add(this, row().apply {
                    vehicle.vehicleId?.let(sprites::drawable)?.let { sprite ->
                        addView(ImageView(context).apply {
                            setImageDrawable(sprite)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LayoutParams(dp(84), dp(68)))
                    }
                    addView(column().apply {
                        if (openVehicle == null) addView(text(vehicle.name, 24, bold = true))
                        else addView(button(vehicle.name) { openVehicle(vehicle.id) }.apply {
                            tag = "shop:list-vehicle:${vehicle.id.value}"
                            contentDescription = s(R.string.shop_scene_vehicle_detail, vehicle.name, vehicle.status)
                        })
                        addView(text(vehicle.status, 20))
                    }, LayoutParams(0, -2, 1f))
                })
            }
        }
    }
    private fun facilityMenu() = style.column().apply {
        style.add(this, mapButton("가게 확장", "shop:menu-expansion") { showMapPanel(ShopMapTarget.Expansion) })
        for (facility in Facility.entries) {
            val title = s(when (facility) {
                Facility.FUEL_STATION -> R.string.shop_facility_fuel
                Facility.INSURANCE -> R.string.shop_facility_insurance
                Facility.CAR_WASH -> R.string.shop_facility_wash
            })
            style.add(this, mapButton(title, "shop:menu-facility:${facility.name}") {
                showMapPanel(ShopMapTarget.FacilityLot(facility))
            })
        }
        style.add(this, mapButton("꾸미기", "shop:map-decorations") { showMapPanel(ShopMapTarget.Decorations) })
        addFacilityPanel(this)
    }

    private fun addFacilityPanel(parent: LinearLayout) = with(style) {
        facilityPanel?.let { panel ->
            (panel.parent as? ViewGroup)?.removeView(panel)
            add(parent, panel)
        }
    }

}
