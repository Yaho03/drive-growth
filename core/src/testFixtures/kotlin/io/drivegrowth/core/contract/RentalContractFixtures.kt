package io.drivegrowth.core.contract

import io.drivegrowth.core.Gear
import java.util.Collections
import kotlin.random.Random

object RentalContractFixtures {
    val time = EpochMillis(1_793_750_400_000L)
    val ruleVersion = RuleVersion("fixture-v0")
    val contentVersion = ContentVersion("fixture-catalog-v0")
    val vehicleId = VehicleId("vA")
    val firstVehicle = OwnedVehicle(
        OwnedVehicleId("v1"), vehicleId, 1, Revision(3), VehicleLifecycle.OWNED,
        Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT,
    )
    val secondVehicle = firstVehicle.copy(
        ownedVehicleId = OwnedVehicleId("v2"), rarity = Rarity.RARE, level = 4, levelProgressPercent = 50,
        acquisitionCost = GameMoney(250), acquisitionSource = VehicleAcquisitionSource.GACHA,
    )
    val paidGachaVehicle = secondVehicle.copy(
        ownedVehicleId = OwnedVehicleId("paid-gacha"), rarity = Rarity.ADVANCED,
        level = 2, levelProgressPercent = 25, revision = Revision(0), acquisitionCost = GameMoney(500),
    )
    val freeGachaVehicle = paidGachaVehicle.copy(
        ownedVehicleId = OwnedVehicleId("free-gacha"), rarity = Rarity.LEGEND,
        level = 1, levelProgressPercent = 0, acquisitionCost = GameMoney(0),
    )
    val deliveryVehicle = paidGachaVehicle.copy(
        ownedVehicleId = OwnedVehicleId("purchase-delivery"), acquisitionCost = GameMoney(800),
        acquisitionSource = VehicleAcquisitionSource.PURCHASE_DELIVERY,
    )

    /** 같은 차종의 revision0 신규 입력. 시험 성장값이며 영구 획득 이력은 아니다. */
    fun vehicleExamples(): List<OwnedVehicle> = Collections.unmodifiableList(
        listOf(firstVehicle.copy(revision = Revision(0)), secondVehicle.copy(revision = Revision(0)),
            paidGachaVehicle, freeGachaVehicle, deliveryVehicle),
    )

    fun random() = Random(33)

