package io.drivegrowth.app.data

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.content.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class TrialRentalDefinitionTest {
    private fun asset(name:String)=sequenceOf(File("src/main/assets/content/$name"),File("app/src/main/assets/content/$name")).first{it.isFile}.readText()
    private fun catalog()=(GameContentLoader.load(asset("vehicle-facts.csv"),asset("game-content.csv")) as ContentLoad.Loaded).snapshot.catalog
    private fun definition()=(parseTrialDefinition(asset("trial-definitions.csv"),catalog().vehicles.map{it.vehicleId}.toSet()) as ContractResult.Success).value
    @Test fun actualCentralInputsCoverTwentyAndExistingScheduleDurationReturnRules() {
        val trial=definition();val loaded=trial.rentalVisitDefinition();assertTrue(loaded.toString(),loaded is ContractResult.Success);val d=(loaded as ContractResult.Success).value
        assertEquals(trial.version,d.version);assertEquals(trial.rules,d.rules);assertTrue(d.trial);assertEquals(1800000L,d.intervalMillis)
        assertEquals(catalog().vehicles.map{it.vehicleId}.toSet(),d.categories.keys);assertEquals(d.categories.keys,d.demand.keys);assertEquals(trial.rentalRates,d.rates)
        assertEquals(setOf(1,3,6),d.durations.keys);assertEquals(mapOf(1 to 15,3 to 30,6 to 60),d.returns);assertTrue(d.customers.all{it.weight>0});assertEquals(ContractResult.Success(16),trial.rentalReplayLimit())
    }
    @Test fun gameCategoryMappingUsesRecordedBodyAndMarketWithoutAllSmallFallback() {
        val loaded=definition().rentalVisitDefinition();assertTrue(loaded is ContractResult.Success);val d=(loaded as ContractResult.Success).value
        assertEquals(RentalCategory.SMALL,d.categories[VehicleId("hyundai_casper")]);assertEquals(RentalCategory.SEDAN,d.categories[VehicleId("hyundai_avante")]);assertEquals(RentalCategory.VAN,d.categories[VehicleId("hyundai_staria_tourer")]);assertEquals(RentalCategory.SUV,d.categories[VehicleId("hyundai_palisade")])
        assertEquals(setOf(RentalCategory.SMALL,RentalCategory.SEDAN,RentalCategory.SUV,RentalCategory.VAN),d.categories.values.toSet())
    }
    @Test fun realPlannerConsumesDefinitionAndReturnsWholeFrozenVisitWithoutStorage() {
        val loaded=definition().rentalVisitDefinition();assertTrue(loaded is ContractResult.Success);val d=(loaded as ContractResult.Success).value
        val at=RentalClock("explicit-boot",EpochMillis(1000),100);val cursor=RentalVisitCursor(Revision(0),0,at)
        val planned=RentalVisitPlanner().prepare(GameIdentity(ProfileId("unit-profile"),InputSource.SIMULATION),catalog(),d,cursor,at,Random(1)){RequestId("central-visit")}
        assertTrue(planned.toString(),planned is ContractResult.Success);val p=(planned as ContractResult.Success).value!!
        assertEquals(d.returns.getValue(p.visit.request.hours),p.visit.terms.returnMinutes);assertEquals(1801000L,p.next.nextAt.epoch.value);assertEquals(1L,p.next.sequence);assertEquals(d.rates,p.visit.hourlyRates)
    }
}
