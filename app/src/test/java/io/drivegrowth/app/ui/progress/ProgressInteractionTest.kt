package io.drivegrowth.app.ui.progress

import org.junit.Assert.*
import org.junit.Test

class ProgressInteractionTest {
    private val key = ProgressKey("daily-results", "fixture-day")
    private fun item(revision: Long = 1, token: String = "offer-1", status: RewardStatus = RewardStatus.CLAIMABLE) =
        ProgressItem(key, ProgressGroup.DAILY, revision, "영업 결과 확인", "1 / 1", 100, "시험 보상 300", status,
            claim = ClaimOffer(token, true))
    private fun content(sequence: Long = 1, entries: List<ProgressItem> = listOf(item())) =
        ProgressScreenState.Content(sequence, entries, "고정 시험 입력")
    private fun interaction(events: MutableList<ProgressEvent>) = ProgressInteraction(events::add)

    @Test fun claimRequiresCurrentSelectionConfirmationAndNeverCreatesReceivedState() {
        val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
        ui.render(content(), true)
        assertFalse(ui.claim())
        ui.select(key)
        assertTrue(ui.claim()); assertFalse(ui.claim())
        assertEquals(1, events.filterIsInstance<ProgressEvent.ClaimReward>().size)
        assertEquals(RewardStatus.CLAIMABLE, ui.selectedItem!!.status)
        assertTrue(ui.isPending(ui.selectedItem!!))
    }
    @Test fun rerenderTabsRestrictionAndRemovedSelectionDoNotUnlockAnOffer() {
        val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
        ui.render(content(), true); ui.select(key); ui.claim()
        ui.render(content(2), false); assertFalse(ui.claim())
        ui.render(content(3), true); ui.closeDetail(); ui.select(key)
        assertFalse(ui.claim())
        ui.render(content(4, emptyList()), true)
        assertNull(ui.selectedItem); assertFalse(ui.claim())
        assertEquals(key, ui.uiState.selectedKey)
    }
    @Test fun staleSnapshotsCannotReplaceNewerRevisionEvenWhenRestricted() {
        val ui = interaction(mutableListOf())
        ui.render(content(5, listOf(item(2, "offer-2", RewardStatus.CLAIMED))), true)
        assertFalse(ui.render(content(4), false)); assertFalse(ui.controlsEnabled)
        ui.render(content(5, listOf(item(2, "offer-2", RewardStatus.CLAIMED))), true)
        ui.select(key); assertFalse(ui.claim())
    }
    @Test fun newRevisionNeedsExplicitNewOfferAndOnlyCurrentPendingCanRetry() {
        val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
        ui.render(content(), true); ui.select(key); ui.claim()
        ui.render(content(2, listOf(item(2, "offer-1", RewardStatus.FAILED))), true)
        assertFalse(ui.claim())
        ui.render(content(3, listOf(item(2, "offer-2", RewardStatus.FAILED))), true)
        assertTrue(ui.claim())
        assertEquals("offer-2", events.filterIsInstance<ProgressEvent.ClaimReward>().last().requestKey)
    }
    @Test fun ineligibleStatusesAndMissingRewardOrPermissionCannotClaim() {
        for (status in RewardStatus.entries.filter { it !in listOf(RewardStatus.CLAIMABLE, RewardStatus.FAILED) }) {
            val ui = interaction(mutableListOf()); ui.render(content(entries = listOf(item(status = status))), true)
            ui.select(key); assertFalse(status.name, ui.claim())
        }
        for (entry in listOf(item().copy(rewardLabel = null), item().copy(claim = null),
            item().copy(claim = ClaimOffer("disabled", false, "명령 미연결")))) {
            val ui = interaction(mutableListOf()); ui.render(content(entries = listOf(entry)), true); ui.select(key)
            assertFalse(ui.claim())
        }
    }
    @Test fun restoreKeepsPendingAcrossViewReplacementButNeverReplaysRequest() {
        val events = mutableListOf<ProgressEvent>(); val first = interaction(events)
        first.render(content(), true); first.select(key); first.claim()
        val replacement = interaction(events); replacement.restoreUiState(first.uiState)
        replacement.render(content(), true); assertFalse(replacement.claim())
        assertEquals(1, events.filterIsInstance<ProgressEvent.ClaimReward>().size)
    }
    @Test fun snapshotOwnsItsListAndDuplicateKeysAreRejected() {
        val entries = mutableListOf(item()); val ui = interaction(mutableListOf())
        ui.render(content(entries = entries), true); entries.clear(); ui.select(key)
        assertNotNull(ui.selectedItem)
        assertThrows(IllegalArgumentException::class.java) { content(entries = listOf(item(), item())) }
    }
    @Test fun externalPendingBlocksAnotherClaimWithoutChangingEitherReward() {
        val ui = interaction(mutableListOf())
        val pending = item(status = RewardStatus.PENDING).copy(key = ProgressKey("other", "fixture-day"), claim = null)
        ui.render(content(entries = listOf(item(), pending)), true); ui.select(key)
        assertFalse(ui.claim())
    }
    @Test fun retainedOldConfirmationCannotClaimAnotherSelectionOrRevision() {
        val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
        ui.render(content(), true); ui.select(key)
        val old = ClaimReference(key, 1, "offer-1")
        ui.render(content(2, listOf(item(2, "offer-2"))), true)
        assertFalse(ui.claim(old)); assertTrue(events.none { it is ProgressEvent.ClaimReward })
        assertTrue(ui.claim(ClaimReference(key, 2, "offer-2")))
    }
    @Test fun retryIsSingleUntilExplicitCompletionAndUnsupportedHasNoAction() {
        val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
        ui.render(ProgressScreenState.Failure("읽기 실패", true), true)
        assertTrue(ui.retry()); ui.render(ProgressScreenState.Failure("문구 갱신", true), true)
        assertFalse(ui.retry()); ui.completeRetry(); assertTrue(ui.retry())
        ui.render(ProgressScreenState.Unsupported("미지원"), true); ui.completeRetry(); assertFalse(ui.retry())
    }
    @Test fun removedKeyRetainsHighestAcceptedRevisionAndCannotClaimOldState() {
        val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
        ui.render(content(1, listOf(item(5, "received-5", RewardStatus.CLAIMED))), true)
        ui.render(content(2, emptyList()), true)
        assertFalse(ui.render(content(3, listOf(item(1, "unused-old-offer"))), true))
        ui.select(key)
        assertNull(ui.selectedItem); assertFalse(ui.claim())
        assertTrue(events.none { it is ProgressEvent.ClaimReward })
        assertTrue(ui.render(content(4, listOf(item(6, "new-offer"))), true))
        ui.select(key); assertTrue(ui.claim())
    }
    @Test fun restoredViewRetainsRemovedRevisionsButAllowsANewPeriod() {
        val events = mutableListOf<ProgressEvent>(); val first = interaction(events)
        first.render(content(1, listOf(item(5, "received-5", RewardStatus.CLAIMED))), true)
        first.render(content(2, emptyList()), true)
        val restored = interaction(events); restored.restoreUiState(first.uiState)
        assertFalse(restored.render(content(3, listOf(item(1, "old-offer"))), true))
        restored.select(key); assertFalse(restored.claim())
        val nextPeriod = key.copy(periodId = "next-fixture-day")
        assertTrue(restored.render(content(4, listOf(item().copy(key = nextPeriod))), true))
        restored.select(nextPeriod); assertTrue(restored.claim())
        assertEquals(nextPeriod, events.filterIsInstance<ProgressEvent.ClaimReward>().single().key)
    }

    @Test fun revisionHistoryIsDefensiveAndRestoringOlderUiStateCannotEraseIt() {
        val source = mutableMapOf(key to 5L); val ui = interaction(mutableListOf())
        ui.restoreUiState(ProgressUiState(acceptedRevisions = source)); source.clear()
        assertThrows(UnsupportedOperationException::class.java) { (ui.uiState.acceptedRevisions as MutableMap).clear() }
        ui.restoreUiState(ProgressUiState(acceptedRevisions = mapOf(key to 1L)))
        assertFalse(ui.render(content(3, listOf(item(1))), true))
        assertEquals(5L, ui.uiState.acceptedRevisions[key])
    }

    @Test fun rejectedSnapshotStillAppliesTheCurrentGateSeparately() {
        val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
        ui.render(content(5), true); ui.select(key)
        ui.render(content(5), false)
        assertFalse(ui.render(content(4), true))
        assertTrue(ui.controlsEnabled); assertTrue(ui.claim())
        assertEquals(1, events.filterIsInstance<ProgressEvent.ClaimReward>().size)
    }

}
