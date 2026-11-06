package io.drivegrowth.core.garage

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.VehicleCatalogSnapshot
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.progression.Rarity
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
class Garage(private val catalog: VehicleCatalogSnapshot) {
    private val known: Set<VehicleId> = catalog.vehicles.map { it.vehicleId }.toSet()

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
     * 대여 가능: 보유 중이면서 활동 상태가 IDLE. 활동 상태는 대여(#41)·수리(#49) 기록에서 호출자가 넘긴다.
     * ponytail: 영업 쉬기 스위치는 저장 위치가 정해지면 같은 필터에 추가.
     */
    fun availableForRental(
        vehicles: List<OwnedVehicle>,
        activityOf: (OwnedVehicleId) -> VehicleActivity,
    ): ContractResult<List<OwnedVehicle>> =
        validated(vehicles) { list ->
            list.filter { it.lifecycle == VehicleLifecycle.OWNED && activityOf(it.ownedVehicleId) == VehicleActivity.IDLE }
        }

    /**
     * 콘텐츠 차종 × 모든 희귀도의 도감 상태. 보유했던 칸은 처분 후에도 OWNED로 남는다.
     * everOwned·discovered는 저장된 획득·발견 기록에서 호출자가 만든다(개체 희귀도 필드·발견 기록 저장은 #33·#34 조율).
     */
    fun collection(everOwned: Set<CollectionKey>, discovered: Set<CollectionKey>): ContractResult<List<CollectionEntry>> {
        if ((everOwned + discovered).any { it.vehicleId !in known }) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val entries = catalog.vehicles.flatMap { definition ->
            Rarity.entries.map { rarity ->
                val key = CollectionKey(definition.vehicleId, rarity)
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
