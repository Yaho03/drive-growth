package io.drivegrowth.app.ui.shop

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.Button
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.MapVehicleDetailView
import io.drivegrowth.app.ui.pixel.PixelStyle
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
) : ViewGroup(context) {
    internal val model = shopSceneModel(content)
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val dashed = DashPathEffect(floatArrayOf(7f, 5f), 0f)
    private val area = RectF()
    private val fullTown = RectF(0f, 0f, 1536f, 1024f)
    private val shape = Path()
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var expandedSlots = IntRange.EMPTY
    private var consumeExpansion: (() -> Boolean)? = null
    private var expansionStartedAt: Long? = null
    private val visibleBounds = Rect()
    private var selectedId: OwnedVehicleId? = null
    private var previousId: OwnedVehicleId? = null
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

    private fun canTouchActors() = scale > 0f && !movingCamera && selectedId == null && canAnimateActors()

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
        else if (selected != null) cancelResidentTouch()
        invalidate()
    }

    internal fun highlightExpansion(slots: IntRange, consume: () -> Boolean) {
        expandedSlots = slots
        consumeExpansion = consume
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate() else stopMotion()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) invalidate() else {
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
        if (visibility == VISIBLE) invalidate() else stopMotion()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrollChanged)
        context.contentResolver.unregisterContentObserver(motionSetting)
        stopMotion()
        super.onDetachedFromWindow()
    }

    private fun stopMotion(cancelPan: Boolean = true) {
        removeCallbacks(nextFrame)
        frameScheduled = false
        expansionStartedAt = null
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
        !movingCamera && selectedId == null && acceptsVehicleTap()

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
            append(context.getString(R.string.shop_scene_description, content.appearance.sign))
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
                        setStroke((resources.displayMetrics.density * 2).roundToInt(), PixelStyle.focus)
                    })
                    addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(0x449cc2ff) })
                }
                setOnClickListener { if (acceptsVehicleTap()) onVehicle(vehicle.id) }
            })
        }
        vehicleTargetCount = childCount
        if (onMapTarget != null) {
            // 차량 선택 타깃은 시설 배지 뒤에 두지 않는다.
            for ((facility, status) in model.facilityStates) {
                val (x, y) = facilityAnchor(facility)
                addMapTarget("${facilityName(facility)} · ${facilityStatus(status)}",
                    "shop:map-facility:${facility.name}", x, y) { onMapTarget(ShopMapTarget.FacilityLot(facility)) }
            }
            addMapTarget("가게 확장", "shop:map-expansion", 1150f, 720f) { onMapTarget(ShopMapTarget.Expansion) }
        }
    }

    private fun addMapTarget(label: String, key: String, x: Float, y: Float, action: () -> Unit) {
        val style = ShopStyle(context)
        val target = Button(context).apply {
            tag = key
            text = label
            textSize = 15f
            isAllCaps = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xff18263c.toInt())
            minimumWidth = style.dp(48)
            minimumHeight = style.dp(48)
            setPadding(style.dp(9), 0, style.dp(9), 0)
            background = style.shape(0xfff8eee0.toInt())
            setOnClickListener { if (acceptsVehicleTap()) action() }
        }
        mapTargets += Triple(target, x, y)
        // 그리기와 터치 모두 차량 타깃을 우선한다.
        addView(target, mapTargets.lastIndex)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec)
        val height = resolveSize((width * scene.height() / scene.width()).roundToInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
        val density = resources.displayMetrics.density
        val hitSize = (70f * camera(selectedId, width, height).scale).roundToInt()
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
            target.visibility = if (selectedId == null && !movingCamera && mode == ShopMode.OVERALL &&
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
        val target = camera(selectedId)
        val elapsed = cameraStart?.let { SystemClock.uptimeMillis() - it } ?: 720L
        if (movingCamera && (!ValueAnimator.areAnimatorsEnabled() || elapsed >= 720L)) movingCamera = false
        if (!movingCamera) {
            scale = target.scale
            offsetX = target.x
            offsetY = target.y
            return
        }
        val start = camera(previousId)
        val t = cameraEasing.getInterpolation((elapsed / 720f).coerceIn(0f, 1f))
        scale = start.scale + (target.scale - start.scale) * t
        offsetX = start.x + (target.x - start.x) * t
        offsetY = start.y + (target.y - start.y) * t
    }

    private fun camera(id: OwnedVehicleId?, width: Int = this.width, height: Int = this.height): ShopMapCamera {
        val slot = placed.firstOrNull { it.first.id == id }?.second
        val fit = minOf(width / scene.width(), height / scene.height())
        if (slot == null && fillViewport) return coverCamera(pan, width, height)
        if (slot == null) return ShopMapCamera(fit, (width - scene.width() * fit) / 2 - scene.left * fit,
            (height - scene.height() * fit) / 2 - scene.top * fit)
        val zoom = maxOf(width / fullTown.width(), height / fullTown.height(), fit * 2.6f)
        val sidePanel = MapVehicleDetailView.usesSidePanel(width, height, resources.displayMetrics.density)
        val centerX = width * if (sidePanel) .29f else .50f
        val centerY = height * if (fillViewport) .34f else .60f
        return ShopMapCamera(zoom, (centerX - slot.first * zoom).coerceIn(minOf(0f, width - fullTown.width() * zoom), 0f),
            (centerY - slot.second * zoom).coerceIn(minOf(0f, height - fullTown.height() * zoom), 0f))
    }

    private fun coverCamera(offset: ShopMapOffset, width: Int = this.width, height: Int = this.height) =
        ShopMapCamera.cover(width, height, scene.left, scene.top, scene.right, scene.bottom, offset)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(PixelStyle.background)
        val now = SystemClock.uptimeMillis()
        val animate = canAnimateActors()
        if (!animate) stopMotion(cancelPan = !canPan())
        else if (!canTouchActors() && residentTouch.pointer >= 0) cancelResidentTouch()
        actors.update(now, animate)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.clipRect(if (selectedId != null || movingCamera) fullTown else scene)
        canvas.drawBitmap(background, null, fullTown, paint)
        model.facilityStates.forEach { (facility, status) -> drawFacility(canvas, facility, status) }
        drawSign(canvas)
        townSlots.forEachIndexed { index, (x, y) ->
            if (index >= (model.slots ?: 0)) drawClosedSlot(canvas, x.toFloat(), y.toFloat())
        }
        if (model.slots == null) {
            badge(canvas, context.getString(R.string.shop_scene_spaces_unknown), 770f, 652f, 360f)
        }
        drawExpansion(canvas, now, animate)
        placed.forEach { (vehicle, slot) -> drawVehicle(canvas, vehicle, slot.first.toFloat(), slot.second.toFloat()) }
        actors.draw(canvas, animate)
        if (!fillViewport && selectedId == null && mode == ShopMode.OVERALL) {
            val unknown = context.getString(R.string.shop_unknown)
            val summary = context.getString(R.string.shop_scene_summary, content.level?.toString() ?: unknown,
                content.ownedCount?.toString() ?: unknown, content.capacity?.toString() ?: unknown)
            badge(canvas, summary, 1360f, 42f, 296f, size = 32f)
        }
        canvas.restore()
        if (animate && !frameScheduled) {
            frameScheduled = postDelayed(nextFrame, if (movingCamera) 16L else 50L)
        }
    }

    private fun drawExpansion(canvas: Canvas, now: Long, animate: Boolean) {
        val start = consumeExpansion
        if (start == null && expansionStartedAt == null) return
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

    private fun drawFacility(canvas: Canvas, facility: Facility, status: ShopAvailability) {
        val (x, y) = facilityAnchor(facility)
        if (status != ShopAvailability.INSTALLED) {
            // 합성 원본의 구도를 유지하면서 미설치·미확인 시설을 흐리게 표시한다.
            val outline = when (facility) {
                Facility.FUEL_STATION -> fuelCover
                Facility.INSURANCE -> insuranceCover
                Facility.CAR_WASH -> washCover
            }
            polygon(canvas, outline, 0x99151a28.toInt())
        }
        if (selectedId != null || fillViewport) return
        val ink = when (status) {
            ShopAvailability.LOCKED -> PixelStyle.gold
            ShopAvailability.AVAILABLE -> PixelStyle.focus
            ShopAvailability.INSTALLED -> PixelStyle.text
            ShopAvailability.UNKNOWN -> PixelStyle.muted
        }
        badge(canvas, "${facilityName(facility)} · ${facilityStatus(status)}", x, y, 270f, ink, size = 30f)
    }

    private fun facilityAnchor(facility: Facility) = when (facility) {
        Facility.FUEL_STATION -> 295f to 154f
        Facility.INSURANCE -> 377f to 434f
        Facility.CAR_WASH -> 1355f to 546f
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

    private fun drawSign(canvas: Canvas) {
        canvas.save()
        canvas.translate(729f, 284f)
        canvas.skew(0f, .40f)
        val fill = content.appearance.accentArgb or Color.BLACK
        paint.color = fill
        canvas.drawRect(0f, 0f, 205f, 45f, paint)
        val luminance = .299f * Color.red(fill) + .587f * Color.green(fill) + .114f * Color.blue(fill)
        drawText(canvas, content.appearance.sign, 102.5f, 22.5f, 189f, 28f,
            if (luminance > 152) PixelStyle.background else PixelStyle.text)
        canvas.restore()
    }

    private fun drawClosedSlot(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        polygon(canvas, closedSlot, 0xff32394a.toInt())
        if (selectedId == null && model.slots != null) badge(canvas, context.getString(R.string.shop_scene_slot_closed), 0f, 0f, 105f)
        canvas.restore()
    }

    private fun drawVehicle(canvas: Canvas, vehicle: ShopVehicle, x: Float, y: Float) {
        val bitmap = bitmaps[vehicle.id]
        val parked = vehicle.activity == VehicleActivity.IDLE || vehicle.activity == VehicleActivity.NEEDS_REPAIR
        if (bitmap != null && parked) {
            vehicle.rarity?.let { drawRarity(canvas, it, x, y + 13f) }
            val drawWidth = if (bitmap.width == 56) 88f else 100f
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
        val label = if (vehicle.activity == VehicleActivity.IDLE && bitmap != null) vehicle.name
            else "${vehicle.name} · ${vehicle.status}"
        badge(canvas, label, x - 36f, y + 25f, 155f)
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

    private fun drawRarity(canvas: Canvas, rarity: Rarity, x: Float, y: Float) {
        canvas.save()
        canvas.rotate(-29f, x, y)
        paint.color = PixelStyle.rarityColor(rarity)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (rarity == Rarity.LEGEND) 4f else 2.5f
        paint.pathEffect = if (rarity == Rarity.COMMON) dashed else null
        area.set(x - 42f, y - 12f, x + 42f, y + 12f)
        canvas.drawOval(area, paint)
        if (rarity == Rarity.RARE) {
            area.inset(4f, 4f)
            canvas.drawOval(area, paint)
        }
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        canvas.restore()
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
        ink: Int = PixelStyle.text,
        size: Float = 23f,
    ) {
        paint.color = PixelStyle.panel
        val halfHeight = size * .78f
        canvas.drawRoundRect(x - width / 2, y - halfHeight, x + width / 2, y + halfHeight, 9f, 9f, paint)
        drawText(canvas, text, x, y, width - 12f, size, ink)
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, width: Float, size: Float, ink: Int) {
        textPaint.textSize = size
        textPaint.color = ink
        val value = TextUtils.ellipsize(text, textPaint, width, TextUtils.TruncateAt.END).toString()
        canvas.drawText(value, x, y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    companion object {
        private val cameraEasing = PathInterpolator(.4f, 0f, .2f, 1f)
        private val fuelCover = floatArrayOf(
            72f, 0f, 536f, 0f, 562f, 102f, 487f, 151f, 487f, 238f, 395f, 292f, 322f, 296f, 80f, 237f,
        )
        private val insuranceCover = floatArrayOf(
            225f, 347f, 386f, 281f, 573f, 365f, 590f, 512f, 539f, 554f, 418f, 613f, 170f, 514f, 170f, 475f,
        )
        private val washCover = floatArrayOf(
            1159f, 472f, 1373f, 359f, 1536f, 432f, 1536f, 658f, 1370f, 726f, 1148f, 631f,
        )
        private val closedSlot = floatArrayOf(-108f, 26f, 40f, -62f, 108f, -29f, -39f, 58f)
    }
}
