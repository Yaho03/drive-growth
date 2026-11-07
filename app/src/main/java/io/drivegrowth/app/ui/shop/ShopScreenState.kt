package io.drivegrowth.app.ui.shop

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.Facility
import io.drivegrowth.core.rental.FacilityOffer
import io.drivegrowth.core.rental.ShopExpansion
import java.util.Collections
import java.util.UUID

/** 호스트가 조회한 표시 사실. 색과 간판은 승인된 표시 입력이며 저장 배치 모델이 아니다. */
data class ShopAppearance(val sign: String, val accentArgb: Int)
data class ShopVehicle(val id: OwnedVehicleId, val name: String, val status: String)
enum class ShopAvailability { LOCKED, AVAILABLE, INSTALLED, UNKNOWN }

sealed interface ShopCommand {
    data class Expand(val offer: ShopExpansion) : ShopCommand
    data class BuyFacility(val offer: FacilityOffer) : ShopCommand
    /** 품목/보유/허용 판정은 공급자 책임. 여기서는 식별자와 승인된 표시안만 전달한다. */
    data class Decorate(val decorationId: String, val appearance: ShopAppearance) : ShopCommand
}

data class ShopOption(
    val id: String,
    val title: String,
    val condition: String,
    val effect: String,
    val availability: ShopAvailability,
    val command: ShopCommand? = null,
    val price: GameMoney? = null,
    val ruleVersion: RuleVersion? = null,
    val trial: Boolean = false,
) {
    init {
        require(id.isNotBlank())
        when (command) {
            is ShopCommand.Expand -> require(price == command.offer.price && ruleVersion == command.offer.ruleVersion && trial == command.offer.trial)
            is ShopCommand.BuyFacility -> require(price == command.offer.price && ruleVersion == command.offer.ruleVersion && trial == command.offer.trial)
            else -> Unit
        }
    }
}

data class ShopFacility(val facility: Facility, val option: ShopOption)
enum class ShopOrigin { STORED, FIXTURE }
sealed interface ShopScreenState {
    data object Loading : ShopScreenState
    data class Content(
        val revision: Revision,
        val name: String,
        val level: Int?,
        val ownedCount: Int?,
        val capacity: Int?,
        val balance: GameMoney?,
        val appearance: ShopAppearance,
        val vehicles: List<ShopVehicle>,
        val facilities: List<ShopFacility>,
        val expansion: ShopOption?,
        val decorations: List<ShopOption>,
        val origin: ShopOrigin,
    ) : ShopScreenState {
        init {
            require(level == null || level >= 1)
            require(ownedCount == null || ownedCount >= 0)
            require(capacity == null || capacity > 0)
            require(vehicles.map { it.id }.distinct().size == vehicles.size)
            require(facilities.map { it.facility }.distinct().size == facilities.size)
            val ids = listOfNotNull(expansion?.id) + facilities.map { it.option.id } + decorations.map { it.id }
            require(ids.distinct().size == ids.size)
        }
    }
    data class Empty(val message: String) : ShopScreenState
    data class Failure(val message: String) : ShopScreenState
    data class Unsupported(val message: String) : ShopScreenState
}

data class ShopSelection(val revision: Revision, val option: ShopOption)
data class ShopRequest(val requestId: RequestId, val selection: ShopSelection, val attemptId: String)
enum class ShopMode { OVERALL, VEHICLES }
/** 호스트가 View 수명 밖에서 보관한다. 복원 자체는 명령을 보내지 않는다. */
data class ShopUiState(
    val mode: ShopMode = ShopMode.OVERALL,
    val selection: ShopSelection? = null,
    val pending: ShopRequest? = null,
    val acknowledged: ShopSelection? = null,
    val requestIds: Map<ShopSelection, RequestId> = emptyMap(),
    val latestRevision: Revision? = null,
    val message: String? = null,
    val retryPending: Boolean = false,
)
sealed interface ShopEvent {
    data class Submit(val request: ShopRequest) : ShopEvent
    data object Retry : ShopEvent
}

