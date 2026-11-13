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

internal data class ShopMapOffset(val x: Float = 0f, val y: Float = 0f)

internal data class ShopMapCamera(val scale: Float, val x: Float, val y: Float) {
    companion object {
        /** 지도 픽셀은 같은 비율로 확대하고, 여유가 있는 축에서만 지도를 이동한다. */
        fun cover(width: Int, height: Int, left: Float, top: Float, right: Float, bottom: Float,
                  offset: ShopMapOffset): ShopMapCamera {
            val sceneWidth = right - left
            val sceneHeight = bottom - top
            val scale = maxOf(width / sceneWidth, height / sceneHeight)
            val extraX = (sceneWidth * scale - width).coerceAtLeast(0f)
            val extraY = (sceneHeight * scale - height).coerceAtLeast(0f)
            // 세로 창에서 본관과 가까운 주차 공간부터 보이게 한다.
            val x = (-extraX * .22f + offset.x * scale).coerceIn(-extraX, 0f)
            val y = (-extraY * .65f + offset.y * scale).coerceIn(-extraY, 0f)
            return ShopMapCamera(scale, x - left * scale, y - top * scale)
        }
    }
}
