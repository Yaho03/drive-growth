package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class SqliteGameChangeStoreTest : InstrumentationTestCase() {
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private val context get() = instrumentation.targetContext
    override fun setUp() {
        super.setUp()
        name = "ledger-test-${System.nanoTime()}.db"
        store = open()
    }
    override fun tearDown() {
        store.close()
        context.deleteDatabase(name)
        super.tearDown()
    }
    private fun open() = SqliteGameChangeStore(context, InputSource.SIMULATION, name) { 123456L }
    private fun credit(id: String, amount: Long = 100) = GameChange(RequestId(id), TransactionKind.RENTAL,
        InputSource.SIMULATION, RuleVersion("test-v1"), MoneyChange(MoneyDirection.CREDIT, GameMoney(amount)))
    private fun vehicle(id: String = "v1") = OwnedVehicle(OwnedVehicleId(id), VehicleId("car"), 1, Revision(0), VehicleLifecycle.OWNED,
        Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
    private fun success(result: ContractResult<Settlement>) = (result as ContractResult.Success).value
    private fun failure(error: ContractError) = ContractResult.Failure(error)

    fun testRetryAndReopenReturnOriginalSettlementAndSingleCredit() {
        val command = credit("r1")
        val original = success(store.commitGameChange(command))
        assertEquals(GameMoney(100), original.balance)
        assertEquals(ContractResult.Success(original), store.commitGameChange(command))
        store.close(); store = open()
        assertEquals(ContractResult.Success(original), store.commitGameChange(command))
        assertEquals(failure(ContractError.STATE_CONFLICT), store.commitGameChange(credit("r1", 200)))
        assertEquals(ContractResult.Success(GameMoney(100)), store.balance())
        assertEquals(1, (store.listUnviewedSettlements() as ContractResult.Success).value.size)
    }

    fun testRetryComparesKindRuleMoneyAndVehicle() {
        val command = credit("r1").copy(vehicle = VehicleWrite.Add(vehicle()))
        success(store.commitGameChange(command))
        val changed = listOf(command.copy(kind = TransactionKind.GARAGE), command.copy(ruleVersion = RuleVersion("other")),
            command.copy(money = null), command.copy(vehicle = VehicleWrite.Add(vehicle("v2"))))
        changed.forEach { assertEquals(failure(ContractError.STATE_CONFLICT), store.commitGameChange(it)) }
    }

    fun testVehicleAcquisitionAndProgressSurviveReopenQueriesAndReplay() {
        val vehicles = listOf(vehicle(),
            vehicle("gacha").copy(rarity = Rarity.RARE, levelProgressPercent = 45,
                acquisitionCost = GameMoney(350), acquisitionSource = VehicleAcquisitionSource.GACHA),
            vehicle("delivery").copy(rarity = Rarity.RARE, acquisitionCost = GameMoney(900),
                acquisitionSource = VehicleAcquisitionSource.PURCHASE_DELIVERY))
        val commands = vehicles.map { credit("add-${it.ownedVehicleId.value}").copy(vehicle = VehicleWrite.Add(it)) }
        val saved = commands.map { success(store.commitGameChange(it)) }
        assertEquals(vehicles, saved.map { it.changedVehicle })
        store.close(); store = open()
        assertEquals(vehicles.sortedBy { it.ownedVehicleId.value }, (store.listOwnedVehicles() as ContractResult.Success).value)
        assertEquals(saved, (store.listUnviewedSettlements() as ContractResult.Success).value)
        commands.zip(saved).forEach { (command, result) ->
            assertEquals(ContractResult.Success(result), store.commitGameChange(command))
        }
    }

    fun testRetryComparesEveryAddedVehicleField() {
        val original = vehicle().copy(rarity = Rarity.RARE, acquisitionCost = GameMoney(350),
            acquisitionSource = VehicleAcquisitionSource.GACHA)
        val command = credit("add").copy(vehicle = VehicleWrite.Add(original))
        val saved = success(store.commitGameChange(command))
        val changed = listOf(original.copy(rarity = Rarity.COMMON), original.copy(levelProgressPercent = 1),
            original.copy(acquisitionCost = GameMoney(351)), original.copy(acquisitionSource = VehicleAcquisitionSource.PURCHASE_DELIVERY))
        changed.forEach {
            assertEquals(failure(ContractError.STATE_CONFLICT), store.commitGameChange(command.copy(vehicle = VehicleWrite.Add(it))))
        }
        assertEquals(ContractResult.Success(saved), store.commitGameChange(command))
        assertEquals(listOf(original), (store.listOwnedVehicles() as ContractResult.Success).value)
        assertEquals(ContractResult.Success(GameMoney(100)), store.balance())
    }

    fun testUpdateProgressPreservesAcquisitionAndParticipatesInRetryComparison() {
        val original = vehicle().copy(rarity = Rarity.RARE, levelProgressPercent = 20,
            acquisitionCost = GameMoney(350), acquisitionSource = VehicleAcquisitionSource.GACHA)
        success(store.commitGameChange(credit("add").copy(vehicle = VehicleWrite.Add(original))))
        val update = VehicleWrite.Update(original.ownedVehicleId, Revision(0), 2, VehicleLifecycle.OWNED, 55)
        val command = credit("grow").copy(vehicle = update)
        val saved = success(store.commitGameChange(command))
        val expected = original.copy(level = 2, revision = Revision(1), levelProgressPercent = 55)
        assertEquals(expected, saved.changedVehicle)
        assertEquals(failure(ContractError.STATE_CONFLICT), store.commitGameChange(command.copy(vehicle = update.copy(levelProgressPercent = 56))))
        store.close(); store = open()
        assertEquals(listOf(expected), (store.listOwnedVehicles() as ContractResult.Success).value)
        assertEquals(ContractResult.Success(saved), store.commitGameChange(command))
    }

    fun testDisposalRejectsGrowthChangesAndPreservesAcquisition() {
        val original = vehicle().copy(rarity = Rarity.RARE, levelProgressPercent = 20,
            acquisitionCost = GameMoney(350), acquisitionSource = VehicleAcquisitionSource.GACHA)
        success(store.commitGameChange(credit("add").copy(vehicle = VehicleWrite.Add(original))))
        listOf(VehicleLifecycle.SOLD, VehicleLifecycle.SCRAPPED, VehicleLifecycle.CONSUMED).forEach { lifecycle ->
            val write = VehicleWrite.Update(original.ownedVehicleId, Revision(0), 1, lifecycle, 20)
            listOf(write.copy(level = 2), write.copy(levelProgressPercent = 21)).forEachIndexed { index, invalid ->
                assertEquals(failure(ContractError.INVALID_INPUT), store.commitGameChange(
                    credit("invalid-${lifecycle.name}-$index").copy(vehicle = invalid)))
            }
        }
        assertEquals(listOf(original), (store.listOwnedVehicles() as ContractResult.Success).value)
        assertEquals(ContractResult.Success(GameMoney(100)), store.balance())
        assertEquals(1, (store.listUnviewedSettlements() as ContractResult.Success).value.size)
        val sold = credit("sold").copy(vehicle = VehicleWrite.Update(original.ownedVehicleId, Revision(0), 1, VehicleLifecycle.SOLD, 20))
        val saved = success(store.commitGameChange(sold))
        assertEquals(original.copy(revision = Revision(1), lifecycle = VehicleLifecycle.SOLD), saved.changedVehicle)
        store.close(); store = open()
        assertEquals(ContractResult.Success(saved), store.commitGameChange(sold))
    }

    fun testLegacyVehicleCommandsConflictWhileMoneyOnlyCommandReplaysWithoutChangingDatabase() {
        val original = vehicle().copy(rarity = Rarity.RARE, acquisitionCost = GameMoney(350),
            acquisitionSource = VehicleAcquisitionSource.GACHA)
        val add = credit("legacy-add").copy(vehicle = VehicleWrite.Add(original))
        val grow = credit("legacy-grow").copy(vehicle = VehicleWrite.Update(
            original.ownedVehicleId, Revision(0), 2, VehicleLifecycle.OWNED, 45))
        val money = credit("legacy-money").copy(ruleVersion = RuleVersion("rental-contract-v0"))
        success(store.commitGameChange(add))
        val grown = success(store.commitGameChange(grow))
        val paid = success(store.commitGameChange(money))
        store.close()
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            listOf(add, grow).forEach { change ->
                val command = db.rawQuery("SELECT command FROM settlements WHERE request_id = ?",
                    arrayOf(change.requestId.value)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    JSONObject(cursor.getString(0))
                }
                val write = command.getJSONObject("write")
                if (change.vehicle is VehicleWrite.Add) {
                    val jsonVehicle = write.getJSONObject("vehicle")
                    listOf("rarity", "levelProgressPercent", "acquisitionCost", "acquisitionSource").forEach { jsonVehicle.remove(it) }
                } else {
                    write.remove("levelProgressPercent")
                }
                assertEquals(1, db.update("settlements", ContentValues().apply { put("command", command.toString()) },
                    "request_id = ?", arrayOf(change.requestId.value)))
            }
        }
        val before = file.readBytes()
        store = open()
        assertEquals(failure(ContractError.STATE_CONFLICT), store.commitGameChange(add))
        assertEquals(failure(ContractError.STATE_CONFLICT), store.commitGameChange(grow))
        assertEquals(ContractResult.Success(paid), store.commitGameChange(money))
        assertEquals(ContractResult.Success(GameMoney(300)), store.balance())
        assertEquals(listOf(grown.changedVehicle), (store.listOwnedVehicles() as ContractResult.Success).value)
        assertEquals(3, (store.listUnviewedSettlements() as ContractResult.Success).value.size)
        store.close()
        assertTrue(before.contentEquals(file.readBytes()))
        store = open()
    }

    fun testPastAddAndGrowthReplayTheirSnapshotsAfterDisposalAndReopen() {
        val original = vehicle().copy(rarity = Rarity.RARE, acquisitionCost = GameMoney(350),
            acquisitionSource = VehicleAcquisitionSource.GACHA)
        val add = credit("add").copy(vehicle = VehicleWrite.Add(original))
        val grow = credit("grow").copy(vehicle = VehicleWrite.Update(
            original.ownedVehicleId, Revision(0), 2, VehicleLifecycle.OWNED, 45))
        val sell = credit("sell").copy(vehicle = VehicleWrite.Update(
            original.ownedVehicleId, Revision(1), 2, VehicleLifecycle.SOLD, 45))
        val added = success(store.commitGameChange(add))
        val grown = success(store.commitGameChange(grow))
        val sold = success(store.commitGameChange(sell))
        assertEquals(original, added.changedVehicle)
        assertEquals(original.copy(level = 2, revision = Revision(1), levelProgressPercent = 45), grown.changedVehicle)
        assertEquals(original.copy(level = 2, revision = Revision(2), lifecycle = VehicleLifecycle.SOLD,
            levelProgressPercent = 45), sold.changedVehicle)
        store.close(); store = open()
        assertEquals(ContractResult.Success(added), store.commitGameChange(add))
        assertEquals(ContractResult.Success(grown), store.commitGameChange(grow))
        assertEquals(ContractResult.Success(sold), store.commitGameChange(sell))
        assertEquals(emptyList<OwnedVehicle>(), (store.listOwnedVehicles() as ContractResult.Success).value)
        assertEquals(listOf(added, grown, sold), (store.listUnviewedSettlements() as ContractResult.Success).value)
        assertEquals(ContractResult.Success(GameMoney(300)), store.balance())
    }

    fun testMissingVehicleFieldsFailWithoutChangingLegacyRecord() {
        assertMissingFieldsPreserveDatabase("vehicles", "payload")
    }

    fun testMissingSettlementVehicleFieldsFailWithoutChangingLegacyRecord() {
        assertMissingFieldsPreserveDatabase("settlements", "result")
    }

    private fun assertMissingFieldsPreserveDatabase(table: String, column: String) {
        val command = credit("add").copy(vehicle = VehicleWrite.Add(vehicle()))
        success(store.commitGameChange(command))
        store.close()
        val file = context.getDatabasePath(name)
        val original = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("SELECT $column FROM $table", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
        }
        listOf("rarity", "levelProgressPercent", "acquisitionCost", "acquisitionSource").forEach { field ->
            val legacy = JSONObject(original)
            val jsonVehicle = if (table == "vehicles") legacy else legacy.getJSONObject("vehicle")
            jsonVehicle.remove(field)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                assertEquals(1, db.update(table, ContentValues().apply { put(column, legacy.toString()) }, null, null))
            }
            val before = file.readBytes()
            store = open()
            if (table == "vehicles") {
                assertEquals(failure(ContractError.STORAGE_FAILURE), store.listOwnedVehicles())
                assertEquals(LedgerFailureReason.INVALID_RECORD, store.lastStorageFailure)
                assertEquals(failure(ContractError.STORAGE_FAILURE), store.commitGameChange(credit("update").copy(
                    vehicle = VehicleWrite.Update(OwnedVehicleId("v1"), Revision(0), 2, VehicleLifecycle.OWNED, 0))))
            } else {
                assertEquals(failure(ContractError.STORAGE_FAILURE), store.listUnviewedSettlements())
                assertEquals(LedgerFailureReason.INVALID_RECORD, store.lastStorageFailure)
                assertEquals(failure(ContractError.STORAGE_FAILURE), store.commitGameChange(command))
            }
            assertEquals(LedgerFailureReason.INVALID_RECORD, store.lastStorageFailure)
            store.close()
            assertTrue("누락 필드 $field 원본 보존", before.contentEquals(file.readBytes()))
        }
        store = open()
    }

    fun testReadAndRepeatedAcknowledgementNeverPayAgain() {
        val saved = success(store.commitGameChange(credit("r1")))
        repeat(3) {
            assertEquals(listOf(saved), (store.listUnviewedSettlements() as ContractResult.Success).value)
        }
        repeat(3) { assertEquals(ContractResult.Success(Unit), store.markViewed(saved.settlementId)) }
        store.close(); store = open()
        assertEquals(emptyList<Settlement>(), (store.listUnviewedSettlements() as ContractResult.Success).value)
        assertEquals(ContractResult.Success(saved), store.commitGameChange(credit("r1")))
        assertEquals(ContractResult.Success(GameMoney(100)), store.balance())
        assertEquals(failure(ContractError.DATA_UNAVAILABLE), store.markViewed(SettlementId("missing")))
    }

    fun testBeforeCommitFailureRollsBackMoneyVehicleAndRequestThenRetrySucceeds() {
        val command = credit("add").copy(vehicle = VehicleWrite.Add(vehicle()))
        store.beforeCommit = { throw SQLiteException("주입한 쓰기 실패") }
        assertEquals(failure(ContractError.STORAGE_FAILURE), store.commitGameChange(command))
        store.close(); store = open()
        assertEquals(ContractResult.Success(GameMoney(0)), store.balance())
        assertEquals(emptyList<OwnedVehicle>(), (store.listOwnedVehicles() as ContractResult.Success).value)
        assertEquals(emptyList<Settlement>(), (store.listUnviewedSettlements() as ContractResult.Success).value)
        val saved = success(store.commitGameChange(command))
        // 응답을 사용하지 않고 닫는 것으로 COMMIT 직후 응답 유실을 재현한다.
        store.close(); store = open()
        assertEquals(ContractResult.Success(saved), store.commitGameChange(command))
        assertEquals(listOf(vehicle()), (store.listOwnedVehicles() as ContractResult.Success).value)
    }

    fun testTwoConnectionsWithSameRevisionOnlyOneCommits() {
        success(store.commitGameChange(credit("seed").copy(vehicle = VehicleWrite.Add(vehicle()))))
        val second = open()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val update = VehicleWrite.Update(OwnedVehicleId("v1"), Revision(0), 2, VehicleLifecycle.OWNED, 0)
            val tasks = listOf(Callable { store.commitGameChange(credit("a").copy(vehicle = update)) },
                Callable { second.commitGameChange(credit("b").copy(vehicle = update.copy(level = 1, lifecycle = VehicleLifecycle.SOLD))) })
            val results = pool.invokeAll(tasks).map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is ContractResult.Success })
            assertEquals(1, results.count { it == failure(ContractError.STATE_CONFLICT) })
            assertEquals(ContractResult.Success(GameMoney(200)), store.balance())
        } finally { pool.shutdownNow(); second.close() }
    }

    fun testTwoConnectionsRetrySameRequestOnlyCreateOneResult() {
        val second = open()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val command = credit("same")
            val results = pool.invokeAll(listOf(Callable { store.commitGameChange(command) },
                Callable { second.commitGameChange(command) })).map { it.get(10, TimeUnit.SECONDS) }
            assertTrue(results.all { it is ContractResult.Success })
            assertEquals(results[0], results[1])
            assertEquals(ContractResult.Success(GameMoney(100)), store.balance())
            assertEquals(1, (store.listUnviewedSettlements() as ContractResult.Success).value.size)
        } finally { pool.shutdownNow(); second.close() }
    }

    fun testVehicleOnlyWriteAndDebitSurviveReopenAndSoldVehicleIsExcluded() {
        val add = credit("add").copy(money = null, vehicle = VehicleWrite.Add(vehicle()))
        assertEquals(GameMoney(0), success(store.commitGameChange(add)).balance)
        success(store.commitGameChange(credit("seed", 200)))
        val sold = credit("sold", 50).copy(money = MoneyChange(MoneyDirection.DEBIT, GameMoney(50)),
            vehicle = VehicleWrite.Update(OwnedVehicleId("v1"), Revision(0), 1, VehicleLifecycle.SOLD, 0))
        val saved = success(store.commitGameChange(sold))
        assertEquals(Revision(1), saved.changedVehicle!!.revision)
        store.close(); store = open()
        assertEquals(ContractResult.Success(GameMoney(150)), store.balance())
        assertEquals(emptyList<OwnedVehicle>(), (store.listOwnedVehicles() as ContractResult.Success).value)
        assertEquals(ContractResult.Success(saved), store.commitGameChange(sold))
        assertEquals(failure(ContractError.STATE_CONFLICT), store.commitGameChange(add.copy(requestId = RequestId("duplicate"))))
    }

    fun testInsufficientFundsOverflowAndMissingVehicleDoNotChangeState() {
        assertEquals(failure(ContractError.INSUFFICIENT_FUNDS), store.commitGameChange(credit("debit").copy(
            money = MoneyChange(MoneyDirection.DEBIT, GameMoney(1)), vehicle = VehicleWrite.Add(vehicle()))))
        assertEquals(emptyList<OwnedVehicle>(), (store.listOwnedVehicles() as ContractResult.Success).value)
        success(store.commitGameChange(credit("max", Long.MAX_VALUE)))
        assertEquals(failure(ContractError.INVALID_INPUT), store.commitGameChange(credit("overflow", 1)))
        assertEquals(failure(ContractError.DATA_UNAVAILABLE), store.commitGameChange(credit("missing", 0).copy(
            vehicle = VehicleWrite.Update(OwnedVehicleId("missing"), Revision(0), 2, VehicleLifecycle.OWNED, 0))))
        assertEquals(ContractResult.Success(GameMoney(Long.MAX_VALUE)), store.balance())
    }

    fun testSourceMismatchNeverMixesSimulationAndVehicleData() {
        assertEquals(failure(ContractError.INVALID_INPUT), store.commitGameChange(credit("r").copy(source = InputSource.VEHICLE)))
        success(store.commitGameChange(credit("sim")))
        SqliteGameChangeStore(context, InputSource.VEHICLE, name).use {
            assertEquals(failure(ContractError.STORAGE_FAILURE), it.balance())
            assertEquals(LedgerFailureReason.SOURCE_MISMATCH, it.lastStorageFailure)
        }
        assertEquals(ContractResult.Success(GameMoney(100)), store.balance())
    }

    fun testUnsupportedFutureSchemaPreservesOriginalBytes() {
        success(store.commitGameChange(credit("r1")))
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
        val before = context.getDatabasePath(name).readBytes()
        store = open()
        assertEquals(failure(ContractError.STORAGE_FAILURE), store.balance())
        assertEquals(LedgerFailureReason.UNSUPPORTED_SCHEMA, store.lastStorageFailure)
        store.close()
        assertTrue(before.contentEquals(context.getDatabasePath(name).readBytes()))
    }

    fun testCorruptDatabaseIsNotDeletedOrReset() {
        store.close()
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val original = ByteArray(4096) { 42 }
        file.writeBytes(original)
        store = open()
        assertEquals(failure(ContractError.STORAGE_FAILURE), store.balance())
        assertEquals(LedgerFailureReason.CORRUPT, store.lastStorageFailure)
        assertTrue(file.exists())
        assertTrue(original.contentEquals(file.readBytes()))
    }

    fun testUnknownVersionZeroDatabaseIsPreserved() {
        store.close()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use {
            it.execSQL("CREATE TABLE unknown_data (value TEXT)")
            it.execSQL("INSERT INTO unknown_data VALUES ('preserve')")
        }
        val before = context.getDatabasePath(name).readBytes()
        store = open()
        assertEquals(failure(ContractError.STORAGE_FAILURE), store.balance())
        assertEquals(LedgerFailureReason.UNSUPPORTED_SCHEMA, store.lastStorageFailure)
        store.close()
        assertTrue(before.contentEquals(context.getDatabasePath(name).readBytes()))
    }
}
