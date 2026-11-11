package io.drivegrowth.app.host.progression

import io.drivegrowth.app.data.RestDrivingProjection
import io.drivegrowth.app.host.*
import io.drivegrowth.app.ui.progress.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.quest.*
import java.util.Collections
import java.util.UUID

/** E가 같은 열린 session의 실제 Quest 읽기/claim lookup/단일 checkpoint를 공급한다. */
data class ProgressionBackend(val quests: QuestChangeStore,
    val lookup: (RequestId) -> ContractResult<QuestClaimSettlement?>,
    val journal: ProgressionJournal?,
    val readRest: (() -> ContractResult<RestDrivingProjection>)? = null)

/** worker 전용. 같은 GameSessionFactory를 열고 모든 경로에서 닫는다. 새 지갑/DB/Quest 정의를 만들지 않는다. */
class FactoryProgressionSource(private val games: GameSessionFactory,
    private val backend: (GameSession) -> ContractResult<ProgressionBackend>,
    private val now: () -> Observation<EpochMillis>,
    private val requestId: () -> RequestId = { RequestId("quest/claim/${UUID.randomUUID()}") }) : ProgressionSource {
    constructor(games: GameSessionFactory, now: () -> Observation<EpochMillis>) : this(games, ::storedQuestProgressionBackend, now)
    /** 호출자가 휴식 읽기 허용 여부를 판단한다. 이 포트는 활성화나 정산을 호출하지 않는다. */
    constructor(games: GameSessionFactory, now: () -> Observation<EpochMillis>,
        readRest: ((GameSession) -> ContractResult<RestDrivingProjection>)?) :
        this(games, { session -> storedQuestProgressionBackend(session, readRest?.let { reader -> { reader(session) } }) }, now)

    private val offers = linkedMapOf<ProgressKey, QuestClaimCommand>()
    private fun <T> use(identity: GameIdentity? = null, action: (GameSession, ProgressionBackend) -> ContractResult<T>): ContractResult<T> =
        when (val opened = games.open()) {
            is ContractResult.Failure -> opened
            is ContractResult.Success -> opened.value.use { session ->
                if (identity != null && session.identity != identity) return@use failure(ContractError.INVALID_INPUT)
                when (val access = backend(session)) { is ContractResult.Failure -> access; is ContractResult.Success -> action(session, access.value) }
            }
        }
    override fun read(references: Set<ClaimReference>): ContractResult<ProgressionRead> = use { session, access ->
        val identity = session.identity
        val pending = when (val read = access.journal?.load() ?: ContractResult.Success(ProgressionJournalState.Clear)) {
            is ContractResult.Failure -> return@use read
            is ContractResult.Success -> read.value
        }
        fun withRest(read: ProgressionRead) = ContractResult.Success(read.copy(rest = access.readRest?.invoke()))
        if (pending is ProgressionJournalState.Quest) {
            if (!matches(identity, pending.command)) return@use failure(ContractError.INVALID_INPUT)
            return@use withRest(ProgressionRead(identity, emptyList(), emptyMap(), pending, true))
        }
        val recovered = linkedMapOf<ClaimReference, QuestClaimSettlement?>()
        for (reference in references) {
            if (!reference.requestKey.startsWith("quest/claim/")) return@use failure(ContractError.INVALID_INPUT)
            val receipt = when (val read = access.lookup(RequestId(reference.requestKey))) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
            if (receipt != null && (!validReceipt(identity, receipt.command, receipt) || progressKey(receipt.progress) != reference.key))
                return@use failure(ContractError.STORAGE_FAILURE)
            recovered[reference] = receipt
            offers.entries.removeAll { it.value.requestId.value == reference.requestKey }
        }
        if (recovered.isNotEmpty()) {
            val original = recovered.values.filterNotNull().map { it.progress }.distinctBy(::progressKey)
            return@use withRest(ProgressionRead(identity, Collections.unmodifiableList(original), emptyMap(), pending,
                access.journal != null, Collections.unmodifiableMap(recovered)))
        }
        when (val initialized = initialized(session)) { is ContractResult.Failure -> return@use initialized; else -> Unit }
        val states = when (val read = access.quests.read(identity.profileId, now())) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        if (states.size != 6 || states.map { it.definition.id }.toSet().size != 6 ||
            states.any { it.profileId != identity.profileId || it.source != identity.source }) return@use failure(ContractError.INVALID_INPUT)
        val active = linkedMapOf<ProgressKey, QuestClaimCommand>()
        for (state in states) if (state.status == QuestStatus.CLAIMABLE && state.revision != null && state.count != null) {
            val key = progressKey(state)
            val prior = offers[key]?.takeIf { it.definition == state.definition && it.period == state.period && it.expectedRevision == state.revision }
            val command = prior ?: QuestClaimCommand(requestId(), identity.profileId, identity.source, state.definition, state.period, state.revision!!)
            active[key] = command
        }
        if (active.values.map { it.requestId }.toSet().size != active.size) return@use failure(ContractError.STATE_CONFLICT)
        offers.clear(); offers.putAll(active)
        withRest(ProgressionRead(identity, Collections.unmodifiableList(ArrayList(states)),
            Collections.unmodifiableMap(LinkedHashMap(active)), pending, access.journal != null,
            Collections.unmodifiableMap(recovered)))
    }
    override fun save(identity: GameIdentity, command: QuestClaimCommand): ContractResult<Unit> = use(identity) { session, access ->
        if (!matches(identity, command)) return@use failure(ContractError.INVALID_INPUT)
        val journal = access.journal ?: return@use failure(ContractError.DATA_UNAVAILABLE)
        when (val prior = journal.load()) {
            is ContractResult.Failure -> return@use prior
            is ContractResult.Success -> when (val state = prior.value) {
                ProgressionJournalState.Foreign -> return@use failure(ContractError.STATE_CONFLICT)
                is ProgressionJournalState.Quest -> return@use if (state.command == command) journal.save(command) else failure(ContractError.STATE_CONFLICT)
                ProgressionJournalState.Clear -> Unit
            }
        }
        when (val initialized = initialized(session)) { is ContractResult.Failure -> return@use initialized; else -> Unit }
        val states = when (val read = access.quests.read(identity.profileId, now())) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        val state = states.singleOrNull { progressKey(it) == progressKey(command.definition, command.period) }
            ?: return@use failure(ContractError.STATE_CONFLICT)
        if (state.profileId != identity.profileId || state.source != identity.source || state.definition != command.definition ||
            state.period != command.period || state.revision != command.expectedRevision || state.status != QuestStatus.CLAIMABLE)
            return@use failure(ContractError.STATE_CONFLICT)
        journal.save(command)
    }
    override fun execute(identity: GameIdentity, command: QuestClaimCommand): ContractResult<QuestClaimSettlement> = use(identity) { _, access ->
        if (!matches(identity, command)) return@use failure(ContractError.INVALID_INPUT)
        val journal = access.journal ?: return@use failure(ContractError.DATA_UNAVAILABLE)
        val pending = when (val read = journal.load()) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        if (pending !is ProgressionJournalState.Quest || pending.command != command) return@use failure(ContractError.STATE_CONFLICT)
        val receipt = when (val executed = journal.execute(command)) { is ContractResult.Failure -> return@use executed; is ContractResult.Success -> executed.value }
        if (!validReceipt(identity, command, receipt)) return@use failure(ContractError.STORAGE_FAILURE)
        when (val stored = access.lookup(command.requestId)) {
            is ContractResult.Failure -> return@use stored
            is ContractResult.Success -> if (stored.value != receipt) return@use failure(ContractError.STORAGE_FAILURE)
        }
        when (val finished = journal.finish(command)) { is ContractResult.Failure -> finished; is ContractResult.Success -> ContractResult.Success(receipt) }
    }
    override fun cancelSaved(identity: GameIdentity, command: QuestClaimCommand): ContractResult<Unit> = use(identity) { _, access ->
        if (!matches(identity, command)) return@use failure(ContractError.INVALID_INPUT)
        val journal = access.journal ?: return@use failure(ContractError.DATA_UNAVAILABLE)
        val pending = when (val read = journal.load()) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        if (pending != ProgressionJournalState.Quest(command, ProgressionPhase.SAVED)) return@use failure(ContractError.STATE_CONFLICT)
        when (val receipt = access.lookup(command.requestId)) {
            is ContractResult.Failure -> return@use receipt
            is ContractResult.Success -> if (receipt.value != null) return@use failure(ContractError.STATE_CONFLICT)
        }
        journal.cancelSaved(command)
    }
    private fun initialized(session: GameSession): ContractResult<Unit> = when (val read = session.store.readInitialization(session.identity.profileId)) {
        is ContractResult.Failure -> read
        is ContractResult.Success -> if (read.value.status is ProfileInitializationStatus.Existing) ContractResult.Success(Unit)
            else failure(ContractError.DATA_UNAVAILABLE)
    }
    private fun matches(identity: GameIdentity, command: QuestClaimCommand) = command.profileId == identity.profileId && command.source == identity.source
    private fun validReceipt(identity: GameIdentity, command: QuestClaimCommand, receipt: QuestClaimSettlement) =
        matches(identity, command) && receipt.command == command && receipt.progress.profileId == identity.profileId &&
            receipt.progress.source == identity.source && receipt.progress.definition == command.definition && receipt.progress.period == command.period &&
            receipt.progress.status == QuestStatus.CLAIMED && receipt.progress.revision == command.expectedRevision &&
            receipt.settlement.transaction.kind == TransactionKind.GARAGE && receipt.settlement.transaction.ruleVersion == command.definition.version &&
            receipt.settlement.transaction.requestId == command.requestId &&
            receipt.settlement.transaction.source == identity.source && receipt.settlement.transaction.money == MoneyChange(MoneyDirection.CREDIT, command.definition.reward)
    private fun failure(error: ContractError) = ContractResult.Failure(error)
}
