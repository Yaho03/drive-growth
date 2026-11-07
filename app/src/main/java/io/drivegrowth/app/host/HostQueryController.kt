package io.drivegrowth.app.host

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import java.util.concurrent.Executor

sealed interface HostQueryState {
    data object Restricted : HostQueryState
    data object Loading : HostQueryState
    data class Content(val snapshot: HostSnapshot) : HostQueryState
    data class Failure(val error: ContractError) : HostQueryState
}

/**
 * 공개 메서드와 main 전달은 UI 스레드에서 실행한다. worker에는 조회 한 개만 제출한다.
 * 제한·재시도는 세대만 바꾸며, 실행 중 조회가 끝날 때 최신 요청 하나만 이어서 읽는다.
 * release는 늦은 응답과 화면 참조를 버린다. 실행기의 종료는 소유 호스트 책임이다.
 */
class HostQueryController(
    private val worker: Executor,
    private val main: Executor,
    private val read: () -> ContractResult<HostSnapshot>,
    private var onState: ((HostQueryState) -> Unit)?,
) {
    private var enabled = false
    private var released = false
    private var generation = 0L
    private var inFlight = false
    private var requested = false

    /** 같은 조회 작업에 새 화면만 연결한다. 이전 화면의 허용 판정은 물려주지 않는다. */
    fun attach(listener: (HostQueryState) -> Unit) {
        if (released) return
        detach()
        onState = listener
        listener(HostQueryState.Restricted)
    }

    fun detach() {
        generation++
        enabled = false
        requested = false
        onState = null
    }

    fun setControlsEnabled(value: Boolean) {
        if (released || value == enabled) return
        enabled = value
        if (value) refresh() else {
            generation++
            requested = false
            onState?.invoke(HostQueryState.Restricted)
        }
    }

    fun refresh() {
        if (released || !enabled) return
        generation++
        requested = true
        onState?.invoke(HostQueryState.Loading)
        launchIfNeeded()
    }

    fun release() {
        released = true
        enabled = false
        requested = false
        generation++
        onState = null
    }

    private fun launchIfNeeded() {
        if (released || !enabled || inFlight || !requested) return
        requested = false
        inFlight = true
        val started = generation
        worker.execute {
            val result = read()
            main.execute {
                inFlight = false
                if (!released && enabled && started == generation) {
                    onState?.invoke(when (result) {
                        is ContractResult.Success -> HostQueryState.Content(result.value)
                        is ContractResult.Failure -> HostQueryState.Failure(result.error)
                    })
                }
                launchIfNeeded()
            }
        }
    }
}
