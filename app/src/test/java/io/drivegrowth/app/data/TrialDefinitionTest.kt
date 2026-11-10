package io.drivegrowth.app.data

import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentLoader
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.Facility
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TrialDefinitionTest {
    private fun asset(name:String):String {
        val cwd=File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(cwd,"src/main/assets/content/$name"),File(cwd,"app/src/main/assets/content/$name"))
            .first{it.isFile}.readText()
    }
    private val text get()=asset("trial-definitions.csv")
    private fun known():Set<VehicleId> {
        val loaded=GameContentLoader.load(asset("vehicle-facts.csv"),asset("game-content.csv")) as ContentLoad.Loaded
        return loaded.snapshot.catalog.vehicles.map{it.vehicleId}.toSet()
    }
    private fun definition():TrialDefinition {
        val result=parseTrialDefinition(text,known());assertTrue(result.toString(),result is ContractResult.Success)
        return(result as ContractResult.Success).value
    }
    @Test fun actualSingleAssetPreservesAgreedExpansionAndBoundedTwentyRates() {
        val d=definition();assertTrue(d.trial);assertEquals(20,d.rentalRates.size);assertEquals(known(),d.rentalRates.keys)
        assertEquals(listOf(3,10,25),d.expansions.offers.map{it.requiredCustomers})
        assertEquals(listOf(500L,1500L,3000L),d.expansions.offers.map{it.price.units})
        assertEquals(100L,d.rentalRates.values.minOf{it.units});assertEquals(150L,d.rentalRates.values.maxOf{it.units})
        assertEquals(setOf(Facility.FUEL_STATION,Facility.INSURANCE,Facility.CAR_WASH),d.facilityOffers.keys)
        assertEquals(GameMoney(10),d.fuelBonusPerRental)
        assertEquals("user-demo-trial-2026-11-10",d.provenance["authorization"])
    }
    @Test fun questKeysAreNumericInputsAndMissingKeyIsNotZeroReward() {
        val d=definition();assertEquals(6,d.questRewards.size)
        assertEquals(ContractResult.Success(TrialQuestNumbers(1,GameMoney(100))),d.questNumbers("daily-result-viewed"))
        assertEquals(ContractResult.Success(TrialQuestNumbers(3,GameMoney(300))),d.questNumbers("weekly-rental-completed"))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),d.questNumbers("unknown"))
        val ids=setOf("daily-result-viewed","daily-vehicle-level-up","daily-rental-completed",
            "weekly-result-viewed","weekly-vehicle-level-up","weekly-rental-completed")
        assertTrue(d.questNumbersFor(ids) is ContractResult.Success)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),d.questNumbersFor(ids+"unregistered"))
    }
    @Test fun malformedMissingDuplicateUnknownVehicleAndOverflowAreRejected() {
        for(bad in listOf(text.replace("hyundai_casper,100","unknown_vehicle,100"),
            text.replace("hyundai_casper,100\n",""),text.replace("hyundai_casper,100","hyundai_casper,100\nhyundai_casper,101"),
            text.replace("FUEL_STATION,500","UNKNOWN,500"),text.replace("per_rental,10","per_rental,9223372036854775808"),
            text.replace("daily-result-viewed,1,100","daily-result-viewed,0,100"),
            text.replace("daily-result-viewed,1,100","daily-result-viewed,1,0")))
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),parseTrialDefinition(bad,known()))
    }
    @Test fun unavailableCatalogueDoesNotBecomeGuessedVehicleRates() {
        val provider=AssetTrialDefinitionProvider({text},{ContractResult.Failure(ContractError.DATA_UNAVAILABLE)})
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),provider.load())
    }
    @Test fun callersCannotOverwriteSharedRatesOrRewards() {
        val d=definition()
        try{(d.rentalRates as MutableMap)[VehicleId("hyundai_casper")]=GameMoney(999);fail("공유값 변경 금지")}catch(_:UnsupportedOperationException){}
        try{(d.questRewards as MutableMap).clear();fail("보상 정의 변경 금지")}catch(_:UnsupportedOperationException){}
        assertEquals(ContractResult.Success(GameMoney(100)),d.rentalRate(VehicleId("hyundai_casper")))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),d.rentalRate(VehicleId("unknown")))
    }
}
