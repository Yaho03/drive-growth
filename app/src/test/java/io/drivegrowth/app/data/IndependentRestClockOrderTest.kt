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

class IndependentRestClockOrderTest {
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

 @Test fun returningToSameClockDomainMustRetainThatDomainsUtcCursor(){
  val a=span(0,1000);val b=span(2000,3000)
  val returned=span(4000,5000).let{it.copy(from=it.from.copy(receivedAt=EpochMillis(90000)),to=it.to.copy(receivedAt=EpochMillis(91000)))}
  val c=ctx(listOf(a,b,returned)).let{it.copy(segments=it.segments.mapIndexed{i,e->if(i==1)e.copy(clockDomain="receiver-2")else e})}
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),RestTimeline.plan(c,emptyList(),RestBonusState(2000,8),RestActiveGrant(8,old)))
 }
 @Test fun receiverSwitchAtEqualElapsedWithConflictingRawIsRejected(){
  val a=ctx(listOf(span(0,1000)))
  val b=ctx(listOf(span(1000,2000))).let{it.copy(trip=it.trip.copy(id="next",departure=it.trip.departure.copy(receivedAt=EpochMillis(101001))),departureClock=RestClockDomain("receiver-2","boot"),segments=it.segments.map{e->e.copy(clockDomain="receiver-2",span=e.span.copy(from=e.span.from.copy(receivedAt=EpochMillis(101001))))})}
  assertFalse(RestTimeline.clockOrder(listOf(a,b)))
 }
 @Test fun differentReceiverCannotReuseEvenOneMillisecondOfPhysicalInterval(){
  val a=ctx(listOf(span(0,1000)))
  val b=ctx(listOf(span(999,2000))).let{it.copy(trip=it.trip.copy(id="next"),departureClock=RestClockDomain("receiver-2","boot"),segments=it.segments.map{e->e.copy(clockDomain="receiver-2")})}
  assertFalse(RestTimeline.clockOrder(listOf(a,b)))
 }
}
