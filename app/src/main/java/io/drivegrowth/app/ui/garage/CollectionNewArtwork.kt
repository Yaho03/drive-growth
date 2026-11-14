package io.drivegrowth.app.ui.garage

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/** 실제로 그리는 보유 칸만 현재 세션의 최초 획득 표지를 소비한다. */
internal class CollectionNewArtwork(
    context: Context,
    private val image: View,
    private val silhouette: () -> View,
    private val take: () -> Boolean,
) : FrameLayout(context) {
    private val visibleBounds = Rect()
    private val style = GarageStyle(context, pixelPanel = true, creamPanel = true)
    private val stamp = style.text("NEW", 14, bold = true).apply {
        contentDescription = "NEW · 도감 등록"
        visibility = INVISIBLE
        background = style.shape(style.panel)
    }
    private val clearStamp = Runnable { stop() }
    private var animator: ValueAnimator? = null
    private var reverse: View? = null
    private var shownNew = false
    private var checkQueued = false
    private val checkNew = Runnable {
        checkQueued = false
        if (canReveal() && take()) {
            shownNew = true
            reveal()
        }
    }

    init {
        addView(image, LayoutParams(-1, -1))
        addView(stamp, LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!shownNew && !checkQueued && canReveal()) {
            checkQueued = true
            post(checkNew)
        }
        super.dispatchDraw(canvas)
    }

    private fun canReveal() = isAttachedToWindow && hasWindowFocus() && isShown &&
        windowVisibility == VISIBLE && getLocalVisibleRect(visibleBounds)

    private fun reveal() {
        stamp.visibility = VISIBLE
        if (ValueAnimator.areAnimatorsEnabled()) {
            val back = silhouette().also {
                reverse = it
                addView(it, 1, LayoutParams(-1, -1))
            }
            image.alpha = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700
                addUpdateListener {
                    val fraction = it.animatedFraction
                    back.rotationY = (fraction * 180f).coerceAtMost(90f)
                    back.alpha = if (fraction < .5f) 1f else 0f
                    image.rotationY = (fraction - 1f) * 180f
                    image.alpha = if (fraction < .5f) 0f else 1f
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (animator === animation) {
                            animator = null
                            post(clearStamp)
                        }
                    }
                })
            }
            animator?.start()
        } else postDelayed(clearStamp, 700)
    }

    private fun stop() {
        removeCallbacks(clearStamp)
        removeCallbacks(checkNew)
        checkQueued = false
        val running = animator
        animator = null
        running?.cancel()
        reverse?.let(::removeView)
        reverse = null
        stamp.visibility = INVISIBLE
        image.rotationY = 0f
        image.alpha = 1f
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) stop() else invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
