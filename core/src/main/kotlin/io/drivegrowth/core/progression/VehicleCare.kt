package io.drivegrowth.core.progression

// 수치는 PRD "렌터카 영업·차량·시설"의 시험값. 금액은 게임머니 정수, 퍼센트 계산은 내림.
// ponytail: 반올림 규칙은 PRD 미정이라 내림으로 고정, 산식 확정 시 이 파일만 바꾼다.
data class VehicleCareRules(
    val maxLevel: Int = 20,
    val levelUpCostPercentPerLevel: Int = 2,
    val materialLevels: Int = 3,
    val rentBonusPercentPerLevel: Int = 2,
    val rentPenaltyPercentPerAccident: Int = 3,
    val maxRentPenaltyPercent: Int = 15,
    val repairCostPercent: Int = 5,
    val salePercent: Int = 60,
    val salePenaltyPointsPerAccident: Int = 5,
    val minSalePercent: Int = 30,
    val scrapOnlyAccidents: Int = 3,
    val scrapPercent: Int = 20,
)

enum class VehicleActivity { IDLE, RENTED, RETURNING, NEEDS_REPAIR }
enum class VehicleAction { LEVEL_UP, USE_MATERIAL, SELL, SCRAP, REPAIR }

class VehicleCare(private val rules: VehicleCareRules = VehicleCareRules()) {
    fun levelUpCost(price: Long, level: Int): Long {
        requireLevel(level)
        require(level < rules.maxLevel) { "최대 레벨은 레벨업할 수 없다" }
        return percentOf(price, rules.levelUpCostPercentPerLevel.toLong() * level)
    }

    // 최대 레벨을 넘는 레벨은 버린다. 이미 최대 레벨이면 재료를 쓸 수 없다.
    fun levelAfterMaterial(level: Int): Int {
        requireLevel(level)
        require(level < rules.maxLevel) { "최대 레벨 차량에는 재료를 쓸 수 없다" }
        return minOf(level + rules.materialLevels, rules.maxLevel)
    }

    // B(영업)가 기본 대여료에 곱하는 배율(%). 레벨 1이 기준 100.
    fun rentPercent(level: Int, accidents: Int): Int {
        requireLevel(level)
        val penalty = minOf(accidents.nonNegative() * rules.rentPenaltyPercentPerAccident, rules.maxRentPenaltyPercent)
        return 100 + (level - 1) * rules.rentBonusPercentPerLevel - penalty
    }

    fun repairCost(price: Long): Long = percentOf(price, rules.repairCostPercent.toLong())

    fun canSell(accidents: Int): Boolean = accidents.nonNegative() < rules.scrapOnlyAccidents

    // 판매 시 레벨 가산은 PRD 미정이라 넣지 않는다.
    fun salePrice(purchasePrice: Long, accidents: Int): Long {
        require(canSell(accidents)) { "사고 ${rules.scrapOnlyAccidents}회 이상은 폐차만 가능하다" }
        val percent = maxOf(rules.salePercent - accidents * rules.salePenaltyPointsPerAccident, rules.minSalePercent)
        return percentOf(purchasePrice, percent.toLong())
    }

    fun scrapValue(purchasePrice: Long): Long = percentOf(purchasePrice, rules.scrapPercent.toLong())

    fun isAllowed(activity: VehicleActivity, action: VehicleAction): Boolean = when (activity) {
        VehicleActivity.IDLE -> action != VehicleAction.REPAIR
        VehicleActivity.RENTED, VehicleActivity.RETURNING -> false
        VehicleActivity.NEEDS_REPAIR -> action in setOf(VehicleAction.REPAIR, VehicleAction.SELL, VehicleAction.SCRAP)
    }

    private fun requireLevel(level: Int) = require(level in 1..rules.maxLevel) { "레벨은 1..${rules.maxLevel}" }
    private fun Int.nonNegative(): Int = also { require(it >= 0) { "사고 횟수는 0 이상" } }
    private fun percentOf(amount: Long, percent: Long): Long {
        require(amount >= 0) { "금액은 0 이상" }
        return Math.multiplyExact(amount, percent) / 100
    }
}
