package io.drivegrowth.app.ui.presentation

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
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
import android.widget.TextView
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.core.contract.Rarity
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
class RewardCelebrationDeviceTest : InstrumentationTestCase() {
    private lateinit var host: CollectionScrollTestActivity

    override fun setUp() {
        super.setUp()
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val intent = Intent(context, CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        host = instrumentation.startActivitySync(intent, options) as CollectionScrollTestActivity
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

    fun testSettlementCoinsHaveSeparateSpaceFromWrappedAmounts() = onUi {
        val effect = RewardCelebrationView(host)
        val amount = TextView(host).apply {
            textSize = 36f
            text = "이번 거래의 재화 변화\n+1,000 게임머니\n정산 후 확정 잔액\n2,345 게임머니"
        }
        effect.render("saved-settlement", RewardCelebrationKind.SETTLEMENT)
        val frame = effect.over(amount)
        for (width in listOf(260, 700)) {
            frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            frame.layout(0, 0, width, frame.measuredHeight)
            assertTrue("동전 영역은 텍스트가 끝난 뒤에 시작해야 한다", amount.bottom <= effect.top)
            assertTrue("동전 영역이 화면 밖으로 넘치면 안 된다", effect.bottom <= frame.height)
            assertTrue("동전을 그릴 영역이 있어야 한다", effect.height > 0)
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

    fun testLegendRevealAddsTwoFlashesAndConfettiOverTheConfirmedVehicle() = onUi {
        val sprite = Bitmap.createBitmap(20, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val frame = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(frame)
            val effect = RewardCelebrationView(host)
            effect.layout(0, 0, frame.width, frame.height)
            fun draw(progress: Float) {
                frame.eraseColor(Color.TRANSPARENT)
                effect.drawGachaFrame(canvas, progress)
            }
            effect.holdGacha("common", Color.WHITE, sprite, Rarity.COMMON) {}
            draw(.54f)
            assertEquals(Color.GREEN, frame.getPixel(160, 137))

            effect.holdGacha("legend", Color.YELLOW, sprite, Rarity.LEGEND) {}
            draw(.54f)
            assertTrue(Color.blue(frame.getPixel(160, 137)) > 0)
            draw(.78f)
            assertTrue(Color.red(frame.getPixel(160, 137)) > 0)

            draw(.92f)
            val pixels = IntArray(frame.width * frame.height)
            frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
            assertTrue(pixels.any { it == 0xff5ce4ad.toInt() })
        } finally {
            frame.recycle()
            sprite.recycle()
        }
    }

    fun testReferenceConfettiUsesSmallRectanglesAndDropsFromAbove() = onUi {
        val bitmap = Bitmap.createBitmap(1360, 948, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val effect = RewardCelebrationView(host)
            effect.referenceConfetti(canvas, .02f, 44, 5)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.none { it != Color.TRANSPARENT })

            effect.referenceConfetti(canvas, .45f, 44, 5)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val painted = pixels.indices.filter { pixels[it] != Color.TRANSPARENT }
            assertTrue(painted.isNotEmpty())
            assertTrue(painted.size <= 44 * 8 * 12)
            assertTrue(painted.all { it / bitmap.width < 118 })
        } finally {
            bitmap.recycle()
        }
    }

    fun testLegendVehicleMovesLeftWithinTheSameReferenceStage() = onUi {
        val sprite = Bitmap.createBitmap(60, 42, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val bitmap = Bitmap.createBitmap(1360, 948, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val effect = RewardCelebrationView(host)
            fun center(time: Float): Int {
                effect.drawReferenceGacha(canvas, time, sprite, Rarity.LEGEND)
                val row = IntArray(bitmap.width)
                bitmap.getPixels(row, 0, bitmap.width, 0, 640, bitmap.width, 1)
                val vehicle = row.indices.filter { row[it] == Color.GREEN }
                assertTrue(vehicle.size >= 250)
                return (vehicle.first() + vehicle.last()) / 2
            }
            assertTrue(center(4.60f) in 650..730)
            assertTrue(center(5.60f) in 420..500)
        } finally {
            bitmap.recycle()
            sprite.recycle()
        }
    }

    fun testGachaRevealWaitsForTapAndAcceptsOnlyOneTap() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val parent = FrameLayout(host)
        val effect = RewardCelebrationView(host)
        var starts = 0
        val finishes = mutableListOf<Boolean>()
        var ready = 0
        var stopped = 0
        var waitStartedAt = 0L
        var readyAt = 0L
        val cues = mutableListOf<Pair<Rarity, Boolean>>()
        onUi { host.setContentView(parent) }
        awaitWindowFocus(parent)
        onUi {
            effect.onStarted = { starts++ }
            effect.onGachaReady = { ready++; readyAt = SystemClock.uptimeMillis() }
            effect.onGachaAudio = { rarity, opening ->
                if (!opening) waitStartedAt = SystemClock.uptimeMillis()
                cues.add(rarity to opening)
            }
            effect.onGachaAudioCancelled = { stopped++ }
            assertTrue(effect.holdGacha("saved-result", Color.MAGENTA, null, Rarity.LEGEND) { finishes += it })
            parent.addView(effect.over(View(host), fillHeight = true), FrameLayout.LayoutParams(-1, -1))
            assertTrue(effect.waitingForReveal)
            assertFalse(effect.canOpenGacha)
            assertFalse("셔터 대기 중에는 탭해도 결과를 공개하지 않는다", effect.openGacha())
        }
        val deadline = SystemClock.uptimeMillis() + 5000L
        while (SystemClock.uptimeMillis() < deadline) {
            var isReady = false
            onUi { isReady = ready > 0 }
            if (isReady) break
            SystemClock.sleep(40L)
        }
        onUi {
            assertEquals("셔터가 실제 활성 창에서 2초를 기다린 뒤 한 번 준비된다 · " +
                shutterDiagnostics(effect, waitStartedAt, finishes.size), 1, ready)
            assertTrue(readyAt - waitStartedAt >= 2000L)
            assertEquals(0, starts)
            assertTrue(effect.openGacha())
            assertFalse(effect.openGacha())
            assertEquals(1, starts)
            assertEquals(listOf(Rarity.COMMON to false, Rarity.LEGEND to true), cues)
            effect.onWindowFocusChanged(false)
            assertFalse(effect.openGacha())
            assertEquals(1, stopped)
        }
        instrumentation.waitForIdleSync()
        onUi { assertEquals(listOf(false), finishes) }
    }

    fun testGachaStaysClosedAfterTheFormerAutomaticOpenTime() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val parent = FrameLayout(host)
        val effect = RewardCelebrationView(host)
        var waitingAt = 0L
        var openedAt = 0L
        var opened = 0
        val finishes = mutableListOf<Boolean>()
        onUi { host.setContentView(parent) }
        awaitWindowFocus(parent)
        onUi {
            effect.onGachaAudio = { _, opening -> if (!opening) waitingAt = SystemClock.uptimeMillis() }
            effect.onGachaOpened = { opened++; openedAt = SystemClock.uptimeMillis() }
            assertTrue(effect.holdGacha("automatic-result", Color.MAGENTA, null) { finishes += it })
            parent.addView(effect.over(View(host), fillHeight = true), FrameLayout.LayoutParams(-1, -1))
        }
        val deadline = SystemClock.uptimeMillis() + 8_500L
        while (SystemClock.uptimeMillis() < deadline) {
            var elapsedPastFormerOpen = false
            onUi { elapsedPastFormerOpen = waitingAt > 0 && SystemClock.uptimeMillis() - waitingAt >= 6_200L }
            if (elapsedPastFormerOpen) break
            SystemClock.sleep(50L)
        }
        onUi {
            assertTrue("이전 자동 공개 시간 이후를 확인한다", waitingAt > 0 &&
                SystemClock.uptimeMillis() - waitingAt >= 6_200L)
            assertEquals("입력 없이 열리지 않는다 · " + shutterDiagnostics(effect, waitingAt, 0), 0, opened)
            assertTrue(effect.waitingForReveal)
            assertTrue(effect.openGacha())
            assertEquals(1, opened)
            assertTrue(openedAt >= waitingAt + 6_200L)
            assertFalse(effect.openGacha())
        }
        val finishDeadline = SystemClock.uptimeMillis() + 3_000L
        while (SystemClock.uptimeMillis() < finishDeadline) {
            var completed = false
            onUi { completed = finishes.isNotEmpty() }
            if (completed) break
            SystemClock.sleep(40L)
        }
        onUi { assertEquals(listOf(true), finishes) }
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
            assertEquals(0xff141a33.toInt(), frame.getPixel(100, 20))
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

    private fun shutterDiagnostics(effect: RewardCelebrationView, waitedAt: Long, finishes: Int): String {
        val bounds = android.graphics.Rect()
        val visible = effect.getLocalVisibleRect(bounds)
        return "display=${effect.display?.displayId} focus=${effect.hasWindowFocus()} " +
            "attached=${effect.isAttachedToWindow} shown=${effect.isShown} laidOut=${effect.isLaidOut} " +
            "size=${effect.width}×${effect.height} visible=$visible bounds=$bounds " +
            "waitStartedAt=$waitedAt waiting=${effect.waitingForReveal} ready=${effect.canOpenGacha} finishes=$finishes"
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
