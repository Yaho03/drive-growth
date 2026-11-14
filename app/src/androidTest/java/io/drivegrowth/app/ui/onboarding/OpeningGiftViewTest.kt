package io.drivegrowth.app.ui.onboarding

import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout

@Suppress("DEPRECATION")
class OpeningGiftViewTest : InstrumentationTestCase() {
    fun testSameOpeningGiftCardFitsResizedAndInsetStage() {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                verifyResizedCard()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun verifyResizedCard() {
        val context = instrumentation.targetContext
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val stage = FrameLayout(context)
        val overlay = OpeningGiftOverlay(context)
        var gachaRequests = 0
        var closeRequests = 0
        val card = OpeningGiftView(context, { gachaRequests++ }, { closeRequests++ })
        overlay.addView(card, FrameLayout.LayoutParams(-1, -2))
        stage.addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
        })
        val close = card.findViewWithTag<Button>("opening-gift:close")
        val gacha = card.findViewWithTag<Button>("opening-gift:gacha")
        for ((width, inset, expected) in listOf(Triple(1200, 0, dp(392)),
            Triple(320, 0, dp(320) - 2 * dp(12)),
            Triple(320, 12, dp(320) - 2 * (dp(12) + dp(12))), Triple(1200, 0, dp(392)))) {
            stage.setPadding(dp(inset), 0, dp(inset), 0)
            stage.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(440), View.MeasureSpec.EXACTLY))
            stage.layout(0, 0, dp(width), dp(440))
            assertEquals(expected, overlay.width)
            assertSame(card, overlay.getChildAt(0))
            assertSame(close, card.findViewWithTag<Button>("opening-gift:close"))
            assertSame(gacha, card.findViewWithTag<Button>("opening-gift:gacha"))
            for (button in listOf(close, gacha)) {
                val bounds = Rect().also {
                    button.getDrawingRect(it)
                    stage.offsetDescendantRectToMyCoords(button, it)
                }
                assertTrue("width=$width inset=$inset bounds=$bounds", bounds.left >= overlay.left &&
                    bounds.right <= overlay.right && bounds.top >= overlay.top && bounds.bottom <= overlay.bottom)
                assertTrue(button.height >= dp(48))
            }
        }
        assertTrue(gacha.performClick())
        assertTrue(close.performClick())
        assertEquals(1, gachaRequests)
        assertEquals(1, closeRequests)
    }
}
