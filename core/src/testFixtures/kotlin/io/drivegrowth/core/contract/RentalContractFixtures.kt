package io.drivegrowth.core.contract

import io.drivegrowth.core.Gear
import io.drivegrowth.core.progression.VehicleActivity
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

/** 재료 참조 시험의 명시적 권위/활동 입력. 제품 초기 상태나 IDLE 기본값이 아니다. */
class MaterialFixtureConfiguration(
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    activities: Map<OwnedVehicleId, ContractResult<VehicleActivity>>,
) {
    val activities: Map<OwnedVehicleId, ContractResult<VehicleActivity>> =
        Collections.unmodifiableMap(HashMap(activities))
}

/** 계약 소비 테스트에만 사용한다. 디스크 저장이나 제품 정책을 대신하지 않는다. */
class InMemoryGameChangeStore(
    vehicles: List<OwnedVehicle>,
    initialBalance: GameMoney = GameMoney(0),
    private val confirmedAt: EpochMillis = RentalContractFixtures.time,
    private var gachaState: GachaState? = null,
    private val materialConfiguration: MaterialFixtureConfiguration? = null,
    private val repairConfiguration: RepairFixtureConfiguration? = null,
    private val initializationConfiguration: InitializationFixtureConfiguration? = null,
    private val firstRentalGiftConfiguration: FirstRentalGiftFixtureConfiguration? = null,
) : FirstRentalGiftStore, MaterialChangeStore, RepairChangeStore, InitializationChangeStore {
    init {
        val gachaSource = gachaState?.authority?.source
        require(gachaSource == null || materialConfiguration == null || gachaSource == materialConfiguration.source) {
            "같은 메모리 원장의 가챠와 재료 출처는 같아야 한다"
        }
        val initializationSource = (initializationConfiguration?.state as? ContractResult.Success)?.value?.source
        val sources = listOfNotNull(gachaSource, materialConfiguration?.source, repairConfiguration?.source, initializationSource, firstRentalGiftConfiguration?.source)
        require(sources.distinct().size <= 1) { "같은 메모리 원장의 출처는 같아야 한다" }
        val initializationProfile = (initializationConfiguration?.state as? ContractResult.Success)?.value?.profileId
        require(initializationProfile == null || firstRentalGiftConfiguration == null || initializationProfile == firstRentalGiftConfiguration.profileId) {
            "초기화와 첫 영업 선물은 같은 프로필 원장을 사용해야 한다"
        }
        materialConfiguration?.activities?.forEach { (id, activity) ->
            val repairActivity = repairConfiguration?.activities?.get(id)
            require(repairActivity == null || repairActivity == activity) { "같은 개체의 활동 입력은 같아야 한다" }
        }
    }

    // 모든 capability가 같은 현재 활동을 읽는다. 수리 완료는 이 Map에서만 IDLE로 전환한다.
    private val activities = (materialConfiguration?.activities.orEmpty() + repairConfiguration?.activities.orEmpty()).toMutableMap()
    private val vehiclesById = vehicles.associateBy { it.ownedVehicleId }.toMutableMap()
    private val committed = mutableMapOf<RequestId, Pair<GameChange, Settlement>>()
    private val gachaCommitted = mutableMapOf<RequestId, GachaSettlement>()
    private val materialCommitted = mutableMapOf<RequestId, MaterialSettlement>()
    private val repairCommitted = mutableMapOf<RequestId, RepairSettlement>()
    private var initializationResult = (((initializationConfiguration?.state as? ContractResult.Success)?.value?.status)
        as? ProfileInitializationStatus.Existing)?.original
    private var initializationMarker = initializationConfiguration?.marker
    private var firstRentalGift = firstRentalGiftConfiguration?.granted
    private var firstRentalGiftMarker = firstRentalGiftConfiguration?.marker
    private var firstRentalGiftOriginalState = firstRentalGiftConfiguration?.originalState
    private var balance = initialBalance
    var nextCommitFailure: ContractError? = null

    private fun hasFirstRentalGiftRequest(requestId: RequestId) = firstRentalGiftMarker?.requestId == requestId ||
        firstRentalGift?.command?.requestId == requestId

    /** 원래 결과와 당시 입력의 연결만 검증한다. 현재 잔액/가챠/영업 조회 실패로 과거 결과를 덮지 않는다. */
    private fun readOriginalFirstRentalGift(): ContractResult<FirstRentalGiftSettlement?> {
        val configuration = firstRentalGiftConfiguration ?: return failure(ContractError.DATA_UNAVAILABLE)
        val original = firstRentalGift
        val marker = firstRentalGiftMarker
        val before = firstRentalGiftOriginalState
        if (original == null && marker == null && before == null) return ContractResult.Success(null)
        if (original == null || marker == null || before == null || before.granted != null ||
            before.profileId != configuration.profileId || before.source != configuration.source)
            return failure(ContractError.STORAGE_FAILURE)
        val prepared = (prepareFirstRentalGift(original.command, before) as? ContractResult.Success)?.value
            as? FirstRentalGiftPreparation.Ready ?: return failure(ContractError.STORAGE_FAILURE)
        val transaction = original.transaction
        if (original.balance != prepared.balance || original.progress != prepared.progress ||
            original.marker != prepared.marker || marker != prepared.marker ||
            transaction.requestId != original.command.requestId || transaction.source != original.command.source ||
            transaction.ruleVersion != original.command.ruleVersion || transaction.kind != TransactionKind.GARAGE || transaction.money != null)
            return failure(ContractError.STORAGE_FAILURE)
        return ContractResult.Success(original)
    }

    @Synchronized
    override fun readFirstRentalGift(profileId: ProfileId): ContractResult<FirstRentalGiftState> {
        val configuration = firstRentalGiftConfiguration ?: return failure(ContractError.DATA_UNAVAILABLE)
        if (profileId != configuration.profileId) return failure(ContractError.INVALID_INPUT)
        val original = when (val read = readOriginalFirstRentalGift()) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        val currentGacha = gachaState ?: return failure(ContractError.DATA_UNAVAILABLE)
        val rental = when (val read = configuration.firstCompletedRental) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        return ContractResult.Success(FirstRentalGiftState(configuration.profileId, configuration.source,
            configuration.contentVersion, configuration.ruleVersion, balance, currentGacha.progress, rental, original))
    }

    @Synchronized
    override fun commitFirstRentalGift(command: FirstRentalGiftCommand): ContractResult<FirstRentalGiftSettlement> {
        val original = when (val read = readOriginalFirstRentalGift()) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        if (original != null) {
            // 위에서 검증한 기록을 사용한다. 현재 권위/진행/완료 조회 없이 원래 전체 명령을 재생한다.
            val history = checkNotNull(firstRentalGiftOriginalState).copy(granted = original)
            return when (val replay = prepareFirstRentalGift(command, history)) {
                is ContractResult.Failure -> replay
                is ContractResult.Success -> ContractResult.Success((replay.value as FirstRentalGiftPreparation.Replay).result)
            }
        }
        if (command.requestId in committed || command.requestId in gachaCommitted || command.requestId in materialCommitted ||
            command.requestId in repairCommitted || hasInitializationRequest(command.requestId))
            return failure(ContractError.STATE_CONFLICT)
        val current = when (val read = readFirstRentalGift(command.profileId)) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        val prepared = when (val result = prepareFirstRentalGift(command, current)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value as FirstRentalGiftPreparation.Ready
        }
        nextCommitFailure?.let {
            nextCommitFailure = null
            return failure(it)
        }
        val receipt = FirstRentalGiftSettlement(command, SettlementId("fixture-settlement-${command.requestId.value}"),
            Transaction(TransactionId("fixture-transaction-${command.requestId.value}"), command.requestId,
                TransactionKind.GARAGE, command.source, command.ruleVersion, null),
            prepared.balance, prepared.progress, prepared.marker, confirmedAt)
        // 현재 GachaProgress는 하나다. 같은 모니터에서 무료권/revision·마커·당시 입력/전체 결과만 함께 확정한다.
        gachaState = checkNotNull(gachaState).copy(progress = prepared.progress)
        firstRentalGiftOriginalState = current
        firstRentalGiftMarker = prepared.marker
        firstRentalGift = receipt
        return ContractResult.Success(receipt)
    }

    @Synchronized
    override fun readInitialization(profileId: ProfileId): ContractResult<InitializationState> {
        val configured = when (val read = initializationConfiguration?.state ?: failure(ContractError.DATA_UNAVAILABLE)) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        if (profileId != configured.profileId) return failure(ContractError.INVALID_INPUT)
        val original = initializationResult
        val marker = initializationMarker
        val status = if (original != null || marker != null) {
            if (original == null || marker == null || !validInitializationResult(original, marker, configured))
                return failure(ContractError.STORAGE_FAILURE)
            ProfileInitializationStatus.Existing(original)
        } else configured.status
        return ContractResult.Success(configured.copy(status = status, balance = balance,
            ownedVehicleCount = vehiclesById.values.count { it.lifecycle == VehicleLifecycle.OWNED }))
    }

    /** 과거 기록의 연결 검증에만 당시 명령을 쓴다. 현재 NEW 판별·재발급을 하지 않는다. */
    private fun validInitializationResult(original: InitializationSettlement, marker: InitializationMarker,
                                          configured: InitializationState): Boolean {
        val command = original.command
        val vehicle = (command.grant.vehicle as? VehicleWrite.Add)?.vehicle ?: return false
        val money = command.grant.money ?: return false
        // 현재 후보/버전으로 과거를 재계산하지 않고 저장된 명령의 형태를 기존 prepare로 검사한다.
        val historical = configured.copy(contentVersion = command.contentVersion, ruleVersion = command.grant.ruleVersion,
            status = ProfileInitializationStatus.New(command.evidence), balance = GameMoney(0), ownedVehicleCount = 0,
            initialMoney = money.amount, initialGarageCapacity = command.garageCapacity, availableCandidates = setOf(vehicle.vehicleId))
        val checked = (prepareInitialization(command, historical) as? ContractResult.Success)?.value
            as? InitializationPreparation.Ready ?: return false
        val transaction = original.grant.transaction
        return original.marker == checked.marker && marker == checked.marker &&
            original.garageCapacity == checked.garageCapacity && original.grant.balance == checked.balance &&
            original.grant.changedVehicle == checked.vehicle && transaction.requestId == command.grant.requestId &&
            transaction.kind == command.grant.kind && transaction.source == command.grant.source &&
            transaction.ruleVersion == command.grant.ruleVersion && transaction.money == command.grant.money
    }

    private fun hasInitializationRequest(requestId: RequestId) = initializationMarker?.requestId == requestId ||
        initializationResult?.command?.grant?.requestId == requestId

    @Synchronized
    override fun commitInitialization(command: InitializationCommand): ContractResult<InitializationSettlement> {
        val current = when (val read = readInitialization(command.profileId)) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        // 기존 원래 명령은 현재 후보/잔액/revision 검사보다 먼저 재생한다.
        if (initializationResult == null &&
            (command.grant.requestId in committed || command.grant.requestId in gachaCommitted ||
                command.grant.requestId in materialCommitted || command.grant.requestId in repairCommitted || hasFirstRentalGiftRequest(command.grant.requestId)))
            return failure(ContractError.STATE_CONFLICT)
        val prepared = when (val result = prepareInitialization(command, current)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        if (prepared is InitializationPreparation.Replay) return ContractResult.Success(prepared.result)
        prepared as InitializationPreparation.Ready
        // 새 지급도 일반 Add처럼 처분 이력을 포함한 기존 개체 ID를 재사용하지 않는다.
        if (prepared.vehicle.ownedVehicleId in vehiclesById) return failure(ContractError.STATE_CONFLICT)
        nextCommitFailure?.let {
            nextCommitFailure = null
            return failure(it)
        }
        val grant = command.grant
        val result = InitializationSettlement(command,
            Settlement(SettlementId("fixture-settlement-${grant.requestId.value}"),
                Transaction(TransactionId("fixture-transaction-${grant.requestId.value}"), grant.requestId,
                    grant.kind, grant.source, grant.ruleVersion, grant.money),
                prepared.balance, prepared.vehicle, confirmedAt), prepared.garageCapacity, prepared.marker)
        val updatedGacha = gachaState?.let { state ->
            val a = state.authority
            state.copy(authority = GachaAuthority(a.source, a.contentVersion, a.ruleVersion, a.shopLevel,
                prepared.garageCapacity, a.purchasePrice, a.pity, a.guaranteedFrom, a.unlockedVehicles, a.eligibleRarities))
        }
        // 같은 모니터에서 재화·첫 차·공간·마커·최초 결과를 함께 확정한다. 무료권은 생성하지 않는다.
        balance = prepared.balance
        vehiclesById[prepared.vehicle.ownedVehicleId] = prepared.vehicle
        activities[prepared.vehicle.ownedVehicleId] = ContractResult.Success(VehicleActivity.IDLE)
        initializationResult = result
        initializationMarker = result.marker
        gachaState = updatedGacha
        return ContractResult.Success(result)
    }

    @Synchronized
    override fun readRepairState(target: OwnedVehicleId): ContractResult<RepairState> {
        val configuration = repairConfiguration ?: return failure(ContractError.DATA_UNAVAILABLE)
        val vehicle = vehiclesById[target] ?: return failure(ContractError.DATA_UNAVAILABLE)
        val activity = when (val read = activities[target] ?: failure(ContractError.DATA_UNAVAILABLE)) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        val accidents = when (val read = configuration.accidentCounts[target] ?: failure(ContractError.DATA_UNAVAILABLE)) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        if (accidents < 0) return failure(ContractError.STORAGE_FAILURE)
        val cost = when (val read = configuration.repairCosts[target] ?: failure(ContractError.DATA_UNAVAILABLE)) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        return ContractResult.Success(RepairState(configuration.source, configuration.contentVersion, configuration.ruleVersion,
            balance, vehicle, activity, accidents, cost))
    }

    @Synchronized
    override fun repairSettlement(requestId: RequestId): ContractResult<RepairSettlement?> {
        if (requestId in committed || requestId in gachaCommitted || requestId in materialCommitted || hasInitializationRequest(requestId) || hasFirstRentalGiftRequest(requestId))
            return failure(ContractError.STATE_CONFLICT)
        return ContractResult.Success(repairCommitted[requestId])
    }

    @Synchronized
    override fun commitRepair(command: RepairCommand): ContractResult<RepairSettlement> {
        repairCommitted[command.requestId]?.let { original ->
            return when (val replay = replayRepairChange(command, original)) {
                is ContractResult.Failure -> replay
                is ContractResult.Success -> ContractResult.Success(original)
            }
        }
        if (command.requestId in committed || command.requestId in gachaCommitted || command.requestId in materialCommitted || hasInitializationRequest(command.requestId) || hasFirstRentalGiftRequest(command.requestId))
            return failure(ContractError.STATE_CONFLICT)
        val current = when (val read = readRepairState(command.target.ownedVehicleId)) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        val prepared = when (val result = prepareRepairChange(command, current)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        nextCommitFailure?.let {
            nextCommitFailure = null
            return failure(it)
        }
        val receipt = RepairSettlement(command, SettlementId("fixture-settlement-${command.requestId.value}"),
            Transaction(TransactionId("fixture-transaction-${command.requestId.value}"), command.requestId,
                TransactionKind.GARAGE, command.source, command.ruleVersion, prepared.money),
            prepared.balance, prepared.vehicle, prepared.activity, prepared.accidentCount, confirmedAt)
        // 같은 모니터에서 지갑·개체·활동·최초 결과를 함께 확정한다. 사고/취득 정보는 변경하지 않는다.
        balance = prepared.balance
        vehiclesById[prepared.vehicle.ownedVehicleId] = prepared.vehicle
        activities[prepared.vehicle.ownedVehicleId] = ContractResult.Success(prepared.activity)
        repairCommitted[command.requestId] = receipt
        return ContractResult.Success(receipt)
    }

    @Synchronized
    override fun readMaterialState(target: OwnedVehicleId, material: OwnedVehicleId): ContractResult<MaterialState> {
        val configuration = materialConfiguration ?: return failure(ContractError.DATA_UNAVAILABLE)
        val targetVehicle = vehiclesById[target] ?: return failure(ContractError.DATA_UNAVAILABLE)
        val materialVehicle = vehiclesById[material] ?: return failure(ContractError.DATA_UNAVAILABLE)
        fun activity(id: OwnedVehicleId) = activities[id] ?: failure(ContractError.DATA_UNAVAILABLE)
        val targetActivity = when (val result = activity(target)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val materialActivity = when (val result = activity(material)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        return ContractResult.Success(MaterialState(configuration.source, configuration.contentVersion, configuration.ruleVersion,
            balance, MaterialVehicleState(targetVehicle, targetActivity), MaterialVehicleState(materialVehicle, materialActivity)))
    }

    @Synchronized
    override fun commitMaterial(command: MaterialCommand): ContractResult<MaterialSettlement> {
        // 모든 필드는 불변 값이다. 역할별 명령도 입구에서 고정해 검증과 확정/재생에 같은 값을 쓴다.
        val request = command.copy(target = command.target.copy(), material = command.material.copy())
        materialCommitted[request.requestId]?.let { original ->
            return if (original.command == request) ContractResult.Success(original) else failure(ContractError.STATE_CONFLICT)
        }
        if (request.requestId in committed || request.requestId in gachaCommitted || request.requestId in repairCommitted || hasInitializationRequest(request.requestId) || hasFirstRentalGiftRequest(request.requestId)) return failure(ContractError.STATE_CONFLICT)
        val current = when (val result = readMaterialState(request.target.ownedVehicleId, request.material.ownedVehicleId)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val prepared = when (val result = prepareMaterialChange(request, current)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        nextCommitFailure?.let {
            nextCommitFailure = null
            return failure(it)
        }
        val receipt = MaterialSettlement(request, SettlementId("fixture-settlement-${request.requestId.value}"),
            Transaction(TransactionId("fixture-transaction-${request.requestId.value}"), request.requestId,
                TransactionKind.GARAGE, request.source, request.ruleVersion, prepared.money),
            prepared.balance, prepared.target, prepared.material, confirmedAt)
        // 단일 GameChange 두 번이 아니라 같은 모니터에서 두 개체·잔액·최초 결과를 함께 확정한다.
        balance = prepared.balance
        vehiclesById[prepared.target.ownedVehicleId] = prepared.target
        vehiclesById[prepared.material.ownedVehicleId] = prepared.material
        materialCommitted[request.requestId] = receipt
        return ContractResult.Success(receipt)
    }

    @Synchronized
    override fun listOwnedVehicles(): ContractResult<List<OwnedVehicle>> =
        ContractResult.Success(Collections.unmodifiableList(vehiclesById.values.filter { it.lifecycle == VehicleLifecycle.OWNED }))

    @Synchronized
    override fun commitGameChange(change: GameChange): ContractResult<Settlement> {
        if (change.requestId in gachaCommitted || change.requestId in materialCommitted || change.requestId in repairCommitted || hasInitializationRequest(change.requestId) || hasFirstRentalGiftRequest(change.requestId)) return failure(ContractError.STATE_CONFLICT)
        committed[change.requestId]?.let { (original, result) ->
            return if (original == change) ContractResult.Success(result) else failure(ContractError.STATE_CONFLICT)
        }
        val authority = gachaState?.authority
        val configuredSource = authority?.source ?: materialConfiguration?.source ?: repairConfiguration?.source
            ?: (initializationConfiguration?.state as? ContractResult.Success)?.value?.source ?: firstRentalGiftConfiguration?.source
        if (configuredSource != null && change.source != configuredSource) return failure(ContractError.INVALID_INPUT)
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
                val capacity = authority?.garageCapacity ?: initializationResult?.garageCapacity
                if (capacity != null && vehiclesById.values.count { it.lifecycle == VehicleLifecycle.OWNED } >= capacity)
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
                if (repairConfiguration != null) {
                    val activity = when (val read = activities[write.ownedVehicleId] ?: failure(ContractError.DATA_UNAVAILABLE)) {
                        is ContractResult.Failure -> return read
                        is ContractResult.Success -> read.value
                    }
                    if (activity == VehicleActivity.RENTED || activity == VehicleActivity.RETURNING ||
                        (activity == VehicleActivity.NEEDS_REPAIR && write.lifecycle !in setOf(VehicleLifecycle.SOLD, VehicleLifecycle.SCRAPPED)))
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
        if (command.requestId in committed || command.requestId in materialCommitted || command.requestId in repairCommitted || hasInitializationRequest(command.requestId) || hasFirstRentalGiftRequest(command.requestId)) return failure(ContractError.STATE_CONFLICT)
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
