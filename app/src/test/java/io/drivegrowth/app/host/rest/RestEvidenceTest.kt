package io.drivegrowth.app.host.rest

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.junit.Test
import org.junit.Assert.*

class RestEvidenceTest {
 private val source=InputSource.SIMULATION
 private val identity=GameIdentity(ProfileId("explicit-fixture"),source)
 private val rules=RestRules(RuleVersion("fixture-rest"),3600000,300000,1200000,150)
 private val missing=Observation.Missing(MissingReason.NO_DATA)
 private val validator=RestEvidenceValidator(RestEvidencePolicy(1000,1000,100,0.5,0.1))
 private fun obs(t:Long,p:Boolean)=VehicleObservation(source,t,EpochMillis(10000000+t),Observation.Available(if(p)0.0 else 10.0),Observation.Available(if(p)Gear.PARK else Gear.DRIVE),Observation.Available(!p))
 private fun place(t:Long,inside:Boolean)=Observation.Available(RestAreaProof("fixture-only",RestPlace.SERVICE_AREA,ContentVersion("fixture"),"https://example.test/fixture","explicit simulation","2026-11-10",source,inside,t,EpochMillis(10000000+t)))
 private fun span(t:Long,p:Boolean,known:Boolean=true)=RestObservedSpan("fixture-boot",obs(t,p),obs(t+1000,p),if(known)place(t,true)else missing,if(known)place(t+1000,true)else missing)
 private fun proof(offset:Long=1000)=RestProof(identity,8,"original-drive",(0L until 3600000L step 1000).map{span(it,false,false)},(3600000L+offset until 3900000L+offset step 1000).map{span(it,true)},RestObservedSpan("fixture-boot",obs(3900000+offset,true),obs(3901000+offset,false),place(3900000+offset,true),place(3901000+offset,false)))
 private fun stop(p:RestProof):RestStop{val r=validator.stop(p);assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
 @Test fun continuousExplicitSimulationProofQualifiesOnlyAtRealAreaExit(){
  val s=stop(proof());assertEquals(Observation.Available(3600000L),s.drivenMs);assertEquals(Observation.Available(300000L),s.parkedMs)
  val result=RestBonus(rules).depart(RestBonusState.NONE,s);assertEquals(RestOutcome.ACCEPTED,result.outcome);assertEquals(1200000L,result.state.remainingMs)
 }
 @Test fun ordinaryParkMissingPlaceOrStillInsideNeverStartsBuff(){
  val p=proof();val last=p.departure
  assertTrue(validator.stop(p.copy(departure=last.copy(toPlace=place(3901000,true)))) is ContractResult.Failure)
  val unknown=p.copy(parked=p.parked.map{it.copy(fromPlace=missing,toPlace=missing)})
  assertNotEquals(RestOutcome.ACCEPTED,RestBonus(rules).classify(stop(unknown)))
 }
 @Test fun gapRebootForeignSourceAndReversedClockCannotQualify(){
  val p=proof()
  val cases=listOf(p.copy(driving=p.driving.drop(1)),p.copy(parked=p.parked.drop(100)),p.copy(driving=p.driving.mapIndexed{i,s->if(i==3)s.copy(bootId="other-boot")else s}),p.copy(departure=p.departure.copy(to=p.departure.to.copy(source=InputSource.VEHICLE))))
  assertEquals(RestOutcome.DRIVING_TOO_SHORT,RestBonus(rules).classify(stop(cases[0])))
  assertTrue(validator.stop(cases[1]) is ContractResult.Failure)
  for(c in cases.drop(2))assertTrue(validator.stop(c) is ContractResult.Failure)
 }
 @Test fun oneMissingInteriorSampleDoesNotBecomeFiveMinuteParking(){
  val p=proof();val spans=p.parked.toMutableList();val s=spans[50];spans[50]=s.copy(to=s.to.copy(speedMps=Observation.Missing(MissingReason.NO_DATA)))
  assertTrue(validator.stop(p.copy(parked=spans)) is ContractResult.Failure)
 }

 @Test fun sameClockWithContradictingDrivingAndParkSignalsCannotProvePhaseTransition(){
  assertTrue(validator.stop(proof(0)) is ContractResult.Failure)
 }

 @Test fun shortDStandstillInsideConfirmedTripDoesNotTurnIntoParking(){
  val p=proof();val list=p.driving.toMutableList();val zero=list[1000].from.copy(speedMps=Observation.Available(0.0))
  list[999]=list[999].copy(to=zero);list[1000]=list[1000].copy(from=zero)
  assertEquals(RestOutcome.ACCEPTED,RestBonus(rules).classify(stop(p.copy(driving=list))))
 }
}
