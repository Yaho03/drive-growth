package io.drivegrowth.app.vehicle

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.test.InstrumentationTestCase
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleInteraction
import io.drivegrowth.core.contract.VehicleObservation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 실제 CarService를 변경하지 않고 Android main looper와 제품 입력 큐의 지연 경계를 확인한다. */
@Suppress("DEPRECATION")
class VehicleInputDelayTest : InstrumentationTestCase() {
    fun testBlockedReaderLeavesMainResponsiveAndExpiredReplyDiscarded() {
        val worker = HandlerThread("VehicleDelayTest").apply { start() }
        val handler = Handler(worker.looper)
        val main = Handler(Looper.getMainLooper())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val now = AtomicLong(3000)
        val gate = ForegroundInteraction()
        val queue = VehicleInputQueue(Executor { handler.post(it) }, Executor { main.post(it) }, now::get, 2500)
        lateinit var session: VehicleInputQueue.Session
        var deliveries = 0
        fun sample(time: Long) = VehicleObservation(InputSource.VEHICLE, time, EpochMillis(time),
            Observation.Available(0.0), Observation.Available(Gear.PARK), Observation.Available(false))
        try {
            instrumentation.runOnMainSync {
                for (time in 0L..3000L step 1000) gate.accept(sample(time))
                assertEquals(Observation.Available(VehicleInteraction.ALLOWED), gate.state(now.get()))
                session = queue.start({ object : VehicleReader {
                    override fun read(): VehicleObservation {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        return sample(now.get())
                    }
                    override fun close() { closed.countDown() }
                } }) { deliveries++; gate.accept(it.getOrThrow()) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // worker의 대기를 풀기 전에 main의 제한 판정·stop을 완료한다. 임의 sleep은 쓰지 않는다.
            instrumentation.runOnMainSync {
                now.set(5501)
                assertFalse(Observation.Available(VehicleInteraction.ALLOWED) == gate.state(now.get()))
                repeat(1000) { queue.poll(session) }
                queue.stop(session)
            }
            assertEquals(1L, release.count)
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertEquals(0, deliveries) }
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { queue.stop(session) }
            worker.quitSafely()
            worker.join(5000)
            assertFalse(worker.isAlive)
        }
    }
}
