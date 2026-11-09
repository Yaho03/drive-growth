package io.drivegrowth.core.contract

import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleActivity

/** 보관한 전체 명령을 재제출한다. 비용은 같은 버전의 VehicleCare 견적이며 최소 단위 Long 0..MAX다. */
data class RepairCommand(
    val requestId: RequestId,
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    val target: VehicleRef,
    val expectedAccidentCount: Int,
    val cost: GameMoney,
)

/**
 * 저장소가 같은 프로필·출처에서 읽은 최신 개체/활동/확정 사고 누계/잔액/비용 견적이다.
 * repairCost는 차종 기준가와 개체 희귀도에 VehicleCare를 적용한 값이다. 취득액을 수리비 기준으로 쓰지 않는다.
 * 필드 누락·미지원/읽기 실패는 이 DTO를 만들지 않고 ContractResult.Failure로 반환한다.
 */
data class RepairState(
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    val balance: GameMoney,
    val vehicle: OwnedVehicle,
    val activity: VehicleActivity,
    val accidentCount: Int,
    val repairCost: GameMoney,
)

/** 순수 예정 결과이며 예약·커밋·수리 성공이 아니다. 개체 revision만 1 증가하고 다른 개체 필드는 보존한다. */
data class RepairPreparedChange(
    val command: RepairCommand,
    val balance: GameMoney,
    val vehicle: OwnedVehicle,
    val activity: VehicleActivity,
    val accidentCount: Int,
) {
    val money: MoneyChange? get() = command.cost.takeIf { it.units > 0 }?.let { MoneyChange(MoneyDirection.DEBIT, it) }
}

/** 최초 확정 때의 전체 결과. 사고는 정산 완료 사고 건수(Int 0..MAX)이며 수리로 감소하지 않는다. */
data class RepairSettlement(
    val command: RepairCommand,
    val settlementId: SettlementId,
    val transaction: Transaction,
    val balance: GameMoney,
    val vehicle: OwnedVehicle,
    val activity: VehicleActivity,
    val accidentCount: Int,
    val confirmedAt: EpochMillis,
)

/**
 * 시험용 InMemoryGameChangeStore가 메모리 참조를 제공한다. 실제 SQLite/live 상태 공급은 후속이다.
 * 기존 GameChange와 같은 원장/프로필/출처/전역 요청 ID 공간을 쓴다.
 * 같은 ID/전체 입력은 현재 상태 검사보다 먼저 최초 전체 결과를 반환하고 다른 입력/명령 종류는 STATE_CONFLICT다.
 * 새 명령은 같은 잠금/DB 거래에서 readRepairState/prepare를 다시 수행하고 잔액·개체 revision·NEEDS_REPAIR→IDLE·
 * 요청/정산을 전부 확정하거나 전부 거절한다. 사고 영업 이력과 영업 쉬기 스위치는 변경하지 않는다.
 * UI의 사전 조회는 최종 검사를 대체하지 않는다. GameChange 단일 Update와 활동 변경을 두 번의 commit으로 나누지 않는다.
 * 비용0은 금액 null인 GARAGE 거래/수리 정산이며 가짜0원 DEBIT를 만들지 않는다.
 * repairSettlement의 정상 null은 확인된 미확정만 뜻한다. 손상은 STORAGE_FAILURE, 미지원은 DATA_UNAVAILABLE다.
 */
interface RepairChangeStore : GameChangeStore {
    fun readRepairState(target: OwnedVehicleId): ContractResult<RepairState>
    fun repairSettlement(requestId: RequestId): ContractResult<RepairSettlement?>
    fun commitRepair(command: RepairCommand): ContractResult<RepairSettlement>
}

/** 현재 상태 검증만 수행한다. 이전 요청 재생/전역 충돌 확인은 저장소가 이 함수보다 먼저 수행한다. */
fun prepareRepairChange(command: RepairCommand, current: RepairState): ContractResult<RepairPreparedChange> {
    if (command.source != current.source || command.expectedAccidentCount < 0 || current.accidentCount < 0)
        return ContractResult.Failure(ContractError.INVALID_INPUT)
    if (command.contentVersion != current.contentVersion || command.ruleVersion != current.ruleVersion)
        return ContractResult.Failure(ContractError.STATE_CONFLICT)
    val vehicle = current.vehicle
    if (vehicle.ownedVehicleId != command.target.ownedVehicleId || vehicle.lifecycle != VehicleLifecycle.OWNED)
        return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    if (vehicle.revision != command.target.revision || current.activity != VehicleActivity.NEEDS_REPAIR ||
        current.accidentCount != command.expectedAccidentCount || current.repairCost != command.cost)
        return ContractResult.Failure(ContractError.STATE_CONFLICT)
    if (vehicle.revision.value == Int.MAX_VALUE) return ContractResult.Failure(ContractError.INVALID_INPUT)
    if (command.cost.units > current.balance.units) return ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS)
    return ContractResult.Success(RepairPreparedChange(command, GameMoney(current.balance.units - command.cost.units),
        vehicle.copy(revision = Revision(vehicle.revision.value + 1)), VehicleActivity.IDLE, current.accidentCount))
}

/** 현재 상태/활동/견적 조회 실패를 무료·유휴 상태로 보정하지 않는 소비 연결이다. */
fun prepareRepairChange(command: RepairCommand, read: (OwnedVehicleId) -> ContractResult<RepairState>): ContractResult<RepairPreparedChange> =
    when (val result = read(command.target.ownedVehicleId)) {
        is ContractResult.Failure -> result
        is ContractResult.Success -> prepareRepairChange(command, result.value)
    }

/**
 * 저장소가 전역 요청 종류를 확인하고 읽은 원래 수리 결과를 대조한다. 현재 상태를 조회하거나 재계산하지 않는다.
 * null은 확인된 미확정 요청일 때만 넘긴다. 다른 종류의 기존 ID는 호출 전에 STATE_CONFLICT, 조회 실패는 원래 오류다.
 * 추가로 decoder는 당시 잔액·원본 개체/사고 이력의 정합성을 검증해야 한다. 이 함수는 JSON 파서나 DB 검증을 대체하지 않는다.
 */
fun replayRepairChange(command: RepairCommand, recorded: RepairSettlement?): ContractResult<RepairSettlement?> {
    if (recorded == null) return ContractResult.Success(null)
    if (recorded.command != command) return ContractResult.Failure(ContractError.STATE_CONFLICT)
    val expectedMoney = command.cost.takeIf { it.units > 0 }?.let { MoneyChange(MoneyDirection.DEBIT, it) }
    val transaction = recorded.transaction
    if (command.expectedAccidentCount < 0 || command.target.revision.value == Int.MAX_VALUE ||
        recorded.vehicle.ownedVehicleId != command.target.ownedVehicleId ||
        recorded.vehicle.revision.value != command.target.revision.value + 1 ||
        recorded.vehicle.lifecycle != VehicleLifecycle.OWNED || recorded.activity != VehicleActivity.IDLE ||
        recorded.accidentCount != command.expectedAccidentCount || transaction.requestId != command.requestId ||
        transaction.source != command.source || transaction.ruleVersion != command.ruleVersion ||
        transaction.kind != TransactionKind.GARAGE || transaction.money != expectedMoney)
        return ContractResult.Failure(ContractError.STORAGE_FAILURE)
    return ContractResult.Success(recorded)
}
