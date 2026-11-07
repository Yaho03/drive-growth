package io.drivegrowth.core.driving

import io.drivegrowth.core.contract.RuleVersion

/** PRD 휴식 버프 표의 시험값이다. 최종 밸런스가 아니다. */
object RestFixtures {
    val trialRules = RestRules(
        ruleVersion = RuleVersion("rest-trial-v0"),
        minDrivingMs = 60 * 60 * 1000L,
        minParkedMs = 5 * 60 * 1000L,
        bonusDurationMs = 20 * 60 * 1000L,
        bonusPercent = 150,
    )
}
