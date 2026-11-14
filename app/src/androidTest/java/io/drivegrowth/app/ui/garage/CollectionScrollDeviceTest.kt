package io.drivegrowth.app.ui.garage

import android.content.Intent
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView

/** 실제 Activity 부착과 중간 상태 layout을 거친다. assertion은 instrumentation 스레드에서 수행한다. */
@Suppress("DEPRECATION")
class CollectionScrollDeviceTest : InstrumentationTestCase() {
    private lateinit var host: CollectionScrollTestActivity

    override fun setUp() {
        super.setUp()
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        host = instrumentation.startActivitySync(Intent(context,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) as CollectionScrollTestActivity
        waitForLayout()
    }

    override fun tearDown() {
        if (::host.isInitialized) instrumentation.runOnMainSync { host.finish() }
        instrumentation.waitForIdleSync()
        super.tearDown()
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
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
            if (onUi { host.collection.isAttachedToWindow && !host.collection.isLayoutRequested && descendants(host.collection)
                    .filterIsInstance<ScrollView>().all { it.isLaidOut } }) return
            Thread.sleep(20)
        }
        fail("도감의 실제 layout이 완료되지 않았다")
    }
    private fun render(state: CollectionScreenState, enabled: Boolean = true) {
        onUi { host.collection.render(state, enabled) }
        waitForLayout()
    }
    private fun scroller() = host.collection.findViewWithTag<ScrollView>("collection:scroll")
    private fun scrollY() = scroller()?.scrollY

    /** 도감을 아래로 내리고 그 위치를 돌려준다. 칸이 화면보다 길어 실제로 내려가야 한다. */
    private fun scrollDown(): Int {
        onUi { scroller().scrollTo(0, scroller().getChildAt(0).height) }
        waitForLayout()
        return checkNotNull(onUi { scrollY() }).also { assertTrue("도감이 스크롤되지 않았다", it > 0) }
    }

    fun testAllVehiclesAreInOneScrollWithoutPager() {
        assertNull(onUi { host.collection.findViewWithTag<Button>("collection:next") })
        assertTrue(onUi { descendants(host.collection).filterIsInstance<TextView>().any { it.text.toString() == "GV80" } })
    }

    fun testLoadingLayoutPreservesScroll() {
        val before = scrollDown()
        render(CollectionScreenState.Loading)
        render(GaragePreviewStates.collection)
        assertEquals(before, onUi { scrollY() })
    }

    fun testErrorLayoutPreservesScroll() {
        val before = scrollDown()
        render(GaragePreviewStates.collectionError)
        render(GaragePreviewStates.collection)
        assertEquals(before, onUi { scrollY() })
    }

    fun testRestrictedScreenHidesContentAndPreservesScrollOnReturn() {
        val before = scrollDown()
        render(GaragePreviewStates.collection, false)
        assertNull(onUi { scroller() })
        assertFalse(onUi { descendants(host.collection).any { it.contentDescription?.contains("보유 이력 있음") == true } })
        render(GaragePreviewStates.collection)
        assertEquals(before, onUi { scrollY() })
    }

    fun testReturningToSameViewAfterLeavingKeepsFilterAndScroll() {
        onUi { host.collection.findViewWithTag<Button>("collection:filter:UNDISCOVERED").performClick() }
        waitForLayout()
        val before = scrollDown()
        onUi {
            host.setContentView(View(host))
            assertFalse(host.collection.isAttachedToWindow)
            host.collection.render(GaragePreviewStates.collection, true)
            host.setContentView(host.collection)
        }
        waitForLayout()
        assertEquals(before, onUi { scrollY() })
        assertTrue(onUi { host.collection.findViewWithTag<Button>("collection:filter:UNDISCOVERED").isSelected })
    }
}
