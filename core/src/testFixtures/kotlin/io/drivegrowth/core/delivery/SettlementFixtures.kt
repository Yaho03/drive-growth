package io.drivegrowth.core.delivery

import io.drivegrowth.core.contract.RuleVersion

/** PRD 시험값: 미도착 24시간, 임시 보관 24시간, 보관 만료 수수료 5%. */
object SettlementFixtures {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    val trialRules = DeliverySettlementRules(
        ruleVersion = RuleVersion("delivery-settle-trial-v0"),
        arrivalWindowMs = DAY_MS,
        holdWindowMs = DAY_MS,
        holdRefundFeePercent = 5,
    )
}
