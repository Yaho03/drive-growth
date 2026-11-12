package io.drivegrowth.app.ui.progress

import android.os.SystemClock
import android.view.ViewGroup
import android.test.ActivityInstrumentationTestCase2
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ScrollView
import java.util.concurrent.FutureTask

@Suppress("DEPRECATION")
class ProgressScrollDeviceTest :
    ActivityInstrumentationTestCase2<ProgressScrollTestActivity>(ProgressScrollTestActivity::class.java) {
    private val content = ProgressPreviewStates.many

    private fun <T> onUi(block: () -> T): T {
        val task = FutureTask<T> { block() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun scroll(view: ProgressView): ScrollView? =
        (0 until view.childCount).map(view::getChildAt).filterIsInstance<ScrollView>().singleOrNull()

    private fun waitForLayout(view: ProgressView, hasScroll: Boolean = true) {
        val end = SystemClock.uptimeMillis() + 3_000
        do {
            instrumentation.waitForIdleSync()
            if (onUi {
                val current = scroll(view)
                view.isLaidOut && !view.isLayoutRequested &&
                    if (hasScroll) current?.isLaidOut == true else current == null
            }) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < end)
        fail("진행 화면이 실제 창에 배치되지 않았다")
    }

    private fun render(view: ProgressView, state: ProgressScreenState, enabled: Boolean = true) {
        assertTrue(onUi { view.render(state, enabled) })
        waitForLayout(view, hasScroll = enabled)
    }

    private fun scrollY(view: ProgressView) = onUi { requireNotNull(scroll(view)).scrollY }

    private fun scrollContent(view: ProgressView): Int {
        waitForLayout(view)
        onUi { requireNotNull(scroll(view)).scrollTo(0, 700) }
        val position = scrollY(view)
        assertEquals("시험 목록이 실제로 스크롤되어야 한다", 700, position)
        return position
    }

    fun testLoadingLayoutPreservesContentPosition() {
        val view = activity.progress
        val position = scrollContent(view)
        render(view, ProgressScreenState.Loading)
        assertEquals(0, scrollY(view))
        render(view, content)
        assertEquals("로딩 뒤 목록 위치", position, scrollY(view))
    }

    fun testFailureLayoutPreservesContentPosition() {
        val view = activity.progress
        val position = scrollContent(view)
        render(view, ProgressPreviewStates.failure)
        assertEquals(0, scrollY(view))
        render(view, content)
        assertEquals("오류 뒤 목록 위치", position, scrollY(view))
    }

    fun testRestrictionHidesContentAndPreservesDetailSelectionAndListPosition() {
        val view = activity.progress
        val position = scrollContent(view)
        val key = content.entries[3].key
        assertTrue(onUi { view.findViewWithTag<Button>(key).performClick() })
        waitForLayout(view)
        assertEquals(key, onUi { view.uiState.selectedKey })
        render(view, content, enabled = false)
        assertNull(onUi { view.findViewWithTag<Button>("progress-claim") })
        assertNull(onUi { view.findViewWithTag<Button>(key) })
        assertEquals(key, onUi { view.uiState.selectedKey })
        render(view, ProgressScreenState.Loading)
        render(view, content)
        assertEquals(key, onUi { view.uiState.selectedKey })
        assertNotNull(onUi { view.findViewWithTag<Button>("progress-claim") })
        assertTrue(onUi { view.handleBack() })
        waitForLayout(view)
        assertNull(onUi { view.uiState.selectedKey })
        assertEquals("상세를 닫은 뒤 목록 위치", position, scrollY(view))
    }

    fun testSameDetailOffsetAcrossRestrictionDetachLoadingAndFreshContent() {
        val host = activity
        val view = host.progress
        val key = content.entries[3].key
        val frame = onUi {
            (view.parent as ViewGroup).removeView(view)
            FrameLayout(host).apply {
                host.setContentView(this)
                val height = (208 * resources.displayMetrics.density).toInt()
                addView(view, FrameLayout.LayoutParams(-1, height))
            }
        }
        waitForLayout(view)
        assertTrue(onUi { view.findViewWithTag<Button>(key).performClick() })
        waitForLayout(view)
        val detailParams = onUi { view.layoutParams }
        val old = onUi {
            requireNotNull(scroll(view)).apply { scrollTo(0, 160) }.scrollY
        }
        assertTrue("the selected detail has to be scrolled before the gate changes", old > 0)

        onUi {
            assertTrue(view.render(ProgressScreenState.Loading, false))
            frame.removeView(view)
            frame.addView(TextView(host))
        }
        instrumentation.waitForIdleSync()
        assertFalse(onUi { view.isAttachedToWindow })
        assertEquals(key, onUi { view.uiState.selectedKey })

        onUi {
            assertTrue(view.render(ProgressScreenState.Loading, true))
            frame.removeAllViews()
            frame.addView(view, detailParams)
        }
        waitForLayout(view)
        assertEquals(0, scrollY(view))
        onUi {
            view.completeRetry()
            assertTrue(view.render(content.copy(sequence = content.sequence + 1), true))
        }
        waitForLayout(view)
        assertEquals(key, onUi { view.uiState.selectedKey })
        assertEquals("reattaching the same detail and replacing Loading must preserve its offset", old, scrollY(view))
    }

    fun testUnlaidContentReplacementKeepsLastLaidOutPosition() {
        val view = activity.progress
        val position = scrollContent(view)
        onUi {
            view.render(content, true)
            view.render(content, true)
        }
        waitForLayout(view)
        assertEquals(position, scrollY(view))
    }
}
