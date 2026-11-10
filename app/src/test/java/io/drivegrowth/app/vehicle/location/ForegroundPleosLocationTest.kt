package io.drivegrowth.app.vehicle.location

import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import org.junit.Assert.*
import org.junit.Test

class ForegroundPleosLocationTest {
    private class Client : LocationSdkClient {
        val calls = mutableListOf<String>()
        var supported = true
        var failAt: String? = null
        var failure: RuntimeException = IllegalStateException("fake SDK unavailable")
        var callback: FusedLocationListener? = null
        var duringRegister: (() -> Unit)? = null
        private fun call(name: String) {
            calls += name
            if (failAt == name) throw failure
        }
        override fun initialize() = call("initialize")
        override fun isSupported(): Boolean { call("isSupported"); return supported }
        override fun register(listener: FusedLocationListener) {
            callback = listener
            duringRegister?.invoke()
            call("register")
        }
        override fun unregister(listener: FusedLocationListener) {
            assertSame(callback, listener)
            call("unregister")
        }
        override fun release() = call("release")
    }

    private class Harness {
        val clients = mutableListOf<Client>()
        val queued = ArrayDeque<() -> Unit>()
        val values = mutableListOf<Observation<PleosLocationFix>>()
        var permission = true
        var onMain = true
        var elapsed = 9_000L
        var utc = 100_000L
        var creates = 0
        var createFailure: RuntimeException? = null
        var next = Client()
        val adapter = ForegroundPleosLocation(
            createClient = {
                creates++
                createFailure?.let { throw it }
                next.also { clients += it }
            },
            hasPermission = { permission },
            post = { queued += it },
            checkMainThread = { check(onMain) },
            elapsedMillis = { elapsed }, utcMillis = { utc }, deliver = { values += it },
        )
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
        fun startAndClear() { adapter.start(); drain(); values.clear() }
        fun missing(reason: MissingReason) { assertEquals(listOf(Observation.Missing(reason)), values) }
    }

    private fun fix() = LocationData(37.0, 127.0, 20.0, 0f, 0.7f, 1234L)

    @Test fun deniedPermissionDoesNotCreateSdk() {
        val h = Harness(); h.permission = false
        h.adapter.start(); h.drain()
        h.missing(MissingReason.PERMISSION_DENIED); assertEquals(0, h.creates)
    }

    @Test fun successfulStartReportsNoDataUntilFirstCallback() {
        val h = Harness(); h.adapter.start(); h.drain()
        h.missing(MissingReason.NO_DATA)
        assertEquals(listOf("initialize", "isSupported", "register"), h.next.calls)
    }

    @Test fun callbackIsQueuedAndPreservesRawAndReceiptTimes() {
        val h = Harness(); h.startAndClear()
        h.next.callback!!.onChanged(fix()); assertTrue(h.values.isEmpty())
        h.elapsed = 20_000; h.utc = 200_000; h.drain()
        assertEquals(listOf(Observation.Available(PleosLocationFix(InputSource.VEHICLE, 37.0, 127.0,
            0.7f, 1234, 9_000, EpochMillis(100_000)))), h.values)
    }

    @Test fun nullCallbackIsMissingRatherThanCoordinates() {
        val h = Harness(); h.startAndClear(); h.next.callback!!.onChanged(null); h.drain()
        h.missing(MissingReason.NO_DATA)
    }

    @Test fun callbackErrorsKeepExplicitMissingReasons() {
        for ((error, reason) in listOf(SecurityException() to MissingReason.PERMISSION_DENIED,
            Exception("service error") to MissingReason.NO_DATA)) {
            val h = Harness(); h.startAndClear(); h.next.callback!!.onError(error); h.drain(); h.missing(reason)
        }
    }

    @Test fun permissionRevokedBeforeQueuedDeliveryCannotPublishAvailable() {
        val h = Harness(); h.startAndClear(); h.next.callback!!.onChanged(fix())
        h.permission = false; h.drain(); h.missing(MissingReason.PERMISSION_DENIED)
    }

