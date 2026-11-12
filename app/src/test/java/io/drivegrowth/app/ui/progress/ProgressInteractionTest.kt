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
    @Test fun overviewEligibilityMatchesDetailForDisabledOffersAndLocalPending() {
        val events = mutableListOf<ProgressEvent>()
        val ui = interaction(events)
        val ready = item()
        val disabled = item(token = "disabled").copy(key = ProgressKey("disabled", "fixture-day"),
            claim = ClaimOffer("disabled", false))
        val other = item(token = "other").copy(key = ProgressKey("other", "fixture-day"))
        ui.render(content(entries = listOf(ready, disabled, other)), true)

        assertTrue(ui.canClaim(ready))
        assertFalse(ui.canClaim(disabled))
        ui.select(disabled.key)
        assertFalse(ui.canClaim())
        assertFalse(ui.claim())
        ui.select(ready.key)
        assertTrue(ui.canClaim())
        assertTrue(ui.claim())
        ui.closeDetail()

        assertTrue(ui.isPending(ready))
        assertFalse(ui.canClaim(ready))
        assertFalse(ui.canClaim(other))
        ui.select(ready.key)
        assertFalse(ui.canClaim())
        assertFalse(ui.claim())
        assertEquals(1, events.filterIsInstance<ProgressEvent.ClaimReward>().size)
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

    @Test fun restorationInvalidatesOlderVisibleContentInEitherCallOrder() {
        for (renderFirst in listOf(true, false)) {
            val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
            val saved = ProgressUiState(selectedKey = key, acceptedRevisions = mapOf(key to 5L))
            if (renderFirst) ui.render(content(), true)
            ui.restoreUiState(saved)
            if (!renderFirst) assertFalse(ui.render(content(), true))
            assertFalse(ui.claim())
            assertEquals(ProgressScreenState.Loading, ui.state)
            assertNull(ui.selectedItem)
            assertFalse(ui.render(content(2), true))
            assertEquals(ProgressScreenState.Loading, ui.state)
            assertTrue(events.none { it is ProgressEvent.ClaimReward })
            assertTrue(ui.render(content(3, listOf(item(6, "valid-new-offer"))), true))
            ui.select(key); assertTrue(ui.claim())
            assertEquals(1, events.filterIsInstance<ProgressEvent.ClaimReward>().size)
        }
    }
    @Test fun invalidatingVisibleContentPreservesPendingAndWaitsForAnExplicitRecoverySnapshot() {
        val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
        ui.render(content(), true)
        val pending = ClaimReference(key, 1, "pending-request")
        ui.restoreUiState(ProgressUiState(selectedKey = key, requestedKeys = setOf(pending.requestKey),
            pending = pending, retryPending = true, acceptedRevisions = mapOf(key to 5L)))
        assertEquals(ProgressScreenState.Loading, ui.state)
        assertEquals(pending, ui.uiState.pending)
        assertEquals(setOf(pending.requestKey), ui.uiState.requestedKeys)
        assertTrue(ui.uiState.retryPending)
        assertEquals(5L, ui.uiState.acceptedRevisions[key])
        assertFalse(ui.claim()); assertTrue(events.isEmpty())
        assertTrue(ui.render(content(3, listOf(item(6, "recovery-request", RewardStatus.FAILED))), true))
        assertNull(ui.uiState.pending)
        assertEquals(RewardStatus.FAILED, ui.selectedItem!!.status)
        assertTrue(ui.claim())
    }

    @Test fun restoringOldSelectionCannotForgetSubmittedOfferOrEnableAnotherClaim() {
        val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
        val other = item(token = "other-offer").copy(key = key.copy(id = "other"))
        ui.render(content(entries = listOf(item(), other)), true); ui.select(key)
        val beforeClaim = ui.uiState
        assertTrue(ui.claim()); val pending = ui.uiState.pending
        ui.restoreUiState(beforeClaim)
        assertFalse(ui.claim())
        ui.select(other.key); assertFalse(ui.claim())
        assertEquals(pending, ui.uiState.pending)
        assertEquals(setOf("offer-1"), ui.uiState.requestedKeys)
        assertEquals(1, events.filterIsInstance<ProgressEvent.ClaimReward>().size)
    }
    @Test fun restoringOldRetryFlagCannotUnlockAnOutstandingReadRequest() {
        val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
        ui.render(ProgressScreenState.Failure("읽기 실패", true), true)
        val beforeRetry = ui.uiState
        assertTrue(ui.retry()); ui.restoreUiState(beforeRetry)
        assertFalse(ui.retry())
        assertEquals(1, events.filterIsInstance<ProgressEvent.Retry>().size)
        ui.completeRetry(); assertTrue(ui.retry())
        assertEquals(2, events.filterIsInstance<ProgressEvent.Retry>().size)
    }

    @Test fun conflictingPendingNeedsExplicitPerRequestRecoveryAndCannotBeClearedByContent() {
        val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
        val other = key.copy(id = "other")
        val a = ClaimReference(key, 1, "pending-a"); val b = ClaimReference(other, 1, "pending-b")
        ui.restoreUiState(ProgressUiState(pending = a))
        val input = mutableSetOf(b)
        ui.restoreUiState(ProgressUiState(pending = b, pendingConflicts = input)); input.clear()
        assertEquals(setOf(a, b), ui.uiState.pendingConflicts)
        assertEquals(setOf(a.requestKey, b.requestKey), ui.uiState.requestedKeys)
        assertThrows(UnsupportedOperationException::class.java) { (ui.uiState.pendingConflicts as MutableSet).clear() }
        val fresh = content(2, listOf(item(2, "recovered-offer", RewardStatus.FAILED),
            item(2, "other-terminal", RewardStatus.CLAIMED).copy(key = other, claim = null)))
        ui.render(fresh, true)
        assertEquals(ProgressScreenState.Loading, ui.state); assertFalse(ui.claim())
        ui.completePendingRecovery(setOf(a))
        assertEquals(b, ui.uiState.pending); assertEquals(setOf(b), ui.uiState.pendingConflicts)
        ui.render(fresh, true); assertEquals(ProgressScreenState.Loading, ui.state)
        ui.completePendingRecovery(setOf(b))
        assertNull(ui.uiState.pending); assertTrue(ui.uiState.pendingConflicts.isEmpty())
        assertEquals(ProgressScreenState.Loading, ui.state)
        assertTrue(events.isEmpty())
        ui.render(fresh, true); ui.select(key); assertTrue(ui.claim())
        assertEquals(1, events.filterIsInstance<ProgressEvent.ClaimReward>().size)
    }
    @Test fun oldSelectionRestoreAfterExplicitFailureKeepsHistoryAndAllowsOnlyTheNewOffer() {
        val events = mutableListOf<ProgressEvent>(); val ui = interaction(events)
        ui.render(content(), true); ui.select(key); val old = ui.uiState
        ui.claim()
        ui.render(content(2, listOf(item(2, "offer-1", RewardStatus.FAILED))), true)
        ui.restoreUiState(old); assertFalse(ui.claim())
        assertEquals(setOf("offer-1"), ui.uiState.requestedKeys)
        ui.render(content(3, listOf(item(3, "offer-2", RewardStatus.FAILED))), true)
        ui.restoreUiState(old); assertTrue(ui.claim())
        assertEquals(listOf("offer-1", "offer-2"), events.filterIsInstance<ProgressEvent.ClaimReward>().map { it.requestKey })
    }

}
