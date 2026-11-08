package io.drivegrowth.core.garage

import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.VehicleCatalogSnapshot

/** 도감과 첫 차 지급 확인에서 소비하는 개체 이력·발견 기록. */
interface VehicleHistoryStore {
    fun listVehicleHistory(): ContractResult<List<OwnedVehicle>>
    fun discoveredVehicles(): ContractResult<Set<CollectionKey>>
    fun recordDiscoveries(catalog: VehicleCatalogSnapshot, keys: Set<CollectionKey>): ContractResult<Unit>
}
