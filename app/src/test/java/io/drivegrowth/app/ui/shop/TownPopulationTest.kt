package io.drivegrowth.app.ui.shop

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TownPopulationTest {
    @Test fun walkMapRejectsDisconnectedAndBlockedTargetsAndDetoursParkedCar() {
        val car = TownPoint(650f, 680f)
        val map = TownWalkMap(listOf(car))
        val from = TownPoint(610f, 652f)
        val to = TownPoint(710f, 697f)
        assertFalse(map.clearLine(from, to))
        val path = checkNotNull(map.path(from, to))
        assertTrue(path.size > 1)
        assertEquals(to, path.last())
        assertTrue((listOf(from) + path).zipWithNext().all { (a, b) -> map.clearLine(a, b) })
        assertNull(map.path(from, car))
        assertNull(map.path(from, map.destinations(TownZone.FUEL).first()))
    }

    @Test fun mechanicReachesAValidApproachToTheFirstParkedCar() {
        val car = TownPoint(253f, 635f)
        val parked = listOf(car, TownPoint(326f, 672f), TownPoint(400f, 709f))
        val people = TownPopulation(parked, listOf(car), false, false, Random(14))
        val mechanic = people.residents.single { it.role.repairs }
        val map = TownWalkMap(parked)
        var worked = false
        repeat(1600) {
            people.step(.05f)
            assertTrue(map.allows(mechanic.point))
            if (mechanic.activity == TownPopulation.Activity.WORKING) {
                worked = true
                assertTrue(mechanic.point.distanceTo(car) < 65f)
            }
        }
        assertTrue("정비사가 주차 슬롯 안으로 들어가지 않고 근처에 도달해야 한다", worked)
    }

    @Test fun mechanicReachesEveryParkedSlotOnTheWidenedLot() {
        // 공터 걷기 영역(LOT)이 주차 칸 둘레의 아스팔트까지 덮으므로 구석인 1번 칸 차량에도 접근할 수 있다.
        val slots = townSlots.map { (x, y) -> TownPoint(x.toFloat(), y.toFloat()) }
        for (count in 4..slots.size) {
            val parked = slots.take(count)
            val map = TownWalkMap(parked)
            for ((index, car) in parked.withIndex()) {
                val people = TownPopulation(parked, listOf(car), true, true, Random(14))
                val mechanic = people.residents.single { it.role.repairs }
                var worked = false
                for (step in 0 until 2000) {
                    people.step(.05f)
                    assertTrue(map.allows(mechanic.point))
                    if (mechanic.activity == TownPopulation.Activity.WORKING) {
                        assertTrue(mechanic.point.distanceTo(car) <= 85f)
                        worked = true
                        break
                    }
                }
                assertTrue("$count 대 주차 배치의 ${index + 1}번 차량에 접근해야 한다", worked)
            }
        }
    }

    @Test fun mechanicWaitsInPlaceWhenTheBrokenCarHasNoReachableApproach() {
        // 수리할 차를 다른 차들로 빙 둘러싸 걸어서 닿을 접근점이 없게 만든다.
        val broken = TownPoint(700f, 760f)
        val ring = listOf(
            TownPoint(590f, 760f), TownPoint(810f, 760f), TownPoint(700f, 700f), TownPoint(700f, 820f),
            TownPoint(610f, 710f), TownPoint(790f, 710f), TownPoint(610f, 810f), TownPoint(790f, 810f),
        )
        val parked = listOf(broken) + ring
        val map = TownWalkMap(parked)
        val people = TownPopulation(parked, listOf(broken), false, false, Random(14))
        val mechanic = people.residents.single { it.role.repairs }
        val component = checkNotNull(map.component(mechanic.point))
        assertTrue("시험 전제: 정비사 구역에서 고장 차 85px 안의 접근점이 없어야 한다", map.repairApproaches(broken, component).isEmpty())
        val start = mechanic.point
        repeat(600) {
            people.step(.05f)
            assertNotEquals(TownPopulation.Activity.WORKING, mechanic.activity)
            assertTrue(map.allows(mechanic.point))
        }
        assertEquals("접근점이 없으면 걷지 않고 제자리에서 기다린다", start, mechanic.point)
    }

    @Test fun visitorsEnterAndExitWithoutLeavingWalkableMap() {
        val people = TownPopulation(emptyList(), emptyList(), false, false, Random(3))
        val map = TownWalkMap(emptyList())
        var inside = false
        var exiting = false
        repeat(3000) {
            people.step(.05f)
            assertTrue("보행 영역 이탈: ${people.residents.filterNot { map.allows(it.point) }.map { it.point }}", people.residents.all { map.allows(it.point) })
            inside = inside || people.residents.any { !it.visible }
            exiting = exiting || people.residents.any { it.activity == TownPopulation.Activity.EXITING }
        }
        assertTrue(inside)
        assertTrue(exiting)
    }

    @Test fun cancelledPointerCannotLiftOrWaveAndNextIndependentPressStillWorks() {
        val people = TownPopulation(emptyList(), emptyList(), false, false, Random(2))
        val (first, second) = people.residents
        val touch = TownResidentTouch()
        touch.begin(4, first, first.point.x, first.point.y)
        touch.begin(9, second, second.point.x, second.point.y)
        touch.release(9)
        assertEquals(4, touch.pointer)
        touch.cancel()
        touch.hold()
        touch.release(4)
        assertFalse(first.pressed || first.held)
        assertEquals(0f, first.reaction, 0f)
        assertEquals(0f, second.reaction, 0f)

        touch.begin(10, second, second.point.x, second.point.y)
        touch.release(10)
        assertTrue(second.reaction > 0f)
    }

    @Test fun fuelWorkerCannotBeDroppedInDisconnectedParkingArea() {
        val people = TownPopulation(emptyList(), emptyList(), true, false, Random(5))
        val worker = people.residents.single { it.role.sprite == "fuel" }
        val original = worker.point
        val map = TownWalkMap(emptyList())
        val otherZone = map.destinations(TownZone.LOT).first()
        val touch = TownResidentTouch()
        touch.begin(4, worker, original.x, original.y)
        touch.hold()
        people.moveHeld(worker, otherZone)
        touch.release(4)
        assertEquals(original, worker.point)
        repeat(300) { people.step(.05f) }
        assertTrue(TownZone.FUEL.contains(worker.point))
        assertNotEquals(original, worker.point)
    }

    @Test fun stoppingMotionClearsHeldDropAndWave() {
        val people = TownPopulation(emptyList(), emptyList(), false, false, Random(2))
        val (first, second, third) = people.residents
        val touch = TownResidentTouch()
        touch.begin(2, first, first.point.x, first.point.y)
        touch.hold()
        touch.release(2)
        assertTrue(first.drop > 0f)
        touch.begin(3, second, second.point.x, second.point.y)
        touch.release(3)
        assertTrue(second.reaction > 0f)
        touch.begin(4, third, third.point.x, third.point.y)
        touch.hold()
        assertTrue(third.held)
        touch.cancel()
        people.stopReactions()
        assertTrue(people.residents.all { !it.pressed && !it.held && it.drop == 0f && it.reaction == 0f })
    }
}
