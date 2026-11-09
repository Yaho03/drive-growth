package io.drivegrowth.core.contract

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.garage.FirstGrant

@JvmInline
value class ProfileId(val value: String) {
    init { require(value.isNotBlank()) }
}

/** 저장/프로필 생성 경계가 검증한 신규 근거. 빈 차고나 INITIAL_GRANT 부재로 생성하지 않는다. */
data class NewProfileEvidence(val profileId: ProfileId, val source: InputSource, val proofId: String, val revision: Revision) {
    init { require(proofId.isNotBlank()) }
}

sealed interface ProfileInitializationStatus {
    data class New(val evidence: NewProfileEvidence) : ProfileInitializationStatus
    /** legacy 기존 프로필은 원래 지급을 복구할 수 없을 수도 있다. 이 경우 재지급하지 않는다. */
    data class Existing(val original: InitializationSettlement?) : ProfileInitializationStatus
    data class Unknown(val reason: MissingReason) : ProfileInitializationStatus
}

/** grant는 기존 FirstGrant가 만든 전체 명령이다. 초기 공간도 저장 측의 명시 규칙과 일치해야 한다. */
data class InitializationCommand(
    val profileId: ProfileId,
    val evidence: NewProfileEvidence,
    val contentVersion: ContentVersion,
    val grant: GameChange,
    val garageCapacity: Int,
) {
    init { require(garageCapacity > 0) }
}

data class InitializationMarker(val profileId: ProfileId, val requestId: RequestId, val evidence: NewProfileEvidence)

data class InitializationSettlement(
    val command: InitializationCommand,
    val grant: Settlement,
    val garageCapacity: Int,
    val marker: InitializationMarker,
)

/** NEW 여부는 status의 양성 근거로만 판단한다. 초기 수치는 임시 기본값 없이 공급한다. */
data class InitializationState(
    val profileId: ProfileId,
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    val status: ProfileInitializationStatus,
    val balance: GameMoney,
    val ownedVehicleCount: Int,
    val initialMoney: GameMoney,
    val initialGarageCapacity: Int,
    /** 저장 측 현재 콘텐츠에서 확인한 후보. 빈 집합을 제품 후보 기본값으로 채우지 않는다. */
    val availableCandidates: Set<VehicleId>,
) {
    init { require(ownedVehicleCount >= 0 && initialGarageCapacity > 0) }
}

/** Ready는 순수 예정 결과이며 지급 성공이 아니다. Replay는 저장 측이 검증한 원래 전체 결과다. */
sealed interface InitializationPreparation {
    data class Ready(val command: InitializationCommand, val vehicle: OwnedVehicle, val balance: GameMoney,
                     val garageCapacity: Int, val marker: InitializationMarker) : InitializationPreparation
    data class Replay(val result: InitializationSettlement) : InitializationPreparation
}

/**
 * 구현은 후속이다. 같은 프로필의 원래 요청/전체 명령 재생을 현재 규칙/상태 검사보다 먼저 한다.
 * NEW 근거 revision과 원장 ID를 같은 잠금/DB에서 다시 검사하고 재화·차량·공간·마커·원래 결과를 한 번에 확정한다.
 * 원장은 한 프로필에 귀속하고 다른 프로필 요청을 거절한다. 다른 종류의 기존 요청 ID도 재사용하지 않는다.
 * 다른 요청 종류/내용은 STATE_CONFLICT, UNKNOWN/미복구 기존 프로필은 DATA_UNAVAILABLE·무지급이다.
 * 마커가 있는데 원래 결과가 손상/누락된 경우는 STORAGE_FAILURE다. Existing(null)은 마커 없는 legacy 양성 판별에만 쓴다.
 * 저장 측은 original의 전체 명령/정산·차량·재화·공간·마커 연결을 검증한 뒤 이 상태를 공급한다.
 */
interface InitializationChangeStore : GameChangeStore {
    fun readInitialization(profileId: ProfileId): ContractResult<InitializationState>
    fun commitInitialization(command: InitializationCommand): ContractResult<InitializationSettlement>
}

