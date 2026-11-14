package io.drivegrowth.app.ui.presentation

import android.app.ActivityOptions
import android.app.Dialog
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity

@Suppress("DEPRECATION")
class PrototypeTitleViewDeviceTest : InstrumentationTestCase() {
    private lateinit var host: CollectionScrollTestActivity
    private lateinit var title: PrototypeTitleView
    private val calls = mutableListOf<String>()
    private var time = 10_000L
    private var motion = true

    override fun setUp() {
        super.setUp()
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        host = instrumentation.startActivitySync(Intent(context,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) as CollectionScrollTestActivity
        onUi {
            title = PrototypeTitleView(host, onStarting = { calls += "starting" }, uptimeMs = { time },
                animationsEnabled = { motion }) { calls += "start" }
            host.setContentView(title)
            title.setPresentationAllowed(true)
        }
        await { title.hasWindowFocus() && title.isClickable && button().width > 0 && button().alpha == 0f }
    }

    override fun tearDown() {
        if (::host.isInitialized) onUi { host.finish() }
        super.tearDown()
    }

    fun testHiddenButtonAreaSkipsIntroThenVisibleButtonStartsOnFirstTap() {
        onUi {
            tapStartPosition()
        }
        await { button().alpha == 1f }
        onUi {
            assertTrue(calls.isEmpty())
            assertTrue(button().isEnabled)
            tapStartPosition()
        }
        await { calls.isNotEmpty() }
        onUi {
            button().performClick()
            title.performClick()
            assertEquals(listOf("starting"), calls)
            assertFalse(button().isEnabled)
            time += 480L
        }
        await { calls == listOf("starting", "start") }
        onUi {
            button().performClick()
            title.performClick()
            assertEquals(listOf("starting", "start"), calls)
        }
    }

    fun testFirstVisibleButtonTapIsAcceptedBeforeIntroEnds() {
        onUi { time += 3_650L }
        await { button().alpha > 0f }
        onUi {
            assertTrue(button().alpha < 1f)
            assertTrue(button().isEnabled)
            tapStartPosition()
        }
        await { calls == listOf("starting") }
        onUi { time += 480L }
        await { calls == listOf("starting", "start") }
    }

    fun testBackgroundRevealsPosterAndNeverStarts() {
        onUi {
            assertTrue(title.performClick())
            assertTrue(button().alpha > 0f)
            title.performClick()
            time += 5_000L
        }
        await { button().alpha in .59f..1f }
        onUi {
            title.performClick()
            assertTrue(calls.isEmpty())
            assertTrue(button().isEnabled)
        }
    }

    fun testDisablingMotionDuringIntroShowsStaticPoster() {
        onUi { motion = false }
        await { button().alpha == 1f && title.findViewWithTag<View>("presentation:title-first").alpha == 1f }
        onUi {
            title.performClick()
            assertTrue(calls.isEmpty())
            assertTrue(button().performClick())
            assertEquals(listOf("starting"), calls)
        }
        await { calls == listOf("starting", "start") }
    }

    fun testDisablingMotionDuringExitCompletesWithoutClockAdvancing() {
        revealStart()
        onUi {
            assertTrue(button().performClick())
            assertEquals(listOf("starting"), calls)
            motion = false
        }
        await { calls == listOf("starting", "start") }
        onUi {
            button().performClick()
            title.performClick()
            assertEquals(listOf("starting", "start"), calls)
        }
    }

    fun testAcceptedStartWaitsForFocusToReturn() {
        revealStart()
        val dialog = onUi {
            assertTrue(button().performClick())
            Dialog(host).apply { setContentView(View(host)); show() }
        }
        try {
            await { !title.hasWindowFocus() }
            onUi {
                time += 500L
                motion = false
                assertEquals(listOf("starting"), calls)
            }
            instrumentation.waitForIdleSync()
            onUi { assertEquals(listOf("starting"), calls) }
        } finally {
            onUi { dialog.dismiss() }
        }
        await { title.hasWindowFocus() && calls == listOf("starting", "start") }
    }

    fun testRevocationAndDetachCancelPendingStart() {
        revealStart()
        onUi {
            button().performClick()
            title.setPresentationAllowed(false)
            motion = false
            time += 500L
        }
        instrumentation.waitForIdleSync()
        onUi {
            assertEquals(listOf("starting"), calls)
            assertFalse(button().isEnabled)
            motion = true
            title.setPresentationAllowed(true)
        }
        await { button().isEnabled }
        onUi {
            button().performClick()
            val parent = title.parent as FrameLayout
            parent.removeView(title)
            parent.addView(title)
            time += 500L
        }
        await { title.isClickable && button().alpha == 0f }
        onUi { time += 500L }
        revealStart()
        onUi { assertEquals(listOf("starting", "starting"), calls) }
    }

    private fun button() = title.findViewWithTag<Button>("presentation:start")

    private fun revealStart() = onUi {
        assertTrue(title.performClick())
        assertTrue(button().isEnabled)
    }

    private fun tapStartPosition() {
        val start = button()
        touch(start.x + start.width / 2f, start.y + start.height / 2f)
    }

    private fun touch(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            try {
                assertTrue(title.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (onUi(condition)) return
            SystemClock.sleep(20L)
        }
        fail("제한 시간 안에 타이틀 상태가 바뀌지 않았습니다")
    }

    private fun <T> onUi(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
