package io.drivegrowth.app

import android.test.InstrumentationTestCase
import android.view.View
import android.widget.Button
import io.drivegrowth.app.host.HostPage
import java.util.concurrent.FutureTask

@Suppress("DEPRECATION")
class MainNavigationBarTest : InstrumentationTestCase() {
    fun testFivePrimaryMenusFit320DpAndShowSelectedSection() {
        val task = FutureTask {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val width = (320 * density).toInt()
            val height = (72 * density).toInt()
            val minimum = (48 * density).toInt()
            val actions = mutableListOf<HostPage>()
            val bar = MainNavigationBar(context, HostPage.COLLECTION, actions::add)
            bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            bar.layout(0, 0, width, height)
            val buttons = (0 until bar.childCount).map { bar.getChildAt(it) as Button }
            assertEquals(listOf("가게", "차고", "탁송", "뽑기", "기록"), buttons.map { it.text.toString() })
            assertEquals(listOf("차고"), buttons.filter { it.isSelected }.map { it.text.toString() })
            for (button in buttons) {
                assertTrue(button.left >= 0 && button.right <= bar.width)
                assertTrue(button.width >= minimum && button.height >= minimum)
                assertTrue(button.performClick())
            }
            assertEquals(listOf(HostPage.HOME, HostPage.GARAGE, HostPage.DELIVERY, HostPage.GACHA, HostPage.RESULTS), actions)
        }
        instrumentation.runOnMainSync(task)
        task.get()
    }
}
