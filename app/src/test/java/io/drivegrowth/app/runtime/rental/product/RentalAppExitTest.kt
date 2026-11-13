package io.drivegrowth.app.runtime.rental.product

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RentalAppExitTest {
    @Test fun stopAloneCannotRecordAnAppExit() {
        assertFalse(RentalAppExit().consumeStop(isChangingConfigurations = false))
    }

    @Test fun userLeaveConfirmedByStopIsConsumedOnlyOnce() {
        val exit = RentalAppExit()
        exit.userLeaveHint()
        assertTrue(exit.consumeStop(isChangingConfigurations = false))
        assertFalse(exit.consumeStop(isChangingConfigurations = false))
    }

    @Test fun configurationStopDiscardsEvenAPendingHint() {
        val exit = RentalAppExit()
        exit.userLeaveHint()
        assertFalse(exit.consumeStop(isChangingConfigurations = true))
        assertFalse(exit.consumeStop(isChangingConfigurations = false))
    }

    @Test fun returningToForegroundDiscardsAnUnconfirmedHint() {
        val exit = RentalAppExit()
        exit.userLeaveHint()
        exit.clear()
        assertFalse(exit.consumeStop(isChangingConfigurations = false))
    }
}
