package io.drivegrowth.app.ui.garage

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.ScrollView

/** 장치 사용 조율 후 실행한다. UI 스레드 실패는 시험 스레드에서 보고한다. */
@Suppress("DEPRECATION")
class GarageViewTest : InstrumentationTestCase() {
    private fun runOnUiTest(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun button(view: View, title: String) = descendants(view).filterIsInstance<Button>().first { it.text.toString() == title }
    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }

    fun testInstanceSelectionAndDuplicateRequest() = runOnUiTest {
        val events = mutableListOf<GarageEvent>()
        val view = GarageView(instrumentation.targetContext, events::add)
        view.render(GaragePreviewStates.normal, true)
        descendants(view).filterIsInstance<Button>().first { it.contentDescription.toString().startsWith("캐스퍼 #1,") }.performClick()
        assertTrue(texts(view).any { it == "캐스퍼 #1" })
        button(view, "판매 조건 확인").performClick()
        assertFalse(button(view, "판매 조건 확인").isEnabled)
        button(view, "판매 조건 확인").performClick()
        assertEquals(1, events.filterIsInstance<GarageEvent.RequestAction>().size)
        view.render(GaragePreviewStates.normal, true)
        assertFalse(button(view, "판매 조건 확인").isEnabled)
        assertFalse(texts(view).any { it.contains("판매 완료") })
    }

    fun testMissingSelectionAndRestrictedContent() = runOnUiTest {
        val view = GarageView(instrumentation.targetContext) {}
        view.render(GaragePreviewStates.normal, true)
        descendants(view).filterIsInstance<Button>().first().performClick()
        view.render(GaragePreviewStates.removed, true)
        assertTrue(texts(view).contains("선택한 차량을 찾을 수 없어요"))
        view.render(GaragePreviewStates.normal, false)
        assertTrue(descendants(view).none { it is Button })
        assertFalse(texts(view).any { it.contains("캐스퍼") })
    }

    fun testEmptyLoadingErrorAndRetry() = runOnUiTest {
        var retries = 0
        val view = GarageView(instrumentation.targetContext) { if (it == GarageEvent.Retry) retries++ }
        view.render(GaragePreviewStates.empty, true)
        assertTrue(texts(view).contains("차고에 보유 차량이 없어요"))
        view.render(GaragePreviewStates.loading, true)
        assertTrue(texts(view).contains("차고를 불러오고 있어요"))
        view.render(GaragePreviewStates.error, true)
        button(view, "다시 시도").performClick()
        button(view, "다시 시도").performClick()
        assertEquals(1, retries)
    }

    fun testCollectionKeepsHistoricalOwnershipSeparateFromUnlockAndEmpty() = runOnUiTest {
        val view = CollectionView(instrumentation.targetContext) {}
        view.render(GaragePreviewStates.collection, true)
        val text = texts(view)
        assertEquals(20, text.count { it == "보유 이력 있음" })
        assertEquals(20, text.count { it == "발견 · 보유 이력 없음" })
        assertEquals(40, text.count { it == "미발견" })
        assertTrue(text.any { it.startsWith("잠김") })
        view.render(GaragePreviewStates.emptyCollection, true)
        assertTrue(texts(view).contains("도감 데이터가 없어요"))
    }
    fun testCollectionScrollSurvivesRenderAtNarrowAndWideSizes() = runOnUiTest {
        val view = CollectionView(instrumentation.targetContext) {}
        fun layout(width: Int) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, 480)
        }
        view.render(GaragePreviewStates.collection, true)
        layout(800)
        descendants(view).filterIsInstance<ScrollView>().single().scrollTo(0, 700)
        val before = descendants(view).filterIsInstance<ScrollView>().single().scrollY
        assertTrue(before > 0)
        view.render(GaragePreviewStates.collection, true)
        view.render(GaragePreviewStates.collection, true)
        layout(800)
        assertEquals(before, descendants(view).filterIsInstance<ScrollView>().single().scrollY)
        layout(1360)
        assertTrue(texts(view).contains("보유 이력 있음"))
    }

}
