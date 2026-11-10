package io.drivegrowth.app.data

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.quest.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId

class TrialQuestDefinitionsTest {
    private fun definition(): TrialDefinition {
        val asset=sequenceOf(File("src/main/assets/content/trial-definitions.csv"),File("app/src/main/assets/content/trial-definitions.csv")).first{it.isFile}.readText()
        val ids=asset.substringAfter("vehicle_id,hourly_rate\n").substringBefore("\n\n").lines().map{VehicleId(it.substringBefore(','))}.toSet()
        return (parseTrialDefinition(asset,ids) as ContractResult.Success).value
    }
    @Test fun exactSixRegisteredActionsUseCentralNumbersAndExplicitZone() {
        val d=definition();val zone=ZoneId.of("Europe/Berlin")
        val result=d.questDefinitions(zone);assertTrue(result.toString(),result is ContractResult.Success)
        val q=(result as ContractResult.Success).value
        assertEquals(d.rules,q.version);assertEquals(d.questRewards.keys,q.definitions.map{it.id}.toSet())
        for(v in q.definitions) {
            assertEquals(zone,v.zone);assertTrue(v.trial);assertEquals(d.rules,v.version)
            assertEquals(if(v.cadence==QuestCadence.DAILY)1 else 3,v.goal)
            assertEquals(GameMoney(if(v.cadence==QuestCadence.DAILY)100 else 300),v.reward)
        }
    }
    @Test fun missingOrUnexpectedQuestKeyIsFailureWithoutSyntheticDefault() {
        val d=definition()
        for(numbers in listOf(d.questRewards-"daily-result-viewed",d.questRewards+("unknown" to TrialQuestNumbers(1,GameMoney(1))))) {
            val malformed=TrialDefinition(d.expansions,d.rentalRates,d.facilityOffers,d.fuelBonusPerRental,numbers,d.provenance)
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),malformed.questDefinitions(ZoneId.of("UTC")))
        }
    }
    @Test fun separateConsumersDoNotShareOrOverwriteTimezoneOrDefinitions() {
        val d=definition();val a=(d.questDefinitions(ZoneId.of("UTC")) as? ContractResult.Success)?.value
        assertNotNull(a)
        val b=(d.questDefinitions(ZoneId.of("Asia/Seoul")) as ContractResult.Success).value
        assertTrue(a!!.definitions.all{it.zone==ZoneId.of("UTC")});assertTrue(b.definitions.all{it.zone==ZoneId.of("Asia/Seoul")})
        try{(a.definitions as MutableList).clear();fail("공유 정의 변경 금지")}catch(_:UnsupportedOperationException){}
    }
}
