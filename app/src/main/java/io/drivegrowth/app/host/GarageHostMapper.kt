package io.drivegrowth.app.host

import io.drivegrowth.app.ui.garage.ActionPresentation
import io.drivegrowth.app.ui.garage.CollectionPresentation
import io.drivegrowth.app.ui.garage.CollectionScreenState
import io.drivegrowth.app.ui.garage.GarageContent
import io.drivegrowth.app.ui.garage.GarageScreenState
import io.drivegrowth.app.ui.garage.VehiclePresentation
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.garage.Garage
import io.drivegrowth.core.progression.VehicleAction

/** 제공되지 않은 활동·가격·명령 연결을 화면의 정상 값으로 채우지 않는다. */
class GarageHostMapper {
    fun garage(snapshot: HostSnapshot): GarageScreenState {
        val owned = when (val result = Garage(snapshot.content.catalog).owned(snapshot.ownedVehicles)) {
            is ContractResult.Failure -> return GarageScreenState.Error("차량 정보를 확인할 수 없어요 (${result.error})")
            is ContractResult.Success -> result.value
        }
        val names = snapshot.content.catalog.vehicles.associate { it.vehicleId to it.displayName }
        return GarageScreenState.Content(GarageContent(
            vehicles = owned.map { vehicle ->
                VehiclePresentation(
                    vehicle = vehicle,
                    name = names.getValue(vehicle.vehicleId),
                    instanceLabel = "개체 ${vehicle.ownedVehicleId.value}",
                    actions = VehicleAction.entries.associateWith {
                        ActionPresentation(false, "관리 명령이 아직 연결되지 않았어요")
                    },
                    informationNote = "활동·사고·모델 가격·보유 공간 정보는 아직 연결되지 않았어요",
                )
            },
            capacityLabel = null,
            sourceLabel = sourceLabel(snapshot),
        ))
    }

    fun collection(snapshot: HostSnapshot, history: ContractResult<CollectionHistory>): CollectionScreenState {
        val saved = when (history) {
            is ContractResult.Failure -> return CollectionScreenState.Error("도감 획득·발견 이력을 확인할 수 없어요 (${history.error})")
            is ContractResult.Success -> history.value
        }
        val entries = when (val result = Garage(snapshot.content.catalog).collection(saved.everOwned, saved.discovered)) {
            is ContractResult.Failure -> return CollectionScreenState.Error("도감 이력을 확인할 수 없어요 (${result.error})")
            is ContractResult.Success -> result.value
        }
        val names = snapshot.content.catalog.vehicles.associate { it.vehicleId to it.displayName }
        return CollectionScreenState.Content(entries.map {
            CollectionPresentation(it, names.getValue(it.key.vehicleId), unlockLabel = null)
        }, sourceLabel(snapshot))
    }

    private fun sourceLabel(snapshot: HostSnapshot): String = buildString {
        append(if (snapshot.source == InputSource.SIMULATION) "모의 원장" else "차량 원장")
        append(" · 콘텐츠 ${snapshot.content.catalog.contentVersion.value}")
        if (snapshot.content.trial) append(" · 시험 콘텐츠")
    }
}
