package io.drivegrowth.app.ui.onboarding

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class OnboardingInteractionTest {
    private val casper = OnboardingCandidate(VehicleDefinition(VehicleId("hyundai_casper"), "캐스퍼"), true)
    private val avante = OnboardingCandidate(VehicleDefinition(VehicleId("hyundai_avante"), "아반떼"), true)
    private val choices = OnboardingScreenState.Choosing(listOf(casper, avante), "고정 시험 입력", emptyList())
    private fun confirmation(candidate: OnboardingCandidate = casper) = OnboardingScreenState.Confirming(candidate, "고정 시험 입력", emptyList(), true)
    private fun completed(candidate: OnboardingCandidate = casper, already: Boolean = false) = OnboardingScreenState.Completed(
        OwnedVehicle(OwnedVehicleId("fixed-owned"), candidate.vehicle.vehicleId, 1, Revision(0), VehicleLifecycle.OWNED,
            Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT), candidate.vehicle.displayName,
        emptyList(), already, "고정 확정 결과 · 실제 지급 아님",
    )

    @Test fun explicitSelectionAndReviewDoNotConfirmOrGrant() {
        val events = mutableListOf<OnboardingEvent>()
        val input = OnboardingInteraction(events::add)
        input.restoreSelection(avante.vehicle.vehicleId)
        input.render(choices, true)
        assertEquals(avante, input.selectedCandidate)
        assertTrue(input.review())
        assertEquals(listOf(OnboardingEvent.ReviewSelection(avante.vehicle.vehicleId)), events)
        assertEquals(choices, input.state)
        assertFalse(input.confirm(avante.vehicle.vehicleId))
    }

    @Test fun confirmationLatchesAcrossRendersAndBlocksBackAndOtherSelection() {
        val events = mutableListOf<OnboardingEvent>()
        val input = OnboardingInteraction(events::add)
        input.render(confirmation(), true)
        assertTrue(input.confirm(casper.vehicle.vehicleId))
        input.render(confirmation(), true)
        assertFalse(input.confirm(casper.vehicle.vehicleId))
        assertFalse(input.select(avante.vehicle.vehicleId))
        assertTrue(input.handleBack())
        assertEquals(listOf(OnboardingEvent.ConfirmSelection(casper.vehicle.vehicleId)), events)
        assertEquals(casper.vehicle.vehicleId, input.pendingSelection)
        assertTrue(input.state is OnboardingScreenState.Confirming)
    }

    @Test fun lateDifferentSelectionResponsesAreRejectedAndGateStillApplies() {
        val input = OnboardingInteraction {}
        input.render(confirmation(), true)
        input.confirm(casper.vehicle.vehicleId)
        assertFalse(input.render(completed(avante), true))
        assertFalse(input.render(OnboardingScreenState.GrantError(avante.vehicle.vehicleId, "아반떼", "실패", true, true, "시험"), false))
        assertFalse(input.controlsEnabled)
        assertEquals(casper.vehicle.vehicleId, input.pendingSelection)
        assertTrue(input.render(completed(), true))
        assertFalse(input.render(choices, true))
        assertFalse(input.confirm(casper.vehicle.vehicleId))
    }

    @Test fun matchingFailureAllowsSameRequestRecoveryAndThenConfirmedNavigation() {
        val events = mutableListOf<OnboardingEvent>()
        val input = OnboardingInteraction(events::add)
        input.render(confirmation(), true)
        input.confirm(casper.vehicle.vehicleId)
        input.render(OnboardingScreenState.GrantError(casper.vehicle.vehicleId, "캐스퍼", "확정 확인 필요", true, false, "시험"), true)
        assertTrue(input.retry())
        assertFalse(input.retry())
        assertEquals(OnboardingEvent.Retry(casper.vehicle.vehicleId), events.last())
        assertTrue(input.render(completed(), true))
        assertTrue(input.goToShop())
        assertFalse(input.goToShop())
        assertEquals(OnboardingEvent.GoToShop, events.last())
    }

    @Test fun alreadyGrantedProfileCannotReopenSelectionOrRequestAnotherGrant() {
        val events = mutableListOf<OnboardingEvent>()
        val input = OnboardingInteraction(events::add)
        input.render(completed(already = true), true)
        input.restoreSelection(avante.vehicle.vehicleId)
        assertFalse(input.render(choices, true))
        assertFalse(input.select(avante.vehicle.vehicleId))
        assertFalse(input.confirm(casper.vehicle.vehicleId))
        assertFalse(input.handleBack())
        assertTrue(events.isEmpty())
    }

    @Test fun missingDisabledRestrictedAndRemovedCandidatesCannotReview() {
        val input = OnboardingInteraction {}
        input.render(choices, true)
        assertFalse(input.review())
        input.restoreSelection(VehicleId("removed"))
        assertNull(input.selectedCandidate)
        assertFalse(input.review())
        input.render(choices.copy(candidates = listOf(casper.copy(selectable = false))), true)
        assertFalse(input.select(casper.vehicle.vehicleId))
        input.render(choices, false)
        assertFalse(input.select(avante.vehicle.vehicleId))
        assertFalse(input.handleBack())
        input.render(OnboardingScreenState.Loading, true)
        assertFalse(input.retry())
        input.render(OnboardingScreenState.LoadError("읽기 실패", false), true)
        assertFalse(input.retry())
    }

    @Test fun recreatedSubmittingStateKeepsTheExactPendingSelection() {
        val input = OnboardingInteraction {}
        input.render(OnboardingScreenState.Submitting(avante.vehicle.vehicleId, "아반떼", "시험"), true)
        input.restoreSelection(casper.vehicle.vehicleId)
        assertEquals(avante.vehicle.vehicleId, input.selectedId)
        assertTrue(input.handleBack())
        assertFalse(input.confirm(avante.vehicle.vehicleId))
        assertTrue(input.render(completed(avante), true))
    }

    @Test fun synchronousHostResponseDoesNotLeaveAStuckLatch() {
        lateinit var input: OnboardingInteraction
        input = OnboardingInteraction { if (it is OnboardingEvent.ConfirmSelection) input.render(completed(), true) }
        input.render(confirmation(), true)
        assertTrue(input.confirm(casper.vehicle.vehicleId))
        assertNull(input.pendingSelection)
        assertTrue(input.goToShop())
    }
    @Test fun backIsConsumedDuringNavigationAndLateReviewCannotReplaceNewChoice() {
        val events = mutableListOf<OnboardingEvent>()
        val input = OnboardingInteraction(events::add)
        input.render(choices, true)
        input.select(avante.vehicle.vehicleId)
        assertFalse(input.render(confirmation(casper), true))
        assertTrue(input.handleBack())
        assertTrue(input.handleBack())
        assertEquals(1, events.count { it == OnboardingEvent.Previous })
    }

    @Test fun lateOutcomeCannotReplaceConfirmedInstanceAndEmptyCandidatesCanReload() {
        val input = OnboardingInteraction {}
        input.render(choices.copy(candidates = emptyList()), true)
        assertTrue(input.retry())
        assertFalse(input.retry())
        input.render(completed(), true)
        val other = completed().copy(vehicle = completed().vehicle.copy(ownedVehicleId = OwnedVehicleId("late-other")))
        assertFalse(input.render(other, true))
        assertEquals(completed(), input.state)
    }

    @Test fun unsupportedProfileStatusCannotStartSelectConfirmOrRetry() {
        val events = mutableListOf<OnboardingEvent>()
        val input = OnboardingInteraction(events::add)
        input.render(OnboardingScreenState.Unavailable("첫 지급 여부 조회 미지원"), true)
        assertFalse(input.start())
        assertFalse(input.select(casper.vehicle.vehicleId))
        assertFalse(input.confirm(casper.vehicle.vehicleId))
        assertFalse(input.retry())
        assertTrue(events.isEmpty())
    }

    @Test fun identicalErrorRenderDoesNotAcknowledgeRecoveryButSubmittingThenErrorDoes() {
        val events = mutableListOf<OnboardingEvent>()
        val input = OnboardingInteraction(events::add)
        val error = OnboardingScreenState.GrantError(casper.vehicle.vehicleId, "캐스퍼", "확인 필요", true, false, "시험")
        input.render(error, true)
        assertTrue(input.retry())
        input.render(error.copy(), false)
        input.render(error.copy(), true)
        assertFalse(input.retry())
        input.render(error.copy(sourceLabel = "출처 표시 갱신", message = "설명만 변경"), true)
        assertFalse(input.retry())
        assertEquals(1, events.size)
        input.render(OnboardingScreenState.Submitting(casper.vehicle.vehicleId, "캐스퍼", "시험"), true)
        input.render(error.copy(), true)
        assertTrue(input.retry())
        assertEquals(2, events.size)
    }

    @Test fun completedRendersDoNotReleaseTheShopNavigationLatch() {
        val events = mutableListOf<OnboardingEvent>()
        val input = OnboardingInteraction(events::add)
        input.render(completed(), true)
        assertTrue(input.goToShop())
        input.render(completed(), false)
        input.render(completed().copy(alreadyGranted = true), true)
        assertFalse(input.goToShop())
        assertEquals(listOf(OnboardingEvent.GoToShop), events)
    }

}
