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
    private val readHistory: (() -> ContractResult<io.drivegrowth.app.data.SettlementHistorySnapshot>)? = null,
) {
    var uiState: ResultsUiState = restored.copy(
        readStates = immutable(restored.readStates.mapValues { (_, value) ->
            if (value == ResultsReadState.Pending) ResultsReadState.Failed(ContractError.DATA_UNAVAILABLE) else value
        }),
        retryPending = false,
    )
        private set
    private var onResult: ((SettlementId, ContractResult<Unit>) -> Unit)? = null
    @Volatile private var released = false
    @Volatile private var historyAccess: Any? = null
    private val historyEnabled get() = historyAccess != null
    @Volatile private var historyGeneration = 0L
    private var history: io.drivegrowth.app.data.SettlementHistorySnapshot? = null
    private var onHistory: ((io.drivegrowth.app.ui.results.ResultsScreenState) -> Unit)? = null
    val hasHistorySource get() = readHistory != null
    var historyState: io.drivegrowth.app.ui.results.ResultsScreenState = io.drivegrowth.app.ui.results.ResultsScreenState.Loading
        private set

    fun attachHistory(listener: (io.drivegrowth.app.ui.results.ResultsScreenState) -> Unit) {
        if (!released) { onHistory = listener; listener(historyState) }
    }
    fun setControlsEnabled(value: Boolean) {
        if (released || historyEnabled == value) return
        historyAccess = if (value) Any() else null
        historyGeneration++
        if (value) refreshHistory()
    }
    fun refreshHistory(): Boolean {
        val read = readHistory ?: return false
        if (released || !historyEnabled) return false
        val generation = ++historyGeneration
        history = null
        historyState = io.drivegrowth.app.ui.results.ResultsScreenState.Loading
        onHistory?.invoke(historyState)
        worker.execute {
            val result = if (!released && historyEnabled && generation == historyGeneration) read()
                else ContractResult.Failure(ContractError.UX_RESTRICTED)
            main.execute historyReply@{
                if (released || !historyEnabled || generation != historyGeneration) return@historyReply
                val mapped = ResultsHostMapper().history(result)
                history = if (mapped is io.drivegrowth.app.ui.results.ResultsScreenState.Content)
                    (result as ContractResult.Success).value else null
                historyState = mapped
                uiState = uiState.copy(retryPending = false)
                onHistory?.invoke(historyState)
            }
        }
        return true
    }

    fun attach(listener: (SettlementId, ContractResult<Unit>) -> Unit) {
        if (!released) onResult = listener
    }

    fun detach() { onResult = null; onHistory = null }

    /** 표시 View의 선택·조회 재시도만 기억한다. 열람 완료는 실제 저장 응답만 만든다. */
    fun remember(value: ResultsUiState) {
        uiState = uiState.copy(selectedId = value.selectedId, retryPending = value.retryPending, scope = value.scope)
    }

    fun completeQuery() { uiState = uiState.copy(retryPending = false) }

    fun markViewed(id: SettlementId, snapshot: HostSnapshot?, controlsEnabled: Boolean): Boolean {
        val access = historyAccess
        val eligible = if (hasHistorySource) access != null && history?.let { data ->
            data.entries.any { !it.viewed && it.settlement.settlementId == id && it.settlement.transaction.source == data.identity.source }
        } == true else snapshot?.let { data -> data.unviewedSettlements.any { it.settlementId == id && it.transaction.source == data.source } } == true
        if (released || !controlsEnabled || !eligible || uiState.selectedId != id ||
            uiState.readStates[id] == ResultsReadState.Done || uiState.readStates.values.any { it == ResultsReadState.Pending }) return false
        uiState = uiState.copy(readStates = immutable(uiState.readStates + (id to ResultsReadState.Pending)))
        worker.execute {
            val result = if (released || (hasHistorySource && historyAccess !== access))
                ContractResult.Failure(ContractError.UX_RESTRICTED) else write(id)
            main.execute {
                if (!released) {
                    val next = when (result) {
                        is ContractResult.Success -> ResultsReadState.Done
                        is ContractResult.Failure -> ResultsReadState.Failed(result.error)
                    }
                    uiState = uiState.copy(readStates = immutable(uiState.readStates + (id to next)))
                    onResult?.invoke(id, result)
                    if (result is ContractResult.Success && !released) {
                        refresh()
                        if (hasHistorySource && historyEnabled) refreshHistory()
                    }
                }
            }
        }
        return true
    }

    fun release() {
        released = true
        historyAccess = null
        historyGeneration++
        detach()
    }

    private fun immutable(values: Map<SettlementId, ResultsReadState>) =
        Collections.unmodifiableMap(LinkedHashMap(values))
}
