package io.drivegrowth.app

import io.drivegrowth.core.contract.ContractResult

/** UI 스레드에서 현재 준비 요청만 한 번 전달한다. */
internal class SimulationStartBoundary {
    private var current: Any? = null

    fun prepare(
        prepare: ((ContractResult<Unit>) -> Unit) -> Unit,
        complete: (ContractResult<Unit>) -> Unit,
    ) {
        if (current != null) return
        val ticket = Any()
        current = ticket
        prepare { result ->
            if (current === ticket) {
                current = null
                complete(result)
            }
        }
    }

    fun cancel() { current = null }
}