    @Test fun unsupportedSdkIsReleasedWithoutRegistering() {
        val h = Harness(); h.next.supported = false; h.adapter.start(); h.drain()
        h.missing(MissingReason.UNSUPPORTED)
        assertEquals(listOf("initialize", "isSupported", "release"), h.next.calls)
        h.adapter.close(); assertEquals(1, h.next.calls.count { it == "release" })
    }

    @Test fun initializationAndRegistrationFailuresReleaseWithReason() {
        for (operation in listOf("initialize", "isSupported", "register")) {
            for ((error, reason) in listOf(SecurityException() to MissingReason.PERMISSION_DENIED,
                IllegalStateException() to MissingReason.NO_DATA)) {
                val h = Harness(); h.next.failAt = operation; h.next.failure = error
                h.adapter.start(); h.drain(); h.missing(reason)
                assertEquals(1, h.next.calls.count { it == "release" })
                h.adapter.close(); assertEquals(1, h.next.calls.count { it == "release" })
            }
        }
    }

    @Test fun clientCreationFailureIsExplicitMissing() {
        for ((error, reason) in listOf(SecurityException() to MissingReason.PERMISSION_DENIED,
            IllegalStateException() to MissingReason.NO_DATA)) {
            val h = Harness(); h.createFailure = error; h.adapter.start(); h.drain(); h.missing(reason)
            assertTrue(h.clients.isEmpty())
        }
    }

    @Test fun callbackQueuedByFailedRegistrationCannotPublishSuccess() {
        val h = Harness(); h.next.failAt = "register"
        h.next.duringRegister = { h.next.callback!!.onChanged(fix()) }
        h.adapter.start(); h.drain(); h.missing(MissingReason.NO_DATA)
        h.next.callback!!.onChanged(fix()); h.drain(); h.missing(MissingReason.NO_DATA)
    }

    @Test fun stopDropsBothQueuedAndSubsequentCallbacksAndReleasesOnce() {
        val h = Harness(); h.startAndClear(); val callback = h.next.callback!!
        callback.onChanged(fix()); h.adapter.close()
        callback.onChanged(fix()); callback.onError(Exception()); h.drain()
        assertTrue(h.values.isEmpty())
        h.adapter.close()
        assertEquals(listOf("initialize", "isSupported", "register", "unregister", "release"), h.next.calls)
    }

    @Test fun restartInvalidatesOldSessionAndUsesNewSdk() {
        val h = Harness(); h.startAndClear(); val old = h.next
        old.callback!!.onChanged(fix()); h.next = Client(); h.adapter.start(); h.drain(); h.values.clear()
        old.callback!!.onChanged(fix()); old.callback!!.onError(SecurityException())
        h.next.callback!!.onChanged(fix()); h.drain()
        assertEquals(1, h.values.size); assertTrue(h.values.single() is Observation.Available)
        assertEquals(listOf("unregister", "release"), old.calls.takeLast(2)); assertEquals(2, h.creates)
    }

    @Test fun revokedPermissionSkipsUnregisterButStillReleases() {
        val h = Harness(); h.startAndClear(); h.permission = false; h.adapter.close()
        assertFalse("unregister" in h.next.calls); assertEquals("release", h.next.calls.last())
    }

    @Test fun unregisterSecurityFailureStillReleases() {
        val h = Harness(); h.startAndClear(); h.next.failAt = "unregister"; h.next.failure = SecurityException()
        h.adapter.close(); assertEquals("release", h.next.calls.last())
    }

    @Test fun unexpectedUnregisterFailureIsNotHiddenButStillReleases() {
        val h = Harness(); h.startAndClear(); h.next.failAt = "unregister"
        assertThrows(IllegalStateException::class.java) { h.adapter.close() }
        assertEquals("release", h.next.calls.last())
    }

