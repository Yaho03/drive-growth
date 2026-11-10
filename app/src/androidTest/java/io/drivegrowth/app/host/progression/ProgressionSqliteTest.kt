package io.drivegrowth.app.host.progression

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.ui.progress.ClaimReference
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.quest.*
import java.time.ZoneId
import java.util.UUID

/** E schema12/실제 단일 checkpoint와 중앙 trial을 소비하는 격리DB 시험. 실행은 A의 별도 슬롯. */
@Suppress("DEPRECATION")
class ProgressionSqliteTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var names: Map<InputSource, String>
    private lateinit var games: GameSessionFactory
    private var unavailable = false
    private val zone = ZoneId.of("UTC") // 명시 시험 입력. 제품 전역 시간대 정책이 아니다.
    private var clockUnknown = false
    private fun <T> ok(result: ContractResult<T>): T { assertTrue(result.toString(), result is ContractResult.Success); return (result as ContractResult.Success).value }
    private fun definitions(): ContractResult<QuestDefinitions> = if (unavailable) ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        else when (val read = AssetTrialDefinitionProvider(context.assets).load()) {
            is ContractResult.Failure -> read
            is ContractResult.Success -> read.value.questDefinitions(zone)
        }
    private fun source() = FactoryProgressionSource(games) { if (clockUnknown) Observation.Missing(MissingReason.NO_DATA)
        else Observation.Available(EpochMillis(System.currentTimeMillis())) }
    override fun setUp() {
        super.setUp(); val prefix = "progression-${UUID.randomUUID()}"
        names = InputSource.entries.associateWith { "$prefix-${it.name}.db" }
        // C/B 소유 factory-provider.patch를 검증 사본에 적용하는 의존 계약.
        games = GameSessionFactory(context, names, InputSource.SIMULATION, ::definitions)
        val onboarding = StoredOnboardingHostSource(games); val new = ok(onboarding.read())
        ok(onboarding.choose(new.identity, FirstGrant.CANDIDATES.first()))
    }
    override fun tearDown() { names.values.forEach(context::deleteDatabase); super.tearDown() }
    private fun balance() = ok(games.open()).use { ok(it.store.balance()) }
    private fun makeClaimable(): ProgressionRead {
        ok(games.open()).use { session ->
            val receipt = ok(session.store.commitGameChange(GameChange(RequestId("view-${UUID.randomUUID()}"), TransactionKind.GARAGE,
                session.identity.source, RuleVersion("source-fixture"))))
            ok(session.store.markViewed(receipt.settlementId)) // E의 실제0→1 원자 hook 소비. 수동 사건 추가 없음.
        }
        return ok(source().read(emptySet()))
    }
    private fun offer(read: ProgressionRead) = read.offers.values.first { it.definition.id == "daily-result-viewed" }
    private fun raw(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(
        context.getDatabasePath(names.getValue(InputSource.SIMULATION)).path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    fun testSixQuestsReadSameFactoryProfileAndMoneyUnchangedAcrossReopen() {
        val before = balance(); val first = ok(source().read(emptySet()))
        assertEquals(InputSource.SIMULATION, first.identity.source); assertEquals(6, first.quests.size)
        assertTrue(first.quests.all { it.profileId == first.identity.profileId && it.count == 0 && it.status == QuestStatus.IN_PROGRESS })
        assertTrue(first.journalSupported)
        val again = ok(source().read(emptySet())); assertEquals(first.identity, again.identity); assertEquals(first.quests, again.quests)
        assertEquals(before, balance())
    }
    fun testPartialPeriodCoverageRemainsUnknownInsteadOfZero() {
        raw { it.execSQL("UPDATE quest_tracking SET complete_since=?", arrayOf(System.currentTimeMillis() + 1)) }
        val read = ok(source().read(emptySet()))
        assertTrue(read.quests.all { it.status == QuestStatus.UNKNOWN && it.count == null && it.revision == null })
    }
    fun testMissingCentralDefinitionFailsWithoutFallbackGoalsOrRewards() {
        unavailable = true; assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), source().read(emptySet()))
    }
    fun testWrongProfileAndIncompleteQuestCannotSaveOrExecute() {
        val current = ok(source().read(emptySet())); val p = current.quests.first()
        val command = QuestClaimCommand(RequestId("quest/claim/incomplete"), current.identity.profileId, current.identity.source,
            p.definition, p.period, Revision(0))
        val wrong = GameIdentity(ProfileId("different"), current.identity.source)
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), source().save(wrong, command))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), source().execute(wrong, command))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), source().save(current.identity, command))
    }
    fun testActualSaveExecuteFinishAndDuplicateDoNotDoubleCredit() {
        val src = source(); val read = makeClaimable(); val command = offer(read); val before = balance()
        ok(src.save(read.identity, command)); val receipt = ok(src.execute(read.identity, command))
        assertEquals(GameMoney(before.units + command.definition.reward.units), receipt.settlement.balance)
        assertEquals(receipt.settlement.balance, balance())
        val reference = ClaimReference(progressKey(receipt.progress), 99, command.requestId.value)
        val reopened = ok(source().read(setOf(reference))); assertEquals(receipt, reopened.resolved[reference])
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), source().save(read.identity, command))
        assertEquals(receipt.settlement.balance, balance())
        ok(games.open()).use { assertNull(ok(it.store.hostCommands.load(read.identity.profileId))) }
    }
    fun testSavedWholeCommandLoadsBeforeMissingDefinitionsAndCanBeExplicitlyCancelled() {
        val read = makeClaimable(); val command = offer(read); val src = source(); val before = balance()
        ok(src.save(read.identity, command)); unavailable = true
        val pending = ok(source().read(emptySet())).journal as ProgressionJournalState.Quest
        assertEquals(command, pending.command); assertEquals(ProgressionPhase.SAVED, pending.phase)
        ok(src.cancelSaved(read.identity, command)); assertEquals(before, balance())
        ok(games.open()).use { assertNull(ok(it.store.hostCommands.load(read.identity.profileId))) }
    }
    fun testStartedOriginalResultReplaysAfterLostResponseWithoutCurrentPolicy() {
        val read = makeClaimable(); val command = offer(read); ok(source().save(read.identity, command))
        val first = ok(games.open()).use { session ->
            (ok(session.store.hostCommands.execute(read.identity.profileId, HostCheckpointCommand.Quest(command))) as HostCheckpointResult.Quest).value
        } // finish 전 응답을 잃은 실제 STARTED 상태.
        unavailable = true; clockUnknown = true
        val pending = ok(source().read(emptySet())).journal as ProgressionJournalState.Quest
        assertEquals(command, pending.command); assertEquals(ProgressionPhase.STARTED, pending.phase)
        assertEquals(first, ok(source().execute(read.identity, command)))
        assertEquals(first.settlement.balance, balance())
    }
    fun testForeignSharedCheckpointIsNeverOverwrittenOrCancelled() {
        val read = makeClaimable(); val command = offer(read)
        val other = ok(games.open()).use { session ->
            val car = ok(session.store.listOwnedVehicles()).first(); val content = ok(session.content)
            val typed = HostCheckpointCommand.Garage(content.catalog.contentVersion, GameChange(RequestId("other-feature"), TransactionKind.GARAGE,
                session.identity.source, content.ruleVersion, vehicle = VehicleWrite.Update(car.ownedVehicleId, car.revision, car.level, car.lifecycle, car.levelProgressPercent)))
            ok(session.store.hostCommands.save(read.identity.profileId, typed)); typed
        }
        assertEquals(ProgressionJournalState.Foreign, ok(source().read(emptySet())).journal)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), source().save(read.identity, command))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), source().cancelSaved(read.identity, command))
        ok(games.open()).use { assertEquals(other, ok(it.store.hostCommands.load(read.identity.profileId))!!.command) }
    }
    fun testRecordedClaimLookupPrecedesMissingDefinitionsAndDamagedCurrentBalance() {
        val read = makeClaimable(); val command = offer(read); ok(source().save(read.identity, command)); val original = ok(source().execute(read.identity, command))
        raw { it.execSQL("UPDATE account SET balance=1.5") }; unavailable = true; clockUnknown = true
        val file = context.getDatabasePath(names.getValue(InputSource.SIMULATION)); val bytes = file.readBytes()
        val reference = ClaimReference(progressKey(original.progress), 100, command.requestId.value)
        val recovered = ok(source().read(setOf(reference)))
        assertEquals(original, recovered.resolved[reference]); assertEquals(listOf(original.progress), recovered.quests)
        assertTrue(bytes.contentEquals(file.readBytes()))
    }
    fun testUnknownClockFailsWithoutFabricatingCurrentPeriodOrProgress() {
        clockUnknown = true
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), source().read(emptySet()))
    }
    fun testStartedWithoutOriginalRowsNeverCreatesFreshClaimOrClearsPending() {
        val read = makeClaimable(); val command = offer(read); ok(source().save(read.identity, command))
        ok(games.open()).use { ok(it.store.hostCommands.execute(read.identity.profileId, HostCheckpointCommand.Quest(command))) }
        raw { it.execSQL("DELETE FROM quest_claims"); it.execSQL("DELETE FROM settlements WHERE request_id=?", arrayOf(command.requestId.value)) }
        val file = context.getDatabasePath(names.getValue(InputSource.SIMULATION)); val before = file.readBytes()
        val src = source(); val pending = ok(src.read(emptySet())).journal as ProgressionJournalState.Quest
        assertEquals(ProgressionPhase.STARTED, pending.phase); assertEquals(command, pending.command)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), src.execute(read.identity, command))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), src.cancelSaved(read.identity, command))
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(pending, ok(source().read(emptySet())).journal)
    }

}
