package io.drivegrowth.app.host.rental

import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class RentalProductHostTest {
    private class Queue : Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { jobs.add(command) }
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }
    }
    private val frame = DrivingFrame(VehicleObservation(InputSource.SIMULATION, 100, EpochMillis(1000),
        Observation.Missing(MissingReason.NO_DATA), Observation.Missing(MissingReason.NO_DATA),
        Observation.Missing(MissingReason.NO_DATA)), Observation.Missing(MissingReason.NO_DATA))
    private val recorded = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)

    @Test fun acceptedObservationsDrainButOldReceiverCannotUpdateNewUi() {
        val worker = Queue(); val main = Queue(); val calls = mutableListOf<String>(); var callbacks = 0
        val host = RentalProductHost(worker, main, { actual, result ->
            assertSame(frame, actual); assertSame(recorded, result); calls += "observe"; recorded
        }, { calls += "reconnect" }, { calls += "pause" }, { _, _ -> ContractResult.Success(Unit) })
        host.start("receiver-1") { callbacks++ }
        worker.execute { host.observed("receiver-1", frame, recorded) }
        host.pause()
        host.start("receiver-2") { callbacks++ }
        worker.execute { host.observed("receiver-2", frame, recorded) }
        worker.drain(); main.drain()
        assertEquals(listOf("reconnect", "observe", "pause", "reconnect", "observe"), calls)
        assertEquals(1, callbacks)
    }

    @Test fun configurationAndOriginalLifecycleClockReachWorkerWithoutReplacement() {
        val worker = Queue(); val main = Queue(); val calls = mutableListOf<Pair<Boolean, ContractResult<RentalClock>>>()
        val host = RentalProductHost(worker, main, { _, _ -> recorded }, {}, {}, { config, clock ->
            calls += config to clock; ContractResult.Success(Unit)
        })
        val actual = ContractResult.Success(RentalClock("explicit-simulation-boot", EpochMillis(1234), 234))
        val missing = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        host.inactive(true, actual); host.inactive(false, missing)
        assertTrue(calls.isEmpty()); worker.drain()
        assertEquals(listOf(true to actual, false to missing), calls)
        assertSame(actual, calls.first().second); assertSame(missing, calls.last().second)
    }
    @Test fun sourceFailureIsTypedAndDoesNotCrashWorker() {
        val worker=Queue();val main=Queue();var answer:ContractResult<RentalRuntimeSnapshot>?=null
        val host=RentalProductHost(worker,main,{_,_->throw IllegalStateException("explicit fixture failure")},{},{},{_,_->ContractResult.Success(Unit)})
        host.start("receiver"){answer=it};worker.drain()
        val result=host.observed("receiver",frame,recorded);main.drain()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),result)
        assertEquals(result,answer)
    }

    @Test fun mapEventsExpireAcrossGateAndLateReceiverWithoutLosingSnapshot() {
        val worker = Queue()
        val main = Queue()
        val event = RentalMapEvent(io.drivegrowth.app.host.GameIdentity(ProfileId("profile"), InputSource.SIMULATION),
            RequestId("visit"), OwnedVehicleId("owned"), RentalMapDirection.DEPARTURE,
            io.drivegrowth.core.progression.VehicleActivity.RENTED)
        val snapshot = RentalRuntimeSnapshot(emptyList(), null, mapEvents = listOf(event))
        val replies = mutableListOf<RentalRuntimeSnapshot>()
        val host = RentalProductHost(worker, main, { _, _ -> ContractResult.Success(snapshot) }, {}, {},
            { _, _ -> ContractResult.Success(Unit) })
        host.start("active") { replies += (it as ContractResult.Success).value }
        host.setMapPresentationEnabled(true)
        host.observed("active", frame, recorded, host.captureMapPresentation())
        main.drain()
        assertEquals(listOf(event), replies.single().mapEvents)
        host.observed("active", frame, recorded, host.captureMapPresentation())
        host.setMapPresentationEnabled(false)
        host.setMapPresentationEnabled(true)
        main.drain()
        assertEquals(snapshot.copy(mapEvents = emptyList()), replies.last())
        host.observed("active", frame, recorded)
        host.pause()
        host.start("replacement") { replies += (it as ContractResult.Success).value }
        main.drain()
        assertEquals(2, replies.size)
    }

    @Test fun acceptedObservationAfterHomeRoundTripStillCommitsWithoutOldMapMotion() {
        val worker = Queue()
        val main = Queue()
        var assignments = 0
        val event = RentalMapEvent(io.drivegrowth.app.host.GameIdentity(ProfileId("profile"), InputSource.SIMULATION),
            RequestId("observed-visit"), OwnedVehicleId("owned"), RentalMapDirection.DEPARTURE,
            io.drivegrowth.core.progression.VehicleActivity.RENTED)
        val pending = io.drivegrowth.app.data.RentalLedger.Pending(event.rentalId, event.vehicle, event.activity,
            EpochMillis(1_000), EpochMillis(2_000), EpochMillis(3_000))
        val rental = RentalProductHost(worker, main, { _, _ ->
            assignments++
            ContractResult.Success(RentalRuntimeSnapshot(listOf(pending), null, mapEvents = listOf(event)))
        }, {}, {}, { _, _ -> ContractResult.Success(Unit) })
        val driving = ObservedDrivingHost(worker, main, { RecordingClock(100, EpochMillis(1_000)) },
            { _, _, _ -> fail("수락 시점의 관측 작업을 사용해야 합니다"); recorded },
            { _, _ -> recorded }, nextReceiver = { "receiver" }).apply {
            prepareObservation = {
                val ticket = rental.captureMapPresentation()
                val work: (String, DrivingFrame, RecordingClock) -> ContractResult<DrivingRecordingResult> = { token, value, _ ->
                    rental.observed(token, value, recorded, ticket)
                    recorded
                }
                work
            }
        }
        val replies = mutableListOf<RentalRuntimeSnapshot>()
        rental.start(driving.start {}) { replies += (it as ContractResult.Success).value }
        for (visibleAtAccept in listOf(true, false)) {
            rental.setMapPresentationEnabled(visibleAtAccept)
            driving.accept(frame)
            rental.setMapPresentationEnabled(false)
            rental.setMapPresentationEnabled(true)
            worker.drain()
            main.drain()
            assertEquals(listOf(pending), replies.last().pending)
            assertTrue(replies.last().mapEvents.isEmpty())
        }
        driving.accept(frame)
        worker.drain()
        main.drain()
        assertEquals(3, assignments)
        assertEquals(listOf(event), replies.last().mapEvents)
    }

}