    @Test fun releaseFailureBlocksNewClientUntilCleanupSucceeds() {
        val h = Harness(); h.startAndClear(); val old = h.next; old.failAt = "release"
        assertThrows(IllegalStateException::class.java) { h.adapter.close() }
        h.next = Client()
        assertThrows(IllegalStateException::class.java) { h.adapter.start() }
        assertEquals(1, h.creates)
        old.failAt = null; h.adapter.start(); h.drain()
        assertEquals(2, h.creates); assertEquals(3, old.calls.count { it == "release" })
    }

    @Test fun mainThreadPreconditionRunsBeforeSdkMutation() {
        val h = Harness(); h.onMain = false
        assertThrows(IllegalStateException::class.java) { h.adapter.start() }
        assertThrows(IllegalStateException::class.java) { h.adapter.close() }
        assertEquals(0, h.creates)
    }

    @Test fun unexpectedRegisterFailureIsPropagatedAfterCleanup() {
        val h = Harness(); h.next.failAt = "register"; h.next.failure = IllegalArgumentException("unexpected")
        val error = assertThrows(IllegalArgumentException::class.java) { h.adapter.start() }
        assertSame(h.next.failure, error); assertEquals("release", h.next.calls.last())
        h.next.callback!!.onChanged(fix()); h.drain(); assertTrue(h.values.isEmpty())
    }

    @Test fun cleanupFailureIsPreservedAlongsideUnexpectedStartFailure() {
        val h = Harness()
        val startup = IllegalArgumentException("registration failed")
        h.next.duringRegister = { h.next.failAt = "release"; throw startup }
        val error = assertThrows(IllegalArgumentException::class.java) { h.adapter.start() }
        assertSame(startup, error); assertSame(h.next.failure, error.suppressed.single())
        h.drain(); assertTrue(h.values.isEmpty())
    }

    @Test fun backgroundCallbackWaitsForMainDispatch() {
        val h = Harness(); h.startAndClear()
        val thread = Thread { h.next.callback!!.onChanged(fix()) }
        thread.start(); thread.join()
        assertTrue(h.values.isEmpty()); h.drain()
        assertTrue(h.values.single() is Observation.Available)
    }

    @Test fun invalidRawNumbersAndReceiptClocksAreMissing() {
        val bad = listOf(
            LocationData(Double.POSITIVE_INFINITY, 0.0, 0.0, 0f, 1f, 1),
            LocationData(-91.0, 0.0, 0.0, 0f, 1f, 1),
            LocationData(0.0, Double.NaN, 0.0, 0f, 1f, 1),
            LocationData(0.0, 181.0, 0.0, 0f, 1f, 1),
            LocationData(0.0, -181.0, 0.0, 0f, 1f, 1),
            LocationData(0.0, 0.0, 0.0, 0f, Float.NaN, 1),
            LocationData(0.0, 0.0, 0.0, 0f, Float.POSITIVE_INFINITY, 1),
            LocationData(0.0, 0.0, 0.0, 0f, 1f, -1),
        )
        for (raw in bad) assertEquals(Observation.Missing(MissingReason.NO_DATA), pleosLocationFix(raw, 0, EpochMillis(0)))
        assertEquals(Observation.Missing(MissingReason.NO_DATA), pleosLocationFix(fix(), -1, EpochMillis(0)))
        assertEquals(Observation.Missing(MissingReason.NO_DATA), pleosLocationFix(fix(), 0, EpochMillis(-1)))
    }

    @Test fun finiteBoundaryCoordinatesAndRawZeroTimeAreNotFreshnessClaims() {
        for ((latitude, longitude) in listOf(-90.0 to -180.0, 90.0 to 180.0, 0.0 to 0.0)) {
            val raw = LocationData(latitude, longitude, 0.0, 0f, 0f, 0)
            val actual = pleosLocationFix(raw, 100, EpochMillis(1_000))
            assertEquals(Observation.Available(PleosLocationFix(InputSource.VEHICLE, latitude, longitude,
                0f, 0, 100, EpochMillis(1_000))), actual)
        }
    }
}
