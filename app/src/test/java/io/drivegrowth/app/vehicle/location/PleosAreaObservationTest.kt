package io.drivegrowth.app.vehicle.location

import ai.pleos.playground.location.fused.listener.*
import ai.pleos.playground.location.fused.model.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.RestPlace
import org.junit.Assert.*
import org.junit.Test

/** 외부 경계 fixture이며 실제 장소/권한/서비스/영역 수신 시험이 아니다. */
class PleosAreaObservationTest {
    private fun area() = PleosAreaDefinition("fixture-area", RestPlace.SERVICE_AREA, ContentVersion("fixture-v1"),
        "https://example.test/boundary", "fixture-only", "2026-11-10", 37.0, 127.0, 25f)
    private class Client : LocationSdkClient, LocationAreaSdkClient {
        val calls = mutableListOf<String>()
        lateinit var position: FusedLocationListener
        var area: ProximityAlertListener? = null
        var request: ProximityAlertRequest? = null
        var supported = true
        var releaseFails = false
        var removeFails = false
        var addFailure: RuntimeException? = null
        override fun initialize() { calls += "initialize" }
        override fun isSupported() = supported
        override fun register(listener: FusedLocationListener) { calls += "register"; position = listener }
        override fun unregister(listener: FusedLocationListener) { calls += "unregister" }
        override fun addArea(request: ProximityAlertRequest, listener: ProximityAlertListener) {
            calls += "add"; this.request = request; area = listener; addFailure?.let { throw it }
        }
        override fun removeArea(alertId: String) { calls += "remove:$alertId"; if (removeFails) throw SecurityException() }
        override fun release() { calls += "release"; if (releaseFails) throw IllegalStateException() }
    }
    private class Fixture {
        var client = Client(); var creates = 0; var permission = true
        var elapsed = 100L; var utc = 1000L
        val jobs = ArrayDeque<() -> Unit>()
        val states = mutableListOf<PleosAreaObservation>()
        val fixes = mutableListOf<Observation<PleosLocationFix>>()
        val adapter = ForegroundPleosLocation({ creates++; client }, { permission }, { jobs += it }, {},
            { elapsed }, { utc }, { fixes += it })
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst().invoke() }
    }
    private fun begin(f: Fixture) { f.adapter.start(area(), f.states::add); f.drain() }

    @Test fun registrationUsesExternalGeometryAndNeverInventsInitialInside() {
        val f = Fixture(); begin(f)
        assertEquals(37.0, f.client.request!!.latitude, 0.0); assertEquals(25f, f.client.request!!.radiusMeters)
        assertEquals(setOf(ProximityTransition.ENTER, ProximityTransition.EXIT), f.client.request!!.transitionTypes)
        assertEquals(Observation.Missing(MissingReason.NO_DATA), f.states.single().alertId)
        f.client.area!!.onRegistered("sdk-id"); f.drain()
        assertEquals(Observation.Available("sdk-id"), f.states.last().alertId)
        assertEquals(Observation.Missing(MissingReason.NO_DATA), f.states.last().initialInside)
        assertEquals(Observation.Missing(MissingReason.NO_DATA), f.states.last().transition)
    }
    @Test fun transitionKeepsDefinitionSourceAndCallbackReceiptWithoutOccurrenceTime() {
        val f = Fixture(); begin(f); f.client.area!!.onRegistered("sdk-id"); f.drain(); f.states.clear()
        f.client.area!!.onTransition(ProximityAlertEvent("sdk-id", ProximityTransition.ENTER))
        f.elapsed = 900; f.utc = 9000; f.drain()
        val s = f.states.single(); assertEquals(area(), s.definition); assertEquals(InputSource.VEHICLE, s.source)
        assertEquals(Observation.Available(PleosAreaTransition.ENTER), s.transition)
        assertEquals(Observation.Available(PleosAreaReceipt(100, EpochMillis(1000))), s.receipt)
        assertEquals(Observation.Missing(MissingReason.NO_DATA), s.occurredAt)
        assertEquals(Observation.Missing(MissingReason.NO_DATA), s.occurredElapsedMs)
        f.client.area!!.onTransition(ProximityAlertEvent("sdk-id", ProximityTransition.EXIT)); f.drain()
        assertEquals(Observation.Available(PleosAreaTransition.EXIT), f.states.last().transition)
    }
    @Test fun beforeRegistrationAndForeignAlertEventsAreNotAttributedToThisArea() {
        val f = Fixture(); begin(f); f.states.clear()
        f.client.area!!.onTransition(ProximityAlertEvent("sdk-id", ProximityTransition.ENTER)); f.drain(); assertTrue(f.states.isEmpty())
        f.client.area!!.onRegistered("sdk-id"); f.drain(); f.states.clear()
        f.client.area!!.onTransition(ProximityAlertEvent("foreign", ProximityTransition.EXIT)); f.drain(); assertTrue(f.states.isEmpty())
    }
    @Test fun closeDropsQueuedAndLaterTransitionsAndRemovesSameSdkIdentifier() {
        val f = Fixture(); begin(f); val old = f.client; old.area!!.onRegistered("sdk-id"); f.drain(); f.states.clear()
        old.area!!.onTransition(ProximityAlertEvent("sdk-id", ProximityTransition.ENTER)); f.adapter.close()
        old.area!!.onTransition(ProximityAlertEvent("sdk-id", ProximityTransition.EXIT)); old.area!!.onError(Exception()); f.drain()
        assertTrue(f.states.isEmpty()); assertEquals(listOf("remove:sdk-id", "release"), old.calls.takeLast(2))
    }
    @Test fun restartInvalidatesOldAreaAndRegistersNewExternalDefinition() {
        val f = Fixture(); begin(f); val old = f.client; old.area!!.onRegistered("old"); f.drain()
        f.client = Client(); val other = area().copy(areaId = "other", radiusMeters = 30f)
        f.adapter.start(other, f.states::add); f.drain(); f.states.clear()
        old.area!!.onRegistered("late-old"); old.area!!.onTransition(ProximityAlertEvent("old", ProximityTransition.ENTER))
        f.client.area!!.onRegistered("new"); f.drain()
        assertEquals(other, f.states.single().definition); assertEquals(Observation.Available("new"), f.states.single().alertId)
    }
    @Test fun deniedAndUnsupportedDoNotAddAnArea() {
        for (reason in listOf(MissingReason.PERMISSION_DENIED, MissingReason.UNSUPPORTED)) {
            val f = Fixture(); if (reason == MissingReason.PERMISSION_DENIED) f.permission = false else f.client.supported = false
            begin(f); assertFalse("add" in f.client.calls); assertEquals(Observation.Missing(reason), f.states.single().alertId)
        }
    }
    @Test fun permissionRevokedBeforeDeliveryCannotPublishTransition() {
        val f = Fixture(); begin(f); f.client.area!!.onRegistered("id"); f.drain(); f.states.clear()
        f.client.area!!.onTransition(ProximityAlertEvent("id", ProximityTransition.ENTER)); f.permission = false; f.drain()
        assertEquals(Observation.Missing(MissingReason.PERMISSION_DENIED), f.states.single().transition)
        f.adapter.close(); assertEquals("release", f.client.calls.last())
    }
    @Test fun callbackErrorClosesAreaEvidenceUntilExplicitRestart() {
        val f = Fixture(); begin(f); f.client.area!!.onRegistered("id"); f.drain(); f.states.clear()
        f.client.area!!.onError(SecurityException()); f.drain()
        assertEquals(Observation.Missing(MissingReason.PERMISSION_DENIED), f.states.single().transition)
        f.client.area!!.onTransition(ProximityAlertEvent("id", ProximityTransition.ENTER)); f.drain(); assertEquals(1, f.states.size)
    }
    @Test fun addFailureReleasesAndInvalidatesAllCallbacks() {
        val f = Fixture(); f.client.addFailure = IllegalStateException(); begin(f)
        assertEquals("release", f.client.calls.last()); assertEquals(Observation.Missing(MissingReason.NO_DATA), f.states.single().alertId)
        f.client.area!!.onRegistered("late"); f.drain(); assertEquals(1, f.states.size)
    }
    @Test fun removeSecurityFailureStillReleasesAndReleaseFailureBlocksReplacement() {
        val f = Fixture(); begin(f); f.client.area!!.onRegistered("id"); f.drain()
        val old = f.client; old.removeFails = true; old.releaseFails = true
        assertThrows(IllegalStateException::class.java) { f.adapter.close() }
        f.client = Client(); assertThrows(IllegalStateException::class.java) { f.adapter.start(area(), f.states::add) }; assertEquals(1, f.creates)
        old.releaseFails = false; f.adapter.start(area(), f.states::add); assertEquals(2, f.creates)
    }
    @Test fun invalidClockIsMissingRatherThanInventedReceipt() {
        val f = Fixture(); begin(f); f.client.area!!.onRegistered("id"); f.drain(); f.elapsed = -1
        f.client.area!!.onTransition(ProximityAlertEvent("id", ProximityTransition.ENTER)); f.drain()
        assertEquals(Observation.Missing(MissingReason.NO_DATA), f.states.last().receipt)
    }
    @Test fun invalidDefinitionFailsBeforeSdkAndDoesNotSupplyProductAreas() {
        for (radius in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java) { area().copy(radiusMeters = radius) }
        assertThrows(IllegalArgumentException::class.java) { area().copy(latitude = 91.0) }
        assertThrows(IllegalArgumentException::class.java) { area().copy(licence = "") }
        val f = Fixture(); f.adapter.start(); f.drain(); assertNull(f.client.area)
    }
    @Test fun inconsistentRegistrationNeverChangesAreaIdentityAndCleansEveryKnownId() {
        val f = Fixture(); begin(f); f.client.area!!.onRegistered("first"); f.drain(); f.states.clear()
        f.client.area!!.onRegistered("different"); f.drain()
        assertEquals(Observation.Missing(MissingReason.NO_DATA), f.states.single().alertId)
        f.client.area!!.onTransition(ProximityAlertEvent("different", ProximityTransition.ENTER)); f.drain()
        assertEquals(1, f.states.size); f.adapter.close()
        assertTrue("remove:first" in f.client.calls); assertTrue("remove:different" in f.client.calls)
    }
    @Test fun closeBeforeRegistrationReplyDropsReplyAndTransitionWithoutReopeningSdk() {
        val f = Fixture(); begin(f); val old = f.client; f.states.clear(); f.adapter.close()
        old.area!!.onRegistered("late"); old.area!!.onTransition(ProximityAlertEvent("late", ProximityTransition.ENTER)); f.drain()
        assertTrue(f.states.isEmpty()); assertEquals(1, f.creates); assertEquals(1, old.calls.count { it == "release" })
    }
    @Test fun consumerClosingOnRegistrationDropsAlreadyQueuedTransition() {
        val f = Fixture()
        f.adapter.start(area()) { value -> f.states += value; if (value.alertId is Observation.Available) f.adapter.close() }
        f.drain(); f.states.clear()
        f.client.area!!.onRegistered("id"); f.client.area!!.onTransition(ProximityAlertEvent("id", ProximityTransition.ENTER)); f.drain()
        assertEquals(1, f.states.size); assertTrue(f.states.single().transition is Observation.Missing)
        assertEquals("release", f.client.calls.last())
    }
    @Test fun internalSdkPortWithoutAreaSupportReportsUnsupportedAndReleases() {
        val client = Client(); val jobs = ArrayDeque<() -> Unit>(); val states = mutableListOf<PleosAreaObservation>()
        val adapter = ForegroundPleosLocation({ object : LocationSdkClient by client {} }, { true }, { jobs += it }, {}, { 1 }, { 2 }, {})
        adapter.start(area(), states::add); while (jobs.isNotEmpty()) jobs.removeFirst().invoke()
        assertEquals(Observation.Missing(MissingReason.UNSUPPORTED), states.single().alertId)
        assertEquals("release", client.calls.last())
    }
}
