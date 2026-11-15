package io.drivegrowth.app.ui.delivery

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.drivegrowth.app.ui.pixel.CleanUi
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import kotlin.math.abs
import kotlin.math.roundToInt

private const val LAST_DELIVERY_LOOKUP = "주차 후 확인 · 마지막 탁송 조회"

private fun View.acceptsProgressInput() =
    isEnabled && isAttachedToWindow && isShown && hasWindowFocus() && windowVisibility == View.VISIBLE

/** 호스트가 전달한 마지막 탁송 조회를 표시한다. */
@SuppressLint("ViewConstructor")
internal class DriveProgressBarView(
    context: Context,
    private val onOpen: (DriveProgress) -> Unit,
    private val onCollapse: (DriveProgress) -> Unit,
) : LinearLayout(context) {
    private val signs = ReferenceHudStyle(context)
    private val road = DriveProgressRoadView(context)
    private val textColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val destination = label(16f, bold = true).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val provenance = label(12f)
    private val status = label(14f)
    private val track = DriveProgressTrackView(context)
    private var progress: DriveProgress? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchProgress: DriveProgress? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    init {
        tag = "drive-progress:bar"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = signs.signButton()
        minimumHeight = signs.dp(48)
        setPadding(signs.dp(12), signs.dp(8), signs.dp(12), signs.dp(8))
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
        visibility = GONE

        addView(road, LayoutParams(0, 0))
        textColumn.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        textColumn.addView(destination)
        textColumn.addView(provenance)
        textColumn.addView(status)
        textColumn.addView(track, LayoutParams(LayoutParams.MATCH_PARENT, signs.dp(7)).apply { topMargin = signs.dp(4) })
        setOnClickListener { currentProgress()?.let(onOpen) }
        setOnLongClickListener { collapse() }
    }

    /** null이면 표시와 조작을 함께 해제한다. 같은 의뢰의 새 거리로 접기 입력을 이어 가지 않는다. */
    fun render(value: DriveProgress?) {
        if (value == progress) return
        stopTouch()
        progress = value
        visibility = if (value == null) GONE else VISIBLE
        isEnabled = value != null
        road.render(value)
        track.fraction = value?.fraction
        if (value == null) {
            contentDescription = null
            return
        }
        destination.text = value.destinationName?.takeIf { it.isNotBlank() } ?: "탁송 목적지"
        provenance.text = LAST_DELIVERY_LOOKUP
        val estimated = "예상 거리 ${DriveProgress.km(value.estimatedMeters)} km"
        val message = value.awaitingMessage ?: value.observedMeters?.let { "관측 거리 ${DriveProgress.km(it)} km" }
            ?: "거리 확인 대기"
        status.text = "$message\n$estimated"
        contentDescription = "주차 후 확인하는 탁송 진행도. ${destination.text}. ${provenance.text}. $message. $estimated."
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wide = MeasureSpec.getSize(widthMeasureSpec) >= signs.dp(440) &&
            resources.configuration.fontScale <= 1.3f
        (road.layoutParams as LayoutParams).apply {
            width = if (wide) signs.dp(120) else 0
            height = if (wide) signs.dp(60) else 0
            marginEnd = if (wide) signs.dp(10) else 0
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun label(size: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(signs.signInk)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun currentProgress() = progress?.takeIf { acceptsProgressInput() }

    private fun collapse(): Boolean {
        val value = currentProgress() ?: return false
        stopTouch()
        onCollapse(value)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun getAccessibilityClassName() = Button::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (progress != null) info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_COLLAPSE, "탁송 진행도 접기"))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
        if (action == ACTION_COLLAPSE) collapse() else super.performAccessibilityAction(action, arguments)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            stopTouch()
            touchProgress = currentProgress() ?: return false
            downX = event.rawX
            downY = event.rawY
            return super.onTouchEvent(event)
        }
        if (touchProgress == null) return true
        if (touchProgress != currentProgress() || event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelPress(event)
            stopTouch()
            return true
        }
        val deltaY = event.rawY - downY
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(deltaY).coerceAtLeast(abs(event.rawX - downX)) > touchSlop) {
                    cancelPress(event)
                    if (deltaY <= abs(event.rawX - downX)) {
                        stopTouch()
                        return true
                    }
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    if (ValueAnimator.areAnimatorsEnabled()) translationY = deltaY.coerceIn(0f, height.toFloat())
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    if (deltaY > signs.dp(32)) collapse() else stopTouch()
                    return true
                }
                touchProgress = null
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cancelPress(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        try {
            cancel.action = MotionEvent.ACTION_CANCEL
            super.onTouchEvent(cancel)
        } finally { cancel.recycle() }
    }

    private fun stopTouch() {
        if (dragging) parent?.requestDisallowInterceptTouchEvent(false)
        touchProgress = null
        dragging = false
        cancelPendingInputEvents()
        isPressed = false
        translationY = 0f
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) stopTouch()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) stopTouch()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) stopTouch()
    }

    override fun onDetachedFromWindow() {
        stopTouch()
        super.onDetachedFromWindow()
    }

    private companion object {
        val ACTION_COLLAPSE = AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id
    }
}

