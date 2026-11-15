package io.drivegrowth.app.ui.shop

import kotlin.math.hypot

internal data class TownPoint(val x: Float, val y: Float) {
    fun distanceTo(other: TownPoint) = hypot(x - other.x, y - other.y)
    fun toward(other: TownPoint, fraction: Float) =
        TownPoint(x + (other.x - x) * fraction, y + (other.y - y) * fraction)
}

internal enum class TownZone(vararg coordinates: Int) {
    // 가게 앞 공터 아스팔트 전체(왼쪽 주차 칸 ~ 세차장 앞, 아래 가로등 연석 안쪽)를 덮도록 넓혔다.
    // 기존 값: LOT(560, 560, 980, 710, 820, 800, 680, 840, 380, 700)
    LOT(125, 700, 292, 570, 585, 528, 800, 622, 975, 700, 1130, 630, 1355, 724, 1418, 766, 1405, 875, 1195, 965, 740, 975, 330, 790),
    RIGHT(1010, 730, 1110, 650, 1390, 800, 1300, 860, 1120, 840),
    FUEL(110, 318, 160, 290, 400, 282, 300, 312),
    PLAZA(610, 510, 660, 495, 880, 590, 840, 625),
    WASH(1150, 660, 1230, 650, 1330, 700, 1250, 720),
    ENTRY_LANE(230, 671, 390, 672, 582, 747, 491, 797, 222, 711),
    LOWER_LANE(945, 762, 1345, 906, 1348, 953, 933, 802),
    ;

    private val polygon = coordinates

    fun contains(point: TownPoint): Boolean {
        var inside = false
        var previous = polygon.size - 2
        for (index in polygon.indices step 2) {
            val ay = polygon[index + 1]
            val by = polygon[previous + 1]
            if ((ay > point.y) != (by > point.y) &&
                point.x < (polygon[previous] - polygon[index]) * (point.y - ay) / (by - ay) + polygon[index]) {
                inside = !inside
            }
            previous = index
        }
        return inside
    }
}

/** 16px 보행 격자는 지도 배치당 한 번만 만든다. 4방향 이동으로 차량 모서리를 가로지르지 않는다. */
internal class TownWalkMap(parked: List<TownPoint>) {
    private val cars = parked.toList()
    private val points = Array(COLUMNS * ROWS) { index ->
        TownPoint((index % COLUMNS * CELL + CELL / 2).toFloat(), (index / COLUMNS * CELL + CELL / 2).toFloat())
    }
    private val free = BooleanArray(points.size) { index -> allows(points[index]) }
    private val neighbors = Array(points.size) { index ->
        if (!free[index]) intArrayOf()
        else {
            val column = index % COLUMNS
            val row = index / COLUMNS
            intArrayOf(
                if (column > 0) index - 1 else -1,
                if (column < COLUMNS - 1) index + 1 else -1,
                if (row > 0) index - COLUMNS else -1,
                if (row < ROWS - 1) index + COLUMNS else -1,
            ).filter { it >= 0 && free[it] && clearLine(points[index], points[it]) }.toIntArray()
        }
    }
    private val components = IntArray(points.size) { -1 }.also { ids ->
        val queue = IntArray(points.size)
        for (start in points.indices) {
            if (!free[start] || ids[start] != -1) continue
            ids[start] = start
            queue[0] = start
            var head = 0
            var tail = 1
            while (head < tail) {
                for (neighbor in neighbors[queue[head++]]) if (ids[neighbor] == -1) {
                    ids[neighbor] = start
                    queue[tail++] = neighbor
                }
            }
        }
    }
    private val destinations = TownZone.entries.associateWith { zone ->
        points.indices.filter { free[it] && zone.contains(points[it]) }.map { points[it] }
    }

    fun destinations(zone: TownZone): List<TownPoint> = destinations.getValue(zone)

    fun repairApproaches(car: TownPoint, component: Int): List<TownPoint> {
        val radius = 85f
        val left = maxOf(0, ((car.x - radius) / CELL).toInt())
        val right = minOf(COLUMNS - 1, ((car.x + radius) / CELL).toInt())
        val top = maxOf(0, ((car.y - radius) / CELL).toInt())
        val bottom = minOf(ROWS - 1, ((car.y + radius) / CELL).toInt())
        val nearby = mutableListOf<Pair<TownPoint, Float>>()
        for (row in top..bottom) for (column in left..right) {
            val index = row * COLUMNS + column
            if (components[index] != component) continue
            val point = points[index]
            val distance = point.distanceTo(car)
            if (distance <= radius) nearby.add(point to distance)
        }
        return nearby.sortedBy { it.second }.map { it.first }
    }

