package io.drivegrowth.app

import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.HorizontalScrollView
import io.drivegrowth.app.host.HostPage
import java.util.concurrent.FutureTask

@Suppress("DEPRECATION")
class MainNavigationBarTest : InstrumentationTestCase() {
    fun testNarrowBarKeepsAllPagesReachableAndRevealsSelectedPage() {
        val task = FutureTask {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val width = (320 * density).toInt()
            val height = (72 * density).toInt()
            val actions = mutableListOf<HostPage>()
            val bar = MainNavigationBar(context, HostPage.entries.map { it to it.name }, actions::add, {}, {})
            fun layout() {
                bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                bar.layout(0, 0, width, height)
            }
            bar.showSelected(HostPage.ONBOARDING)
            layout()
            val scroll = bar.findViewWithTag<HorizontalScrollView>("host:pages")
            assertTrue(scroll.scrollX > 0)
            val selected = checkNotNull(bar.mainPage(HostPage.ONBOARDING))
            val bounds = Rect(0, 0, selected.width, selected.height)
            scroll.offsetDescendantRectToMyCoords(selected, bounds)
            assertTrue(bounds.left >= scroll.scrollX && bounds.right <= scroll.scrollX + scroll.width)
            assertTrue(HostPage.entries.all { bar.mainPage(it) != null })
            assertEquals(listOf(HostPage.ONBOARDING), HostPage.entries.filter { bar.mainPage(it)?.isSelected == true })
            assertTrue(checkNotNull(bar.mainPage(HostPage.HOME)).performClick())
            assertEquals(listOf(HostPage.HOME), actions)
        }
        instrumentation.runOnMainSync(task)
        task.get()
    }
}
