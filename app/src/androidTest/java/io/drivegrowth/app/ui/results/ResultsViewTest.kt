package io.drivegrowth.app.ui.results

import android.graphics.drawable.BitmapDrawable
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.core.contract.SettlementId

/** 실제 Android 위젯의 상태·layout 시험. assertion 실패는 시험 스레드로 돌려준다. */
@Suppress("DEPRECATION")
class ResultsViewTest : InstrumentationTestCase() {
    private fun <T> onUi(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { result = block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun texts(v: View) = descendants(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun button(v: View, tag: String) = v.findViewWithTag<Button>(tag)
    private fun open(view: ResultsView, id: String) { button(view, "results:open:$id").performClick() }
    private fun layout(view: ResultsView, widthDp: Int, heightDp: Int) {
        val d = view.resources.displayMetrics.density
        fun pass() {
            val w = (widthDp * d).toInt(); val h = (heightDp * d).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
        }
        onUi { pass() }; instrumentation.waitForIdleSync(); onUi { pass() }
    }

    fun testDetailsUseMoneyChangeAndBalanceAsDifferentFacts() {
        val view = onUi { ResultsView(instrumentation.targetContext) {}.apply { render(ResultsPreviewStates.normal, true) } }
        layout(view, 480, 500)
        onUi { open(view, "fixture-result-1") }
        val labels = onUi { texts(view) }
        val shown = labels.joinToString("\n")
        assertTrue(shown.contains("−350 게임머니"))
        assertTrue(shown.contains("정산 후 확정 잔액 · 이번 수익 합계 아님\n9,001 게임머니"))
        assertTrue(shown.contains("실제 취득 기준액\n0 게임머니"))
        assertFalse(shown.contains("fixture-request-1"))
        assertFalse(shown.contains("ui-fixture-v1"))
        assertNotNull(onUi { view.findViewWithTag<Button>("results:mark:fixture-result-1") })
        assertTrue(shown.contains("모의 입력"))
        assertTrue(shown.contains("고정 시험 입력"))
        assertTrue(instrumentation.targetContext.getString(R.string.results_details_unsupported) in labels)
        assertFalse(shown.contains("+9,001"))
    }

    fun testVehicleArtUsesTheSettledVehicleIdAndHidesWhenRestricted() = onUi {
        val context = instrumentation.targetContext
        val source = ResultsPreviewStates.normal
        val original = source.results.first { it.settlement.changedVehicle != null }
        val id = checkNotNull(original.settlement.changedVehicle).vehicleId
        val state = source.copy(results = listOf(original))
        val view = ResultsView(context) {}
        view.render(state, true)
        open(view, original.settlement.settlementId.value)
        val image = checkNotNull(view.findViewWithTag<ImageView>("results:vehicle-art:${id.value}"))
        val expected = checkNotNull(VehicleSprites(context).bitmap(id))
        assertTrue(expected.sameAs((image.drawable as BitmapDrawable).bitmap))
        view.render(state, false)
        assertNull(view.findViewWithTag<ImageView>("results:vehicle-art:${id.value}"))
    }

    fun testMarkViewedWaitsForResponseAndRetriesAfterFailure() {
        val events = mutableListOf<ResultsEvent>()
        val id = SettlementId("fixture-result-1")
        val view = onUi { ResultsView(instrumentation.targetContext, events::add).apply {
            render(ResultsPreviewStates.normal, true); open(this, id.value)
            button(this, "results:mark:${id.value}").performClick()
            button(this, "results:mark:${id.value}").performClick()
            render(ResultsPreviewStates.normal, true)
        } }
        assertEquals(1, events.filterIsInstance<ResultsEvent.MarkViewed>().size)
        assertTrue(onUi { texts(view).contains("열람 표시 저장 중") })
        assertFalse(onUi { texts(view).contains("열람 표시 저장 완료") })
        onUi {
            view.completeMarkViewed(id, ResultsPreviewStates.readFailure)
            button(view, "results:mark:${id.value}").performClick()
            view.completeMarkViewed(id, ResultsPreviewStates.readSuccess)
        }
        assertEquals(2, events.filterIsInstance<ResultsEvent.MarkViewed>().size)
        assertTrue(onUi { texts(view).contains("열람 표시 저장 완료") })
        assertFalse(onUi { button(view, "results:mark:${id.value}").isEnabled })
    }

    fun testRestrictedHidesFactsAndOldButtonsCannotEmitActions() {
        val events = mutableListOf<ResultsEvent>()
        val view = onUi { ResultsView(instrumentation.targetContext, events::add).apply { render(ResultsPreviewStates.normal, true) } }
        val stale = onUi { button(view, "results:open:fixture-result-1") }
        onUi { view.render(ResultsPreviewStates.normal, false); stale.performClick() }
        assertTrue(events.isEmpty())
        assertTrue(onUi { descendants(view).none { it is Button } })
        assertFalse(onUi { texts(view).any { it.contains("9,001") || it.contains("fixture-result") || it.contains("캐스퍼") } })
        assertFalse(onUi { view.closeDetail() })
    }

    fun testLoadingEmptyMissingUnsupportedAndExplicitRetryCompletion() {
        val events = mutableListOf<ResultsEvent>()
        val view = onUi { ResultsView(instrumentation.targetContext, events::add) }
        onUi { view.render(ResultsPreviewStates.empty, true) }
        assertTrue(onUi { texts(view).contains("미확인 정산이 없어요") })
        onUi { view.render(ResultsPreviewStates.loading, true) }
        assertTrue(onUi { texts(view).contains("미확인 결과를 불러오고 있어요") })
        onUi { view.render(ResultsPreviewStates.unsupported, true) }
        assertTrue(onUi { descendants(view).none { it is Button } })
        onUi { view.render(ResultsPreviewStates.missing, true) }
        assertFalse(onUi { texts(view).contains("확정 정산") })
        onUi {
            view.render(ResultsPreviewStates.error, true)
            button(view, "results:retry").performClick()
            view.render(ResultsPreviewStates.error, true)
            button(view, "results:retry").performClick()
        }
        assertEquals(1, events.count { it == ResultsEvent.Retry })
        onUi { view.completeRetry(); button(view, "results:retry").performClick() }
        assertEquals(2, events.count { it == ResultsEvent.Retry })
    }

    fun testRestoredSelectionMissingRecordAndClosedPendingRequest() {
        val id = SettlementId("fixture-result-1")
        val original = onUi { ResultsView(instrumentation.targetContext) {}.apply {
            render(ResultsPreviewStates.normal, true); restoreSelection(id)
            button(this, "results:mark:${id.value}").performClick()
        } }
        val restored = onUi { ResultsView(instrumentation.targetContext) {}.apply {
            restoreUiState(original.uiState); render(ResultsPreviewStates.normal, true)
        } }
        assertEquals(id, onUi { restored.selectedSettlementId })
        assertFalse(onUi { button(restored, "results:mark:${id.value}").isEnabled })
        assertTrue(onUi { restored.closeDetail() })
        assertFalse(onUi { restored.closeDetail() })
        onUi { restored.restoreSelection(id); restored.render(ResultsPreviewStates.empty, true) }
        assertTrue(onUi { texts(restored).contains("선택한 정산이 현재 목록에 없어요") })
        assertTrue(onUi { descendants(restored).filterIsInstance<Button>().none { it.tag == "results:mark:${id.value}" } })
    }

    fun testLongTextAndManyResultsFitNarrowAndWideAndPreserveListPosition() {
        val view = onUi { ResultsView(instrumentation.targetContext) {}.apply { render(ResultsPreviewStates.many, true) } }
        layout(view, 480, 360)
        onUi { view.findViewWithTag<ScrollView>("results:list").scrollTo(0, 700) }
        val before = onUi { view.findViewWithTag<ScrollView>("results:list").scrollY }
        assertEquals(700, before)
        onUi { view.render(ResultsPreviewStates.loading, true) }; layout(view, 480, 360)
        onUi { view.render(ResultsPreviewStates.many, true) }; layout(view, 480, 360)
        assertEquals(before, onUi { view.findViewWithTag<ScrollView>("results:list").scrollY })
        onUi { view.render(ResultsPreviewStates.error, true) }; layout(view, 480, 360)
        onUi { view.render(ResultsPreviewStates.many, false) }; layout(view, 480, 360)
        onUi { view.render(ResultsPreviewStates.many, true) }; layout(view, 480, 360)
        assertEquals(before, onUi { view.findViewWithTag<ScrollView>("results:list").scrollY })
        onUi { view.render(ResultsPreviewStates.longText, true); view.restoreSelection(SettlementId("fixture-result-1")) }
        layout(view, 480, 360)
        val overflows = onUi {
            descendants(view).filterIsInstance<TextView>().mapNotNull { text ->
                val content = text.layout ?: return@mapNotNull null
                // getLineWidth에는 줄 끝 공백이 포함된다. 화면에 표시되는 양쪽 경계를 확인한다.
                val lines = (0 until content.lineCount).filter { line ->
                    content.getLineLeft(line) < -2 || content.getLineRight(line) > content.width + 2
                }
                if (lines.isEmpty()) null else buildString {
                    append("${text.text} (layoutWidth=${content.width}, ")
                    append(lines.joinToString { line ->
                        "line=$line left=${content.getLineLeft(line)} right=${content.getLineRight(line)}"
                    })
                    append(")")
                }
            }
        }
        assertTrue("긴 텍스트가 가로로 넘치면 안 된다: $overflows", overflows.isEmpty())
        layout(view, 1360, 948)
        // 이 시험은 window 미부착 위젯이다. attach 전 post queue 대신 다음 render를 명시한다.
        onUi { view.render(ResultsPreviewStates.longText, true) }
        layout(view, 1360, 948)
        assertNotNull(onUi { view.findViewWithTag<ScrollView>("results:list") })
        assertNotNull(onUi { view.findViewWithTag<ScrollView>("results:detail") })
    }
}
