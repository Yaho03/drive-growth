package io.drivegrowth.app.ui.results

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/** 가상 영업 사고 표시의 짧은 장식. 금융 결과와 접근성 문구는 정산 본문이 유지한다. */
internal class RentalAccidentEffectView(context: Context) : View(context) {
    private val paint = Paint()
    private val visibleBounds = Rect()
    private var progress = 0f
    private var animation: ValueAnimator? = null
    internal var finished = false
        private set
    private var wasVisible = false

    init {
        tag = "results:rental-accident-effect"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun stop() {
        finished = true
        val current = animation
        animation = null
        current?.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) stop()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) wasVisible = true
        else if (wasVisible) stop()
    }

    private fun canPlay() = isAttachedToWindow && isShown && hasWindowFocus() &&
        windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled() && getLocalVisibleRect(visibleBounds)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (finished || progress >= 1f || !canPlay()) return
        if (animation == null) {
            val animator = ValueAnimator.ofFloat(0f, 1f)
            animation = animator
            animator.duration = 700
            animator.addUpdateListener {
                if (!canPlay()) stop()
                else {
                    progress = it.animatedValue as Float
                    invalidate()
                }
            }
            animator.start()
        }
        canvas.save()
        canvas.scale(width / 76f, height / 42f)
        paint.color = 0xff746854.toInt()
        canvas.drawRect(13f, 30f, 48f, 37f, paint)
        canvas.drawRect(21f, 25f, 38f, 30f, paint)
        paint.color = 0xff3f3627.toInt()
        canvas.drawRect(17f, 36f, 23f, 40f, paint)
        canvas.drawRect(39f, 36f, 45f, 40f, paint)
        paint.color = 0xff795023.toInt()
        canvas.drawRect(46f, 4f, 65f, 20f, paint)
        canvas.drawRect(46f, 20f, 50f, 23f, paint)
        paint.color = 0xffffe2a3.toInt()
        canvas.drawRect(54f, 7f, 57f, 13f, paint)
        canvas.drawRect(54f, 15f, 57f, 17f, paint)
        paint.color = 0xff8d7e6a.toInt()
        paint.alpha = ((1f - progress) * 165).toInt()
        val rise = progress * 12f
        canvas.drawRect(26f, 20f - rise, 30f, 24f - rise, paint)
        canvas.drawRect(23f, 16f - rise, 27f, 20f - rise, paint)
        canvas.drawRect(26f, 13f - rise, 31f, 16f - rise, paint)
        canvas.restore()
    }
}
