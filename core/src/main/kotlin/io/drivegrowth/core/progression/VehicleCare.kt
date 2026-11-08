package io.drivegrowth.core.progression

import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.Rarity
import java.math.BigInteger

// 수치는 PRD "렌터카 영업·차량·시설"의 시험값. 금액은 게임머니 정수, 퍼센트 계산은 내림.
// ponytail: 반올림 규칙은 PRD 미정이라 내림으로 고정, 산식 확정 시 이 파일만 바꾼다.
data class VehicleCareRules(
    val maxLevel: Int = 20,
    val levelUpCostPercentPerLevel: Int = 2,
    /** 레벨업·수리 비용 배율(%). PRD 2026-11-05: 일반 1.0·고급 1.05·희귀 1.1·전설 1.15. */
    val costMultiplierPercent: Map<Rarity, Int> =
        mapOf(Rarity.COMMON to 100, Rarity.ADVANCED to 105, Rarity.RARE to 110, Rarity.LEGEND to 115),
    /** 재료 희귀도별 레벨 진행량(100 = 1레벨). PRD 2026-11-05: 100·150·250·400. */
    val materialProgressPercent: Map<Rarity, Int> =
        mapOf(Rarity.COMMON to 100, Rarity.ADVANCED to 150, Rarity.RARE to 250, Rarity.LEGEND to 400),
    val rentBonusPercentPerLevel: Int = 2,
    val rentPenaltyPercentPerAccident: Int = 3,
    val maxRentPenaltyPercent: Int = 15,
    val repairCostPercent: Int = 5,
    val salePercent: Int = 60,
    val salePenaltyPointsPerAccident: Int = 5,
    val minSalePercent: Int = 30,
    /** 레벨 1 초과분 레벨당 판매가 가산(%p). PRD 2026-11-05: +1%p, Lv20 사고 없음 79%. */
    val saleLevelBonusPointsPerLevel: Int = 1,
    val scrapOnlyAccidents: Int = 3,
    val scrapPercent: Int = 20,
)

data class LevelProgress(val level: Int, val progressPercent: Int)

enum class VehicleActivity { IDLE, RENTED, RETURNING, NEEDS_REPAIR }
enum class VehicleAction { LEVEL_UP, USE_MATERIAL, SELL, SCRAP, REPAIR }

class VehicleCare(private val rules: VehicleCareRules = VehicleCareRules()) {
    /** 차종 가격 × 2% × 현재 레벨 × 희귀도 비용 배율. 한 번만 내림한다. */
    fun levelUpCost(price: Long, level: Int, rarity: Rarity): Long {
        requireLevel(level)
        require(level < rules.maxLevel) { "최대 레벨은 레벨업할 수 없다" }
        require(price >= 0) { "금액은 0 이상" }
        val percentTimes100 = rules.levelUpCostPercentPerLevel.toLong() * level * rules.costMultiplierPercent.getValue(rarity)
        // 중간 곱이 Long을 넘어도 최종 비용이 Long 안이면 계산한다. 최종값이 넘으면 ArithmeticException.
        return BigInteger.valueOf(price).multiply(BigInteger.valueOf(percentTimes100))
            .divide(BigInteger.valueOf(10_000)).longValueExact()
    }

    /** 게임머니 레벨업은 한 레벨 올리고 진행량은 유지한다. 최대 레벨이 되면 진행량은 0. */
    fun afterLevelUp(current: LevelProgress): LevelProgress {
        requireProgress(current)
        require(current.level < rules.maxLevel) { "최대 레벨은 레벨업할 수 없다" }
        val level = current.level + 1
        return LevelProgress(level, if (level == rules.maxLevel) 0 else current.progressPercent)
    }

    /** 재료 희귀도만큼 진행량을 채우고 남는 진행량은 다음 레벨로 넘긴다. 최대 레벨을 넘는 진행량은 버린다. */
    fun afterMaterial(current: LevelProgress, materialRarity: Rarity): LevelProgress {
        requireProgress(current)
        require(current.level < rules.maxLevel) { "최대 레벨 차량에는 재료를 쓸 수 없다" }
        val total = current.level * 100 + current.progressPercent + rules.materialProgressPercent.getValue(materialRarity)
        val level = minOf(total / 100, rules.maxLevel)
        return LevelProgress(level, if (level == rules.maxLevel) 0 else total % 100)
    }

