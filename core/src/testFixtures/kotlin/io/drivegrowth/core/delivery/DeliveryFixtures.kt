package io.drivegrowth.core.delivery

import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RuleVersion

/** 시험값. #24 제안이며 PRD에 확정된 제품 규칙이 아니다. 수익 탁송 최소 거리와 제시 개수는 #24에서 논의 중이다. 제품에 연결하기 전에 합의를 확인한다. */
object DeliveryFixtures {
    private fun weights(common: Int, advanced: Int, rare: Int, legend: Int) = mapOf(
        Rarity.COMMON to common, Rarity.ADVANCED to advanced, Rarity.RARE to rare, Rarity.LEGEND to legend,
    )

    val trialRules = DeliveryRules(
        ruleVersion = RuleVersion("delivery-trial-v0"),
        bands = listOf(
            DeliveryBand(10_000, weights(850, 139, 10, 1), incomePercent = 50),
            DeliveryBand(50_000, weights(700, 240, 55, 5), incomePercent = 100),
            DeliveryBand(null, weights(600, 300, 90, 10), incomePercent = 150),
        ),
        rarityPricePercent = mapOf(
            Rarity.COMMON to 100, Rarity.ADVANCED to 120, Rarity.RARE to 150, Rarity.LEGEND to 200,
        ),
        incomeRatePercent = 10,
        incomeMinMeters = 1_000,
        incomeOfferCount = 2,
        purchaseOfferCount = 3,
        rounding = DeliveryRounding.DOWN,
    )
}
