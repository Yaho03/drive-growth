package io.drivegrowth.app.ui.garage

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.garage.CollectionState
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** 차종의 그림만 조회한다. 희귀도와 도감 표시는 호출자가 공급한 값이다. */
internal class VehicleArtwork(context: Context, private val style: GarageStyle) {
    private val sprites = VehicleSprites(context)

    fun hasSprite(id: VehicleId) = sprites.bitmap(id) != null

    fun view(id: VehicleId?, rarity: Rarity?, height: Int, state: CollectionState? = null) =
        FrameLayout(style.context).apply {
            background = style.shape(style.inset)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val mystery = id == null || state == CollectionState.UNDISCOVERED
            val sprite = id?.takeUnless { mystery }?.let(sprites::drawable)
            if (rarity != null && !mystery) {
                val width = if (height >= 100) height + 28 else height - 8
                addView(View(style.context).apply { background = ring(rarity) },
                    FrameLayout.LayoutParams(style.dp(width), style.dp(height / 3), Gravity.CENTER).apply {
                        topMargin = style.dp(height / 3)
                    })
            }
            addView(object : ImageView(style.context) {
                override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                    super.onSizeChanged(w, h, oldw, oldh)
                    if (sprite == null || w == 0 || h == 0) return
                    val ratio = min(w.toFloat() / sprite.intrinsicWidth, h.toFloat() / sprite.intrinsicHeight)
                    // 원본이 들어가는 경우 정수 배율로 확대하고, 더 작은 칸에서는 전체 윤곽을 보존한다.
                    val scale = if (ratio >= 1f) floor(ratio) else ratio
                    val matrix = Matrix()
                    matrix.setScale(scale, scale)
                    matrix.postTranslate(((w - sprite.intrinsicWidth * scale) / 2f).roundToInt().toFloat(),
                        ((h - sprite.intrinsicHeight * scale) / 2f).roundToInt().toFloat())
                    imageMatrix = matrix
                }
            }.apply {
                if (sprite != null) {
                    scaleType = ImageView.ScaleType.MATRIX
                    setImageDrawable(sprite)
                } else {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageResource(R.drawable.garage_vehicle_placeholder)
                }
                if (mystery) {
                    setColorFilter(0xff0c1020.toInt())
                } else if (state == CollectionState.DISCOVERED) {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                    alpha = 0.72f
                }
            }, FrameLayout.LayoutParams(-1, style.dp(height - 4), Gravity.CENTER))
            if (mystery) {
                addView(style.text("?", 26, color = style.muted, bold = true),
                    FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
        }

    private fun ring(rarity: Rarity): LayerDrawable {
        val color = PixelStyle.rarityColor(rarity)
        val line = when (rarity) {
            Rarity.COMMON -> oval(color, 2, dashed = true)
            Rarity.ADVANCED, Rarity.RARE -> oval(color, 2)
            Rarity.LEGEND -> oval(color, 3)
        }
        if (rarity != Rarity.RARE) return LayerDrawable(arrayOf(line))
        return LayerDrawable(arrayOf(line, oval(color, 1))).apply {
            val inset = style.dp(4)
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun oval(color: Int, width: Int, dashed: Boolean = false) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        val pixels = style.dp(width).coerceAtLeast(1)
        if (dashed) setStroke(pixels, color, style.dp(4).toFloat(), style.dp(3).toFloat())
        else setStroke(pixels, color)
    }
}
