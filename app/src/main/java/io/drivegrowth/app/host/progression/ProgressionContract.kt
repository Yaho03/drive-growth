package io.drivegrowth.app.host.progression

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.progress.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.quest.*

enum class ProgressionPhase { SAVED, STARTED }
sealed interface ProgressionJournalState {
    data object Clear : ProgressionJournalState
    data object Foreign : ProgressionJournalState
    data class Quest(val command: QuestClaimCommand, val phase: ProgressionPhase) : ProgressionJournalState
}
/** 실제 공통 단일 checkpoint의 typed adapter. 이 포트 자체는 저장소가 아니다. */
interface ProgressionJournal {
    fun load(): ContractResult<ProgressionJournalState>
    fun save(command: QuestClaimCommand): ContractResult<Unit>
    fun execute(command: QuestClaimCommand): ContractResult<QuestClaimSettlement>
    fun finish(command: QuestClaimCommand): ContractResult<Unit>
    fun cancelSaved(command: QuestClaimCommand): ContractResult<Unit>
}
data class ProgressionRead(val identity: GameIdentity, val quests: List<QuestProgress>,
    val offers: Map<ProgressKey, QuestClaimCommand>, val journal: ProgressionJournalState,
    val journalSupported: Boolean,
    /** null은 검증된 원장 조회의 부재다. 조회실패를 null로 낮추면 안 된다. */
    val resolved: Map<ClaimReference, QuestClaimSettlement?> = emptyMap())
interface ProgressionSource {
    fun read(references: Set<ClaimReference>): ContractResult<ProgressionRead>
    fun save(identity: GameIdentity, command: QuestClaimCommand): ContractResult<Unit>
    fun execute(identity: GameIdentity, command: QuestClaimCommand): ContractResult<QuestClaimSettlement>
    fun cancelSaved(identity: GameIdentity, command: QuestClaimCommand): ContractResult<Unit>
}
data class ProgressionFrame(val screen: ProgressScreenState, val controlsEnabled: Boolean,
    val resolved: Set<ClaimReference> = emptySet(), val retryComplete: Boolean = false)
internal fun progressKey(definition: QuestDefinition, period: QuestPeriod) = ProgressKey(definition.id,
    "${period.cadence.name}|${period.zone.id}|${period.start}")
internal fun progressKey(progress: QuestProgress) = progressKey(progress.definition, progress.period)
