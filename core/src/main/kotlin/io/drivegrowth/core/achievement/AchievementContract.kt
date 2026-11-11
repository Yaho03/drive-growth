package io.drivegrowth.core.achievement

import io.drivegrowth.core.contract.*

enum class AchievementMetric(val id: String) {
    OWNED("owned-vehicles"), CUSTOMERS("completed-customers"), SHOP("shop-level"), COLLECTION("collection-completion"),
}

data class AchievementDefinition(
    val metric: AchievementMetric,
    val version: RuleVersion,
    val goal: Int,
    val reward: GameMoney,
    val trial: Boolean,
) {
    init { require(goal > 0 && reward.units > 0 && (metric != AchievementMetric.COLLECTION || goal <= 100)) }

    fun reached(value: AchievementMeasure): Boolean = if (metric == AchievementMetric.COLLECTION) {
        value.total?.let { value.count.toLong() * 100 >= goal.toLong() * it } ?: false
    } else value.total == null && value.count >= goal
}

data class AchievementMeasure(val count: Int, val total: Int? = null, val contentVersion: ContentVersion? = null) {
    init {
        require(count >= 0 && (total == null) == (contentVersion == null))
        require(total == null || total > 0 && count <= total)
    }
}

data class AchievementMilestone(
    val profileId: ProfileId,
    val source: InputSource,
    val definition: AchievementDefinition,
    val measure: AchievementMeasure,
    val observedAt: EpochMillis,
) {
    init { require(observedAt.value >= 0 && definition.reached(measure)) }
}

enum class AchievementStatus { UNKNOWN, IN_PROGRESS, CLAIMABLE, CLAIMED }

data class AchievementProgress(
    val definition: AchievementDefinition,
    val measure: AchievementMeasure?,
    val milestone: AchievementMilestone? = null,
    val claimed: Boolean = false,
) {
    init { require((!claimed || milestone != null) && (milestone == null || milestone.definition.metric == definition.metric)) }
    val status get() = when {
        claimed -> AchievementStatus.CLAIMED
        milestone != null -> AchievementStatus.CLAIMABLE
        measure == null -> AchievementStatus.UNKNOWN
        else -> AchievementStatus.IN_PROGRESS
    }
}

const val ACHIEVEMENT_CLAIM_PREFIX = "achievement/claim/"

data class AchievementClaimCommand(val requestId: RequestId, val milestone: AchievementMilestone) {
    init { require(requestId.value.startsWith(ACHIEVEMENT_CLAIM_PREFIX) && requestId.value.length > ACHIEVEMENT_CLAIM_PREFIX.length) }
    val profileId get() = milestone.profileId
    val source get() = milestone.source
}

data class AchievementClaimSettlement(val command: AchievementClaimCommand, val settlement: Settlement)

fun achievementBalance(command: AchievementClaimCommand, before: GameMoney): ContractResult<GameMoney> {
    val reward = command.milestone.definition.reward.units
    return if (reward > Long.MAX_VALUE - before.units) ContractResult.Failure(ContractError.INVALID_INPUT)
    else ContractResult.Success(GameMoney(before.units + reward))
}
