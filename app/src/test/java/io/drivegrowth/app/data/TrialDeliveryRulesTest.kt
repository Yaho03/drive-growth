package io.drivegrowth.app.data

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TrialDeliveryRulesTest {
    private val text get()=sequenceOf(File("src/main/assets/content/trial-definitions.csv"),File("app/src/main/assets/content/trial-definitions.csv")).first{it.isFile}.readText()
    private fun ids(t:String)=t.substringAfter("vehicle_id,hourly_rate\n").substringBefore("\n\n").lines().map{VehicleId(it.substringBefore(','))}.toSet()
    private fun definition()=(parseTrialDefinition(text,ids(text)) as ContractResult.Success).value
    @Test fun centralTypedRulesPreservePrdNumbersAndExplicitTrialInputs() {
        val d=definition();val r=(d.deliveryRules() as ContractResult.Success).value
        assertTrue(d.trial);assertEquals(d.rules,r.ruleVersion);assertEquals(listOf(10000L,50000L,null),r.bands.map{it.maxMetersInclusive})
        assertEquals(DeliveryRounding.DOWN,r.rounding);assertEquals(2,r.incomeOfferCount);assertEquals(3,r.purchaseOfferCount)
        assertEquals(10,(d.deliveryChangeRules() as ContractResult.Success).value.cancelFeePercent)
        val settle=(d.deliverySettlementRules() as ContractResult.Success).value;assertEquals(86400000L,settle.arrivalWindowMs);assertEquals(86400000L,settle.holdWindowMs);assertEquals(5,settle.holdRefundFeePercent)
        val drive=(d.drivingIncomeRules() as ContractResult.Success).value;assertEquals(500000L,drive.dailyCapMeters);assertEquals(GameMoney(10),drive.incomePerKm)
    }
    @Test fun missingMalformedOverflowAndChangedFixedCapAreRejected() {
        for(bad in listOf(text.replace("income_per_km,10\n",""),text.replace("daily_cap_meters,500000","daily_cap_meters,500001"),
            text.replace("10000,850","10000.5,850"),text.replace("income_offer_count,2","income_offer_count,2147483648"),
            text.replace("rounding,DOWN","rounding,UNKNOWN"),text.replace("unbounded,600","unbounded,-1")))
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),parseTrialDefinition(bad,ids(text)))
    }
    @Test fun rulesReturnImmutableBandsAndExactRarityMaps() {
        val r=(definition().deliveryRules() as ContractResult.Success).value;assertEquals(Rarity.entries.toSet(),r.rarityPricePercent.keys)
        try{(r.bands as MutableList).clear();fail("규칙 변경 금지")}catch(_:UnsupportedOperationException){}
        try{(r.bands[0].rarityWeights as MutableMap).clear();fail("가중치 변경 금지")}catch(_:UnsupportedOperationException){}
    }
}
