package io.drivegrowth.app.ui.delivery

import kotlin.random.Random

/** 트럭을 기준으로 한 옆 차선의 장식 좌표. 양의 속도가 더 빠른 차량이다. */
internal class DeliveryTraffic(private val spriteCount: Int) {
    data class Car(val id: Int, val sprite: Int, val cruiseSpeed: Float, var x: Float)

    private var random = Random(0)
    private var untilSpawn = 0L
    private var nextId = 0
    private val active = ArrayList<Car>(6)
    val cars: List<Car> get() = active

    fun reset() {
        active.clear()
        random = Random(0)
        untilSpawn = 0
        nextId = 0
    }

    fun advance(frameMillis: Long) {
        if (frameMillis <= 0 || spriteCount == 0) return
        val step = frameMillis.coerceAtMost(100)
        val seconds = step / 1000f
        for (index in active.indices) {
            val car = active[index]
            val next = car.x + car.cruiseSpeed * seconds
            // 앞차의 이번 프레임 위치를 기준으로 간격을 유지한다.
            car.x = if (index == 0) next else minOf(next, active[index - 1].x - 240f)
        }
        active.removeAll { it.x < -320f || it.x > 1620f }
        untilSpawn -= step
        if (untilSpawn > 0) return
        untilSpawn = random.nextLong(1_200, 3_800)
        val fast = random.nextFloat() < .6f
        val x = if (fast) -220f else 1520f
        val edge = if (fast) active.lastOrNull() else active.firstOrNull()
        if (active.size >= 6 || (edge != null && if (fast) edge.x - x < 280f else x - edge.x < 280f)) return
        val speed = if (fast) random.nextInt(60, 211) else -random.nextInt(40, 131)
        val car = Car(nextId++, random.nextInt(spriteCount), speed.toFloat(), x)
        if (fast) active.add(car) else active.add(0, car)
    }
}
