package io.drivegrowth.core.contract

const val RENTAL_CONTRACT_VERSION = "rental-contract-v0"

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

enum class VehicleLifecycle { OWNED, SOLD, SCRAPPED, CONSUMED }

data class OwnedVehicle(
    val ownedVehicleId: OwnedVehicleId,
    val vehicleId: VehicleId,
    val level: Int,
    val revision: Revision,
    val lifecycle: VehicleLifecycle,
) {
    init { require(level >= 1) }
}

enum class MoneyDirection { CREDIT, DEBIT }
enum class TransactionKind { DRIVING, DELIVERY, RENTAL, FACILITY, GARAGE }

data class MoneyChange(
    val direction: MoneyDirection,
    val amount: GameMoney,
)

sealed interface VehicleWrite {
    data class Add(val vehicle: OwnedVehicle) : VehicleWrite
    data class Update(
        val ownedVehicleId: OwnedVehicleId,
        val expectedRevision: Revision,
        val level: Int,
        val lifecycle: VehicleLifecycle,
    ) : VehicleWrite {
        init { require(level >= 1) }
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
