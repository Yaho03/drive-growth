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
    @Test fun fuelBonusUsesSuppliedValueForLockedAvailableAndInstalledStates() {
        val supplied = facts().copy(fuelBonusPerRental = GameMoney(17))
        fun fuel(value: FacilityFacts) = FacilityHostMapper.options(value, true)
            .single { it.facility == Facility.FUEL_STATION }.option

        val available = fuel(supplied)
        assertEquals("대여당 17 게임머니 추가 수익", available.effect)
        assertEquals(rule, available.ruleVersion)
        assertEquals(GameMoney(731), available.price)
        assertEquals(ShopAvailability.AVAILABLE, available.availability)

        val locked = fuel(facts(owned = 4).copy(fuelBonusPerRental = GameMoney(17)))
        assertEquals(available.effect, locked.effect)
        assertEquals(ShopAvailability.LOCKED, locked.availability)
        assertNull(locked.command)

        val installed = fuel(facts(installed = setOf(Facility.FUEL_STATION))
            .copy(fuelBonusPerRental = GameMoney(17)))
        assertEquals("기존 설치 기록 · ${available.effect}", installed.effect)
        assertEquals(ShopAvailability.INSTALLED, installed.availability)
        assertNull(installed.command)
        assertEquals("대여당 0 게임머니 추가 수익",
            fuel(supplied.copy(fuelBonusPerRental = GameMoney(0))).effect)
    }

    @Test fun missingFuelEffectAndUnsupportedPurchaseKeepTheirOwnStatus() {
        fun fuel(value: FacilityFacts, journal: Boolean = true) = FacilityHostMapper.options(value, journal)
            .single { it.facility == Facility.FUEL_STATION }.option

        assertEquals("대여당 추가 수익 · 금액 미제공", fuel(facts()).effect)
        val supplied = facts().copy(fuelBonusPerRental = GameMoney(17))
        val missing = fuel(supplied.copy(offers = offers - Facility.FUEL_STATION))
        assertEquals("중앙 시설 정의 미제공", missing.effect)
        assertNull(missing.command)
        val unsupported = fuel(supplied, false)
        assertEquals("구매 원명령 저장 연결 미지원", unsupported.effect)
        assertNull(unsupported.command)
    }

    @Test fun mergingFacilitiesPreservesExpansionAndDecorations() {
        val option=ShopOption("original-expand","원래 확장","","",ShopAvailability.UNKNOWN)
        val original=ShopScreenState.Content(Revision(4),"기존 가게",3,5,12,GameMoney(1000),ShopAppearance("기존",0),emptyList(),emptyList(),option,listOf(ShopOption("decor","장식","","",ShopAvailability.UNKNOWN)),ShopOrigin.STORED)
        val result=FacilityHostMapper.merge(original,facts(),true)
        assertEquals(option,result.expansion);assertEquals(original.decorations,result.decorations);assertEquals(original.appearance,result.appearance)
        assertEquals(3,result.facilities.size)
    }
}
