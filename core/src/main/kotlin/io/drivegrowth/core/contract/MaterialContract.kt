package io.drivegrowth.core.contract

import io.drivegrowth.core.progression.VehicleActivity

/** 계산된 역할별 변경을 고정한다. 비용0은 명시적 무과금이며 누락/default가 아니다. */
data class MaterialCommand(
    val requestId: RequestId,
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    val target: VehicleWrite.Update,
    val material: VehicleWrite.Update,
    val cost: GameMoney,
)

data class MaterialVehicleState(val vehicle: OwnedVehicle, val activity: VehicleActivity)

/** 저장 측의 같은 출처/조회 경계에서 얻은 상태. 활동 조회 실패는 이 값을 만들지 않고 오류로 반환한다. */
data class MaterialState(
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    val balance: GameMoney,
    val target: MaterialVehicleState,
    val material: MaterialVehicleState,
)

/** 순수 검증의 예정 결과이며 저장/지급 성공이나 현재 상태 예약이 아니다. */
data class MaterialPreparedChange(
    val command: MaterialCommand,
    val balance: GameMoney,
    val target: OwnedVehicle,
    val material: OwnedVehicle,
) {
    val money: MoneyChange? get() = command.cost.takeIf { it.units > 0 }?.let { MoneyChange(MoneyDirection.DEBIT, it) }
}

/**
 * 같은 정산의 대상/재료 snapshot을 역할별로 보존한다. 현재 개체로 재구성하지 않는다.
 * 후속 저장소/decoder는 transaction의 requestId·출처·규칙·GARAGE 종류·금액과 command의 연결을 검증해야 한다.
 * 두 snapshot도 command의 역할 ID·증가한 revision·확정 성장/lifecycle과 일치해야 한다.
 */
data class MaterialSettlement(
    val command: MaterialCommand,
    val settlementId: SettlementId,
    val transaction: Transaction,
    val balance: GameMoney,
    val target: OwnedVehicle,
    val material: OwnedVehicle,
    val confirmedAt: EpochMillis,
)

/**
 * #121의 같은 메모리 원장 참조 구현은 testFixtures에서 제공한다. #122 SQLite와 실제 제품 소비는 후속이다.
 * 같은 ID/전체 입력의 최초 전체 결과 재생을 현재 상태 검사보다 먼저 수행한다. 다른 내용/종류는 STATE_CONFLICT다.
 * 새 명령은 같은 잠금/DB 거래에서 최신 상태를 읽어 검증하고 두 개체·비용·정산·요청 이력을 전부 확정하거나 전부 거절한다.
 * prepare 성공을 커밋으로 취급하거나 두 번의 단일 GameChange로 나누지 않는다.
 */
interface MaterialChangeStore : GameChangeStore {
    fun readMaterialState(target: OwnedVehicleId, material: OwnedVehicleId): ContractResult<MaterialState>
    fun commitMaterial(command: MaterialCommand): ContractResult<MaterialSettlement>
}

/** 기존 VehicleUpgrade/VehicleCare가 계산한 성장값의 구조·현재 조건만 검사한다. 성장 산식을 재계산하지 않는다. */
fun prepareMaterialChange(command: MaterialCommand, current: MaterialState): ContractResult<MaterialPreparedChange> {
    if (command.target.ownedVehicleId == command.material.ownedVehicleId ||
        command.target.lifecycle != VehicleLifecycle.OWNED || command.material.lifecycle != VehicleLifecycle.CONSUMED)
        return ContractResult.Failure(ContractError.INVALID_INPUT)
    if (command.source != current.source) return ContractResult.Failure(ContractError.INVALID_INPUT)
    if (command.contentVersion != current.contentVersion || command.ruleVersion != current.ruleVersion)
        return ContractResult.Failure(ContractError.STATE_CONFLICT)
    val target = current.target.vehicle
    val material = current.material.vehicle
    if (target.ownedVehicleId != command.target.ownedVehicleId || material.ownedVehicleId != command.material.ownedVehicleId ||
        target.lifecycle != VehicleLifecycle.OWNED || material.lifecycle != VehicleLifecycle.OWNED)
        return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    if (target.revision != command.target.expectedRevision || material.revision != command.material.expectedRevision)
        return ContractResult.Failure(ContractError.STATE_CONFLICT)
    if (target.revision.value == Int.MAX_VALUE || material.revision.value == Int.MAX_VALUE)
        return ContractResult.Failure(ContractError.INVALID_INPUT)
    if (target.vehicleId != material.vehicleId) return ContractResult.Failure(ContractError.INVALID_INPUT)
    if (current.target.activity != VehicleActivity.IDLE || current.material.activity != VehicleActivity.IDLE)
        return ContractResult.Failure(ContractError.STATE_CONFLICT)
    val grows = command.target.level > target.level ||
        (command.target.level == target.level && command.target.levelProgressPercent > target.levelProgressPercent)
    if (target.level == MAX_VEHICLE_LEVEL || !grows || command.material.level != material.level ||
        command.material.levelProgressPercent != material.levelProgressPercent)
        return ContractResult.Failure(ContractError.INVALID_INPUT)
    if (command.cost.units > current.balance.units) return ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS)
    return ContractResult.Success(MaterialPreparedChange(
        command, GameMoney(current.balance.units - command.cost.units),
        target.copy(level = command.target.level, levelProgressPercent = command.target.levelProgressPercent,
            revision = Revision(target.revision.value + 1)),
        material.copy(lifecycle = VehicleLifecycle.CONSUMED, revision = Revision(material.revision.value + 1)),
    ))
}
