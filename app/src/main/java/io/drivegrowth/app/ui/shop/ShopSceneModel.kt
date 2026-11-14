package io.drivegrowth.app.ui.shop

import io.drivegrowth.core.rental.Facility

/** #229 원본 픽셀 좌표. 차량은 화면에만 배치하며 저장 슬롯과 무관하다. */
internal val townSlots = listOf(
    253 to 635, 326 to 672, 400 to 709, 474 to 745, 548 to 781,
    984 to 758, 1063 to 796, 1142 to 835, 1220 to 873, 1299 to 912,
)

internal enum class TownGrowthArt(val parkingSpaces: Int) {
    LEVEL_1(3), LEVEL_2(5), LEVEL_3(7), LEVEL_4(10);

    val assetPath get() = "pixel/town-growth/town-lv${ordinal + 1}.png"

    companion object {
        // 그림에 그려진 칸 수와 조회값이 다르면 공통 지도에서 조회값으로 공간을 표시한다.
        fun matching(level: Int?, capacity: Int?): TownGrowthArt? =
            entries.firstOrNull { it.ordinal + 1 == level && it.parkingSpaces == capacity }
    }
}

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
                  offset: ShopMapOffset, horizontalBias: Float = .22f): ShopMapCamera {
            val sceneWidth = right - left
            val sceneHeight = bottom - top
            val scale = maxOf(width / sceneWidth, height / sceneHeight)
            val extraX = (sceneWidth * scale - width).coerceAtLeast(0f)
            val extraY = (sceneHeight * scale - height).coerceAtLeast(0f)
            val x = (-extraX * horizontalBias + offset.x * scale).coerceIn(-extraX, 0f)
            val y = (-extraY * .65f + offset.y * scale).coerceIn(-extraY, 0f)
            return ShopMapCamera(scale, x - left * scale, y - top * scale)
        }
    }
}

/** 지도 원본 좌표의 대상 실루엣과 그 반대편에 놓일 패널. */
internal data class ShopFocusRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val panelOnLeft get() = left + right > 1536f

    companion object {
        fun vehicle(slot: Pair<Int, Int>): ShopFocusRegion {
            val (x, y) = slot
            return ShopFocusRegion(x - 74f, y - 84f, x + 74f, y + 42f)
        }
    }
}

/** 패널 크기와 카메라가 사용할 빈 영역을 같은 창 치수로 계산한다. */
internal data class ShopFocusLayout(
    val side: Boolean,
    val panelWidth: Int,
    val maxPanelHeight: Int,
    val margin: Int,
) {
    fun camera(width: Int, height: Int, region: ShopFocusRegion, overviewScale: Float): ShopMapCamera {
        val subjectLeft = if (side && region.panelOnLeft) panelWidth + margin else 0
        val subjectRight = if (side && !region.panelOnLeft) width - panelWidth - margin else width
        val subjectBottom = if (side) height else height - maxPanelHeight - margin
        val padding = margin.toFloat()
        val maxScale = minOf(
            (subjectRight - subjectLeft - 2 * padding).coerceAtLeast(1f) / (region.right - region.left),
            (subjectBottom - 2 * padding).coerceAtLeast(1f) / (region.bottom - region.top),
        )
        val cover = maxOf(width / 1536f, height / 1024f)
        val zoom = minOf(maxOf(overviewScale * 1.6f, cover), maxScale)
        val x = (subjectLeft + subjectRight) / 2f - (region.left + region.right) / 2f * zoom
        val y = subjectBottom / 2f - (region.top + region.bottom) / 2f * zoom
        // 지도 경계에서 가능한 만큼만 이동하되, 패널을 피해 확보한 대상 영역은 지킨다.
        fun position(desired: Float, screen: Int, map: Float, first: Float, last: Float, low: Int, high: Int): Float {
            val min = low - first * zoom
            val max = high - last * zoom
            val coverMin = minOf(0f, screen - map * zoom)
            val overlapMin = maxOf(min, coverMin)
            val overlapMax = minOf(max, 0f)
            if (overlapMin <= overlapMax) return desired.coerceIn(overlapMin, overlapMax)
            return desired.coerceIn(min, max.coerceAtLeast(min))
        }
        return ShopMapCamera(zoom,
            position(x, width, 1536f, region.left, region.right, subjectLeft, subjectRight),
            position(y, height, 1024f, region.top, region.bottom, 0, subjectBottom))
    }

    companion object {
        fun usesSidePanel(width: Int, height: Int, density: Float) = width >= (520 * density).toInt() && width > height

        fun fit(width: Int, height: Int, density: Float): ShopFocusLayout {
            val margin = (12 * density).toInt()
            val availableWidth = (width - 2 * margin).coerceAtLeast(0)
            val availableHeight = (height - 2 * margin).coerceAtLeast(0)
            val side = usesSidePanel(width, height, density)
            return ShopFocusLayout(side,
                if (side) (width / 2 - margin).coerceAtLeast(0) else availableWidth,
                if (side) availableHeight else minOf(availableHeight, height * 3 / 5),
                margin)
        }
    }
}
