package io.drivegrowth.app.ui.progress

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/** 기기 슬롯 배정 뒤 실행한다. 지금은 컴파일 검증만 하며 실제 실행으로 보고하지 않는다. */
@Suppress("DEPRECATION")
class ProgressViewTest : InstrumentationTestCase() {
    private fun onUi(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun texts(view: View) = all(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun claim(view: View) = all(view).filterIsInstance<Button>().single { it.tag == "progress-claim" }
    private fun select(view: View) = all(view).filterIsInstance<Button>().single { it.tag == ProgressPreviewStates.firstKey }.performClick()

    fun testClaimIsOnlyARequestUntilNewConfirmedSnapshotAndOldButtonsCannotReplay() = onUi {
        val events = mutableListOf<ProgressEvent>()
        val view = ProgressView(instrumentation.targetContext, events::add)
        view.render(ProgressPreviewStates.normal, true); select(view)
        val button = claim(view); button.performClick(); button.performClick()
        assertEquals(1, events.filterIsInstance<ProgressEvent.ClaimReward>().size)
        assertTrue(texts(view).contains("청구 결과 확인 중"))
        assertFalse(texts(view).contains("수령 확인됨"))
        view.render(ProgressPreviewStates.received, true)
        button.performClick()
        assertEquals(1, events.filterIsInstance<ProgressEvent.ClaimReward>().size)
        assertTrue(texts(view).contains("수령 확인됨")); assertFalse(claim(view).isEnabled)
        assertFalse(view.render(ProgressPreviewStates.normal, true))
    }
    fun testRestrictionAndSelectionDisappearanceCannotCreateAReward() = onUi {
        val events = mutableListOf<ProgressEvent>(); val view = ProgressView(instrumentation.targetContext, events::add)
        view.render(ProgressPreviewStates.normal, true); select(view)
        val old = claim(view)
        view.render(ProgressPreviewStates.normal, false); old.performClick()
        assertTrue(all(view).none { it is Button }); assertFalse(texts(view).contains("영업 결과 확인하기"))
        view.render(ProgressPreviewStates.removed, true)
        assertTrue(texts(view).contains("선택한 목표를 더 이상 확인할 수 없어요"))
        assertTrue(events.none { it is ProgressEvent.ClaimReward })
    }
    fun testMissingUnsupportedEmptyAndRestStatesStayDistinct() = onUi {
        val view = ProgressView(instrumentation.targetContext) { }
        view.render(ProgressPreviewStates.missing, true)
        assertTrue(texts(view).contains("보상 정보 확인 불가"))
        assertTrue(texts(view).contains("휴식 조건 판정 불가"))
        assertTrue(texts(view).contains("버프 적용 여부 확인 불가"))
        val empty = ProgressView(instrumentation.targetContext) { }
        empty.render(ProgressPreviewStates.empty, true)
        assertTrue(texts(empty).contains("표시할 목표가 없어요"))
        val unsupported = ProgressView(instrumentation.targetContext) { }
        unsupported.render(ProgressPreviewStates.unsupported, true)
        assertTrue(all(unsupported).none { it is Button })
        val active = ProgressView(instrumentation.targetContext) { }
        active.render(ProgressPreviewStates.activeRest, true)
        assertTrue(texts(active).contains("저장된 활성 버프"))
        assertTrue(texts(active).contains("잔여 12분 · 고정 시험 표시"))
    }
    fun testLongListsMeasureAtNarrowAndWideWidthsWithoutHorizontalContentOverflow() = onUi {
        val context = instrumentation.targetContext
        for (widthDp in listOf(440, 1360)) {
            val view = ProgressView(context) { }
            view.render(ProgressPreviewStates.many, true)
            val density = context.resources.displayMetrics.density
            val width = (widthDp * density).toInt(); val height = (800 * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            // 분리된 View의 post 대신 폭 판정 후 같은 snapshot을 다시 표시한다.
            view.render(ProgressPreviewStates.many, true)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            assertEquals(width, view.measuredWidth)
            assertEquals(40, all(view).filterIsInstance<Button>().count { it.tag is ProgressKey })
            assertTrue(all(view).filterIsInstance<Button>().all { it.minHeight >= (64 * density).toInt() })
            assertTrue(all(view).filterIsInstance<TextView>().all { it.measuredWidth <= width })
        }
    }
    fun testRemovedRevisionGuardSurvivesViewReplacement() = onUi {
        val events = mutableListOf<ProgressEvent>(); val first = ProgressView(instrumentation.targetContext, events::add)
        val normal = ProgressPreviewStates.normal
        val confirmed = normal.copy(entries = normal.entries.map {
            if (it.key == ProgressPreviewStates.firstKey) it.copy(revision = 5, status = RewardStatus.CLAIMED) else it
        })
        first.render(confirmed, true)
        first.render(normal.copy(sequence = 2, entries = emptyList()), true)
        val replacement = ProgressView(instrumentation.targetContext, events::add)
        replacement.restoreUiState(first.uiState)
        assertFalse(replacement.render(normal.copy(sequence = 3), true))
        assertTrue(events.none { it is ProgressEvent.ClaimReward })
        assertTrue(all(replacement).none { it is Button })
    }

    fun testRenderBeforeRestoreClearsStaleContentAndRetainedButtons() = onUi {
        val events = mutableListOf<ProgressEvent>(); val view = ProgressView(instrumentation.targetContext, events::add)
        val normal = ProgressPreviewStates.normal
        view.render(normal, true); select(view)
        val staleButton = claim(view)
        view.restoreUiState(view.uiState.copy(acceptedRevisions = mapOf(ProgressPreviewStates.firstKey to 5L)))
        assertTrue(texts(view).contains("진행 정보를 확인하고 있어요"))
        assertTrue(all(view).none { it is Button })
        assertFalse(texts(view).contains("영업 결과 확인하기"))
        staleButton.performClick()
        assertFalse(view.render(normal.copy(sequence = 2), true))
        assertTrue(all(view).none { it is Button })
        assertTrue(events.none { it is ProgressEvent.ClaimReward })
        val latest = normal.copy(sequence = 3, entries = normal.entries.map {
            if (it.key == ProgressPreviewStates.firstKey) it.copy(revision = 6) else it
        })
        assertTrue(view.render(latest, true))
        staleButton.performClick()
        assertTrue(events.none { it is ProgressEvent.ClaimReward })
        claim(view).performClick()
        assertEquals(1, events.filterIsInstance<ProgressEvent.ClaimReward>().size)
    }

}
