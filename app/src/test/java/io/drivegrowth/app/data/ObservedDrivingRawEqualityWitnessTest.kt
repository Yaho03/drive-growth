package io.drivegrowth.app.data
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import org.junit.Test
import org.junit.Assert.*
class ObservedDrivingRawEqualityWitnessTest {
 @Test fun distinctRawNanGapBitsCompareEqualAsWholeTrips() {
  val sample=VehicleObservation(InputSource.SIMULATION,0,EpochMillis(1000),Observation.Available(10.0),Observation.Available(Gear.DRIVE),Observation.Available(true))
  fun trip(bits:Long)=ObservedTrip("one",sample,Observation.Missing(MissingReason.NO_DATA),ContractResult.Failure(ContractError.DATA_UNAVAILABLE),gaps=listOf(DrivingGap(DrivingGapReason.INVALID_SIGNAL,null,RecordingClock(100,EpochMillis(1100)),sample.copy(speedMps=Observation.Available(Double.fromBits(bits))))))
  val a=trip(0x7ff8000000000042L);val b=trip(0x7ff8000000000043L)
  assertNotEquals((a.gaps.single().observation!!.speedMps as Observation.Available).value.toRawBits(),(b.gaps.single().observation!!.speedMps as Observation.Available).value.toRawBits())
  assertEquals(a,b) // 현재 removed-outbox 비교가 raw provenance 차이를 못 보는 사실을 입증한다.
 }
}
