package io.drivegrowth.core.garage

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.VehicleCatalogSnapshot
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.progression.VehicleActivity
import java.util.Collections

/** 도감 한 칸. PRD: 차종 × 희귀도. */
data class CollectionKey(val vehicleId: VehicleId, val rarity: Rarity)

enum class CollectionState { UNDISCOVERED, DISCOVERED, OWNED }

data class CollectionEntry(val key: CollectionKey, val state: CollectionState)

/**
 * 저장소에서 읽은 개체 목록(처분 포함)을 조회 결과로 바꾼다. 상태를 바꾸거나 점유하지 않는다.
 * 손상 데이터(콘텐츠에 없는 차종·중복 개체 ID)는 INVALID_INPUT으로 거절하고 조용히 고치지 않는다.
 */
class Garage(catalog: VehicleCatalogSnapshot) {
    private val vehicleIds: List<VehicleId> = catalog.vehicles.map { it.vehicleId }
    private val known: Set<VehicleId> = vehicleIds.toSet()

    /** 현재 보유(OWNED) 개체. SOLD·SCRAPPED·CONSUMED는 빠진다. */
    fun owned(vehicles: List<OwnedVehicle>): ContractResult<List<OwnedVehicle>> =
        validated(vehicles) { list -> list.filter { it.lifecycle == VehicleLifecycle.OWNED } }

    /** 보유 중인 개체 한 대. 없거나 이미 처분됐으면 DATA_UNAVAILABLE. */
    fun find(vehicles: List<OwnedVehicle>, id: OwnedVehicleId): ContractResult<OwnedVehicle> =
        when (val result = owned(vehicles)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> result.value.firstOrNull { it.ownedVehicleId == id }
                ?.let { ContractResult.Success(it) } ?: ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        }

    /**
     * OWNED·IDLE 대여 후보 조회. 영업 쉬기·최종 배정·점유·동시 변경 검사는 별도다.
     * 공급자는 상태 변경 없이 활동을 읽고, 누락은 DATA_UNAVAILABLE 등 명시 실패로 반환한다.
     * 활동 조회 실패는 그대로 전파하며 일부 후보를 성공으로 반환하지 않는다.
     */
    fun availableForRental(
        vehicles: List<OwnedVehicle>,
        activityOf: (OwnedVehicleId) -> ContractResult<VehicleActivity>,
    ): ContractResult<List<OwnedVehicle>> {
        val owned = when (val result = owned(vehicles)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val available = ArrayList<OwnedVehicle>()
        for (vehicle in owned) {
            when (val activity = activityOf(vehicle.ownedVehicleId)) {
                is ContractResult.Failure -> return activity
                is ContractResult.Success -> if (activity.value == VehicleActivity.IDLE) available.add(vehicle)
            }
        }
        return ContractResult.Success(Collections.unmodifiableList(available))
    }

    /**
     * 콘텐츠 차종 × 모든 희귀도의 도감 상태. 보유했던 칸은 처분 후에도 OWNED로 남는다.
     * everOwned·discovered는 저장된 획득·발견 기록에서 호출자가 만든다. 현재 보유 목록만으로 처분 포함 획득 이력을 대체하지 않는다.
     */
    fun collection(everOwned: Set<CollectionKey>, discovered: Set<CollectionKey>): ContractResult<List<CollectionEntry>> {
        if ((everOwned + discovered).any { it.vehicleId !in known }) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val entries = vehicleIds.flatMap { vehicleId ->
            Rarity.entries.map { rarity ->
                val key = CollectionKey(vehicleId, rarity)
                val state = when (key) {
                    in everOwned -> CollectionState.OWNED
                    in discovered -> CollectionState.DISCOVERED
                    else -> CollectionState.UNDISCOVERED
                }
                CollectionEntry(key, state)
            }
        }
        return ContractResult.Success(Collections.unmodifiableList(entries))
    }

    /**
     * 저장된 기록으로 도감을 만든다. 보유 칸은 처분 포함 이력(`listVehicleHistory`)의 (차종, 희귀도), 발견 칸은 `discoveredVehicles`.
     * 조회 실패는 빈 도감으로 바꾸지 않고 그대로 전달하며, 이력의 손상(콘텐츠에 없는 차종·중복 개체 ID)은 INVALID_INPUT이다.
     * 가챠·탁송에서 본 키는 호출자가 `recordDiscoveries`로 먼저 기록한다.
     */
    fun collection(history: VehicleHistoryStore): ContractResult<List<CollectionEntry>> {
        val records = when (val result = history.listVehicleHistory()) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val checked = when (val result = validated(records) { it }) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val discovered = when (val result = history.discoveredVehicles()) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        return collection(checked.map { CollectionKey(it.vehicleId, it.rarity) }.toSet(), discovered)
    }

    private fun validated(
        vehicles: List<OwnedVehicle>,
        select: (List<OwnedVehicle>) -> List<OwnedVehicle>,
    ): ContractResult<List<OwnedVehicle>> {
        val corrupt = vehicles.any { it.vehicleId !in known } ||
            vehicles.map { it.ownedVehicleId }.toSet().size != vehicles.size
        if (corrupt) return ContractResult.Failure(ContractError.INVALID_INPUT)
        return ContractResult.Success(Collections.unmodifiableList(ArrayList(select(vehicles))))
    }
}
