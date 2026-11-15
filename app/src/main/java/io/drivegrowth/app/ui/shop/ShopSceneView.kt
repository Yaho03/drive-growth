package io.drivegrowth.app.ui.shop

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.Button
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.CleanUi
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.Facility
import kotlin.math.roundToInt

/** #229 원본 좌표를 Canvas와 차량 선택 View에 공통으로 적용한다. */
@SuppressLint("ViewConstructor")
internal class ShopSceneView(
    context: Context,
    private val content: ShopScreenState.Content,
    private val mode: ShopMode,
    private val background: Bitmap,
    sprites: VehicleSprites,
    onVehicle: ((OwnedVehicleId) -> Unit)?,
    private val fillViewport: Boolean = false,
    initialPan: ShopMapOffset = ShopMapOffset(),
    private val onPan: (ShopMapOffset) -> Unit = {},
    onMapTarget: ((ShopMapTarget) -> Unit)? = null,
    private val growthArt: TownGrowthArt? = null,
) : ViewGroup(context) {
    internal val model = shopSceneModel(content)
    private val sign = content.appearance.sign.displayShopSign()
    private val defaultSign = sign == "기본 간판"
    // 꾸미기 항목 이름(예: "간판 색상")은 간판 문구가 아니다. 간판에는 항상 가게 이름을 쓰고 꾸미기는 색만 바꾼다.
    private val shopName = context.getString(R.string.shop_scene_default_sign)
    private val scene = if (mode == ShopMode.OVERALL) RectF(0f, 0f, 1536f, 1024f)
        else RectF(100f, 546f, 1450f, 986f)
    private val placed = model.vehicles.zip(townSlots).sortedBy { it.second.second }
    private val bitmaps = placed.associate { (vehicle, _) -> vehicle.id to vehicle.vehicleId?.let(sprites::bitmap) }
    private val fuelInstalled = model.facilityStates[Facility.FUEL_STATION] == ShopAvailability.INSTALLED
    private val washInstalled = model.facilityStates[Facility.CAR_WASH] == ShopAvailability.INSTALLED
    private val actorsDelegate = lazy(LazyThreadSafetyMode.NONE) {
        val parked = placed.filter { (vehicle, _) ->
            vehicle.activity == VehicleActivity.IDLE || vehicle.activity == VehicleActivity.NEEDS_REPAIR
        }
        val population = TownPopulation(
            parked.map { (_, slot) -> TownPoint(slot.first.toFloat(), slot.second.toFloat()) },
            parked.filter { (vehicle, _) -> vehicle.activity == VehicleActivity.NEEDS_REPAIR }
                .map { (_, slot) -> TownPoint(slot.first.toFloat(), slot.second.toFloat()) },
            fuelInstalled, washInstalled,
        )
        TownActors(context, population)
    }
    private val actors by actorsDelegate
    private val lampForeground by lazy(LazyThreadSafetyMode.NONE) { TownLampForeground.extract(background) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val area = RectF()
    private val fullTown = RectF(0f, 0f, 1536f, 1024f)
    private val shape = Path()
    private val statusBadge = ReferenceHudStyle(context).signSurface()
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var expandedSlots = IntRange.EMPTY
    private var consumeExpansion: (() -> Boolean)? = null
    private var expansionStartedAt: Long? = null
    private var facilityInstallation: FacilityInstallationScene? = null
    private var facilityMotionEligible: (() -> Boolean)? = null
    private var facilityEffect: InstalledFacilityEffect? = null
    private data class RentalAnimation(val effect: RentalMapEffect, val art: RentalMapMotionScene,
        val eligible: () -> Boolean, val offeredAt: Long, var startedAt: Long? = null)
    private var rentalAnimation: RentalAnimation? = null
    private val rentalGuest by lazy(LazyThreadSafetyMode.NONE) {
        context.assets.open("pixel/prototype-actors/guest.png").use { android.graphics.BitmapFactory.decodeStream(it) }
    }

    internal fun visibleRentalSlot(vehicle: OwnedVehicleId): Pair<Int, Int>? {
        val slot = placed.firstOrNull { it.first.id == vehicle }?.second ?: return null
        if (!canAnimateActors() || movingCamera || focusRegion != null || bitmaps[vehicle] == null || !slotVisible(slot)) return null
        return slot
    }

    internal data class RentalMotionFrame(val effect: RentalMapEffect, val offeredAt: Long, val startedAt: Long?)

    internal fun rentalMotionFrame(): RentalMotionFrame? = rentalAnimation?.let {
        val now = SystemClock.uptimeMillis()
        if (!it.eligible() || now - it.offeredAt > 2_250L ||
            (it.startedAt?.let { start -> now - start >= 2_000L } == true)) null
        else RentalMotionFrame(it.effect, it.offeredAt, it.startedAt)
    }

    internal fun animateRentalMapMotion(effect: RentalMapEffect, eligible: () -> Boolean) =
        animateRentalMapMotion(RentalMotionFrame(effect, SystemClock.uptimeMillis(), null), eligible)

    internal fun animateRentalMapMotion(frame: RentalMotionFrame, eligible: () -> Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - frame.offeredAt > 2_250L || frame.startedAt?.let { now - it >= 2_000L } == true) return
        val effect = frame.effect
        val vehicle = placed.firstOrNull { it.first.id == effect.event.vehicle && it.second == effect.slot }?.first ?: return
        if (vehicle.activity != effect.event.activity) return
        val bitmap = bitmaps[vehicle.id] ?: return
        val guest = rentalGuest ?: return
        rentalAnimation = RentalAnimation(effect, RentalMapMotionScene(effect, bitmap, guest), eligible, frame.offeredAt, frame.startedAt)
        invalidate()
    }

    internal val showingRentalMapMotion: Boolean get() = rentalAnimation != null

    internal fun stopRentalMapMotion() {
        if (rentalAnimation == null) return
        rentalAnimation = null
        invalidate()
    }

    private fun slotVisible(slot: Pair<Int, Int>): Boolean = getLocalVisibleRect(visibleBounds) &&
        offsetX + slot.first * scale in visibleBounds.left.toFloat()..visibleBounds.right.toFloat() &&
        offsetY + slot.second * scale in visibleBounds.top.toFloat()..visibleBounds.bottom.toFloat()
    internal val showingFacilityInstallation: Boolean get() = facilityEffect != null

    internal fun animateFacilityInstallation(effect: InstalledFacilityEffect, eligible: () -> Boolean) {
        if (model.facilityStates[effect.facility] != ShopAvailability.INSTALLED) return
        facilityInstallation = FacilityInstallationScene(context, background, effect.facility)
        facilityEffect = effect
        facilityMotionEligible = eligible
        invalidate()
    }

    internal fun stopFacilityInstallation() {
        if (facilityInstallation == null) return
        facilityInstallation = null
        facilityMotionEligible = null
        facilityEffect = null
        invalidate()
    }
    private val visibleBounds = Rect()
    private var selectedId: OwnedVehicleId? = null
    private var previousId: OwnedVehicleId? = null
    private var focusRegion: ShopFocusRegion? = null
    private var cameraFrom: ShopMapCamera? = null
    private var onCameraSettled: (() -> Unit)? = null
    private var cameraStart: Long? = null
    private var movingCamera = false
    private var acceptsVehicleTap: () -> Boolean = { true }
    private var frameScheduled = false
    private var pan = initialPan
    private var gesturePointer = -1
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var panning = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val mapTargets = mutableListOf<Triple<View, Float, Float>>()
    private var vehicleTargetCount = 0
    private val nextFrame = Runnable {
        frameScheduled = false
        if (canAnimateActors()) {
            if (movingCamera) {
                updateCamera()
                placeVehicleTargets()
            }
            invalidate()
        } else stopMotion()
    }

    internal val residentTouch = TownResidentTouch()
    private val liftResident = Runnable {
        if (canTouchActors()) {
            residentTouch.hold()
            if (residentTouch.isHeld) parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
        } else cancelResidentTouch()
    }
    private val scrollChanged = ViewTreeObserver.OnScrollChangedListener {
        if (canAnimateActors()) invalidate() else stopMotion()
    }
    private val motionSetting = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // 설정 통지와 플랫폼의 애니메이터 상태 갱신 순서가 달라도 다음 그리기에서 다시 확인한다.
            invalidate()
        }
    }

    private fun canAnimateActors() = isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
        hasWindowFocus() && acceptsVehicleTap() && ValueAnimator.areAnimatorsEnabled() && getLocalVisibleRect(visibleBounds)

    private fun canTouchActors() = scale > 0f && !movingCamera && focusRegion == null && selectedId == null && canAnimateActors()

    internal fun displayedCamera() = if (isLaidOut) ShopMapCamera(scale, offsetX, offsetY) else null

    internal fun frameSelection(region: ShopFocusRegion?, from: ShopMapCamera?) {
        focusRegion = region
        cameraFrom = from
    }

    internal fun afterCameraSettles(awaitTransition: Boolean, reveal: () -> Unit) {
        if (movingCamera && awaitTransition) onCameraSettled = reveal
        else reveal()
    }

    internal fun focus(
        selected: OwnedVehicleId?,
        previous: OwnedVehicleId?,
        startedAt: Long?,
        acceptsTap: () -> Boolean,
    ) {
        selectedId = selected
        previousId = previous
        cameraStart = startedAt?.takeIf { SystemClock.uptimeMillis() - it < 720L && ValueAnimator.areAnimatorsEnabled() }
        movingCamera = cameraStart != null
        acceptsVehicleTap = acceptsTap
        if (!acceptsTap()) stopMotion()
        else if (selected != null || focusRegion != null) cancelResidentTouch()
        invalidate()
    }

    internal fun highlightExpansion(slots: IntRange, consume: () -> Boolean) {
        expandedSlots = slots
        consumeExpansion = consume
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate() else {
            stopFacilityInstallation()
            stopMotion()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) invalidate() else {
            stopFacilityInstallation()
            stopMotion()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollChanged)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionSetting,
        )
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate() else {
            stopFacilityInstallation()
            stopMotion()
        }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrollChanged)
        context.contentResolver.unregisterContentObserver(motionSetting)
        stopFacilityInstallation()
        stopMotion()
        super.onDetachedFromWindow()
    }

    private fun stopMotion(cancelPan: Boolean = true) {
        removeCallbacks(nextFrame)
        frameScheduled = false
        expansionStartedAt = null
        stopRentalMapMotion()
        cancelResidentTouch()
        if (cancelPan) resetPanGesture()
        if (actorsDelegate.isInitialized()) actors.stop()
        cameraStart = null
        if (movingCamera) {
            movingCamera = false
            updateCamera()
            placeVehicleTargets()
            invalidate()
        }
    }

    private fun cancelResidentTouch() {
        removeCallbacks(liftResident)
        if (residentTouch.isHeld) parent?.requestDisallowInterceptTouchEvent(false)
        residentTouch.cancel()
    }

    private fun canPan() = fillViewport && width > 0 && height > 0 &&
        !movingCamera && selectedId == null && focusRegion == null && acceptsVehicleTap()

    private fun resetPanGesture() {
        if (panning) parent?.requestDisallowInterceptTouchEvent(false)
        gesturePointer = -1
        panning = false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetPanGesture()
                if (canPan()) {
                    gesturePointer = event.getPointerId(0)
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(gesturePointer)
                if (index >= 0 && canPan() && !residentTouch.isHeld &&
                    beginPanIfMoved(event.getX(index), event.getY(index))
                ) return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> resetPanGesture()
        }
        return false
    }

    private fun beginPanIfMoved(x: Float, y: Float): Boolean {
        if (panning) return true
        if (kotlin.math.hypot(x - downX, y - downY) <= touchSlop) return false
        cancelResidentTouch()
        panning = true
        parent?.requestDisallowInterceptTouchEvent(true)
        movePan(x, y)
        return true
    }

    private fun movePan(x: Float, y: Float) {
        val origin = coverCamera(ShopMapOffset())
        val requested = ShopMapOffset(pan.x + (x - lastX) / scale, pan.y + (y - lastY) / scale)
        val clamped = coverCamera(requested)
        pan = ShopMapOffset((clamped.x - origin.x) / scale, (clamped.y - origin.y) / scale)
        lastX = x
        lastY = y
        onPan(pan)
        updateCamera()
        placeVehicleTargets()
        invalidate()
    }

    // 차량과 시설 자식이 먼저 클릭을 받고, 드래그가 시작된 경우에만 지도에서 가로챈다.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val canTouch = canTouchActors()
        if (!canTouch && !canPan() && !panning) {
            cancelResidentTouch()
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelResidentTouch()
                if (canTouch) {
                    val x = (event.x - offsetX) / scale
                    val y = (event.y - offsetY) / scale
                    if (scene.contains(x, y)) {
                        val targetSize = 40f * resources.displayMetrics.density / scale
                        actors.hit(x, y, targetSize)?.let { resident ->
                            residentTouch.begin(event.getPointerId(0), resident, x, y)
                            postDelayed(liftResident, ViewConfiguration.getLongPressTimeout().toLong())
                            invalidate()
                        }
                    }
                }
                if (residentTouch.pointer < 0 && !canPan()) return super.onTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val residentIndex = event.findPointerIndex(residentTouch.pointer)
                if (residentIndex >= 0 && residentTouch.isHeld && canTouch) {
                    val x = (event.getX(residentIndex) - offsetX) / scale
                    val y = (event.getY(residentIndex) - offsetY) / scale
                    residentTouch.drag(x, y, actors::moveHeld)
                    invalidate()
                } else {
                    val index = event.findPointerIndex(gesturePointer)
                    if (index >= 0 && canPan()) {
                        val x = event.getX(index)
                        val y = event.getY(index)
                        if (panning) movePan(x, y) else beginPanIfMoved(x, y)
                    } else if (residentIndex >= 0) {
                        val x = (event.getX(residentIndex) - offsetX) / scale
                        val y = (event.getY(residentIndex) - offsetY) / scale
                        if (residentTouch.moved(x, y, touchSlop / scale)) cancelResidentTouch()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(liftResident)
                if (residentTouch.isHeld) parent?.requestDisallowInterceptTouchEvent(false)
                residentTouch.release(event.getPointerId(event.actionIndex))
                resetPanGesture()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                cancelResidentTouch()
                resetPanGesture()
                invalidate()
            }
        }
        return true
    }

    init {
        setWillNotDraw(false)
        tag = "shop:scene"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = buildString {
            append(context.getString(R.string.shop_scene_description,
                shopName))
            append(". ")
            append(content.capacity?.let { context.getString(R.string.shop_scene_spaces, it) }
                ?: context.getString(R.string.shop_scene_spaces_unknown))
            model.facilityStates.forEach { (facility, status) ->
                append(". ${facilityName(facility)}: ${facilityStatus(status)}")
            }
        }
        if (onVehicle != null) placed.forEach { (vehicle, _) ->
            addView(View(context).apply {
                tag = "shop:map-vehicle:${vehicle.id.value}"
                contentDescription = context.getString(R.string.shop_scene_vehicle_detail, vehicle.name, vehicle.status)
                isFocusable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        setStroke((resources.displayMetrics.density * 2).roundToInt(), CleanUi.SEA_LIGHT)
                    })
                    addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(0x44bfdcff) })
                }
                setOnClickListener { if (acceptsVehicleTap()) onVehicle(vehicle.id) }
            })
        }
        vehicleTargetCount = childCount
        if (onMapTarget != null) {
            // 차량 선택 타깃은 시설 배지 뒤에 두지 않는다.
            for ((facility, status) in model.facilityStates) {
                val (x, y) = facilityAnchor(facility)
                val label = "${facilityName(facility)} · ${mapFacilityStatus(status)}"
                val icon = when (status) {
                    ShopAvailability.LOCKED -> MapSignIcon.LOCK
                    ShopAvailability.INSTALLED -> MapSignIcon.OPEN
                    else -> null
                }
                addMapTarget(label, "shop:map-facility:${facility.name}", x, y, icon) {
                    onMapTarget(ShopMapTarget.FacilityLot(facility))
                }
            }
            val expansionStatus = content.expansion?.availability ?: ShopAvailability.UNKNOWN
            val expansionIcon = when (expansionStatus) {
                ShopAvailability.AVAILABLE -> MapSignIcon.ADD
                ShopAvailability.LOCKED -> MapSignIcon.LOCK
                else -> null
            }
            val expansionLabel = context.getString(R.string.shop_expand)
            addMapTarget(expansionLabel, "shop:map-expansion", 1250f, 340f, expansionIcon,
                description = "$expansionLabel · ${facilityStatus(expansionStatus)}") {
                onMapTarget(ShopMapTarget.Expansion)
            }
        }
    }

    private fun addMapTarget(
        label: String,
        key: String,
        x: Float,
        y: Float,
        icon: MapSignIcon?,
        description: String = label,
        action: () -> Unit,
    ) {
        val style = ShopStyle(context)
        val hudStyle = ReferenceHudStyle(context)
        val target = Button(context).apply {
            tag = key
            contentDescription = description
            text = if (icon == MapSignIcon.OPEN) SpannableString(label).apply {
                setSpan(ForegroundColorSpan(CleanUi.SEA_LIGHT), label.lastIndexOf('·') + 1,
                    label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else label
            textSize = 14f
            isAllCaps = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(hudStyle.featureButtonText(primary = false))
            letterSpacing = 0f
            minWidth = style.dp(48)
            minHeight = style.dp(48)
            minimumWidth = minWidth
            minimumHeight = minHeight
            backgroundTintList = null
            background = hudStyle.signButton()
            setPadding(style.dp(10), style.dp(8), style.dp(10), style.dp(8))
            if (icon != null) {
                val size = style.dp(14)
                val glyph = MapSignGlyph(icon).apply { setBounds(0, 0, size, size) }
                setCompoundDrawablesRelative(glyph, null, null, null)
                compoundDrawablePadding = style.dp(5)
            }
            setOnClickListener { if (acceptsVehicleTap()) action() }
        }
        mapTargets += Triple(target, x, y)
        // 차량 그림과 선택 영역을 시설 표지판보다 앞에 둔다.
        addView(target, mapTargets.lastIndex)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec)
        val height = resolveSize((width * scene.height() / scene.width()).roundToInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
        val density = resources.displayMetrics.density
        val hitSize = (84f * camera(selectedId, width, height, focusRegion).scale).roundToInt()
            .coerceAtLeast((48f * density).roundToInt())
        val hitSpec = MeasureSpec.makeMeasureSpec(hitSize, MeasureSpec.EXACTLY)
        for (index in 0 until vehicleTargetCount) getChildAt(mapTargets.size + index).measure(hitSpec, hitSpec)
        val badgeWidth = MeasureSpec.makeMeasureSpec(minOf(width, (216 * density).roundToInt()), MeasureSpec.AT_MOST)
        val badgeHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        mapTargets.forEach { (target, _, _) -> target.measure(badgeWidth, badgeHeight) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        updateCamera()
        placeVehicleTargets()
    }

    private fun placeVehicleTargets() {
        for ((target, worldX, worldY) in mapTargets) {
            val centerX = offsetX + worldX * scale
            val centerY = offsetY + worldY * scale
            val left = (centerX - target.measuredWidth / 2f).roundToInt()
            val top = (centerY - target.measuredHeight / 2f).roundToInt()
            target.layout(left, top, left + target.measuredWidth, top + target.measuredHeight)
            target.visibility = if (selectedId == null && focusRegion == null && !movingCamera && mode == ShopMode.OVERALL &&
                left >= 0 && top >= 0 && target.right <= width && target.bottom <= height
            ) VISIBLE else INVISIBLE
        }
        for (index in 0 until vehicleTargetCount) {
            val (vehicle, slot) = placed[index]
            val child = getChildAt(mapTargets.size + index)
            child.isSelected = vehicle.id == selectedId
            val x = (offsetX + slot.first * scale - child.measuredWidth / 2f).roundToInt()
            val y = (offsetY + (slot.second - 7) * scale - child.measuredHeight / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            child.visibility = if (x + child.measuredWidth > 0 && x < width &&
                y + child.measuredHeight > 0 && y < height) VISIBLE else INVISIBLE
        }
    }

    private fun updateCamera() {
        val target = camera(selectedId, region = focusRegion)
        val elapsed = cameraStart?.let { SystemClock.uptimeMillis() - it } ?: 720L
        if (movingCamera && (!ValueAnimator.areAnimatorsEnabled() || elapsed >= 720L)) movingCamera = false
        if (!movingCamera) {
            scale = target.scale
            offsetX = target.x
            offsetY = target.y
            val settled = onCameraSettled
            onCameraSettled = null
            settled?.invoke()
            return
        }
        val start = cameraFrom ?: camera(previousId)
        val t = cameraEasing.getInterpolation((elapsed / 720f).coerceIn(0f, 1f))
        scale = start.scale + (target.scale - start.scale) * t
        offsetX = start.x + (target.x - start.x) * t
        offsetY = start.y + (target.y - start.y) * t
    }

    private fun camera(
        id: OwnedVehicleId?, width: Int = this.width, height: Int = this.height,
        region: ShopFocusRegion? = null,
    ): ShopMapCamera {
        val fit = minOf(width / scene.width(), height / scene.height())
        val overview = if (fillViewport) coverCamera(pan, width, height)
            else ShopMapCamera(fit, (width - scene.width() * fit) / 2 - scene.left * fit,
                (height - scene.height() * fit) / 2 - scene.top * fit)
        val subject = region ?: placed.firstOrNull { it.first.id == id }?.second?.let(ShopFocusRegion::vehicle)
        if (subject == null || width <= 0 || height <= 0) return overview
        return ShopFocusLayout.fit(width, height, resources.displayMetrics.density)
            .camera(width, height, subject, overview.scale)
    }

    private fun coverCamera(offset: ShopMapOffset, width: Int = this.width, height: Int = this.height) =
        ShopMapCamera.cover(width, height, scene.left, scene.top, scene.right, scene.bottom, offset,
            horizontalBias = if (mode == ShopMode.VEHICLES) .10f else .22f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if (focusRegion != null || movingCamera) 0xff47672a.toInt() else PixelStyle.background)
        val now = SystemClock.uptimeMillis()
        val animate = canAnimateActors()
        if (!animate) stopMotion(cancelPan = !canPan())
        else if (!canTouchActors() && residentTouch.pointer >= 0) cancelResidentTouch()
        actors.update(now, animate)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.clipRect(if (selectedId != null || focusRegion != null || movingCamera) fullTown else scene)
        canvas.drawBitmap(background, null, fullTown, paint)
        drawFacilityInstallation(canvas, now)
        if (!defaultSign) drawSign(canvas)
        if (growthArt == null) model.slots?.let { slots ->
            for (index in slots until townSlots.size) {
                val (x, y) = townSlots[index]
                drawClosedSlot(canvas, x.toFloat(), y.toFloat())
            }
        }

        if (model.slots == null) {
            badge(canvas, context.getString(R.string.shop_scene_spaces_unknown), 770f, 652f, 360f)
        }
        drawExpansion(canvas, now, animate)
        val rental = rentalAnimation?.let { animation ->
            val start = animation.startedAt ?: now.also { animation.startedAt = it }
            if (!animation.eligible() || !slotVisible(animation.effect.slot) || now - animation.offeredAt > 2_250L ||
                now - start >= 2_000L || movingCamera) {
                stopRentalMapMotion()
                null
            } else animation to (now - start) / 2_000f
        }
        placed.forEach { (vehicle, slot) ->
            val moving = vehicle.id == rental?.first?.effect?.event?.vehicle
            drawVehicle(canvas, vehicle, slot.first.toFloat(), slot.second.toFloat(), drawParkedSprite = !moving)
        }
        rental?.let { (animation, time) -> animation.art.draw(canvas, time) }
        actors.draw(canvas, animate)
        // 주민이 가로등 뒤로 지나가도록 배경의 아래 연석 가로등을 주민 위에 다시 그린다.
        for (lamp in lampForeground) canvas.drawBitmap(lamp.bitmap, lamp.left, lamp.top, paint)
        if (!fillViewport && selectedId == null && mode == ShopMode.OVERALL) {
            val unknown = context.getString(R.string.shop_unknown)
            val summary = context.getString(R.string.shop_scene_summary, content.level?.toString() ?: unknown,
                content.ownedCount?.toString() ?: unknown, content.capacity?.toString() ?: unknown)
            badge(canvas, summary, 1360f, 42f, 296f, size = 32f)
        }
        canvas.restore()
        if (animate && !frameScheduled) {
            frameScheduled = postDelayed(nextFrame, if (movingCamera || facilityEffect != null || rentalAnimation != null) 16L else 50L)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        for ((target, _, _) in mapTargets) {
            if (target.visibility != VISIBLE) continue
            val x = (target.left + target.right) * .5f
            val y = target.bottom.toFloat()
            paint.color = 0xff483827.toInt()
            canvas.drawRect(x - 3f * density, y - 8f * density, x + 3f * density, y + 8f * density, paint)
            paint.color = 0xffcaa267.toInt()
            canvas.drawRect(x - density, y - 8f * density, x + density, y + 6f * density, paint)
        }
        super.dispatchDraw(canvas)
    }

    private fun drawFacilityInstallation(canvas: Canvas, now: Long) {
        val effect = facilityEffect ?: return
        if (!effect.canDraw(now) || !canAnimateActors() || facilityMotionEligible?.invoke() != true) {
            stopFacilityInstallation()
            return
        }
        if (movingCamera) return
        facilityInstallation?.draw(canvas, effect.progress(now))
    }

    private fun drawExpansion(canvas: Canvas, now: Long, animate: Boolean) {
        val start = consumeExpansion
        if (start == null && expansionStartedAt == null || movingCamera) return
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE || !hasWindowFocus() ||
            !getLocalVisibleRect(visibleBounds)
        ) {
            expansionStartedAt = null
            return
        }
        if (start != null) {
            consumeExpansion = null
            if (start() && animate && !expandedSlots.isEmpty()) expansionStartedAt = now
        }
        val startedAt = expansionStartedAt ?: return
        val progress = (now - startedAt) / 900f
        if (!animate || progress >= 1f) {
            expansionStartedAt = null
            return
        }
        val alpha = ((1f - progress) * 144).roundToInt()
        val highlight = (PixelStyle.gold and 0x00ffffff) or (alpha shl 24)
        for (index in expandedSlots) {
            val (x, y) = townSlots[index]
            canvas.save()
            canvas.translate(x.toFloat(), y.toFloat())
            polygon(canvas, closedSlot, highlight)
            canvas.restore()
        }
    }

    private fun facilityAnchor(facility: Facility) = when (facility) {
        Facility.FUEL_STATION -> 288f to 272f
        Facility.INSURANCE -> 365f to 419f
        Facility.CAR_WASH -> 1329f to 560f
    }

    private fun facilityName(facility: Facility) = context.getString(when (facility) {
        Facility.FUEL_STATION -> R.string.shop_facility_fuel
        Facility.INSURANCE -> R.string.shop_facility_insurance
        Facility.CAR_WASH -> R.string.shop_facility_wash
    })

    private fun facilityStatus(status: ShopAvailability) = context.getString(when (status) {
        ShopAvailability.LOCKED -> R.string.shop_locked
        ShopAvailability.AVAILABLE -> R.string.shop_available
        ShopAvailability.INSTALLED -> R.string.shop_installed
        ShopAvailability.UNKNOWN -> R.string.shop_unknown
    })

    private fun mapFacilityStatus(status: ShopAvailability) = when (status) {
        ShopAvailability.AVAILABLE -> context.getString(R.string.shop_scene_build_available)
        else -> facilityStatus(status)
    }

    private fun drawSign(canvas: Canvas) {
        canvas.save()
        // Lv4 간판은 증축된 건물의 2층 파사드에 있다.
        canvas.translate(729f, if (growthArt == TownGrowthArt.LEVEL_4) 310f else 284f)
        canvas.skew(0f, .40f)
        val fill = content.appearance.accentArgb or Color.BLACK
        paint.color = fill
        canvas.drawRect(0f, 0f, 205f, 45f, paint)
        val luminance = .299f * Color.red(fill) + .587f * Color.green(fill) + .114f * Color.blue(fill)
        drawText(canvas, shopName, 102.5f, 22.5f, 189f, 28f,
            if (luminance > 152) PixelStyle.background else PixelStyle.text)
        canvas.restore()
    }

    private fun drawClosedSlot(canvas: Canvas, x: Float, y: Float) {
        paint.color = 0x8c121622.toInt()
        canvas.drawCircle(x, y - 18f, 17f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.color = Color.WHITE
        canvas.drawArc(x - 6f, y - 29f, x + 6f, y - 15f, 180f, 180f, false, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(x - 9f, y - 23f, x + 9f, y - 9f, 2f, 2f, paint)
    }

    private fun drawVehicle(canvas: Canvas, vehicle: ShopVehicle, x: Float, y: Float, drawParkedSprite: Boolean) {
        val bitmap = bitmaps[vehicle.id]
        val parked = vehicle.activity == VehicleActivity.IDLE || vehicle.activity == VehicleActivity.NEEDS_REPAIR
        if (bitmap != null && parked && drawParkedSprite) {
            if (vehicle.id == selectedId) drawVehicleSelection(canvas, x, y + 13f)
            val drawWidth = bitmap.width * 1.8f
            val drawHeight = drawWidth * bitmap.height / bitmap.width
            area.set(x - drawWidth / 2, y - 7f - drawHeight / 2, x + drawWidth / 2, y - 7f + drawHeight / 2)
            canvas.save()
            canvas.scale(-1f, 1f, x, y)
            canvas.drawBitmap(bitmap, null, area, paint)
            val start = cameraStart
            if (vehicle.id == selectedId && start != null && canAnimateActors()) {
                drawHeadlights(vehicle.rarity, canvas, SystemClock.uptimeMillis() - start)
            }
            canvas.restore()
        }
        if (mode == ShopMode.VEHICLES || bitmap == null) {
            val label = if (vehicle.activity == VehicleActivity.IDLE) vehicle.name else "${vehicle.name} · ${vehicle.status}"
            badge(canvas, label, x - 30f, y + 27f, 155f, size = 20f)
        } else {
            val status = when (vehicle.activity) {
                VehicleActivity.IDLE -> return
                VehicleActivity.RENTED -> R.string.shop_scene_rented
                VehicleActivity.RETURNING -> R.string.shop_scene_returning
                VehicleActivity.NEEDS_REPAIR -> R.string.shop_scene_needs_repair
                null -> R.string.shop_scene_activity_unknown
            }
            val labelY = if (parked) y - 52f else y - 13f
            badge(canvas, context.getString(status), x, labelY, 122f, size = 20f)
        }
    }

    private fun drawVehicleSelection(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.rotate(-29f, x, y)
        area.set(x - 57f, y - 23f, x + 57f, y + 23f)
        paint.color = 0x6647a9ff
        canvas.drawOval(area, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        paint.color = 0xff40a8ff.toInt()
        canvas.drawOval(area, paint)
        paint.strokeWidth = 3f
        paint.color = 0xffd3faff.toInt()
        canvas.drawOval(area, paint)
        paint.style = Paint.Style.FILL
        canvas.restore()
    }

    private fun drawHeadlights(rarity: Rarity?, canvas: Canvas, elapsed: Long) {
        if (rarity == null || elapsed !in 0L until 600L) return
        val tint = when (rarity) {
            Rarity.COMMON -> 0xfff6f6f2.toInt()
            Rarity.ADVANCED -> 0xff68a0f0.toInt()
            Rarity.RARE -> 0xfff68068.toInt()
            Rarity.LEGEND -> 0xfff6aa3c.toInt()
        }
        val alpha = ((1f - elapsed / 600f) * 255).roundToInt()
        val radius = 3f + 7f * alpha / 255f
        val left = area.left + area.width() * .66f
        val right = area.left + area.width() * .95f
        fun light(x: Float, y: Float) {
            paint.color = (tint and 0x00ffffff) or (alpha shl 24)
            canvas.drawRect(x - radius, y - 2f, x + radius, y + 2f, paint)
            canvas.drawRect(x - 2f, y - radius, x + 2f, y + radius, paint)
            paint.color = 0x00ffffff or (alpha shl 24)
            canvas.drawRect(x - 2f, y - 2f, x + 2f, y + 2f, paint)
        }
        light(left, area.top + area.height() * .70f)
        light(right, area.top + area.height() * .58f)
        paint.color = Color.WHITE
    }

    private fun polygon(canvas: Canvas, coords: FloatArray, color: Int) {
        shape.reset()
        shape.moveTo(coords[0], coords[1])
        for (i in 2 until coords.size step 2) shape.lineTo(coords[i], coords[i + 1])
        shape.close()
        paint.color = color
        canvas.drawPath(shape, paint)
    }

    private fun badge(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        width: Float,
        ink: Int = CleanUi.INK,
        size: Float = 23f,
    ) {
        val halfHeight = size * .78f
        statusBadge.setBounds((x - width / 2).roundToInt(), (y - halfHeight).roundToInt(),
            (x + width / 2).roundToInt(), (y + halfHeight).roundToInt())
        statusBadge.draw(canvas)
        drawText(canvas, text, x, y, width - 12f, size, ink)
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, width: Float, size: Float, ink: Int) {
        textPaint.textSize = size
        textPaint.color = ink
        val value = TextUtils.ellipsize(text, textPaint, width, TextUtils.TruncateAt.END).toString()
        canvas.drawText(value, x, y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private enum class MapSignIcon { LOCK, OPEN, ADD }

    private class MapSignGlyph(private val icon: MapSignIcon) : Drawable() {
        private val ink = Paint()
        private var glyphAlpha = 255

        override fun draw(canvas: Canvas) {
            canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 16f, bounds.height() / 16f)
            ink.color = when (icon) {
                MapSignIcon.LOCK -> 0xff292e39.toInt()
                MapSignIcon.OPEN -> 0xff237543.toInt()
                MapSignIcon.ADD -> 0xff285e35.toInt()
            }
            ink.alpha = glyphAlpha
            when (icon) {
                MapSignIcon.LOCK -> {
                    canvas.drawRect(3f, 7f, 13f, 15f, ink)
                    canvas.drawRect(5f, 3f, 7f, 8f, ink)
                    canvas.drawRect(9f, 3f, 11f, 8f, ink)
                    canvas.drawRect(6f, 2f, 10f, 4f, ink)
                }
                MapSignIcon.OPEN -> canvas.drawRect(4f, 4f, 12f, 12f, ink)
                MapSignIcon.ADD -> {
                    canvas.drawRect(6f, 2f, 10f, 14f, ink)
                    canvas.drawRect(2f, 6f, 14f, 10f, ink)
                }
            }
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            glyphAlpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            ink.colorFilter = colorFilter
            invalidateSelf()
        }
        @Suppress("DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        private val cameraEasing = PathInterpolator(.4f, 0f, .2f, 1f)
        private val closedSlot = floatArrayOf(-108f, 26f, 40f, -62f, 108f, -29f, -39f, 58f)
    }
}
