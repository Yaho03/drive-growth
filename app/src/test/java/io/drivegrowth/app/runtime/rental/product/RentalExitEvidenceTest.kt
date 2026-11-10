package io.drivegrowth.app.runtime.rental.product

import io.drivegrowth.app.host.driving.RecordingPolicy
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class RentalExitEvidenceTest {
    private val policy = RecordingPolicy(2500, 2500, 1000, 3000, 0.1, 0.5)
    private val since = RentalClock("boot", EpochMillis(10000), 5000)
    private val drive = VehicleObservation(InputSource.SIMULATION, 4900, EpochMillis(9900),
        Observation.Available(2.0), Observation.Available(Gear.DRIVE), Observation.Available(true))
    private fun mode(input: VehicleObservation? = drive, trip: Boolean = true, end: RentalClock? = null,
        configuration: Boolean = false) = rentalExitMode(configuration, InputSource.SIMULATION, since, input, trip, end, policy)

    @Test fun freshActualDrivingNeedsNoInventedPhysicalEnd() {
        assertEquals(RentalExitMode.DRIVING, mode())
        assertNull(mode(trip = false))
    }
    @Test fun configurationChangeNeverCreatesEitherExitMode() {
        assertNull(mode(configuration = true))
        assertNull(mode(parked(), false, since, true))
    }
    private fun parked() = drive.copy(speedMps = Observation.Available(0.0), gear = Observation.Available(Gear.PARK))
    @Test fun currentParkOrMissingOriginIsNotDrivingAndDoesNotCreateParkedOrigin() {
        assertNull(mode(parked()))
        assertEquals(RentalExitMode.KNOWN_PARKED, mode(parked(), false, since.copy(epoch = EpochMillis(9000), elapsedMs = 4000)))
        assertNull(mode(parked(), false, since.copy(bootId = "other")))
    }
    @Test fun staleMissingForeignAndInvalidSpeedCannotSelectDrivingExit() {
        for (input in listOf(null, drive.copy(receivedElapsedMs = 0), drive.copy(source = InputSource.VEHICLE),
            drive.copy(speedMps = Observation.Missing(MissingReason.NO_DATA)),
            drive.copy(speedMps = Observation.Available(Double.NaN)), drive.copy(gear = Observation.Available(Gear.UNKNOWN))))
            assertNull(mode(input))
    }
}
