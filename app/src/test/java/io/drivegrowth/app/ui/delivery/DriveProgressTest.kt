package io.drivegrowth.app.ui.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.delivery.DeliveryHostRead
import io.drivegrowth.app.host.delivery.DeliveryNavigationSnapshot
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class DriveProgressTest {
    private val data = DeliveryPreviewStates
    private val estimated = data.active.active.proposed.estimated
    private val identity = GameIdentity(ProfileId("fixture"), estimated.source)
    private val missing = Observation.Missing(MissingReason.NO_DATA)

    private fun read(frame: DeliveryFrame, observed: Observation<ObservedDistance>) =
        DeliveryHostRead(identity, frame.screen, navigation = DeliveryNavigationSnapshot(null, null, missing, observed, missing, null))

    @Test fun activeDeliveryFillsOnlyConfirmedDistance() {
        val frame = data.activeFrame
        val half = estimated.meters / 2
        val progress = DriveProgress.from(frame, read(frame, Observation.Available(ObservedDistance(half, estimated.source))))
        assertNotNull(progress)
        assertEquals(data.purchase.vehicleId, progress!!.vehicleId)
        assertEquals(estimated.meters, progress.estimatedMeters)
        assertEquals(half, progress.observedMeters)
        assertEquals(half.toDouble() / estimated.meters, progress.fraction!!.toDouble(), 0.001)
        assertFalse(progress.awaitingResult)
    }

    @Test fun missingOrOtherSourceDistanceLeavesTheBarEmpty() {
        val frame = data.activeFrame
        assertNull(DriveProgress.from(frame, read(frame, missing))!!.fraction)
        assertNull(DriveProgress.from(frame, null)!!.observedMeters)
        val other = InputSource.values().first { it != estimated.source }
        val foreign = read(frame, Observation.Available(ObservedDistance(estimated.meters, other)))
        assertNull(DriveProgress.from(frame, foreign)!!.observedMeters)
    }

    @Test fun readOfAnotherScreenIsNotUsed() {
        val stale = DeliveryHostRead(identity, DeliveryScreen.Loading,
            navigation = DeliveryNavigationSnapshot(null, null, missing,
                Observation.Available(ObservedDistance(1, estimated.source)), missing, null))
        assertNull(DriveProgress.from(data.activeFrame, stale)!!.observedMeters)
    }

    @Test fun distanceBeyondEstimateIsCappedAtFull() {
        val frame = data.activeFrame
        val over = read(frame, Observation.Available(ObservedDistance(estimated.meters * 3, estimated.source)))
        assertEquals(1f, DriveProgress.from(frame, over)!!.fraction!!, 0f)
    }

    @Test fun onlyActiveOrAwaitingScreensShowProgress() {
        assertNull(DriveProgress.from(DeliveryFrame(1, DeliveryScreen.Loading), null))
        val awaiting = DeliveryFrame(2, DeliveryScreen.AwaitingResult(data.active, "확인 대기"))
        assertTrue(DriveProgress.from(awaiting, null)!!.awaitingResult)
    }

    @Test fun kilometreLabelRoundsToOneDecimal() {
        assertEquals("0.0", DriveProgress.km(0))
        assertEquals("1.0", DriveProgress.km(1000))
        assertEquals("12.5", DriveProgress.km(12460))
        assertEquals("0.0", DriveProgress.km(-5))
    }
}
