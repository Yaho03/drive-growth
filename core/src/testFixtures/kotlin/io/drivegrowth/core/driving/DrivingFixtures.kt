package io.drivegrowth.core.driving

import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.RuleVersion

/** 시험값. 하루 500km는 PRD 값이고, 1km당 10은 #24 제안 단계라 확정 전이다. */
object DrivingFixtures {
    val trialRules = DrivingIncomeRules(
        ruleVersion = RuleVersion("driving-trial-v0"),
        dailyCapMeters = 500_000,
        incomePerKm = GameMoney(10),
    )
}