/** UI 상태만 관리한다. 해금/잔액 판정, DB 쓰기, 경제 계산은 하지 않는다. */
class ShopInteraction(
    private val onEvent: (ShopEvent) -> Unit,
    private val nextRequestId: () -> RequestId = { RequestId(UUID.randomUUID().toString()) },
) {
    var state: ShopScreenState = ShopScreenState.Loading
        private set
    var uiState = ShopUiState()
        private set
    var controlsEnabled = false
        private set

    fun render(value: ShopScreenState, enabled: Boolean) {
        // 오래된 허용 입력으로 제한을 풀지 않는다. Loading/Failure 뒤에도 기준을 보존한다.
        if (!enabled) controlsEnabled = false
        if (value is ShopScreenState.Content) {
            if (value.revision.value < (uiState.latestRevision?.value ?: -1)) return
            uiState = uiState.copy(latestRevision = value.revision,
                requestIds = immutableMap(uiState.requestIds.filterKeys { it.revision == value.revision }))
        }
        state = if (value is ShopScreenState.Content) value.copy(
            vehicles = immutable(value.vehicles), facilities = immutable(value.facilities), decorations = immutable(value.decorations),
        ) else value
        controlsEnabled = enabled
        // 조회 중·제한 중에도 요청 식별자를 보존한다. 새 확정 상태에서 오래된 선택만 해제한다.
        if (state is ShopScreenState.Content && uiState.selection?.let { current(it) == null } == true)
            uiState = uiState.copy(selection = null, message = "표시 내용이 갱신됐습니다. 다시 선택해 주세요")
    }

    val selection: ShopSelection? get() = uiState.selection?.takeIf { current(it) != null }

    fun restoreUiState(value: ShopUiState) {
        val latest = listOfNotNull(uiState.latestRevision, value.latestRevision).maxByOrNull { it.value }
        uiState = value.copy(latestRevision = latest, requestIds = immutableMap(value.requestIds))
    }
    fun mode(value: ShopMode) { if (controlsEnabled) uiState = uiState.copy(mode = value) }
    private fun options(c: ShopScreenState.Content) = listOfNotNull(c.expansion) + c.facilities.map { it.option } + c.decorations
    private fun current(selection: ShopSelection): ShopOption? {
        val c = state as? ShopScreenState.Content ?: return null
        return options(c).find { c.revision == selection.revision && it == selection.option }
    }
    fun canSelect(selection: ShopSelection): Boolean = controlsEnabled && uiState.pending == null &&
        uiState.acknowledged?.revision != (state as? ShopScreenState.Content)?.revision && current(selection)?.let {
            it.availability == ShopAvailability.AVAILABLE && it.command != null && it.price != null && it.ruleVersion != null
        } == true

    fun select(selection: ShopSelection) {
        if (canSelect(selection)) uiState = uiState.copy(selection = selection, message = null)
    }
    fun cancel(): Boolean {
        if (!controlsEnabled || uiState.pending != null || uiState.selection == null) return false
        uiState = uiState.copy(selection = null, message = null)
        return true
    }
    fun confirm(selection: ShopSelection): Boolean {
        if (uiState.selection != selection || !canSelect(selection)) return false
        val requestId = uiState.requestIds[selection] ?: nextRequestId()
        val request = ShopRequest(requestId, selection, UUID.randomUUID().toString())
        uiState = uiState.copy(pending = request, message = null,
            requestIds = immutableMap(uiState.requestIds + (selection to requestId)))
        onEvent(ShopEvent.Submit(request))
        return true
    }
    /** 성공 응답만으로 표시 사실을 변경하지 않는다. 호스트가 확정 상태를 재조회해 render한다. */
    fun complete(request: ShopRequest, result: ContractResult<Unit>) {
        val pending = uiState.pending?.takeIf { it == request } ?: return
        uiState = when (result) {
            is ContractResult.Success -> uiState.copy(pending = null, selection = null, acknowledged = pending.selection,
                message = "처리 응답을 받았습니다. 확정 상태 재조회를 기다립니다")
            is ContractResult.Failure -> uiState.copy(pending = null, selection = null, message = "요청 실패: ${result.error}. 확정 상태를 확인하고 다시 선택해 주세요")
        }
    }
    fun retry() {
        if (!controlsEnabled || uiState.retryPending || (state !is ShopScreenState.Failure && state !is ShopScreenState.Empty)) return
        uiState = uiState.copy(retryPending = true)
        onEvent(ShopEvent.Retry)
    }
    fun completeRetry() { uiState = uiState.copy(retryPending = false) }
    private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
