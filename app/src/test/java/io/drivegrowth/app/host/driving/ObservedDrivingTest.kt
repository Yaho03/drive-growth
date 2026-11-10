package io.drivegrowth.app.host.driving
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.junit.Test
import org.junit.Assert.*
import java.math.BigDecimal
import java.time.*
class ObservedDrivingTest {
 private val start=Instant.parse("2026-11-09T14:59:59Z").toEpochMilli()
 private val actor=GameIdentity(ProfileId("p"),InputSource.VEHICLE)
 private val rules=ContractResult.Success(DrivingIncomeRules(RuleVersion("fixture"),500000,GameMoney(10)))
 private fun initial()=RecordingState(actor,0,null,null,null,null,null,emptyList())
 private fun reducer()=ObservedDrivingReducer(RecordingPolicy(2500,2500,1000,3000,0.1,0.5)){"stable-trip"}
 private fun frame(ms:Long,speed:Double,gear:Gear=Gear.DRIVE,ux:Boolean=true)=DrivingFrame(VehicleObservation(InputSource.VEHICLE,ms,EpochMillis(start+ms),Observation.Available(speed),Observation.Available(gear),Observation.Available(ux)),Observation.Available(ZoneId.of("Asia/Seoul")))
 private fun ok(r:ContractResult<RecordingState>):RecordingState {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
 private fun ingest(r:ObservedDrivingReducer,s:RecordingState,f:DrivingFrame,receiver:String="epoch")=ok(r.observe(s,receiver,f,RecordingClock(f.observation.receivedElapsedMs,f.observation.receivedAt),rules))
 @Test fun confirmedSpeedIntervalsUseOneStableDepartureAndSealOnlyAfterConfirmedPark(){
  val r=reducer();var s=ingest(r,initial(),frame(0,10.0));assertNotNull(s.trip);s=ingest(r,s,frame(1000,10.0));assertEquals(0,BigDecimal(10).compareTo(s.trip!!.meters))
  s=ingest(r,s,frame(2000,0.0));assertTrue(s.outbox.isEmpty());assertNotNull(s.trip)
  for(t in 3000L..6000L step 1000)s=ingest(r,s,frame(t,0.0,Gear.PARK,false))
  assertNull(s.trip);assertEquals(15L,s.outbox.single().confirmed(InputSource.VEHICLE)!!.distance.meters)
  assertEquals(LocalDate.parse("2026-11-09"),s.outbox.single().confirmed(InputSource.VEHICLE)!!.departedOn)
 }
 @Test fun signalGapDoesNotExtendPreviousSpeedAndDuplicateIsNoExtraDistance(){
  val r=reducer();var s=ingest(r,initial(),frame(0,10.0));assertNotNull(s.trip);s=ingest(r,s,frame(1000,10.0));val duplicate=ingest(r,s,frame(1000,10.0));assertEquals(s,duplicate)
  s=ingest(r,s,frame(10000,10.0));s=ingest(r,s,frame(11000,10.0));assertEquals(0,BigDecimal(20).compareTo(s.trip!!.meters));assertTrue(s.trip!!.gaps.any{it.reason==DrivingGapReason.SIGNAL_GAP})
 }
 @Test fun missingSpeedAndProcessRecoveryNeverBridgeUnobservedInterval(){
  val r=reducer();var s=ingest(r,initial(),frame(0,10.0));assertNotNull(s.trip);s=ingest(r,s,frame(1000,10.0));val f=frame(2000,10.0);s=ingest(r,s,f.copy(observation=f.observation.copy(speedMps=Observation.Missing(MissingReason.NO_DATA))))
  s=ingest(r,s,frame(3000,10.0));s=ingest(r,s,frame(4000,10.0),"new-process");s=ingest(r,s,frame(5000,10.0),"new-process")
  assertEquals(0,BigDecimal(20).compareTo(s.trip!!.meters));assertEquals("stable-trip",s.trip!!.id);assertTrue(s.trip!!.gaps.isNotEmpty())
 }
 @Test fun sourceMismatchIsRejectedAndMissingZoneIsNotReplacedByCurrentZone(){
  val r=reducer();val f=frame(0,10.0);assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),r.observe(initial(),"epoch",f.copy(observation=f.observation.copy(source=InputSource.SIMULATION)),RecordingClock(0,EpochMillis(start)),rules))
  val s=ingest(r,initial(),f.copy(zone=Observation.Missing(MissingReason.NO_DATA)));assertNotNull(s.trip);assertNull(s.trip!!.confirmed(InputSource.VEHICLE))
 }

 @Test fun reversedSampleCannotCountAnAlreadyObservedIntervalAgain(){
  val r=reducer();var s=ingest(r,initial(),frame(0,10.0));s=ingest(r,s,frame(1000,10.0))
  s=ingest(r,s,frame(500,10.0));s=ingest(r,s,frame(1500,10.0))
  assertEquals(0,BigDecimal(10).compareTo(s.trip!!.meters))
  assertTrue(s.trip!!.gaps.any{it.reason==DrivingGapReason.CLOCK_REVERSED})
 }
 @Test fun stoppedReceiverDoesNotBridgePauseAndRetainsOriginalRulesAndZone(){
  val r=reducer();var s=ingest(r,initial(),frame(0,10.0));s=ingest(r,s,frame(1000,10.0))
  s=ok(r.pause(s,"epoch",RecordingClock(1500,EpochMillis(start+1500))))
  val changed=frame(2000,10.0).copy(zone=Observation.Available(ZoneId.of("UTC")))
  s=ok(r.observe(s,"new",changed,RecordingClock(2000,EpochMillis(start+2000)),ContractResult.Failure(ContractError.POLICY_UNRESOLVED)))
  assertEquals(0,BigDecimal(10).compareTo(s.trip!!.meters));assertEquals(rules,s.trip!!.rules)
  assertEquals(Observation.Available(ZoneId.of("Asia/Seoul")),s.trip!!.zone)
  assertEquals("stable-trip",s.trip!!.id);assertTrue(s.trip!!.gaps.any{it.reason==DrivingGapReason.APP_STOPPED})
 }
 @Test fun subMeterIntervalsAreOnlyRoundedAtSeal(){
  val r=reducer();var s=ingest(r,initial(),frame(0,0.6))
  for(t in 1000L..2000L step 1000)s=ingest(r,s,frame(t,0.6))
  for(t in 3000L..6000L step 1000)s=ingest(r,s,frame(t,0.0,Gear.PARK,false))
  assertEquals(1L,s.outbox.single().confirmed(InputSource.VEHICLE)!!.distance.meters)
  assertEquals(0,BigDecimal("1.5").compareTo(s.outbox.single().meters))
 }
 @Test fun staleOrClockSkewedInputBreaksBothDistanceAndParkContinuity(){
  val r=reducer();var s=ingest(r,initial(),frame(0,10.0));s=ingest(r,s,frame(1000,10.0))
  val old=frame(2000,10.0)
  s=ok(r.observe(s,"epoch",old,RecordingClock(5000,EpochMillis(start+5000)),rules))
  assertNull(s.anchor);assertEquals(DrivingGapReason.STALE,s.trip!!.gaps.last().reason)
  s=ingest(r,s,frame(6000,0.0,Gear.PARK,false));s=ingest(r,s,frame(7000,0.0,Gear.PARK,false))
  val skew=frame(8000,0.0,Gear.PARK,false);s=ingest(r,s,skew.copy(observation=skew.observation.copy(receivedAt=EpochMillis(start+18000))))
  assertTrue(s.outbox.isEmpty());assertNull(s.parkSince)
  assertEquals(0,BigDecimal(10).compareTo(s.trip!!.meters))
 }
 @Test fun neutralAndLowSpeedNeverInventDepartureAndMissingUxCannotSeal(){
  val r=reducer();assertNull(ingest(r,initial(),frame(0,10.0,Gear.NEUTRAL)).trip)
  assertNull(ingest(r,initial(),frame(0,0.2)).trip)
  var s=ingest(r,initial(),frame(0,10.0))
  for(t in 1000L..5000L step 1000){val f=frame(t,0.0,Gear.PARK,false);s=ingest(r,s,f.copy(observation=f.observation.copy(uxRestricted=Observation.Missing(MissingReason.NO_DATA))))}
  assertTrue(s.outbox.isEmpty());assertNull(s.parkSince);assertNotNull(s.trip)
 }

 @Test fun multipleLateSamplesAfterReversalStayBelowWatermarkAndNeverRecount(){
  val r=reducer();var s=ingest(r,initial(),frame(0,10.0));s=ingest(r,s,frame(1000,10.0))
  for(t in listOf(500L,600L,700L,1500L))s=ingest(r,s,frame(t,10.0))
  assertEquals(0,BigDecimal(10).compareTo(s.trip!!.meters))
  s=ingest(r,s,frame(2000,10.0));assertEquals(0,BigDecimal(15).compareTo(s.trip!!.meters))
 }

 @Test fun pauseClockReversalCannotLowerTheLastConfirmedBoundary(){
  val r=reducer();var s=ingest(r,initial(),frame(0,10.0));s=ingest(r,s,frame(1000,10.0))
  s=ok(r.pause(s,"epoch",RecordingClock(500,EpochMillis(start+500))))
  for(t in listOf(600L,700L,1500L))s=ingest(r,s,frame(t,10.0))
  assertEquals(0,BigDecimal(10).compareTo(s.trip!!.meters))
 }

 @Test fun recordedTripCannotBeRelabeledAsAnotherInputSource(){
  val r=reducer();val s=ingest(r,initial(),frame(0,10.0))
  assertNull(s.trip!!.confirmed(InputSource.SIMULATION))
 }

 @Test fun segmentKeepsOriginalSpeedGearUxAndObservationClocksForAudit(){
  val r=reducer();val first=frame(0,10.0);val last=frame(1000,12.0,Gear.REVERSE,true)
  val s=ingest(r,ingest(r,initial(),first),last)
  assertEquals(first.observation,s.trip!!.segments.single().from)
  assertEquals(last.observation,s.trip!!.segments.single().to)
  assertEquals(0,BigDecimal(11).compareTo(s.trip!!.meters))
 }
}
