package io.drivegrowth.app

import android.test.InstrumentationTestCase
import android.view.View
import android.widget.Button
import io.drivegrowth.app.host.HostPage
import java.util.concurrent.FutureTask

@Suppress("DEPRECATION")
class MainNavigationBarTest : InstrumentationTestCase() {
    fun testSixFloatingMapMenusHaveIllustrationsAndCallExistingPages() {
        val task = FutureTask {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val width = (392 * density).toInt()
            val height = (72 * density).toInt()
            val minimum = (48 * density).toInt()
            val actions = mutableListOf<HostPage>()
            val bar = MainNavigationBar(context, actions::add)
            bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            bar.layout(0, 0, width, height)
            val buttons = (0 until bar.childCount).map { bar.getChildAt(it) as Button }
            assertEquals(listOf("차고", "도감", "뽑기", "탁송", "정산", "기록"), buttons.map { it.text.toString() })
            assertNull(bar.background)
            assertTrue(buttons.none { it.isSelected })
            for (button in buttons) {
                assertNotNull(button.compoundDrawablesRelative[1])
                assertTrue(button.left >= 0 && button.right <= bar.width)
                assertTrue(button.width >= minimum && button.height >= minimum)
                assertTrue(button.performClick())
            }
            assertEquals(listOf(HostPage.GARAGE, HostPage.COLLECTION, HostPage.GACHA, HostPage.DELIVERY, HostPage.RESULTS, HostPage.RANKING), actions)
        }
        instrumentation.runOnMainSync(task)
        task.get()
    }
}
