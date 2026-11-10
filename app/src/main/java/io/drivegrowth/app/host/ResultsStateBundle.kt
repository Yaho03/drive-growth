package io.drivegrowth.app.host

import android.os.Bundle
import io.drivegrowth.app.ui.results.ResultsReadState
import io.drivegrowth.app.ui.results.ResultsUiState
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.SettlementId

/** UI 식별/표시 상태만 저장한다. 프로세스 유실 Pending의 결과 확인은 controller가 별도로 처리한다. */
internal object ResultsStateBundle {
    fun encode(state: ResultsUiState) = Bundle().apply {
        putString("selected", state.selectedId?.value)
        putString("scope", state.scope.name)
        putBoolean("retry", state.retryPending)
        putBundle("reads", Bundle().apply {
            state.readStates.forEach { (id, value) ->
                putBundle(id.value, Bundle().apply {
                    putString("state", when (value) {
                        ResultsReadState.Ready -> "ready"
                        ResultsReadState.Pending -> "pending"
                        ResultsReadState.Done -> "done"
                        is ResultsReadState.Failed -> "failed"
                    })
                    if (value is ResultsReadState.Failed) putString("error", value.error.name)
                })
            }
        })
    }

    fun decode(saved: Bundle?): ResultsUiState {
        val selected = saved?.getString("selected")?.takeIf { it.isNotBlank() }?.let(::SettlementId)
        val states = saved?.getBundle("reads")
        val restored = buildMap {
            states?.keySet()?.filter { it.isNotBlank() }?.forEach { key ->
                val value = states.getBundle(key)
                val state = when (value?.getString("state")) {
                    "pending" -> ResultsReadState.Pending
                    "done" -> ResultsReadState.Done
                    "failed" -> ResultsReadState.Failed(ContractError.entries.firstOrNull {
                        it.name == value.getString("error")
                    } ?: ContractError.DATA_UNAVAILABLE)
                    else -> ResultsReadState.Ready
                }
                put(SettlementId(key), state)
            }
        }
        return ResultsUiState(selected, restored, saved?.getBoolean("retry") ?: false,
            io.drivegrowth.app.ui.results.ResultsScope.entries.firstOrNull { it.name == saved?.getString("scope") }
                ?: io.drivegrowth.app.ui.results.ResultsScope.UNVIEWED)
    }
}
