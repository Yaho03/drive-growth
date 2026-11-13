package io.drivegrowth.app

import io.drivegrowth.core.contract.ContractResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SimulationStartBoundaryTest {
    @Test fun delayedPreparationCannotStartAfterTitleOrObservationEnds() {
        val gate = SimulationStartBoundary()
        val replies = mutableListOf<(ContractResult<Unit>) -> Unit>()
        var begins = 0
        fun request() = gate.prepare({ replies.add(it) }) { if (it is ContractResult.Success) begins++ }

        request()
        request()
        assertEquals(1, replies.size)
        gate.cancel()
        replies[0](ContractResult.Success(Unit))
        assertEquals(0, begins)

        request()
        gate.cancel()
        request()
        replies[1](ContractResult.Success(Unit))
        assertEquals(0, begins)
        replies[2](ContractResult.Success(Unit))
        replies[2](ContractResult.Success(Unit))
        assertEquals(1, begins)
    }
}
