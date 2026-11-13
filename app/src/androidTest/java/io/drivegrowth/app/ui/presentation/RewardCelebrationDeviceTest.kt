package io.drivegrowth.app.ui.presentation

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
class RewardCelebrationDeviceTest : InstrumentationTestCase() {
    private lateinit var host: CollectionScrollTestActivity

    override fun setUp() {
        super.setUp()
        host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CollectionScrollTestActivity
    }

    override fun tearDown() {
        if (::host.isInitialized) onUi { host.finish() }
        super.tearDown()
    }

    fun testConfirmedResultStartsOnlyWhenVisibleAndDoesNotRepeatOnRebuild() {
        var starts = 0
        val parent = FrameLayout(host)
        val effect = RewardCelebrationView(host).apply { onStarted = { starts++ } }
        val width = (300 * host.resources.displayMetrics.density).roundToInt()
        val height = (170 * host.resources.displayMetrics.density).roundToInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        try {
            onUi {
                host.setContentView(parent)
                effect.render("first-confirmed", RewardCelebrationKind.GACHA)
                effect.draw(canvas)
                assertEquals(0, starts)
            }
            awaitWindowFocus(parent)
            onUi {
                val content = View(host)
                fun rebuild() {
                    effect.rebuildContent { parent.removeAllViews() }
                    parent.addView(effect.over(View(host)), FrameLayout.LayoutParams(width, height))
                    parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    parent.layout(0, 0, width, height)
                }
                fun hasShutter(): Boolean {
                    bitmap.eraseColor(0)
                    effect.draw(canvas)
                    return bitmap.getPixel(width / 2, 1) != 0
                }
                assertTrue(parent.hasWindowFocus())
                rebuild()
                assertEquals(ValueAnimator.areAnimatorsEnabled(), hasShutter())
                assertEquals(1, starts)

                effect.render("first-confirmed", RewardCelebrationKind.GACHA)
                rebuild()
                assertEquals(ValueAnimator.areAnimatorsEnabled(), hasShutter())
                assertEquals(1, starts)

                effect.render(null, RewardCelebrationKind.GACHA)
                assertFalse(hasShutter())
                assertSame(content, effect.over(content))
                effect.render("first-confirmed", RewardCelebrationKind.GACHA)
                rebuild()
                assertFalse(hasShutter())
                assertEquals(1, starts)
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun testDecorativeOverlayLetsTheExistingButtonReceiveTouch() {
        var clicks = 0
        onUi {
            val parent = FrameLayout(host)
            host.setContentView(parent)
            val button = Button(host).apply { setOnClickListener { clicks++ } }
            val effect = RewardCelebrationView(host).apply { render("expanded", RewardCelebrationKind.EXPANSION) }
            val wrapper = effect.over(button)
            parent.addView(wrapper, FrameLayout.LayoutParams(200, 200))
            val size = View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)
            wrapper.measure(size, size)
            wrapper.layout(0, 0, 200, 200)
            assertTrue(button.width > 0 && button.height > 0)
            val x = button.left + button.width / 2f
            val y = button.top + button.height / 2f
            val now = android.os.SystemClock.uptimeMillis()
            for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val touch = MotionEvent.obtain(now, now, action, x, y, 0)
                try {
                    assertTrue("버튼 중앙의 터치 동작 $action 전달", wrapper.dispatchTouchEvent(touch))
                } finally { touch.recycle() }
            }
        }
        instrumentation.waitForIdleSync()
        onUi { assertEquals(1, clicks) }
    }

    private fun awaitWindowFocus(view: View) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var focused = false
            onUi { focused = view.hasWindowFocus() }
            if (focused) return
            SystemClock.sleep(50)
        }
        fail("연출을 그릴 계측 창이 포커스를 받지 못했습니다")
    }

    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

}
