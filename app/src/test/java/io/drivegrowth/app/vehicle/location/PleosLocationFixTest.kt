package io.drivegrowth.app.vehicle.location
import ai.pleos.playground.location.fused.model.LocationData
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
class PleosLocationFixTest {
 @Test fun actualSdkFieldsArePreservedWithVehicleSourceAndSeparateReceiveClock(){
  val raw=LocationData(37.0,127.0,20.0,0f,0.7f,1234L)
  val result=pleosLocationFix(raw,9000,EpochMillis(100000));assertTrue(result is Observation.Available)
  val f=(result as Observation.Available).value;assertEquals(InputSource.VEHICLE,f.source);assertEquals(1234L,f.rawTimestampMillis);assertEquals(9000L,f.receivedElapsedMs);assertEquals(0.7f,f.rawConfidence);assertEquals(37.0,f.latitude,0.0)
 }
 @Test fun nullOrInvalidCoordinatesNeverBecomeOriginOrAPlace(){
  assertTrue(pleosLocationFix(null,0,EpochMillis(0)) is Observation.Missing)
  for(lat in listOf(Double.NaN,91.0))assertTrue(pleosLocationFix(LocationData(lat,127.0,0.0,0f,1f,1),0,EpochMillis(0)) is Observation.Missing)
 }
}
