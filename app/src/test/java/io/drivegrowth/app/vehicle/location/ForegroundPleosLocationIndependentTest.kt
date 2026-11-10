package io.drivegrowth.app.vehicle.location

import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/** SDK 대체 경계의 순서/소유권 시험이다. 실제 Android 권한·센서 시험이 아니다. */
class ForegroundPleosLocationIndependentTest {
    private class Client : LocationSdkClient {
        lateinit var callback: FusedLocationListener
        var failRelease = false
        var unregisters = 0
        var releases = 0
        override fun initialize() = Unit
        override fun isSupported() = true
        override fun register(listener: FusedLocationListener) { callback = listener }
        override fun unregister(listener: FusedLocationListener) {
            assertSame(callback, listener)
            unregisters++
        }
        override fun release() {
            releases++
            if (failRelease) throw IllegalStateException("independent cleanup failure")
        }
    }

    private class Fixture {
        val queue = ConcurrentLinkedQueue<() -> Unit>()
        val delivered = mutableListOf<Observation<PleosLocationFix>>()
        var client = Client()
        var creates = 0
        var afterDelivery: (Observation<PleosLocationFix>) -> Unit = {}
        val adapter = ForegroundPleosLocation(
            createClient = { creates++; client }, hasPermission = { true },
            post = { queue.add(it); Unit }, checkMainThread = {},
            elapsedMillis = { 123L }, utcMillis = { 456L },
            deliver = { delivered += it; afterDelivery(it) },
        )
        fun drain() { while (true) (queue.poll() ?: return).invoke() }
        fun begin() { adapter.start(); drain(); delivered.clear() }
        fun location(latitude: Double) = LocationData(latitude, 127.0, 0.0, 0f, .5f, 12L)
    }

    @Test fun failedReleaseInvalidatesAllOldCallbacksAndPreventsReplacementUntilCleaned() {
        val f = Fixture(); f.begin(); val old = f.client
        old.callback.onChanged(f.location(37.0))
        old.failRelease = true
        assertThrows(IllegalStateException::class.java) { f.adapter.close() }
        Thread {
            old.callback.onChanged(f.location(38.0))
            old.callback.onError(SecurityException("late old callback"))
        }.also { it.start(); it.join() }
        f.drain(); assertTrue(f.delivered.isEmpty())

        f.client = Client()
        assertThrows(IllegalStateException::class.java) { f.adapter.start() }
        assertEquals(1, f.creates)
        old.callback.onChanged(f.location(39.0)); f.drain()
        assertTrue(f.delivered.isEmpty())

        old.failRelease = false; f.adapter.start()
        old.callback.onChanged(f.location(40.0))
        f.client.callback.onChanged(f.location(41.0)); f.drain()
        assertEquals(2, f.creates)
        assertEquals(3, old.releases)
        assertEquals(Observation.Missing(MissingReason.NO_DATA), f.delivered[0])
        assertEquals(2, f.delivered.size)
        val value = (f.delivered[1] as Observation.Available).value
        assertEquals(41.0, value.latitude, 0.0)
        f.adapter.close()
    }

    @Test fun consumerClosingDuringFirstDeliveryDropsAlreadyQueuedFixAndError() {
        val f = Fixture(); f.begin(); val old = f.client
        f.afterDelivery = { if (it is Observation.Available) f.adapter.close() }
        old.callback.onChanged(f.location(37.0))
        old.callback.onChanged(f.location(38.0))
        old.callback.onError(IllegalStateException("queued old error"))
        f.drain()
        assertEquals(1, f.delivered.size)
        assertEquals(37.0, (f.delivered.single() as Observation.Available).value.latitude, 0.0)
        assertEquals(1, old.unregisters); assertEquals(1, old.releases)
        old.callback.onChanged(f.location(39.0)); f.drain()
        assertEquals(1, f.delivered.size)

        f.afterDelivery = {}; f.delivered.clear(); f.client = Client()
        f.adapter.start(); f.drain()
        assertEquals(listOf(Observation.Missing(MissingReason.NO_DATA)), f.delivered)
        f.adapter.close()
    }
}
