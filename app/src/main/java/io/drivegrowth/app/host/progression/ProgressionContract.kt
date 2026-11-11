package io.drivegrowth.app.host.progression

import io.drivegrowth.app.data.RestDrivingProjection
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.progress.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.achievement.*
import io.drivegrowth.core.quest.*

enum class ProgressionPhase { SAVED, STARTED }
sealed interface ProgressionJournalState {
    data object Clear : ProgressionJournalState
    data object Foreign : ProgressionJournalState
    data class Quest(val command: QuestClaimCommand, val phase: ProgressionPhase) : ProgressionJournalState
    data class Achievement(val command: AchievementClaimCommand, val phase: ProgressionPhase) : ProgressionJournalState
}
/** 실제 공통 단일 checkpoint의 typed adapter. 이 포트 자체는 저장소가 아니다. */
interface ProgressionJournal {
    fun load(): ContractResult<ProgressionJournalState>
    fun save(command: QuestClaimCommand): ContractResult<Unit>
    fun execute(command: QuestClaimCommand): ContractResult<QuestClaimSettlement>
    fun finish(command: QuestClaimCommand): ContractResult<Unit>
    fun cancelSaved(command: QuestClaimCommand): ContractResult<Unit>
    fun save(command: AchievementClaimCommand): ContractResult<Unit> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    fun execute(command: AchievementClaimCommand): ContractResult<AchievementClaimSettlement> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    fun finish(command: AchievementClaimCommand): ContractResult<Unit> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    fun cancelSaved(command: AchievementClaimCommand): ContractResult<Unit> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
}
data class ProgressionRead(val identity: GameIdentity, val quests: List<QuestProgress>,
    val offers: Map<ProgressKey, QuestClaimCommand>, val journal: ProgressionJournalState,
    val journalSupported: Boolean,
    /** null은 검증된 원장 조회의 부재다. 조회실패를 null로 낮추면 안 된다. */
    val resolved: Map<ClaimReference, QuestClaimSettlement?> = emptyMap(),
    /** 미연결은 null이며 연결된 조회의 실패는 원래 오류를 보존한다. */
    val rest: ContractResult<RestDrivingProjection>? = null,
    val achievements: ContractResult<List<AchievementProgress>>? = null,
    val achievementOffers: Map<ProgressKey, AchievementClaimCommand> = emptyMap(),
    val resolvedAchievements: Map<ClaimReference, AchievementClaimSettlement?> = emptyMap())
interface ProgressionSource {
    fun read(references: Set<ClaimReference>): ContractResult<ProgressionRead>
    fun save(identity: GameIdentity, command: QuestClaimCommand): ContractResult<Unit>
    fun execute(identity: GameIdentity, command: QuestClaimCommand): ContractResult<QuestClaimSettlement>
    fun cancelSaved(identity: GameIdentity, command: QuestClaimCommand): ContractResult<Unit>
    fun save(identity: GameIdentity, command: AchievementClaimCommand): ContractResult<Unit> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    fun execute(identity: GameIdentity, command: AchievementClaimCommand): ContractResult<AchievementClaimSettlement> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    fun cancelSaved(identity: GameIdentity, command: AchievementClaimCommand): ContractResult<Unit> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
}
data class ProgressionFrame(val screen: ProgressScreenState, val controlsEnabled: Boolean,
    val resolved: Set<ClaimReference> = emptySet(), val retryComplete: Boolean = false)
internal fun progressKey(definition: QuestDefinition, period: QuestPeriod) = ProgressKey(definition.id,
    "${period.cadence.name}|${period.zone.id}|${period.start}")
internal fun progressKey(progress: QuestProgress) = progressKey(progress.definition, progress.period)

internal fun achievementKey(metric: AchievementMetric) = ProgressKey("achievement/${metric.id}", "lifetime")
