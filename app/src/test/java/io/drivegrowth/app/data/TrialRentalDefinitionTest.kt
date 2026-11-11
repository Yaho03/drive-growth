package io.drivegrowth.app.data

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.content.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.Garage
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class TrialRentalDefinitionTest {
    private fun asset(name:String)=sequenceOf(File("src/main/assets/content/$name"),File("app/src/main/assets/content/$name")).first{it.isFile}.readText()
    private fun content()=(GameContentLoader.load(asset("vehicle-facts.csv"),asset("game-content.csv")) as ContentLoad.Loaded).snapshot
    private fun catalog()=content().catalog
    private fun definition()=(AssetTrialDefinitionProvider(
        { asset("trial-definitions.csv") },
        { ContractResult.Success(catalog().vehicles.map { it.vehicleId }.toSet()) },
    ).load() as ContractResult.Success).value
    @Test fun actualCentralInputsCoverTwentyAndExistingScheduleDurationReturnRules() {
        val trial=definition();val loaded=trial.rentalVisitDefinition();assertTrue(loaded.toString(),loaded is ContractResult.Success);val d=(loaded as ContractResult.Success).value
        assertEquals(trial.version,d.version);assertEquals(trial.rules,d.rules);assertTrue(d.trial);assertEquals(1800000L,d.intervalMillis)
        assertEquals(catalog().vehicles.map{it.vehicleId}.toSet(),d.categories.keys);assertEquals(d.categories.keys,d.demand.keys);assertEquals(trial.rentalRates,d.rates)
        assertEquals(setOf(1,3,6),d.durations.keys);assertEquals(mapOf(1 to 15,3 to 30,6 to 60),d.returns);assertTrue(d.customers.all{it.weight>0});assertEquals(ContractResult.Success(16),trial.rentalReplayLimit())
    }
    @Test fun bundledGameCategoriesMatchPrdAndCoverAllFiveAtShopLevelOne() {
        val trial = definition()
        val categories = (trial.rentalVisitDefinition() as ContractResult.Success).value.categories
        val eco = setOf("hyundai_casper_electric", "hyundai_ioniq5", "hyundai_ioniq6", "hyundai_nexo", "hyundai_ioniq9")
            .map(::VehicleId).toSet()
        assertEquals(eco, categories.filterValues { it == RentalCategory.ECO }.keys)
        assertEquals(mapOf(RentalCategory.SMALL to 3, RentalCategory.SEDAN to 5, RentalCategory.SUV to 5,
            RentalCategory.VAN to 2, RentalCategory.ECO to 5), categories.values.groupingBy { it }.eachCount())
        assertEquals(RentalCategory.SMALL, categories[VehicleId("hyundai_casper")])
        assertEquals(RentalCategory.SEDAN, categories[VehicleId("hyundai_avante")])
        assertEquals(RentalCategory.VAN, categories[VehicleId("hyundai_staria_tourer")])
        assertEquals(RentalCategory.SUV, categories[VehicleId("hyundai_palisade")])
        val unlocked = content().unlockedAt(1)
        assertEquals(8, unlocked.size)
        assertEquals(RentalCategory.entries.toSet(), unlocked.map { categories.getValue(it.vehicleId) }.toSet())
        assertEquals(setOf(VehicleId("hyundai_casper_electric")),
            unlocked.map { it.vehicleId }.filter { it in eco }.toSet())
        assertEquals(ContentVersion("trial-demo-2026-11-10-v5"), trial.version)
        assertEquals(RuleVersion("trial-demo-2026-11-10-v4"), trial.rules)
    }

    @Test fun ecoVisitCanUseADifferentEcoModel() {
        val ioniq6 = owned("hyundai_ioniq6")
        assertEquals(AssignmentPlan.Candidate(request("hyundai_ioniq5"), ioniq6, RentalMatch.SAME_CATEGORY),
            plan("hyundai_ioniq5", listOf(ioniq6)))
    }

    @Test fun ecoVisitRevisitsWithOnlyACombustionSedan() {
        assertEquals(AssignmentPlan.Revisit, plan("hyundai_ioniq6", listOf(owned("hyundai_avante"))))
    }

    @Test fun ecoVisitPrefersExactModelAndExcludesRestingAndOccupiedVehicles() {
        val exact = owned("hyundai_ioniq5")
        val alternate = owned("hyundai_ioniq6", level = 2)
        val another = owned("hyundai_nexo")
        val vehicles = listOf(alternate, another, exact)
        assertEquals(AssignmentPlan.Candidate(request("hyundai_ioniq5"), exact, RentalMatch.EXACT),
            plan("hyundai_ioniq5", vehicles))
        assertEquals(AssignmentPlan.Candidate(request("hyundai_ioniq5"), another, RentalMatch.SAME_CATEGORY),
            plan("hyundai_ioniq5", vehicles, resting = setOf(exact.ownedVehicleId), rented = setOf(alternate.ownedVehicleId)))
    }
    @Test fun realPlannerConsumesDefinitionAndReturnsWholeFrozenVisitWithoutStorage() {
        val loaded=definition().rentalVisitDefinition();assertTrue(loaded is ContractResult.Success);val d=(loaded as ContractResult.Success).value
        val at=RentalClock("explicit-boot",EpochMillis(1000),100);val cursor=RentalVisitCursor(Revision(0),0,at)
        val planned=RentalVisitPlanner().prepare(GameIdentity(ProfileId("unit-profile"),InputSource.SIMULATION),catalog(),d,cursor,at,Random(1)){RequestId("central-visit")}
        assertTrue(planned.toString(),planned is ContractResult.Success);val p=(planned as ContractResult.Success).value!!
        assertEquals(d.returns.getValue(p.visit.request.hours),p.visit.terms.returnMinutes);assertEquals(1801000L,p.next.nextAt.epoch.value);assertEquals(1L,p.next.sequence);assertEquals(d.rates,p.visit.hourlyRates)
        assertEquals(d.version, p.definitionVersion)
        assertEquals(d.categories, p.visit.categories)
    }

    @Test fun independentMissingCategoryMalformedWeightsAndClockSettingsNeverDefault() {
        val original=asset("trial-definitions.csv");val ids=catalog().vehicles.map{it.vehicleId}.toSet()
        for(bad in listOf(original.replace("hyundai_casper,SMALL,market-small,1\n",""),
            original.replace("hyundai_casper,SMALL,market-small,1","hyundai_casper,SMALL,unknown-basis,1"),
            original.replace("interval_ms,1800000","interval_ms,-1"),original.replace("max_replay_visits,16","max_replay_visits,2147483648"),
            original.replace("22,6,false,20","22,6,unknown,20")))
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),parseTrialDefinition(bad,ids))
    }

    private fun owned(model: String, level: Int = 1) = OwnedVehicle(
        OwnedVehicleId(model), VehicleId(model), level, Revision(0), VehicleLifecycle.OWNED,
        Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT,
    )

    private fun request(model: String) = RentalRequest(RequestId("eco-visit"), VehicleId(model), 1)

    private fun plan(
        model: String, vehicles: List<OwnedVehicle>,
        resting: Set<OwnedVehicleId> = emptySet(), rented: Set<OwnedVehicleId> = emptySet(),
    ): AssignmentPlan {
        val definition = (definition().rentalVisitDefinition() as ContractResult.Success).value
        val result = Garage(catalog()).planRental(request(model), vehicles, definition.categories, resting) {
            ContractResult.Success(if (it in rented) VehicleActivity.RENTED else VehicleActivity.IDLE)
        }
        return (result as ContractResult.Success).value
    }
}
