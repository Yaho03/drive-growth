package io.drivegrowth.app.vehicle

import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleSignalReadTest {
    @Test fun failuresBecomeReasonsWithoutFakeValues() {
        assertEquals(Observation.Missing(MissingReason.PERMISSION_DENIED), readSignal<Double> { throw SecurityException() })
        assertEquals(Observation.Missing(MissingReason.UNSUPPORTED), readSignal<Double> { throw IllegalArgumentException() })
        assertEquals(Observation.Missing(MissingReason.NO_DATA), readSignal<Double> { throw IllegalStateException() })
    }

    @Test fun successfulReadIsReturnedAsIs() {
        assertEquals(Observation.Available(3.0), readSignal { Observation.Available(3.0) })
    }
}
