package io.drivegrowth.app.ui.rest

import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.driving.RestOutcome
import io.drivegrowth.core.driving.RestPlace
import org.junit.Assert.*
import org.junit.Test

class RestAreaSceneTest {
    private val arrived = RestAreaScene.Parked(
        InputSource.SIMULATION, "stop-1", RestPlace.SERVICE_AREA, null,
        RestAreaScene.ObservedParking(0, 300_000),
    )

    @Test fun remainingParkedPreservesStopAndUpdatesDisplay() {
        val resting = arrived.copy(progress = RestAreaScene.ObservedParking(60_000, 300_000))
        assertEquals(RestAreaScene.Phase.ARRIVED, arrived.phase)
        assertEquals(RestAreaScene.Phase.RESTING, resting.phase)
        assertEquals(arrived.stopId, resting.stopId)
        assertEquals(0.2f, resting.progress!!.fraction, 0f)
    }

    @Test fun fullObservedDurationDoesNotMakeBonusReady() {
        val full = arrived.copy(progress = RestAreaScene.ObservedParking(360_000, 300_000))
        assertEquals(1f, full.progress!!.fraction, 0f)
        assertFalse(full.ready)
        assertFalse(full.copy(outcome = RestOutcome.UNCONFIRMED).ready)
        assertFalse(full.copy(outcome = RestOutcome.DRIVING_TOO_SHORT).ready)
        assertTrue(full.copy(outcome = RestOutcome.ACCEPTED).ready)
    }

    @Test fun otherParkingCannotBeRenderedAsARestArea() {
        assertThrows(IllegalArgumentException::class.java) { arrived.copy(place = RestPlace.OTHER) }
        assertEquals(InputSource.VEHICLE, RestAreaScene.Unconfirmed(InputSource.VEHICLE).source)
    }
}
