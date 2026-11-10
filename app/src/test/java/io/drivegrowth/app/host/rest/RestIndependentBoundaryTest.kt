package io.drivegrowth.app.host.rest
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.junit.Assert.*
import org.junit.Test
class RestIndependentBoundaryTest {
 private val identity=GameIdentity(ProfileId("independent-rest"),InputSource.SIMULATION)
 private val policy=RestEvidencePolicy(1000,1000,100,0.5,0.1)
 private val missing=Observation.Missing(MissingReason.NO_DATA)
 private fun sample(t:Long,speed:Double=10.0,gear:Gear=Gear.DRIVE)=VehicleObservation(identity.source,t,EpochMillis(100000+t),Observation.Available(speed),Observation.Available(gear),Observation.Available(gear!=Gear.PARK))
 private fun span(t:Long)=RestObservedSpan("boot",sample(t),sample(t+1000),missing,missing)
 @Test fun conflictingTouchingSamplesCannotConsumeAsContinuousKnownEvidence(){
  val first=span(0);val second=span(1000).copy(from=sample(1000,40.0))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),RestUsePlanner(policy).plan(identity,RestBonusState(5000,8),listOf(first,second)))
 }
 @Test fun sameBoundaryObservationSupportsExactMidSegmentExpiry(){
  val result=RestUsePlanner(policy).plan(identity,RestBonusState(1500,8),listOf(span(0),span(1000))) as ContractResult.Success
  assertEquals(2000L,result.value.observedMs);assertEquals(listOf(1000L,500L),result.value.slices.map{it.boostedMs});assertEquals(0L,result.value.remainingMs)
 }
 @Test fun absentIntervalsDoNotConsumeBuffAndNewBootIsRejected(){
  val result=RestUsePlanner(policy).plan(identity,RestBonusState(5000,8),listOf(span(0),span(10000))) as ContractResult.Success
  assertEquals(2000L,result.value.observedMs);assertEquals(3000L,result.value.remainingMs)
  assertTrue(RestUsePlanner(policy).plan(identity,RestBonusState(5000,8),listOf(span(0),span(10000).copy(bootId="other"))) is ContractResult.Failure)
 }
 @Test fun parkedNeutralAndForeignSourceCannotConsumeDrivingBuff(){
  for(gear in listOf(Gear.PARK,Gear.NEUTRAL)) {
   val stopped=span(0).copy(from=sample(0,0.0,gear),to=sample(1000,0.0,gear))
   assertTrue(RestUsePlanner(policy).plan(identity,RestBonusState(1000,8),listOf(stopped)) is ContractResult.Failure)
  }
  val other=span(0).copy(from=sample(0).copy(source=InputSource.VEHICLE))
  assertTrue(RestUsePlanner(policy).plan(identity,RestBonusState(1000,8),listOf(other)) is ContractResult.Failure)
 }
 private fun area(t:Long,inside:Boolean)=Observation.Available(RestAreaProof("test-only",RestPlace.SERVICE_AREA,ContentVersion("independent"),"https://example.test","test licence","2026-11-10",identity.source,inside,t,EpochMillis(100000+t)))
 private fun departure()=RestObservedSpan("boot",sample(10000,0.0,Gear.PARK),sample(11000),area(10000,true),area(11000,false))
 @Test fun missingPlaceOrNotActuallyOutsideCannotProveDeparture(){
  val v=RestEvidenceValidator(policy);val d=departure();val p=RestProof(identity,1,"drive",emptyList(),emptyList(),d)
  assertTrue(v.stop(p.copy(departure=d.copy(fromPlace=missing))) is ContractResult.Failure)
  assertTrue(v.stop(p.copy(departure=d.copy(toPlace=area(11000,true)))) is ContractResult.Failure)
 }
 @Test fun staleOrFutureLocationProofCannotBecomeRestPlace(){
  val v=RestEvidenceValidator(policy);val d=departure();val p=RestProof(identity,1,"drive",emptyList(),emptyList(),d)
  assertTrue(v.stop(p.copy(departure=d.copy(fromPlace=area(0,true)))) is ContractResult.Failure)
  assertTrue(v.stop(p.copy(departure=d.copy(fromPlace=area(10001,true)))) is ContractResult.Failure)
 }
 @Test fun knownExitWithoutDrivingAndParkingCoverageIsStillUnconfirmed(){
  val result=RestEvidenceValidator(policy).stop(RestProof(identity,1,"drive",emptyList(),emptyList(),departure())) as ContractResult.Success
  assertEquals(RestOutcome.UNCONFIRMED,RestBonus(RestRules(RuleVersion("test"),3600000,300000,1200000,150)).classify(result.value))
 }
}
