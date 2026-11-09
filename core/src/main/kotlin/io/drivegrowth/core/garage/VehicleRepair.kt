package io.drivegrowth.core.garage

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.RepairChangeStore
import io.drivegrowth.core.contract.RepairCommand
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.prepareRepairChange
import io.drivegrowth.core.progression.VehicleCare

/**
 * 수리 명령(#49). 저장 측 `readRepairState`의 개체·활동·사고 누계·견적을 이 앱 콘텐츠와 대조해 `RepairCommand`를 만든다.
 * 저장하지 않는다. 호출자가 `store.commitRepair(command)`로 비용 차감과 NEEDS_REPAIR→IDLE을 한 정산에 확정하고(#121),
 * 응답을 잃으면 **보관한 같은 command를 다시 commitRepair**한다. 저장소가 처음 정산을 재생한다.
 * 수리비 = 차종 가격 × 5% × 희귀도 비용 배율([VehicleCare.repairCost]). 취득액은 쓰지 않는다.
 */
class VehicleRepair(private val content: GameContentSnapshot, private val care: VehicleCare = VehicleCare()) {
    private val prices = content.vehicles.associate { it.vehicleId to it.price }

    /**
     * 거절: 조회 실패는 그대로 / 저장 측 버전·견적이 이 앱 계산과 다르면 STATE_CONFLICT / 콘텐츠에 없는 차종 DATA_UNAVAILABLE.
     * 화면에서 본 revision·수리 필요 상태·잔액은 `prepareRepairChange`로 미리 검사한다. 최종 검사는 commitRepair가 다시 한다.
     */
    fun prepare(store: RepairChangeStore, requestId: RequestId, source: InputSource, target: VehicleRef): ContractResult<RepairCommand> {
        val state = when (val result = store.readRepairState(target.ownedVehicleId)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        if (state.contentVersion != content.catalog.contentVersion || state.ruleVersion != content.ruleVersion)
            return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val price = prices[state.vehicle.vehicleId] ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val quote = try { care.repairCost(price.units, state.vehicle.rarity) } catch (_: ArithmeticException) {
            return ContractResult.Failure(ContractError.INVALID_INPUT)
        }
        if (quote != state.repairCost.units) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val command = RepairCommand(requestId, source, state.contentVersion, state.ruleVersion, target, state.accidentCount, state.repairCost)
        return when (val checked = prepareRepairChange(command, state)) {
            is ContractResult.Failure -> checked
            is ContractResult.Success -> ContractResult.Success(command)
        }
    }
}
