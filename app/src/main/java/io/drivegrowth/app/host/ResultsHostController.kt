package io.drivegrowth.app.host

import io.drivegrowth.app.ui.results.ResultsReadState
import io.drivegrowth.app.ui.results.ResultsUiState
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.SettlementId
import java.util.Collections
import java.util.concurrent.Executor

/** main에서 입력·응답을 관리한다. 열람 쓰기는 한 개이며 Activity 대신 구성 변경 세션이 소유한다. */
class ResultsHostController(
    private val worker: Executor,
    private val main: Executor,
    private val write: (SettlementId) -> ContractResult<Unit>,
    private val refresh: () -> Unit,
    restored: ResultsUiState = ResultsUiState(),
) {
    var uiState: ResultsUiState = restored.copy(
        readStates = immutable(restored.readStates.mapValues { (_, value) ->
            if (value == ResultsReadState.Pending) ResultsReadState.Failed(ContractError.DATA_UNAVAILABLE) else value
        }),
        retryPending = false,
    )
        private set
    private var onResult: ((SettlementId, ContractResult<Unit>) -> Unit)? = null
    private var released = false

    fun attach(listener: (SettlementId, ContractResult<Unit>) -> Unit) {
        if (!released) onResult = listener
    }

    fun detach() { onResult = null }

    /** 표시 View의 선택·조회 재시도만 기억한다. 열람 완료는 실제 저장 응답만 만든다. */
    fun remember(value: ResultsUiState) {
        uiState = uiState.copy(selectedId = value.selectedId, retryPending = value.retryPending)
    }

    fun completeQuery() { uiState = uiState.copy(retryPending = false) }

    fun markViewed(id: SettlementId, snapshot: HostSnapshot?, controlsEnabled: Boolean): Boolean {
        if (released || !controlsEnabled || snapshot == null || uiState.selectedId != id ||
            uiState.readStates[id] == ResultsReadState.Done || uiState.readStates.values.any { it == ResultsReadState.Pending } ||
            snapshot.unviewedSettlements.none { it.settlementId == id && it.transaction.source == snapshot.source }) return false
        uiState = uiState.copy(readStates = immutable(uiState.readStates + (id to ResultsReadState.Pending)))
        worker.execute {
            val result = write(id)
            main.execute {
                if (!released) {
                    val next = when (result) {
                        is ContractResult.Success -> ResultsReadState.Done
                        is ContractResult.Failure -> ResultsReadState.Failed(result.error)
                    }
                    uiState = uiState.copy(readStates = immutable(uiState.readStates + (id to next)))
                    onResult?.invoke(id, result)
                    if (result is ContractResult.Success) refresh()
                }
            }
        }
        return true
    }

    fun release() {
        released = true
        detach()
    }

    private fun immutable(values: Map<SettlementId, ResultsReadState>) =
        Collections.unmodifiableMap(LinkedHashMap(values))
}
