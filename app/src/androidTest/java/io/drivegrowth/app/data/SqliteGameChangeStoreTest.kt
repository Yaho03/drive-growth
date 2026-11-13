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

    fun testOutOfIntRangeProgressIsRejectedWithoutWrapping() {
        assertInvalidVehicleNumbers("vehicles", "payload", listOf("levelProgressPercent" to "4294967297"))
    }

    fun testFractionalAcquisitionCostIsRejectedWithoutTruncation() {
        assertInvalidVehicleNumbers("vehicles", "payload", listOf("acquisitionCost" to "350.75"))
    }

    fun testInvalidVehicleNumberFormatsAndRangesPreserveOriginalDatabase() {
        assertInvalidVehicleNumbers("vehicles", "payload", invalidVehicleNumbers())
    }

    fun testInvalidSettlementVehicleNumbersFailQueryAndReplayWithoutMutation() {
        assertInvalidVehicleNumbers("settlements", "result", invalidVehicleNumbers())
    }

    private fun invalidVehicleNumbers(): List<Pair<String, String>> = listOf(
        "level" to "4294967297", "level" to "1.5", "level" to "0", "level" to "21",
        "revision" to "4294967296", "revision" to "0.5", "revision" to "-1",
        "levelProgressPercent" to "4294967297", "levelProgressPercent" to "1.5",
        "levelProgressPercent" to "-1", "levelProgressPercent" to "100",
        "acquisitionCost" to "350.75", "acquisitionCost" to "-1",
        "acquisitionCost" to "9223372036854775808",
    ) + listOf("level", "revision", "levelProgressPercent", "acquisitionCost").flatMap { field ->
        // writer는 JSON 정수만 쓴다. 문자열·실수(정수값 포함)·지수 표기 등은 허용하지 않는다.
        listOf("\"1\"", "1.0", "1e0", "true", "null", "[]", "{}").map { field to it }
    }

    private fun assertInvalidVehicleNumbers(table: String, column: String, invalid: List<Pair<String, String>>) {
        val originalVehicle = vehicle().copy(rarity = Rarity.RARE, acquisitionCost = GameMoney(350),
            acquisitionSource = VehicleAcquisitionSource.GACHA)
        val command = credit("add").copy(vehicle = VehicleWrite.Add(originalVehicle))
        val added = success(store.commitGameChange(command))
        store.close()
        val file = context.getDatabasePath(name)
        val original = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("SELECT $column FROM $table", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
        }
        invalid.forEach { (field, literal) ->
            store.close()
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                // JSON 텍스트를 직접 바꿔 1.0·지수·범위 초과 원문을 재직렬화로 정규화하지 않는다.
                val malformed = Regex("\\\"$field\\\":-?\\d+").replace(original, "\"$field\":$literal")
                assertTrue(original != malformed)
                assertEquals(1, db.update(table, ContentValues().apply { put(column, malformed) }, null, null))
            }
            val before = file.readBytes()
            store = open()
            val queried = if (table == "vehicles") store.listOwnedVehicles() else store.listUnviewedSettlements()
            val queryReason = store.lastStorageFailure
            store.close()
            assertTrue("$table $field=$literal 조회 후 원본 보존", before.contentEquals(file.readBytes()))
            assertEquals("$table $field=$literal 조회", failure(ContractError.STORAGE_FAILURE), queried)
            assertEquals(LedgerFailureReason.INVALID_RECORD, queryReason)
            store = open()
            val retried = if (table == "vehicles") store.commitGameChange(credit("grow").copy(
                vehicle = VehicleWrite.Update(originalVehicle.ownedVehicleId, Revision(0), 2, VehicleLifecycle.OWNED, 0)))
                else store.commitGameChange(command)
            val retryReason = store.lastStorageFailure
            assertEquals(ContractResult.Success(GameMoney(100)), store.balance())
            if (table == "vehicles") assertEquals(ContractResult.Success(added), store.commitGameChange(command))
            else assertEquals(ContractResult.Success(listOf(originalVehicle)), store.listOwnedVehicles())
            store.close()
            assertTrue("$table $field=$literal 원본 보존", before.contentEquals(file.readBytes()))
            assertEquals("$table $field=$literal 변경/재생", failure(ContractError.STORAGE_FAILURE), retried)
            assertEquals(LedgerFailureReason.INVALID_RECORD, retryReason)
            store = open()
        }
    }

    fun testJsonIntegerTypesAndVehicleBoundariesSurviveReopenAndReplay() {
        val parsed = JSONObject("{\"int\":99,\"long\":9223372036854775807,\"fraction\":350.75,\"wholeDouble\":1.0}")
        assertTrue(parsed.get("int") is Int)
        assertTrue(parsed.get("long") is Long)
        assertTrue(parsed.get("fraction") is Double)
        assertTrue(parsed.get("wholeDouble") is Double)
        val vehicles = listOf(vehicle("free").copy(rarity = Rarity.LEGEND, acquisitionSource = VehicleAcquisitionSource.GACHA),
            vehicle("progress-max").copy(level = 19, levelProgressPercent = 99,
                acquisitionCost = GameMoney(Long.MAX_VALUE), acquisitionSource = VehicleAcquisitionSource.PURCHASE_DELIVERY),
            vehicle("level-max").copy(level = MAX_VEHICLE_LEVEL))
        val commands = vehicles.map { credit(it.ownedVehicleId.value).copy(vehicle = VehicleWrite.Add(it)) }
        val saved = commands.map { success(store.commitGameChange(it)) }
        store.close(); store = open()
        assertEquals(vehicles.sortedBy { it.ownedVehicleId.value }, (store.listOwnedVehicles() as ContractResult.Success).value)
        assertEquals(ContractResult.Success(saved), store.listUnviewedSettlements())
        commands.zip(saved).forEach { (command, result) -> assertEquals(ContractResult.Success(result), store.commitGameChange(command)) }
    }

    fun testFreeGachaGrowthRollbackAndPastAddReplayPreserveAcquisition() {
        val original = vehicle().copy(rarity = Rarity.LEGEND, levelProgressPercent = 45,
            acquisitionSource = VehicleAcquisitionSource.GACHA)
        val add = credit("add").copy(vehicle = VehicleWrite.Add(original))
        val added = success(store.commitGameChange(add))
        val grow = credit("grow").copy(vehicle = VehicleWrite.Update(original.ownedVehicleId, Revision(0), 3, VehicleLifecycle.OWNED, 75))
        store.beforeCommit = { throw SQLiteException("성장 저장 실패 주입") }
        assertEquals(failure(ContractError.STORAGE_FAILURE), store.commitGameChange(grow))
        store.close(); store = open()
        assertEquals(ContractResult.Success(listOf(original)), store.listOwnedVehicles())
        assertEquals(ContractResult.Success(listOf(added)), store.listUnviewedSettlements())
        assertEquals(ContractResult.Success(GameMoney(100)), store.balance())
        val grown = success(store.commitGameChange(grow))
        store.close(); store = open()
        assertEquals(ContractResult.Success(added), store.commitGameChange(add))
        assertEquals(original.copy(level = 3, levelProgressPercent = 75, revision = Revision(1)), grown.changedVehicle)
        assertEquals(ContractResult.Success(listOf(grown.changedVehicle)), store.listOwnedVehicles())
        assertEquals(ContractResult.Success(GameMoney(200)), store.balance())
    }

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
        val grow = credit("legacy-grow").copy(kind = TransactionKind.GARAGE, vehicle = VehicleWrite.Update(
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

    fun testUpdateReplayRejectsDifferentStoredLevel() =
        assertUpdateReplayRejects { it.copy(level = 3) }

    fun testUpdateReplayRejectsDifferentStoredProgress() =
        assertUpdateReplayRejects { it.copy(levelProgressPercent = 46) }

    fun testUpdateReplayRejectsDifferentStoredLifecycle() =
        assertUpdateReplayRejects { it.copy(lifecycle = VehicleLifecycle.SOLD) }

    fun testUpdateReplayRejectsDifferentStoredRevision() =
        assertUpdateReplayRejects { it.copy(revision = Revision(2)) }

    private fun assertUpdateReplayRejects(changeSnapshot: (OwnedVehicle) -> OwnedVehicle) {
        val original = vehicle().copy(rarity = Rarity.RARE, acquisitionCost = GameMoney(350),
            acquisitionSource = VehicleAcquisitionSource.GACHA)
        success(store.commitGameChange(credit("add").copy(kind = TransactionKind.GARAGE, vehicle = VehicleWrite.Add(original))))
        val grow = credit("grow").copy(kind = TransactionKind.GARAGE, vehicle = VehicleWrite.Update(
            original.ownedVehicleId, Revision(0), 2, VehicleLifecycle.OWNED, 45))
        val profile = (store.localProfileId() as ContractResult.Success).value
        val command = HostCheckpointCommand.Garage(ContentVersion("test-content"), grow)
        assertEquals(ContractResult.Success(HostCommandCheckpoint(profile, command, HostCheckpointPhase.SAVED)),
            store.hostCommands.save(profile, command))
        val result = (store.hostCommands.execute(profile, command) as ContractResult.Success).value as HostCheckpointResult.Garage
        val expectedVehicle = original.copy(level = 2, levelProgressPercent = 45, revision = Revision(1))
        assertEquals(expectedVehicle, result.value.changedVehicle)
        val started = ContractResult.Success(HostCommandCheckpoint(profile, command, HostCheckpointPhase.STARTED))
        assertEquals(started, store.hostCommands.load(profile))
        store.close()

        val file = context.getDatabasePath(name)
        val modified = result.value.copy(changedVehicle = changeSnapshot(expectedVehicle))
        val encoded = LedgerJson.settlement(modified)
        assertEquals(modified, LedgerJson.settlement(JSONObject(encoded.toString())))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val values = ContentValues().apply { put("result", encoded.toString()) }
            assertEquals(1, db.update("settlements", values, "request_id = ?", arrayOf(grow.requestId.value)))
        }
        val before = file.readBytes()

        store = open()
        val replayed = store.commitGameChange(grow)
        val replayReason = store.lastStorageFailure
        store.close()
        assertTrue("동일 명령 재시도 후 DB 원본 보존", before.contentEquals(file.readBytes()))

        store = open()
        val finished = store.hostCommands.finish(profile, command)
        val finishReason = store.lastStorageFailure
        val slot = store.hostCommands.load(profile)
        val balance = store.balance()
        val vehicles = store.listOwnedVehicles()
        store.close()
        val unchanged = before.contentEquals(file.readBytes())
        store = open()

        val expected = failure(ContractError.STORAGE_FAILURE)
        assertEquals("동일 명령 재생 및 STARTED 완료", listOf(expected, expected), listOf(replayed, finished))
        assertEquals(LedgerFailureReason.INVALID_RECORD, replayReason)
        assertEquals(LedgerFailureReason.INVALID_RECORD, finishReason)
        assertEquals(started, slot)
        assertEquals(ContractResult.Success(GameMoney(200)), balance)
        assertEquals(ContractResult.Success(listOf(expectedVehicle)), vehicles)
        assertTrue("완료 실패 후 정산과 DB 원본 보존", unchanged)
    }

    fun testPastAddAndGrowthReplayTheirSnapshotsAfterDisposalAndReopen() {
        val original = vehicle().copy(rarity = Rarity.RARE, acquisitionCost = GameMoney(350),
            acquisitionSource = VehicleAcquisitionSource.GACHA)
        val add = credit("add").copy(vehicle = VehicleWrite.Add(original))
        val grow = credit("grow").copy(kind = TransactionKind.GARAGE, vehicle = VehicleWrite.Update(
            original.ownedVehicleId, Revision(0), 2, VehicleLifecycle.OWNED, 45))
        val sell = credit("sell").copy(kind = TransactionKind.GARAGE, vehicle = VehicleWrite.Update(
            original.ownedVehicleId, Revision(1), 2, VehicleLifecycle.SOLD, 45))
        val added = success(store.commitGameChange(add))
        val grown = success(store.commitGameChange(grow))
        val sold = success(store.commitGameChange(sell))
        assertEquals(original, added.changedVehicle)
        assertEquals(original.copy(level = 2, revision = Revision(1), levelProgressPercent = 45), grown.changedVehicle)
        assertEquals(original.copy(level = 2, revision = Revision(2), lifecycle = VehicleLifecycle.SOLD,
            levelProgressPercent = 45), sold.changedVehicle)
        store.close()
        val file = context.getDatabasePath(name)
        val before = file.readBytes()
        store = open()
        assertEquals(ContractResult.Success(added), store.commitGameChange(add))
        assertEquals(ContractResult.Success(grown), store.commitGameChange(grow))
        assertEquals(ContractResult.Success(sold), store.commitGameChange(sell))
        assertEquals(failure(ContractError.STATE_CONFLICT),
            store.commitGameChange(grow.copy(vehicle = (grow.vehicle as VehicleWrite.Update).copy(levelProgressPercent = 46))))
        assertEquals(emptyList<OwnedVehicle>(), (store.listOwnedVehicles() as ContractResult.Success).value)
        assertEquals(listOf(added, grown, sold), (store.listUnviewedSettlements() as ContractResult.Success).value)
        assertEquals(ContractResult.Success(GameMoney(300)), store.balance())
        store.close()
        assertTrue(before.contentEquals(file.readBytes()))
        store = open()
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
