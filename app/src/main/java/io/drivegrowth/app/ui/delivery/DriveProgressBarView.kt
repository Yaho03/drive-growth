package io.drivegrowth.app.ui.delivery

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.core.contract.InputSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** 도트 그림 하나(문자 지도). 막대와 접힌 아이콘이 같이 쓴다. */
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
    val flag = arrayOf("kk......", "krrrrr..", "krrrrrrr", "krrrrr..", "k.......", "k.......", "k.......", "k.......", "kk......")
    val flagColors = mapOf('k' to 0xffe7eaf3.toInt(), 'r' to 0xffff6b6b.toInt())

    fun draw(canvas: Canvas, paint: Paint, rows: Array<String>, colors: Map<Char, Int>, left: Float, top: Float, unit: Float) {
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                val color = colors[c] ?: return@forEachIndexed
                paint.color = color
                canvas.drawRect(left + x * unit, top + y * unit, left + (x + 1) * unit, top + (y + 1) * unit, paint)
            }
        }
    }
}

/**
 * 가게·차고 등 다른 화면 아래에 두는 탁송 진행도 막대.
 *
 * 누르면 [onOpen](탁송 화면 열기), 아래로 밀거나 길게 누르면 [onCollapse](막대 숨기기)를 부른다.
 * 표시 여부와 조작 허용은 호스트가 정한다. 이 View는 진행도를 계산하거나 저장하지 않는다.
 */
