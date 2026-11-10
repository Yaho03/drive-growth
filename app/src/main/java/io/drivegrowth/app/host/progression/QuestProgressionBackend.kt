package io.drivegrowth.app.host.progression

import io.drivegrowth.app.data.QuestLedger
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.quest.*

/** 중앙 정의 supplier와 실제 열린 같은 store를 사용한다. 금융 기능은 원결과 lookup+공통 journal이 함께 있어야 한다. */
fun questProgressionBackend(session: GameSession, definitions: () -> ContractResult<QuestDefinitions>,
    lookup: ((RequestId) -> ContractResult<QuestClaimSettlement?>)? = null,
    journal: ProgressionJournal? = null): ContractResult<ProgressionBackend> {
    if (journal != null && lookup == null) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    val quests = QuestLedger(session.store, session.store::localProfileId, definitions)
    val readOriginal: (RequestId) -> ContractResult<QuestClaimSettlement?> = lookup ?: { id ->
        when (val ids = quests.claimedRequestIds(session.identity.profileId)) {
            is ContractResult.Failure -> ids
            is ContractResult.Success -> if (id in ids.value) ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
                else ContractResult.Success(null)
        }
    }
    return ContractResult.Success(ProgressionBackend(quests, readOriginal, journal))
}
