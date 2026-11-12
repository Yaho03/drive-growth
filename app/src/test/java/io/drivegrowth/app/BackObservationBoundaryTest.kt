package io.drivegrowth.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackObservationBoundaryTest {
    @Test
    fun lateGainsAfterBackCannotRestartObservationUntilTopResumedIsLost() {
        val boundary = BackObservationBoundary()
        assertTrue(boundary.topResumedChanged(true))

        boundary.sentToBack()

        assertFalse(boundary.topResumedChanged(true))
        assertFalse(boundary.topResumedChanged(true))
        assertFalse(boundary.topResumedChanged(false))
    }

    @Test
    fun lossThenGainAfterBackRestoresObservationWithoutResume() {
        val boundary = BackObservationBoundary()
        boundary.topResumedChanged(true)
        boundary.sentToBack()

        assertFalse(boundary.topResumedChanged(false))
        assertTrue(boundary.topResumedChanged(true))
    }

    @Test
    fun lossFromBeforeBackDoesNotReleaseTheNewBackBoundary() {
        val boundary = BackObservationBoundary()
        boundary.topResumedChanged(false)
        boundary.sentToBack()

        assertFalse(boundary.topResumedChanged(true))
        assertFalse(boundary.topResumedChanged(false))
        assertTrue(boundary.topResumedChanged(true))
    }

    @Test
    fun normalResumeRestoresObservationWithoutWaitingForTopResumedLoss() {
        val boundary = BackObservationBoundary()
        boundary.sentToBack()

        boundary.resumed()

        assertTrue(boundary.topResumedChanged(true))
    }
}
