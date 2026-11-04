package io.drivegrowth.core.contract

import io.drivegrowth.core.Gear
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleObservationTest {
    @Test
    fun `속도와 UX 중 한 신호가 없으면 주차 후보가 아니다`() {
        val parked = RentalContractFixtures.parkedVehicle()
        assertTrue(parked.isParkCandidate(maxStationarySpeedMps = 0.1))
        assertFalse(parked.copy(gear = Observation.Available(Gear.DRIVE)).isParkCandidate(0.1))
        assertFalse(parked.copy(speedMps = Observation.Missing(MissingReason.PERMISSION_DENIED)).isParkCandidate(0.1))
        assertFalse(parked.copy(uxRestricted = Observation.Available(true)).isParkCandidate(0.1))
        assertFalse(parked.copy(source = InputSource.SIMULATION).isParkCandidate(0.1))
        assertFalse(parked.copy(speedMps = Observation.Available(Double.NaN)).isParkCandidate(0.1))
    }

    @Test
    fun `도착 증거는 같은 경로와 출처에만 적용한다`() {
        val arrival = DeliveryArrivalEvidence(RouteId("route-2"), InputSource.VEHICLE, EpochMillis(300))
        assertTrue(arrival.matches(RouteId("route-2"), InputSource.VEHICLE))
        assertFalse(arrival.matches(RouteId("route-1"), InputSource.VEHICLE))
        assertFalse(arrival.matches(RouteId("route-2"), InputSource.SIMULATION))
    }
}
