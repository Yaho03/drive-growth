package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant

/** 실제 공용 helper/factory를 쓰며 표를 시험에서 생성하지 않는다. 제품 Activity/프로세스 시험과 구분한다. */
@Suppress("DEPRECATION")
class HostCheckpointStoreIntegrationTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.SIMULATION
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private lateinit var content: GameContentSnapshot
    private fun open() = SqliteGameChangeStore(context, source, name,
        initializationAuthority = { ContractResult.Success(InitializationAuthority(source, content, FirstGrant.CANDIDATES.toSet())) },
        initialGachaProgress = GachaProgress(Revision(0), 0, 0))
    private fun <T> ok(value: ContractResult<T>): T {
        assertTrue(value.toString(), value is ContractResult.Success)
        return (value as ContractResult.Success).value
    }
    override fun setUp() {
        super.setUp(); name = "checkpoint-public-${System.nanoTime()}.db"
        content = (AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        store = open()
    }
    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }
    private fun raw(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(
        context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun initialized() = ok(FirstGrant(content).choose(store, ok(store.localProfileId()), VehicleId("hyundai_casper")))
    private fun command(car: OwnedVehicle) = HostCheckpointCommand.Garage(content.catalog.contentVersion,
        GameChange(RequestId("host-upgrade"), TransactionKind.GARAGE, source, content.ruleVersion,
            MoneyChange(MoneyDirection.DEBIT, GameMoney(10)),
            VehicleWrite.Update(car.ownedVehicleId, car.revision, 2, VehicleLifecycle.OWNED, 0)))
    private fun rows(): List<String> = buildList {
        raw { db -> for (table in listOf("account", "vehicles", "settlements", "shop", "initialization_profile",
            "initialization_requests", "gacha_progress", "first_gift_requests", "first_gift_markers")) {
            db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { c -> while (c.moveToNext())
                add(table + ":" + (0 until c.columnCount).joinToString("|") { if(c.isNull(it)) "<null>" else c.getString(it) }) }
        } }
    }
    fun testActualHelperFactoryExecutesSavedIdAndReplaysAfterReopen() {
        val profile = ok(store.localProfileId())
        assertNull(ok(store.hostCommands.load(profile)))
        val first = initialized(); val c = command(first.grant.changedVehicle!!)
        val saved = ok(store.hostCommands.save(profile, c))
        assertEquals(HostCheckpointPhase.SAVED, saved.phase)
        // checkpoint 저장은 완료 원장 ID 점유가 아니므로 같은 명령의 실제 확정을 막지 않는다.
        val result = ok(store.hostCommands.execute(profile, c))
        assertEquals(GameMoney(990), ok(store.balance()))
        store.close(); store = open()
        assertEquals(profile, ok(store.localProfileId()))
        assertEquals(HostCheckpointPhase.STARTED, ok(store.hostCommands.load(profile))!!.phase)
        assertEquals(result, ok(store.hostCommands.execute(profile, c)))
        assertEquals(result, ok(store.hostCommands.finish(profile, c)))
        assertNull(ok(store.hostCommands.load(profile)))
        assertEquals(GameMoney(990), ok(store.balance()))
    }
    private fun upgrade(version: Int) {
        val first = initialized(); val profile = ok(store.localProfileId()); val before = rows()
        store.close()
        raw { db ->
            listOf("quest_events","quest_claims","quest_tracking").forEach{db.execSQL("DROP TABLE $it")};db.execSQL("DROP TABLE host_command_checkpoint");stripDeliveryCheckpointFixture(db)
            if (version == 8) { db.execSQL("DROP TABLE first_gift_requests"); db.execSQL("DROP TABLE first_gift_markers") }
            db.version = version
        }
        store = open()
        assertEquals(profile, ok(store.localProfileId()))
        assertEquals(before, rows())
        assertEquals(first, ok(FirstGrant(content).choose(store, profile, VehicleId("hyundai_casper"))))
        assertNull(ok(store.hostCommands.load(profile)))
        val c = command(first.grant.changedVehicle!!)
        ok(store.hostCommands.save(profile, c)); ok(store.hostCommands.execute(profile, c))
        raw { assertEquals(13, it.version) }
    }
    fun testV9MigrationPreservesProfileProgressAndOriginalResults() = upgrade(9)
    fun testV8MigrationAddsGiftAndCheckpointWithoutReplacingProfile() = upgrade(8)
    fun testV9MigrationFailurePreservesVersionAndWholeFile() {
        initialized(); store.close()
        // v9에 새 표가 이미 있으면 이전 충돌이다. 기존 표를 삭제하는 복구를 하지 않는다.
        raw { it.version = 9 }
        val file = context.getDatabasePath(name); val before = file.readBytes()
        store = open()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.balance())
        store.close(); assertTrue(before.contentEquals(file.readBytes()))
        raw { db -> assertEquals(9, db.version); db.version = 13 }
        store = open(); assertEquals(GameMoney(1000), ok(store.balance()))
    }
}
