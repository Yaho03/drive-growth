package io.drivegrowth.app.ui.gacha

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections
import java.util.UUID

enum class GachaAvailability { READY, EMPTY_CANDIDATES, GUARANTEE_UNAVAILABLE, NO_SPACE, INSUFFICIENT_MONEY, INSUFFICIENT_TICKETS, MISSING, UNSUPPORTED, INVALID_DISPLAY }
enum class GachaOptionKind { PAID, FREE }
enum class GachaOrigin { STORED, FIXTURE }

data class GachaOption(
    val id: String, val title: String, val kind: GachaOptionKind,
    val moneyCost: GameMoney?, val ticketCost: Int?, val ownedTickets: Int?,
    val requestId: RequestId?, val availability: GachaAvailability, val description: String,
) {
    init { require(id.isNotBlank()); require(ticketCost == null || ticketCost >= 0); require(ownedTickets == null || ownedTickets >= 0) }
    fun displayProblem(): GachaAvailability? = when {
        availability != GachaAvailability.READY -> availability
        requestId == null || moneyCost == null || ticketCost == null || ownedTickets == null -> GachaAvailability.MISSING
        else -> null
    }
}
/** 횟수·이번 보장 여부는 공급값이다. UI에서 counter로 계산하지 않는다. */
data class GachaGuarantee(val remaining: Int?, val nextGuaranteed: Boolean?, val available: Boolean?, val description: String)
data class GachaSpace(val owned: Int, val capacity: Int, val availability: GachaAvailability) {
    init { require(owned >= 0 && capacity >= 0) }
}

/** 이번 pool의 원시 가중치와 분모를 보존하는 정확한 비율이다. */
class GachaRatio(numerators: Map<Rarity, Long>, val denominator: Long) {
    val numerators: Map<Rarity, Long> = Collections.unmodifiableMap(LinkedHashMap(numerators))
    fun valid(): Boolean = denominator > 0 && numerators.keys == Rarity.entries.toSet() &&
        numerators.values.all { it >= 0 } &&
        numerators.values.fold(BigInteger.ZERO) { sum, value -> sum + BigInteger.valueOf(value) } == BigInteger.valueOf(denominator)
    override fun equals(other: Any?): Boolean = other is GachaRatio && denominator == other.denominator && numerators == other.numerators
    override fun hashCode(): Int = 31 * numerators.hashCode() + denominator.hashCode()
}

data class GachaOffers(
    val content: GameContentSnapshot?,
    val candidates: List<VehicleId>?,
    /** 이번 선택에서 실제 적용할 퍼센트. 기본 가중치의 변환/보장 재분배를 UI에서 하지 않는다. */
    val appliedPercent: Map<Rarity, BigDecimal>?,
    val guarantee: GachaGuarantee?, val space: GachaSpace?,
    val balance: GameMoney?, val ticketPurchasePrice: GameMoney?,
    val options: List<GachaOption>, val availability: GachaAvailability, val origin: GachaOrigin,
    val appliedRatio: GachaRatio? = null,
) {
    init { require(options.map { it.id }.distinct().size == options.size) }
    fun ratesValid(): Boolean {
        val percent = appliedPercent
        val ratio = appliedRatio
        if (percent == null && ratio == null) return false
        if (percent != null && (percent.keys != Rarity.entries.toSet() ||
            percent.values.any { it < BigDecimal.ZERO || it > BigDecimal(100) } ||
            percent.values.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal(100)) != 0)) return false
        if (ratio != null && !ratio.valid()) return false
        // 둘 다 공급했으면 반올림 없이 교차 곱해 같은 확률인지 검사한다.
        return percent == null || ratio == null || Rarity.entries.all { rarity ->
            percent.getValue(rarity).multiply(BigDecimal.valueOf(ratio.denominator))
                .compareTo(BigDecimal.valueOf(ratio.numerators.getValue(rarity)).multiply(BigDecimal(100))) == 0
        }
    }
    fun displayProblem(): GachaAvailability? {
        if (availability != GachaAvailability.READY) return availability
        val keys = options.mapNotNull { it.requestId }
        if (keys.distinct().size != keys.size) return GachaAvailability.INVALID_DISPLAY
        val c = content ?: return GachaAvailability.MISSING
        val ids = candidates ?: return GachaAvailability.MISSING
        if (ids.isEmpty()) return GachaAvailability.EMPTY_CANDIDATES
        if (ids.distinct().size != ids.size || ids.any { id -> c.catalog.vehicles.count { it.vehicleId == id } != 1 || c.vehicles.count { it.vehicleId == id } != 1 }) return GachaAvailability.INVALID_DISPLAY
        if (appliedPercent == null && appliedRatio == null) return GachaAvailability.MISSING
        if (!ratesValid()) return GachaAvailability.INVALID_DISPLAY
        val g = guarantee ?: return GachaAvailability.MISSING
        if (g.available == false) return GachaAvailability.GUARANTEE_UNAVAILABLE
        if (g.available == null || g.remaining == null || g.nextGuaranteed == null) return GachaAvailability.MISSING
        if (g.remaining !in 1..c.gacha.pity) return GachaAvailability.INVALID_DISPLAY
        val s = space ?: return GachaAvailability.MISSING
        if (s.availability != GachaAvailability.READY) return s.availability
        return null
    }
}

