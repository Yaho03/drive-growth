package io.drivegrowth.app.host.progression

import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.achievement.*
import io.drivegrowth.core.quest.*

/** E b1b4f262의 실제 동일 store capability만 연결한다. 별도 저널/잔액/원문 복제는 없다. */
fun storedQuestProgressionBackend(session: GameSession): ContractResult<ProgressionBackend> =
    storedQuestProgressionBackend(session, null)

internal fun storedQuestProgressionBackend(session: GameSession,
    readRest: (() -> ContractResult<RestDrivingProjection>)?): ContractResult<ProgressionBackend> = ContractResult.Success(
    ProgressionBackend(session.store.quests, session.store.quests::settlement,
        StoredQuestProgressionJournal(session.identity, session.store.hostCommands), readRest,
        readAchievements = if (session.store.achievements.authority == null) null else { -> session.store.achievements.read(session.identity.profileId) },
        lookupAchievement = session.store.achievements::settlement))

/** 같은 세션의 검증된 저장 projection과 원결과를 조회한다. 활성화 여부와 입력 권위는 호스트가 확인한다. */
internal fun readStoredRestProgression(session: GameSession): ContractResult<RestDrivingProjection> =
    session.store.restDriving.state(session.identity.profileId)

private class StoredQuestProgressionJournal(private val identity: GameIdentity,
    private val ledger: HostCommandCheckpointLedger) : ProgressionJournal {
    override fun load(): ContractResult<ProgressionJournalState> = when (val read = ledger.load(identity.profileId)) {
        is ContractResult.Failure -> read
        is ContractResult.Success -> {
            val row = read.value
            if (row == null) ContractResult.Success(ProgressionJournalState.Clear)
            else {
                val phase = if (row.phase == HostCheckpointPhase.SAVED) ProgressionPhase.SAVED else ProgressionPhase.STARTED
                when (val command = row.command) {
                    is HostCheckpointCommand.Quest -> if (matches(row.profileId, command.value.profileId, command.value.source))
                        ContractResult.Success(ProgressionJournalState.Quest(command.value, phase))
                    else ContractResult.Failure(ContractError.INVALID_INPUT)
                    is HostCheckpointCommand.Achievement -> if (matches(row.profileId, command.value.profileId, command.value.source))
                        ContractResult.Success(ProgressionJournalState.Achievement(command.value, phase))
                    else ContractResult.Failure(ContractError.INVALID_INPUT)
                    else -> ContractResult.Success(ProgressionJournalState.Foreign)
                }
            }
        }
    }
    private fun matches(row: ProfileId, profile: ProfileId, source: InputSource): Boolean =
        row == identity.profileId && profile == identity.profileId && source == identity.source

    override fun save(command: QuestClaimCommand): ContractResult<Unit> = when (val saved = ledger.save(identity.profileId, HostCheckpointCommand.Quest(command))) {
        is ContractResult.Failure -> saved
        is ContractResult.Success -> if (saved.value.profileId == identity.profileId && saved.value.command == HostCheckpointCommand.Quest(command))
            ContractResult.Success(Unit) else ContractResult.Failure(ContractError.STORAGE_FAILURE)
    }
    override fun execute(command: QuestClaimCommand): ContractResult<QuestClaimSettlement> = result(
        ledger.execute(identity.profileId, HostCheckpointCommand.Quest(command)), command)
    override fun finish(command: QuestClaimCommand): ContractResult<Unit> = when (val finished = result(
        ledger.finish(identity.profileId, HostCheckpointCommand.Quest(command)), command)) {
        is ContractResult.Failure -> finished
        is ContractResult.Success -> ContractResult.Success(Unit)
    }
    override fun cancelSaved(command: QuestClaimCommand) = ledger.cancelUnstarted(identity.profileId, HostCheckpointCommand.Quest(command))
    override fun save(command: AchievementClaimCommand): ContractResult<Unit> =
        when (val saved = ledger.save(identity.profileId, HostCheckpointCommand.Achievement(command))) {
            is ContractResult.Failure -> saved
            is ContractResult.Success -> if (saved.value.profileId == identity.profileId && saved.value.command == HostCheckpointCommand.Achievement(command))
                ContractResult.Success(Unit) else ContractResult.Failure(ContractError.STORAGE_FAILURE)
        }

    override fun execute(command: AchievementClaimCommand): ContractResult<AchievementClaimSettlement> =
        achievementResult(ledger.execute(identity.profileId, HostCheckpointCommand.Achievement(command)), command)

    override fun finish(command: AchievementClaimCommand): ContractResult<Unit> =
        when (val finished = achievementResult(ledger.finish(identity.profileId, HostCheckpointCommand.Achievement(command)), command)) {
            is ContractResult.Failure -> finished
            is ContractResult.Success -> ContractResult.Success(Unit)
        }

    override fun cancelSaved(command: AchievementClaimCommand) =
        ledger.cancelUnstarted(identity.profileId, HostCheckpointCommand.Achievement(command))

    private fun achievementResult(read: ContractResult<HostCheckpointResult>,
        expected: AchievementClaimCommand): ContractResult<AchievementClaimSettlement> = when (read) {
        is ContractResult.Failure -> read
        is ContractResult.Success -> {
            val receipt = (read.value as? HostCheckpointResult.Achievement)?.value
            if (receipt?.command == expected && expected.profileId == identity.profileId && expected.source == identity.source)
                ContractResult.Success(receipt) else ContractResult.Failure(ContractError.STORAGE_FAILURE)
        }
    }

    private fun result(read: ContractResult<HostCheckpointResult>, expected: QuestClaimCommand): ContractResult<QuestClaimSettlement> = when (read) {
        is ContractResult.Failure -> read
        is ContractResult.Success -> {
            val receipt = (read.value as? HostCheckpointResult.Quest)?.value
            if (receipt?.command == expected && expected.profileId == identity.profileId && expected.source == identity.source)
                ContractResult.Success(receipt) else ContractResult.Failure(ContractError.STORAGE_FAILURE)
        }
    }
}
