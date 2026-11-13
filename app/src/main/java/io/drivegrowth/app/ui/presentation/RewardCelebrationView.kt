package io.drivegrowth.app.ui.presentation

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.drivegrowth.app.ui.pixel.PixelStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal enum class RewardCelebrationKind(val durationMillis: Long) {
    GACHA(2100),
    FIRST_GRANT(1350),
    SETTLEMENT(1150),
    EXPANSION(1020),
}

/** 확정 화면 위에 겹치는 장식이다. 클릭과 접근성 탐색은 기존 화면이 받는다. */
@SuppressLint("ViewConstructor")
internal class RewardCelebrationView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rays = Path()
    private val vehicleBounds = RectF()
    private var vehicle: Bitmap? = null
    private val visibleBounds = Rect()
    private val main = Handler(context.mainLooper)
    private val displayedKeys = mutableSetOf<String>()
    private var key: String? = null
    private var kind = RewardCelebrationKind.GACHA
    private var accent = PixelStyle.gold
    private var startedAt: Long? = null
    private var rebuilding = false
    var waitingForReveal = false
        private set
    private var onRevealFinished: (() -> Unit)? = null
    var onStarted: ((RewardCelebrationKind) -> Unit)? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun render(
        key: String?, kind: RewardCelebrationKind, accent: Int = PixelStyle.gold, vehicle: Bitmap? = null,
    ) {
        if (this.key != key || this.kind != kind) startedAt = null
        this.key = key
        this.kind = kind
        this.accent = accent
        this.vehicle = vehicle
        invalidate()
    }

    fun holdGacha(key: String, accent: Int, vehicle: Bitmap?, onFinished: () -> Unit): Boolean {
        if (onRevealFinished == null && key in displayedKeys) return false
        if (this.key != key || onRevealFinished == null) waitingForReveal = true
        render(key, RewardCelebrationKind.GACHA, accent, vehicle)
        onRevealFinished = onFinished
        return true
    }

    fun openGacha(): Boolean {
        if (!waitingForReveal || !canShowEffect() || !ValueAnimator.areAnimatorsEnabled()) return false
        val result = key ?: return false
        if (!displayedKeys.add(result)) return false
        waitingForReveal = false
        startedAt = SystemClock.uptimeMillis()
        onStarted?.invoke(RewardCelebrationKind.GACHA)
        invalidate()
        return true
    }

    fun finishGacha() {
        val finished = onRevealFinished ?: return
        key?.let(displayedKeys::add)
        onRevealFinished = null
        waitingForReveal = false
        startedAt = null
        key = null
        main.post { finished() }
    }

    private fun canShowEffect() =
        isAttachedToWindow && isShown && hasWindowFocus() && getLocalVisibleRect(visibleBounds)

    fun rebuildContent(rebuild: () -> Unit) {
        rebuilding = true
        try {
            rebuild()
        } finally {
            rebuilding = false
        }
    }

    fun over(content: View, fillHeight: Boolean = false, stage: Boolean = false): View {
        if (key == null && !stage) return content
        if (key != null) rebuildContent { (parent as? ViewGroup)?.removeView(this) }
        if (kind == RewardCelebrationKind.SETTLEMENT) {
            // 확정 금액과 잔액의 줄바꿈·글꼴 크기에 관계없이 별도 영역에만 그린다.
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                val stripHeight = (64 * resources.displayMetrics.density).toInt()
                addView(this@RewardCelebrationView, LinearLayout.LayoutParams(MATCH_PARENT, stripHeight))
            }
        }
        // 무대는 결과가 재표시되거나 시스템 애니메이션이 꺼져도 그대로 남는다.
        val frame = if (stage) object : FrameLayout(context) {
            private val stageAccent = accent
            init { setWillNotDraw(false) }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                drawStage(canvas, width.toFloat(), height.toFloat(), stageAccent)
            }
        } else FrameLayout(context)
        frame.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, if (fillHeight) MATCH_PARENT else WRAP_CONTENT))
        if (key != null) frame.addView(this, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return frame
    }

    override fun onDetachedFromWindow() {
        if (!rebuilding) { finishGacha(); startedAt = null }
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate() else if (!rebuilding) { finishGacha(); startedAt = null }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) invalidate() else if (!rebuilding) { finishGacha(); startedAt = null }
    }

    override fun onDraw(canvas: Canvas) {
        val result = key ?: return
        if (!canShowEffect()) {
            if (isAttachedToWindow && !hasWindowFocus()) finishGacha()
            startedAt = null
            return
        }
        if (onRevealFinished != null && !ValueAnimator.areAnimatorsEnabled()) {
            finishGacha()
            return
        }
        if (waitingForReveal) {
            drawGachaFrame(canvas, 0f)
            postInvalidateDelayed(250L)
            return
        }
        val now = SystemClock.uptimeMillis()
        val begin = startedAt ?: run {
            if (!displayedKeys.add(result)) return
            onStarted?.invoke(kind)
            if (!ValueAnimator.areAnimatorsEnabled()) return
            now.also { startedAt = it }
        }
        val progress = if (onRevealFinished != null) .28f + (now - begin) / 1700f
            else (now - begin).toFloat() / kind.durationMillis
        if (progress >= 1f || !ValueAnimator.areAnimatorsEnabled()) {
            finishGacha()
            startedAt = null
            return
        }
        val span = minOf(width, height).toFloat()
        when (kind) {
            RewardCelebrationKind.GACHA -> drawGachaFrame(canvas, progress)
            RewardCelebrationKind.FIRST_GRANT -> {
                val sprite = vehicle
                if (sprite != null) {
                    drawStage(canvas, width.toFloat(), height.toFloat(), accent)
                    val hop = ((progress - .3f) / .35f).coerceIn(0f, 1f)
                    val y = height * (.52f - .09f * sin(PI * hop).toFloat())
                    drawVehicle(canvas, sprite, width * .5f, y, minOf(width * .65f / sprite.width, height * .80f / sprite.height))
                }
                drawBurst(canvas, width * .5f, height * .58f, progress, span * 1.25f)
                drawCoins(canvas, progress, span, toBalance = false)
            }
            RewardCelebrationKind.SETTLEMENT -> drawCoins(canvas, progress, span, toBalance = true)
            RewardCelebrationKind.EXPANSION -> drawBurst(canvas, width * .72f, height * .72f, progress, span)
        }
        postInvalidateOnAnimation()
    }

    private fun drawStage(canvas: Canvas, w: Float, h: Float, color: Int) {
        canvas.drawColor(0xff151a30.toInt())
        ink(0xff20294d.toInt(), 255)
        canvas.drawRect(0f, h * .62f, w, h, paint)
        ink(0xff2c3963.toInt(), 255)
        paint.strokeWidth = resources.displayMetrics.density
        for (index in -4..8) {
            val x = w * index / 6
            canvas.drawLine(x, h * .62f, x + w * .65f, h, paint)
            canvas.drawLine(x, h * .62f, x - w * .65f, h, paint)
        }
        paint.style = Paint.Style.STROKE
        for (index in 0..1) {
            val scale = if (index == 0) 1f else .82f
            val half = w * .37f * scale
            val depth = h * .15f * scale
            val y = h * .77f
            rays.rewind()
            rays.moveTo(w / 2, y - depth)
            rays.lineTo(w / 2 + half, y)
            rays.lineTo(w / 2, y + depth)
            rays.lineTo(w / 2 - half, y)
            rays.close()
            ink(color, if (index == 0) 230 else 116)
            paint.strokeWidth = resources.displayMetrics.density * if (index == 0) 2.5f else 1f
            canvas.drawPath(rays, paint)
        }
        paint.style = Paint.Style.FILL
    }

    /** 벽시계와 무관하게 셔터와 공개 프레임을 그린다. */
    internal fun drawGachaFrame(canvas: Canvas, progress: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val span = minOf(w, h)
        val opening = ((progress - .28f) / .24f).coerceIn(0f, 1f)
        if (opening < 1f) {
            val left = (w - minOf(w * .78f, h * .68f)) / 2
            val right = w - left
            val top = h * .12f
            val threshold = h * .63f
            val rail = span * .038f
            val fascia = h * .075f
            val doorTop = top + fascia
            val slatHeight = (threshold - doorTop) / 12
            val visibleBottom = threshold - opening * (threshold - doorTop)
            drawStage(canvas, w, h, accent)
            ink(0xff101322.toInt(), 255)
            canvas.drawRect(left, top, right, threshold, paint)
            ink(accent, (65 + 120 * opening).toInt())
            canvas.drawRect(left + rail, doorTop, right - rail, threshold, paint)
            ink(0xff434759.toInt(), 255)
            canvas.drawRect(left, top, left + rail, threshold, paint)
            canvas.drawRect(right - rail, top, right, threshold, paint)
            canvas.save()
            canvas.clipRect(left + rail, doorTop, right - rail, visibleBottom)
            for (index in 0 until 12) {
                val y = doorTop + index * slatHeight - opening * (threshold - doorTop)
                ink(if (index % 2 == 0) 0xff858598.toInt() else 0xff78798b.toInt(), 255)
                canvas.drawRect(left + rail, y, right - rail, y + slatHeight, paint)
                ink(0xffb9bed0.toInt(), 255)
                canvas.drawRect(left + rail, y, right - rail, y + slatHeight * .11f, paint)
                ink(0xff434759.toInt(), 255)
                canvas.drawRect(left + rail, y + slatHeight * .90f, right - rail, y + slatHeight, paint)
            }
            ink(PixelStyle.panel, 255)
            canvas.drawRect(w * .44f, visibleBottom - slatHeight * 1.05f, w * .56f, visibleBottom - slatHeight * .4f, paint)
            ink(0xffb9bed0.toInt(), 255)
            canvas.drawRect(w * .45f, visibleBottom - slatHeight * .92f, w * .55f, visibleBottom - slatHeight * .53f, paint)
            canvas.restore()
            ink(accent, 255)
            canvas.drawRect(left + rail, visibleBottom - slatHeight * .24f, right - rail, visibleBottom, paint)
            ink(0xff858598.toInt(), 255)
            canvas.drawRect(left - rail * .4f, top, right + rail * .4f, doorTop, paint)
            ink(PixelStyle.primary, 255)
            val badgeHalf = (right - left) * .15f
            canvas.drawRect(w / 2 - badgeHalf, top + fascia * .19f, w / 2 + badgeHalf, top + fascia * .81f, paint)
            ink(Color.WHITE, 255)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = fascia * .46f
            canvas.drawText("GARAGE", w / 2, top + fascia * .66f, paint)
            return
        }

        val reveal = ((progress - .52f) / .48f).coerceIn(0f, 1f)
        val x = w * .5f
        val y = h * .57f
        val radius = maxOf(w, h)
        val sprite = vehicle
        if (sprite != null) {
            // 공개 막바지에는 정적인 원결과 무대가 자연스럽게 이어지도록 합성한다.
            val fade = ((1f - reveal) / .24f).coerceIn(0f, 1f)
            canvas.saveLayerAlpha(0f, 0f, w, h, (fade * 255).toInt())
            drawStage(canvas, w, h, accent)
        }
        rays.rewind()
        for (index in 0 until 12) {
            val angle = index * (2 * PI / 12) + reveal * .36
            rays.moveTo(x, y)
            rays.lineTo(x + cos(angle).toFloat() * radius, y + sin(angle).toFloat() * radius)
            rays.lineTo(x + cos(angle + .23).toFloat() * radius, y + sin(angle + .23).toFloat() * radius)
            rays.close()
        }
        canvas.save()
        // Exclude the vehicle area so the actual sprite keeps its original colors.
        if (sprite == null) canvas.clipOutRect(x - span * .29f, y - span * .30f, x + span * .29f, y + span * .24f)
        ink(accent, ((1f - reveal) * 118).toInt())
        canvas.drawPath(rays, paint)
        canvas.restore()
        if (sprite != null) {
            val revealScale = .74f + .26f * (reveal * 3f).coerceAtMost(1f)
            val scale = minOf(w * .57f / sprite.width, h * .54f / sprite.height) * revealScale
            drawVehicle(canvas, sprite, x, y, scale)
        }
        drawBurst(canvas, x, y, reveal, span)
        if (sprite != null) canvas.restore()
    }

    private fun drawVehicle(canvas: Canvas, sprite: Bitmap, x: Float, y: Float, scale: Float) {
        val halfWidth = sprite.width * scale / 2
        val halfHeight = sprite.height * scale / 2
        vehicleBounds.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
        ink(Color.WHITE, 255)
        canvas.drawBitmap(sprite, null, vehicleBounds, paint)
    }

    private fun drawBurst(canvas: Canvas, x: Float, y: Float, progress: Float, span: Float) {
        val distance = span * (.12f + .55f * progress)
        val alpha = ((1f - progress) * 225).toInt()
        for (index in 0 until 18) {
            val angle = index * (2 * PI / 18)
            val spread = distance * (.5f + index % 4 * .16f)
            val px = x + cos(angle).toFloat() * spread
            val py = y + sin(angle).toFloat() * spread * .62f
            val size = span * (.011f + index % 3 * .006f)
            ink(if (index % 3 == 0) PixelStyle.text else accent, alpha)
            canvas.drawRect(px - size, py - size, px + size, py + size, paint)
        }
    }

    private fun drawCoins(canvas: Canvas, progress: Float, span: Float, toBalance: Boolean) {
        for (index in 0 until 9) {
            val travel = (progress - index * .034f) / .66f
            if (travel !in 0f..1f) continue
            val startX = width * (.23f + index % 4 * .14f)
            val endX = if (toBalance) width * .86f else startX + (index - 4) * span * .04f
            val x = startX + (endX - startX) * travel
            val y = if (toBalance) height * (.83f - .7f * travel - .12f * sin(PI * travel).toFloat())
                else height * (.78f - (.36f + index % 3 * .1f) * sin(PI * travel).toFloat())
            val radius = span * .068f
            val halfWidth = radius * (.32f + .68f * abs(cos((travel * 11 + index).toDouble()).toFloat()))
            val alpha = (255 * ((1f - travel) * 3f).coerceAtMost(1f)).toInt()
            ink(0xff8e5525.toInt(), alpha)
            canvas.drawOval(x - halfWidth * 1.2f, y - radius, x + halfWidth * 1.2f, y + radius * 1.12f, paint)
            ink(PixelStyle.gold, alpha)
            canvas.drawOval(x - halfWidth, y - radius, x + halfWidth, y + radius, paint)
            ink(0xffad7424.toInt(), alpha)
            canvas.drawOval(x - halfWidth * .45f, y - radius * .63f, x + halfWidth * .45f, y + radius * .63f, paint)
        }
    }

    private fun ink(color: Int, alpha: Int) {
        paint.color = color
        paint.alpha = alpha
    }

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
