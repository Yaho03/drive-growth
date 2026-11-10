package io.drivegrowth.app.data
import io.drivegrowth.core.contract.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class TrialRestDefinitionTest {
 private fun text()=File("src/main/assets/content/trial-definitions.csv").readText()
 private fun known()=text().substringAfter("[rental_rates]\n").substringBefore("\n[").lineSequence().drop(1).filter{it.isNotBlank()}.map{VehicleId(it.substringBefore(','))}.toSet()
 @Test fun agreedRestNumbersComeFromTheSameVersionedAsset(){
  val r=parseTrialDefinition(text(),known());assertTrue(r.toString(),r is ContractResult.Success);val d=(r as ContractResult.Success).value
  val rest=d.restRules();assertTrue(rest is ContractResult.Success);val v=(rest as ContractResult.Success).value
  assertEquals(d.rules,v.ruleVersion);assertEquals(3600000L,v.minDrivingMs);assertEquals(300000L,v.minParkedMs);assertEquals(1200000L,v.bonusDurationMs);assertEquals(150,v.bonusPercent)
  assertEquals(ContractResult.Success(RestPricingPolicy.CHRONOLOGICAL_PREFIX_RATIONAL_V1),d.restPricingPolicy())
 }
 @Test fun absentAndMalformedRestInputsAreRejectedWithoutDefaults(){
  for(bad in listOf(text().substringBefore("\n[rest]\n"),text().replace("bonus_percent,150","bonus_percent,0"),text().replace("min_parked_ms,300000","min_parked_ms,1.5"),text().replace("pricing_policy,chronological-prefix-rational-v1","pricing_policy,unknown")))assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),parseTrialDefinition(bad,known()))
 }
}
