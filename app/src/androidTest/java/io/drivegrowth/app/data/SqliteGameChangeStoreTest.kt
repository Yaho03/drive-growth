package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
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
    private fun vehicle(id: String = "v1") = OwnedVehicle(OwnedVehicleId(id), VehicleId("car"), 1, Revision(0), VehicleLifecycle.OWNED)
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
            val update = VehicleWrite.Update(OwnedVehicleId("v1"), Revision(0), 2, VehicleLifecycle.OWNED)
            val tasks = listOf(Callable { store.commitGameChange(credit("a").copy(vehicle = update)) },
                Callable { second.commitGameChange(credit("b").copy(vehicle = update.copy(lifecycle = VehicleLifecycle.SOLD))) })
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
            vehicle = VehicleWrite.Update(OwnedVehicleId("v1"), Revision(0), 2, VehicleLifecycle.SOLD))
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
            vehicle = VehicleWrite.Update(OwnedVehicleId("missing"), Revision(0), 2, VehicleLifecycle.OWNED))))
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
