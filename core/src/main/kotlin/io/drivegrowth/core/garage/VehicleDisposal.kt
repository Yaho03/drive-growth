package io.drivegrowth.core.garage

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleCatalogSnapshot
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.VehicleWrite
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.progression.VehicleCare
import io.drivegrowth.core.rental.CompletedRentalSource

/**
 * 판매·폐차 계획: 처분 금액 CREDIT과 SOLD/SCRAPPED Update(성장값 보존)를 담은 불변 `GameChange`를 만든다.
 * 저장은 호출자가 `store.commitGameChange(plan)`으로 하고, 응답을 잃으면 같은 계획을 다시 제출해 처음 정산을 재생한다.
 * 사고 횟수는 #116 `CompletedRentalSource.accidentCount`(정산 완료 사고)에서 읽는다.
 * 대여 중·반납 중은 거절, 수리 필요는 판매·폐차 가능(PRD 「차량 사용 중 제한」). 동시 대여와의 점유 경쟁은 저장소(#107 SQLite)가 확정 때 다시 검사한다.
 */
class VehicleDisposal(
    catalog: VehicleCatalogSnapshot,
    private val ruleVersion: RuleVersion,
    private val care: VehicleCare = VehicleCare(),
) {
    private val garage = Garage(catalog)

    /** 사고 3회 이상은 판매 불가(INVALID_INPUT, 폐차만 가능). */
    fun planSale(
        owned: List<OwnedVehicle>,
        requestId: RequestId,
        source: InputSource,
        target: VehicleRef,
        accidents: CompletedRentalSource,
        activityOf: (OwnedVehicleId) -> ContractResult<VehicleActivity>,
    ): ContractResult<GameChange> {
        val vehicle = when (val result = usable(owned, target, VehicleAction.SELL, activityOf)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val count = when (val result = accidents.accidentCount(vehicle.ownedVehicleId)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        if (count < 0) return ContractResult.Failure(ContractError.INVALID_INPUT)
        if (!care.canSell(count)) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val amount = try { care.salePrice(vehicle, count) } catch (_: ArithmeticException) { return ContractResult.Failure(ContractError.INVALID_INPUT) }
        return ContractResult.Success(change(requestId, source, vehicle, VehicleLifecycle.SOLD, amount))
    }

    /** 폐차는 사고 횟수와 무관하게 가능하다. 금액 = 실제 취득액의 20%. */
    fun planScrap(
        owned: List<OwnedVehicle>,
        requestId: RequestId,
        source: InputSource,
        target: VehicleRef,
        activityOf: (OwnedVehicleId) -> ContractResult<VehicleActivity>,
    ): ContractResult<GameChange> {
        val vehicle = when (val result = usable(owned, target, VehicleAction.SCRAP, activityOf)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val amount = try { care.scrapValue(vehicle) } catch (_: ArithmeticException) { return ContractResult.Failure(ContractError.INVALID_INPUT) }
        return ContractResult.Success(change(requestId, source, vehicle, VehicleLifecycle.SCRAPPED, amount))
    }

    private fun change(requestId: RequestId, source: InputSource, vehicle: OwnedVehicle, lifecycle: VehicleLifecycle, amount: Long) =
        GameChange(
            requestId, TransactionKind.GARAGE, source, ruleVersion,
            money = if (amount > 0) MoneyChange(MoneyDirection.CREDIT, GameMoney(amount)) else null,
            vehicle = VehicleWrite.Update(vehicle.ownedVehicleId, vehicle.revision, vehicle.level, lifecycle, vehicle.levelProgressPercent),
        )

    private fun usable(
        owned: List<OwnedVehicle>,
        ref: VehicleRef,
        action: VehicleAction,
        activityOf: (OwnedVehicleId) -> ContractResult<VehicleActivity>,
    ): ContractResult<OwnedVehicle> {
        val vehicle = when (val result = garage.find(owned, ref.ownedVehicleId)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        if (vehicle.revision != ref.revision) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val activity = when (val result = activityOf(vehicle.ownedVehicleId)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        return if (care.isAllowed(activity, action)) ContractResult.Success(vehicle) else ContractResult.Failure(ContractError.STATE_CONFLICT)
    }
}
