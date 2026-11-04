package io.drivegrowth.core.progression

import io.drivegrowth.core.progression.VehicleAction.*
import io.drivegrowth.core.progression.VehicleActivity.*
import org.junit.Assert.*
import org.junit.Test

class VehicleCareTest {
    private val care = VehicleCare()

    @Test fun `레벨업 비용은 차값의 2퍼센트에 현재 레벨을 곱한다`() {
        assertEquals(200L, care.levelUpCost(10_000, 1))
        assertEquals(1_000L, care.levelUpCost(10_000, 5))
        assertEquals(3_800L, care.levelUpCost(10_000, 19))
        assertThrows(IllegalArgumentException::class.java) { care.levelUpCost(10_000, 20) }
    }

    @Test fun `중복 재료는 3레벨을 올리고 20에서 멈추며 만렙에는 쓸 수 없다`() {
        assertEquals(8, care.levelAfterMaterial(5))
        assertEquals(20, care.levelAfterMaterial(18))
        assertEquals(20, care.levelAfterMaterial(19))
        assertThrows(IllegalArgumentException::class.java) { care.levelAfterMaterial(20) }
    }

    @Test fun `대여료 배율은 레벨당 2퍼센트 오르고 사고당 3퍼센트 내려가며 감소는 15퍼센트까지다`() {
        assertEquals(100, care.rentPercent(1, 0))
        assertEquals(108, care.rentPercent(5, 0))
        assertEquals(138, care.rentPercent(20, 0))
        assertEquals(94, care.rentPercent(1, 2))
        assertEquals(85, care.rentPercent(1, 5))
        assertEquals(85, care.rentPercent(1, 10))
    }

    @Test fun `수리비는 차값의 5퍼센트이고 소수는 내린다`() {
        assertEquals(500L, care.repairCost(10_000))
        assertEquals(49L, care.repairCost(999))
    }

    @Test fun `판매가는 60퍼센트에서 사고당 5퍼센트포인트 내려가고 사고 3회부터는 판매할 수 없다`() {
        assertEquals(6_000L, care.salePrice(10_000, 0))
        assertEquals(5_000L, care.salePrice(10_000, 2))
        assertFalse(care.canSell(3))
        assertThrows(IllegalArgumentException::class.java) { care.salePrice(10_000, 3) }
    }

    @Test fun `판매가는 최저 30퍼센트 아래로 내려가지 않는다`() {
        val lenient = VehicleCare(VehicleCareRules(scrapOnlyAccidents = 100))
        assertEquals(3_500L, lenient.salePrice(10_000, 5))
        assertEquals(3_000L, lenient.salePrice(10_000, 6))
        assertEquals(3_000L, lenient.salePrice(10_000, 8))
    }

    @Test fun `폐차 금액은 구매가의 20퍼센트다`() {
        assertEquals(2_000L, care.scrapValue(10_000))
    }

    @Test fun `대여 중이거나 반납 중인 차는 아무 행동도 할 수 없다`() {
        for (activity in listOf(RENTED, RETURNING)) for (action in VehicleAction.entries) {
            assertFalse("$activity $action", care.isAllowed(activity, action))
        }
    }

    @Test fun `수리 필요 차는 수리와 판매와 폐차만 할 수 있다`() {
        assertEquals(setOf(REPAIR, SELL, SCRAP), VehicleAction.entries.filter { care.isAllowed(NEEDS_REPAIR, it) }.toSet())
    }

    @Test fun `대기 중인 차는 수리를 빼고 모두 할 수 있다`() {
        assertEquals(setOf(LEVEL_UP, USE_MATERIAL, SELL, SCRAP), VehicleAction.entries.filter { care.isAllowed(IDLE, it) }.toSet())
    }

    @Test fun `음수 금액과 음수 사고 횟수와 범위 밖 레벨은 거절한다`() {
        assertThrows(IllegalArgumentException::class.java) { care.repairCost(-1) }
        assertThrows(IllegalArgumentException::class.java) { care.rentPercent(1, -1) }
        assertThrows(IllegalArgumentException::class.java) { care.rentPercent(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { care.rentPercent(21, 0) }
    }
}
