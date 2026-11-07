package io.drivegrowth.core.garage

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameChangeStore
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MAX_VEHICLE_LEVEL
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.VehicleWrite
import io.drivegrowth.core.progression.LevelProgress
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.progression.VehicleCare

/** 화면에서 본 개체와 revision. 다르면 그사이 바뀐 것이므로 STATE_CONFLICT. */
data class VehicleRef(val ownedVehicleId: OwnedVehicleId, val revision: Revision)

/** 재료 강화의 저장 전 결과. 대상 성장과 재료 CONSUMED를 한 정산에 함께 확정해야 한다. */
data class MaterialPlan(val target: VehicleWrite.Update, val material: VehicleWrite.Update)

/**
 * 레벨업·재료 강화. 활동 상태는 대여(#41)·수리(#49) 기록에서 호출자가 넘긴다.
 * 거절: 없음·처분됨 DATA_UNAVAILABLE / revision 불일치·사용 중 STATE_CONFLICT / 최대 레벨·같은 개체·다른 차종 INVALID_INPUT.
 */
class VehicleUpgrade(private val content: GameContentSnapshot, private val care: VehicleCare = VehicleCare()) {
    private val garage = Garage(content.catalog)
    private val prices = content.vehicles.associate { it.vehicleId to it.price }

    /**
     * 게임머니 레벨업: 비용 차감과 레벨+1을 거래 한 건으로 저장한다. 잔액 부족·저장 실패는 저장소가 전체 거절한다.
     * 성공 후 같은 요청을 다시 보내면 revision이 바뀌어 STATE_CONFLICT가 되고 다시 반영되지 않는다.
     */
    fun levelUp(
        store: GameChangeStore,
        requestId: RequestId,
        source: InputSource,
        target: VehicleRef,
        activityOf: (OwnedVehicleId) -> VehicleActivity,
    ): ContractResult<Settlement> {
        val owned = when (val result = store.listOwnedVehicles()) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val vehicle = when (val result = usable(owned, target, VehicleAction.LEVEL_UP, activityOf)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val next = care.afterLevelUp(LevelProgress(vehicle.level, vehicle.levelProgressPercent))
        val cost = GameMoney(care.levelUpCost(prices.getValue(vehicle.vehicleId).units, vehicle.level, vehicle.rarity))
        val change = GameChange(
            requestId, TransactionKind.GARAGE, source, content.ruleVersion,
            money = if (cost.units > 0) MoneyChange(MoneyDirection.DEBIT, cost) else null,
            vehicle = VehicleWrite.Update(target.ownedVehicleId, target.revision, next.level, VehicleLifecycle.OWNED, next.progressPercent),
        )
        return store.commitGameChange(change)
    }

    /**
     * 재료 강화 계산·검사. 같은 차종이면 희귀도가 달라도 재료가 된다.
     * ponytail: 현재 GameChange는 차량 한 대만 바꿔 저장하지 않는다. 다중 개체 거래가 생기면(#33·#34) 이 계획을 한 건으로 커밋.
     */
    fun planMaterial(
        owned: List<OwnedVehicle>,
        target: VehicleRef,
        material: VehicleRef,
        activityOf: (OwnedVehicleId) -> VehicleActivity,
    ): ContractResult<MaterialPlan> {
        if (target.ownedVehicleId == material.ownedVehicleId) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val t = when (val result = usable(owned, target, VehicleAction.USE_MATERIAL, activityOf)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val m = when (val result = usable(owned, material, VehicleAction.USE_MATERIAL, activityOf, checkMax = false)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        if (t.vehicleId != m.vehicleId) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val next = care.afterMaterial(LevelProgress(t.level, t.levelProgressPercent), m.rarity)
        return ContractResult.Success(MaterialPlan(
            VehicleWrite.Update(t.ownedVehicleId, t.revision, next.level, VehicleLifecycle.OWNED, next.progressPercent),
            // 처분은 저장된 성장값을 그대로 둔다(#104 계약).
            VehicleWrite.Update(m.ownedVehicleId, m.revision, m.level, VehicleLifecycle.CONSUMED, m.levelProgressPercent),
        ))
    }

    private fun usable(
        owned: List<OwnedVehicle>,
        ref: VehicleRef,
        action: VehicleAction,
        activityOf: (OwnedVehicleId) -> VehicleActivity,
        checkMax: Boolean = true,
    ): ContractResult<OwnedVehicle> {
        val vehicle = when (val result = garage.find(owned, ref.ownedVehicleId)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        return when {
            vehicle.revision != ref.revision -> ContractResult.Failure(ContractError.STATE_CONFLICT)
            !care.isAllowed(activityOf(vehicle.ownedVehicleId), action) -> ContractResult.Failure(ContractError.STATE_CONFLICT)
            checkMax && vehicle.level >= MAX_VEHICLE_LEVEL -> ContractResult.Failure(ContractError.INVALID_INPUT)
            else -> ContractResult.Success(vehicle)
        }
    }
}
