package io.drivegrowth.app

/** UI 스레드에서 현재 준비 요청만 한 번 전달한다. */
internal class SimulationStartBoundary {
    private var current: Any? = null

    fun <T> prepare(
        prepare: ((T) -> Unit) -> Unit,
        complete: (T) -> Unit,
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