private class DriveProgressTrackView(context: Context) : View(context) {
    private val paint = Paint()
    var fraction: Float? = null
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        paint.color = CleanUi.CHIP
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        fraction?.let {
            paint.color = CleanUi.SEA
            canvas.drawRect(0f, 0f, width * it, height.toFloat(), paint)
        }
    }
}

/** 접힌 표시를 같은 의뢰의 막대로 복원한다. */
@SuppressLint("ViewConstructor")
internal class DriveProgressChipView(context: Context, private val onExpand: (DriveProgress) -> Unit) : View(context) {
    private val signs = ReferenceHudStyle(context)
    private val paint = Paint()
    private var progress: DriveProgress? = null
    private var touchProgress: DriveProgress? = null

    init {
        tag = "drive-progress:chip"
        minimumWidth = signs.dp(48)
        minimumHeight = signs.dp(48)
        isFocusable = true
        isFocusableInTouchMode = true
        background = signs.signButton()
        visibility = GONE
        setOnClickListener {
            progress?.takeIf { acceptsProgressInput() }?.let(onExpand)
        }
    }

    fun render(value: DriveProgress?) {
        if (value == progress) return
        resetPress()
        progress = value
        visibility = if (value == null) GONE else VISIBLE
        isEnabled = value != null
        contentDescription = value?.let { "탁송 진행도 다시 보기. $LAST_DELIVERY_LOOKUP." }
    }

    override fun getAccessibilityClassName() = Button::class.java.name

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val shown = progress?.takeIf { acceptsProgressInput() }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchProgress = shown ?: return false
        } else if (touchProgress == null || touchProgress != shown || event.pointerCount != 1) {
            resetPress()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touchProgress = null
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()


    override fun onDraw(canvas: Canvas) {
        val unit = minOf(width, height) / 25f
        DrivePixels.draw(canvas, paint, DrivePixels.car, DrivePixels.carColors,
            width / 2f - 10 * unit, height / 2f - 5.5f * unit, unit)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) resetPress()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) resetPress()
    }

    private fun resetPress() {
        touchProgress = null
        cancelPendingInputEvents()
        isPressed = false
    }

    override fun onDetachedFromWindow() {
        resetPress()
        super.onDetachedFromWindow()
    }
}

private class DriveProgressRoadView(context: Context) : View(context) {
    private val sprites = VehicleSprites(context)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val scene = Bitmap.createBitmap(SCENE_W, SCENE_H, Bitmap.Config.ARGB_8888)
    private val sceneCanvas = Canvas(scene)
    private val sceneSrc = Rect(0, 0, SCENE_W, SCENE_H)
    private val sceneDst = RectF()
    private var progress: DriveProgress? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun render(value: DriveProgress?) {
        progress = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val value = progress ?: return
        if (width == 0 || height == 0) return
        val moving = value.awaitingMessage == null && value.observedMeters != null &&
            acceptsProgressInput() && ValueAnimator.areAnimatorsEnabled()
        val time = if (moving) (SystemClock.uptimeMillis() % 4800L) / 1000f else 0f
        drawScene(value, time)
        sceneDst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(scene, sceneSrc, sceneDst, paint)
        if (moving) postInvalidateOnAnimation()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        invalidate()
    }

