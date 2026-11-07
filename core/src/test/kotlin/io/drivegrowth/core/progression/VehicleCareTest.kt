package io.drivegrowth.core.progression

import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.progression.VehicleAction.*
import io.drivegrowth.core.progression.VehicleActivity.*
import org.junit.Assert.*
import org.junit.Test

class VehicleCareTest {
    private val care = VehicleCare()

    @Test fun `레벨업 비용은 차값의 2퍼센트에 현재 레벨과 희귀도 비용 배율을 곱하고 마지막에 내린다`() {
        assertEquals(200L, care.levelUpCost(10_000, 1, Rarity.COMMON))
        assertEquals(1_000L, care.levelUpCost(10_000, 5, Rarity.COMMON))
        assertEquals(3_800L, care.levelUpCost(10_000, 19, Rarity.COMMON))
        assertEquals(1_050L, care.levelUpCost(10_000, 5, Rarity.ADVANCED))
        assertEquals(1_100L, care.levelUpCost(10_000, 5, Rarity.RARE))
        assertEquals(4_370L, care.levelUpCost(10_000, 19, Rarity.LEGEND))
        assertEquals(16L, care.levelUpCost(777, 1, Rarity.ADVANCED)) // 777×2%×1.05 = 16.317 → 16
        assertThrows(IllegalArgumentException::class.java) { care.levelUpCost(10_000, 20, Rarity.COMMON) }
    }

    @Test fun `재료는 희귀도별 진행량을 채우고 남는 진행량을 넘기며 20을 넘는 몫은 버린다`() {
        assertEquals(LevelProgress(6, 0), care.afterMaterial(LevelProgress(5, 0), Rarity.COMMON))
        assertEquals(LevelProgress(6, 50), care.afterMaterial(LevelProgress(5, 0), Rarity.ADVANCED))
        assertEquals(LevelProgress(8, 0), care.afterMaterial(LevelProgress(5, 50), Rarity.RARE))
        assertEquals(LevelProgress(9, 20), care.afterMaterial(LevelProgress(5, 20), Rarity.LEGEND))
        assertEquals(LevelProgress(8, 40), care.afterMaterial(LevelProgress(6, 90), Rarity.ADVANCED)) // 6.9 + 1.5 = 8.4
        assertEquals(LevelProgress(20, 0), care.afterMaterial(LevelProgress(19, 0), Rarity.COMMON))
        assertEquals(LevelProgress(20, 0), care.afterMaterial(LevelProgress(18, 60), Rarity.LEGEND))
        assertThrows(IllegalArgumentException::class.java) { care.afterMaterial(LevelProgress(20, 0), Rarity.COMMON) }
        assertThrows(IllegalArgumentException::class.java) { care.afterMaterial(LevelProgress(5, 100), Rarity.COMMON) }
    }

    @Test fun `게임머니 레벨업은 진행량을 유지하고 20이 되면 진행량을 0으로 한다`() {
        assertEquals(LevelProgress(6, 40), care.afterLevelUp(LevelProgress(5, 40)))
        assertEquals(LevelProgress(20, 0), care.afterLevelUp(LevelProgress(19, 70)))
        assertThrows(IllegalArgumentException::class.java) { care.afterLevelUp(LevelProgress(20, 0)) }
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
