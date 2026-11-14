package io.drivegrowth.app.ui.shop

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.RentalMapEvent
import io.drivegrowth.app.host.facility.FacilityReceipt

/** 주차 gate·저장·거래는 호스트 책임. 단독 생성해 fixture를 render할 수 있다. */
class ShopView(context: Context, onEvent: (ShopEvent) -> Unit) : LinearLayout(context) {
    private val style = ShopStyle(context)
    internal var onCelebration: ((RewardCelebrationKind) -> Unit)? = null
    private var submittedExpansion: ShopRequest? = null
    private var committedExpansion: ShopRequest? = null
    private val installation = FacilityInstallationMotion()
    private val rentalMotion = RentalMapPresentation()
    private var carriedRentalMotion: ShopSceneView.RentalMotionFrame? = null

    internal fun queueRentalMapEvents(identity: GameIdentity, events: List<RentalMapEvent>) {
        val content = interaction.state as? ShopScreenState.Content ?: return
        val scene = activeCamera ?: return
        if (!canPresentRentalMapMotion() || content.origin != ShopOrigin.STORED) return
        rentalMotion.offer(identity, content, events, SystemClock.uptimeMillis(), scene::visibleRentalSlot)
    }

    internal fun dismissRentalMapMotion() {
        rentalMotion.clear()
        carriedRentalMotion = null
        activeCamera?.stopRentalMapMotion()
    }

    private fun canPresentRentalMapMotion() = canPresentInstallation() && !mapModalOpen &&
        focusedVehicleId == null && !managementBusy && uiState.pending == null
    private var pendingInstallation: InstalledFacilityEffect? = null
    private var installedFacility: Facility? = null

    private fun canPresentInstallation() = interaction.controlsEnabled && !hostOverlayOpen &&
        isAttachedToWindow && isShown && hasWindowFocus() && windowVisibility == VISIBLE &&
        ValueAnimator.areAnimatorsEnabled()

    internal fun submitInstallation(identity: GameIdentity?, request: ShopRequest): Boolean {
        val shown = interaction.state as? ShopScreenState.Content ?: return false
        return installation.submit(identity, request,
            shown.origin == ShopOrigin.STORED && canPresentInstallation() && request == uiState.pending)
    }

    internal fun confirmInstallation(request: ShopRequest, receipt: FacilityReceipt.Purchased) {
        val facility = installation.confirm(request, receipt, canPresentInstallation() && request == uiState.pending) ?: return
        pendingInstallation = InstalledFacilityEffect(facility, request.selection.revision, SystemClock.uptimeMillis())
        installedFacility = facility
    }

