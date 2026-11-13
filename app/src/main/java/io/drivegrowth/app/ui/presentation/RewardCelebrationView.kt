package io.drivegrowth.app.ui.presentation

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.drivegrowth.app.ui.pixel.PixelStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal enum class RewardCelebrationKind(val durationMillis: Long) {
    GACHA(1080),
    FIRST_GRANT(920),
    SETTLEMENT(840),
    EXPANSION(1020),
}

/** 확정 화면 위에 겹치는 장식이다. 클릭과 접근성 탐색은 기존 화면이 받는다. */
@SuppressLint("ViewConstructor")
internal class RewardCelebrationView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rays = Path()
    private val visibleBounds = Rect()
    private val displayedKeys = mutableSetOf<String>()
    private var key: String? = null
    private var kind = RewardCelebrationKind.GACHA
    private var accent = PixelStyle.gold
    private var startedAt: Long? = null
    private var rebuilding = false
    var onStarted: ((RewardCelebrationKind) -> Unit)? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun render(key: String?, kind: RewardCelebrationKind, accent: Int = PixelStyle.gold) {
        if (this.key != key || this.kind != kind) startedAt = null
        this.key = key
        this.kind = kind
        this.accent = accent
        invalidate()
    }

    fun rebuildContent(rebuild: () -> Unit) {
        rebuilding = true
        try {
            rebuild()
        } finally {
            rebuilding = false
        }
    }

    fun over(content: View, fillHeight: Boolean = false): View {
        if (key == null) return content
        rebuildContent { (parent as? ViewGroup)?.removeView(this) }
        return FrameLayout(context).apply {
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, if (fillHeight) MATCH_PARENT else WRAP_CONTENT))
            addView(this@RewardCelebrationView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    override fun onDetachedFromWindow() {
        if (!rebuilding) startedAt = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate() else if (!rebuilding) startedAt = null
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) invalidate() else if (!rebuilding) startedAt = null
    }

    override fun onDraw(canvas: Canvas) {
        val result = key ?: return
        if (!isAttachedToWindow || !isShown || !hasWindowFocus() || !getLocalVisibleRect(visibleBounds)) {
            startedAt = null
            return
        }
        val now = SystemClock.uptimeMillis()
        val begin = startedAt ?: run {
            if (!displayedKeys.add(result)) return
            onStarted?.invoke(kind)
            if (!ValueAnimator.areAnimatorsEnabled()) return
            now.also { startedAt = it }
        }
        val progress = (now - begin).toFloat() / kind.durationMillis
        if (progress >= 1f || !ValueAnimator.areAnimatorsEnabled()) {
            startedAt = null
            return
        }
        val span = minOf(width, height).toFloat()
        when (kind) {
            RewardCelebrationKind.GACHA -> drawReveal(canvas, progress, span)
            RewardCelebrationKind.FIRST_GRANT -> {
                drawBurst(canvas, width * .5f, height * .58f, progress, span)
                drawCoins(canvas, progress, span, toBalance = false)
            }
            RewardCelebrationKind.SETTLEMENT -> drawCoins(canvas, progress, span, toBalance = true)
            RewardCelebrationKind.EXPANSION -> drawBurst(canvas, width * .72f, height * .72f, progress, span)
        }
        postInvalidateOnAnimation()
    }

    private fun drawReveal(canvas: Canvas, progress: Float, span: Float) {
        val opening = (progress / .38f).coerceAtMost(1f)
        val shutter = height * .5f * (1f - opening)
        if (shutter > 0f) {
            ink(PixelStyle.panel, 212)
            canvas.drawRect(0f, 0f, width.toFloat(), shutter, paint)
            canvas.drawRect(0f, height - shutter, width.toFloat(), height.toFloat(), paint)
            ink(accent, 226)
            val trim = minOf(3f, shutter)
            canvas.drawRect(0f, shutter - trim, width.toFloat(), shutter, paint)
            canvas.drawRect(0f, height - shutter, width.toFloat(), height - shutter + trim, paint)
        }
        val reveal = ((progress - .14f) / .86f).coerceIn(0f, 1f)
        if (reveal == 0f) return
        val x = width * .5f
        val y = height * .5f
        val radius = span * (.55f + reveal * .42f)
        rays.rewind()
        for (index in 0 until 9) {
            val angle = index * (2 * PI / 9) + reveal * .42
            rays.moveTo(x, y)
            rays.lineTo(x + cos(angle).toFloat() * radius, y + sin(angle).toFloat() * radius)
            rays.lineTo(x + cos(angle + .18).toFloat() * radius, y + sin(angle + .18).toFloat() * radius)
            rays.close()
        }
        ink(accent, ((1f - reveal) * 54).toInt())
        canvas.drawPath(rays, paint)
        drawBurst(canvas, x, y, reveal, span)
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
            val radius = span * .045f
            val halfWidth = radius * (.32f + .68f * abs(cos((travel * 11 + index).toDouble()).toFloat()))
            val alpha = (255 * ((1f - travel) * 3f).coerceAtMost(1f)).toInt()
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
