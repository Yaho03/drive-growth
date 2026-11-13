package io.drivegrowth.app.ui.presentation

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

    fun testGachaShutterCoversTheVehicleBeforeDrawingItsActualSprite() = onUi {
        val sprite = Bitmap.createBitmap(20, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val frame = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(frame)
            val effect = RewardCelebrationView(host)
            val width = View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
            effect.measure(width, height)
            effect.layout(0, 0, 320, 240)
            effect.render("saved-gacha", RewardCelebrationKind.GACHA, Color.MAGENTA, sprite)
            effect.drawGachaFrame(canvas, .15f)
            assertEquals("닫힌 셔터가 차량 영역을 가려야 한다", 0xff858598.toInt(), frame.getPixel(160, 51))
            assertEquals(Color.MAGENTA, frame.getPixel(100, 150))

            frame.eraseColor(Color.TRANSPARENT)
            effect.drawGachaFrame(canvas, .64f)
            assertEquals("셔터 뒤에는 전달받은 차량을 표시한다", Color.GREEN, frame.getPixel(160, 137))
            assertEquals("공개된 차량이 작은 중앙 장식에 그치지 않아야 한다", Color.GREEN, frame.getPixel(100, 137))
        } finally {
            frame.recycle()
            sprite.recycle()
        }
    }

    fun testGachaRevealWaitsForTapAndAcceptsOnlyOneTap() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val parent = FrameLayout(host)
        val effect = RewardCelebrationView(host)
        var starts = 0
        var finishes = 0
        onUi { host.setContentView(parent) }
        awaitWindowFocus(parent)
        onUi {
            effect.onStarted = { starts++ }
            assertTrue(effect.holdGacha("saved-result", Color.MAGENTA, null) { finishes++ })
            parent.addView(effect.over(View(host), fillHeight = true), FrameLayout.LayoutParams(-1, -1))
        }
        instrumentation.waitForIdleSync()
        onUi {
            val bitmap = Bitmap.createBitmap(effect.width, effect.height, Bitmap.Config.ARGB_8888)
            try {
                effect.draw(Canvas(bitmap))
                assertTrue(effect.waitingForReveal)
                assertEquals(0, starts)
                assertTrue(effect.openGacha())
                assertFalse(effect.openGacha())
                assertEquals(1, starts)
                effect.onWindowFocusChanged(false)
                assertFalse(effect.openGacha())
            } finally {
                bitmap.recycle()
            }
        }
        instrumentation.waitForIdleSync()
        onUi { assertEquals(1, finishes) }
    }

    fun testGachaStageRemainsWhenResultIsReopenedWithoutAnEffectKey() = onUi {
        val effect = RewardCelebrationView(host).apply {
            onStarted = { fail("다시 연 결과가 연출을 시작했습니다") }
            render(null, RewardCelebrationKind.GACHA)
        }
        val content = View(host)
        val stage = effect.over(content, fillHeight = true, stage = true) as FrameLayout
        assertEquals(1, stage.childCount)
        assertSame(content, stage.getChildAt(0))
        val size = View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)
        stage.measure(size, size)
        stage.layout(0, 0, 200, 200)
        val frame = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        try {
            stage.draw(Canvas(frame))
            assertEquals(0xff151a30.toInt(), frame.getPixel(100, 20))
        } finally {
            frame.recycle()
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