    // B(영업)가 기본 대여료에 곱하는 배율(%). 레벨 1이 기준 100.
    fun rentPercent(level: Int, accidents: Int): Int {
        requireLevel(level)
        val penalty = minOf(accidents.nonNegative() * rules.rentPenaltyPercentPerAccident, rules.maxRentPenaltyPercent)
        return 100 + (level - 1) * rules.rentBonusPercentPerLevel - penalty
    }

    /** 차종 가격 × 5% × 희귀도 비용 배율. 마지막에 한 번 내림. */
    fun repairCost(modelPrice: Long, rarity: Rarity): Long =
        percentOf(modelPrice, rules.repairCostPercent.toLong() * rules.costMultiplierPercent.getValue(rarity), 10_000)

    fun canSell(accidents: Int): Boolean = accidents.nonNegative() < rules.scrapOnlyAccidents

    /**
     * 판매가 = 실제 취득액 × (60% − 사고당 5%p, 최저 30%, + 레벨 1 초과분 레벨당 1%p).
     * 기준은 개체의 실제 취득액(무료권·첫 지급 0)이며 차종 가격·희귀도는 쓰지 않는다(PRD 2026-11-07).
     */
    fun salePrice(vehicle: OwnedVehicle, accidents: Int): Long {
        require(canSell(accidents)) { "사고 ${rules.scrapOnlyAccidents}회 이상은 폐차만 가능하다" }
        // 규칙 값이 커도 Int로 잘리지 않도록 비율도 BigInteger로 계산한다.
        val penalty = big(accidents).multiply(big(rules.salePenaltyPointsPerAccident))
        val base = big(rules.salePercent).subtract(penalty).max(big(rules.minSalePercent))
        val percent = base.add(big(vehicle.level - 1).multiply(big(rules.saleLevelBonusPointsPerLevel)))
        return percentOf(vehicle.acquisitionCost.units, percent)
    }

    /** 폐차 = 실제 취득액의 20%. 사고 횟수와 무관하게 가능하다(사고 3회 이상은 폐차만 가능). */
    fun scrapValue(vehicle: OwnedVehicle): Long = percentOf(vehicle.acquisitionCost.units, rules.scrapPercent.toLong())

    fun isAllowed(activity: VehicleActivity, action: VehicleAction): Boolean = when (activity) {
        VehicleActivity.IDLE -> action != VehicleAction.REPAIR
        VehicleActivity.RENTED, VehicleActivity.RETURNING -> false
        VehicleActivity.NEEDS_REPAIR -> action in setOf(VehicleAction.REPAIR, VehicleAction.SELL, VehicleAction.SCRAP)
    }

    private fun requireLevel(level: Int) = require(level in 1..rules.maxLevel) { "레벨은 1..${rules.maxLevel}" }
    private fun requireProgress(p: LevelProgress) {
        requireLevel(p.level)
        require(p.progressPercent in 0..99) { "진행량은 0..99" }
    }
    private fun Int.nonNegative(): Int = also { require(it >= 0) { "사고 횟수는 0 이상" } }
    /** amount × numerator / denominator(내림). 중간 곱이 Long을 넘어도 최종값이 Long 안이면 계산하고, 넘으면 ArithmeticException. */
    private fun percentOf(amount: Long, numerator: Long, denominator: Long = 100): Long =
        percentOf(amount, BigInteger.valueOf(numerator), denominator)

    private fun percentOf(amount: Long, numerator: BigInteger, denominator: Long = 100): Long {
        require(amount >= 0) { "금액은 0 이상" }
        require(numerator.signum() >= 0) { "비율은 0 이상" }
        return BigInteger.valueOf(amount).multiply(numerator).divide(BigInteger.valueOf(denominator)).longValueExact()
    }

    private fun big(value: Int): BigInteger = BigInteger.valueOf(value.toLong())
}
