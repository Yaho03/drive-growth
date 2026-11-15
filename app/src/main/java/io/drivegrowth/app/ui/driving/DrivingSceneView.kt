package io.drivegrowth.app.ui.driving

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.View
import java.time.LocalTime

/** 소리나 조작 없이 주행 제한 안내 뒤에 표시하는 9차 디자인 보드 장면. */
internal class DrivingSceneView(context: Context, private val clock: () -> LocalTime = LocalTime::now) : View(context) {
    private var drawable: Drawable? = null
    private var loaded: String? = null
    private var simulationLoop = false
    private val refresh = Runnable(::sync)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
        setBackgroundColor(0xff111318.toInt())
    }

    fun setSimulationLoop(enabled: Boolean) {
        if (simulationLoop == enabled) return
        simulationLoop = enabled
        sync()
    }

    private fun sync() {
        removeCallbacks(refresh)
        if (!isAttachedToWindow || windowVisibility != VISIBLE || !isShown || !hasWindowFocus() ||
            width == 0 || height == 0
        ) {
            pause()
            return
        }
        val animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
        val asset = drivingSceneAsset(clock().hour, simulationLoop, animatorsEnabled)
        if (asset != loaded) {
            clearDrawable()
            loaded = asset
            drawable = runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, asset))
            }.getOrNull()?.apply {
                setBounds(0, 0, WIDTH, HEIGHT)
                isFilterBitmap = false
                callback = this@DrivingSceneView
            }
            invalidate()
        }
        (drawable as? AnimatedImageDrawable)?.let {
            if (!simulationLoop || !animatorsEnabled) it.stop()
            else if (!it.isRunning) {
                it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                it.start()
            }
        }
        // 화면이 살아 있는 동안 현재 시간대와 시스템의 움직임 줄이기 설정을 반영한다.
        postDelayed(refresh, 1_000L)
    }

    private fun pause() {
        removeCallbacks(refresh)
        (drawable as? AnimatedImageDrawable)?.stop()
    }

    private fun clearDrawable() {
        (drawable as? AnimatedImageDrawable)?.stop()
        drawable?.callback = null
        drawable = null
        loaded = null
    }

    override fun onDraw(canvas: Canvas) {
        val image = drawable ?: return
        // 좁거나 낮은 창에서도 화면 가장자리의 그림이 잘리지 않는다.
        val scale = minOf(width / WIDTH.toFloat(), height / HEIGHT.toFloat())
        canvas.save()
        canvas.translate((width - WIDTH * scale) / 2, (height - HEIGHT * scale) / 2)
        canvas.scale(scale, scale)
        image.draw(canvas)
        canvas.restore()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === drawable || super.verifyDrawable(who)
    override fun onAttachedToWindow() { super.onAttachedToWindow(); sync() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) sync() else pause()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) sync() else pause()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) sync() else pause()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sync()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        clearDrawable()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val WIDTH = 340
        const val HEIGHT = 237
    }
}
