package io.drivegrowth.app.ui.garage

import io.drivegrowth.app.host.garage.GarageCommandState
import io.drivegrowth.app.host.garage.GaragePending
import io.drivegrowth.app.host.garage.GarageReceipt
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity

/** 저장이 확인된 결과에서만 표시할 값이다. 이전 상태를 현재 차고에서 다시 조회하지 않는다. */
internal data class GarageCareOutcome(
    val action: VehicleAction,
    val vehicle: OwnedVehicle,
    val material: OwnedVehicle? = null,
    val credit: GameMoney? = null,
) {
    companion object {
        fun from(state: GarageCommandState.Confirmed): GarageCareOutcome? {
            val request = state.pending.request
            val receipt = state.receipt
            val requestId = when (receipt) {
                is GarageReceipt.Single -> receipt.value.transaction.requestId
                is GarageReceipt.Material -> receipt.value.transaction.requestId
                is GarageReceipt.Repair -> receipt.value.transaction.requestId
            }
            if (requestId != state.pending.command.requestId) return null
            val outcome = when (request.action) {
                VehicleAction.LEVEL_UP, VehicleAction.SELL, VehicleAction.SCRAP -> {
                    val result = (receipt as? GarageReceipt.Single)?.value ?: return null
                    val vehicle = result.changedVehicle ?: return null
                    val credit = result.transaction.money?.takeIf { it.direction == MoneyDirection.CREDIT && it.amount.units > 0 }?.amount
                    GarageCareOutcome(request.action, vehicle, credit = credit)
                }
                VehicleAction.USE_MATERIAL -> {
                    val result = (receipt as? GarageReceipt.Material)?.value ?: return null
                    if (result.material.lifecycle != VehicleLifecycle.CONSUMED ||
                        result.material.ownedVehicleId != result.command.material.ownedVehicleId) return null
                    GarageCareOutcome(request.action, result.target, material = result.material)
                }
                VehicleAction.REPAIR -> {
                    val result = (receipt as? GarageReceipt.Repair)?.value ?: return null
                    if (result.activity != VehicleActivity.IDLE) return null
                    GarageCareOutcome(request.action, result.vehicle)
                }
            }
            val expected = when (request.action) {
                VehicleAction.SELL -> VehicleLifecycle.SOLD
                VehicleAction.SCRAP -> VehicleLifecycle.SCRAPPED
                else -> VehicleLifecycle.OWNED
            }
            return outcome.takeIf { it.vehicle.ownedVehicleId == request.id && it.vehicle.lifecycle == expected }
        }
    }
}

internal data class GarageCareEffect(val outcome: GarageCareOutcome, val before: OwnedVehicle)

/** 이번 화면에서 직접 시작한 거래 한 건에만 연출을 연결한다. */
internal class GarageCareMotion {
    private val attempted = mutableSetOf<RequestId>()
    private var armed: GaragePending? = null
    private var running = false

    fun arm(review: GarageCommandState.Review, allowed: Boolean) {
        clear()
        if (allowed && review.canConfirm && !review.recovering && attempted.add(review.pending.command.requestId)) {
            armed = review.pending
        }
    }

    fun observe(state: GarageCommandState, allowed: Boolean): GarageCareEffect? {
        val pending = armed ?: return null
        if (!allowed) {
            clear()
            return null
        }
        if (state is GarageCommandState.Running && state.pending == pending) {
            running = true
            return null
        }
        val started = running
        clear()
        if (!started || state !is GarageCommandState.Confirmed || state.pending != pending) return null
        val before = pending.vehicle?.takeIf {
            it.ownedVehicleId == pending.request.id && it.revision == pending.request.revision &&
                it.lifecycle == VehicleLifecycle.OWNED
        } ?: return null
        val result = GarageCareOutcome.from(state) ?: return null
        return GarageCareEffect(result, before)
    }

    fun clear() {
        armed = null
        running = false
    }
}
