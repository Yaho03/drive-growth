package io.drivegrowth.app.data

import android.test.InstrumentationTestCase
import android.database.sqlite.SQLiteDatabase
import io.drivegrowth.core.contract.*
import java.util.UUID

/** 같은 실제 helper의 읽기/열람 경계. 전체 기록 조회는 지급·열람 쓰기를 하지 않는다. */
@Suppress("DEPRECATION")
class SettlementHistoryReaderTest:InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private lateinit var store:SqliteGameChangeStore
    private lateinit var name:String
    private fun <T> ok(r:ContractResult<T>):T{assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    override fun setUp(){super.setUp();name="history-${UUID.randomUUID()}.db";store=SqliteGameChangeStore(context,InputSource.SIMULATION,name,clock={1000})}
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    private fun credit(id:String)=ok(store.commitGameChange(GameChange(RequestId(id),TransactionKind.GARAGE,InputSource.SIMULATION,RuleVersion("original"),MoneyChange(MoneyDirection.CREDIT,GameMoney(123)))))
    private fun raw(f:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(f)
    private fun rows():List<String> = buildList{raw{db->for(t in listOf("account","vehicles","settlements","material_requests","rentals","visits","quest_events","quest_claims"))db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{r->while(r.moveToNext())add(t+":"+(0 until r.columnCount).joinToString("|"){r.getType(it).toString()+":"+r.getString(it)})}}}
    fun testViewedRowsRemainInFullHistoryAndReadDoesNotWrite() {
        val first=credit("first");val second=credit("second");ok(store.markViewed(first.settlementId));val before=rows()
        val history=ok(SettlementHistoryReader(store).read());assertEquals(listOf(first,second),history.entries.map{it.settlement})
        assertEquals(listOf(true,false),history.entries.map{it.viewed});assertEquals(before,rows())
        store.close();store=SqliteGameChangeStore(context,InputSource.SIMULATION,name);assertEquals(history,ok(SettlementHistoryReader(store).read()))
    }
    fun testFractionalBalanceAndCommandIdentityDamageFailWithoutRepair() {
        val receipt=credit("original");val saved=rows()
        for(sql in listOf("UPDATE settlements SET result=replace(result,'\"balance\":123','\"balance\":123.5')",
            "UPDATE settlements SET request_id='foreign'", "UPDATE settlements SET command=replace(command,'GARAGE','RENTAL')")) {
            var originalResult="";var originalCommand="";raw{db->db.rawQuery("SELECT result,command FROM settlements",null).use{it.moveToFirst();originalResult=it.getString(0);originalCommand=it.getString(1)};db.execSQL(sql)}
            val damaged=rows();assertFalse("손상 입력이 실제 행을 바꿔야 함",saved==damaged);assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),SettlementHistoryReader(store).read());assertEquals(damaged,rows())
            raw{it.execSQL("UPDATE settlements SET request_id=?,result=?,command=?",arrayOf(receipt.transaction.requestId.value,originalResult,originalCommand))}
        }
        assertEquals(saved,rows())
    }
    fun testRealCompletedRentalSuppliesStoredDetailsAndOrphanIsRejected() {
        store.close();store=SqliteGameChangeStore(context,InputSource.SIMULATION,name,clock={4000000})
        val car=OwnedVehicle(OwnedVehicleId("rental-car"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT)
        ok(store.commitGameChange(GameChange(RequestId("add-car"),TransactionKind.GARAGE,InputSource.SIMULATION,RuleVersion("rental-original"),vehicle=VehicleWrite.Add(car))))
        val request=io.drivegrowth.core.rental.RentalRequest(RequestId("actual-rental"),car.vehicleId,1)
        val terms=RentalTerms(RuleVersion("rental-original"),io.drivegrowth.core.rental.SimulatedCustomer(30,36),0,EpochMillis(0),15,GameMoney(5),java.math.RoundingMode.DOWN)
        ok(store.rental.assign(request,VehicleCatalogSnapshot(ContentVersion("fixture"),listOf(VehicleDefinition(car.vehicleId,"검증 차량"))),mapOf(car.vehicleId to io.drivegrowth.core.rental.RentalCategory.SMALL),mapOf(car.vehicleId to GameMoney(100)),terms))
        ok(store.rental.account(request.id,EpochMillis(0),EpochMillis(3600000),null));ok(store.rental.settle(request.id))
        val completed=ok(store.rental.completedRental(request.id))!!;val before=rows()
        val row=ok(SettlementHistoryReader(store).read()).entries.single{it.rental!=null}
        assertEquals(completed,row.rental);assertEquals(completed.receipt.settlement,row.settlement);assertNull(row.rental!!.receipt.regularCustomer);assertEquals(car.vehicleId,row.rentalVehicleId);assertEquals(before,rows())
        raw{it.execSQL("DELETE FROM rentals WHERE id=?",arrayOf(request.id.value))};val damaged=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),SettlementHistoryReader(store).read());assertEquals(damaged,rows())
    }

    fun testMaterialRoleSnapshotDamageCannotBecomeNormalHistory() {
        val content=(AssetGameContent(context.assets).load() as io.drivegrowth.core.content.ContentLoad.Loaded).snapshot
        store.close();store=SqliteGameChangeStore(context,InputSource.SIMULATION,name,materialVersions={ContractResult.Success(MaterialVersions(content.catalog.contentVersion,content.ruleVersion))},clock={1000})
        credit("fund")
        val a=OwnedVehicle(OwnedVehicleId("target"),VehicleId("hyundai_casper"),2,Revision(0),VehicleLifecycle.OWNED,Rarity.RARE,50,GameMoney(0),VehicleAcquisitionSource.GACHA)
        val b=a.copy(ownedVehicleId=OwnedVehicleId("material"),level=1,levelProgressPercent=0,rarity=Rarity.ADVANCED)
        for(car in listOf(a,b))ok(store.commitGameChange(GameChange(RequestId("add-"+car.ownedVehicleId.value),TransactionKind.GARAGE,InputSource.SIMULATION,content.ruleVersion,vehicle=VehicleWrite.Add(car))))
        val command=ok(io.drivegrowth.core.garage.VehicleUpgrade(content).prepareMaterial(store,RequestId("material-original"),InputSource.SIMULATION,io.drivegrowth.core.garage.VehicleRef(a.ownedVehicleId,a.revision),io.drivegrowth.core.garage.VehicleRef(b.ownedVehicleId,b.revision)))
        ok(store.commitMaterial(command));ok(SettlementHistoryReader(store).read());val before=rows()
        raw{it.execSQL("UPDATE material_requests SET result=replace(result,'\"revision\":1','\"revision\":0')")};val damaged=rows();assertFalse("역할 snapshot 손상 입력이 실제 행을 바꿔야 함",before==damaged)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),SettlementHistoryReader(store).read());assertEquals(damaged,rows())
    }

}
