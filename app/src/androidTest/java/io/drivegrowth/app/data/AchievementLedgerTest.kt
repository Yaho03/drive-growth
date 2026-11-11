package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.achievement.*
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.UUID

@Suppress("DEPRECATION")
class AchievementLedgerTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.SIMULATION
    private val model = VehicleId("hyundai_casper")
    private val secondModel = VehicleId("hyundai_avante")
    private val version = RuleVersion("achievement-device-test-v1")
    private val catalog = VehicleCatalogSnapshot(ContentVersion("achievement-test-catalog"),
        listOf(VehicleDefinition(model, "캐스퍼"), VehicleDefinition(secondModel, "아반떼")))
    private var now = 10_000_000L
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private val profile get() = ok(store.localProfileId())
    private var definitions = definitions(version)

    override fun setUp() {
        super.setUp()
        name = "achievement-fixture-${UUID.randomUUID()}.db"
        store = open()
        profile
    }

    override fun tearDown() {
        store.close()
        context.deleteDatabase(name)
        super.tearDown()
    }

    private fun definitions(rule: RuleVersion) = AchievementMetric.entries.associateWith { metric ->
        val goal = when (metric) {
            AchievementMetric.OWNED, AchievementMetric.SHOP -> 2
            AchievementMetric.CUSTOMERS -> 1
            AchievementMetric.COLLECTION -> 25
        }
        AchievementDefinition(metric, rule, goal, GameMoney(100), true)
    }

    private fun open(tracking: Boolean = true) = SqliteGameChangeStore(context, source, name, clock = { now }).also { opened ->
        if (tracking) opened.achievements.authority = { ContractResult.Success(AchievementAuthority(definitions, catalog)) }
    }

    private fun reopen(tracking: Boolean = true) {
        store.close()
        store = open(tracking)
    }

    private fun raw(operation: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(operation)

    private fun <T> ok(value: ContractResult<T>): T {
        assertTrue(value.toString(), value is ContractResult.Success)
        return (value as ContractResult.Success).value
    }

    private fun add(index: Int, modelId: VehicleId = model) = store.commitGameChange(GameChange(
        RequestId("add-$index"), TransactionKind.GARAGE, source, version, null,
        VehicleWrite.Add(OwnedVehicle(OwnedVehicleId("car-$index"), modelId, 1, Revision(0),
            VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.GACHA))))

    private fun sell(index: Int) = store.commitGameChange(GameChange(RequestId("sell-$index"), TransactionKind.GARAGE,
        source, version, null, VehicleWrite.Update(OwnedVehicleId("car-$index"), Revision(0), 1, VehicleLifecycle.SOLD, 0)))

    private fun state(metric: AchievementMetric) = ok(store.achievements.read(profile)).single { it.definition.metric == metric }
    private fun command(metric: AchievementMetric, key: String) =
        AchievementClaimCommand(RequestId(ACHIEVEMENT_CLAIM_PREFIX + key), requireNotNull(state(metric).milestone))

    fun testOwnedAttainmentSurvivesSaleAndDefinitionChangeWithOneDurableClaim() {
        ok(add(1)); ok(add(2, secondModel)); ok(sell(2))
        assertEquals(1, ok(store.listOwnedVehicles()).size)
        assertEquals(AchievementStatus.CLAIMABLE, state(AchievementMetric.OWNED).status)
        assertEquals(AchievementMeasure(2, 8, catalog.contentVersion), state(AchievementMetric.COLLECTION).milestone?.measure)

        val c = command(AchievementMetric.OWNED, "owned")
        val checkpoint = HostCheckpointCommand.Achievement(c)
        ok(store.hostCommands.save(profile, checkpoint))
        val original = (ok(store.hostCommands.execute(profile, checkpoint)) as HostCheckpointResult.Achievement).value
        assertEquals(GameMoney(100), original.settlement.balance)
        assertEquals(HostCheckpointPhase.STARTED, ok(store.hostCommands.load(profile))?.phase)

        now += 90L * 24 * 60 * 60 * 1000
        definitions = definitions(RuleVersion("achievement-device-test-v2")).mapValues { (_, d) -> d.copy(reward = GameMoney(500)) }
        reopen()
        assertEquals(HostCheckpointResult.Achievement(original), ok(store.hostCommands.execute(profile, checkpoint)))
        assertEquals(HostCheckpointResult.Achievement(original), ok(store.hostCommands.finish(profile, checkpoint)))
        assertNull(ok(store.hostCommands.load(profile)))
        assertEquals(original, ok(store.achievements.claim(c)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.achievements.claim(c.copy(requestId = RequestId(ACHIEVEMENT_CLAIM_PREFIX + "second"))))
        val changed = c.copy(milestone = c.milestone.copy(definition = c.milestone.definition.copy(reward = GameMoney(500))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.achievements.claim(changed))
        assertEquals(GameMoney(100), ok(store.balance()))
        assertEquals(AchievementStatus.CLAIMED, state(AchievementMetric.OWNED).status)
        assertTrue(ok(SettlementHistoryReader(store).read()).entries.any { it.settlement == original.settlement })

        val content = (AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        val earned = ok(RankingReadSource(store, source, content).read(EpochMillis(now)))
            .metrics.getValue(io.drivegrowth.app.host.ranking.RankingMetric.EARNED)
        assertTrue(earned is io.drivegrowth.app.host.ranking.RankingValue.Empty)
    }

    fun testMilestoneAndClaimWritesRollbackTheirOriginalTransactions() {
        ok(add(1))
        raw { it.execSQL("CREATE TRIGGER ignore_achievement BEFORE INSERT ON achievements BEGIN SELECT RAISE(IGNORE); END") }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), add(2))
        assertEquals(1, ok(store.listOwnedVehicles()).size)
        assertEquals(1, ok(store.listUnviewedSettlements()).size)
        raw { it.execSQL("DROP TRIGGER ignore_achievement") }
        ok(add(2))
        val c = command(AchievementMetric.OWNED, "retry")
        val checkpoint = HostCheckpointCommand.Achievement(c)
        ok(store.hostCommands.save(profile, checkpoint))
        for ((table, operation) in listOf("account" to "UPDATE", "settlements" to "INSERT", "achievements" to "UPDATE")) {
            raw { it.execSQL("CREATE TRIGGER ignore_claim BEFORE $operation ON $table BEGIN SELECT RAISE(IGNORE); END") }
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.hostCommands.execute(profile, checkpoint))
            assertEquals(GameMoney(0), ok(store.balance()))
            assertNull(ok(store.achievements.settlement(c.requestId)))
            assertEquals(HostCheckpointPhase.SAVED, ok(store.hostCommands.load(profile))?.phase)
            raw { it.execSQL("DROP TRIGGER ignore_claim") }
        }
        ok(store.hostCommands.execute(profile, checkpoint))
        ok(store.hostCommands.finish(profile, checkpoint))
        assertEquals(GameMoney(100), ok(store.balance()))
    }

    fun testActualRentalAndExpansionRecordAttainmentWithoutCountingVisitsOrDuplicateSettlement() {
        ok(add(1))
        val id = RequestId("one-completed-rental")
        ok(store.rental.assign(RentalRequest(id, model, 1), catalog, mapOf(model to RentalCategory.SEDAN),
            mapOf(model to GameMoney(100)), RentalTerms(version, SimulatedCustomer(30, 36), 99, EpochMillis(0), 15,
                GameMoney(0), RoundingMode.DOWN)))
        assertEquals(AchievementMeasure(0), state(AchievementMetric.CUSTOMERS).measure)
        ok(store.rental.account(id, EpochMillis(0), EpochMillis(3_600_000), null))
        val done = ok(store.rental.settle(id))
        assertEquals(done, ok(store.rental.settle(id)))
        val offer = ShopExpansion(version, true, 1, 3, 5, 1, GameMoney(10))
        ok(store.rental.expand(RequestId("expand-once"), offer))
        reopen()
        assertEquals(AchievementMeasure(1), state(AchievementMetric.CUSTOMERS).milestone?.measure)
        assertEquals(AchievementMeasure(2), state(AchievementMetric.SHOP).milestone?.measure)
    }

    fun testDefinedPreDisposalSnapshotCanSupplyFirstAttainmentAfterUpgrade() {
        reopen(false)
        ok(add(1)); ok(add(2))
        store.close()
        raw { db -> db.execSQL("DROP TABLE achievements"); db.version = 18 }
        store = open()
        ok(sell(2))
        assertEquals(1, ok(store.listOwnedVehicles()).size)
        assertEquals(AchievementMeasure(2), state(AchievementMetric.OWNED).milestone?.measure)
    }

    fun testCorruptClaimMarkerCannotRecreditThroughAnotherRequest() {
        ok(add(1)); ok(add(2))
        val c = command(AchievementMetric.OWNED, "first")
        ok(store.achievements.claim(c))
        raw { it.execSQL("UPDATE achievements SET request_id=NULL,balance_before=NULL WHERE id='owned-vehicles'") }
        val other = c.copy(requestId = RequestId(ACHIEVEMENT_CLAIM_PREFIX + "other"))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.achievements.claim(other))
        assertEquals(GameMoney(100), ok(store.balance()))
    }

    fun testDefinitionFailureDoesNotReturnPartialSuccessOrEraseOriginalReceipt() {
        ok(add(1)); ok(add(2))
        val c = command(AchievementMetric.OWNED, "known")
        val original = ok(store.achievements.claim(c))
        store.achievements.authority = { ContractResult.Failure(ContractError.INVALID_INPUT) }
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), store.achievements.read(profile))
        assertEquals(original, ok(store.achievements.settlement(c.requestId)))
        assertEquals(original, ok(store.achievements.claim(c)))
        assertEquals(GameMoney(100), ok(store.balance()))
    }

    fun testV18UpgradeDoesNotInferUnobservedPastOwnedThreshold() {
        reopen(false)
        ok(add(1)); ok(add(2, secondModel)); ok(sell(2))
        val previous = ok(store.listVehicleHistory())
        store.close()
        raw { db -> db.execSQL("DROP TABLE achievements"); db.version = 18 }
        store = open()
        assertEquals(previous, ok(store.listVehicleHistory()))
        assertEquals(AchievementStatus.IN_PROGRESS, state(AchievementMetric.OWNED).status)
        assertEquals(AchievementMeasure(1), state(AchievementMetric.OWNED).measure)
        assertEquals(AchievementStatus.CLAIMABLE, state(AchievementMetric.COLLECTION).status)
        raw { assertEquals(19, it.version) }
    }
}