data class GachaConfirmedResult(val settlement: Settlement, val vehicleName: String?, val summary: String, val origin: GachaOrigin) {
    init { require(settlement.changedVehicle?.lifecycle == VehicleLifecycle.OWNED) }
}
sealed interface GachaScreen {
    data object Loading : GachaScreen
    data class Offers(val value: GachaOffers) : GachaScreen
    data class Result(val value: GachaConfirmedResult) : GachaScreen
    data class Failure(val error: ContractError, val message: String, val retryAllowed: Boolean) : GachaScreen
    data class Missing(val message: String, val retryAllowed: Boolean) : GachaScreen
    data class Unsupported(val message: String) : GachaScreen
}
/** 표시 공급 세대. 경제 요청 키나 DB revision이 아니다. */
data class GachaFrame(val generation: Long, val screen: GachaScreen) { init { require(generation >= 0) } }
data class GachaDrawInput(val offers: GachaOffers, val option: GachaOption)
data class GachaRequest(val generation: Long, val input: GachaDrawInput, val attemptId: String)
/** 차고 이동 시도 상관값. 경제 RequestId와 별개다. */
data class GachaGarageRequest(val settlementId: SettlementId, val ownedVehicleId: OwnedVehicleId, val token: String)
data class GachaUiState(
    val frame: GachaFrame? = null, val selected: String? = null, val pending: GachaRequest? = null,
    val reservations: Map<String, GachaDrawInput> = emptyMap(),
    val acknowledged: Set<RequestId> = emptySet(), val failedKeys: Set<RequestId> = emptySet(),
    val failure: ContractError? = null, val reloadToken: String? = null, val garagePending: GachaGarageRequest? = null,
)
sealed interface GachaEvent {
    data class Draw(val request: GachaRequest) : GachaEvent
    data class Reload(val token: String) : GachaEvent
    data class OpenGarage(val request: GachaGarageRequest) : GachaEvent
}

/** 표시·전송 경계만 관리한다. 추첨·재분배·해금·금액 판단·저장을 실행하지 않는다. */
class GachaInteraction(private val onEvent: (GachaEvent) -> Unit, restored: GachaUiState = GachaUiState()) {
    var uiState: GachaUiState = restored.snapshot()
        private set
    val frame: GachaFrame? get() = uiState.frame
    var controlsEnabled = false
        private set
    private var released = false

