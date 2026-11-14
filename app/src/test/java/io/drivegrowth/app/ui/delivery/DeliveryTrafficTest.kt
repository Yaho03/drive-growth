package io.drivegrowth.app.ui.delivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryTrafficTest {
    @Test
    fun overlappingArrivalTimesKeepSeveralCarsInOrderWithoutUnboundedTraffic() {
        val traffic = DeliveryTraffic(20)
        val appearances = mutableSetOf<Int>()
        var followed = false
        var multipleVisible = false
        var approachedFromAhead = false
        var approachedFromBehind = false
        var removed = false
        repeat(3_200) {
            val previous = traffic.cars.map { it.copy() }
            traffic.advance(32)
            val cars = traffic.cars
            assertTrue(cars.size <= 6)
            multipleVisible = multipleVisible || cars.count { it.x in 0f..1360f } >= 2
            for (car in cars) {
                appearances += car.sprite
                approachedFromAhead = approachedFromAhead || car.cruiseSpeed < 0
                approachedFromBehind = approachedFromBehind || car.cruiseSpeed > 0
                val before = previous.singleOrNull { it.id == car.id }
                if (before != null && car.cruiseSpeed > 0) {
                    followed = followed || car.x - before.x < car.cruiseSpeed * .032f - 1f
                }
            }
            removed = removed || previous.any { before -> cars.none { it.id == before.id } }
            assertTrue(cars.zipWithNext().all { (ahead, behind) -> ahead.x - behind.x >= 239.9f })
        }
        assertTrue(followed && multipleVisible)
        assertTrue(approachedFromAhead && approachedFromBehind)
        assertTrue(appearances.size > 3)
        assertTrue(removed)
    }

    @Test
    fun resetReplaysTheSameSceneAndZeroTimeLeavesItStill() {
        val traffic = DeliveryTraffic(20)
        fun run(): List<DeliveryTraffic.Car> {
            repeat(220) { traffic.advance(32) }
            return traffic.cars.map { it.copy() }
        }
        val first = run()
        repeat(60) { traffic.advance(0) }
        assertEquals(first, traffic.cars)
        traffic.reset()
        assertTrue(traffic.cars.isEmpty())
        assertEquals(first, run())
    }
}
