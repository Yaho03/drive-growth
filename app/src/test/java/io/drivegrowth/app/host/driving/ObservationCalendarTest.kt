package io.drivegrowth.app.host.driving

import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ObservationCalendarTest {
 private fun sample(elapsed:Long)=VehicleObservation(InputSource.VEHICLE,elapsed,EpochMillis(10000+elapsed),Observation.Available(5.0),Observation.Available(Gear.DRIVE),Observation.Available(true))
 @Test fun originalZoneIsConsumedOnceAndDifferentObservationNeverMatches(){
  val calendar=ObservationCalendar(2);val original=sample(0);val zone=ZoneId.of("Asia/Seoul")
  calendar.capture(original,zone)
  assertTrue(calendar.take(original.copy(speedMps=Observation.Available(6.0))) is Observation.Missing)
  assertEquals(Observation.Available(zone),calendar.take(original))
  assertTrue(calendar.take(original) is Observation.Missing)
 }
 @Test fun ambiguousZoneAndEvictedEvidenceRemainExplicitlyMissing(){
  val calendar=ObservationCalendar(2);val a=sample(0)
  calendar.capture(a,ZoneId.of("UTC"));calendar.capture(a,ZoneId.of("Asia/Seoul"))
  assertTrue(calendar.take(a) is Observation.Missing)
  calendar.capture(a,ZoneId.of("UTC"));calendar.capture(sample(1),ZoneId.of("UTC"));calendar.capture(sample(2),ZoneId.of("UTC"))
  assertTrue(calendar.take(a) is Observation.Missing)
  assertEquals(Observation.Available(ZoneId.of("UTC")),calendar.take(sample(1)))
 }
}
