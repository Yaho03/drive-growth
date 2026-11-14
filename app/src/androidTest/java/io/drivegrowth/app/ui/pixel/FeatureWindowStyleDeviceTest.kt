package io.drivegrowth.app.ui.pixel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.Button
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.core.contract.Rarity

@Suppress("DEPRECATION")
class FeatureWindowStyleDeviceTest : InstrumentationTestCase() {
    fun testFeatureProgressKeepsZeroSmallAndFullValues() = onUi {
        val context = instrumentation.targetContext
        val style = MapPanelStyle(context, featureWindow = true)
        val inset = ReferenceHudStyle(context).dp(3)
        val width = 200 + 2 * inset
        val height = 20 + 2 * inset
        fun pixels(percent: Int): List<Int> {
            val view = style.experienceTrack(percent)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return try {
                view.draw(Canvas(bitmap))
                (inset until width - inset).map { bitmap.getPixel(it, height / 2) }
            } finally { bitmap.recycle() }
        }
        val zero = pixels(0)
        val small = pixels(1)
        val full = pixels(100)
        assertEquals(CleanUi.CHIP, zero[100])
        assertEquals(2, zero.indices.count { zero[it] != small[it] })
        assertTrue(zero.indices.all { zero[it] != full[it] })
    }

    fun testFeatureButtonUsesEnabledStateAndKeepsTouchSize() = onUi {
        val context = instrumentation.targetContext
        val style = MapPanelStyle(context, featureWindow = true)
        val button = style.styleButton(Button(context), primary = true)
        assertEquals(CleanUi.ON_PRIMARY, button.currentTextColor)
        assertEquals(ReferenceHudStyle(context).dp(48), button.minimumHeight)
        button.isPressed = true
        assertEquals(CleanUi.ON_PRIMARY, button.currentTextColor)
        button.isPressed = false
        button.isEnabled = false
        assertEquals(CleanUi.MUTED, button.currentTextColor)
    }

    fun testFeatureThemeKeepsPixelRarityFillAndBorder() = onUi {
        val context = instrumentation.targetContext
        val cream = GarageStyle(context, pixelPanel = true, creamPanel = true)
        val feature = GarageStyle(context, pixelPanel = true, featureWindow = true)
        for (rarity in Rarity.entries) {
            fun pixels(style: GarageStyle): List<Int> {
                val bitmap = Bitmap.createBitmap(96, 44, Bitmap.Config.ARGB_8888)
                return try {
                    style.raritySurface(rarity).apply { setBounds(0, 0, 96, 44) }.draw(Canvas(bitmap))
                    listOf(bitmap.getPixel(0, 0), bitmap.getPixel(48, 0), bitmap.getPixel(48, 22))
                } finally { bitmap.recycle() }
            }
            assertEquals(pixels(cream), pixels(feature))
            assertEquals(cream.rarityColor(rarity), feature.rarityColor(rarity))
        }
    }

    fun testFeaturePanelUsesReadableDeliveryHostText() = onUi {
        val context = instrumentation.targetContext
        val style = GarageStyle(context, pixelPanel = true, featureWindow = true)
        val hint = style.text("목적지를 확인할 수 없어요.")
        assertEquals(CleanUi.INK, hint.currentTextColor)
        val coat = style.shape(style.panel, selected = true)
        val bitmap = Bitmap.createBitmap(96, 44, Bitmap.Config.ARGB_8888)
        try {
            coat.setBounds(0, 0, 96, 44)
            coat.draw(Canvas(bitmap))
            assertEquals(CleanUi.SEA_DEEP, bitmap.getPixel(48, 22))
        } finally { bitmap.recycle() }
    }

    private fun onUi(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
}
