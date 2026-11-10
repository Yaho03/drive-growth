package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.garage.VehicleRepair
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 실제 RentalLedger 사고/반납과 assets 견적을 쓰는 격리 DB 시험. 제품 프로필/실차 입력이 아니다. */
@Suppress("DEPRECATION")
class RepairAccountIgnoreIndependentTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.SIMULATION
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private lateinit var content: GameContentSnapshot
    private lateinit var supplied: ContractResult<GameContentSnapshot>
    private val car = OwnedVehicle(OwnedVehicleId("repair-car"), VehicleId("hyundai_casper"), 4, Revision(0),
        VehicleLifecycle.OWNED, Rarity.RARE, 50, GameMoney(250), VehicleAcquisitionSource.GACHA)
    private val rentalId = RequestId("damaged-rental")
    private fun open(provide: Boolean = true) = SqliteGameChangeStore(context, source, name,
        repairContent = if (provide) ({ supplied }) else null)
    private fun <T> ok(r: ContractResult<T>): T { assertTrue(r.toString(), r is ContractResult.Success); return (r as ContractResult.Success).value }
    private fun failure(e: ContractError) = ContractResult.Failure(e)
    override fun setUp() {
        super.setUp(); name = "repair-" + System.nanoTime() + ".db"
        content = (AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        supplied = ContractResult.Success(content); store = open()
        ok(store.commitGameChange(GameChange(RequestId("fund"), TransactionKind.GARAGE, source, content.ruleVersion,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(1000)), VehicleWrite.Add(car))))
        damage()
    }
    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }
    private fun damage() {
        val terms = RentalTerms(content.ruleVersion, SimulatedCustomer(30,36), 0, EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)
        assertEquals(car.ownedVehicleId, ok(store.rental.assign(RentalRequest(rentalId,car.vehicleId,1),content.catalog,
            mapOf(car.vehicleId to RentalCategory.SEDAN),mapOf(car.vehicleId to GameMoney(0)),terms)))
        ok(store.rental.account(rentalId,EpochMillis(0),EpochMillis(3_600_000),null))
        assertTrue(ok(store.rental.settle(rentalId)).accident)
        assertEquals(VehicleActivity.NEEDS_REPAIR,ok(store.rental.finishReturn(rentalId,EpochMillis(4_500_000))))
    }
    private fun command(id: String = "repair") = ok(VehicleRepair(content).prepare(store,RequestId(id),source,
        VehicleRef(car.ownedVehicleId,ok(store.listOwnedVehicles()).single().revision)))
    private fun reopen(provide: Boolean = true) { store.close();store=open(provide) }
    private fun raw(block:(SQLiteDatabase)->Unit) = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun assertUnchanged(c:RepairCommand,e:ContractError) {
        val balance=ok(store.balance());val history=ok(store.listVehicleHistory());val receipts=ok(store.listUnviewedSettlements());val activity=ok(store.rental.activity(car.ownedVehicleId))
        assertEquals(failure(e),store.commitRepair(c));assertEquals(balance,ok(store.balance()));assertEquals(history,ok(store.listVehicleHistory()));assertEquals(receipts,ok(store.listUnviewedSettlements()));assertEquals(activity,ok(store.rental.activity(car.ownedVehicleId)))
    }
    fun testIgnoredAccountUpdateMustRollBackEntireRepair() {
        val c=command("ignore-account")
        val beforeBalance=ok(store.balance())
        val beforeHistory=ok(store.listVehicleHistory())
        val beforeActivity=ok(store.rental.activity(car.ownedVehicleId))
        val beforeReceipts=ok(store.listUnviewedSettlements())
        fun rows(): Map<String,List<String>> {
            val result=linkedMapOf<String,List<String>>()
            raw { db ->
                for (table in listOf("account","vehicles","rentals","settlements","repair_requests")) {
                    db.rawQuery("SELECT * FROM $table ORDER BY rowid",null).use { row ->
                        val values=mutableListOf<String>()
                        while(row.moveToNext()) values.add((0 until row.columnCount).joinToString("|") { if(row.isNull(it)) "<NULL>" else row.getString(it) })
                        result[table]=values
                    }
                }
            }
            return result
        }
        val beforeRows=rows()
        raw { it.execSQL("CREATE TRIGGER ignore_repair_account BEFORE UPDATE ON account BEGIN SELECT RAISE(IGNORE); END") }
        val result=store.commitRepair(c)
        val actualBalance=store.balance()
        val receipt=store.repairSettlement(c.requestId)
        val activity=store.rental.activity(car.ownedVehicleId)
        val afterRows=rows()
        val evidence="result=$result\nactualBalance=$actualBalance\nreceipt=$receipt\nactivity=$activity\nbeforeRows=$beforeRows\nafterRows=$afterRows"
        context.openFileOutput("account-ignore-evidence.txt",0).use { it.write(evidence.toByteArray()) }
        instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream", "\nACCOUNT_IGNORE_OBSERVED\n$evidence\n") })
        assertEquals(failure(ContractError.STORAGE_FAILURE),result)
        assertEquals(beforeBalance,ok(store.balance()))
        assertEquals(beforeHistory,ok(store.listVehicleHistory()))
        assertEquals(beforeActivity,ok(store.rental.activity(car.ownedVehicleId)))
        assertEquals(beforeReceipts,ok(store.listUnviewedSettlements()))
        assertNull(ok(store.repairSettlement(c.requestId)))
        assertEquals(beforeRows,afterRows)
    }
}
