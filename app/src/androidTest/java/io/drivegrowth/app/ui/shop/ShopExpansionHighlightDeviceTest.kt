package io.drivegrowth.app.ui.shop

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.display.DisplayManager
import android.os.SystemClock
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
        val host = launchHost()
        try {
            lateinit var view: ShopView
            var starts = 0
            onUi {
                view = ShopView(host, events::add)
                host.setContentView(android.widget.FrameLayout(host).apply {
                    addView(view, android.widget.FrameLayout.LayoutParams(-1,
                        (resources.displayMetrics.density * 360).toInt()))
                })
                view.onCelebration = { starts++ }
                view.render(before, true)
            }
            confirmExpansion(view)
            onUi {
                assertTrue(view.hasWindowFocus())
                val request = (events.single() as ShopEvent.Submit).request
                view.complete(request.copy(requestId = RequestId("different-expansion")), ContractResult.Success(Unit))
                view.render(after, true)
                sampleSlots(view)
                assertEquals(0, starts)

                view.complete(request, ContractResult.Success(Unit))
            }
            awaitUi("전체 지도에 돌아온 뒤 확장 강조 시작") { starts == 1 }
            onUi {
                val highlighted = sampleSlots(view)
                assertEquals(1, starts)
                view.visibility = View.INVISIBLE
                view.visibility = View.VISIBLE
                val afterHide = sampleSlots(view)
                if (ValueAnimator.areAnimatorsEnabled()) {
                    assertEquals(afterHide[0], highlighted[0])
                    val revealed = listOf(1, 2).filter { highlighted[it] != null }
                    assertTrue(revealed.isNotEmpty())
                    for (index in revealed) assertFalse(afterHide[index] == highlighted[index])
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
        val after = before.copy(revision = Revision(13), level = 3, capacity = 7)
        val host = launchHost()
        try {
            lateinit var view: ShopView
            var starts = 0
            onUi {
                view = ShopView(host, events::add)
                host.setContentView(android.widget.FrameLayout(host).apply {
                    addView(view, android.widget.FrameLayout.LayoutParams(-1,
                        (resources.displayMetrics.density * 360).toInt()))
                })
                view.onCelebration = { starts++ }
                view.render(before, true)
            }
            confirmExpansion(view)
            onUi {
                view.complete((events.single() as ShopEvent.Submit).request, ContractResult.Success(Unit))
                sampleSlots(view)
                assertEquals(0, starts)

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
            }
            confirmExpansion(view)
            onUi {
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

    private fun launchHost(): android.app.Activity {
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        return instrumentation.startActivitySync(Intent(context, CollectionScrollTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
    }

    private fun confirmExpansion(view: ShopView) {
        awaitUi("확장 시험 창 포커스") { view.isLaidOut && view.isShown && view.hasWindowFocus() }
        onUi { view.showMapPanel(ShopMapTarget.Expansion) }
        awaitUi("카메라 이동 뒤 확장 확인 버튼") {
            view.findViewWithTag<Button>("shop:map-confirm")
                ?.takeIf { it.isShown && it.isEnabled && it.hasWindowFocus() }?.performClick() == true
        }
    }

    private fun awaitUi(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            onUi { matched = ready() }
            if (matched) return
            SystemClock.sleep(30)
        }
        fail(label)
    }

    private fun sampleSlots(view: ShopView): List<Int?> {
        val width = View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY)
        view.measure(width, height)
        view.layout(view.left, view.top, view.right, view.bottom)
        val scene = view.findViewWithTag<ShopSceneView>("shop:scene")
        val bitmap = Bitmap.createBitmap(scene.width, scene.height, Bitmap.Config.ARGB_8888)
        return try {
            scene.draw(Canvas(bitmap))
            val (scale, left, top) = ShopMapCamera.cover(scene.width, scene.height, 0f, 0f, 1536f, 1024f, ShopMapOffset())
            townSlots.subList(4, 8).map { (x, y) ->
                val px = (left + x * scale).toInt()
                val py = (top + y * scale).toInt()
                if (px in 0 until bitmap.width && py in 0 until bitmap.height) bitmap.getPixel(px, py) else null
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
