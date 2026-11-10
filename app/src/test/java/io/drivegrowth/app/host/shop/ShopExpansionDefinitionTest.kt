package io.drivegrowth.app.host.shop

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.ShopExpansion
import org.junit.Assert.*
import org.junit.Test
import io.drivegrowth.app.data.AssetShopExpansionProvider
import java.io.IOException

internal val expansionDefinitionFixture = """definition_version,trial-shop-expansion-2026-11-10-v1
rule_version,trial-shop-expansion-2026-11-10-v1
trial,true
from_level,current_capacity,next_capacity,completed_rentals,price
1,3,5,3,500
2,5,7,10,1500
3,7,10,25,3000
"""
class ShopExpansionDefinitionTest {
    @Test fun agreedDefinitionPreservesWholeStageValues() {
        val result = parseShopExpansionDefinition(expansionDefinitionFixture)
        assertTrue(result.toString(), result is ContractResult.Success)
        val definition = (result as ContractResult.Success).value
        assertEquals(ContentVersion("trial-shop-expansion-2026-11-10-v1"), definition.version)
        assertEquals(listOf(
            ShopExpansion(definition.rules,true,1,3,5,3,GameMoney(500)),
            ShopExpansion(definition.rules,true,2,5,7,10,GameMoney(1500)),
            ShopExpansion(definition.rules,true,3,7,10,25,GameMoney(3000)),
        ),definition.offers)
    }
    @Test fun missingMalformedDuplicateAndDiscontinuousDefinitionAreRejected() {
        for (bad in listOf("", expansionDefinitionFixture.replace("trial,true\n", ""),
            expansionDefinitionFixture.replace("1,3,5,3,500", "1,3,5,3,500.1"),
            expansionDefinitionFixture.replace("2,5,7,10,1500", "1,5,7,10,1500"),
            expansionDefinitionFixture.replace("2,5,7,10,1500", "2,6,7,10,1500"),
            expansionDefinitionFixture.replace("25,3000", "25,9223372036854775808")))
            assertEquals(bad,ContractResult.Failure(ContractError.INVALID_INPUT),parseShopExpansionDefinition(bad))
    }
    @Test fun missingAssetDoesNotSupplyDefaultOrFreeExpansion() {
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),AssetShopExpansionProvider { throw IOException("missing") }.load())
    }
    @Test fun callerCannotReplaceSharedDefinitionStages() {
        val definition=(parseShopExpansionDefinition(expansionDefinitionFixture) as ContractResult.Success).value
        try { (definition.offers as MutableList).clear();fail("공유 정의가 변경되면 안 된다") } catch (_:UnsupportedOperationException) {}
        assertEquals(3,definition.offers.size)
    }
}
