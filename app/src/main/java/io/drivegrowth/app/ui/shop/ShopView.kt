package io.drivegrowth.app.ui.shop

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.GarageEvent
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
    internal var onFocusedRest: ((FacilityControlsEvent) -> Unit)? = null
    internal var focusedVehicleId: OwnedVehicleId? = null
        private set
    private var focusVehicles: Map<OwnedVehicleId, VehiclePresentation>? = null
    private var focusActionsScroll: ScrollView? = null
    private var focusActionsY = 0
    private var focusScene: ShopScreenState.Content? = null
    private var focusRest: Map<OwnedVehicleId, FacilityControlsEvent.SetResting> = emptyMap()
    private var focusRestRecovery = false
    private var managementBusy = false
    private var vehicleActionsBlocked = false
    private var focusFrom: OwnedVehicleId? = null
    private var focusStartedAt: Long? = null
    internal var focusManagementPanel: View? = null
    private var wide = false
    private var sceneScroll: ScrollView? = null
    private var controlsScroll: ScrollView? = null
    private var sceneY = 0
    private var controlsY = 0
    val uiState: ShopUiState get() = interaction.uiState.copy(
        sceneScrollY = sceneScroll?.takeIf { it.isLaidOut }?.scrollY ?: sceneY,
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
    ) {
        val stale = state is ShopScreenState.Content &&
            state.revision.value < (interaction.uiState.latestRevision?.value ?: -1)
        interaction.render(state, controlsEnabled)
        if (!stale) {
            this.focusVehicles = focusVehicles?.filter { it.vehicle.lifecycle == VehicleLifecycle.OWNED }
                ?.associateBy { it.vehicle.ownedVehicleId }
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
        sceneScroll = null; controlsScroll = null
        drawScreen()
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
        interaction.complete(request, result)
        drawScreen()
    }
    fun completeRetry() { interaction.completeRetry(); drawScreen() }
    /** 부모 탐색 전에 가게 미리보기 또는 지도 차량 선택을 닫는다. */
    fun handleBack(): Boolean {
        if (!interaction.controlsEnabled) return false
        if (interaction.cancel()) {
            drawScreen()
            return true
        }
        if (focusedVehicleId == null) return false
        if (!managementBusy) selectFocusedVehicle(null)
        return true
    }

    private fun selectFocusedVehicle(id: OwnedVehicleId?) {
        if (id == focusedVehicleId) return
        focusFrom = focusedVehicleId
        focusedVehicleId = id
        if (id == null) focusScene = null
        focusActionsY = 0
        focusActionsScroll = null
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
        val canManage = interaction.state is ShopScreenState.Content && !vehicleActionsBlocked &&
            !managementBusy && uiState.pending == null
        val rest = focusRest[selected.id]?.takeIf {
            onFocusedRest != null && it.vehicle.ownedVehicleId == selected.id &&
                it.vehicle.revision == facts?.vehicle?.revision
        }
        val recovery = facilityPanel?.takeIf { focusRestRecovery }
        (recovery?.parent as? ViewGroup)?.removeView(recovery)
        val management = focusManagementPanel
        (management?.parent as? ViewGroup)?.removeView(management)
        return ShopVehicleFocusPanel(context, camera, facts, vehicles, selected, recovery, management,
            rest?.let { if (it.expected) "영업 쉬기 끄기" else "영업 쉬기 켜기" },
            !managementBusy && uiState.pending == null, canManage, focusActionsY,
            onBack = { if (acceptsFocusInput(generation)) selectFocusedVehicle(null) },
            onAction = { action ->
                if (acceptsFocusInput(generation) && canManage && focusedVehicleId == selected.id &&
                    facts != null && facts.actions[action]?.enabled == true
                ) {
                    onFocusedVehicleAction?.invoke(GarageEvent.RequestAction(selected.id, facts.vehicle.revision, action))
                }
            },
            onRest = {
                if (acceptsFocusInput(generation) && canManage && focusedVehicleId == selected.id && rest != null) {
                    onFocusedRest?.invoke(rest)
                }
            },
            onSelect = {
                if (acceptsFocusInput(generation) && focusVehicles?.containsKey(it.id) != false) selectFocusedVehicle(it.id)
            },
        ).also { focusActionsScroll = it.actionScroll }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val nextWide = w >= style.dp(840)
        if (nextWide != wide) {
            wide = nextWide
            post { drawScreen() }
        }
    }
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun money(value: GameMoney?) = value?.let { s(R.string.shop_money, NumberFormat.getIntegerInstance(Locale.KOREA).format(it.units)) } ?: s(R.string.shop_unknown)
    private fun drawScreen(): Unit = with(style) {
        sceneScroll?.takeIf { it.isLaidOut }?.let { sceneY = it.scrollY }
        controlsScroll?.takeIf { it.isLaidOut }?.let { controlsY = it.scrollY }
        focusActionsScroll?.takeIf { it.isLaidOut }?.let { focusActionsY = it.scrollY }
        focusActionsScroll = null
        val focus = findFocus()?.tag
        val generation = ++sceneGeneration
        sceneScroll = null; controlsScroll = null; removeAllViews()
        setPadding(dp(24), dp(16), dp(24), dp(16))
        if (!interaction.controlsEnabled) {
            focusScene = null
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
        if (selectedId != null && (state is ShopScreenState.Content || focusVehicles?.containsKey(selectedId) == false)) {
            focusedVehicleId = null
            focusScene = null
            focusActionsY = 0
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
            is ShopScreenState.Content -> {
                val area = if (wide) row() else column()
                addView(area, LayoutParams(-1, 0, 1f))
                if (wide) {
                    sceneScroll = scroll(scene(state, generation), sceneY).apply { tag = "shop:scene-scroll" }
                    controlsScroll = scroll(controls(state), controlsY).apply { tag = "shop:controls-scroll" }
                    area.addView(sceneScroll, LayoutParams(0, -1, 1.8f))
                    area.addView(controlsScroll, LayoutParams(0, -1, 1f).apply { marginStart = dp(20) })
                } else {
                    // 좁은 화면은 한 스크롤로 모든 정보와 확인/취소에 접근한다.
                    controlsScroll = scroll(column().apply { addView(scene(state, generation)); addView(controls(state)) }, controlsY)
                    area.addView(controlsScroll, LayoutParams(-1, -1))
                }
            }
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
    private fun originLabel(content: ShopScreenState.Content) = style.text(
        s(if (content.origin == ShopOrigin.FIXTURE) R.string.shop_fixture else R.string.shop_committed),
        16, color = style.muted,
    )

    private fun withVehicleLabels(content: ShopScreenState.Content): ShopScreenState.Content {
        val labels = vehicleInstanceLabels(content.vehicles.map { it.id to it.name })
        return if (labels.isEmpty()) content else content.copy(vehicles = content.vehicles.map { vehicle ->
            vehicle.copy(name = vehicleDisplayName(vehicle.name, labels[vehicle.id]))
        })
    }

    private fun cameraScene(c: ShopScreenState.Content, generation: Long, onVehicle: ((OwnedVehicleId) -> Unit)?): ShopSceneView {
        val scene = ShopSceneView(context, c, uiState.mode, townBackground, sprites, onVehicle)
        scene.focus(focusedVehicleId, focusFrom, focusStartedAt) { acceptsFocusInput(generation) }
        return scene
    }

    private fun scene(c: ShopScreenState.Content, generation: Long) = with(style) {
        column().apply {
            val openVehicle: ((OwnedVehicleId) -> Unit)? =
                if (onFocusedVehicleAction == null && onOpenVehicle == null) null else { id ->
                    if (acceptsFocusInput(generation)) {
                        if (onFocusedVehicleAction != null && shopSceneModel(c).vehicles.any { it.id == id } &&
                            focusVehicles?.containsKey(id) != false
                        ) selectFocusedVehicle(id)
                        else onOpenVehicle?.invoke(id, c.revision)
                    }
                }
            val display = withVehicleLabels(c)
            val scene = cameraScene(display, generation, openVehicle)
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
            addView(scene)
            addView(originLabel(c))
            addView(text(c.name, 30, bold = true))
            addView(text(s(R.string.shop_level, c.level?.toString() ?: s(R.string.shop_unknown)) + " · " +
                s(R.string.shop_capacity, c.ownedCount?.toString() ?: s(R.string.shop_unknown), c.capacity?.toString() ?: s(R.string.shop_unknown)), 22))
            addView(text(s(R.string.shop_balance, money(c.balance)), 24, color = accent))
            val modes = row()
            for (mode in ShopMode.entries) modes.addView(button(
                s(if (mode == ShopMode.OVERALL) R.string.shop_overall else R.string.shop_vehicles),
                enabled = uiState.pending == null, primary = uiState.mode == mode,
            ) { interaction.mode(mode); drawScreen() }.apply {
                tag = "shop:mode:$mode"
                isSelected = uiState.mode == mode
            },
                LayoutParams(0, -2, 1f).apply { if (mode == ShopMode.VEHICLES) marginStart = dp(8) })
            add(this, modes)
            addView(text(c.appearance.sign, 24, bold = true))
            addView(text(s(R.string.shop_scene_layout_note), 16, color = muted))
            val model = shopSceneModel(c)
            if (model.additionalVehicles > 0) addView(text(s(R.string.shop_scene_more, model.additionalVehicles), 18))
            if (c.capacity != null && c.capacity > townSlots.size) addView(text(s(R.string.shop_scene_capacity_more), 18))
            // 시각 미리보기는 확정 전경을 바꾸지 않는다. 요청안/비용은 기존 확인 흐름에 남긴다.
            (interaction.selection?.option?.command as? ShopCommand.Decorate)?.let { preview ->
                add(this, text(s(R.string.shop_preview_label), color = accent))
                addView(text(s(R.string.shop_after, preview.appearance.sign), 24, bold = true))
            }
            if (c.vehicles.isEmpty()) addView(text(s(R.string.shop_no_vehicles)))
            display.vehicles.forEach { vehicle ->
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
    private fun controls(c: ShopScreenState.Content) = with(style) {
        column().apply {
            uiState.pending?.let { add(this, text(s(R.string.shop_pending), color = accent)) }
            uiState.message?.let { add(this, text(it, color = accent)) }
            uiState.acknowledged?.takeIf { it.revision == c.revision }?.let { add(this, text(s(R.string.shop_wait_refresh))) }
            interaction.selection?.let { selection ->
                add(this, card().apply {
                    addView(text(s(R.string.shop_preview_label), 24, bold = true))
                    addView(text(selection.option.title, 28, bold = true))
                    addView(text(s(R.string.shop_before, when (val command = selection.option.command) {
                        is ShopCommand.Expand -> s(R.string.shop_capacity, c.ownedCount?.toString() ?: s(R.string.shop_unknown), c.capacity?.toString() ?: s(R.string.shop_unknown)) + " · " + s(R.string.shop_level, c.level?.toString() ?: s(R.string.shop_unknown))
                        is ShopCommand.BuyFacility -> s(R.string.shop_available)
                        else -> c.appearance.sign
                    })))
                    addView(text(s(R.string.shop_after, selection.option.effect)))
                    addView(text(s(R.string.shop_price, money(selection.option.price))))
                    addView(text(s(R.string.shop_condition, selection.option.condition)))
                    add(this, button(s(R.string.shop_confirm), interaction.canSelect(selection), primary = true) {
                        interaction.confirm(selection); drawScreen()
                    }.apply { tag = "shop:confirm" })
                    add(this, button(s(R.string.shop_cancel), uiState.pending == null) { interaction.cancel(); drawScreen() }.apply { tag = "shop:cancel" })
                })
            }
            add(this, text(s(R.string.shop_expand), 28, bold = true))
            c.expansion?.let { add(this, option(c, it)) } ?: add(this, text(s(R.string.shop_no_expansion)))
            add(this, text(s(R.string.shop_facilities), 28, bold = true))
            for (facility in Facility.entries) {
                val title = s(when (facility) {
                    Facility.FUEL_STATION -> R.string.shop_facility_fuel
                    Facility.INSURANCE -> R.string.shop_facility_insurance
                    Facility.CAR_WASH -> R.string.shop_facility_wash
                })
                val supplied = c.facilities.find { it.facility == facility }
                if (supplied == null) add(this, card().apply { addView(text(title, 24, bold = true)); addView(text(s(R.string.shop_unknown))) })
                else add(this, option(c, supplied.option, title))
            }
            addFacilityPanel(this)
            add(this, text(s(R.string.shop_decorations), 28, bold = true))
            if (c.decorations.isEmpty()) add(this, text(s(R.string.shop_no_decorations)))
            c.decorations.forEach { add(this, option(c, it)) }
        }
    }
    private fun addFacilityPanel(parent: LinearLayout) = with(style) {
        facilityPanel?.let { panel ->
            (panel.parent as? ViewGroup)?.removeView(panel)
            add(parent, panel)
        }
    }

    private fun option(c: ShopScreenState.Content, option: ShopOption, title: String = option.title) = with(style) {
        card().apply {
            addView(text(title, 26, bold = true))
            addView(text(s(when (option.availability) {
                ShopAvailability.LOCKED -> R.string.shop_locked
                ShopAvailability.AVAILABLE -> R.string.shop_available
                ShopAvailability.INSTALLED -> R.string.shop_installed
                ShopAvailability.UNKNOWN -> R.string.shop_unknown
            }), color = accent))
            addView(text(s(R.string.shop_condition, option.condition)))
            addView(text(s(R.string.shop_effect, option.effect)))
            addView(text(s(R.string.shop_price, money(option.price))))
            if (option.trial) addView(text(s(R.string.shop_trial), color = muted))
            val selection = ShopSelection(c.revision, option)
            if (option.command != null) add(this, button(s(R.string.shop_preview), interaction.canSelect(selection)) {
                interaction.select(selection); controlsY = 0; controlsScroll = null; drawScreen()
            }.apply { tag = "shop:preview:${option.id}" })
        }
    }
}
