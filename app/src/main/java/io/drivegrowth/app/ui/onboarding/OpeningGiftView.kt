package io.drivegrowth.app.ui.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.ui.garage.GarageStyle
import kotlin.math.sin

/** 현재 stage의 측정 폭으로 카드 폭을 제한한다. 크기 변경은 기존 카드의 재배치만 한다. */
internal class OpeningGiftOverlay(context: Context) : ScrollView(context) {
    private val style = GarageStyle(context)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = style.dp(392)
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            maxWidth
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        val cardWidth = minOf(maxWidth, available)
        super.onMeasure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}

/** 이미 지급된 무료권의 표시다. 버튼은 기존 탐색 또는 닫기만 호출한다. */
internal class OpeningGiftView(context: Context, onGacha: () -> Unit, onClose: () -> Unit) : LinearLayout(context) {
    private val picture = GiftPicture(context)

    init {
        val style = GarageStyle(context, pixelPanel = true, creamPanel = true)
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = style.shape(style.panel)
        setPadding(style.dp(14), style.dp(10), style.dp(14), style.dp(10))
        tag = "opening-gift:card"
        addView(style.text("개업 선물", 22, bold = true))
        addView(picture, LayoutParams(style.dp(104), style.dp(68)))
        addView(style.text("무료 뽑기권 1장을 받았어요.", 16))
        with(style) {
            add(this@OpeningGiftView, row().apply {
                addView(button("닫기", onClick = onClose).apply { tag = "opening-gift:close" },
                    LayoutParams(0, -2, 1f))
                addView(button("지금 뽑으러 가기", primary = true, onClick = onGacha).apply { tag = "opening-gift:gacha" },
                    LayoutParams(0, -2, 2f).apply { marginStart = dp(8) })
            })
        }
    }

    private class GiftPicture(context: Context) : View(context) {
        private val paint = Paint()
        private var animator: ValueAnimator? = null
        private var frame = 0f

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (animator == null && frame == 0f) {
                if (mayAnimate()) {
                    animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 1500
                        addUpdateListener {
                            if (mayAnimate()) { frame = it.animatedFraction; invalidate() }
                            else settle()
                        }
                        start()
                    }
                } else frame = 1f
            }
            val opening = ((frame - .30f) / .35f).coerceIn(0f, 1f)
            canvas.save()
            canvas.scale(width / 104f, height / 68f)
            canvas.translate(if (frame < .30f) sin(frame * 82f) * (1f - frame / .30f) * 4f else 0f, 0f)
            rect(canvas, 26f, 35f, 78f, 64f, 0x784928)
            rect(canvas, 29f, 38f, 75f, 61f, 0xbf6748)
            rect(canvas, 49f, 38f, 55f, 61f, 0xffd876)
            val lidBottom = 42f - opening * 8
            rect(canvas, 23f, lidBottom - 10, 81f, lidBottom, 0x8e492b)
            rect(canvas, 26f, lidBottom - 8, 78f, lidBottom - 2, 0xe48253)
            rect(canvas, 49f, lidBottom - 12, 56f, lidBottom, 0xffda79)
            rect(canvas, 41f, lidBottom - 14, 50f, lidBottom - 10, 0xffda79)
            rect(canvas, 55f, lidBottom - 14, 64f, lidBottom - 10, 0xffda79)
            if (opening > 0f) {
                canvas.save()
                canvas.translate(52f, 36f - opening * 21)
                canvas.rotate((1f - opening) * -28f)
                val spin = if (opening < 1f) .25f + .75f * kotlin.math.abs(kotlin.math.cos(opening * Math.PI.toFloat() * 2f)) else 1f
                canvas.scale(spin, 1f)
                rect(canvas, -21f, -10f, 21f, 10f, 0x795023)
                rect(canvas, -18f, -7f, 18f, 7f, 0xffffba)
                rect(canvas, -7f, -2f, 7f, 2f, 0xb67c30)
                canvas.restore()
            }
            canvas.restore()
        }

        private fun mayAnimate() = isAttachedToWindow && isShown && hasWindowFocus() &&
            windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (!hasWindowFocus) settle()
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            if (!isVisible && animator != null) settle()
        }

        override fun onDetachedFromWindow() {
            settle()
            super.onDetachedFromWindow()
        }

        private fun settle() {
            val running = animator
            animator = null
            running?.cancel()
            frame = 1f
            invalidate()
        }

        private fun rect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, rgb: Int) {
            paint.color = rgb or 0xff000000.toInt()
            canvas.drawRect(l, t, r, b, paint)
        }
    }
}
