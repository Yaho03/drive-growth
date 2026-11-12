package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** #24 동의된 확장 시험값만 사용한다. 0원 영업은 완료 증거 시험이며 차종별 요금 정책이 아니다. */
@Suppress("DEPRECATION")
class CompletedExpansionLedgerTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.SIMULATION
    private val rules = RuleVersion("completed-expansion-trial-v1")
    private val car = OwnedVehicle(OwnedVehicleId("expansion-car"), VehicleId("hyundai_casper"), 1, Revision(0),
        VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
    private val catalog = VehicleCatalogSnapshot(ContentVersion("completed-expansion-fixture"), listOf(VehicleDefinition(car.vehicleId,"시험 차량")))
    private val offer = ShopExpansion(rules, true, 1, 3, 5, 3, GameMoney(500))
    private val expansionId = RequestId("expand-level2")
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private fun <T> ok(value: ContractResult<T>): T {
        assertTrue(value.toString(), value is ContractResult.Success)
        return (value as ContractResult.Success).value
    }
    private fun open() = SqliteGameChangeStore(context, source, name)
    override fun setUp() {
        super.setUp(); name="completed-expansion-${System.nanoTime()}.db";store=open()
        ok(store.commitGameChange(GameChange(RequestId("fixture-fund"), TransactionKind.GARAGE, source, rules,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(1000)), VehicleWrite.Add(car))))
    }
    override fun tearDown() { store.close();context.deleteDatabase(name);super.tearDown() }
    private fun raw(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,
        null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun rows(): List<String> = buildList { raw { db ->
        for (table in listOf("account","vehicles","visits","rentals","settlements","shop")) {
            db.rawQuery("SELECT * FROM $table ORDER BY 1",null).use { row -> while(row.moveToNext())
                add(table+":"+(0 until row.columnCount).joinToString("|") { if(row.isNull(it)) "<null>" else row.getString(it) }) }
        }
    } }
    private fun assign(index: Int, accident: Boolean = false): RequestId {
        val id=RequestId("completed-$index");val start=index*4_500_000L
        ok(store.rental.assign(RentalRequest(id,car.vehicleId,1),catalog,mapOf(car.vehicleId to RentalCategory.SEDAN),
            mapOf(car.vehicleId to GameMoney(0)), RentalTerms(rules,SimulatedCustomer(30,36),if(accident)0 else 99,
                EpochMillis(start),15,GameMoney(0),RoundingMode.DOWN)))
        return id
    }
    private fun complete(index: Int, accident: Boolean = false): RentalReceipt {
        val id=assign(index,accident);val start=index*4_500_000L
        ok(store.rental.account(id,EpochMillis(start),EpochMillis(start+3_600_000),null))
        val receipt=ok(store.rental.settle(id))
        assertEquals(if(accident)VehicleActivity.NEEDS_REPAIR else VehicleActivity.IDLE,
            ok(store.rental.finishReturn(id,EpochMillis(start+4_500_000))))
        return receipt
    }
    private fun assertExpansionFailure(error: ContractError) {
        val before=rows()
        assertEquals(ContractResult.Failure(error),store.rental.expand(expansionId,offer))
        assertEquals(before,rows())
    }
    fun testThreeUnassignedVisitsAndReplaysDoNotUnlockExpansion() {
        ok(store.rental.setResting(car.ownedVehicleId,true))
        repeat(3) { assign(it);assign(it) }
        assertEquals(3,ok(store.rental.shop()).progress.servedCustomers)
        assertEquals(0,ok(store.rental.completedRentalCount()))
        assertExpansionFailure(ContractError.STATE_CONFLICT)
        assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testOtherDomainUppercaseRequestIsNotAnOrphanRental() {
        ok(store.commitGameChange(GameChange(RequestId("RENTAL/SETTLE/ordinary"),TransactionKind.GARAGE,source,rules,
            MoneyChange(MoneyDirection.CREDIT,GameMoney(0)))))
        assertEquals(0,ok(store.rental.completedRentalCount()))
        assertExpansionFailure(ContractError.STATE_CONFLICT)
    }
    fun testAssignedButUnsettledRentalIsNotCompletedCustomer() {
        assign(0);assign(1);assign(2)
        assertEquals(3,ok(store.rental.shop()).progress.servedCustomers)
        assertEquals(0,ok(store.rental.completedRentalCount()))
        assertExpansionFailure(ContractError.STATE_CONFLICT)
    }
    fun testTwoCompletedRejectThenThirdCompletedAllowsLevelTwo() {
        complete(0);complete(1)
        assertEquals(2,ok(store.rental.completedRentalCount()))
        assertExpansionFailure(ContractError.STATE_CONFLICT)
        complete(2)
        assertEquals(3,ok(store.rental.completedRentalCount()))
        assertEquals(GameMoney(500),ok(store.rental.expand(expansionId,offer)).balance)
        assertEquals(2,ok(store.rental.shop()).progress.shopLevel)
        assertEquals(5,ok(store.rental.shop()).capacity)
    }
    fun testAgreedLevelThreeAndFourThresholdsAndCosts() {
        ok(store.commitGameChange(GameChange(RequestId("fixture-extra-fund"),TransactionKind.GARAGE,source,rules,
            MoneyChange(MoneyDirection.CREDIT,GameMoney(5000)))))
        val third=ShopExpansion(rules,true,2,5,7,10,GameMoney(1500))
        val fourth=ShopExpansion(rules,true,3,7,10,25,GameMoney(3000))
        repeat(25) { index ->
            complete(index)
            when(index+1) {
                3 -> ok(store.rental.expand(expansionId,offer))
                9 -> assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.rental.expand(RequestId("level3"),third))
                10 -> ok(store.rental.expand(RequestId("level3"),third))
                24 -> assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.rental.expand(RequestId("level4"),fourth))
                25 -> ok(store.rental.expand(RequestId("level4"),fourth))
            }
        }
        assertEquals(4,ok(store.rental.shop()).progress.shopLevel)
        assertEquals(10,ok(store.rental.shop()).capacity)
        assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testMoreThanThresholdAllowsOneExpansionWithoutExtraCharge() {
        repeat(4) { complete(it) }
        assertEquals(4,ok(store.rental.completedRentalCount()))
        val result=ok(store.rental.expand(expansionId,offer))
        assertEquals(result,ok(store.rental.expand(expansionId,offer)))
        assertEquals(GameMoney(500),ok(store.balance()))
    }
    fun testSameSettlementReplayDoesNotAddCompletedCustomers() {
        val first=complete(0)
        repeat(4) { assertEquals(first,ok(store.rental.settle(RequestId("completed-0")))) }
        assertEquals(1,ok(store.rental.completedRentalCount()))
        assertExpansionFailure(ContractError.STATE_CONFLICT)
    }
    fun testFreeAndAccidentRentalsCountOnlyWhenActualReceiptExists() {
        val free=complete(0);val accident=complete(1,true)
        assertEquals(GameMoney(1000),ok(store.balance()))
        assertEquals(GameMoney(0),free.settlement.transaction.money!!.amount)
        assertTrue(accident.accident)
        assertEquals(2,ok(store.rental.completedRentalCount()))
        assertEquals(free,ok(store.rental.completedRental(RequestId("completed-0")))!!.receipt)
        assertEquals(accident,ok(store.rental.completedRental(RequestId("completed-1")))!!.receipt)
        assertExpansionFailure(ContractError.STATE_CONFLICT)
    }
    private fun damaged(sql: String) {
        repeat(3) { complete(it) }
        raw { it.execSQL(sql) }
        val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.rental.completedRentalCount())
        assertEquals(before,rows())
        assertExpansionFailure(ContractError.STORAGE_FAILURE)
    }
    fun testMissingReceiptFailsWithoutChargeOrExpansion() = damaged(
        "DELETE FROM settlements WHERE request_id='rental/settle/completed-0'")
    fun testCorruptReceiptFailsWithoutChargeOrExpansion() = damaged(
        "UPDATE settlements SET result='{}' WHERE request_id='rental/settle/completed-0'")
    fun testTwoRentalsCannotShareTheSameCompletedReceipt() = damaged(
        "UPDATE rentals SET settlement_request='rental/settle/completed-0' WHERE id='completed-1'")
    fun testOrphanRentalReceiptDoesNotBecomeCompletedCustomer() = damaged(
        "DELETE FROM rentals WHERE id='completed-0'")
    fun testTwoConnectionsCannotExpandTheSameCapacityTwice() {
        repeat(3) { complete(it) }
        val alternateId = RequestId("expand-level2-second")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        open().use { other ->
            ok(other.balance())
            try {
                val a = pool.submit<ContractResult<Settlement>> {
                    start.await()
                    store.rental.expand(expansionId, offer)
                }
                val b = pool.submit<ContractResult<Settlement>> {
                    start.await()
                    other.rental.expand(alternateId, offer)
                }
                start.countDown()
                val results = listOf(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))
                val original = ok(results.single { it is ContractResult.Success })
                assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), results.single { it is ContractResult.Failure })
                store.close()
                store = open()
                val after = rows()
                val shop = ok(store.rental.shop())
                assertEquals(2 to 5, shop.progress.shopLevel to shop.capacity)
                assertEquals(GameMoney(500), ok(store.balance()))
                val purchases = ok(store.listUnviewedSettlements())
                    .filter { it.transaction.requestId in setOf(expansionId, alternateId) }
                assertEquals(listOf(original), purchases)
                assertEquals(original, ok(store.rental.expand(original.transaction.requestId, offer)))
                assertEquals(after, rows())
            } finally {
                pool.shutdownNow()
            }
        }
    }

    fun testCommitFailureRetryAndReplayDoNotDependOnLatestCountOrBalance() {
        repeat(3) { complete(it) };val before=rows()
        store.beforeCommit={throw SQLiteException("확장 전체 쓰기 뒤 실패")}
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.rental.expand(expansionId,offer))
        store.beforeCommit=null;assertEquals(before,rows())
        val original=ok(store.rental.expand(expansionId,offer))
        ok(store.commitGameChange(GameChange(RequestId("later-spend"),TransactionKind.GARAGE,source,rules,
            MoneyChange(MoneyDirection.DEBIT,GameMoney(500)))))
        raw { it.execSQL("DELETE FROM settlements WHERE request_id='rental/settle/completed-0'") }
        store.close();store=open();val after=rows()
        assertEquals(original,ok(store.rental.expand(expansionId,offer)))
        assertEquals(GameMoney(0),ok(store.balance()));assertEquals(after,rows())
    }
}
