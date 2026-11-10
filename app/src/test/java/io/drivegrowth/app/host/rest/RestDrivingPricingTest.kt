package io.drivegrowth.app.host.rest

import io.drivegrowth.app.host.driving.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RestDrivingPricingTest {
    private val rules = DrivingIncomeRules(RuleVersion("driving-test"), 500000, GameMoney(10))
    private val day = LocalDate.of(2026, 11, 10)
    private fun observation(t: Long, speed: Double, gear: Gear = Gear.DRIVE) = VehicleObservation(
        InputSource.SIMULATION, t, EpochMillis(1794268800000L + t), Observation.Available(speed),
        Observation.Available(gear), Observation.Available(false))
    private fun segment(a: Double, b: Double, ms: Long, meters: String, start: Long = 0) =
        DrivingSegment(observation(start,a), observation(start+ms,b), BigDecimal(meters))
    private fun trip(vararg segments: DrivingSegment, income: DrivingIncomeRules = rules): ObservedTrip =
        ObservedTrip("original-drive", segments.firstOrNull()?.from ?: observation(0,1.0),
            Observation.Available(ZoneId.of("UTC")), ContractResult.Success(income),
            segments.fold(BigDecimal.ZERO) { sum, s -> sum + s.meters }, segments.toList())
    private fun window(index: Int, from: Long, until: Long, percent: Int = 150) = RestPricingWindow(index,from,until,percent)
    private fun price(t: ObservedTrip, windows: List<RestPricingWindow>, before: DrivingDay? = null): RestDrivingPrice {
        val result = RestDrivingPricing.price(t,before,windows)
        assertTrue("정확한 가격 계획 성공 필요: $result", result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    private fun exact(n: Long, d: Long = 1) = RestDistanceFraction(BigInteger.valueOf(n),BigInteger.valueOf(d))
    private fun invalid(t: ObservedTrip, windows: List<RestPricingWindow>) =
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), RestDrivingPricing.price(t,null,windows))

    @Test fun acceleratingExpiryUsesPartialIntegralNotTimeProportion() {
        val p=price(trip(segment(0.0,20.0,2000,"20.0")),listOf(window(0,0,1000)))
        assertEquals(exact(5),p.slices.single().rawMeters)
        assertEquals(exact(5),p.slices.single().rewardedMeters)
        assertEquals(20,p.settlement.recordedMeters)
    }
    @Test fun decelerationExpiryUsesOriginalSlope() {
        val p=price(trip(segment(20.0,0.0,2000,"20.0")),listOf(window(0,0,1000)))
        assertEquals(exact(15),p.slices.single().rawMeters)
    }
    @Test fun departureInsideSegmentExcludesEarlierMovement() {
        val p=price(trip(segment(0.0,20.0,2000,"20.0")),listOf(window(0,500,1500)))
        assertEquals(exact(10),p.slices.single().rawMeters)
        assertEquals(1000,p.boostedMs)
    }
    @Test fun nonTerminatingPartialDistanceRemainsExact() {
        val p=price(trip(segment(0.0,1.0,3,"0.0015")),listOf(window(0,0,1)))
        assertEquals(exact(1,6000),p.slices.single().rawMeters)
        assertEquals(exact(0),p.slices.single().rewardedMeters)
    }
    @Test fun capBeforeExpiryClipsByDistance() {
        val p=price(trip(segment(0.0,20.0,2000,"20.0")),listOf(window(0,0,1000)),DrivingDay(day,499997))
        assertEquals(3,p.settlement.rewardedMeters);assertEquals(exact(3),p.slices.single().rewardedMeters)
        assertEquals(500000,p.settlement.day.rewardedMeters)
    }
    @Test fun expiryBeforeCapLeavesUnboostedRewardedSuffix() {
        val p=price(trip(segment(0.0,20.0,2000,"20.0")),listOf(window(0,0,1000)),DrivingDay(day,499993))
        assertEquals(7,p.settlement.rewardedMeters);assertEquals(exact(5),p.slices.single().rewardedMeters)
    }
    @Test fun capInsideLaterWindowDoesNotBoostEarlierUncoveredDistance() {
        val p=price(trip(segment(10.0,10.0,2000,"20.0")),listOf(window(0,1000,2000)),DrivingDay(day,499985))
        assertEquals(exact(5),p.slices.single().rewardedMeters)
    }
    @Test fun capExhaustionPreservesObservedBoostTimeWithoutPayment() {
        val p=price(trip(segment(10.0,10.0,2000,"20.0")),listOf(window(0,0,2000)),DrivingDay(day,500000))
        assertEquals(2000,p.boostedMs);assertEquals(20,p.settlement.recordedMeters)
        assertEquals(0,p.settlement.rewardedMeters);assertEquals(GameMoney(0),p.settlement.amount);assertNull(p.settlement.change)
    }
    @Test fun fractionalTailIsNotResurrectedByBonus() {
        val p=price(trip(segment(66.9,66.9,1000,"66.9")),listOf(window(0,0,1000)))
        assertEquals(66,p.settlement.recordedMeters);assertEquals(exact(66),p.slices.single().rewardedMeters)
        assertEquals(GameMoney(0),p.settlement.amount)
    }
    @Test fun floorOccursOnceAfterWholeDriveBonus() {
        val p=price(trip(segment(99.5,99.5,2000,"199.0")),listOf(window(0,0,2000)))
        assertEquals(GameMoney(2),p.settlement.amount)
        assertEquals(RequestId("drive/original-drive"),p.settlement.change!!.requestId)
        assertEquals(TransactionKind.DRIVING,p.settlement.change!!.kind)
    }
    @Test fun splittingOneGrantIntoAdjacentSegmentsDoesNotChangeMoney() {
        val split=price(trip(segment(66.0,66.0,1000,"66.0"),segment(66.0,66.0,1000,"66.0",1000)),
            listOf(window(0,0,1000),window(1,0,1000)))
        val whole=price(trip(segment(66.0,66.0,2000,"132.0")),listOf(window(0,0,2000)))
        assertEquals(GameMoney(1),split.settlement.amount);assertEquals(whole.settlement,split.settlement)
    }
    @Test fun adjacentOldAndRefreshedGrantWindowsDoNotStack() {
        val p=price(trip(segment(100.0,100.0,2000,"200.0")),listOf(window(0,0,1000),window(0,1000,2000)))
        assertEquals(GameMoney(3),p.settlement.amount);assertEquals(2000,p.boostedMs)
        assertEquals(listOf(exact(100),exact(100)),p.slices.map{it.rewardedMeters})
    }
    @Test fun gapDoesNotBecomeDistanceOrBoostTime() {
        val p=price(trip(segment(10.0,10.0,1000,"10.0"),segment(10.0,10.0,1000,"10.0",100000)),
            listOf(window(0,0,1000),window(1,0,1000)))
        assertEquals(2000,p.boostedMs);assertEquals(20,p.settlement.recordedMeters)
    }
    @Test fun stationaryDriveKeepsTimeButNoDistance() {
        val p=price(trip(segment(0.0,0.0,1000,"0.0")),listOf(window(0,0,1000)))
        assertEquals(1000,p.boostedMs);assertEquals(exact(0),p.slices.single().rawMeters);assertNull(p.settlement.change)
    }
    @Test fun reverseUsesAbsoluteEndpointSpeed() {
        val p=price(trip(segment(-20.0,-10.0,2000,"30.0")),listOf(window(0,0,1000)))
        assertEquals(exact(35,2),p.slices.single().rawMeters)
    }
    @Test fun zeroRateStillReturnsDistancesAndBoostTime() {
        val p=price(trip(segment(10.0,10.0,1000,"10.0"),income=rules.copy(incomePerKm=GameMoney(0))),listOf(window(0,0,1000)))
        assertEquals(GameMoney(0),p.settlement.amount);assertEquals(exact(10),p.slices.single().rewardedMeters)
    }
    @Test fun unboostedMatchesExistingDrivingIncomeAcrossMeterAndCapBoundaries() {
        for(meters in 0L..500L) for(prior in listOf(0L,499999L,500000L)) {
            val t=trip(if(meters==0L) segment(0.0,0.0,1000,"0.0")
                else segment(1.0,1.0,meters*1000,"$meters.0"))
            val before=DrivingDay(day,prior)
            val expected=(DrivingIncome(rules).settle(before,t.confirmed(InputSource.SIMULATION)!!) as ContractResult.Success).value
            assertEquals(expected,price(t,emptyList(),before).settlement)
        }
    }
    @Test fun keepsOriginalDepartureDateAndZoneAcrossMidnight() {
        val s=segment(10.0,10.0,2000,"20.0")
        val departure=s.from.copy(receivedAt=EpochMillis(java.time.Instant.parse("2026-11-10T14:59:59Z").toEpochMilli()))
        val acrossMidnight=s.copy(from=departure,to=s.to.copy(receivedAt=EpochMillis(departure.receivedAt.value+2000)))
        val t=trip(acrossMidnight).copy(zone=Observation.Available(ZoneId.of("Asia/Seoul")))
        val result=price(t,listOf(window(0,0,2000))).settlement
        assertEquals(day,result.drive.departedOn)
        assertEquals("original-drive",result.drive.driveId)
    }
    @Test fun missingOriginalZoneAndRulesAreNotFilled() {
        val t=trip(segment(1.0,1.0,1000,"1.0"))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),RestDrivingPricing.price(t.copy(zone=Observation.Missing(MissingReason.NO_DATA)),null,emptyList()))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),RestDrivingPricing.price(t.copy(rules=ContractResult.Failure(ContractError.STORAGE_FAILURE)),null,emptyList()))
    }
    @Test fun rejectsWrongDayWithoutChangingInput() {
        val t=trip(segment(1.0,1.0,1000,"1.0"))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),RestDrivingPricing.price(t,DrivingDay(day.minusDays(1),0),emptyList()))
        assertEquals(BigDecimal("1.0"),t.meters)
    }
    @Test fun rejectsOverlappingDuplicateUnsortedAndOutOfRangeWindows() {
        val t=trip(segment(10.0,10.0,2000,"20.0"))
        for(ws in listOf(listOf(window(0,0,1500),window(0,1000,2000)),listOf(window(0,0,1000),window(0,0,1000)),
            listOf(window(0,1000,2000),window(0,0,1000)),listOf(window(1,0,1000)),listOf(window(0,-1,1000)),
            listOf(window(0,0,2001)),listOf(window(0,0,0)),listOf(window(0,0,1000,0)))) invalid(t,ws)
    }
    @Test fun rejectsCorruptedRawTotalOrSegmentDistanceAndSource() {
        val s=segment(10.0,10.0,1000,"10.0");val t=trip(s)
        invalid(t.copy(meters=BigDecimal("11.0")),emptyList())
        invalid(trip(s.copy(meters=BigDecimal("11.0"))),emptyList())
        invalid(trip(s.copy(to=s.to.copy(source=InputSource.VEHICLE))),emptyList())
        invalid(trip(s.copy(to=s.to.copy(receivedElapsedMs=0))),emptyList())
    }
    @Test fun rejectsNonfiniteOrOutOfBoundSpeedsAndBoostOverParking() {
        val s=segment(10.0,10.0,1000,"10.0")
        for(speed in listOf(Double.NaN,Double.POSITIVE_INFINITY,101.0)) invalid(trip(s.copy(from=s.from.copy(speedMps=Observation.Available(speed)))),emptyList())
        invalid(trip(s.copy(to=s.to.copy(gear=Observation.Available(Gear.PARK)))),listOf(window(0,0,1000)))
    }
    @Test fun wideIntermediateWorksAndFinalMoneyOverflowIsRejected() {
        val t=trip(segment(1.0,1.0,1000,"1.0"),income=rules.copy(incomePerKm=GameMoney(Long.MAX_VALUE)))
        assertEquals(GameMoney(13835058055282163L),price(t,listOf(window(0,0,1000))).settlement.amount)
        val tooMuch=trip(segment(100.0,100.0,10000,"1000.0"),income=rules.copy(incomePerKm=GameMoney(Long.MAX_VALUE)))
        invalid(tooMuch,listOf(window(0,0,10000)))
    }
    @Test fun rejectsBoostDurationOverflowAcrossIndependentClockDomains() {
        val duration=Long.MAX_VALUE/2+1
        val s=DrivingSegment(observation(0,0.0),observation(1,0.0).copy(receivedElapsedMs=duration),BigDecimal("0.0"))
        invalid(trip(s,s),listOf(window(0,0,duration),window(1,0,duration)))
    }
    @Test fun fractionalSegmentMetersAreAddedBeforeOriginalMeterFloor() {
        val p=price(trip(segment(33.5,33.5,1000,"33.5"),segment(33.5,33.5,1000,"33.5",1000)),
            listOf(window(0,0,1000),window(1,0,1000)))
        assertEquals(67,p.settlement.recordedMeters);assertEquals(GameMoney(1),p.settlement.amount)
    }
    @Test fun wholeDriveDistanceOverflowIsRejectedBeforeNarrowing() {
        val s=DrivingSegment(observation(0,100.0),observation(1,100.0).copy(receivedElapsedMs=Long.MAX_VALUE),BigDecimal("922337203685477580.7"))
        invalid(trip(*Array(11){s}),emptyList())
    }
    @Test fun resultSlicesCannotBeMutatedByCallerInputList() {
        val windows=mutableListOf(window(0,0,1000));val p=price(trip(segment(10.0,10.0,1000,"10.0")),windows)
        windows.clear();assertEquals(1,p.slices.size)
        try {(p.slices as MutableList).clear();fail("결과 목록 수정 금지")}catch(_:UnsupportedOperationException){}
    }
}
