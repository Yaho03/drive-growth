package io.drivegrowth.app.data

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.rest.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.junit.Test
import org.junit.Assert.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.ZoneId

/** E 독립 기대값을 D 가격 부품에 연결한다. 시뮬레이션 산술이며 실제 관측/지급 시험이 아니다. */
class RestTimelinePricingTest {
 private val id=GameIdentity(ProfileId("rest-math-fixture"),InputSource.SIMULATION)
 private val driving=DrivingIncomeRules(RuleVersion("explicit-math"),500000,GameMoney(1000))
 private val rest=RestRules(driving.ruleVersion,1,1,500,150)
 private val policy=RestEvidencePolicy(2500,2500,0,0.5,0.1)
 private val missing=Observation.Missing(MissingReason.NO_DATA)
 private fun o(t:Long,v:Double)=VehicleObservation(id.source,t,EpochMillis(100000+t),Observation.Available(v),Observation.Available(Gear.DRIVE),Observation.Available(true))
 private fun segment(a:Long,b:Long,v0:Double,v1:Double)=DrivingSegment(o(a,v0),o(b,v1),BigDecimal.valueOf(v0).add(BigDecimal.valueOf(v1)).multiply(BigDecimal.valueOf(b-a)).divide(BigDecimal(2000)))
 private fun input(segments:List<DrivingSegment>,before:RestBonusState=RestBonusState(500,2),departure:RestProof?=null):RestPricedCommand {
  val trip=ObservedTrip("math-drive",segments.first().from,Observation.Available(ZoneId.of("UTC")),ContractResult.Success(driving),segments.fold(BigDecimal.ZERO){a,s->a+s.meters},segments)
  val spans=segments.mapIndexed{i,s->RestSegmentEvidence(i,"original-epoch",RestObservedSpan("explicit-boot",s.from,s.to,missing,missing),policy)}
  val ctx=RestDriveContext(id,trip,rest,RestPricingPolicy.CHRONOLOGICAL_PREFIX_RATIONAL_V1,spans,RestClockDomain("original-epoch","explicit-boot"))
  val events=departure?.let{listOf(RestAnchoredDeparture(RequestId("actual-exit"),0,RestDeparturePacket(it,rest,policy,RestClockDomain("original-epoch","explicit-boot"))))}?:emptyList()
  val grant=before.lastStopNumber?.let{RestActiveGrant(it,rest.copy(bonusDurationMs=maxOf(500,before.remainingMs)))}
  return RestPricedCommand(ctx,events,before,grant,null,3)
 }
 private fun price(c:RestPricedCommand):RestPriceCalculation {val r=RestDrivingRecords.calculate(c);assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
 @Test fun accelerationExpiryUsesIntegratedDistanceInsteadOfHalfWholeDistance(){
  val p=price(input(listOf(segment(0,1000,0.0,20.0))))
  assertEquals(10L,p.price.settlement.rewardedMeters);assertEquals(GameMoney(11),p.price.settlement.amount);assertEquals(RestDistanceFraction(BigInteger.valueOf(5),BigInteger.valueOf(2)),p.price.slices.single().rawMeters);assertEquals(0L,p.timeline.after.remainingMs)
 }
 @Test fun chronologicalCapClipsBeforeRoundingButDoesNotPreserveUsedTime(){
  val c=input(listOf(segment(0,1000,0.0,20.0)));val p=price(c.copy(dayBefore=DrivingDay(c.context.trip.confirmed(id.source)!!.departedOn,499994)))
  assertEquals(6L,p.price.settlement.rewardedMeters);assertEquals(GameMoney(7),p.price.settlement.amount);assertEquals(1000L,p.timeline.operations.filterIsInstance<RestTimelineOperation.Use>().sumOf{it.elapsedMs});assertEquals(0L,p.timeline.after.remainingMs)
 }
 @Test fun gapDoesNotConsumeTimeAndFractionalSegmentsRoundOncePerDrive(){
  val p=price(input(listOf(segment(0,1000,10.0,10.0),segment(10000,11000,10.0,10.0)),RestBonusState(1500,2)))
  assertEquals(GameMoney(27),p.price.settlement.amount);assertEquals(2000L,p.timeline.operations.filterIsInstance<RestTimelineOperation.Use>().sumOf{it.elapsedMs})
  assertEquals(GameMoney(1),price(input(listOf(segment(0,500,1.0,1.0),segment(500,1000,1.0,1.0)))).price.settlement.amount)
 }
 @Test fun noBuffMatchesExistingFormulaAndZeroHasNoSyntheticMoneyChange(){
  val c=input(listOf(segment(0,1000,0.0,20.0)),RestBonusState.NONE);val p=price(c)
  assertEquals((DrivingIncome(driving).settle(null,c.context.trip.confirmed(id.source)!!) as ContractResult.Success).value,p.price.settlement)
  val low=c.copy(context=c.context.copy(trip=c.context.trip.copy(rules=ContractResult.Success(driving.copy(incomePerKm=GameMoney(1))))))
  val zero=price(low);assertEquals(GameMoney(0),zero.price.settlement.amount);assertNull(zero.price.settlement.change)
 }
 @Test fun StaleGrantOrUnmatchedRawSpanCannotBecomePricedWindows(){
  val c=input(listOf(segment(0,1000,10.0,10.0)))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),RestDrivingRecords.calculate(c.copy(grantBefore=c.grantBefore!!.copy(stopNumber=3))))
  val e=c.context.segments.single();val changed=e.copy(span=e.span.copy(to=o(1000,20.0)))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),RestDrivingRecords.calculate(c.copy(context=c.context.copy(segments=listOf(changed)))))
 }
 @Test fun oldGrantBeforeActualExitAndRefreshedGrantAfterExitStaySeparate(){
  fun area(t:Long,inside:Boolean)=Observation.Available(RestAreaProof("fixture-only",RestPlace.SERVICE_AREA,ContentVersion("fixture"),"https://example.test","test fixture","2026-11-10",id.source,inside,t,EpochMillis(100000+t)))
  fun parked(t:Long)=o(t,0.0).copy(gear=Observation.Available(Gear.PARK),uxRestricted=Observation.Available(false))
  val proof=RestProof(id,3,"math-drive",listOf(RestObservedSpan("explicit-boot",o(0,10.0),o(1000,10.0),missing,missing)),
   listOf(RestObservedSpan("explicit-boot",parked(1500),parked(2500),area(1500,true),area(2500,true))),
   RestObservedSpan("explicit-boot",parked(2500),o(4500,10.0),area(2500,true),area(4500,false)))
  val p=price(input(listOf(segment(3500,4500,10.0,10.0),segment(4500,5500,10.0,10.0)),departure=proof))
  assertEquals(GameMoney(25),p.price.settlement.amount);assertEquals(RestBonusState(0,3),p.timeline.after);assertEquals(listOf(2L,3L),p.timeline.operations.filterIsInstance<RestTimelineOperation.Use>().map{it.afterStop})
 }
}
