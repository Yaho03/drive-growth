package io.drivegrowth.app.ui.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.delivery.DeliveryHostRead
import io.drivegrowth.app.host.delivery.DeliveryNavigationSnapshot
import io.drivegrowth.app.host.delivery.DeliveryHostOperation
import io.drivegrowth.core.delivery.DeliveryChangeRules
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

    private fun progress(frame: DeliveryFrame, read: DeliveryHostRead?) = DriveProgress.from(frame, read, identity)

    @Test fun activeDeliveryFillsOnlyConfirmedDistance() {
        val frame = data.activeFrame
        val half = estimated.meters / 2
        val progress = progress(frame, read(frame, Observation.Available(ObservedDistance(half, estimated.source))))
        assertNotNull(progress)
        assertEquals(data.purchase.vehicleId, progress!!.vehicleId)
        assertEquals(estimated.meters, progress.estimatedMeters)
        assertEquals(half, progress.observedMeters)
        assertEquals(half.toDouble() / estimated.meters, progress.fraction!!.toDouble(), 0.001)
        assertEquals(data.active.active.requestId, progress.key.requestId)
    }

    @Test fun missingOrOtherSourceDistanceLeavesTheBarEmpty() {
        val frame = data.activeFrame
        assertNull(progress(frame, read(frame, missing))!!.fraction)
        assertNull(progress(frame, null))
        val other = InputSource.values().first { it != estimated.source }
        val foreign = read(frame, Observation.Available(ObservedDistance(estimated.meters, other)))
        assertNull(progress(frame, foreign)!!.observedMeters)
    }

    @Test fun readOfAnotherScreenIsNotUsed() {
        val stale = DeliveryHostRead(identity, DeliveryScreen.Loading,
            navigation = DeliveryNavigationSnapshot(null, null, missing,
                Observation.Available(ObservedDistance(1, estimated.source)), missing, null))
        assertNull(progress(data.activeFrame, stale))
    }

    @Test fun distanceBeyondEstimateIsCappedAtFull() {
        val frame = data.activeFrame
        val over = read(frame, Observation.Available(ObservedDistance(estimated.meters * 3, estimated.source)))
        assertEquals(1f, progress(frame, over)!!.fraction!!, 0f)
    }

    @Test fun awaitingMessagesArePreservedAndOldObservedDistanceIsHidden() {
        val messages = listOf("내비게이션에서 새 목적지와 예상 경로를 확인해 주세요.",
            "도착 확인 대기 · 다음 주차 때 다시 확인해 주세요.")
        for (message in messages) {
            val awaiting = DeliveryFrame(2, DeliveryScreen.AwaitingResult(data.active, message))
            val previousDistance = Observation.Available(ObservedDistance(estimated.meters, estimated.source))
            val shown = requireNotNull(progress(awaiting, read(awaiting, previousDistance)))
            assertEquals(message, shown.awaitingMessage)
            assertNull(shown.fraction)
            assertNull(shown.observedMeters)
        }
        assertNull(progress(DeliveryFrame(1, DeliveryScreen.Loading), null))
    }

    @Test fun matchingReadMustBelongToCurrentProfileAndInputSource() {
        val frame = data.activeFrame
        val read = read(frame, Observation.Available(ObservedDistance(10, estimated.source)))
        assertNull(DriveProgress.from(frame, read, identity.copy(profileId = ProfileId("other"))))
        val other = InputSource.entries.first { it != estimated.source }
        val foreign = identity.copy(source = other)
        assertNull(DriveProgress.from(frame, read.copy(identity = foreign), foreign))
    }

    @Test fun explicitlyObservedZeroIsDifferentFromMissingDistance() {
        val frame = data.activeFrame
        val zero = progress(frame, read(frame, Observation.Available(ObservedDistance(0L, estimated.source))))
        assertEquals(0L, zero?.observedMeters)
        assertEquals(0f, requireNotNull(zero?.fraction), 0f)
        assertNull(progress(frame, read(frame, missing))?.fraction)
    }

    @Test fun matchingFrameWithAStoredOperationIsNotAnOverlay() {
        val frame = data.activeFrame
        val read = read(frame, Observation.Available(ObservedDistance(10, estimated.source)))
        val original = DeliveryHostOperation.Abandon(DeliveryChangeRules(RuleVersion("fixture"), 10),
            data.active.active.requestId)
        assertNull(progress(frame, read.copy(pending = original)))
        assertEquals(frame.version, progress(frame, read)?.readVersion)
    }

    @Test fun kilometreLabelRoundsToOneDecimal() {
        assertEquals("0.0", DriveProgress.km(0))
        assertEquals("1.0", DriveProgress.km(1000))
        assertEquals("12.5", DriveProgress.km(12460))
        assertEquals("0.0", DriveProgress.km(-5))
        assertEquals("9223372036854775.8", DriveProgress.km(Long.MAX_VALUE))
    }
}