    fun allows(point: TownPoint): Boolean = allows(point, 0f)

    private fun allows(point: TownPoint, clearance: Float): Boolean =
        inMask(point) && (clearance == 0f || (
            inMask(TownPoint(point.x + clearance, point.y)) && inMask(TownPoint(point.x - clearance, point.y)) &&
                inMask(TownPoint(point.x, point.y + clearance)) && inMask(TownPoint(point.x, point.y - clearance))
            )) && cars.none { car ->
            val dx = (point.x - car.x) / (57f + clearance)
            val dy = (point.y - car.y - 4f) / (31f + clearance)
            dx * dx + dy * dy < 1f
        }

    private fun inMask(point: TownPoint): Boolean {
        val x = point.x
        val y = point.y
        val bridge = x in 560f..980f && y in (x * .37f + 294f)..(x * .37f + 393f) ||
            x in 800f..1130f && y in 700f..822f || x in 1100f..1280f && y in 668f..741f
        return bridge || TownZone.entries.any { it.contains(point) }
    }

    fun clearLine(from: TownPoint, to: TownPoint): Boolean {
        val samples = (from.distanceTo(to) / 4f).toInt().coerceAtLeast(1) + 1
        if (!allows(from) || !allows(to)) return false
        return (1 until samples).all { allows(from.toward(to, it.toFloat() / samples), 1f) }
    }

    fun component(point: TownPoint): Int? = nearestConnectedCell(point)?.let { components[it] }

    fun path(from: TownPoint, to: TownPoint): List<TownPoint>? {
        if (!allows(from) || !allows(to)) return null
        if (clearLine(from, to)) return listOf(to)
        val start = nearestConnectedCell(from) ?: return null
        val goal = nearestConnectedCell(to) ?: return null
        if (components[start] != components[goal]) return null
        val previous = IntArray(points.size) { -1 }
        val queue = IntArray(points.size)
        previous[start] = start
        queue[0] = start
        var head = 0
        var tail = 1
        while (head < tail && previous[goal] == -1) {
            val cell = queue[head++]
            for (next in neighbors[cell]) if (previous[next] == -1) {
                previous[next] = cell
                queue[tail++] = next
            }
        }
        if (previous[goal] == -1) return null
        val path = mutableListOf(to)
        var cell = goal
        while (cell != start) {
            path.add(points[cell])
            cell = previous[cell]
        }
        path.add(points[start])
        path.reverse()
        // 연결이 확인된 경로만 직선으로 줄여 걷는 방향이 자주 바뀌지 않게 한다.
        val waypoints = mutableListOf<TownPoint>()
        var anchor = from
        var index = 0
        while (index < path.size) {
            var last = index
            while (last + 1 < path.size && clearLine(anchor, path[last + 1])) last++
            anchor = path[last]
            waypoints.add(anchor)
            index = last + 1
        }
        return waypoints
    }

    private fun nearestConnectedCell(point: TownPoint): Int? {
        val centerX = (point.x / CELL).toInt()
        val centerY = (point.y / CELL).toInt()
        var nearest: Int? = null
        var best = Float.MAX_VALUE
        for (row in maxOf(0, centerY - 2)..minOf(ROWS - 1, centerY + 2)) {
            for (column in maxOf(0, centerX - 2)..minOf(COLUMNS - 1, centerX + 2)) {
                val index = row * COLUMNS + column
                val distance = point.distanceTo(points[index])
                if (free[index] && distance < best && clearLine(point, points[index])) {
                    nearest = index
                    best = distance
                }
            }
        }
        return nearest
    }

    companion object {
        // HOME이 같은 주차 배치를 다시 렌더할 때 격자와 연결을 재사용한다.
        private var cached: TownWalkMap? = null

        @Synchronized
        fun forParking(parked: List<TownPoint>): TownWalkMap =
            cached?.takeIf { it.cars == parked } ?: TownWalkMap(parked).also { cached = it }

        private const val CELL = 16
        private const val COLUMNS = 96
        private const val ROWS = 64
    }
}
