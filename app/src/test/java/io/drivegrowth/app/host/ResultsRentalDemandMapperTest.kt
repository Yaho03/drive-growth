package io.drivegrowth.app.host

import io.drivegrowth.app.data.RentalDemandHistory
import io.drivegrowth.app.data.SettlementHistoryEntry
import io.drivegrowth.app.data.SettlementHistorySnapshot
import io.drivegrowth.app.runtime.rental.product.RentalCity
import io.drivegrowth.app.ui.results.RentalDemandPresentation
import io.drivegrowth.app.ui.results.ResultsScreenState
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.CompletedRental
import io.drivegrowth.core.rental.RentalCategory
import io.drivegrowth.core.rental.RentalReceipt
import org.junit.Assert.*
import org.junit.Test

class ResultsRentalDemandMapperTest {
    private val id = RequestId("original-visit")
    private val identity = GameIdentity(ProfileId("original-profile"), InputSource.SIMULATION)
    private val settlement = Settlement(
        SettlementId("original-settlement"),
        Transaction(TransactionId("original-transaction"), RequestId("rental/settle/${id.value}"),
            TransactionKind.RENTAL, identity.source, RuleVersion("original-rule"), MoneyChange(MoneyDirection.CREDIT, GameMoney(80))),
        GameMoney(180), null, EpochMillis(1_000),
    )
    private val rental = CompletedRental(id, OwnedVehicleId("assigned"),
        RentalReceipt(settlement, false, false, false, GameMoney(0), null))
    private val desired = VehicleDefinition(VehicleId("wanted"), "방문 당시 차종명")
    private val assigned = VehicleId("assigned-model")
    private val original = RentalDemandHistory(identity, rental, desired, RentalCategory.SMALL, Observation.Available(RentalCity.SUBURBAN))

    private fun map(demand: Observation<RentalDemandHistory>, actual: CompletedRental? = rental) =
        ResultsHostMapper().history(ContractResult.Success(SettlementHistorySnapshot(
            identity, listOf(SettlementHistoryEntry(settlement, false, actual, assigned, rentalDemand = demand)),
            mapOf(desired.vehicleId to "현재 희망 차종명", assigned to "대여 차종명"),
        )))

    @Test fun originalDesiredNameAndCityAreIndependentOfAssignedCarAndCurrentCatalog() {
        val result = (map(Observation.Available(original)) as ResultsScreenState.Content).results.single()
        assertEquals(Observation.Available(RentalDemandPresentation("방문 당시 차종명", RentalCategory.SMALL,
            Observation.Available(RentalCity.SUBURBAN))), result.rentalDemand)
        assertEquals("대여 차종명", result.rentalVehicleName)
        assertSame(settlement, result.settlement)
    }

    @Test fun absentVisitAndV1UnrecordedCityRemainDistinct() {
        val missing = Observation.Missing(MissingReason.NO_DATA)
        val unknown = (map(missing) as ResultsScreenState.Content).results.single()
        assertEquals(missing, unknown.rentalDemand)
        val legacy = (map(Observation.Available(original.copy(city = missing))) as ResultsScreenState.Content).results.single()
        assertEquals(Observation.Available(RentalDemandPresentation(desired.displayName, RentalCategory.SMALL, missing)), legacy.rentalDemand)
    }

    @Test fun foreignProfileSourceVisitOrAssignmentCannotBecomeNormalDetail() {
        val mismatches = listOf(
            original.copy(identity = identity.copy(profileId = ProfileId("foreign"))),
            original.copy(identity = identity.copy(source = InputSource.VEHICLE)),
            original.copy(rental = rental.copy(rentalId = RequestId("foreign"))),
            original.copy(rental = rental.copy(ownedVehicleId = OwnedVehicleId("foreign"))),
            original.copy(rental = rental.copy(receipt = rental.receipt.copy(
                settlement = settlement.copy(settlementId = SettlementId("foreign"))))),
        )
        mismatches.forEach { assertTrue(map(Observation.Available(it)) is ResultsScreenState.Error) }
        assertTrue(map(Observation.Available(original), actual = null) is ResultsScreenState.Error)
    }
}
