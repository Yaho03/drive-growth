package io.drivegrowth.core.progression

import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
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
        // 중간 곱(price × 200)은 Long을 넘지만 최종 비용은 표현 가능
        assertEquals(184_467_440_737_095_516L, care.levelUpCost(Long.MAX_VALUE, 1, Rarity.COMMON))
        assertEquals(Long.MAX_VALUE / 10_000 * 4_370 + Long.MAX_VALUE % 10_000 * 4_370 / 10_000, care.levelUpCost(Long.MAX_VALUE, 19, Rarity.LEGEND))
        // 기본 규칙의 비용은 차값의 43.7% 이하라 넘지 않는다. 규칙을 바꿔 최종 비용이 Long을 넘으면 잘리지 않고 예외
        val steep = VehicleCare(VehicleCareRules(levelUpCostPercentPerLevel = 10)) // 19레벨 전설 = 218.5%
        assertThrows(ArithmeticException::class.java) { steep.levelUpCost(Long.MAX_VALUE, 19, Rarity.LEGEND) }
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

    private fun owned(cost: Long, level: Int = 1, source: VehicleAcquisitionSource = VehicleAcquisitionSource.GACHA,
                      rarity: Rarity = Rarity.COMMON) =
        OwnedVehicle(OwnedVehicleId("v"), VehicleId("hyundai_casper"), level, Revision(0), VehicleLifecycle.OWNED,
            if (source == VehicleAcquisitionSource.INITIAL_GRANT) Rarity.COMMON else rarity, 0, GameMoney(cost), source)

    @Test fun `수리비는 차종 가격의 5퍼센트에 희귀도 비용 배율을 곱하고 마지막에 내린다`() {
        assertEquals(500L, care.repairCost(10_000, Rarity.COMMON))
        assertEquals(525L, care.repairCost(10_000, Rarity.ADVANCED))
        assertEquals(550L, care.repairCost(10_000, Rarity.RARE))
        assertEquals(575L, care.repairCost(10_000, Rarity.LEGEND))
        assertEquals(49L, care.repairCost(999, Rarity.COMMON))   // 49.95 → 49
        assertEquals(57L, care.repairCost(999, Rarity.LEGEND))   // 57.4425 → 57
        assertEquals(Long.MAX_VALUE / 10_000 * 575 + Long.MAX_VALUE % 10_000 * 575 / 10_000,
            care.repairCost(Long.MAX_VALUE, Rarity.LEGEND)) // 중간 곱은 Long 초과
    }

    @Test fun `판매가는 실제 취득액 기준으로 사고당 5퍼센트포인트 빼고 레벨당 1퍼센트포인트 더한다`() {
        // PRD·06 기대값: 구입 뽑기권(500)·무료권·첫 지급 Lv1 → 300·0·0, Lv20 사고 없음 → 395(79%)
        assertEquals(300L, care.salePrice(owned(500), 0))
        assertEquals(0L, care.salePrice(owned(0), 0))
        assertEquals(0L, care.salePrice(owned(0, source = VehicleAcquisitionSource.INITIAL_GRANT), 0))
        assertEquals(395L, care.salePrice(owned(500, level = 20), 0))
        assertEquals(250L, care.salePrice(owned(500), 2))             // 50%
        assertEquals(345L, care.salePrice(owned(500, level = 20), 2)) // 50% + 19%p = 69%
        // 희귀도는 판매가에 영향 없음
        assertEquals(care.salePrice(owned(500), 1), care.salePrice(owned(500, rarity = Rarity.LEGEND), 1))
        assertFalse(care.canSell(3))
        assertThrows(IllegalArgumentException::class.java) { care.salePrice(owned(500), 3) }
    }

    @Test fun `판매가는 사고 감액이 최저 30퍼센트에서 멈추고 레벨 가산은 그 위에 더한다`() {
        val lenient = VehicleCare(VehicleCareRules(scrapOnlyAccidents = 100))
        assertEquals(3_500L, lenient.salePrice(owned(10_000), 5))
        assertEquals(3_000L, lenient.salePrice(owned(10_000), 6))
        assertEquals(3_000L, lenient.salePrice(owned(10_000), 8))
        assertEquals(4_900L, lenient.salePrice(owned(10_000, level = 20), 8)) // 30% + 19%p
    }

    @Test fun `판매 비율 계산은 규칙 값이 커도 Int로 잘리지 않고 최종값이 넘으면 예외다`() {
        // 리뷰 재현: Lv20·취득액 100·사고 0·레벨 가산 Int.MAX → 60 + 19 × Int.MAX = 40,802,189,353 (음수 아님)
        val bigBonus = VehicleCare(VehicleCareRules(saleLevelBonusPointsPerLevel = Int.MAX_VALUE))
        assertEquals(40_802_189_353L, bigBonus.salePrice(owned(100, level = 20), 0))
        assertEquals(2_147_483_707L, bigBonus.salePrice(owned(100, level = 2), 0)) // 인접 경계: 60 + Int.MAX
        assertEquals(60L, bigBonus.salePrice(owned(100, level = 1), 0))             // Lv1은 가산 없음
        assertThrows(ArithmeticException::class.java) { bigBonus.salePrice(owned(Long.MAX_VALUE, level = 20), 0) }
        // 사고 감액 곱도 잘리지 않는다: 2 × Int.MAX 감액이 음수로 뒤집히지 않고 최저 30%에서 멈춤
        val bigPenalty = VehicleCare(VehicleCareRules(salePenaltyPointsPerAccident = Int.MAX_VALUE, scrapOnlyAccidents = 100))
        assertEquals(30L, bigPenalty.salePrice(owned(100), 2))
        // 기본 규칙 기대값은 그대로
        assertEquals(listOf(300L, 0L, 395L), listOf(care.salePrice(owned(500), 0), care.salePrice(owned(0), 0), care.salePrice(owned(500, level = 20), 0)))
    }

    @Test fun `폐차 금액은 실제 취득액의 20퍼센트이고 무료 취득은 0이다`() {
        assertEquals(100L, care.scrapValue(owned(500)))
        assertEquals(100L, care.scrapValue(owned(500, level = 20)))
        assertEquals(0L, care.scrapValue(owned(0, source = VehicleAcquisitionSource.INITIAL_GRANT)))
        assertEquals(Long.MAX_VALUE / 5, care.scrapValue(owned(Long.MAX_VALUE)))
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
        assertThrows(IllegalArgumentException::class.java) { care.repairCost(-1, Rarity.COMMON) }
        assertThrows(IllegalArgumentException::class.java) { care.rentPercent(1, -1) }
        assertThrows(IllegalArgumentException::class.java) { care.rentPercent(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { care.rentPercent(21, 0) }
    }
}
