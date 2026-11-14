package io.drivegrowth.app.ui.presentation

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView

/** 글꼴과 크기를 원본 장면의 논리 픽셀 단위로 적용한다. */
internal class ReferenceSceneTypography(context: Context) {
    private val assets = context.assets
    val plexMedium by lazy { Typeface.createFromAsset(assets, "scene-fonts/plex-medium.ttf") }
    val plexBold by lazy { Typeface.createFromAsset(assets, "scene-fonts/plex-bold.ttf") }
    val notoMedium by lazy { noto(500) }
    val notoBold by lazy { noto(700) }
    val notoBlack by lazy { noto(900) }

    private fun noto(weight: Int) = Typeface.Builder(assets, "scene-fonts/noto.ttf")
        .setFontVariationSettings("'wght' $weight").setWeight(weight).build()

    fun <T : TextView> apply(view: T, size: Float, face: Typeface): T = view.apply {
        typeface = face
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
    }
}
