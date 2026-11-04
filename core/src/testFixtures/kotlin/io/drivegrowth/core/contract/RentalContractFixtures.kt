package io.drivegrowth.core.contract

import kotlin.random.Random

object RentalContractFixtures {
    val time = EpochMillis(1_793_750_400_000L)
    val ruleVersion = RuleVersion("fixture-v0")
    val contentVersion = ContentVersion("fixture-catalog-v0")
    val vehicleId = VehicleId("vA")
    val firstVehicle = OwnedVehicle(OwnedVehicleId("v1"), vehicleId, 1, Revision(3), VehicleLifecycle.OWNED)
    val secondVehicle = firstVehicle.copy(ownedVehicleId = OwnedVehicleId("v2"))

    fun random() = Random(33)

    fun distanceInput() = DeliveryDistanceInput(
        observed = Observation.Available(ObservedDistance(30_000, InputSource.VEHICLE)),
        estimated = Observation.Available(EstimatedRouteDistance(20_000, InputSource.SIMULATION)),
    )

    fun catalog(failure: ContractError? = null): VehicleCatalog = VehicleCatalog {
        failure?.let { ContractResult.Failure(it) }
            ?: ContractResult.Success(VehicleCatalogSnapshot(contentVersion, listOf(VehicleDefinition(vehicleId, "시험 차량"))))
    }

    fun store() = InMemoryGameChangeStore(
        listOf(firstVehicle, secondVehicle),
        confirmedAt = time,
    )
}

/** 계약 소비 테스트에만 사용한다. 디스크 저장이나 제품 정책을 대신하지 않는다. */
class InMemoryGameChangeStore(
    vehicles: List<OwnedVehicle>,
    initialBalance: GameMoney = GameMoney(0),
    private val confirmedAt: EpochMillis = RentalContractFixtures.time,
) : GameChangeStore {
    private val vehiclesById = vehicles.associateBy { it.ownedVehicleId }.toMutableMap()
    private val committed = mutableMapOf<RequestId, Pair<GameChange, Settlement>>()
    private var balance = initialBalance
    var nextCommitFailure: ContractError? = null

    @Synchronized
    override fun listOwnedVehicles(): ContractResult<List<OwnedVehicle>> =
        ContractResult.Success(vehiclesById.values.filter { it.lifecycle == VehicleLifecycle.OWNED })

    @Synchronized
    override fun commitGameChange(change: GameChange): ContractResult<Settlement> {
        committed[change.requestId]?.let { (original, result) ->
            return if (original == change) ContractResult.Success(result) else failure(ContractError.STATE_CONFLICT)
        }
        nextCommitFailure?.let {
            nextCommitFailure = null
            return failure(it)
        }

        val updatedVehicle = when (val write = change.vehicle) {
            null -> null
            is VehicleWrite.Add -> {
                val vehicle = write.vehicle
                if (vehicle.ownedVehicleId in vehiclesById) return failure(ContractError.STATE_CONFLICT)
                if (vehicle.revision != Revision(0) || vehicle.lifecycle != VehicleLifecycle.OWNED) {
                    return failure(ContractError.INVALID_INPUT)
                }
                vehicle
            }
            is VehicleWrite.Update -> {
                val previous = vehiclesById[write.ownedVehicleId] ?: return failure(ContractError.DATA_UNAVAILABLE)
                if (previous.revision != write.expectedRevision || previous.lifecycle != VehicleLifecycle.OWNED) {
                    return failure(ContractError.STATE_CONFLICT)
                }
                if (previous.revision.value == Int.MAX_VALUE) return failure(ContractError.INVALID_INPUT)
                previous.copy(
                    level = write.level,
                    lifecycle = write.lifecycle,
                    revision = Revision(previous.revision.value + 1),
                )
            }
        }

        val updatedBalance = when (val money = change.money) {
            null -> balance
            else -> when (money.direction) {
                MoneyDirection.CREDIT -> {
                    if (money.amount.units > Long.MAX_VALUE - balance.units) return failure(ContractError.INVALID_INPUT)
                    GameMoney(balance.units + money.amount.units)
                }
                MoneyDirection.DEBIT -> {
                    if (money.amount.units > balance.units) return failure(ContractError.INSUFFICIENT_FUNDS)
                    GameMoney(balance.units - money.amount.units)
                }
            }
        }

        val result = Settlement(
            SettlementId("fixture-settlement-${change.requestId.value}"),
            Transaction(
                TransactionId("fixture-transaction-${change.requestId.value}"),
                change.requestId, change.kind, change.source, change.ruleVersion, change.money,
            ),
            updatedBalance, updatedVehicle, confirmedAt,
        )
        balance = updatedBalance
        if (updatedVehicle != null) vehiclesById[updatedVehicle.ownedVehicleId] = updatedVehicle
        committed[change.requestId] = change to result
        return ContractResult.Success(result)
    }

    private fun failure(error: ContractError) = ContractResult.Failure(error)
}
