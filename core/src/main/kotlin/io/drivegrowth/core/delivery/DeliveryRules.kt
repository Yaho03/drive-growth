package io.drivegrowth.core.delivery

import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RuleVersion
import java.math.BigInteger
import java.util.Collections

/** 금액 반올림은 팀 결정 전이라 규칙 입력으로 받는다. */
enum class DeliveryRounding { DOWN, HALF_UP }

/**
 * 예상 경로 거리 구간. 입력 맵을 복사해 검증한 값이 바뀌지 않게 고정한다.
 *
 * @param maxMetersInclusive 구간 상한. 마지막 구간만 null이다.
 * @param rarityWeights 희귀도 가중치. 합계는 1 이상 Int 범위까지 지원한다.
 * @param incomePercent 수익 탁송 보상의 거리 배율(100 = 1.0배).
 */
class DeliveryBand(
    val maxMetersInclusive: Long?,
    rarityWeights: Map<Rarity, Int>,
    val incomePercent: Int,
) {
    val rarityWeights: Map<Rarity, Int> = Collections.unmodifiableMap(LinkedHashMap(rarityWeights))

    init {
        require(maxMetersInclusive == null || maxMetersInclusive > 0) { "구간 상한은 양수" }
        require(this.rarityWeights.values.all { it >= 0 }) { "가중치는 0 이상" }
        require(this.rarityWeights.values.sumOf { it.toLong() } in 1..Int.MAX_VALUE) { "가중치 합은 1 이상 Int 범위" }
        require(incomePercent >= 0) { "거리 배율은 0 이상" }
    }
}

/**
 * 탁송 목록 규칙. 확정 전 수치를 제품 기본값으로 굳히지 않도록 기본값을 두지 않는다.
 * 입력 목록과 맵을 복사해 검증한 값이 바뀌지 않게 고정한다.
 *
 * @param bands 상한 오름차순. 마지막 구간은 상한이 없다.
 * @param rarityPricePercent 구매가의 희귀도 배율(100 = 1.0배).
 * @param incomeRatePercent 수익 보상의 차종 가격 비율.
 * @param incomeMinMeters 이 거리 미만이면 수익 탁송을 제시하지 않는다.
 */
class DeliveryRules(
    val ruleVersion: RuleVersion,
    bands: List<DeliveryBand>,
    rarityPricePercent: Map<Rarity, Int>,
    val incomeRatePercent: Int,
    val incomeMinMeters: Long,
    val incomeOfferCount: Int,
    val purchaseOfferCount: Int,
    val rounding: DeliveryRounding,
) {
    val bands: List<DeliveryBand> = Collections.unmodifiableList(ArrayList(bands))
    val rarityPricePercent: Map<Rarity, Int> = Collections.unmodifiableMap(LinkedHashMap(rarityPricePercent))

    init {
        require(this.bands.isNotEmpty() && this.bands.last().maxMetersInclusive == null) { "마지막 구간은 상한 없음" }
        val limits = this.bands.dropLast(1).map { requireNotNull(it.maxMetersInclusive) { "마지막 외 구간은 상한 필요" } }
        require(limits.zipWithNext().all { (a, b) -> a < b }) { "구간 상한은 오름차순" }
        require(Rarity.entries.all { (this.rarityPricePercent[it] ?: 0) > 0 }) { "모든 희귀도에 양수 가격 배율 필요" }
        require(incomeRatePercent >= 0 && incomeMinMeters >= 0) { "수익 비율과 최소 거리는 0 이상" }
        require(incomeOfferCount >= 0 && purchaseOfferCount >= 0) { "제시 개수는 0 이상" }
    }

    fun copy(
        ruleVersion: RuleVersion = this.ruleVersion,
        bands: List<DeliveryBand> = this.bands,
        rarityPricePercent: Map<Rarity, Int> = this.rarityPricePercent,
        incomeRatePercent: Int = this.incomeRatePercent,
        incomeMinMeters: Long = this.incomeMinMeters,
        incomeOfferCount: Int = this.incomeOfferCount,
        purchaseOfferCount: Int = this.purchaseOfferCount,
        rounding: DeliveryRounding = this.rounding,
    ) = DeliveryRules(
        ruleVersion, bands, rarityPricePercent, incomeRatePercent,
        incomeMinMeters, incomeOfferCount, purchaseOfferCount, rounding,
    )

    fun bandIndexOf(meters: Long): Int =
        bands.indexOfFirst { band -> band.maxMetersInclusive.let { it == null || meters <= it } }

    fun bandOf(meters: Long): DeliveryBand = bands[bandIndexOf(meters)]

    /** 구매가 = 차종 가격 × 희귀도 배율. GameMoney 범위를 넘으면 null이다. */
    fun purchasePrice(vehiclePrice: GameMoney, rarity: Rarity): GameMoney? =
        scale(vehiclePrice, rarityPricePercent.getValue(rarity).toLong(), 100)

    /** 수익 보상 = 차종 가격 × 수익 비율 × 거리 배율. 희귀도와 무관하다. GameMoney 범위를 넘으면 null이다. */
    fun incomeReward(vehiclePrice: GameMoney, band: DeliveryBand): GameMoney? =
        scale(vehiclePrice, incomeRatePercent.toLong() * band.incomePercent, 10_000)

    private fun scale(amount: GameMoney, numerator: Long, denominator: Long): GameMoney? {
        val scaled = BigInteger.valueOf(amount.units) * BigInteger.valueOf(numerator)
        val divisor = BigInteger.valueOf(denominator)
        val rounded = when (rounding) {
            DeliveryRounding.DOWN -> scaled / divisor
            DeliveryRounding.HALF_UP -> (scaled * BigInteger.TWO + divisor) / (divisor * BigInteger.TWO)
        }
        return if (rounded.bitLength() < Long.SIZE_BITS) GameMoney(rounded.toLong()) else null
    }
}
