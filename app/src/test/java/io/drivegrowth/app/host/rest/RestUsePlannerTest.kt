package io.drivegrowth.app.host.rest
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.junit.Assert.*
import org.junit.Test
class RestUsePlannerTest {
 private val identity=GameIdentity(ProfileId("fixture"),InputSource.SIMULATION)
 private val policy=RestEvidencePolicy(1000,1000,100,0.5,0.1)
 private val missing=Observation.Missing(MissingReason.NO_DATA)
 private fun o(t:Long)=VehicleObservation(identity.source,t,EpochMillis(100000+t),Observation.Available(10.0),Observation.Available(Gear.DRIVE),Observation.Available(true))
 private fun s(t:Long)=RestObservedSpan("boot",o(t),o(t+1000),missing,missing)
 @Test fun expiryIsSplitWithinAnActualSegmentAndGapDoesNotConsumeTime(){
  val result=RestUsePlanner(policy).plan(identity,RestBonusState(1500,8),listOf(s(0),s(10000)))
  assertTrue(result.toString(),result is ContractResult.Success);val p=(result as ContractResult.Success).value
  assertEquals(2000L,p.observedMs);assertEquals(listOf(1000L,500L),p.slices.map{it.boostedMs});assertEquals(0L,p.remainingMs);assertEquals(8L,p.afterStop)
 }
 @Test fun overlappingSegmentsOrRebootCannotBecomeASecondConsumption(){
  val planner=RestUsePlanner(policy)
  for(spans in listOf(listOf(s(0),s(0)),listOf(s(0),s(1000).copy(bootId="new"))))
   assertTrue(planner.plan(identity,RestBonusState(5000,8),spans) is ContractResult.Failure)
 }
 @Test fun noBuffIsNotInventedByConfirmedDriving(){
  val r=RestUsePlanner(policy).plan(identity,RestBonusState.NONE,listOf(s(0)))
  assertTrue(r is ContractResult.Success);val p=(r as ContractResult.Success).value;assertEquals(0L,p.remainingMs);assertEquals(0L,p.slices.single().boostedMs)
 }

 @Test fun stoppedDInConfirmedDriveIsNotReclassifiedAsParkOrUnavailable(){
  val base=s(0);val stopped=base.copy(from=base.from.copy(speedMps=Observation.Available(0.0)),to=base.to.copy(speedMps=Observation.Available(0.0)))
  val r=RestUsePlanner(policy).plan(identity,RestBonusState(1000,8),listOf(stopped))
  assertTrue(r.toString(),r is ContractResult.Success);assertEquals(0L,(r as ContractResult.Success).value.remainingMs)
 }
}
