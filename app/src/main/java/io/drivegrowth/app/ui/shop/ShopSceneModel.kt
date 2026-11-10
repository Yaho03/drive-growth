package io.drivegrowth.app.ui.shop

import io.drivegrowth.core.rental.Facility

/** 표현에 필요한 확정 입력만 묶는다. 슬롯 배치는 화면용이며 저장 위치/새 소유 사실이 아니다. */
internal data class ShopSceneModel(
    val slots: Int?, val vehicles: List<ShopVehicle>, val additionalVehicles: Int,
    val expandedBuilding: Boolean, val installed: Set<Facility>,
)
internal fun shopSceneModel(content: ShopScreenState.Content): ShopSceneModel =
    ShopSceneModel(
        content.capacity?.coerceAtMost(12),
        content.vehicles.take(content.capacity?.coerceAtMost(12) ?: 12),
        content.vehicles.size - content.vehicles.take(content.capacity?.coerceAtMost(12) ?: 12).size,
        content.level?.let { it > 1 } == true,
        content.facilities.filter { it.option.availability == ShopAvailability.INSTALLED }.map { it.facility }.toSet(),
    )
