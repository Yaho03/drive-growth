package io.drivegrowth.app.ui.garage

import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView

/** 실제 Activity 부착과 중간 상태 layout을 거친다. assertion은 instrumentation 스레드에서 수행한다. */
@Suppress("DEPRECATION")
class CollectionScrollDeviceTest : InstrumentationTestCase() {
    private lateinit var host: CollectionScrollTestActivity

    override fun setUp() {
        super.setUp()
        host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CollectionScrollTestActivity
        waitForLayout()
    }

    override fun tearDown() {
        if (::host.isInitialized) instrumentation.runOnMainSync { host.finish() }
        instrumentation.waitForIdleSync()
        super.tearDown()
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun scroll() = descendants(host.collection).filterIsInstance<ScrollView>().single()
    private fun <T> onUi(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { result = block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    private fun waitForLayout() {
        repeat(150) {
            instrumentation.waitForIdleSync()
            if (onUi { !host.collection.isLayoutRequested && descendants(host.collection)
                    .filterIsInstance<ScrollView>().all { it.isLaidOut } }) return
            Thread.sleep(20)
        }
        fail("도감의 실제 layout이 완료되지 않았다")
    }
    private fun render(state: CollectionScreenState, enabled: Boolean = true) {
        onUi { host.collection.render(state, enabled) }
        waitForLayout()
    }
    private fun scrollToSavedPosition(): Int {
        onUi { scroll().scrollTo(0, 700) }
        instrumentation.waitForIdleSync()
        val position = onUi { scroll().scrollY }
        assertEquals("시험에 사용할 Content가 실제로 스크롤되어야 한다", 700, position)
        return position
    }

    fun testLoadingLayoutPreservesContentPosition() {
        val before = scrollToSavedPosition()
        render(CollectionScreenState.Loading)
        assertEquals(0, onUi { scroll().scrollY })
        render(GaragePreviewStates.collection)
        assertEquals(before, onUi { scroll().scrollY })
    }

    fun testErrorLayoutPreservesContentPosition() {
        val before = scrollToSavedPosition()
        render(GaragePreviewStates.collectionError)
        assertEquals(0, onUi { scroll().scrollY })
        render(GaragePreviewStates.collection)
        assertEquals(before, onUi { scroll().scrollY })
    }

    fun testConsecutiveContentRendersPreservePosition() {
        val before = scrollToSavedPosition()
        onUi {
            host.collection.render(GaragePreviewStates.collection, true)
            host.collection.render(GaragePreviewStates.collection, true)
        }
        waitForLayout()
        assertEquals(before, onUi { scroll().scrollY })
    }

    fun testRestrictedScreenHidesContentAndPreservesPositionOnReturn() {
        val before = scrollToSavedPosition()
        render(GaragePreviewStates.collection, false)
        assertFalse(onUi { descendants(host.collection).filterIsInstance<TextView>().any { it.text == "캐스퍼" } })
        render(GaragePreviewStates.collection)
        assertEquals(before, onUi { scroll().scrollY })
    }

    fun testShorterContentClampsPositionAndKeepsThatPosition() {
        scrollToSavedPosition()
        render(CollectionScreenState.Loading)
        val shorter = GaragePreviewStates.collection.copy(entries = GaragePreviewStates.collection.entries.take(4))
        render(shorter)
        val bounded = onUi {
            val current = scroll()
            val available = (current.getChildAt(0).height - current.height + current.paddingTop + current.paddingBottom).coerceAtLeast(0)
            current.scrollY to available
        }
        assertEquals(minOf(700, bounded.second), bounded.first)
        render(GaragePreviewStates.collection)
        assertEquals(bounded.first, onUi { scroll().scrollY })
    }
}
