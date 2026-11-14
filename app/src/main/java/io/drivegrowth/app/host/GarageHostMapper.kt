package io.drivegrowth.app.host

import io.drivegrowth.app.ui.garage.ActionPresentation
import io.drivegrowth.app.ui.garage.CollectionPresentation
import io.drivegrowth.app.ui.garage.CollectionScreenState
import io.drivegrowth.app.ui.garage.GarageContent
import io.drivegrowth.app.ui.garage.GarageScreenState
import io.drivegrowth.app.ui.garage.HeldVehiclePresentation
import io.drivegrowth.app.ui.garage.VehiclePresentation
import io.drivegrowth.app.ui.vehicleInstanceLabels
import io.drivegrowth.core.contract.ContractError
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
        val stored = when (val result = snapshot.garageState) {
            is ContractResult.Success -> result.value
            is ContractResult.Failure -> null
        }
        if (stored != null && (stored.source != snapshot.source || stored.contentVersion != snapshot.content.catalog.contentVersion ||
                stored.ruleVersion != snapshot.content.ruleVersion))
            return GarageScreenState.Error("차고 조회의 출처·버전을 확인할 수 없어요")
        val details = stored?.vehicles.orEmpty()
        val byId = owned.associateBy { it.ownedVehicleId }
        if (details.map { it.vehicle.ownedVehicleId }.distinct().size != details.size ||
            details.any { it.vehicle != byId[it.vehicle.ownedVehicleId] })
            return GarageScreenState.Error("차량 정보가 바뀌었어요. 다시 조회해 주세요 (STATE_CONFLICT)")
        val detailsById = details.associateBy { it.vehicle.ownedVehicleId }
        val missing = (snapshot.garageState as? ContractResult.Failure) ?: ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val rawCapacity = stored?.capacity ?: missing
        val capacity = if (rawCapacity is ContractResult.Success && rawCapacity.value < 0)
            ContractResult.Failure(ContractError.STORAGE_FAILURE) else rawCapacity
        val names = snapshot.content.catalog.vehicles.associate { it.vehicleId to it.displayName }
        val instanceLabels = vehicleInstanceLabels(owned.map { it.ownedVehicleId to names.getValue(it.vehicleId) })
        val prices = snapshot.content.vehicles.groupBy { it.vehicleId }
        val held = when (val result = snapshot.heldVehicles) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> {
                val records = result.value
                if (records.any { it.source != snapshot.source }) ContractResult.Failure(ContractError.STATE_CONFLICT)
                else {
                    val labels = vehicleInstanceLabels(records.map {
                        it.vehicle.ownedVehicleId to names[it.vehicle.vehicleId].orEmpty()
                    })
                    ContractResult.Success(records.map {
                        HeldVehiclePresentation(it.vehicle, names[it.vehicle.vehicleId],
                            labels[it.vehicle.ownedVehicleId].orEmpty(), it.deadline)
                    })
                }
            }
        }
        return GarageScreenState.Content(GarageContent(
            vehicles = owned.map { vehicle ->
                val detail = detailsById[vehicle.ownedVehicleId]
                val activity = detail?.activity ?: missing
                val accidents = detail?.accidents ?: missing
                val count = (accidents as? ContractResult.Success)?.value?.takeIf { it >= 0 }
                val price = prices[vehicle.vehicleId]?.singleOrNull()?.price
                val notes = listOfNotNull(
                    (activity as? ContractResult.Failure)?.let { "활동 정보를 확인할 수 없어요 (${it.error})" },
                    (accidents as? ContractResult.Failure)?.let { "사고 이력을 확인할 수 없어요 (${it.error})" },
                    if (accidents is ContractResult.Success && count == null) "사고 이력을 확인할 수 없어요 (STORAGE_FAILURE)" else null,
                    if (price == null) "게임 차종 가격을 확인할 수 없어요" else null,
                    (capacity as? ContractResult.Failure)?.let { "보유 공간을 확인할 수 없어요 (${it.error})" })
                VehiclePresentation(
                    vehicle = vehicle,
                    name = names.getValue(vehicle.vehicleId),
                    instanceLabel = instanceLabels[vehicle.ownedVehicleId].orEmpty(),
                    activity = (activity as? ContractResult.Success)?.value,
                    accidentLabel = count?.toString(),
                    modelPriceLabel = price?.let { "${it.units} 게임머니" },
                    actions = VehicleAction.entries.associateWith {
                        ActionPresentation(false, "관리 명령이 아직 연결되지 않았어요")
                    },
                    informationNote = notes.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                )
            },
            capacityLabel = (capacity as? ContractResult.Success)?.value?.takeIf { it >= 0 }?.let { "보유 ${owned.size} / ${it}칸" },
            sourceLabel = sourceLabel(snapshot),
            heldVehicles = held,
        ))
    }

    fun collection(snapshot: HostSnapshot, history: ContractResult<CollectionHistory>): CollectionScreenState {
        val saved = when (history) {
            is ContractResult.Failure -> return CollectionScreenState.Error("도감 획득·발견 이력을 확인할 수 없어요 (${history.error})")
            is ContractResult.Success -> history.value
        }
        if (saved.source != snapshot.source || saved.contentVersion != snapshot.content.catalog.contentVersion ||
            saved.ruleVersion != snapshot.content.ruleVersion)
            return CollectionScreenState.Error("도감 조회의 출처·버전을 확인할 수 없어요")
        val entries = when (val result = Garage(snapshot.content.catalog).collection(saved.everOwned, saved.discovered)) {
            is ContractResult.Failure -> return CollectionScreenState.Error("도감 이력을 확인할 수 없어요 (${result.error})")
            is ContractResult.Success -> result.value
        }
        val names = snapshot.content.catalog.vehicles.associate { it.vehicleId to it.displayName }
        val gameVehicles = snapshot.content.vehicles.groupBy { it.vehicleId }
        return CollectionScreenState.Content(entries.map { entry ->
            val vehicleId = entry.key.vehicleId
            val unlockLevel = gameVehicles[vehicleId]?.singleOrNull()?.unlockShopLevel?.takeIf { it > 0 }
            CollectionPresentation(entry, names.getValue(vehicleId), unlockLevel)
        }, sourceLabel(snapshot))
    }

    private fun sourceLabel(snapshot: HostSnapshot): String = buildString {
        append(if (snapshot.source == InputSource.SIMULATION) "모의 원장" else "차량 원장")
        append(" · 콘텐츠 ${snapshot.content.catalog.contentVersion.value}")
        if (snapshot.content.trial) append(" · 시험 콘텐츠")
    }
}
