package io.drivegrowth.app

import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.vehicle.ForegroundInteraction
import io.drivegrowth.core.Gear
import io.drivegrowth.core.InputSource
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleInteraction
import io.drivegrowth.core.contract.VehicleObservation
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AppShellTest {
    @Test fun interactionRequiresFreshContinuousVehicleParkAndUxPermission() {
        val gate = ForegroundInteraction()
        val allowed = Observation.Available(VehicleInteraction.ALLOWED)
        fun sample(time: Long, gear: Gear = Gear.PARK, ux: Boolean? = false,
                   source: InputSource = InputSource.VEHICLE) =
            VehicleObservation(source, time, EpochMillis(time), Observation.Available(0.0),
                Observation.Available(gear),
                ux?.let { Observation.Available(it) } ?: Observation.Missing(MissingReason.NO_DATA))
        assertNotEquals(allowed, gate.state(0))
        gate.accept(sample(0, Gear.DRIVE))
        assertNotEquals(allowed, gate.state(0))
        for (time in 1000L..4000L step 1000) gate.accept(sample(time))
        assertEquals(allowed, gate.state(4000))
        assertNotEquals(allowed, gate.state(6501))
        gate.accept(sample(7000))
        assertNotEquals(allowed, gate.state(7000))
        for (time in 8000L..10000L step 1000) gate.accept(sample(time))
        assertEquals(allowed, gate.state(10000))
        gate.accept(sample(11000, ux = null))
        assertNotEquals(allowed, gate.state(11000))
        for (time in 12000L..15000L step 1000) gate.accept(sample(time, source = InputSource.SIMULATION))
        assertNotEquals(allowed, gate.state(15000))
        gate.reset()
        assertNotEquals(allowed, gate.state(15000))
    }

    @Test fun absentAndMalformedAssetsRejectWithoutDefaultContent() {
        assertTrue(AssetGameContent { throw IOException("missing") }.load() is ContentLoad.Rejected)
        assertTrue(AssetGameContent { "broken" }.load() is ContentLoad.Rejected)
    }
}
