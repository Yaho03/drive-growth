package io.drivegrowth.app.ui.pixel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.Button
import io.drivegrowth.app.ui.garage.GarageStyle

@Suppress("DEPRECATION")
class FeatureWindowStyleDeviceTest : InstrumentationTestCase() {
    fun testFeatureProgressKeepsZeroSmallAndFullValues() = onUi {
        val context = instrumentation.targetContext
        val style = MapPanelStyle(context)
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
        assertEquals(CleanUi.TRACK, zero[100])
        assertEquals(2, zero.indices.count { zero[it] != small[it] })
        assertTrue(zero.indices.all { zero[it] != full[it] })
    }

    fun testFeatureButtonUsesEnabledStateAndKeepsTouchSize() = onUi {
        val context = instrumentation.targetContext
        val style = MapPanelStyle(context)
        val button = style.styleButton(Button(context), primary = true)
        assertEquals(CleanUi.ON_PRIMARY, button.currentTextColor)
        assertEquals(ReferenceHudStyle(context).dp(56), button.minimumHeight)
        button.isPressed = true
        assertEquals(CleanUi.ON_PRIMARY, button.currentTextColor)
        button.isPressed = false
        button.isEnabled = false
        assertEquals(CleanUi.MUTED, button.currentTextColor)
    }

    fun testFeaturePanelUsesReadableDeliveryHostText() = onUi {
        val context = instrumentation.targetContext
        val style = GarageStyle(context, pixelPanel = true, featureWindow = true)
        val hint = style.text("목적지를 확인할 수 없어요.")
        assertEquals(CleanUi.INK, hint.currentTextColor)
        val hud = ReferenceHudStyle(context)
        val coat = style.shape(style.panel, selected = true)
        val width = hud.dp(96)
        val height = hud.dp(60)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            coat.setBounds(0, 0, width, height)
            coat.draw(Canvas(bitmap))
            assertEquals(0xff182545.toInt(), bitmap.getPixel(width / 2, height / 2))
            assertEquals(CleanUi.SEA_LIGHT, bitmap.getPixel(width / 2, 0))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
            assertEquals(0xff09142d.toInt(), bitmap.getPixel(width / 2, bitmap.height - 1))
            assertEquals(CleanUi.SEA_DEEP, bitmap.getPixel(width / 2, hud.dp(2) * 2))
            val step = hud.dp(3)
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(step, 0))
            assertEquals(CleanUi.SEA_LIGHT, bitmap.getPixel(step, step))
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
