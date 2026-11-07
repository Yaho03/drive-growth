package io.drivegrowth.core.delivery

import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RuleVersion
import java.math.BigInteger

/** 금액 반올림은 팀 결정 전이라 규칙 입력으로 받는다. */
enum class DeliveryRounding { DOWN, HALF_UP }

/**
 * 예상 경로 거리 구간.
 *
 * @param maxMetersInclusive 구간 상한. 마지막 구간만 null이다.
 * @param rarityWeights 희귀도 천분율 가중치. Gacha와 같은 형식이다.
 * @param incomePercent 수익 탁송 보상의 거리 배율(100 = 1.0배).
 */
data class DeliveryBand(
    val maxMetersInclusive: Long?,
    val rarityWeights: Map<Rarity, Int>,
    val incomePercent: Int,
) {
    init {
        require(maxMetersInclusive == null || maxMetersInclusive > 0) { "구간 상한은 양수" }
        require(rarityWeights.values.all { it >= 0 } && rarityWeights.values.sum() > 0) { "가중치는 0 이상, 합은 양수" }
        require(incomePercent >= 0) { "거리 배율은 0 이상" }
    }
}

/**
 * 탁송 목록 규칙. 확정 전 수치를 제품 기본값으로 굳히지 않도록 기본값을 두지 않는다.
 *
 * @param bands 상한 오름차순. 마지막 구간은 상한이 없다.
 * @param rarityPricePercent 구매가의 희귀도 배율(100 = 1.0배).
 * @param incomeRatePercent 수익 보상의 차종 가격 비율.
 * @param incomeMinMeters 이 거리 미만이면 수익 탁송을 제시하지 않는다.
 */
data class DeliveryRules(
    val ruleVersion: RuleVersion,
    val bands: List<DeliveryBand>,
    val rarityPricePercent: Map<Rarity, Int>,
    val incomeRatePercent: Int,
    val incomeMinMeters: Long,
    val incomeOfferCount: Int,
    val purchaseOfferCount: Int,
    val rounding: DeliveryRounding,
) {
    init {
        require(bands.isNotEmpty() && bands.last().maxMetersInclusive == null) { "마지막 구간은 상한 없음" }
        val limits = bands.dropLast(1).map { requireNotNull(it.maxMetersInclusive) { "마지막 외 구간은 상한 필요" } }
        require(limits.zipWithNext().all { (a, b) -> a < b }) { "구간 상한은 오름차순" }
        require(Rarity.entries.all { (rarityPricePercent[it] ?: 0) > 0 }) { "모든 희귀도에 양수 가격 배율 필요" }
        require(incomeRatePercent >= 0 && incomeMinMeters >= 0) { "수익 비율과 최소 거리는 0 이상" }
        require(incomeOfferCount >= 0 && purchaseOfferCount >= 0) { "제시 개수는 0 이상" }
    }

    fun bandOf(meters: Long): DeliveryBand =
        bands.first { band -> band.maxMetersInclusive.let { it == null || meters <= it } }

    /** 구매가 = 차종 가격 × 희귀도 배율. */
    fun purchasePrice(vehiclePrice: GameMoney, rarity: Rarity): GameMoney =
        scale(vehiclePrice, rarityPricePercent.getValue(rarity).toLong(), 100)

    /** 수익 보상 = 차종 가격 × 수익 비율 × 거리 배율. 희귀도와 무관하다. */
    fun incomeReward(vehiclePrice: GameMoney, band: DeliveryBand): GameMoney =
        scale(vehiclePrice, incomeRatePercent.toLong() * band.incomePercent, 10_000)

    private fun scale(amount: GameMoney, numerator: Long, denominator: Long): GameMoney {
        val scaled = BigInteger.valueOf(amount.units) * BigInteger.valueOf(numerator)
        val divisor = BigInteger.valueOf(denominator)
        val rounded = when (rounding) {
            DeliveryRounding.DOWN -> scaled / divisor
            DeliveryRounding.HALF_UP -> (scaled * BigInteger.TWO + divisor) / (divisor * BigInteger.TWO)
        }
        return GameMoney(rounded.longValueExact())
    }
}
