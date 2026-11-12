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
        verifyNarrowBar(HostPage.entries.map { it to it.name })
    }

    fun testLongLastLabelFitsWithinNarrowViewport() {
        val destinations = HostPage.entries.filterNot { it == HostPage.ONBOARDING }.map { it to it.name } +
            (HostPage.ONBOARDING to "첫 차량 안내 및 렌터카 영업 시작 준비와 운영 방법")
        verifyNarrowBar(destinations)
    }

    private fun verifyNarrowBar(destinations: List<Pair<HostPage, String>>) {
        val task = FutureTask {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val width = (320 * density).toInt()
            val height = (72 * density).toInt()
            val actions = mutableListOf<HostPage>()
            val bar = MainNavigationBar(context, destinations, actions::add, {}, {})
            fun layout() {
                bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                bar.layout(0, 0, width, height)
            }
            bar.showSelected(HostPage.ONBOARDING)
            layout()
            val scroll = bar.findViewWithTag<HorizontalScrollView>("host:pages")
            assertTrue(scroll.scrollX > 0)
            fun assertFullyVisible(page: HostPage) {
                val selected = checkNotNull(bar.mainPage(page))
                val bounds = Rect()
                selected.getDrawingRect(bounds)
                scroll.offsetDescendantRectToMyCoords(selected, bounds)
                val start = scroll.scrollX + scroll.paddingLeft
                val end = scroll.scrollX + scroll.width - scroll.paddingRight
                assertTrue("$page button width ${selected.width} exceeds viewport ${end - start}", selected.width <= end - start)
                assertTrue("$page bounds $bounds outside viewport [$start, $end]", bounds.left >= start && bounds.right <= end)
            }
            assertFullyVisible(HostPage.ONBOARDING)
            assertTrue(HostPage.entries.all { bar.mainPage(it) != null })
            assertEquals(listOf(HostPage.ONBOARDING), HostPage.entries.filter { bar.mainPage(it)?.isSelected == true })
            scroll.scrollTo(0, 0)
            bar.showSelected(HostPage.ONBOARDING)
            layout()
            assertEquals(0, scroll.scrollX)
            bar.showSelected(HostPage.GACHA)
            layout()
            assertFullyVisible(HostPage.GACHA)
            bar.showSelected(HostPage.HOME)
            layout()
            assertFullyVisible(HostPage.HOME)
            assertTrue(checkNotNull(bar.mainPage(HostPage.HOME)).performClick())
            assertEquals(listOf(HostPage.HOME), actions)
        }
        instrumentation.runOnMainSync(task)
        task.get()
    }
}