    internal fun dismissInstallation() {
        installation.clear()
        pendingInstallation = null
        activeCamera?.stopFacilityInstallation()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            dismissInstallation()
            dismissRentalMapMotion()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            dismissInstallation()
            dismissRentalMapMotion()
        }
    }

    override fun onDetachedFromWindow() {
        dismissRentalMapMotion()
        dismissInstallation()
        installedFacility = null
        super.onDetachedFromWindow()
    }
    private val interaction = ShopInteraction({ event ->
        if (event is ShopEvent.Submit) rememberExpansion(event.request)
        onEvent(event)
    })

    private val sprites = VehicleSprites(context)
    private var cachedTown: Pair<TownGrowthArt?, Bitmap>? = null

    private fun townBackground(growth: TownGrowthArt?): Bitmap =
        cachedTown?.takeIf { it.first == growth }?.second ?: run {
            val path = growth?.assetPath ?: "pixel/town.png"
            val bitmap = requireNotNull(context.assets.open(path).use { BitmapFactory.decodeStream(it) })
            cachedTown = growth to bitmap
            bitmap
        }
    private var sceneGeneration = 0L
    private var displayChanged = true
    internal var facilityPanel: View? = null
        set(value) {
            displayChanged = displayChanged || field !== value
            field = value
        }
    internal var onActivateDecoration: (() -> Unit)? = null
        set(value) {
            displayChanged = displayChanged || (field == null) != (value == null)
            field = value
        }
    internal var onOpenVehicle: ((OwnedVehicleId, Revision) -> Unit)? = null
    internal var onFocusedVehicleAction: ((GarageEvent.RequestAction) -> Unit)? = null
    internal var onFocusedVehicleRetry: (() -> Unit)? = null
    private var preparingVehicleAction = false
    private val vehicleDetail: MapVehicleDetailView = MapVehicleDetailView(context, ::onVehicleDetailEvent)

    private fun onVehicleDetailEvent(event: GarageEvent) {
        when (event) {
            GarageEvent.CloseDetail -> selectFocusedVehicle(null)
            GarageEvent.Retry -> onFocusedVehicleRetry?.invoke()
            is GarageEvent.RequestAction -> {
                preparingVehicleAction = true
                try {
                    onFocusedVehicleAction?.invoke(event)
                } finally {
                    preparingVehicleAction = false
                    if (!managementBusy) vehicleDetailRequestFinished()
                }
            }
            is GarageEvent.SelectVehicle -> Unit
            GarageEvent.OpenHeldDeliveries -> Unit
        }
    }
    private fun vehicleDetailRequestFinished() { vehicleDetail.completeRequest() }

    internal var onFocusedRest: ((FacilityControlsEvent) -> Unit)? = null
    internal var focusedVehicleId: OwnedVehicleId? = null
        private set
    private var focusVehicles: Map<OwnedVehicleId, VehiclePresentation>? = null
    private var focusGarageError: String? = null
    private var focusScene: ShopScreenState.Content? = null
    private var lastMapContent: ShopScreenState.Content? = null
    private var focusRest: Map<OwnedVehicleId, FacilityControlsEvent.SetResting> = emptyMap()
    private var focusRestRecovery = false
    private var recoveryDismissed = false
    private var managementBusy = false
    private var vehicleActionsBlocked = false
    private var activeCamera: ShopSceneView? = null
    private var previousCamera: ShopMapCamera? = null
    private var cameraRegion: ShopFocusRegion? = null
    private var cameraFrom: ShopMapCamera? = null
    private var cameraStartedAt: Long? = null
    internal var focusManagementPanel: View? = null
        set(value) {
            displayChanged = displayChanged || field !== value
            field = value
        }
    private var panelTarget: MapPanel? = null
    private var hostOverlayOpen = false

    internal fun setHostOverlayOpen(open: Boolean) {
        if (open) {
            dismissInstallation()
            dismissRentalMapMotion()
        }
        hostOverlayOpen = open
        descendantFocusability = if (open) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }
    private var activeSheet: MapPanel? = null
    private var mapOverlay: ShopMapOverlayView? = null
    private var dismissedPending: ShopRequest? = null
    private var mapPan = ShopMapOffset()
    private var viewedMode = ShopMode.OVERALL
    private var operatingStatus: String? = null
    private var openSettings: (() -> Unit)? = null
    internal var onMapModalChanged: ((Boolean) -> Unit)? = null
    private var mapModalOpen = false

    private fun updateMapModal(open: Boolean) {
        if (mapModalOpen == open) return
        mapModalOpen = open
        if (open) dismissRentalMapMotion()
        onMapModalChanged?.invoke(open)
    }

    /** 호스트가 확인한 요약만 표시한다. 다음 render에서 같은 화면 세대로 함께 배치한다. */
    internal fun updateMapHud(
        operatingStatus: String? = null,
        onSettings: (() -> Unit)? = null,
    ) {
        displayChanged = displayChanged || this.operatingStatus != operatingStatus ||
            (openSettings == null) != (onSettings == null)
        this.operatingStatus = operatingStatus
        openSettings = onSettings
    }

    internal fun showMapPanel(target: ShopMapTarget) = openMapPanel(MapPanel.Details(target))

    private sealed interface MapPanel {
        data class Details(val target: ShopMapTarget) : MapPanel
        data object VehicleList : MapPanel
        data object FacilityMenu : MapPanel
    }

    private fun openMapPanel(target: MapPanel) {
        if (!interaction.controlsEnabled || uiState.pending != null || managementBusy || hostOverlayOpen) return
        if (interaction.state !is ShopScreenState.Content) {
            if (target == MapPanel.FacilityMenu && focusRestRecovery && lastMapContent != null) {
                recoveryDismissed = false
                drawScreen()
            }
            return
        }
        dismissInstallation()
        if (interaction.selection != null && panelTarget != target) interaction.cancel()
        panelTarget = target
        focusedVehicleId = null
        focusScene = null
        controlsY = 0
        controlsScroll = null
        drawScreen()
    }

    private var controlsScroll: ScrollView? = null
    private var controlsY = 0
    private var projectedBalance: Map<String, GameMoney> = emptyMap()

    internal fun updateMapProjectedBalance(value: Map<String, GameMoney>) {
        if (projectedBalance == value) return
        projectedBalance = value.toMap()
        displayChanged = true
    }

    val uiState: ShopUiState get() = interaction.uiState.copy(
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
        val previousState = interaction.state
        if (!controlsEnabled) {
            dismissRentalMapMotion()
            lastMapContent = null
            dismissInstallation()
            installedFacility = null
        }
        if (!stale && state !is ShopScreenState.Content && state != ShopScreenState.Loading) {
            dismissInstallation()
            dismissRentalMapMotion()
        }
        if (!focusRestRecovery && state is ShopScreenState.Content && !stale) {
            installedFacility = null
            pendingInstallation = null
        }
        val previousUi = interaction.uiState
        val previousAccess = interaction.controlsEnabled
        interaction.render(state, controlsEnabled)
        if (!stale) {
            when (val current = interaction.state) {
                is ShopScreenState.Content -> lastMapContent = current.takeIf { controlsEnabled }
                ShopScreenState.Loading -> Unit
                else -> lastMapContent = null
            }
            val nextVehicles = focusVehicles?.takeIf { focusError == null }
                ?.filter { it.vehicle.lifecycle == VehicleLifecycle.OWNED }?.associateBy { it.vehicle.ownedVehicleId }
            displayChanged = displayChanged || this.focusVehicles != nextVehicles || focusGarageError != focusError ||
                this.focusRest != focusRest || this.focusRestRecovery != focusRestRecovery ||
                this.managementBusy != managementBusy || this.vehicleActionsBlocked != vehicleActionsBlocked
            this.focusVehicles = nextVehicles
            focusGarageError = focusError
            this.focusRest = focusRest
            this.focusRestRecovery = focusRestRecovery
            if (controlsEnabled && !focusRestRecovery) recoveryDismissed = false
            this.managementBusy = managementBusy
            this.vehicleActionsBlocked = vehicleActionsBlocked
        }
        // 표시가 같으면 터치 중인 자식 View와 카메라를 유지한다.
        if (displayChanged || previousState != interaction.state || previousUi != interaction.uiState ||
            previousAccess != interaction.controlsEnabled) drawScreen()
    }
    fun restoreUiState(state: ShopUiState) {
        interaction.restoreUiState(state)
        controlsY = state.controlsScrollY.coerceAtLeast(0)
        controlsScroll = null
        drawScreen()
    }
    internal fun restoreFocusedVehicle(id: OwnedVehicleId?) {
        focusedVehicleId = id
        if (id != null) panelTarget = null
        focusScene = null
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
        installation.complete(request)
        if (result is ContractResult.Failure) dismissInstallation()
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
    fun completeRetry() {
        if (!interaction.uiState.retryPending) return
        interaction.completeRetry()
        drawScreen()
    }
    /** 부모 탐색 전에 가게 미리보기 또는 지도 차량 선택을 닫는다. */
    fun handleBack(): Boolean {
        dismissRentalMapMotion()
        dismissInstallation()
        if (!interaction.controlsEnabled) return false
        if (mapOverlay?.handleBack() == true) return true
        if (interaction.cancel()) {
            drawScreen()
            return true
        }
        if (activeSheet != null || panelTarget != null) {
            closeMapPanel()
            return true
        }
        if (focusedVehicleId == null) return false
        if (!managementBusy && !vehicleDetail.handleBack()) selectFocusedVehicle(null)
        return true
    }

    private fun selectFocusedVehicle(id: OwnedVehicleId?) {
        if (id == focusedVehicleId) return
        if (id != null) panelTarget = null
        focusedVehicleId = id
        if (id == null) focusScene = null
        drawScreen()
    }

    private fun acceptsFocusInput(generation: Long) =
        sceneGeneration == generation && interaction.controlsEnabled && uiState.pending == null &&
            !managementBusy && !hostOverlayOpen

    private fun focusPanel(c: ShopScreenState.Content, selected: ShopVehicle, generation: Long): View {
        val display = withVehicleLabels(c)
        val slot = townSlots[shopSceneModel(c).vehicles.indexOfFirst { it.id == selected.id }]
        val region = ShopFocusRegion.vehicle(slot)
        val camera = cameraScene(display, generation, region = region, onVehicle = { id ->
            if (acceptsFocusInput(generation) && !focusRestRecovery && focusVehicles?.containsKey(id) != false) {
                selectFocusedVehicle(id)
            }
        })
        camera.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        camera.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
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
                val sheet = ShopMapSheet(context, content, controlsY, !managementBusy, region) {
                    if (acceptsFocusInput(generation)) selectFocusedVehicle(null)
                }
                controlsScroll = sheet.scroll
                addFocusedPanel(sheet, camera)
            }
        }
        if (!managementBusy && !preparingVehicleAction) vehicleDetailRequestFinished()
        val detailState = focusGarageError?.let { GarageScreenState.Error(it) }
            ?: focusVehicles?.values?.toList()?.let { GarageScreenState.Content(GarageContent(it, null, "")) }
            ?: GarageScreenState.Loading
        vehicleDetail.setMapFocus(region)
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
            addFocusedPanel(vehicleDetail, camera)
        }
    }
    private fun FrameLayout.addFocusedPanel(panel: View, camera: ShopSceneView) {
        val block = FrameLayout(context).apply {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(panel, FrameLayout.LayoutParams(-1, -1))
        }
        addView(block, FrameLayout.LayoutParams(-1, -1))
        val generation = sceneGeneration
        val visible = panel.visibility
        if (visible == View.VISIBLE) panel.visibility = View.INVISIBLE
        camera.afterCameraSettles(this@ShopView.isAttachedToWindow) {
            if (sceneGeneration == generation) panel.visibility = visible
        }
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun drawScreen(): Unit = with(style) {
        displayChanged = false
        controlsScroll?.takeIf { it.isLaidOut }?.let { controlsY = it.scrollY }
        previousCamera = activeCamera?.displayedCamera()
        activeCamera?.rentalMotionFrame()?.let { carriedRentalMotion = it }
        activeCamera = null
        val focus = findFocus()?.tag
        val generation = ++sceneGeneration
        // 교체된 overlay의 버튼도 현재 ShopInteraction으로 명령을 보내지 못하게 한다.
        val previousOverlayScroll = mapOverlay?.scrollPosition()
        mapOverlay?.visibility = View.GONE
        mapOverlay = null
        activeSheet = null
        controlsScroll = null; removeAllViews()
        setPadding(0, 0, 0, 0)
        setBackgroundColor(if (interaction.state is ShopScreenState.Content && interaction.controlsEnabled) base else page)
        if (!interaction.controlsEnabled) {
            vehicleDetail.render(GarageScreenState.Loading, null, false, true)
            focusScene = null
            panelTarget = null
            cameraRegion = null
            cameraFrom = null
            cameraStartedAt = null
            submittedExpansion = null
            committedExpansion = null
            addView(text(s(R.string.shop_restricted), 28))
            updateMapModal(false)
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
            updateMapModal(true)
            if (focus != null) findViewWithTag<View>(focus)?.takeIf { it.isEnabled }?.requestFocus()
            return@with
        }
        vehicleDetail.render(GarageScreenState.Loading, null, false, true)
        if (selectedId != null && (state is ShopScreenState.Content || focusVehicles?.containsKey(selectedId) == false)) {
            focusedVehicleId = null
            focusScene = null
        }
        when (state) {
            ShopScreenState.Loading -> {
                val previous = lastMapContent
                if (previous != null) {
                    addView(loadingMap(previous, generation), LayoutParams(-1, 0, 1f))
                } else {
                    val content = column().apply {
                        addView(text(s(R.string.shop_title), 32, bold = true))
                        addView(text(s(R.string.shop_loading)))
                        addFacilityPanel(this)
                    }
                    addView(scroll(content, controlsY), LayoutParams(-1, 0, 1f))
                }
            }
            is ShopScreenState.Empty -> problem(s(R.string.shop_empty), state.message, true)
            is ShopScreenState.Failure -> problem(s(R.string.shop_failure), state.message, true)
            is ShopScreenState.Unsupported -> problem(s(R.string.shop_unsupported), state.message, false)
            is ShopScreenState.Content -> addView(
                mapScreen(state, generation, previousOverlayScroll), LayoutParams(-1, 0, 1f),
            )
        }
        updateMapModal(activeSheet != null || mapOverlay != null)
        if (focus != null) findViewWithTag<View>(focus)?.takeIf { it.isEnabled }?.requestFocus()
    }
    private fun loadingMap(previous: ShopScreenState.Content, generation: Long): View = with(style) {
        val panel = facilityPanel
        if (focusRestRecovery && panel != null) return@with facilityResultMap(previous, generation, panel)
        object : FrameLayout(context) {
            override fun onInterceptTouchEvent(event: android.view.MotionEvent) = true
        }.apply {
            tag = "shop:loading-map"
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setBackgroundColor(base)
            addView(cameraScene(previous, generation, onVehicle = null, region = cameraRegion, readOnly = true).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }, FrameLayout.LayoutParams(-1, -1))
            addView(text(s(R.string.shop_loading), 18, accent).apply {
                background = surface()
                setPadding(dp(12), dp(8), dp(12), dp(8))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(12)
            })
        }
    }

    private fun facilityResultMap(previous: ShopScreenState.Content, generation: Long, panel: View): View {
        val facility = installedFacility
        val region = facility?.let { ShopMapTarget.FacilityLot(it).focusRegion() }
        val backdrop = if (facility == null) previous else previous.copy(facilities = previous.facilities.map {
            if (it.facility == facility) it.copy(option = it.option.copy(availability = ShopAvailability.INSTALLED, command = null))
            else it
        })
        val scene = cameraScene(backdrop, generation, onVehicle = null, region = region, readOnly = facility == null)
        val effect = pendingInstallation?.takeIf { it.canDraw(SystemClock.uptimeMillis()) }
        pendingInstallation = effect
        if (recoveryDismissed) return FrameLayout(context).apply {
            addView(scene, FrameLayout.LayoutParams(-1, -1))
            addView(style.button(context.getString(R.string.shop_facilities), action = ::openMapTools).apply {
                tag = "shop:reopen-facility-result"
            }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
                topMargin = style.dp(8)
                marginEnd = style.dp(8)
            })
        }
        (panel.parent as? ViewGroup)?.removeView(panel)
        activeSheet = MapPanel.FacilityMenu
        val sheet = ShopMapSheet(context, panel, controlsY, true, region) {
            if (sceneGeneration == generation) closeMapPanel()
        }
        controlsScroll = sheet.scroll
        if (effect != null && effect.facility == facility && previous.revision.value >= effect.revision.value) {
            scene.animateFacilityInstallation(effect) {
                sceneGeneration == generation && canPresentInstallation()
            }
        }
        return FrameLayout(context).apply {
            addView(scene, FrameLayout.LayoutParams(-1, -1))
            addView(sheet, FrameLayout.LayoutParams(-1, -1))
        }
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
        region: ShopFocusRegion? = null,
        readOnly: Boolean = false,
        onMapTarget: ((ShopMapTarget) -> Unit)? = null,
    ): ShopSceneView {
        if (cameraRegion != region) {
            cameraRegion = region
            cameraFrom = previousCamera
            cameraStartedAt = cameraFrom?.let { SystemClock.uptimeMillis() }
        }
        val growth = TownGrowthArt.matching(c.level, c.capacity)
        val scene = ShopSceneView(context, c, uiState.mode, townBackground(growth), sprites, onVehicle,
            growthArt = growth, fillViewport = true, initialPan = mapPan, onPan = { mapPan = it }, onMapTarget = onMapTarget)
        scene.frameSelection(region, cameraFrom)
        scene.focus(focusedVehicleId, null, cameraStartedAt) { !readOnly && acceptsFocusInput(generation) }
        activeCamera = scene
        return scene
    }

    private fun mapScreen(
        c: ShopScreenState.Content,
        generation: Long,
        previousOverlayScroll: Pair<ShopMapTarget, Int>?,
    ) = with(style) {
        val frame = FrameLayout(context).apply {
            tag = "shop:map-screen"
            setBackgroundColor(base)
        }
        if (viewedMode != uiState.mode) {
            viewedMode = uiState.mode
            mapPan = ShopMapOffset()
        }
        val openVehicle: ((OwnedVehicleId) -> Unit)? =
            if (onFocusedVehicleAction == null && onOpenVehicle == null) null else { id ->
                if (acceptsFocusInput(generation)) {
                    if (onOpenVehicle != null) {
                        panelTarget = null
                        onOpenVehicle?.invoke(id, c.revision)
                        drawScreen()
                    } else if (onFocusedVehicleAction != null && shopSceneModel(c).vehicles.any { it.id == id } &&
                        focusVehicles?.containsKey(id) != false) selectFocusedVehicle(id)
                }
            }
        val review = interaction.selection
        val pending = uiState.pending
        val target = when {
            pending != null -> pending.takeUnless { it == dismissedPending }
                ?.let { MapPanel.Details(it.selection.option.mapTarget()) }
            review != null -> MapPanel.Details(review.option.mapTarget())
            focusRestRecovery && !recoveryDismissed && panelTarget == null -> MapPanel.FacilityMenu
            else -> panelTarget
        }
        val region = (target as? MapPanel.Details)?.target?.focusRegion()
        val display = withVehicleLabels(c)
        val scene = cameraScene(display, generation, onVehicle = { id: OwnedVehicleId ->
            if (acceptsFocusInput(generation) && activeSheet == null && mapOverlay == null) {
                if (onFocusedVehicleAction != null && focusVehicles?.containsKey(id) == true) {
                    selectFocusedVehicle(id)
                } else {
                    openVehicle?.invoke(id)
                }
            }
        }.takeIf { openVehicle != null }, region = region) { mapTarget ->
            if (acceptsFocusInput(generation) && activeSheet == null && mapOverlay == null) showMapPanel(mapTarget)
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
        if (target == null && c.origin == ShopOrigin.STORED && canPresentRentalMapMotion()) {
            val motion = rentalMotion.consume(c, SystemClock.uptimeMillis())
            val carry = carriedRentalMotion
            carriedRentalMotion = null
            val eligible = { sceneGeneration == generation && canPresentRentalMapMotion() }
            if (motion != null) scene.animateRentalMapMotion(motion, eligible)
            else if (carry != null) scene.animateRentalMapMotion(carry, eligible)
        }
        if (target != null) {
            scene.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            scene.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (target is MapPanel.Details) {
                val overlay = ShopMapOverlayView(context,
                    onClose = {
                        if (sceneGeneration == generation) {
                            dismissInstallation()
                            panelTarget = null
                            dismissedPending = uiState.pending
                            drawScreen()
                        }
                    },
                    onChanged = { if (sceneGeneration == generation) drawScreen() },
                    town = townBackground(TownGrowthArt.matching(c.level, c.capacity)),
                )
                mapOverlay = overlay
                overlay.render(target.target, interaction, projectedBalance,
                    initialScrollY = previousOverlayScroll?.takeIf { it.first == target.target }?.second ?: 0,
                    focus = region)
                if (region != null) frame.addFocusedPanel(overlay, scene)
                else frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            } else {
                activeSheet = target
                val content = when (target) {
                    MapPanel.VehicleList -> vehicleList(display, openVehicle)
                    else -> facilityMenu(c)
                }
                val sheet = ShopMapSheet(context, content, controlsY, uiState.pending == null) {
                    if (sceneGeneration == generation) closeMapPanel()
                }
                controlsScroll = sheet.scroll
                frame.addView(sheet, FrameLayout.LayoutParams(-1, -1))
            }
        } else {
            val hud = ShopMapHud(context, c, operatingStatus,
                openSettings?.let { guardedMapAction { openSettings?.invoke() } })
            frame.addView(hud, FrameLayout.LayoutParams(-1, -1))
            val notice = uiState.message ?: uiState.acknowledged
                ?.takeIf { it.revision == c.revision }?.let { s(R.string.shop_wait_refresh) }
            if (notice != null) {
                val layout = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                    bottomMargin = dp(62)
                }
                val label = text(notice, 16, accent).apply {
                    background = surface()
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                frame.addView(label, layout)
            }
        }
        frame
    }

    private fun closeMapPanel() {
        dismissInstallation()
        if (!interaction.controlsEnabled || uiState.pending != null) return
        if (activeSheet == MapPanel.FacilityMenu && focusRestRecovery) recoveryDismissed = true
        interaction.cancel()
        panelTarget = null
        drawScreen()
    }

    private fun mapButton(label: String, key: String, selected: Boolean = false, action: () -> Unit) =
        style.button(label, primary = selected, action = guardedMapAction(action)).apply { tag = key }

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
    /** 지도 기본 화면에는 주 탐색만 두고 보조 조작은 설정에서 연다. */
    fun openMapTools() {
        if (acceptsFocusInput(sceneGeneration)) openMapPanel(MapPanel.FacilityMenu)
    }

    private fun facilityMenu(content: ShopScreenState.Content) = with(style) {
        column().apply {
            addView(text(s(R.string.shop_facilities), 22, bold = true).apply { isAccessibilityHeading = true })
            val town = townBackground(TownGrowthArt.matching(content.level, content.capacity))
            for (facility in Facility.entries) {
                val target = ShopMapTarget.FacilityLot(facility)
                val title = s(when (facility) {
                    Facility.FUEL_STATION -> R.string.shop_facility_fuel
                    Facility.INSURANCE -> R.string.shop_facility_insurance
                    Facility.CAR_WASH -> R.string.shop_facility_wash
                })
                val availability = content.facilities.firstOrNull { it.facility == facility }?.option?.availability
                val status = s(when (availability) {
                    ShopAvailability.LOCKED -> R.string.shop_locked
                    ShopAvailability.AVAILABLE -> R.string.shop_menu_available
                    ShopAvailability.INSTALLED -> R.string.shop_menu_installed
                    ShopAvailability.UNKNOWN, null -> R.string.shop_menu_unknown
                })
                add(this, card().apply {
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                    addView(facilityPicture(town, target).apply { tag = "shop:menu-art:${facility.name}" },
                        LayoutParams(-1, dp(82)))
                    addView(row().apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(mapButton(title, "shop:menu-facility:${facility.name}") {
                            showMapPanel(target)
                        }.apply {
                            gravity = Gravity.START or Gravity.CENTER_VERTICAL
                            contentDescription = "$title · $status"
                        }, LayoutParams(0, -2, 3f))
                        addView(text(status, 13, bold = true).apply {
                            background = infoSurface()
                            gravity = Gravity.CENTER
                            setPadding(dp(4), dp(5), dp(4), dp(5))
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LayoutParams(0, -2, 2f).apply { marginStart = dp(5) })
                    }, LayoutParams(-1, -2).apply { topMargin = dp(5) })
                }, gap = 6)
            }

            add(this, mapButton(s(R.string.shop_expand), "shop:menu-expansion") { showMapPanel(ShopMapTarget.Expansion) })
            if (onActivateDecoration != null) {
                add(this, mapButton("꾸미기 기능 사용 시작", "decoration:activate") {
                    if (activeSheet == MapPanel.FacilityMenu) onActivateDecoration?.invoke()
                })
            }
            add(this, mapButton("꾸미기", "shop:map-decorations") { showMapPanel(ShopMapTarget.Decorations) })
            add(this, text(s(R.string.shop_menu_map_view), 18, bold = true).apply { isAccessibilityHeading = true })
            add(this, row().apply {
                for (mode in ShopMode.entries) {
                    val selected = uiState.mode == mode
                    addView(mapButton(if (mode == ShopMode.OVERALL) "전체 보기" else "차량 중심 보기",
                        "shop:mode:$mode", selected) {
                        if (!selected) { interaction.mode(mode); drawScreen() }
                    }.apply { isSelected = selected }, LayoutParams(0, -2, 1f).apply {
                        if (mode != ShopMode.OVERALL) marginStart = dp(6)
                    })
                }
            }, gap = 4)
            add(this, mapButton("차량 목록", "shop:map-vehicles") { openMapPanel(MapPanel.VehicleList) })
            addFacilityPanel(this)
        }
    }

    private fun facilityPicture(town: Bitmap, target: ShopMapTarget.FacilityLot): View {
        val region = checkNotNull(target.focusRegion())
        val crop = Rect(region.left.toInt(), region.top.toInt(), region.right.toInt(), region.bottom.toInt())
        return object : View(context) {
            private val image = RectF()
            private val paint = Paint()
            init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                val scale = minOf(w.toFloat() / crop.width(), h.toFloat() / crop.height())
                val halfWidth = crop.width() * scale / 2
                val halfHeight = crop.height() * scale / 2
                image.set(w / 2f - halfWidth, h / 2f - halfHeight, w / 2f + halfWidth, h / 2f + halfHeight)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawBitmap(town, crop, image, paint)
            }
        }
    }

    private fun addFacilityPanel(parent: LinearLayout) = with(style) {
        facilityPanel?.let { panel ->
            (panel.parent as? ViewGroup)?.removeView(panel)
            add(parent, panel)
        }
    }

}
