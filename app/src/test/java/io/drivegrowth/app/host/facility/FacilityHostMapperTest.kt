package io.drivegrowth.app.host.facility

import io.drivegrowth.app.data.ShopState
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test

class FacilityHostMapperTest {
    private val identity=GameIdentity(ProfileId("actual"),InputSource.SIMULATION)
    private val rule=RuleVersion("central")
    private val offers=Facility.entries.associateWith { FacilityOffer(it,rule,true,GameMoney(731)) }
    private fun facts(owned:Int=5, customers:Int=100, level:Int=3, balance:Long=1000, installed:Set<Facility> = emptySet()) =
        FacilityFacts(identity,ContentVersion("central-definition"),ShopState(ShopProgress(owned,customers,level),12,installed),
            GameMoney(balance),emptyList(),emptyMap(),emptySet(),offers)
    @Test fun centralPricesAreUsedWithoutDefaultsAndInstalledCannotBuyAgain() {
        val mapped=FacilityHostMapper.options(facts(installed=setOf(Facility.INSURANCE)),true)
        assertEquals(GameMoney(731),mapped.first().option.price)
        assertEquals(ShopAvailability.AVAILABLE,mapped.first().option.availability)
        assertEquals(ShopCommand.BuyFacility(offers.getValue(Facility.FUEL_STATION)),mapped.first().option.command)
        assertEquals(ShopAvailability.INSTALLED,mapped.single{it.facility==Facility.INSURANCE}.option.availability)
        assertNull(mapped.single{it.facility==Facility.INSURANCE}.option.command)
    }
    @Test fun unlocksFundsAndMissingJournalCannotEnablePurchase() {
        assertTrue(FacilityHostMapper.options(facts(owned=4,customers=99,level=2),true).all{it.option.availability==ShopAvailability.LOCKED})
        assertTrue(FacilityHostMapper.options(facts(balance=730),true).all{it.option.availability==ShopAvailability.LOCKED})
        assertTrue(FacilityHostMapper.options(facts(),false).all{it.option.availability==ShopAvailability.UNKNOWN&&it.option.command==null})
    }
    @Test fun mergingFacilitiesPreservesExpansionAndDecorations() {
        val option=ShopOption("original-expand","원래 확장","","",ShopAvailability.UNKNOWN)
        val original=ShopScreenState.Content(Revision(4),"기존 가게",3,5,12,GameMoney(1000),ShopAppearance("기존",0),emptyList(),emptyList(),option,listOf(ShopOption("decor","장식","","",ShopAvailability.UNKNOWN)),ShopOrigin.STORED)
        val result=FacilityHostMapper.merge(original,facts(),true)
        assertEquals(option,result.expansion);assertEquals(original.decorations,result.decorations);assertEquals(original.appearance,result.appearance)
        assertEquals(3,result.facilities.size)
    }
}
