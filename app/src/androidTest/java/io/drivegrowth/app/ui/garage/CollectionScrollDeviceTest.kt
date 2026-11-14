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
                    .filterIsInstance<ScrollView>().all { it.isLaidOut } &&
                    (host.collection.findViewWithTag<Button>("collection:next")?.isLaidOut != false) }) return
            Thread.sleep(20)
        }
        fail("도감의 실제 layout이 완료되지 않았다")
    }
    private fun render(state: CollectionScreenState, enabled: Boolean = true) {
        onUi { host.collection.render(state, enabled) }
        waitForLayout()
    }
    private fun pageLabel() = host.collection.findViewWithTag<TextView>("collection:page")?.text?.toString()

    private fun advance(): String {
        val before = onUi { pageLabel() }
        onUi {
            val next = host.collection.findViewWithTag<Button>("collection:next")
            assertTrue(next.isEnabled)
            assertTrue(next.performClick())
        }
        waitForLayout()
        return checkNotNull(onUi { pageLabel() }).also { assertTrue(it != before) }
    }

    fun testLoadingLayoutPreservesPage() {
        val before = advance()
        render(CollectionScreenState.Loading)
        assertNull(onUi { pageLabel() })
        render(GaragePreviewStates.collection)
        assertEquals(before, onUi { pageLabel() })
    }

    fun testErrorLayoutPreservesPage() {
        val before = advance()
        render(GaragePreviewStates.collectionError)
        assertNull(onUi { pageLabel() })
        render(GaragePreviewStates.collection)
        assertEquals(before, onUi { pageLabel() })
    }

    fun testConsecutiveContentRendersPreservePage() {
        val before = advance()
        onUi {
            host.collection.render(GaragePreviewStates.collection, true)
            host.collection.render(GaragePreviewStates.collection, true)
        }
        waitForLayout()
        assertEquals(before, onUi { pageLabel() })
    }

    fun testRestrictedScreenHidesContentAndPreservesPageOnReturn() {
        val before = advance()
        render(GaragePreviewStates.collection, false)
        assertNull(onUi { pageLabel() })
        assertFalse(onUi { descendants(host.collection).any { it.contentDescription?.contains("보유 이력 있음") == true } })
        render(GaragePreviewStates.collection)
        assertEquals(before, onUi { pageLabel() })
    }

    fun testShorterContentClampsPageAndKeepsThatPosition() {
        advance()
        advance()
        render(CollectionScreenState.Loading)
        val shorter = GaragePreviewStates.collection.copy(entries = GaragePreviewStates.collection.entries.take(4))
        render(shorter)
        assertEquals("1 / 1", onUi { pageLabel() })
        assertFalse(onUi { host.collection.findViewWithTag<Button>("collection:next").isEnabled })
        assertFalse(onUi { host.collection.findViewWithTag<Button>("collection:previous").isEnabled })

        render(GaragePreviewStates.collection)
        assertTrue(onUi { pageLabel()?.startsWith("1 / ") == true })
    }

    fun testReturningToSameViewAfterLeavingKeepsFilterAndPage() {
        onUi { host.collection.findViewWithTag<Button>("collection:filter:DISCOVERED").performClick() }
        val before = advance()
        onUi {
            host.setContentView(View(host))
            assertFalse(host.collection.isAttachedToWindow)
            host.collection.render(GaragePreviewStates.collection, true)
            host.setContentView(host.collection)
        }
        waitForLayout()
        assertEquals(before, onUi { pageLabel() })
        assertTrue(onUi { host.collection.findViewWithTag<Button>("collection:filter:DISCOVERED").isSelected })
    }
}
