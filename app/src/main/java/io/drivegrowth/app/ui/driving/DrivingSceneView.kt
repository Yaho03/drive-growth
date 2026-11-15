package io.drivegrowth.app.ui.driving

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.View
import java.time.LocalTime

/**
 * 9차 「주행 중 화면」: 앞유리 너머로 트럭을 운전하는 르르르를 아주 조용히 보여 준다. 조작·정보·소리는 없다.
 * 그림은 assets/pixel/driving의 340×237 WebP이고, 화면을 꽉 채우도록(cover) 픽셀 그대로 키운다.
 * 19시~6시는 밤 그림, 움직임 줄이기 설정이면 정지 그림을 쓴다. 화면에서 사라지면 재생을 멈춘다.
 */
internal class DrivingSceneView(context: Context, private val clock: () -> LocalTime = LocalTime::now) : View(context) {
    private var drawable: Drawable? = null
    private var loaded: String? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(0xff111318.toInt())
    }

    private fun assetName(): String {
        val hour = clock().hour
        val time = if (hour >= 19 || hour < 6) "night" else "day"
        val part = if (ValueAnimator.areAnimatorsEnabled()) "loop" else "still"
        return "pixel/driving/rrr-$time-$part.webp"
    }

    private fun sync() {
        val playing = isAttachedToWindow && windowVisibility == VISIBLE && isShown
        if (!playing) {
            (drawable as? AnimatedImageDrawable)?.stop()
            return
        }
        val name = assetName()
        if (name != loaded) {
            (drawable as? AnimatedImageDrawable)?.stop()
            drawable?.callback = null
            drawable = runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.assets, name)) }.getOrNull()
            loaded = name
            drawable?.apply {
                setBounds(0, 0, WIDTH, HEIGHT)
                isFilterBitmap = false
                callback = this@DrivingSceneView
            }
        }
        (drawable as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val d = drawable ?: return
        val scale = maxOf(width / WIDTH.toFloat(), height / HEIGHT.toFloat())
        canvas.save()
        canvas.translate((width - WIDTH * scale) / 2, (height - HEIGHT * scale) / 2)
        canvas.scale(scale, scale)
        d.draw(canvas)
        canvas.restore()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === drawable || super.verifyDrawable(who)
    override fun onAttachedToWindow() { super.onAttachedToWindow(); sync() }
    override fun onDetachedFromWindow() { (drawable as? AnimatedImageDrawable)?.stop(); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); sync() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); sync() }

    private companion object {
        const val WIDTH = 340
        const val HEIGHT = 237
    }
}
