package io.drivegrowth.app.host.shop

import io.drivegrowth.app.data.ShopState
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.validFor
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test

class ShopExpansionMapperTest {
    private val definition=(parseShopExpansionDefinition(expansionDefinitionFixture) as ContractResult.Success).value
    private fun mapped(completed:Int=3,balance:Long=500,capacity:Int=3,level:Int=1)=mapShopExpansion(ProfileId("p"),InputSource.SIMULATION,
        definition,ShopState(ShopProgress(1,99,level),capacity,setOf(Facility.INSURANCE)),completed,GameMoney(balance),emptyList())
    @Test fun visitsDoNotReplaceCompletedCountAndInsufficientMoneyStaysLocked() {
        assertEquals(ShopAvailability.LOCKED,mapped(completed=2).screen.expansion!!.availability)
        assertEquals(ShopAvailability.LOCKED,mapped(balance=499).screen.expansion!!.availability)
        val exact=mapped();assertEquals(ShopAvailability.AVAILABLE,exact.screen.expansion!!.availability)
        assertEquals(GameMoney(500),exact.screen.expansion!!.price)
        assertTrue(exact.screen.expansion!!.condition.contains("3/3"))
    }
    @Test fun expansionEffectUsesGameCopyAndKeepsDefinitionAndRuleVersions() {
        val snapshot = mapped()
        val option = checkNotNull(snapshot.screen.expansion)
        assertEquals("확정 후 공간 5칸", option.effect)
        assertEquals(definition.version, snapshot.definitionVersion)
        assertEquals(definition.rules, option.ruleVersion)
        assertEquals(ShopCommand.Expand(definition.offers.first()), option.command)
        assertTrue(option.trial)
    }
    @Test fun unknownCapacityAndUnsupportedFacilitiesAndDecorationsCannotSubmit() {
        val value=mapped(capacity=6)
        assertEquals(6,value.screen.capacity);assertEquals(ShopAvailability.UNKNOWN,value.screen.expansion!!.availability)
        assertTrue(value.screen.facilities.all { it.option.command==null && it.option.price==null })
        assertTrue(value.screen.decorations.all { it.command==null && it.availability==ShopAvailability.UNKNOWN })
        assertEquals(ShopAvailability.INSTALLED,value.screen.facilities.single{it.facility==Facility.INSURANCE}.option.availability)
        assertNull(mapped(level=4,capacity=10).screen.expansion!!.command)
    }
    @Test fun wholeExpandCommandKeepsSourceAndReservationBoundary() {
        val c=HostCheckpointCommand.Expand(RequestId("expand"),InputSource.SIMULATION,definition.version,definition.offers[0])
        assertTrue(c.validFor(InputSource.SIMULATION));assertFalse(c.validFor(InputSource.VEHICLE))
        assertFalse(c.copy(requestId=RequestId("drive/reserved")).validFor(InputSource.SIMULATION))
        assertNotEquals(c,c.copy(offer=c.offer.copy(price=GameMoney(501))))
        assertNotEquals(c,c.copy(definitionVersion=ContentVersion("different-definition")))
    }
}
