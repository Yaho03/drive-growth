package io.drivegrowth.app.ui.shop

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.Button
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision

@Suppress("DEPRECATION")
class ShopExpansionHighlightDeviceTest : InstrumentationTestCase() {
    fun testHighlightNeedsMatchingConfirmationAndFreshStoredCapacity() {
        val events = mutableListOf<ShopEvent>()
        val before = ShopPreviewStates.normal().copy(origin = ShopOrigin.STORED)
        val after = before.copy(revision = Revision(13), level = 3, capacity = 7)
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var view: ShopView
            var starts = 0
            onUi {
                view = ShopView(host, events::add)
                host.setContentView(view)
                view.onCelebration = { starts++ }
                view.render(before, true)
            }
            instrumentation.waitForIdleSync()
            onUi {
                assertTrue(view.hasWindowFocus())
                view.findViewWithTag<Button>("shop:preview:expand").performClick()
                view.findViewWithTag<Button>("shop:confirm").performClick()
                val request = (events.single() as ShopEvent.Submit).request
                view.complete(request.copy(requestId = RequestId("different-expansion")), ContractResult.Success(Unit))
                view.render(after, true)
                sampleSlots(view)
                assertEquals(0, starts)

                view.complete(request, ContractResult.Success(Unit))
                val highlighted = sampleSlots(view)
                assertEquals(1, starts)
                view.visibility = View.INVISIBLE
                view.visibility = View.VISIBLE
                val afterHide = sampleSlots(view)
                if (ValueAnimator.areAnimatorsEnabled()) {
                    assertEquals(afterHide[0], highlighted[0])
                    assertFalse(afterHide[1] == highlighted[1])
                    assertFalse(afterHide[2] == highlighted[2])
                    assertEquals(afterHide[3], highlighted[3])
                }
                view.render(after.copy(revision = Revision(14)), true)
                assertEquals(afterHide, sampleSlots(view))
                assertEquals(1, starts)
                assertEquals(1, events.size)
            }
        } finally {
            onUi { host.finish() }
        }
    }

    fun testExpansionWaitsForExactStoredSnapshotAndDropsEffectOnRestriction() {
        val events = mutableListOf<ShopEvent>()
        val before = ShopPreviewStates.normal().copy(origin = ShopOrigin.STORED)
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var view: ShopView
            var starts = 0
            onUi {
                view = ShopView(host, events::add)
                host.setContentView(view)
                view.onCelebration = { starts++ }
                view.render(before, true)
            }
            instrumentation.waitForIdleSync()
            onUi {
                view.findViewWithTag<Button>("shop:preview:expand").performClick()
                view.findViewWithTag<Button>("shop:confirm").performClick()
                view.complete((events.single() as ShopEvent.Submit).request, ContractResult.Success(Unit))
                sampleSlots(view)
                assertEquals(0, starts)

                val after = before.copy(revision = Revision(13), level = 3, capacity = 7)
                view.render(after.copy(revision = before.revision), true)
                sampleSlots(view)
                assertEquals(0, starts)
                view.render(after.copy(origin = ShopOrigin.FIXTURE), true)
                sampleSlots(view)
                assertEquals(0, starts)

                view.render(after.copy(revision = Revision(14)), false)
                assertNull(view.findViewWithTag<View>("shop:scene"))
                view.render(after.copy(revision = Revision(15)), true)
                sampleSlots(view)
                assertEquals(0, starts)

                val refreshedBefore = before.copy(revision = Revision(16))
                view.render(refreshedBefore, true)
                view.findViewWithTag<Button>("shop:preview:expand").performClick()
                view.findViewWithTag<Button>("shop:confirm").performClick()
                view.complete((events.last() as ShopEvent.Submit).request, ContractResult.Success(Unit))
                view.render(after.copy(revision = Revision(17), capacity = 8), true)
                sampleSlots(view)
                assertEquals(0, starts)
                view.render(after.copy(revision = Revision(18)), true)
                sampleSlots(view)
                assertEquals(0, starts)
                assertEquals(2, events.size)
            }
        } finally {
            onUi { host.finish() }
        }
    }

    private fun sampleSlots(view: ShopView): List<Int> {
        val width = View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY)
        view.measure(width, height)
        view.layout(view.left, view.top, view.right, view.bottom)
        val scene = view.findViewWithTag<ShopSceneView>("shop:scene")
        val bitmap = Bitmap.createBitmap(scene.width, scene.height, Bitmap.Config.ARGB_8888)
        return try {
            scene.draw(Canvas(bitmap))
            val scale = minOf(scene.width / 1536f, scene.height / 1024f)
            val left = (scene.width - 1536f * scale) / 2
            val top = (scene.height - 1024f * scale) / 2
            townSlots.subList(4, 8).map { (x, y) ->
                bitmap.getPixel((left + x * scale).toInt(), (top + y * scale).toInt())
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}
