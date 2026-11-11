package io.drivegrowth.app.host.progression

import io.drivegrowth.app.data.RestActiveGrant
import io.drivegrowth.app.data.RestDrivingProjection
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.progress.BuffPhase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.RestBonusState
import io.drivegrowth.core.driving.RestRules
import org.junit.Assert.*
import org.junit.Test

class RestProgressionMapperTest {
    private val rules = RestRules(RuleVersion("rest-test"), 1, 1, 20_000, 150)

    private fun display(result: ContractResult<RestDrivingProjection>?, source: InputSource = InputSource.VEHICLE) =
        ProgressionMapper().content(ProgressionRead(GameIdentity(ProfileId("rest-profile"), source),
            emptyList(), emptyMap(), ProgressionJournalState.Clear, true, rest = result)).rest!!

    @Test fun missingAndStorageFailureRemainDistinct() {
        assertEquals(BuffPhase.UNSUPPORTED, display(null).buffPhase)
        val missing = display(ContractResult.Failure(ContractError.DATA_UNAVAILABLE))
        val damaged = display(ContractResult.Failure(ContractError.STORAGE_FAILURE))
        assertEquals(BuffPhase.UNCONFIRMED, missing.buffPhase)
        assertEquals(BuffPhase.UNCONFIRMED, damaged.buffPhase)
        assertNotEquals(missing.note, damaged.note)
        for (value in listOf(missing, damaged)) {
            assertNull(value.recognition)
            assertNull(value.remainingLabel)
        }
    }

    @Test fun pendingProjectionDoesNotExposeProvisionalOrStaleRemaining() {
        val confirmed = RestBonusState(10_000, 1)
        val pending = RestBonusState(20_000, 2)
        val value = display(ContractResult.Success(RestDrivingProjection(2, confirmed, pending, 1, 1, RestActiveGrant(2, rules))))
        assertEquals(BuffPhase.UNCONFIRMED, value.buffPhase)
        assertNull(value.remainingLabel)
        assertNull(value.recognition)
    }

    @Test fun confirmedRemainingUsesStoredValueAndMarksSimulation() {
        val stored = RestBonusState(12_345, 1)
        val value = display(ContractResult.Success(RestDrivingProjection(1, stored, stored, 1, 0, RestActiveGrant(1, rules))), InputSource.SIMULATION)
        assertEquals(BuffPhase.ACTIVE, value.buffPhase)
        assertEquals("원장 잔여 12.345초", value.remainingLabel)
        assertTrue(value.note!!.contains("모의 입력"))
        assertNull(value.recognition)
        assertNull(value.effectLabel)
    }

    @Test fun emptyModuleDoesNotPretendToHaveObservedAStop() {
        val none = RestBonusState.NONE
        val empty = display(ContractResult.Success(RestDrivingProjection(0, none, none, 0, 0, null)))
        val settled = display(ContractResult.Success(RestDrivingProjection(1, none, none, 1, 0, null)))
        assertEquals(BuffPhase.UNCONFIRMED, empty.buffPhase)
        assertEquals(BuffPhase.INACTIVE, settled.buffPhase)
        assertNull(settled.recognition)
    }
}
