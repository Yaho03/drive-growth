package io.drivegrowth.app.ui.progress

import android.content.res.Configuration
import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.data.RestActiveGrant
import io.drivegrowth.app.data.RestDrivingProjection
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.progression.ProgressionJournalState
import io.drivegrowth.app.host.progression.ProgressionMapper
import io.drivegrowth.app.host.progression.ProgressionRead
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.ProfileId
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.driving.RestBonusState
import io.drivegrowth.core.driving.RestRules

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
    fun testConfirmedRestIsReadableNearTheTopOfTheShallowOverview() = onUi {
        val base = instrumentation.targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = 1f })
        val view = ProgressView(context) { fail("Reading a rest card must not send an action") }
        val activeLabel = context.getString(R.string.progress_buff_active)
        val remaining = "원장 잔여 1100초"
        val source = "모의 입력 · 저장된 확정 상태"
        val content = ProgressPreviewStates.normal.copy(
            sourceLabel = "모의 입력 프로필 · 원장 기록",
            rest = RestPresentation(null, BuffPhase.ACTIVE, remainingLabel = remaining, note = source),
        )
        view.render(content, true)

        val density = context.resources.displayMetrics.density
        val width = (936 * density).toInt()
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightSpec)
        val title = view.getChildAt(0)
        val viewport = (131 * density).toInt() // 98 px at the observed 120 dpi.
        val height = title.measuredHeight + view.paddingTop + view.paddingBottom + viewport
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)

        val scroll = all(view).filterIsInstance<ScrollView>().single()
        assertEquals(viewport, scroll.height)
        for (label in listOf(activeLabel, remaining, source)) {
            val text = all(scroll).filterIsInstance<TextView>().single { it.text.toString() == label }
            val bounds = Rect(0, 0, text.width, text.height)
            scroll.offsetDescendantRectToMyCoords(text, bounds)
            scroll.scrollTo(0, (bounds.bottom - viewport).coerceAtLeast(0))
            assertTrue("Expected $label within two short viewport scrolls", scroll.scrollY <= viewport * 2)
            assertTrue("Expected the entire $label inside the viewport",
                bounds.top >= scroll.scrollY && bounds.bottom <= scroll.scrollY + viewport)
        }
        assertTrue(all(view).filterIsInstance<Button>().any { it.tag == ProgressPreviewStates.firstKey })

        view.render(content, false)
        assertFalse(texts(view).any { it == activeLabel || it == remaining || it == source })
        assertTrue(all(view).none { it is Button })
    }

    fun testActiveNeverGrantedAndConsumedRestKeepTheirStoredMeaning() = onUi {
        val context = instrumentation.targetContext
        val view = ProgressView(context) { fail("A stored rest state must not send an action") }
        val identity = GameIdentity(ProfileId("rest-placement"), InputSource.SIMULATION)
        val rules = RestRules(RuleVersion("rest-placement-test"), 1, 1, 1_200_000, 150)
        val mapper = ProgressionMapper()
        val active = RestBonusState(1_100_000, 1)
        val neverGranted = RestBonusState.NONE
        val consumed = RestBonusState(0, 1)
        val activeLabel = context.getString(R.string.progress_buff_active)
        val inactiveLabel = context.getString(R.string.progress_buff_inactive)
        val source = "모의 입력 · 저장된 확정 상태"
        for (stored in listOf(active, neverGranted, consumed)) {
            val grant = if (stored == active) RestActiveGrant(1, rules) else null
            val content = mapper.content(ProgressionRead(identity, emptyList(), emptyMap(),
                ProgressionJournalState.Clear, true, rest = ContractResult.Success(
                    RestDrivingProjection(2, stored, stored, 2, 0, grant))))
            val mapped = requireNotNull(content.rest)
            val expectedPhase = if (stored == active) BuffPhase.ACTIVE else BuffPhase.INACTIVE
            val expectedRemaining = if (stored == active) "원장 잔여 1100초" else "원장 잔여 0초"
            assertEquals("stored=$stored", expectedPhase, mapped.buffPhase)
            assertEquals(expectedRemaining, mapped.remainingLabel)
            assertEquals(source, mapped.note)
            assertNull(mapped.recognition)

            assertTrue(view.render(content, true))
            val labels = texts(view)
            assertEquals(1, labels.count { it == expectedRemaining })
            assertEquals(1, labels.count { it == source })
            assertEquals(stored == active, activeLabel in labels)
            assertEquals(stored != active, inactiveLabel in labels)
            if (stored != active) {
                val achievements = context.getString(R.string.progress_achievements)
                assertTrue("Inactive or consumed rest must stay after achievements",
                    labels.indexOf(achievements) in 0 until labels.indexOf(expectedRemaining))
            }
        }
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

    fun testOldSelectionRestoreKeepsRequestLocksAndConflictsWaitForRecovery() = onUi {
        val events = mutableListOf<ProgressEvent>(); val view = ProgressView(instrumentation.targetContext, events::add)
        view.render(ProgressPreviewStates.normal, true); select(view)
        val old = view.uiState; val submit = claim(view)
        submit.performClick(); val pending = view.uiState.pending
        view.restoreUiState(old); submit.performClick(); claim(view).performClick()
        assertEquals(pending, view.uiState.pending)
        assertEquals(1, events.filterIsInstance<ProgressEvent.ClaimReward>().size)
        assertFalse(claim(view).isEnabled)
        val conflict = ClaimReference(ProgressKey("other", "fixture-day"), 1, "other-request")
        view.restoreUiState(old.copy(pending = conflict))
        assertTrue(texts(view).any { it.contains("청구 요청 이력이 달라") })
        assertTrue(all(view).filterIsInstance<Button>().none { it.tag == "progress-claim" })
        val retry = all(view).filterIsInstance<Button>().single { it.text.toString() == "다시 확인" }
        retry.performClick(); view.restoreUiState(old); retry.performClick()
        assertEquals(1, events.filterIsInstance<ProgressEvent.Retry>().size)
        assertEquals(1, events.filterIsInstance<ProgressEvent.ClaimReward>().size)
        view.completePendingRecovery(setOf(pending!!, conflict))
        assertTrue(all(view).filterIsInstance<Button>().none { it.tag == "progress-claim" })
        view.render(ProgressPreviewStates.received, true)
        assertTrue(texts(view).contains("수령 확인됨"))
    }

}
