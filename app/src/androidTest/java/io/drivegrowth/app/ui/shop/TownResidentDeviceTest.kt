package io.drivegrowth.app.ui.shop

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.core.contract.OwnedVehicleId

@Suppress("DEPRECATION")
class TownResidentDeviceTest : InstrumentationTestCase() {
    fun testCancelMultitouchAndMapExitClearHoldAndKeepVehicleSelection() {
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val opened = mutableListOf<OwnedVehicleId>()
        lateinit var scene: ShopSceneView
        val background = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            onUi {
                scene = ShopSceneView(host, ShopPreviewStates.normal(), ShopMode.OVERALL, background,
                    VehicleSprites(host), opened::add)
                host.setContentView(scene)
            }
            val deadline = SystemClock.uptimeMillis() + 5000L
            while (SystemClock.uptimeMillis() < deadline && !onUi { scene.hasWindowFocus() && scene.isLaidOut }) {
                SystemClock.sleep(25)
            }
            onUi {
                assertTrue("장면이 보이고 창 포커스를 받아야 한다", scene.hasWindowFocus() && scene.isLaidOut)
                assertTrue("잡기 계측은 시스템 애니메이션이 켜진 장치에서 실행한다", ValueAnimator.areAnimatorsEnabled())
            }
            val first = onUi { beginResidentPress(scene) }
            onUi {
                val properties = Array(2) { id -> MotionEvent.PointerProperties().apply { this.id = id } }
                val coords = Array(2) { index -> MotionEvent.PointerCoords().apply {
                    x = first.x + index * 8
                    y = first.y
                    pressure = 1f
                } }
                val pointerDown = MotionEvent.obtain(first.time, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2,
                    properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
                try { scene.dispatchTouchEvent(pointerDown) } finally { pointerDown.recycle() }
                assertEquals(-1, scene.residentTouch.pointer)
                dispatch(scene, first, MotionEvent.ACTION_CANCEL)
            }
            waitForHoldTimer()
            onUi {
                assertFalse(scene.residentTouch.isHeld)
                assertTrue(opened.isEmpty())
            }
            val held = onUi { beginResidentPress(scene) }
            waitForHoldTimer()
            onUi {
                assertTrue(scene.residentTouch.isHeld)
                dispatch(scene, held, MotionEvent.ACTION_CANCEL)
                assertFalse(scene.residentTouch.isHeld)
                val child = scene.getChildAt(0)
                val car = Touch((child.left + child.right) / 2f, (child.top + child.bottom) / 2f, SystemClock.uptimeMillis())
                dispatch(scene, car, MotionEvent.ACTION_DOWN)
                dispatch(scene, car, MotionEvent.ACTION_UP)
            }
            instrumentation.waitForIdleSync()
            onUi {
                assertEquals(listOf(scene.model.vehicles.first().id), opened)
                beginResidentPress(scene)
            }
            waitForHoldTimer()
            onUi {
                assertTrue(scene.residentTouch.isHeld)
                scene.visibility = View.INVISIBLE
                assertFalse(scene.residentTouch.isHeld)
                assertEquals(-1, scene.residentTouch.pointer)
                scene.visibility = View.VISIBLE
            }
            var allows = true
            onUi {
                scene.focus(null, null, null) { allows }
                beginResidentPress(scene)
            }
            waitForHoldTimer()
            onUi {
                assertTrue(scene.residentTouch.isHeld)
                allows = false
                scene.focus(null, null, null) { allows }
                assertFalse(scene.residentTouch.isHeld)
                assertEquals(-1, scene.residentTouch.pointer)
                allows = true
                scene.focus(null, null, null) { allows }
                beginResidentPress(scene)
            }
            waitForHoldTimer()
            onUi {
                assertTrue(scene.residentTouch.isHeld)
                host.setContentView(View(host))
                assertFalse(scene.residentTouch.isHeld)
                assertEquals(-1, scene.residentTouch.pointer)
            }
        } finally {
            onUi { host.finishAndRemoveTask() }
            background.recycle()
        }
    }

    private fun beginResidentPress(scene: ShopSceneView): Touch {
        val step = (8 * scene.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        for (y in step until scene.height step step) for (x in step until scene.width step step) {
            // 차량 클릭 없이 빈 지도에 있는 주민만 찾는다.
            if ((0 until scene.childCount).any { index ->
                val child = scene.getChildAt(index)
                x in child.left until child.right && y in child.top until child.bottom
            }) continue
            val touch = Touch(x.toFloat(), y.toFloat(), SystemClock.uptimeMillis())
            dispatch(scene, touch, MotionEvent.ACTION_DOWN)
            if (scene.residentTouch.pointer != -1) return touch
            dispatch(scene, touch, MotionEvent.ACTION_CANCEL)
        }
        error("보이는 지도에서 주민 터치 영역을 찾지 못했다")
    }

    private fun dispatch(scene: ShopSceneView, touch: Touch, action: Int) {
        val event = MotionEvent.obtain(touch.time, SystemClock.uptimeMillis(), action, touch.x, touch.y, 0)
        try { scene.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun waitForHoldTimer() = SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100L)

    private data class Touch(val x: Float, val y: Float, val time: Long)

    private fun <T> onUi(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
