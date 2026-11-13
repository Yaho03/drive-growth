package io.drivegrowth.core.achievement

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class AchievementContractTest {
    private val owned = AchievementDefinition(AchievementMetric.OWNED, RuleVersion("trial-1"), 2, GameMoney(100), true)
    private val profile = ProfileId("test-profile")
    private val source = InputSource.SIMULATION

    @Test fun `도감 분자는 실수 반올림 없이 목표 비율과 비교한다`() {
        val definition = owned.copy(metric = AchievementMetric.COLLECTION, goal = 25)
        val catalog = ContentVersion("catalog")
        assertFalse(definition.reached(AchievementMeasure(24, 100, catalog)))
        assertTrue(definition.reached(AchievementMeasure(1, 4, catalog)))
        assertFalse(definition.reached(AchievementMeasure(1)))
    }

    @Test fun `처음 달성한 근거와 보상은 현재값과 새 정의로 재계산하지 않는다`() {
        val earned = AchievementMilestone(profile, source, owned, AchievementMeasure(2), EpochMillis(10))
        val current = AchievementProgress(owned.copy(version = RuleVersion("trial-2"), goal = 4),
            AchievementMeasure(1), earned)
        assertEquals(AchievementStatus.CLAIMABLE, current.status)
        val command = AchievementClaimCommand(RequestId("achievement/claim/first"), earned)
        assertEquals(ContractResult.Success(GameMoney(110)), achievementBalance(command, GameMoney(10)))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), achievementBalance(command, GameMoney(Long.MAX_VALUE)))
    }

    @Test fun `확인할 수 없는 값은 수령 가능으로 바꾸지 않는다`() {
        assertEquals(AchievementStatus.UNKNOWN, AchievementProgress(owned, null).status)
        assertEquals(AchievementStatus.IN_PROGRESS, AchievementProgress(owned, AchievementMeasure(1)).status)
    }
}