internal class DriveProgressBarView(
    context: Context,
    private val onOpen: () -> Unit,
    private val onCollapse: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val sprites = VehicleSprites(context)
    private val paint = Paint().apply { isAntiAlias = false }
    private val smooth = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PixelStyle.text; textSize = dp(16f); typeface = Typeface.DEFAULT_BOLD
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = PixelStyle.muted; textSize = dp(12f) }
    private val badge = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PixelStyle.gold; textSize = dp(11f); typeface = Typeface.DEFAULT_BOLD
    }

    // 왼쪽 작은 창의 도로 그림(132×66 도트)을 그린 뒤 화면에 키워서 붙인다.
    private val scene = Bitmap.createBitmap(SCENE_W, SCENE_H, Bitmap.Config.ARGB_8888)
    private val sceneCanvas = Canvas(scene)
    private val sceneSrc = Rect(0, 0, SCENE_W, SCENE_H)
    private val sceneDst = RectF()
    private val startedAt = SystemClock.uptimeMillis()

    private var progress: DriveProgress? = null
    private var downY = 0f
    private var downX = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
        tag = "drive-progress:bar"
        setOnClickListener { onOpen() }
        setOnLongClickListener { onCollapse(); true }
    }

    fun render(value: DriveProgress?) {
        if (value == progress) return
        progress = value
        contentDescription = value?.let(::describe)
        invalidate()
    }

    private fun describe(value: DriveProgress): String {
        val where = value.destinationName?.takeIf { it.isNotBlank() } ?: "목적지"
        val distance = value.observedMeters?.let { "${DriveProgress.km(it)}km 중 " }.orEmpty()
        val state = if (value.awaitingResult) "도착 확인 대기. " else ""
        return "탁송 진행도. ${where}까지 $distance${DriveProgress.km(value.estimatedMeters)}km. $state" +
            "누르면 탁송 화면을 열고, 길게 누르거나 아래로 밀면 숨겨요."
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(dp(78f).roundToInt(), heightMeasureSpec))
    }

    private fun animating() = progress?.awaitingResult == false && ValueAnimator.areAnimatorsEnabled() && isShown

    override fun onDraw(canvas: Canvas) {
        val value = progress ?: return
        val t = if (animating()) (SystemClock.uptimeMillis() - startedAt) / 1000f else 0f
        canvas.drawColor(PixelStyle.panel)
        paint.color = PixelStyle.outline
        canvas.drawRect(0f, 0f, width.toFloat(), dp(1f), paint)
        // 위쪽 손잡이: 아래로 밀 수 있다는 표시
        smooth.color = PixelStyle.outline
        canvas.drawRoundRect(width / 2f - dp(22f), dp(5f), width / 2f + dp(22f), dp(9f), dp(2f), dp(2f), smooth)

        val pad = dp(12f)
        val top = dp(16f)
        val sceneH = height - top - dp(8f)
        sceneDst.set(pad, top, pad + sceneH * 2f, top + sceneH)
        drawScene(value, t)
        canvas.drawBitmap(scene, sceneSrc, sceneDst, paint)

        val left = sceneDst.right + dp(14f)
        val right = width - pad
        var textRight = right
        if (value.source == InputSource.SIMULATION) {   // 모의 입력은 실제 차량 입력처럼 보이지 않게 표시한다
            val label = "모의 입력"
            textRight = right - badge.measureText(label)
            canvas.drawText(label, textRight, top + dp(14f), badge)
            textRight -= dp(8f)
        }
        val where = value.destinationName?.takeIf { it.isNotBlank() } ?: "목적지"
        val headline = when {
            value.awaitingResult -> "도착 확인 대기 · 다음 주차 때 다시 확인해요"
            value.observedMeters != null ->
                "${where}까지 · ${DriveProgress.km(value.observedMeters)} / ${DriveProgress.km(value.estimatedMeters)}km"
            else -> "${where}까지 · ${DriveProgress.km(value.estimatedMeters)}km · 거리 확인 대기"
        }
        val fitted = TextUtils.ellipsize(headline, title, max(0f, textRight - left), TextUtils.TruncateAt.END)
        canvas.drawText(fitted, 0, fitted.length, left, top + dp(15f), title)

        // 진행 막대: 확인된 거리만 채운다
        val trackTop = top + dp(36f)
        val trackLeft = left + dp(4f)
        val trackRight = right - dp(14f)
        smooth.color = PixelStyle.outline
        canvas.drawRoundRect(trackLeft, trackTop, trackRight, trackTop + dp(8f), dp(4f), dp(4f), smooth)
        val fraction = value.fraction ?: 0f
        val fillRight = trackLeft + (trackRight - trackLeft) * fraction
        if (fraction > 0f) {
            smooth.color = if (value.awaitingResult) PixelStyle.muted else PixelStyle.primary
            canvas.drawRoundRect(trackLeft, trackTop, max(fillRight, trackLeft + dp(8f)), trackTop + dp(8f), dp(4f), dp(4f), smooth)
        }
        val unit = dp(1.5f)
        val bob = if (t > 0f && (t * 8).toInt() % 2 == 1) dp(2f) else 0f
        DrivePixels.draw(canvas, paint, DrivePixels.car, DrivePixels.carColors,
            (fillRight - 18 * unit).coerceAtLeast(trackLeft - dp(4f)), trackTop - 12 * unit - bob, unit)
        DrivePixels.draw(canvas, paint, DrivePixels.flag, DrivePixels.flagColors, trackRight + dp(2f), trackTop - 9 * unit + dp(6f), unit)
        canvas.drawText("우리 가게", trackLeft, trackTop + dp(22f), small)
        val end = "목적지"
        canvas.drawText(end, trackRight + dp(12f) - small.measureText(end), trackTop + dp(22f), small)

        if (t > 0f) postInvalidateOnAnimation()
    }

    /** 도로가 왼쪽 위로 흘러가고 탁송 차가 가운데서 달리는 장면. 원본 시안 drive-bar의 drawRoad와 같다. */
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - downY
                if (!dragging && dy > touchSlop && dy > abs(event.x - downX)) {
                    dragging = true
                    cancelLongPress()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    translationY = max(0f, dy)
                    alpha = 1f - (translationY / height).coerceIn(0f, 0.6f)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                val collapse = event.actionMasked == MotionEvent.ACTION_UP && event.y - downY > dp(32f)
                dragging = false
                cancelLongPress()
                isPressed = false
                if (collapse) animate().translationY(height.toFloat()).alpha(0f).setDuration(SLIDE_MS)
                    .withEndAction { resetSlide(); onCollapse() }.start()
                else animate().translationY(0f).alpha(1f).setDuration(SLIDE_MS).start()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // 탭은 super.onTouchEvent → performClick으로 처리된다. 접근성 서비스의 클릭도 같은 경로를 쓴다.
    override fun performClick(): Boolean = super.performClick()

    private fun resetSlide() { translationY = 0f; alpha = 1f }

    /** 숨겨 둔 막대를 다시 보일 때 아래에서 올라오게 한다. */
    fun slideIn() {
        animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { resetSlide(); return }
        translationY = dp(78f); alpha = 0f
        animate().translationY(0f).alpha(1f).setDuration(SLIDE_MS).start()
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        resetSlide()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SCENE_W = 132
        const val SCENE_H = 66
        const val SLIDE_MS = 260L
    }
}

/** 막대를 숨겼을 때 화면 오른쪽 아래에 남는 작은 내 차 아이콘. 누르면 막대를 다시 보인다. */
internal class DriveProgressChipView(context: Context, onExpand: () -> Unit) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint().apply { isAntiAlias = false }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
        tag = "drive-progress:chip"
        contentDescription = "탁송 진행도 막대 다시 보기"
        setOnClickListener { onExpand() }
    }

    fun popIn() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        scaleX = 0.4f; scaleY = 0.4f; alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220L).start()
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        ring.style = Paint.Style.FILL
        ring.color = 0xf0171c2a.toInt()
        canvas.drawCircle(width / 2f, height / 2f, r - density, ring)
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = 2 * density
        ring.color = if (isFocused) PixelStyle.gold else PixelStyle.primary
        canvas.drawCircle(width / 2f, height / 2f, r - 2 * density, ring)
        val unit = 2 * density
        DrivePixels.draw(canvas, paint, DrivePixels.car, DrivePixels.carColors,
            width / 2f - 10 * unit, height / 2f - 5.5f * unit, unit)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }
}
