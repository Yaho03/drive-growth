package io.drivegrowth.app.vehicle

import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleInteraction
import io.drivegrowth.core.contract.VehicleObservation
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VehicleInputQueueTest {
    private class Tasks : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun runNext() = pending.removeFirst().run()
    }
    private class Reader(var value: () -> VehicleObservation) : VehicleReader {
        var reads = 0
        var closes = 0
        override fun read(): VehicleObservation { reads++; return value() }
        override fun close() { closes++ }
    }
    private var now = 0L
    private val work = Tasks()
    private val ui = Tasks()
    private val queue = VehicleInputQueue(work, ui, { now }, 2500)
    private val received = mutableListOf<Result<VehicleObservation>>()
    private fun sample(gear: Gear = Gear.PARK) = VehicleObservation(InputSource.VEHICLE, now,
        EpochMillis(now), Observation.Available(0.0), Observation.Available(gear), Observation.Available(false))
    private fun finish() { work.runNext(); ui.runNext() }

    @Test fun stalledReadDoesNotBlockUiOrExpiryTimer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val replies = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val realQueue = VehicleInputQueue(executor, Executor { replies.add(it) }, { now }, 2500)
        val gate = ForegroundInteraction()
        for (time in 0L..3000L step 1000) { now = time; gate.accept(sample()) }
        assertEquals(Observation.Available(VehicleInteraction.ALLOWED), gate.state(now))
        val session = realQueue.start({ Reader {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            sample()
        } }) { received.add(it) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val uiTask = Runnable { now = 5501; assertNotEquals(
                Observation.Available(VehicleInteraction.ALLOWED), gate.state(now)); completed.countDown() }
            uiTask.run()
            assertEquals(0L, completed.count)
            repeat(100) { realQueue.poll(session) }
            release.countDown()
            replies.poll(5, TimeUnit.SECONDS)!!.run()
            assertTrue(received.isEmpty())
        } finally {
            release.countDown()
            realQueue.stop(session)
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun stopDiscardsResultAndClosesOnWorker() {
        val reader = Reader { sample() }
        val session = queue.start({ reader }, received::add)
        work.runNext()
        queue.stop(session)
        assertEquals(0, reader.closes)
        ui.runNext()
        assertTrue(received.isEmpty())
        assertEquals(1, work.pending.size)
        finish()
        assertEquals(1, reader.closes)
    }

    @Test fun restartRejectsOldSessionAndOldStopCannotStopNewSession() {
        val old = Reader { sample() }
        val latest = Reader { sample(Gear.DRIVE) }
        val first = queue.start({ old }, received::add)
        work.runNext()
        queue.stop(first)
        val second = queue.start({ latest }, received::add)
        repeat(100) { queue.poll(second) }
        assertTrue(work.pending.isEmpty())
        ui.runNext()
        assertTrue(received.isEmpty())
        queue.stop(first)
        finish()
        assertEquals(1, old.closes)
        assertEquals(Observation.Available(Gear.DRIVE), received.single().getOrThrow().gear)
        assertEquals(1, latest.reads)
    }

    @Test fun repeatedStartStopKeepsOneJobAndOnlyLatestFactory() {
        var opens = 0
        var session = queue.start({ opens++; Reader { sample() } }, received::add)
        repeat(1000) {
            queue.stop(session)
            session = queue.start({ opens++; Reader { sample() } }, received::add)
            queue.poll(session)
        }
        assertEquals(1, work.pending.size)
        finish()
        assertTrue(received.isEmpty())
        assertEquals(1, work.pending.size)
        finish()
        assertEquals(1, opens)
        assertEquals(1, received.size)
        assertTrue(work.pending.isEmpty())
    }

    @Test fun lateReplyAfterExpiryCannotRestoreAllowed() {
        val gate = ForegroundInteraction()
        for (time in 0L..3000L step 1000) { now = time; gate.accept(sample()) }
        val session = queue.start({ Reader { sample() } }) { gate.accept(it.getOrThrow()) }
        work.runNext()
        now = 5501
        assertNotEquals(Observation.Available(VehicleInteraction.ALLOWED), gate.state(now))
        ui.runNext()
        assertNotEquals(Observation.Available(VehicleInteraction.ALLOWED), gate.state(now))
        queue.poll(session)
        finish()
        assertNotEquals(Observation.Available(VehicleInteraction.ALLOWED), gate.state(now))
    }

    @Test fun errorsAndPermissionDenialNeverBecomeParkedDefaults() {
        for ((error, reason) in listOf(SecurityException() to MissingReason.PERMISSION_DENIED,
            IllegalStateException() to MissingReason.NO_DATA)) {
            val gate = ForegroundInteraction()
            val session = queue.start({ Reader { throw error } }) { result ->
                val speed = readSignal<Double> { result.getOrThrow().speedMps }
                assertEquals(Observation.Missing(reason), speed)
                gate.accept(sample().copy(speedMps = speed))
            }
            finish()
            assertNotEquals(Observation.Available(VehicleInteraction.ALLOWED), gate.state(now))
            queue.stop(session)
            finish()
        }
    }

    @Test fun normalParkAndDriveKeepExistingGateRules() {
        val gate = ForegroundInteraction()
        val reader = Reader { sample() }
        val session = queue.start({ reader }) { gate.accept(it.getOrThrow()) }
        finish()
        for (time in 1000L..3000L step 1000) { now = time; queue.poll(session); finish() }
        assertEquals(Observation.Available(VehicleInteraction.ALLOWED), gate.state(now))
        now = 4000
        reader.value = { sample(Gear.DRIVE) }
        queue.poll(session); finish()
        assertEquals(Observation.Available(VehicleInteraction.RESTRICTED), gate.state(now))
        gate.reset()
        assertNotEquals(Observation.Available(VehicleInteraction.ALLOWED), gate.state(now))
    }

    @Test fun invalidationDiscardsReplyEvenWhenWorkerAlreadyFinished() {
        val session = queue.start({ Reader { sample() } }, received::add)
        work.runNext()
        queue.invalidate(session)
        ui.runNext()
        assertTrue(received.single().isFailure)
        queue.poll(session)
        finish()
        assertEquals(2, received.size)
    }

    @Test fun responseKeepsReceiptTimeAndRequestStillExpires() {
        val session = queue.start({ Reader { sample() } }, received::add)
        now = 1000
        work.runNext()
        now = 2500
        ui.runNext()
        assertEquals(1000L, received.single().getOrThrow().receivedElapsedMs)
        assertEquals(EpochMillis(1000), received.single().getOrThrow().receivedAt)
        queue.poll(session)
        work.runNext()
        now = 5001
        ui.runNext()
        assertEquals(1, received.size)
    }

    @Test fun failedCloseRetainsOldSourceUntilItCanBeReleased() {
        var fail = true
        var opens = 0
        val old = object : VehicleReader {
            override fun read() = sample()
            override fun close() { if (fail) throw IllegalStateException("close") }
        }
        val first = queue.start({ old }, received::add)
        finish()
        queue.stop(first)
        val second = queue.start({ opens++; Reader { sample() } }, received::add)
        finish()
        finish()
        assertEquals(0, opens)
        assertTrue(received.last().isFailure)
        fail = false
        queue.poll(second)
        finish()
        assertEquals(1, opens)
    }

    @Test fun variableReadLatencyCannotShortenContinuousParking() {
        val gate = ForegroundInteraction()
        val session = queue.start({ Reader { sample() } }) { gate.accept(it.getOrThrow()) }
        now = 1500
        finish()
        for (time in listOf(2000L, 3000L)) { now = time; queue.poll(session); finish() }
        assertNotEquals(Observation.Available(VehicleInteraction.ALLOWED), gate.state(now))
        now = 4500
        queue.poll(session); finish()
        assertEquals(Observation.Available(VehicleInteraction.ALLOWED), gate.state(now))
    }

    @Test fun cleanupFailureIsReportedWithoutAnActivityReceiver() {
        val failures = mutableListOf<String>()
        val checked = VehicleInputQueue(work, ui, { now }, 2500) { operation, _ -> failures.add(operation) }
        val session = checked.start({ object : VehicleReader {
            override fun read() = sample()
            override fun close() { throw IllegalStateException("close") }
        } }, {})
        finish()
        checked.stop(session)
        finish()
        assertEquals(listOf("close"), failures)
    }

    @Test fun failedOpenDoesNotLeaveQueueBusy() {
        var fail = true
        val session = queue.start({
            if (fail) throw IllegalStateException("open")
            Reader { sample() }
        }, received::add)
        finish()
        assertTrue(received.single().isFailure)
        fail = false
        queue.poll(session)
        finish()
        assertTrue(received.last().isSuccess)
    }

    @Test fun replacementDuringCloseSkipsTheNowObsoleteFactory() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val replies = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val realQueue = VehicleInputQueue(executor, Executor { replies.add(it) }, { now }, 2500)
        var obsoleteOpens = 0
        var latestOpens = 0
        realQueue.start({ object : VehicleReader {
            override fun read() = sample()
            override fun close() { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        } }, {})
        try {
            replies.poll(5, TimeUnit.SECONDS)!!.run()
            val obsolete = realQueue.start({ obsoleteOpens++; Reader { sample() } }, {})
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            realQueue.stop(obsolete)
            val latest = realQueue.start({ latestOpens++; Reader { sample() } }, {})
            release.countDown()
            replies.poll(5, TimeUnit.SECONDS)!!.run()
            replies.poll(5, TimeUnit.SECONDS)!!.run()
            assertEquals(0, obsoleteOpens)
            assertEquals(1, latestOpens)
            realQueue.stop(latest)
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun blockedCloseDoesNotCreateAnotherSourceOrQueueWork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val replies = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val realQueue = VehicleInputQueue(executor, Executor { replies.add(it) }, { now }, 2500)
        var opens = 0
        val first = realQueue.start({ object : VehicleReader {
            override fun read() = sample()
            override fun close() { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        } }, {})
        try {
            replies.poll(5, TimeUnit.SECONDS)!!.run()
            realQueue.stop(first)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = realQueue.start({ opens++; Reader { sample() } }, {})
            repeat(1000) { realQueue.poll(second) }
            assertEquals(0, opens)
            release.countDown()
            replies.poll(5, TimeUnit.SECONDS)!!.run()
            replies.poll(5, TimeUnit.SECONDS)!!.run()
            assertEquals(1, opens)
            realQueue.stop(second)
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
