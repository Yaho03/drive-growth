package io.drivegrowth.app.host.progression

import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.quest.*

/** E b1b4f262의 실제 동일 store capability만 연결한다. 별도 저널/잔액/원문 복제는 없다. */
fun storedQuestProgressionBackend(session: GameSession): ContractResult<ProgressionBackend> = ContractResult.Success(
    ProgressionBackend(session.store.quests, session.store.quests::settlement,
        StoredQuestProgressionJournal(session.identity, session.store.hostCommands)))

private class StoredQuestProgressionJournal(private val identity: GameIdentity,
    private val ledger: HostCommandCheckpointLedger) : ProgressionJournal {
    override fun load(): ContractResult<ProgressionJournalState> = when (val read = ledger.load(identity.profileId)) {
        is ContractResult.Failure -> read
        is ContractResult.Success -> {
            val row = read.value
            val command = row?.command as? HostCheckpointCommand.Quest
            when {
                row == null -> ContractResult.Success(ProgressionJournalState.Clear)
                command == null -> ContractResult.Success(ProgressionJournalState.Foreign)
                row.profileId != identity.profileId || command.value.profileId != identity.profileId || command.value.source != identity.source ->
                    ContractResult.Failure(ContractError.INVALID_INPUT)
                else -> ContractResult.Success(ProgressionJournalState.Quest(command.value,
                    if (row.phase == HostCheckpointPhase.SAVED) ProgressionPhase.SAVED else ProgressionPhase.STARTED))
            }
        }
    }
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
    private fun result(read: ContractResult<HostCheckpointResult>, expected: QuestClaimCommand): ContractResult<QuestClaimSettlement> = when (read) {
        is ContractResult.Failure -> read
        is ContractResult.Success -> {
            val receipt = (read.value as? HostCheckpointResult.Quest)?.value
            if (receipt?.command == expected && expected.profileId == identity.profileId && expected.source == identity.source)
                ContractResult.Success(receipt) else ContractResult.Failure(ContractError.STORAGE_FAILURE)
        }
    }
}
