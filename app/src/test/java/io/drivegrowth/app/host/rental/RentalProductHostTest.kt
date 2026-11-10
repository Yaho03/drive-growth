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
}
