package io.drivegrowth.app.data

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.rest.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId

class RestTimelineTest {
 private val id=GameIdentity(ProfileId("timeline"),InputSource.SIMULATION)
 private val rules=RestRules(RuleVersion("original"),1000,1000,600,150)
 private val old=rules.copy(ruleVersion=RuleVersion("old"),bonusDurationMs=2000,bonusPercent=200)
 private val policy=RestEvidencePolicy(1500,1500,100,0.5,0.1)
 private val missing=Observation.Missing(MissingReason.NO_DATA)
 private fun o(t:Long,gear:Gear=Gear.DRIVE)=VehicleObservation(id.source,t,EpochMillis(100000+t),Observation.Available(if(gear==Gear.PARK)0.0 else 10.0),Observation.Available(gear),Observation.Available(gear!=Gear.PARK))
 private fun span(a:Long,b:Long)=RestObservedSpan("boot",o(a),o(b),missing,missing)
 private fun ctx(spans:List<RestObservedSpan>):RestDriveContext {
  val segments=spans.map{DrivingSegment(it.from,it.to,BigDecimal.valueOf(((it.from.speedMps as Observation.Available).value+(it.to.speedMps as Observation.Available).value)*(it.to.receivedElapsedMs-it.from.receivedElapsedMs)/2000))}
  val trip=ObservedTrip("one-original-drive",spans.first().from,Observation.Available(ZoneId.of("Asia/Seoul")),ContractResult.Success(DrivingIncomeRules(rules.ruleVersion,500000,GameMoney(10))),segments.fold(BigDecimal.ZERO){a,b->a+b.meters},segments)
  return RestDriveContext(id,trip,rules,RestPricingPolicy.CHRONOLOGICAL_PREFIX_RATIONAL_V1,spans.mapIndexed{i,s->RestSegmentEvidence(i,"receiver-1",s,policy)},RestClockDomain("receiver-1","boot"))
 }
 private fun area(t:Long,inside:Boolean)=Observation.Available(RestAreaProof("area",RestPlace.SERVICE_AREA,ContentVersion("dataset"),"https://example.test/source","licence","2026-11-10",id.source,inside,t,EpochMillis(100000+t)))
 private fun departure(c:RestDriveContext,segment:Int=1,r:RestRules=rules):RestAnchoredDeparture {
  val parked=RestObservedSpan("boot",o(1500,Gear.PARK),o(2500,Gear.PARK),area(1500,true),area(2500,true))
  val exit=RestObservedSpan("boot",parked.to,c.trip.segments[segment].to,area(2500,true),area(c.trip.segments[segment].to.receivedElapsedMs,false))
  return RestAnchoredDeparture(RequestId("rest-exit"),segment,RestDeparturePacket(RestProof(id,9,c.trip.id,listOf(span(0,1000)),listOf(parked),exit),r,policy,RestClockDomain("receiver-1","boot")))
 }
 private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
 @Test fun expiryUsesKnownDTimeAndDoesNotConsumeGapOrPTransition(){
  val p=ok(RestTimeline.plan(ctx(listOf(span(0,1000),span(10000,11000))),emptyList(),RestBonusState(1500,8),RestActiveGrant(8,old)))
  assertEquals(0L,p.after.remainingMs);assertEquals(1500L,p.boostedMs);assertEquals(listOf(1000L,500L),p.boosts.map{it.toMs});assertEquals(listOf(1000L,1000L),p.operations.filterIsInstance<RestTimelineOperation.Use>().map{it.elapsedMs})
 }
 @Test fun carryThenActualExitRefreshThenNewUseKeepOneOriginalDrive(){
  val transition=RestObservedSpan("boot",o(2500,Gear.PARK),o(3500),missing,missing)
  val c=ctx(listOf(span(0,1000),transition,span(3500,4500)))
  val p=ok(RestTimeline.plan(c,listOf(departure(c)),RestBonusState(500,8),RestActiveGrant(8,old)))
  assertEquals(listOf(RestBoostSlice(0,0,500,200),RestBoostSlice(2,0,600,150)),p.boosts)
  assertEquals(1100L,p.boostedMs);assertEquals(RestBonusState(0,9),p.after)
  assertEquals(listOf(8L,9L),p.operations.filterIsInstance<RestTimelineOperation.Use>().map{it.afterStop})
  assertEquals(listOf("rest/use/one-original-drive/0","rest/use/one-original-drive/2"),p.operations.filterIsInstance<RestTimelineOperation.Use>().map{it.consumeId})
 }
 @Test fun rejectedLaterStopDoesNotReplaceAcceptedGrantRules(){
  val transition=RestObservedSpan("boot",o(2500,Gear.PARK),o(3500),missing,missing);val c=ctx(listOf(span(0,1000),transition,span(3500,4500)))
  val p=ok(RestTimeline.plan(c,listOf(departure(c,r=rules.copy(minParkedMs=2000))),RestBonusState(2000,8),RestActiveGrant(8,old)))
  assertEquals(listOf(200,200),p.boosts.map{it.percent});assertEquals(RestActiveGrant(8,old),p.activeGrant);assertEquals(9L,p.after.lastStopNumber)
  assertEquals(RestOutcome.PARKING_TOO_SHORT,p.operations.filterIsInstance<RestTimelineOperation.Depart>().single().result.outcome)
 }
 @Test fun overlappingRawSegmentsOrAlteredEndpointCannotConsumeAgain(){
  val c=ctx(listOf(span(0,1000),span(0,1000)))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),RestTimeline.plan(c,emptyList(),RestBonusState(1000,8),RestActiveGrant(8,old)))
  val valid=ctx(listOf(span(0,1000)));val e=valid.segments.single()
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),RestTimeline.plan(valid.copy(segments=listOf(e.copy(span=e.span.copy(to=o(999))))),emptyList(),RestBonusState.NONE,null))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),RestTimeline.plan(valid.copy(departureClock=RestClockDomain("other","boot")),emptyList(),RestBonusState.NONE,null))
 }
 @Test fun activeStateWithoutOriginalAcceptedGrantDoesNotBecomeBaseOnly(){
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),RestTimeline.plan(ctx(listOf(span(0,1000))),emptyList(),RestBonusState(1,8),null))
 }
 @Test fun confirmedDStandstillConsumesTimeWithoutInventingDistance(){
  val s=span(0,1000);val stopped=s.copy(from=s.from.copy(speedMps=Observation.Available(0.0)),to=s.to.copy(speedMps=Observation.Available(0.0)))
  val p=ok(RestTimeline.plan(ctx(listOf(stopped)),emptyList(),RestBonusState(500,8),RestActiveGrant(8,old)))
  assertEquals(0L,p.after.remainingMs);assertEquals(500L,p.boostedMs)
 }
}
