package io.drivegrowth.app.host.ranking

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.*

class RankingAggregationTest {
    private val source=InputSource.SIMULATION
    private val car=OwnedVehicle(OwnedVehicleId("a"),VehicleId("v"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.LEGEND,0,GameMoney(0),VehicleAcquisitionSource.GACHA)
    private fun amount(value:RankingValue)=(value as RankingValue.Available).amount
    @Test fun currentCopiesCountSeparatelyButDisposedDoNot(){
        val cars=listOf(car,car.copy(ownedVehicleId=OwnedVehicleId("b")),car.copy(ownedVehicleId=OwnedVehicleId("sold"),lifecycle=VehicleLifecycle.SOLD))
        assertEquals(BigDecimal(2),amount(RankingAggregation.owned(cars)))
    }
    @Test fun collectionCountsHistoricalSlotsAccordingToExplicitDefinition(){
        val entries=listOf(CollectionEntry(CollectionKey(VehicleId("v"),Rarity.COMMON),CollectionState.OWNED),CollectionEntry(CollectionKey(VehicleId("v"),Rarity.RARE),CollectionState.DISCOVERED))
        assertEquals(BigDecimal.ONE,amount(RankingAggregation.collection(entries,CollectionCountDefinition.OBTAINED)))
        assertEquals(BigDecimal(2),amount(RankingAggregation.collection(entries,CollectionCountDefinition.SEEN_OR_OBTAINED)))
        assertTrue(RankingAggregation.collection(entries,null) is RankingValue.Missing)
    }
    @Test fun highestUsesExactGamePriceAndRarityAndNeverAcquisitionOrLevel(){
        assertEquals(BigDecimal("1.3"),amount(RankingAggregation.highest(listOf(car),mapOf(car.vehicleId to GameMoney(1)))))
        assertTrue(RankingAggregation.highest(listOf(car.copy(lifecycle=VehicleLifecycle.SOLD)),emptyMap()) is RankingValue.Empty)
        assertTrue(RankingAggregation.highest(listOf(car),emptyMap()) is RankingValue.Failure)
    }
    @Test fun incomeDeduplicatesSameCauseAndDoesNotWrapAtLongMax(){
        val def=RankingIncomeDefinition(RuleVersion("earned"),setOf(RankingIncomeOrigin.RENTAL))
        val row=RankingIncome(RequestId("one"),RankingIncomeOrigin.RENTAL,GameMoney(Long.MAX_VALUE),source)
        val other=row.copy(requestId=RequestId("two"))
        assertEquals(BigDecimal("18446744073709551614"),amount(RankingAggregation.income(listOf(row,row,other),def,source)))
        assertTrue(RankingAggregation.income(listOf(row,row.copy(amount=GameMoney(1))),def,source) is RankingValue.Failure)
        assertTrue(RankingAggregation.income(listOf(row),null,source) is RankingValue.Missing)
    }
    @Test fun incomeBasisUsesDeclaredOrderRegardlessOfSetIteration() {
        val rows = listOf(
            RankingIncome(RequestId("drive"), RankingIncomeOrigin.DRIVING, GameMoney(1), source),
            RankingIncome(RequestId("sale"), RankingIncomeOrigin.DISPOSAL, GameMoney(2), source),
            RankingIncome(RequestId("quest"), RankingIncomeOrigin.QUEST, GameMoney(3), source),
            RankingIncome(RequestId("rental"), RankingIncomeOrigin.RENTAL, GameMoney(1_000), source),
        )
        val origins = listOf(RankingIncomeOrigin.DRIVING, RankingIncomeOrigin.DISPOSAL, RankingIncomeOrigin.QUEST)
        val expected = RankingValue.Available(BigDecimal(6), "확정 수익 누적 · 주행, 판매·폐차, 퀘스트")

        for (order in listOf(origins, origins.reversed())) {
            val definition = RankingIncomeDefinition(RuleVersion("earned"), order.toSet())
            assertEquals(expected, RankingAggregation.income(rows, definition, source))
        }
    }

    @Test fun weekUsesExplicitZoneAndStartDayAndExcludesNextWeek(){
        val def=RankingWeekDefinition(RuleVersion("weekly"),DayOfWeek.MONDAY,ZoneId.of("Asia/Seoul"))
        val at=EpochMillis(Instant.parse("2026-11-09T00:00:00Z").toEpochMilli())
        val rows=listOf(RankingDayDistance(LocalDate.parse("2026-11-08"),500000,source,Observation.Available(def.zone)),RankingDayDistance(LocalDate.parse("2026-11-09"),1234,source,Observation.Available(def.zone)),RankingDayDistance(LocalDate.parse("2026-11-16"),99,source,Observation.Available(def.zone)))
        assertEquals(BigDecimal(1234),amount(RankingAggregation.week(rows,def,source,at)))
        assertTrue(RankingAggregation.week(rows,null,source,at) is RankingValue.Missing)
        assertTrue(RankingAggregation.week(listOf(rows[1].copy(rewardedMeters=500001)),def,source,at) is RankingValue.Failure)
    }
    @Test fun mixedSourcesAndDuplicateVehicleIdsAreFailures(){
        assertTrue(RankingAggregation.owned(listOf(car,car)) is RankingValue.Failure)
        val def=RankingIncomeDefinition(RuleVersion("earned"),setOf(RankingIncomeOrigin.RENTAL))
        assertTrue(RankingAggregation.income(listOf(RankingIncome(RequestId("x"),RankingIncomeOrigin.RENTAL,GameMoney(1),InputSource.VEHICLE)),def,source) is RankingValue.Failure)
    }
    @Test fun acceptedPolicyIncludesDisposalAndQuestAndUsesObtainedKoreanMonday(){
        val d=RankingDefinitions.acceptedLocal();assertEquals(CollectionCountDefinition.OBTAINED,d.collection)
        assertEquals(DayOfWeek.MONDAY,d.week!!.firstDay);assertEquals("Asia/Seoul",d.week!!.zone.id)
        val rows=listOf(RankingIncome(RequestId("sale"),RankingIncomeOrigin.DISPOSAL,GameMoney(75),source),RankingIncome(RequestId("quest"),RankingIncomeOrigin.QUEST,GameMoney(25),source))
        assertEquals(BigDecimal(100),amount(RankingAggregation.income(rows,d.income,source)))
    }

    @Test fun unknownOrOtherDepartureCalendarNeverBecomesAKoreanWeek(){
        val def=RankingDefinitions.acceptedLocal().week!!;val at=EpochMillis(Instant.parse("2026-11-09T00:00:00Z").toEpochMilli())
        val row=RankingDayDistance(LocalDate.parse("2026-11-09"),1234,source)
        assertTrue(RankingAggregation.week(listOf(row),def,source,at) is RankingValue.Missing)
        assertTrue(RankingAggregation.week(listOf(row.copy(departureZone=Observation.Available(ZoneId.of("America/Los_Angeles")))),def,source,at) is RankingValue.Missing)
        assertTrue(RankingAggregation.week(emptyList(),def,source,at) is RankingValue.Empty)
        assertEquals(BigDecimal.ZERO,amount(RankingAggregation.week(listOf(row.copy(rewardedMeters=0,departureZone=Observation.Available(def.zone))),def,source,at)))
    }

    @Test fun simulationWeekUsesUtcAndIgnoresPreviousWeekZone() {
        val simulation = InputSource.SIMULATION
        val definition = RankingDefinitions.acceptedLocal(simulation).week!!
        assertEquals(ZoneId.of("UTC"), definition.zone)
        val asOf = EpochMillis(Instant.parse("2026-11-09T00:00:00Z").toEpochMilli())
        val monday = LocalDate.parse("2026-11-09")
        val current = RankingDayDistance(monday, 1_234, simulation, Observation.Available(definition.zone))
        val older = RankingDayDistance(monday.minusDays(1), 50, simulation)
        assertEquals(1_234.toBigDecimal(),
            amount(RankingAggregation.week(listOf(older, current), definition, simulation, asOf)))
    }

    @Test fun rarityPriceRatiosAreExactAndDoNotOverflowLong(){
        val expected=listOf("1000","1100","1200","1300")
        Rarity.entries.forEachIndexed{i,r->assertEquals(0,BigDecimal(expected[i]).compareTo(amount(RankingAggregation.highest(listOf(car.copy(rarity=r)),mapOf(car.vehicleId to GameMoney(1000))))))}
        assertEquals(0,BigDecimal("11990383647911208549.1").compareTo(amount(RankingAggregation.highest(listOf(car),mapOf(car.vehicleId to GameMoney(Long.MAX_VALUE))))))
    }
    @Test fun snapshotsAreCopiedAndNegativePresentationAmountsAreRejected(){
        val map=mutableMapOf<RankingMetric,RankingValue>(RankingMetric.OWNED to RankingValue.Available(BigDecimal.ONE,"기록"))
        val frozen=RankingSnapshot(source,EpochMillis(0),ContentVersion("c"),RuleVersion("r"),map).frozen();map.clear();assertEquals(1,frozen.metrics.size)
        assertThrows(UnsupportedOperationException::class.java){(frozen.metrics as MutableMap).clear()}
        assertThrows(IllegalArgumentException::class.java){RankingValue.Available(BigDecimal(-1),"잘못된 표시")}
    }
    @Test fun confirmedEmptyAndMissingEvidenceAreDifferent(){
        assertEquals(BigDecimal.ZERO,amount(RankingAggregation.owned(emptyList())))
        assertTrue(RankingAggregation.highest(emptyList(),emptyMap()) is RankingValue.Empty)
        assertTrue(RankingAggregation.income(emptyList(),RankingDefinitions.acceptedLocal().income,source) is RankingValue.Empty)
        assertTrue(RankingAggregation.income(emptyList(),null,source) is RankingValue.Missing)
    }

}