    fun parkedVehicle() = VehicleObservation(
        source = InputSource.VEHICLE,
        receivedElapsedMs = 100,
        receivedAt = time,
        speedMps = Observation.Available(0.0),
        gear = Observation.Available(Gear.PARK),
        uxRestricted = Observation.Available(false),
    )

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
    private var gachaState: GachaState? = null,
) : GachaChangeStore {
    private val vehiclesById = vehicles.associateBy { it.ownedVehicleId }.toMutableMap()
    private val committed = mutableMapOf<RequestId, Pair<GameChange, Settlement>>()
    private val gachaCommitted = mutableMapOf<RequestId, GachaSettlement>()
    private var balance = initialBalance
    var nextCommitFailure: ContractError? = null

    @Synchronized
    override fun listOwnedVehicles(): ContractResult<List<OwnedVehicle>> =
        ContractResult.Success(Collections.unmodifiableList(vehiclesById.values.filter { it.lifecycle == VehicleLifecycle.OWNED }))

    @Synchronized
    override fun commitGameChange(change: GameChange): ContractResult<Settlement> {
        if (change.requestId in gachaCommitted) return failure(ContractError.STATE_CONFLICT)
        committed[change.requestId]?.let { (original, result) ->
            return if (original == change) ContractResult.Success(result) else failure(ContractError.STATE_CONFLICT)
        }
        val authority = gachaState?.authority
        if (authority != null && change.source != authority.source) return failure(ContractError.INVALID_INPUT)
        nextCommitFailure?.let {
            nextCommitFailure = null
            return failure(it)
        }

        val updatedVehicle = when (val write = change.vehicle) {
            null -> null
            is VehicleWrite.Add -> {
                val vehicle = write.vehicle
                if (vehicle.ownedVehicleId in vehiclesById) return failure(ContractError.STATE_CONFLICT)
                // 명시적으로 구성한 권위 공간은 예전 Add 진입점과도 공유한다.
                if (authority != null && vehiclesById.values.count { it.lifecycle == VehicleLifecycle.OWNED } >= authority.garageCapacity)
                    return failure(ContractError.CAPACITY_EXCEEDED)
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
                if (write.lifecycle != VehicleLifecycle.OWNED &&
                    (write.level != previous.level || write.levelProgressPercent != previous.levelProgressPercent)) {
                    return failure(ContractError.INVALID_INPUT)
                }
                previous.copy(
                    level = write.level,
                    levelProgressPercent = write.levelProgressPercent,
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

    @Synchronized
    override fun readGachaState(): ContractResult<GachaState> =
        gachaState?.let { ContractResult.Success(it) } ?: failure(ContractError.DATA_UNAVAILABLE)

    @Synchronized
    override fun commitGacha(command: GachaCommand): ContractResult<GachaSettlement> {
        gachaCommitted[command.requestId]?.let { original ->
            return if (original.command == command) ContractResult.Success(original) else failure(ContractError.STATE_CONFLICT)
        }
        if (command.requestId in committed) return failure(ContractError.STATE_CONFLICT)
        val state = gachaState ?: return failure(ContractError.DATA_UNAVAILABLE)
        val authority = state.authority
        val progress = state.progress
        if (command.source != authority.source) return failure(ContractError.INVALID_INPUT)
        if (command.contentVersion != authority.contentVersion || command.ruleVersion != authority.ruleVersion ||
            command.expectedRevision != progress.revision || command.drawsSinceGuarantee != progress.drawsSinceGuarantee ||
            command.shopLevel != authority.shopLevel || command.garageCapacity != authority.garageCapacity)
            return failure(ContractError.STATE_CONFLICT)
        if (vehiclesById.values.count { it.lifecycle == VehicleLifecycle.OWNED } >= authority.garageCapacity)
            return failure(ContractError.CAPACITY_EXCEEDED)
        if (progress.revision.value == Int.MAX_VALUE) return failure(ContractError.INVALID_INPUT)
        if (authority.unlockedVehicles.isEmpty()) return failure(ContractError.DATA_UNAVAILABLE)

        val award = command.award
        val vehicle = award.vehicle
        val price = if (command.ticket == GachaTicketUse.PURCHASED) authority.purchasePrice else GameMoney(0)
        val guaranteed = progress.drawsSinceGuarantee + 1 == authority.pity
        val nextCounter = if (vehicle.rarity >= authority.guaranteedFrom) 0 else progress.drawsSinceGuarantee + 1
        if (vehicle.vehicleId !in authority.unlockedVehicles || vehicle.rarity !in authority.eligibleRarities ||
            vehicle.revision != Revision(0) || vehicle.lifecycle != VehicleLifecycle.OWNED ||
            vehicle.acquisitionSource != VehicleAcquisitionSource.GACHA || vehicle.level != 1 || vehicle.levelProgressPercent != 0 ||
            vehicle.acquisitionCost != price || award.guaranteed != guaranteed || award.nextCounter != nextCounter ||
            (guaranteed && vehicle.rarity < authority.guaranteedFrom)) return failure(ContractError.INVALID_INPUT)
        if (command.ticket == GachaTicketUse.FREE && progress.freeTickets == 0L)
            return failure(ContractError.INSUFFICIENT_FUNDS)
        val updatedProgress = GachaProgress(Revision(progress.revision.value + 1), nextCounter,
            progress.freeTickets - if (command.ticket == GachaTicketUse.FREE) 1 else 0)
        val change = GameChange(command.requestId, TransactionKind.GARAGE, command.source, command.ruleVersion,
            money = if (command.ticket == GachaTicketUse.PURCHASED) MoneyChange(MoneyDirection.DEBIT, price) else null,
            vehicle = VehicleWrite.Add(vehicle))
        // 같은 락·잔액·차량·기존 정산을 재사용한다. 모든 검증/실패는 변경 전에 끝낸다.
        return when (val result = commitGameChange(change)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> {
                val receipt = GachaSettlement(command, result.value, updatedProgress)
                gachaState = state.copy(progress = updatedProgress)
                gachaCommitted[command.requestId] = receipt
                ContractResult.Success(receipt)
            }
        }
    }

    private fun failure(error: ContractError) = ContractResult.Failure(error)
}
