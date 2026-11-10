package io.drivegrowth.app.ui.results

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.SettlementId
import java.util.Collections

/** 확정 정산을 그대로 보존한다. null 차종명은 조회 공급자가 이름을 제공하지 않은 상태다. */
data class ResultPresentation(val settlement: Settlement, val vehicleName: String? = null,
    val viewed: Boolean = false, val rental: io.drivegrowth.core.rental.CompletedRental? = null,
    val rentalVehicleName: String? = null)
enum class ResultsOrigin { STORED, FIXTURE }
enum class ResultsScope { UNVIEWED, ALL }

sealed interface ResultsScreenState {
    data object Loading : ResultsScreenState
    data class Content(val results: List<ResultPresentation>, val origin: ResultsOrigin, val historyAvailable: Boolean = false) : ResultsScreenState {
        init {
            require(results.map { it.settlement.settlementId }.distinct().size == results.size) { "정산 ID 중복" }
        }
    }
    data class Error(val message: String) : ResultsScreenState
    data class Missing(val message: String) : ResultsScreenState
    data class Unsupported(val message: String) : ResultsScreenState
}

sealed interface ResultsReadState {
    data object Ready : ResultsReadState
    data object Pending : ResultsReadState
    data object Done : ResultsReadState
    data class Failed(val error: ContractError) : ResultsReadState
}

/** 호스트가 View 재생성 밖에 보존한다. Pending 복원 뒤에는 호스트가 기존 요청의 응답을 연결한다. */
data class ResultsUiState(
    val selectedId: SettlementId? = null,
    val readStates: Map<SettlementId, ResultsReadState> = emptyMap(),
    val retryPending: Boolean = false,
    val scope: ResultsScope = ResultsScope.UNVIEWED,
)

sealed interface ResultsEvent {
    data class SelectResult(val id: SettlementId) : ResultsEvent
    data object CloseDetail : ResultsEvent
    data object Retry : ResultsEvent
    /** 재지급 요청이 아니다. 실제 열람 저장 결과는 호스트가 completeMarkViewed로 전달한다. */
    data class MarkViewed(val id: SettlementId) : ResultsEvent
}

/** 상태·요청 중복만 관리한다. 차량 판단·원장 조회/쓰기·수익 계산은 호스트 책임이다. */
class ResultsInteraction(private val onEvent: (ResultsEvent) -> Unit) {
    var state: ResultsScreenState = ResultsScreenState.Loading
        private set
    var uiState: ResultsUiState = ResultsUiState()
        private set
    var controlsEnabled: Boolean = false
        private set
    val selectedResult: ResultPresentation?
        get() = (state as? ResultsScreenState.Content)?.results?.find { it.settlement.settlementId == uiState.selectedId }

    fun render(value: ResultsScreenState, enabled: Boolean) {
        state = if (value is ResultsScreenState.Content) value.copy(
            results = Collections.unmodifiableList(ArrayList(value.results)),
        ) else value
        controlsEnabled = enabled
    }

    fun restoreUiState(value: ResultsUiState) { update(value) }
    fun restoreSelection(id: SettlementId?) { update(uiState.copy(selectedId = id)) }
    val visibleResults: List<ResultPresentation> get() {
        val content = state as? ResultsScreenState.Content ?: return emptyList()
        return if (!content.historyAvailable || uiState.scope == ResultsScope.ALL) content.results
            else content.results.filter { !it.viewed && readState(it.settlement.settlementId) != ResultsReadState.Done }
    }
    fun scope(value: ResultsScope) {
        if (controlsEnabled && (state as? ResultsScreenState.Content)?.historyAvailable == true)
            update(uiState.copy(scope = value))
    }
    fun readState(id: SettlementId): ResultsReadState =
        if ((state as? ResultsScreenState.Content)?.results?.any { it.settlement.settlementId == id && it.viewed } == true)
            ResultsReadState.Done else uiState.readStates[id] ?: ResultsReadState.Ready

    fun select(id: SettlementId) {
        if (!controlsEnabled || visibleResults.none { it.settlement.settlementId == id }) return
        update(uiState.copy(selectedId = id))
        onEvent(ResultsEvent.SelectResult(id))
    }

    fun closeDetail(): Boolean {
        if (!controlsEnabled || uiState.selectedId == null) return false
        update(uiState.copy(selectedId = null))
        onEvent(ResultsEvent.CloseDetail)
        return true
    }

    fun canMarkViewed(id: SettlementId): Boolean = controlsEnabled &&
        selectedResult?.settlement?.settlementId == id && selectedResult?.viewed != true && readState(id) != ResultsReadState.Done &&
        uiState.readStates.values.none { it == ResultsReadState.Pending }

    fun requestMarkViewed(id: SettlementId): Boolean {
        if (!canMarkViewed(id)) return false
        update(uiState.copy(readStates = uiState.readStates + (id to ResultsReadState.Pending)))
        onEvent(ResultsEvent.MarkViewed(id))
        return true
    }

    /** 동기 callback과 다른 정산의 늦은 응답도 ID로 구분한다. 미요청/완료된 ID 응답은 무시한다. */
    fun completeMarkViewed(id: SettlementId, result: ContractResult<Unit>) {
        if (uiState.readStates[id] != ResultsReadState.Pending) return
        val next = when (result) {
            is ContractResult.Success -> ResultsReadState.Done
            is ContractResult.Failure -> ResultsReadState.Failed(result.error)
        }
        update(uiState.copy(readStates = uiState.readStates + (id to next)))
    }

    fun retry(): Boolean {
        if (!controlsEnabled || uiState.retryPending ||
            (state !is ResultsScreenState.Error && state !is ResultsScreenState.Missing)) return false
        update(uiState.copy(retryPending = true))
        onEvent(ResultsEvent.Retry)
        return true
    }

    /** 재조회 응답을 받은 호스트만 명시적으로 해제한다. render 자체는 요청 완료가 아니다. */
    fun completeRetry() { update(uiState.copy(retryPending = false)) }

    private fun update(value: ResultsUiState) {
        uiState = value.copy(readStates = Collections.unmodifiableMap(LinkedHashMap(value.readStates)))
    }
}
