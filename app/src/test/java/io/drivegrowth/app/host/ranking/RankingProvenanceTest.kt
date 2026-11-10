package io.drivegrowth.app.host.ranking

import io.drivegrowth.app.data.DriveCommit
import io.drivegrowth.app.host.driving.ObservedDrivingReceipt
import io.drivegrowth.app.host.driving.ObservedTrip
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.DrivingDay
import io.drivegrowth.core.driving.DrivingIncomeRules
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 순수 조회 adapter 시험이다. 아래 SIMULATION receipt는 실제 SQLite/SDK 실행 근거가 아니다. */
class RankingProvenanceTest {
    private val profile = ProfileId("actual-profile")
    private val zone = ZoneId.of("America/New_York")
    private val original = ObservedDrivingReceipt(
        ObservedTrip("drive-a", VehicleObservation(InputSource.SIMULATION, 100, EpochMillis(1000),
            Observation.Missing(MissingReason.NO_DATA), Observation.Missing(MissingReason.NO_DATA), Observation.Missing(MissingReason.NO_DATA)),
            Observation.Available(zone), ContractResult.Success(DrivingIncomeRules(RuleVersion("sim"), 500000, GameMoney(10)))),
        DriveCommit("drive-a", 0, 0, GameMoney(0), DrivingDay(LocalDate.of(1969, 12, 31), 0), null),
    )
    private fun provider(receipt: ContractResult<ObservedDrivingReceipt?> = ContractResult.Success(original),
        identity: ContractResult<ProfileId> = ContractResult.Success(profile)) = RankingProvenance(InputSource.SIMULATION,
        { identity }, { assertEquals(profile, it); ContractResult.Success(setOf(RequestId("opaque-claim"))) },
        { assertEquals("drive-a", it); receipt })
    private fun failure(error: ContractError, action: () -> Unit) {
        assertEquals(error, assertThrows(RankingProvenanceFailure::class.java, action).error)
    }
    @Test fun claimsUseActualProfileAndDoNotInferRequestPrefixes() {
        assertEquals(ContractResult.Success(setOf(RequestId("opaque-claim"))), provider().questCauses())
    }
    @Test fun claimFailureIsNotEmptyIncome() {
        val p = RankingProvenance(InputSource.SIMULATION, { ContractResult.Success(profile) },
            { ContractResult.Failure(ContractError.STORAGE_FAILURE) }, { error("조회하지 않는 주행") })
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), p.questCauses())
    }
    @Test fun profileFailureIsPreservedBeforeEitherRead() {
        val p = provider(identity = ContractResult.Failure(ContractError.PERMISSION_DENIED))
        assertEquals(ContractResult.Failure(ContractError.PERMISSION_DENIED), p.questCauses())
        failure(ContractError.PERMISSION_DENIED) { p.departureZone("drive-a") }
    }
    @Test fun originalDepartureZoneSurvivesInsteadOfCurrentOrRankingZone() {
        assertEquals(Observation.Available(zone), provider().departureZone("drive-a"))
    }
    @Test fun absentReceiptAndUnactivatedLegacyJournalStayMissing() {
        assertEquals(Observation.Missing(MissingReason.NO_DATA), provider(ContractResult.Success(null)).departureZone("drive-a"))
        assertEquals(Observation.Missing(MissingReason.NO_DATA), provider(ContractResult.Failure(ContractError.DATA_UNAVAILABLE)).departureZone("drive-a"))
    }
    @Test fun damagedAndDeniedReceiptReadsRemainTypedFailures() {
        for (error in listOf(ContractError.STORAGE_FAILURE, ContractError.PERMISSION_DENIED, ContractError.INVALID_INPUT))
            failure(error) { provider(ContractResult.Failure(error)).departureZone("drive-a") }
    }
    @Test fun mismatchedTripSourceIdAndCommitIdAreStorageFailures() {
        for (receipt in listOf(original.copy(trip = original.trip.copy(id = "foreign")),
            original.copy(trip = original.trip.copy(departure = original.trip.departure.copy(source = InputSource.VEHICLE))),
            original.copy(commit = original.commit.copy(driveId = "foreign"))))
            failure(ContractError.STORAGE_FAILURE) { provider(ContractResult.Success(receipt)).departureZone("drive-a") }
    }
    @Test fun unsupportedOriginalZoneIsNotReplacedWithAZone() {
        val receipt = original.copy(trip = original.trip.copy(zone = Observation.Missing(MissingReason.UNSUPPORTED)))
        assertEquals(Observation.Missing(MissingReason.UNSUPPORTED), provider(ContractResult.Success(receipt)).departureZone("drive-a"))
    }
}