fun prepareInitialization(command: InitializationCommand, current: InitializationState): ContractResult<InitializationPreparation> {
    if (command.profileId != current.profileId || command.grant.source != current.source)
        return ContractResult.Failure(ContractError.INVALID_INPUT)
    when (val status = current.status) {
        is ProfileInitializationStatus.Existing -> {
            val original = status.original ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            return if (command == original.command) ContractResult.Success(InitializationPreparation.Replay(original))
                else ContractResult.Failure(ContractError.STATE_CONFLICT)
        }
        is ProfileInitializationStatus.Unknown -> return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        is ProfileInitializationStatus.New -> if (command.evidence != status.evidence)
            return ContractResult.Failure(ContractError.STATE_CONFLICT)
    }
    if (command.evidence.profileId != current.profileId || command.evidence.source != current.source)
        return ContractResult.Failure(ContractError.INVALID_INPUT)
    if (command.contentVersion != current.contentVersion || command.grant.ruleVersion != current.ruleVersion ||
        command.garageCapacity != current.initialGarageCapacity || current.balance.units != 0L || current.ownedVehicleCount != 0)
        return ContractResult.Failure(ContractError.STATE_CONFLICT)
    val grant = command.grant
    val vehicle = (grant.vehicle as? VehicleWrite.Add)?.vehicle
        ?: return ContractResult.Failure(ContractError.INVALID_INPUT)
    // 기존 계산은 FirstGrant 책임이다. 여기서는 그 출력과 저장 측 권위 입력의 일치만 검증한다.
    if (grant.requestId != FirstGrant.REQUEST_ID || grant.kind != TransactionKind.GARAGE ||
        grant.money != MoneyChange(MoneyDirection.CREDIT, current.initialMoney) || current.initialMoney.units == 0L ||
        vehicle.vehicleId !in FirstGrant.CANDIDATES || vehicle.ownedVehicleId != OwnedVehicleId("initial-grant") ||
        vehicle.lifecycle != VehicleLifecycle.OWNED || vehicle.revision != Revision(0) || vehicle.level != 1 ||
        vehicle.levelProgressPercent != 0 || vehicle.rarity != Rarity.COMMON || vehicle.acquisitionCost.units != 0L ||
        vehicle.acquisitionSource != VehicleAcquisitionSource.INITIAL_GRANT)
        return ContractResult.Failure(ContractError.INVALID_INPUT)
    val availableCandidates = current.availableCandidates.toSet()
    if (vehicle.vehicleId !in availableCandidates) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    return ContractResult.Success(InitializationPreparation.Ready(command, vehicle, current.initialMoney,
        current.initialGarageCapacity, InitializationMarker(current.profileId, grant.requestId, command.evidence)))
}

/**
 * 새 선택에서 기존 FirstGrant 계산을 전용 원자 확정 경로에 연결한다. legacy 쓰기로 나눠 지급하지 않는다.
 * 응답 유실 재시도는 보관한 InitializationCommand를 commitInitialization에 그대로 제출한다.
 * 이 연결이 현재 콘텐츠로 원래 선택/원래 명령을 재구성하는 복구 API는 아니다.
 */
fun commitFirstGrantInitialization(
    store: InitializationChangeStore,
    evidence: NewProfileEvidence,
    content: GameContentSnapshot,
    chosen: VehicleId,
    garageCapacity: Int,
): ContractResult<InitializationSettlement> {
    var committed: ContractResult<InitializationSettlement>? = null
    val bridge = object : GameChangeStore by store {
        override fun commitGameChange(change: GameChange): ContractResult<Settlement> {
            val result = store.commitInitialization(InitializationCommand(
                evidence.profileId, evidence, content.catalog.contentVersion, change, garageCapacity))
            committed = result
            return when (result) {
                is ContractResult.Failure -> result
                is ContractResult.Success -> ContractResult.Success(result.value.grant)
            }
        }
    }
    return when (val result = FirstGrant(content).grant(bridge, chosen, evidence.source)) {
        is ContractResult.Failure -> result
        is ContractResult.Success -> checkNotNull(committed)
    }
}