    private fun drawScene(value: DriveProgress, t: Float) = with(sceneCanvas) {
        val o = t * 40f
        fun px(x: Float, y: Float, w: Float, h: Float, color: Int) {
            paint.color = color; drawRect(x.roundToInt().toFloat(), y.roundToInt().toFloat(), x.roundToInt() + w, y.roundToInt() + h, paint)
        }
        drawColor(0xff72a449.toInt())
        for (i in 0 until 34) {
            val x = (((i * 37) % SCENE_W - o) % SCENE_W + SCENE_W) % SCENE_W
            val y = (((i * 53) % SCENE_H - o / 2) % SCENE_H + SCENE_H) % SCENE_H
            px(x, y, 2f, 1f, 0xff5e8f3b.toInt()); px(x + 1, y - 1, 1f, 1f, 0xff8cc25a.toInt())
        }
        for (x in 0 until SCENE_W) {
            val yc = (x / 2f + 2f).roundToInt().toFloat()
            px(x.toFloat(), yc - 11, 1f, 22f, 0xff4a4d60.toInt())
            px(x.toFloat(), yc - 12, 1f, 1f, 0xffe8c9b8.toInt()); px(x.toFloat(), yc + 11, 1f, 1f, 0xffe8c9b8.toInt())
        }
        for (k in -1 until 8) {
            val xs = ((k * 24 - o) % 192 + 192) % 192 - 24
            var x = xs
            while (x < xs + 8) {
                if (x >= 0 && x < SCENE_W) px(x, (x / 2f + 2f).roundToInt().toFloat(), 1f, 1f, PixelStyle.gold)
                x += 1f
            }
        }
        val car = value.vehicleId?.let(sprites::bitmap)
        val bob = if (t > 0f && (t * 9).toInt() % 2 == 1) 1f else 0f
        if (car != null) {
            val left = (66 - car.width / 2).toFloat()
            val top = 34f - car.height * 0.8f - bob
            drawBitmap(car, left, top, paint)
        } else {
            DrivePixels.draw(this, paint, DrivePixels.car, DrivePixels.carColors, 46f, 22f - bob, 2f)
        }
    }

    private companion object {
        const val SCENE_W = 132
        const val SCENE_H = 66
    }
}

private object DrivePixels {
    val car = arrayOf(
        "......kkkkkkk.......",
        ".....kbbbbbbbk......",
        "....kbsslbsslbk.....",
        "...kbbsslbsslbbkk...",
        ".kkbbbbbbbbbbbbbbk..",
        "kbbbbbbbbbbbbbbbbbk.",
        "kbbbbbbbbbbbbbbbbhk.",
        "kddbbbbbbbbbbbbbbbk.",
        ".kkbkkkbbbbbbkkkbk..",
        "...kddk......kddk...",
        "....kk........kk....",
    )
    val carColors = mapOf('k' to 0xff1c1e2c.toInt(), 'b' to 0xff7cc04a.toInt(), 's' to 0xffaad6f6.toInt(),
        'l' to 0xffffffff.toInt(), 'h' to PixelStyle.gold, 'd' to 0xff4f8a2e.toInt())

    fun draw(canvas: Canvas, paint: Paint, rows: Array<String>, colors: Map<Char, Int>, left: Float, top: Float, unit: Float) {
        rows.forEachIndexed { y, row ->
            row.forEachIndexed pixels@{ x, c ->
                val color = colors[c] ?: return@pixels
                paint.color = color
                canvas.drawRect(left + x * unit, top + y * unit, left + (x + 1) * unit, top + (y + 1) * unit, paint)
            }
        }
    }
}