    fun render(value: GachaFrame, enabled: Boolean): Boolean {
        if (released) return false
        if (!enabled) controlsEnabled = false
        val prior = frame
        if (prior != null && (value.generation < prior.generation || value.generation == prior.generation && value != prior)) return false
        uiState = uiState.copy(frame = value.snapshot(), selected = uiState.selected.takeIf { prior == value })
        if (value.screen is GachaScreen.Result) {
            val key = value.screen.value.settlement.transaction.requestId
            uiState = uiState.copy(reservations = frozenMap(uiState.reservations.filterValues { it.option.requestId != key }),
                acknowledged = frozenSet(uiState.acknowledged + key), failedKeys = frozenSet(uiState.failedKeys - key),
                pending = uiState.pending.takeUnless { it?.input?.option?.requestId == key })
        }
        controlsEnabled = enabled
        return true
    }
    val awaitingConfirmation: Boolean get() = uiState.reservations.values.any { it.option.requestId in uiState.acknowledged }
    fun canSelect(id: String): Boolean {
        if (released || !controlsEnabled || uiState.pending != null || uiState.reloadToken != null || awaitingConfirmation) return false
        val offers = (frame?.screen as? GachaScreen.Offers)?.value ?: return false
        if (offers.displayProblem() != null) return false
        val option = offers.options.singleOrNull { it.id == id } ?: return false
        if (option.displayProblem() != null) return false
        if (option.requestId in uiState.acknowledged) return false
        val input = GachaDrawInput(offers, option)
        if (uiState.reservations[id]?.let { it != input } == true) return false
        return uiState.reservations.values.none { it.option.requestId == option.requestId && it != input }
    }
    fun select(id: String): Boolean {
        if (!canSelect(id)) return false
        uiState = uiState.copy(selected = id, failure = null); return true
    }
    fun cancel(): Boolean {
        if (released || !controlsEnabled || uiState.pending != null || uiState.selected == null) return false
        uiState = uiState.copy(selected = null); return true
    }
    fun confirm(): Boolean {
        val id = uiState.selected ?: return false
        if (!canSelect(id)) return false
        val offers = (frame!!.screen as GachaScreen.Offers).value
        val input = GachaDrawInput(offers, offers.options.single { it.id == id })
        val request = GachaRequest(frame!!.generation, input, UUID.randomUUID().toString())
        uiState = uiState.copy(pending = request, reservations = frozenMap(uiState.reservations + (id to input)), failure = null)
        onEvent(GachaEvent.Draw(request)); return true
    }
    /** 성공 응답만으로 결과 화면을 만들지 않는다. 저장된 결과의 새 세대 render를 기다린다. */
    fun complete(request: GachaRequest, result: ContractResult<Unit>) {
        if (released || uiState.pending != request) return
        val key = request.input.option.requestId!!
        uiState = when (result) {
            is ContractResult.Success -> uiState.copy(pending = null, selected = null, acknowledged = frozenSet(uiState.acknowledged + key), failedKeys = frozenSet(uiState.failedKeys - key), failure = null)
            is ContractResult.Failure -> uiState.copy(pending = null, selected = null, failedKeys = frozenSet(uiState.failedKeys + key), failure = result.error)
        }
    }
    /** 호스트가 이전 요청의 확정 실패를 조회한 뒤에만 호출한다. 새 옵션/재화만으로 키를 버리지 않는다. */
    fun resolveFailedRequest(key: RequestId): Boolean {
        if (released || uiState.pending?.input?.option?.requestId == key || key !in uiState.failedKeys) return false
        uiState = uiState.copy(reservations = frozenMap(uiState.reservations.filterValues { it.option.requestId != key }), failedKeys = frozenSet(uiState.failedKeys - key))
        return true
    }
    fun reload(): Boolean {
        val allowed = when(val s = frame?.screen) { is GachaScreen.Failure -> s.retryAllowed; is GachaScreen.Missing -> s.retryAllowed; else -> false }
        if (released || !controlsEnabled || uiState.reloadToken != null || uiState.pending != null || !allowed) return false
        val token = UUID.randomUUID().toString(); uiState = uiState.copy(reloadToken = token)
        onEvent(GachaEvent.Reload(token)); return true
    }
    fun completeReload(token: String) { if (!released && token == uiState.reloadToken) uiState = uiState.copy(reloadToken = null) }
    fun openGarage(): Boolean {
        val result = (frame?.screen as? GachaScreen.Result)?.value ?: return false
        if (released || !controlsEnabled || uiState.garagePending != null) return false
        val request = GachaGarageRequest(result.settlement.settlementId, result.settlement.changedVehicle!!.ownedVehicleId, UUID.randomUUID().toString())
        uiState = uiState.copy(garagePending = request)
        onEvent(GachaEvent.OpenGarage(request)); return true
    }
    fun completeGarageNavigation(request: GachaGarageRequest) { if (!released && request == uiState.garagePending) uiState = uiState.copy(garagePending = null) }
    fun release() { released = true; controlsEnabled = false }
}

private fun GachaOffers.snapshot() = copy(
    content = content?.copy(catalog = content.catalog.copy(vehicles = frozenList(content.catalog.vehicles)), vehicles = frozenList(content.vehicles), gacha = content.gacha.copy(weights = frozenMap(content.gacha.weights))),
    candidates = candidates?.let { frozenList(it) }, appliedPercent = appliedPercent?.let { frozenMap(it) }, options = frozenList(options),
)
private fun GachaFrame.snapshot() = copy(screen = when(val s = screen) { is GachaScreen.Offers -> s.copy(value = s.value.snapshot()); else -> s })
private fun GachaUiState.snapshot() = copy(frame = frame?.snapshot(),
    pending = pending?.copy(input = pending.input.copy(offers = pending.input.offers.snapshot())),
    reservations = frozenMap(reservations.mapValues { (_, v) -> v.copy(offers = v.offers.snapshot()) }), acknowledged = frozenSet(acknowledged), failedKeys = frozenSet(failedKeys))
private fun <T> frozenList(v: List<T>): List<T> = Collections.unmodifiableList(ArrayList(v))
private fun <K,V> frozenMap(v: Map<K,V>): Map<K,V> = Collections.unmodifiableMap(LinkedHashMap(v))
private fun <T> frozenSet(v: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(v))
