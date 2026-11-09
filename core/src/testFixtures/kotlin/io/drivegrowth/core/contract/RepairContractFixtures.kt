package io.drivegrowth.core.contract

import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.progression.VehicleCare

/** SIMULATION 전용 소비 입력. 실제 commit이나 새 지갑/원장이 아니다. */
object RepairContractFixtures {
    val missingState: ContractResult<RepairState> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    val storageFailure: ContractResult<RepairState> = ContractResult.Failure(ContractError.STORAGE_FAILURE)

    fun state(modelPrice: Long = 1000): RepairState = RepairState(
        InputSource.SIMULATION, ContentVersion("repair-fixture-v1"), RuleVersion("repair-fixture-v1"), GameMoney(1000),
        OwnedVehicle(OwnedVehicleId("repair-car"), VehicleId("hyundai_casper"), 7, Revision(3), VehicleLifecycle.OWNED,
            Rarity.RARE, 42, GameMoney(250), VehicleAcquisitionSource.GACHA),
        VehicleActivity.NEEDS_REPAIR, 3,
        (quote(modelPrice) as ContractResult.Success).value,
    )

    fun quote(modelPrice: Long, care: VehicleCare = VehicleCare()): ContractResult<GameMoney> = try {
        ContractResult.Success(GameMoney(care.repairCost(modelPrice, Rarity.RARE)))
    } catch (_: ArithmeticException) {
        ContractResult.Failure(ContractError.INVALID_INPUT)
    } catch (_: IllegalArgumentException) {
        ContractResult.Failure(ContractError.INVALID_INPUT)
    }

    fun command(state: RepairState = state()) = RepairCommand(
        RequestId("repair-fixture-1"), state.source, state.contentVersion, state.ruleVersion,
        VehicleRef(state.vehicle.ownedVehicleId, state.vehicle.revision), state.accidentCount, state.repairCost,
    )

    fun prepareExample(): ContractResult<RepairPreparedChange> {
        val state = state()
        return prepareRepairChange(command(state), state)
    }

    /** 과거 결과의 형식 예시. 저장 성공/재시작 검증 결과가 아니다. */
    fun recordedExample(): RepairSettlement {
        val before = state()
        val command = command(before)
        return RepairSettlement(command, SettlementId("repair-example-settlement"),
            Transaction(TransactionId("repair-example-transaction"), command.requestId, TransactionKind.GARAGE,
                command.source, command.ruleVersion, MoneyChange(MoneyDirection.DEBIT, GameMoney(55))),
            GameMoney(945), before.vehicle.copy(revision = Revision(4)), VehicleActivity.IDLE, 3, EpochMillis(1794190000000))
    }
}
