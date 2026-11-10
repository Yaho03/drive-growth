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
class RepairLedgerTest : InstrumentationTestCase() {
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
    fun testActualPrepareCommitReopenPreservesVehicleAccidentsAndRest() {
        ok(store.rental.setResting(car.ownedVehicleId,true));val c=command();assertEquals(GameMoney(55),c.cost)
        val r=ok(store.commitRepair(c));assertEquals(GameMoney(945),r.balance);assertEquals(car.copy(revision=Revision(2)),r.vehicle)
        assertEquals(MoneyChange(MoneyDirection.DEBIT,GameMoney(55)),r.transaction.money)
        assertEquals(VehicleActivity.IDLE,r.activity);assertEquals(1,r.accidentCount)
        assertEquals(1,ok(store.rental.accidentCount(car.ownedVehicleId)));assertEquals(setOf(car.ownedVehicleId),ok(store.rental.restingVehicles()))
        reopen();assertEquals(r,ok(store.commitRepair(c)));assertEquals(r,ok(store.repairSettlement(c.requestId)))
        assertEquals(VehicleActivity.IDLE,ok(store.rental.activity(car.ownedVehicleId)));assertEquals(GameMoney(945),ok(store.balance()))
        assertEquals(1,ok(store.listUnviewedSettlements()).count{it.transaction.requestId==c.requestId})
        ok(store.markViewed(r.settlementId));assertEquals(r,ok(store.commitRepair(c)))
    }
    fun testReplayBeforeCurrentAuthorityAndSoldVehicle() {
        val c=command();val r=ok(store.commitRepair(c))
        ok(store.commitGameChange(GameChange(RequestId("sell"),TransactionKind.GARAGE,source,content.ruleVersion,
            MoneyChange(MoneyDirection.CREDIT,GameMoney(9)),VehicleWrite.Update(car.ownedVehicleId,Revision(2),4,VehicleLifecycle.SOLD,50))))
        supplied=failure(ContractError.STORAGE_FAILURE);reopen(false)
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),store.readRepairState(car.ownedVehicleId));assertEquals(r,ok(store.commitRepair(c)))
        assertEquals(GameMoney(954),ok(store.balance()));assertTrue(ok(store.listOwnedVehicles()).isEmpty())
    }
    fun testChangedWholeCommandConflicts() {
        val c=command();ok(store.commitRepair(c))
        for(bad in listOf(c.copy(cost=GameMoney(56)),c.copy(expectedAccidentCount=2),c.copy(source=InputSource.VEHICLE),
            c.copy(contentVersion=ContentVersion("other")),c.copy(ruleVersion=RuleVersion("other")),c.copy(target=c.target.copy(revision=Revision(9)))))
            assertUnchanged(bad,ContractError.STATE_CONFLICT)
    }
    fun testMissingAuthorityAndExplicitFailuresDoNotBecomeFreeRepair() {
        val c=command();reopen(false);assertUnchanged(c,ContractError.DATA_UNAVAILABLE)
        for(e in listOf(ContractError.DATA_UNAVAILABLE,ContractError.PERMISSION_DENIED,ContractError.STORAGE_FAILURE)) {
            supplied=failure(e);reopen();assertUnchanged(c,e)
        }
    }
    fun testRepairActivityWithoutCompletedAccidentEvidenceIsStorageFailure() {
        val c=command();store.close();raw{it.execSQL("UPDATE rentals SET settlement_request=NULL")};store=open()
        assertEquals(failure(ContractError.STORAGE_FAILURE),store.readRepairState(car.ownedVehicleId))
        assertEquals(failure(ContractError.STORAGE_FAILURE),store.commitRepair(c));assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testCurrentSourceVersionsRevisionCostAndAccidentsAreValidated() {
        val c=command()
        assertUnchanged(c.copy(source=InputSource.VEHICLE),ContractError.INVALID_INPUT)
        for(bad in listOf(c.copy(contentVersion=ContentVersion("old")),c.copy(target=c.target.copy(revision=Revision(0))),
            c.copy(cost=GameMoney(0)),c.copy(expectedAccidentCount=0),c.copy(cost=GameMoney(Long.MAX_VALUE))))assertUnchanged(bad,ContractError.STATE_CONFLICT)
    }
    fun testRentedReturningAndAlreadyIdleCannotRepair() {
        val c=command()
        for(phase in listOf(VehicleActivity.RENTED,VehicleActivity.RETURNING,VehicleActivity.IDLE)) {
            ok(store.atomic{db->db.execSQL("UPDATE rentals SET phase=?",arrayOf(phase.name));ContractResult.Success(Unit)})
            assertUnchanged(c,ContractError.STATE_CONFLICT)
        }
    }
    fun testMissingAndDisposedVehicleCannotRepair() {
        val c=command();assertEquals(failure(ContractError.DATA_UNAVAILABLE),store.commitRepair(c.copy(target=VehicleRef(OwnedVehicleId("missing"),Revision(0)))))
        ok(store.commitGameChange(GameChange(RequestId("dispose-first"),TransactionKind.GARAGE,source,content.ruleVersion,
            vehicle=VehicleWrite.Update(car.ownedVehicleId,Revision(1),4,VehicleLifecycle.SCRAPPED,50))))
        assertUnchanged(c,ContractError.DATA_UNAVAILABLE)
    }
    fun testInsufficientFundsAndMaximumRevisionDoNotWrite() {
        val c=command();ok(store.atomic{db->db.execSQL("UPDATE account SET balance=54");ContractResult.Success(Unit)})
        assertUnchanged(c,ContractError.INSUFFICIENT_FUNDS)
        ok(store.atomic{db->store.writeVehicle(db,car.copy(revision=Revision(Int.MAX_VALUE)));ContractResult.Success(Unit)})
        assertUnchanged(c.copy(target=c.target.copy(revision=Revision(Int.MAX_VALUE))),ContractError.INVALID_INPUT)
    }
    fun testZeroCostUsesNoMoneyTransactionAndLongBalanceDoesNotWrap() {
        supplied=ContractResult.Success(content.copy(vehicles=content.vehicles.map{if(it.vehicleId==car.vehicleId)it.copy(price=GameMoney(0))else it}))
        ok(store.atomic{db->db.execSQL("UPDATE account SET balance=?",arrayOf(Long.MAX_VALUE));ContractResult.Success(Unit)})
        val state=ok(store.readRepairState(car.ownedVehicleId));assertEquals(GameMoney(0),state.repairCost)
        val c=RepairCommand(RequestId("free"),source,state.contentVersion,state.ruleVersion,VehicleRef(state.vehicle.ownedVehicleId,state.vehicle.revision),state.accidentCount,state.repairCost)
        val r=ok(store.commitRepair(c));assertNull(r.transaction.money);assertEquals(GameMoney(Long.MAX_VALUE),r.balance)
        reopen(false);assertEquals(r,ok(store.commitRepair(c)))
    }
    fun testCommitFailureRollsBackMoneyVehicleActivityAndBothRecords() {
        val c=command();val before=ok(store.readRepairState(car.ownedVehicleId));val receipts=ok(store.listUnviewedSettlements())
        store.beforeCommit={throw SQLiteException("수리 COMMIT 전 실패")}
        assertEquals(failure(ContractError.STORAGE_FAILURE),store.commitRepair(c));store.beforeCommit=null
        assertEquals(before,ok(store.readRepairState(car.ownedVehicleId)));assertEquals(receipts,ok(store.listUnviewedSettlements()))
        assertNull(ok(store.repairSettlement(c.requestId)));val r=ok(store.commitRepair(c));assertEquals(GameMoney(945),r.balance)
    }
    fun testActivityWriteFailureRollsBackPreviousMoneyAndVehicleWrites() {
        val c=command();ok(store.atomic{db->db.execSQL("CREATE TRIGGER reject_repair BEFORE UPDATE OF phase ON rentals WHEN NEW.phase='IDLE' BEGIN SELECT RAISE(ABORT,'repair'); END");ContractResult.Success(Unit)})
        assertUnchanged(c,ContractError.STORAGE_FAILURE)
    }
    fun testGenericRequestIdCollisionBothDirections() {
        val c=command();val other=GameChange(c.requestId,TransactionKind.GARAGE,source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1)))
        val r=ok(store.commitRepair(c));assertEquals(failure(ContractError.STATE_CONFLICT),store.commitGameChange(other));assertEquals(r,ok(store.commitRepair(c)))
        val id=RequestId("generic-first");ok(store.commitGameChange(other.copy(requestId=id)))
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitRepair(c.copy(requestId=id)));assertEquals(failure(ContractError.STATE_CONFLICT),store.repairSettlement(id))
    }
    fun testMaterialRequestIdCollisionBothDirections() {
        val c=command();ok(store.commitRepair(c))
        val material=MaterialCommand(c.requestId,source,c.contentVersion,c.ruleVersion,VehicleWrite.Update(car.ownedVehicleId,Revision(2),5,VehicleLifecycle.OWNED,0),
            VehicleWrite.Update(OwnedVehicleId("material"),Revision(0),1,VehicleLifecycle.CONSUMED,0),GameMoney(0))
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitMaterial(material))
        ok(store.atomic{db->db.execSQL("INSERT INTO material_requests VALUES('material-first','x','x','x')");ContractResult.Success(Unit)})
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitRepair(c.copy(requestId=RequestId("material-first"))))
    }
    fun testGachaRequestIdCollisionBothDirections() {
        val c=command();ok(store.commitRepair(c))
        val authority=GachaAuthority(source,content.catalog.contentVersion,content.ruleVersion,1,3,GameMoney(500),30,Rarity.RARE,setOf(car.vehicleId),Rarity.entries.toSet())
        ok(store.atomic{db->db.execSQL("INSERT INTO gacha_progress VALUES(1,0,0,1)");ContractResult.Success(Unit)})
        store.close();store=SqliteGameChangeStore(context,source,name,gachaAuthority={ContractResult.Success(authority)},repairContent={supplied})
        fun draw(id:RequestId)=GachaCommand(id,source,authority.contentVersion,authority.ruleVersion,Revision(0),1,3,0,GachaTicketUse.FREE,
            GachaAward(car.copy(ownedVehicleId=OwnedVehicleId("drawn"),revision=Revision(0),level=1,levelProgressPercent=0,rarity=Rarity.COMMON,acquisitionCost=GameMoney(0)),1,false))
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitGacha(draw(c.requestId)))
        val drawn=ok(store.commitGacha(draw(RequestId("gacha-first"))))
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitRepair(c.copy(requestId=drawn.command.requestId)))
    }
    private fun race(a:()->ContractResult<*>,b:()->ContractResult<*>):List<ContractResult<*>> {
        val pool=Executors.newFixedThreadPool(2);val gate=CountDownLatch(1)
        try{val jobs=listOf(a,b).map{fn->pool.submit(Callable{gate.await();fn()})};gate.countDown();return jobs.map{it.get(10,TimeUnit.SECONDS)}}finally{pool.shutdownNow()}
    }
    fun testTwoConnectionsRepairOnlyOnceAndSameCommandReplays() {
        val c=command();val other=open()
        try {val results=race({store.commitRepair(c)},{other.commitRepair(c.copy(requestId=RequestId("other-repair")))})
            assertEquals(1,results.count{it is ContractResult.Success});assertEquals(1,results.count{it==failure(ContractError.STATE_CONFLICT)})
            assertEquals(GameMoney(945),ok(store.balance()));assertEquals(Revision(2),ok(store.listOwnedVehicles()).single().revision)
            val winner=results.filterIsInstance<ContractResult.Success<*>>().single().value as RepairSettlement
            assertEquals(winner,ok(other.commitRepair(winner.command)))
        }finally{other.close()}
    }
    fun testTwoConnectionsSameCommandHaveOneReceipt() {
        val c=command();val other=open();try{val results=race({store.commitRepair(c)},{other.commitRepair(c)})
            assertEquals(ok(results[0]),ok(results[1]));assertEquals(GameMoney(945),ok(store.balance()))
        }finally{other.close()}
    }
    fun testRepairRacesSaleWithoutPartialCharge() {
        val c=command();val other=open();try{val results=race({store.commitRepair(c)},{other.commitGameChange(GameChange(RequestId("sale"),TransactionKind.GARAGE,source,content.ruleVersion,
            vehicle=VehicleWrite.Update(car.ownedVehicleId,Revision(1),4,VehicleLifecycle.SOLD,50)))})
            assertEquals(1,results.count{it is ContractResult.Success});assertEquals(GameMoney(if(results[0] is ContractResult.Success)945 else 1000),ok(store.balance()))
        }finally{other.close()}
    }
    fun testDeletedEitherHalfOfRepairHistoryIsStorageFailure() {
        val c=command();val receipt=ok(store.commitRepair(c));store.close()
        raw{db->db.execSQL("DELETE FROM repair_requests WHERE request_id='repair'")};store=open()
        assertEquals(failure(ContractError.STORAGE_FAILURE),store.commitRepair(c));assertEquals(failure(ContractError.STORAGE_FAILURE),store.repairSettlement(c.requestId))
        assertEquals(GameMoney(945),ok(store.balance()));assertEquals(receipt.vehicle,ok(store.listOwnedVehicles()).single())
    }
    fun testMissingCommonReceiptIsStorageFailureWithoutSecondCharge() {
        val c=command();ok(store.commitRepair(c));store.close();raw{it.execSQL("DELETE FROM settlements WHERE request_id='repair'")};store=open()
        assertEquals(failure(ContractError.STORAGE_FAILURE),store.commitRepair(c));assertEquals(GameMoney(945),ok(store.balance()))
    }
    fun testStrictNumbersAndBrokenResultPreserveDatabaseBytes() {
        val c=command();ok(store.commitRepair(c));store.close()
        for(column in listOf("command","original_state","result")) {
            var original="";raw{db->db.rawQuery("SELECT $column FROM repair_requests",null).use{it.moveToFirst();original=it.getString(0)}}
            val field=when(column){"command"->"cost";"original_state"->"accidents";else->"accidents"}
            val pattern=Regex("\\\"$field\\\":\\d+")
            for(literal in listOf("1.5","\"1\"","1e0","4294967297","null")) {
                val bad=original.replaceFirst(pattern,"\"$field\":$literal");assertTrue(bad!=original)
                raw{it.execSQL("UPDATE repair_requests SET $column=?",arrayOf(bad))};val file=context.getDatabasePath(name);val bytes=file.readBytes();store=open()
                assertEquals(failure(ContractError.STORAGE_FAILURE),store.commitRepair(c));store.close();assertTrue(bytes.contentEquals(file.readBytes()))
                raw{it.execSQL("UPDATE repair_requests SET $column=?",arrayOf(original))}
            }
        };store=open();ok(store.commitRepair(c))
    }
    fun testSupportedV1ThroughV10MigrationPreservesOriginalCoreRows() {
        store.close()
        val base=context.getDatabasePath(name).readBytes()
        for(version in 1..10) {
            context.getDatabasePath(name).writeBytes(base)
            raw{db->
                val introduced=mapOf(2 to listOf("rentals","visits","shop","facilities","rental_rest"),3 to listOf("discoveries"),4 to listOf("gacha_progress","gacha_requests"),
                    5 to listOf("delivery_lists","delivery_requests","delivery_active","delivery_routes","delivery_outcomes","delivery_held","delivery_changes","driving_days","driving_drives","delivery_route_history","delivery_hold_outcomes"),6 to listOf("material_requests"),7 to listOf("repair_requests"),8 to listOf("initialization_requests","initialization_profile"),9 to listOf("first_gift_requests","first_gift_markers"),10 to listOf("host_command_checkpoint"),11 to listOf("quest_events","quest_claims","quest_tracking"))
                stripDeliveryCheckpointFixture(db);introduced.filterKeys{it>version}.values.flatten().forEach{db.execSQL("DROP TABLE $it")};db.version=version
            }
            store=open();assertEquals(GameMoney(1000),ok(store.balance()));assertEquals(car.copy(revision=Revision(1)),ok(store.listOwnedVehicles()).single())
            assertNull(ok(store.repairSettlement(RequestId("not-committed"))));store.close();raw{assertEquals(12,it.version)}
        };store=open()
    }
    fun testFailedV6MigrationPreservesOriginalVersionAndBytes() {
        store.close();raw{it.version=6};val file=context.getDatabasePath(name);val bytes=file.readBytes();store=open()
        assertEquals(failure(ContractError.STORAGE_FAILURE),store.balance());store.close();assertTrue(bytes.contentEquals(file.readBytes()));raw{assertEquals(6,it.version)};store=open()
    }
}
