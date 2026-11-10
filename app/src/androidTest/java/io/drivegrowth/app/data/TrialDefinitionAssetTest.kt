package io.drivegrowth.app.data

import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*

/** 실제 APK asset/provider 확인만 한다. SDK·진행·돈·claim을 생성하지 않는다. */
@Suppress("DEPRECATION")
class TrialDefinitionAssetTest : InstrumentationTestCase() {
    fun testBundledDefinitionAndExpansionAdapterUseTheSameVersionAndValues() {
        val assets=instrumentation.targetContext.assets
        val loaded=AssetTrialDefinitionProvider(assets).load()
        assertTrue(loaded.toString(),loaded is ContractResult.Success)
        val definition=(loaded as ContractResult.Success).value
        assertTrue(definition.trial);assertEquals(20,definition.rentalRates.size)
        assertEquals(100L,definition.rentalRates.values.minOf{it.units});assertEquals(150L,definition.rentalRates.values.maxOf{it.units})
        val expansion=AssetShopExpansionProvider(assets).load() as ContractResult.Success
        assertEquals(definition.version,expansion.value.version);assertEquals(definition.expansions.offers,expansion.value.offers)
        assertEquals(6,definition.questRewards.size)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),definition.questNumbers("unregistered"))
    }
}
