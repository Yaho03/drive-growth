package io.drivegrowth.core.rental

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.contract.RentalContractFixtures.firstVehicle
import io.drivegrowth.core.garage.Garage
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.*
import org.junit.Test

class RentalFlowTest {
    private val other = firstVehicle.copy(ownedVehicleId = OwnedVehicleId("other"), vehicleId = VehicleId("other"))
    private val garage = Garage(VehicleCatalogSnapshot(ContentVersion("test"),
        listOf(firstVehicle, other).map { VehicleDefinition(it.vehicleId, it.vehicleId.value) }))
    private val request = RentalRequest(RequestId("r1"), firstVehicle.vehicleId, 3)
    private val categories = listOf(other, firstVehicle).associate { it.vehicleId to RentalCategory.SEDAN }

    @Test fun exactThenSameCategoryThenRevisit() {
        fun plan(rest: Set<OwnedVehicleId>, activity: VehicleActivity) = garage.planRental(
            request, listOf(other, firstVehicle), categories, rest,
        ) { ContractResult.Success(if (it == firstVehicle.ownedVehicleId) activity else VehicleActivity.IDLE) }
        fun selected(result: ContractResult<AssignmentPlan>) =
            (result as ContractResult.Success).value as AssignmentPlan.Candidate
        assertEquals(firstVehicle, selected(plan(emptySet(), VehicleActivity.IDLE)).vehicle)
        assertEquals(RentalMatch.SAME_CATEGORY, selected(plan(emptySet(), VehicleActivity.RENTED)).match)
        assertEquals(other, selected(plan(setOf(firstVehicle.ownedVehicleId), VehicleActivity.IDLE)).vehicle)
        assertEquals(ContractResult.Success(AssignmentPlan.Revisit),
            plan(setOf(other.ownedVehicleId), VehicleActivity.NEEDS_REPAIR))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),
            garage.planRental(request, listOf(firstVehicle), categories, emptySet()) {
                ContractResult.Failure(ContractError.STORAGE_FAILURE)
            })
    }

    @Test fun demandRequiresAProfileAndReplaysWithTheSameSeed() {
        val id = RequestId("visit-2")
        val demand = listOf(other.vehicleId, firstVehicle.vehicleId)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            generateRentalRequest(id, emptyList(), kotlin.random.Random(3)))
        val result = generateRentalRequest(id, demand, kotlin.random.Random(3))
        assertEquals(result, generateRentalRequest(id, demand, kotlin.random.Random(3)))
        assertTrue((result as ContractResult.Success).value.desired in demand)
    }

    @Test fun expansionRequiresMatchingLevelCapacityAndFunds() {
        val offer = ShopExpansion(RuleVersion("fixture"), true, 1, 3, 5, 10, GameMoney(20))
        val shop = ShopProgress(3, 10, 1)
        assertEquals(ContractResult.Success(offer), offer.preview(shop, 3, GameMoney(20)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            offer.preview(shop.copy(shopLevel = 2), 5, GameMoney(20)))
        assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS), offer.preview(shop, 3, GameMoney(19)))
    }

    @Test fun accidentAndFacilitiesUseTheSameAssignedVehicle() {
        val candidate = AssignmentPlan.Candidate(request, firstVehicle, RentalMatch.EXACT)
        val customer = SimulatedCustomer(24, 11)
        val allFacilities = rentalFacilities(Facility.entries.toSet(), GameMoney(7))
        fun amount(roll: Int, facilities: RentalFacilities) =
            candidate.quote(GameMoney(100), 0, customer, roll, facilities)
        val insured = amount(12, allFacilities)
        assertTrue(insured.accident && insured.carWashReviewBoost && insured.exactMatchReviewBoost)
        assertEquals(270, insured.fare.intValueExact())
        assertEquals(GameMoney(7), insured.facilityBonus)
        assertEquals(360, amount(13, allFacilities).fare.intValueExact())
        val bare = amount(12, rentalFacilities(emptySet(), GameMoney(7)))
        assertEquals(180, bare.fare.intValueExact())
        assertEquals(GameMoney(0), bare.facilityBonus)
        assertEquals(listOf(8, 4, 4, 3), listOf(11,12,35,36).map { SimulatedCustomer(25,it).accidentPercent() })
    }

    @Test fun parkingSegmentsSurviveRepeatRollbackAndEightHourCap() {
        fun minutes(n: Int) = EpochMillis(n * 60000L)
        fun window(from: Int, to: Int) = parkingIncomeWindow(minutes(10), minutes(10+from), minutes(10+to))
        for (m in listOf(29,30,31,479,480,481)) {
            val whole = window(0,m)
            assertEquals(minutes(minOf(30,m)).value, whole.fullMillis)
            assertEquals(minutes((m-30).coerceIn(0,450)).value, whole.reducedMillis)
            assertEquals(0, whole.equivalentMillis().compareTo(window(0,29).equivalentMillis() +
                window(29,m).equivalentMillis()))
        }
        assertEquals(minutes(41), window(31,29).accountedThrough)
        assertEquals(0, window(31,31).equivalentMillis().signum())
        assertEquals(18000L, window(30,31).equivalentMillis().longValueExact())
    }

    @Test fun facilitiesOnlyAffectRentalsAfterPurchase() {
        val above = ShopProgress(6, 101, 4)
        val cases = listOf(
            Triple(Facility.FUEL_STATION, above.copy(ownedVehicles = 4), above.copy(ownedVehicles = 5)),
            Triple(Facility.INSURANCE, above.copy(servedCustomers = 99), above.copy(servedCustomers = 100)),
            Triple(Facility.CAR_WASH, above.copy(shopLevel = 2), above.copy(shopLevel = 3)),
        )
        for ((facility, before, exact) in cases) {
            val offer = FacilityOffer(facility, RuleVersion("test"), true, GameMoney(10))
            assertFalse(facility.unlocked(before))
            assertTrue(facility.unlocked(exact))
            assertTrue(facility.unlocked(above))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
                offer.planPurchase(before, emptySet(), GameMoney(10)))
            assertEquals(ContractResult.Success(offer), offer.planPurchase(exact, emptySet(), GameMoney(10)))
            assertEquals(ContractResult.Success(offer), offer.planPurchase(above, emptySet(), GameMoney(10)))
            assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS),
                offer.planPurchase(exact, emptySet(), GameMoney(9)))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
                offer.planPurchase(exact, setOf(facility), GameMoney(10)))
        }
    }
}
