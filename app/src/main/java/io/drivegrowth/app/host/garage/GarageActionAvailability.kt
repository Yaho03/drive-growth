package io.drivegrowth.app.host.garage

import io.drivegrowth.app.host.HostSnapshot
import io.drivegrowth.app.ui.garage.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.*

/** 화면 버튼은 계획 조회만 연다. 비용 계산과 최종 허용 검사는 core/store가 수행한다. */
object GarageActionAvailability {
    fun map(state: GarageScreenState, snapshot: HostSnapshot): GarageScreenState {
        if(state !is GarageScreenState.Content) return state
        val care = VehicleCare()
        val facts = (snapshot.garageState as? ContractResult.Success)?.value?.vehicles.orEmpty().associateBy { it.vehicle.ownedVehicleId }
        return GarageScreenState.Content(state.data.copy(vehicles = state.data.vehicles.map { shown ->
            shown.copy(actions = VehicleAction.entries.associateWith { action ->
                val activity = shown.activity
                val accidents = facts[shown.vehicle.ownedVehicleId]?.accidents
                val reason = when {
                    activity == null -> "활동 정보를 확인할 수 없습니다"
                    !care.isAllowed(activity, action) -> "현재 활동에서는 이 관리를 할 수 없습니다"
                    action in setOf(VehicleAction.LEVEL_UP,VehicleAction.USE_MATERIAL) && shown.vehicle.level >= MAX_VEHICLE_LEVEL -> "최대 레벨 차량은 더 강화할 수 없습니다"
                    action == VehicleAction.SELL && accidents !is ContractResult.Success -> "판매 전 확정 사고 이력을 확인해야 합니다"
                    action == VehicleAction.SELL && (accidents as ContractResult.Success).value < 0 -> "사고 이력 형식 오류입니다"
                    action == VehicleAction.SELL && !care.canSell((accidents as ContractResult.Success).value) -> "사고 3회 이상 차량은 판매할 수 없고 폐차만 가능합니다"
                    else -> null
                }
                ActionPresentation(reason == null, reason ?: "누르면 비용과 결과를 먼저 보여 드려요")
            })
        }))
    }
}
