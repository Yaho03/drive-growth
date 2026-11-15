package io.drivegrowth.app.ui.driving

import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingRestrictionEvidenceTest {
    @Test fun movingEvidenceExpiresWithoutANewReadingAndCannotBeRestoredByAnOlderSample() {
        val evidence = DrivingRestrictionEvidence()
        evidence.accept(sample(10))
        assertTrue(evidence.confirmsMotion(InputSource.VEHICLE, 2_510))
        assertFalse(evidence.confirmsMotion(InputSource.VEHICLE, 2_511))
        evidence.accept(sample(9), observedAt = 2_511)
        assertFalse(evidence.confirmsMotion(InputSource.VEHICLE, 2_511))
        evidence.reset()
        assertFalse(evidence.confirmsMotion(InputSource.VEHICLE, 10))
    }

    @Test fun restrictedDisplayWithdrawsDrivingSceneForIncompleteStationaryAndUnknownReadings() {
        val evidence = DrivingRestrictionEvidence()
        evidence.accept(sample(1))
        assertTrue(evidence.confirmsMotion(InputSource.VEHICLE, 1))
        val invalid = listOf(
            sample(2).copy(speedMps = Observation.Missing(MissingReason.PERMISSION_DENIED)),
            sample(3).copy(gear = Observation.Missing(MissingReason.UNSUPPORTED)),
            sample(4).copy(uxRestricted = Observation.Missing(MissingReason.NO_DATA)),
            sample(5).copy(gear = Observation.Available(Gear.UNKNOWN)),
            sample(6).copy(gear = Observation.Available(Gear.PARK)),
            sample(7).copy(speedMps = Observation.Available(0.0)),
            sample(8).copy(speedMps = Observation.Available(Double.NaN)),
        )
        for (reading in invalid) {
            evidence.accept(reading)
            assertFalse(evidence.confirmsMotion(InputSource.VEHICLE, reading.receivedElapsedMs))
        }
        evidence.accept(sample(9).copy(gear = Observation.Available(Gear.REVERSE), speedMps = Observation.Available(-1.0)))
        assertTrue(evidence.confirmsMotion(InputSource.VEHICLE, 9))
    }

    @Test fun simulationUsesTheLocalReceiveClockWithoutBecomingVehicleEvidence() {
        val evidence = DrivingRestrictionEvidence()
        evidence.accept(sample(90_000).copy(source = InputSource.SIMULATION), observedAt = 20)
        assertTrue(evidence.confirmsMotion(InputSource.SIMULATION, 21))
        assertFalse(evidence.confirmsMotion(InputSource.SIMULATION, 19))
        assertFalse(evidence.confirmsMotion(InputSource.VEHICLE, 21))
        assertFalse(evidence.confirmsMotion(InputSource.SIMULATION, 2_521))
    }

    @Test fun dayAndNightAssetsUseStillFramesForVehicleOrReducedMotion() {
        assertEquals("pixel/driving/rrr-night-loop.webp", drivingSceneAsset(5, true, true))
        assertEquals("pixel/driving/rrr-day-loop.webp", drivingSceneAsset(6, true, true))
        assertEquals("pixel/driving/rrr-day-still.webp", drivingSceneAsset(18, true, false))
        assertEquals("pixel/driving/rrr-night-still.webp", drivingSceneAsset(19, false, true))
    }

    private fun sample(elapsed: Long) = VehicleObservation(
        InputSource.VEHICLE, elapsed, EpochMillis(elapsed),
        Observation.Available(2.0), Observation.Available(Gear.DRIVE), Observation.Available(true),
    )
}
