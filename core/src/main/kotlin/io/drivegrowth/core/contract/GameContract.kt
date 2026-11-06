package io.drivegrowth.core.contract

const val RENTAL_CONTRACT_VERSION = "rental-contract-v1"

@JvmInline
value class VehicleId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class OwnedVehicleId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class RequestId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class TransactionId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class SettlementId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class RuleVersion(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class ContentVersion(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class Revision(val value: Int) {
    init { require(value >= 0) }
}

@JvmInline
value class GameMoney(val units: Long) {
    init { require(units >= 0) }
}

@JvmInline
value class EpochMillis(val value: Long)

enum class InputSource { VEHICLE, SIMULATION }
enum class MissingReason { NO_DATA, PERMISSION_DENIED, UNSUPPORTED, STALE }

sealed interface Observation<out T> {
    data class Available<T>(val value: T) : Observation<T>
    data class Missing(val reason: MissingReason) : Observation<Nothing>
}

data class ObservedDistance(val meters: Long, val source: InputSource) {
    init { require(meters >= 0) }
}

data class EstimatedRouteDistance(val meters: Long, val source: InputSource) {
    init { require(meters >= 0) }
}

data class DeliveryDistanceInput(
    val observed: Observation<ObservedDistance>,
    val estimated: Observation<EstimatedRouteDistance>,
)

enum class ContractError {
    INVALID_INPUT,
    POLICY_UNRESOLVED,
    DATA_UNAVAILABLE,
    PERMISSION_DENIED,
    UX_RESTRICTED,
    INSUFFICIENT_FUNDS,
    CAPACITY_EXCEEDED,
    STATE_CONFLICT,
    STORAGE_FAILURE,
}

sealed interface ContractResult<out T> {
    data class Success<T>(val value: T) : ContractResult<T>
    data class Failure(val error: ContractError) : ContractResult<Nothing>
}

data class VehicleDefinition(val vehicleId: VehicleId, val displayName: String) {
    init { require(displayName.isNotBlank()) }
}

data class VehicleCatalogSnapshot(
    val contentVersion: ContentVersion,
    val vehicles: List<VehicleDefinition>,
)

fun interface VehicleCatalog {
    fun load(): ContractResult<VehicleCatalogSnapshot>
}

// 차종이 아닌 보유 개체의 희귀도. 순서가 비교 순서다(COMMON < ... < LEGEND).
enum class Rarity { COMMON, ADVANCED, RARE, LEGEND }
enum class VehicleAcquisitionSource { INITIAL_GRANT, GACHA, PURCHASE_DELIVERY }
enum class VehicleLifecycle { OWNED, SOLD, SCRAPPED, CONSUMED }

const val MAX_VEHICLE_LEVEL = 20

private fun requireVehicleProgress(level: Int, progressPercent: Int) {
    require(level in 1..MAX_VEHICLE_LEVEL) { "레벨은 1..$MAX_VEHICLE_LEVEL" }
    require(progressPercent in 0..99) { "레벨 진행량은 0..99 퍼센트포인트" }
    require(level < MAX_VEHICLE_LEVEL || progressPercent == 0) { "최대 레벨의 진행량은 0" }
}

data class OwnedVehicle(
    val ownedVehicleId: OwnedVehicleId,
    val vehicleId: VehicleId,
    val level: Int,
    val revision: Revision,
    val lifecycle: VehicleLifecycle,
    val rarity: Rarity,
    val levelProgressPercent: Int,
    /** 실제 취득 때 지출한 게임머니. 무료 획득의 0은 정보 누락과 다르다. */
    val acquisitionCost: GameMoney,
    val acquisitionSource: VehicleAcquisitionSource,
) {
    init {
        requireVehicleProgress(level, levelProgressPercent)
        if (acquisitionSource == VehicleAcquisitionSource.INITIAL_GRANT) {
            require(rarity == Rarity.COMMON && acquisitionCost == GameMoney(0)) { "첫 지급은 일반 희귀도·취득 기준액 0" }
        }
    }
}

enum class MoneyDirection { CREDIT, DEBIT }
enum class TransactionKind { DRIVING, DELIVERY, RENTAL, FACILITY, GARAGE }

data class MoneyChange(
    val direction: MoneyDirection,
    val amount: GameMoney,
)

sealed interface VehicleWrite {
    data class Add(val vehicle: OwnedVehicle) : VehicleWrite
    /** 레벨·진행량을 함께 명시한다. 처분에서는 저장된 성장값을 보존하며 취득 정보는 변경하지 않는다. */
    data class Update(
        val ownedVehicleId: OwnedVehicleId,
        val expectedRevision: Revision,
        val level: Int,
        val lifecycle: VehicleLifecycle,
        val levelProgressPercent: Int,
    ) : VehicleWrite {
        init { requireVehicleProgress(level, levelProgressPercent) }
    }
}

data class GameChange(
    val requestId: RequestId,
    val kind: TransactionKind,
    val source: InputSource,
    val ruleVersion: RuleVersion,
    val money: MoneyChange? = null,
    val vehicle: VehicleWrite? = null,
) {
    init { require(money != null || vehicle != null) }
}

data class Transaction(
    val transactionId: TransactionId,
    val requestId: RequestId,
    val kind: TransactionKind,
    val source: InputSource,
    val ruleVersion: RuleVersion,
    val money: MoneyChange?,
)

data class Settlement(
    val settlementId: SettlementId,
    val transaction: Transaction,
    val balance: GameMoney,
    val changedVehicle: OwnedVehicle?,
    val confirmedAt: EpochMillis,
)

interface GameChangeStore {
    fun commitGameChange(change: GameChange): ContractResult<Settlement>
    fun listOwnedVehicles(): ContractResult<List<OwnedVehicle>>
}
