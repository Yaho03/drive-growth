package io.drivegrowth.app.ui.shop

import io.drivegrowth.core.rental.Facility

/** #229 원본 픽셀 좌표. 차량은 화면에만 배치하며 저장 슬롯과 무관하다. */
internal val townSlots = listOf(
    253 to 635, 326 to 672, 400 to 709, 474 to 745, 548 to 781,
    984 to 758, 1063 to 796, 1142 to 835, 1220 to 873, 1299 to 912,
)

internal data class ShopSceneModel(
    val slots: Int?,
    val vehicles: List<ShopVehicle>,
    val additionalVehicles: Int,
    val facilityStates: Map<Facility, ShopAvailability>,
)

internal fun shopSceneModel(content: ShopScreenState.Content): ShopSceneModel {
    val slots = content.capacity?.coerceAtMost(townSlots.size)
    val shown = content.vehicles.take(slots ?: 0)
    val supplied = content.facilities.associate { it.facility to it.option.availability }
    return ShopSceneModel(
        slots,
        shown,
        content.vehicles.size - shown.size,
        Facility.entries.associateWith { supplied[it] ?: ShopAvailability.UNKNOWN },
    )
}
