package io.drivegrowth.app.ui.presentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView

/** 첨부 HTML의 SVG를 원래 픽셀 크기로 래스터화한 장면 자산. */
internal class ReferenceSceneArt(private val context: Context) {
    val coin by lazy { load("coin") }
    val truck by lazy { load("truck") }
    val box by lazy { load("box") }

    fun view(bitmap: Bitmap) = ImageView(context).apply {
        importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.FIT_XY
        setImageDrawable(BitmapDrawable(resources, bitmap).apply { setFilterBitmap(false) })
    }

    fun coinDrawable(size: Int) = BitmapDrawable(context.resources, coin).apply {
        setFilterBitmap(false)
        setBounds(0, 0, size, size)
    }

    private fun load(name: String): Bitmap =
        context.assets.open("pixel/scene-hud/$name.png").use {
            checkNotNull(BitmapFactory.decodeStream(it)).apply { density = Bitmap.DENSITY_NONE }
        }
}
