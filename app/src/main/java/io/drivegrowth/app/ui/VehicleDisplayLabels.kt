package io.drivegrowth.app.ui

import io.drivegrowth.core.contract.OwnedVehicleId

/** 같은 이름의 차량만 번호로 구분한다. 같은 보유 목록이면 조회 순서가 달라져도 번호가 같다. */
internal fun vehicleInstanceLabels(vehicles: List<Pair<OwnedVehicleId, String>>): Map<OwnedVehicleId, String> = buildMap {
    for (ids in vehicles.groupBy({ it.second }, { it.first }).values) {
        if (ids.size < 2) continue
        ids.sortedBy { it.value }.forEachIndexed { index, id -> put(id, "${index + 1}번") }
    }
}

internal fun vehicleDisplayName(name: String, label: String?): String =
    if (label.isNullOrBlank()) name else "$name $label"
