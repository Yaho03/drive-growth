package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*
import org.json.JSONObject
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 실제 공용 helper 연결 patch 적용 후 격리 DB에서 실행한다. 이번 작성 단계에서는 adb 실행하지 않는다. */
@Suppress("DEPRECATION")
class InitializationLedgerTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private val source=InputSource.SIMULATION
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private lateinit var ledger:InitializationLedger
    private lateinit var content:GameContentSnapshot
    private lateinit var supplied:ContractResult<InitializationAuthority>
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return (r as ContractResult.Success).value}
    private fun failure(e:ContractError)=ContractResult.Failure(e)
    private fun open() {store=SqliteGameChangeStore(context,source,name);ledger=InitializationLedger(store,{supplied})}
    override fun setUp() {
        super.setUp();name="initialization-test-${UUID.randomUUID()}.db"
        content=(AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        supplied=ContractResult.Success(InitializationAuthority(source,content,FirstGrant.CANDIDATES.toSet()));open()
    }
    override fun tearDown() {store.close();assertTrue(name.startsWith("initialization-test-"));context.deleteDatabase(name);super.tearDown()}
    private fun reopen() {store.close();open()}
    private fun profile()=ok(ledger.profileId())
    private fun state()=ok(ledger.readInitialization(profile()))
    private fun raw(block:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun command(chosen:VehicleId=VehicleId("hyundai_casper")):InitializationCommand {
        val s=state();val proof=(s.status as ProfileInitializationStatus.New).evidence
        val car=OwnedVehicle(OwnedVehicleId("initial-grant"),chosen,1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT)
        return InitializationCommand(s.profileId,proof,s.contentVersion,GameChange(FirstGrant.REQUEST_ID,TransactionKind.GARAGE,source,s.ruleVersion,
            MoneyChange(MoneyDirection.CREDIT,FirstGrant.INITIAL_MONEY),VehicleWrite.Add(car)),FirstGrant.INITIAL_GARAGE_CAPACITY)
    }
    private fun snapshot():List<String> {
        val values=mutableListOf<String>()
        raw { db -> for ((table,columns) in listOf("account" to "balance,source","vehicles" to "owned_id,payload","shop" to "level,capacity",
            "initialization_profile" to "profile_id,source,proof_id,proof_revision,marker_request","initialization_requests" to "request_id,profile_id,command,original_state,result",
            "settlements" to "request_id,settlement_id,command,result")) {
            db.rawQuery("SELECT $columns FROM $table ORDER BY 1",null).use { row -> while(row.moveToNext()) values.add(table+":"+(0 until row.columnCount).joinToString("|"){if(row.isNull(it))"<null>" else row.getString(it)}) }
        }}
        return values
    }
    fun testActualNewDatabaseProofSurvivesReopenAndCannotBeReissued() {
        val p=profile();val before=state();assertTrue(before.status is ProfileInitializationStatus.New)
        reopen();assertEquals(p,profile());assertEquals(before.status,state().status)
        raw { db -> db.beginTransaction();try {try {InitializationLedger.onNewDatabase(db,source);fail("기존 DB에서 NEW를 다시 발급하면 안 됨")} catch(_:SQLiteException) {}}finally {db.endTransaction()} }
        assertEquals(before.status,state().status)
    }
    fun testFirstGrantAdapterCommitReopenAndOriginalReplay() {
        val p=profile();val proof=(state().status as ProfileInitializationStatus.New).evidence
        val first=ok(commitFirstGrantInitialization(ledger,proof,content,VehicleId("hyundai_casper"),3))
        assertEquals(GameMoney(1000),first.grant.balance);assertEquals(3,ok(store.rental.shop()).capacity)
        assertEquals(first.grant,ok(store.listUnviewedSettlements()).single());assertEquals(first.grant.changedVehicle,ok(store.listOwnedVehicles()).single())
        reopen();assertEquals(p,profile());assertEquals(first,(state().status as ProfileInitializationStatus.Existing).original)
        assertEquals(first,ok(ledger.commitInitialization(first.command)))
    }
    fun testReplayPrecedesUnavailableChangedContentGrowthSaleAndBalance() {
        val c=command();val first=ok(ledger.commitInitialization(c));val car=first.grant.changedVehicle!!
        ok(store.commitGameChange(GameChange(RequestId("grow"),TransactionKind.GARAGE,source,content.ruleVersion,
            vehicle=VehicleWrite.Update(car.ownedVehicleId,car.revision,2,VehicleLifecycle.OWNED,0))))
        ok(store.commitGameChange(GameChange(RequestId("sale"),TransactionKind.GARAGE,source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(9)),
            VehicleWrite.Update(car.ownedVehicleId,Revision(1),2,VehicleLifecycle.SOLD,0))))
        supplied=failure(ContractError.DATA_UNAVAILABLE);reopen()
        assertEquals(first,ok(ledger.commitInitialization(c)));assertEquals(GameMoney(1009),ok(store.balance()))
        assertTrue(ok(store.listOwnedVehicles()).isEmpty());assertTrue(state().status is ProfileInitializationStatus.Existing)
        assertEquals(failure(ContractError.STATE_CONFLICT),ledger.commitInitialization(c.copy(contentVersion=ContentVersion("new"))))
    }
    fun testLegacyVersionSevenFixtureGetsUnknownEvenWhenEmpty() {
        profile();store.close()
        raw { db -> db.execSQL("DROP TABLE initialization_requests");db.execSQL("DROP TABLE initialization_profile");db.execSQL("DROP TABLE first_gift_requests");db.execSQL("DROP TABLE first_gift_markers");listOf("quest_events","quest_claims","quest_tracking").forEach{db.execSQL("DROP TABLE $it")};db.execSQL("DROP TABLE host_command_checkpoint");db.version=7 }
        open();assertTrue(state().status is ProfileInitializationStatus.Unknown)
        assertEquals(GameMoney(0),ok(store.balance()));assertTrue(ok(store.listOwnedVehicles()).isEmpty())
        val s=state();val c=InitializationCommand(s.profileId,NewProfileEvidence(s.profileId,source,"not-issued-by-store",Revision(0)),s.contentVersion,
            GameChange(FirstGrant.REQUEST_ID,TransactionKind.GARAGE,source,s.ruleVersion,MoneyChange(MoneyDirection.CREDIT,FirstGrant.INITIAL_MONEY),
                VehicleWrite.Add(OwnedVehicle(OwnedVehicleId("initial-grant"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT))),3)
        val before=snapshot();assertEquals(failure(ContractError.DATA_UNAVAILABLE),ledger.commitInitialization(c));assertEquals(before,snapshot())
    }
    fun testProofSourceProfileCandidateAndVersionFailuresPreserveAllRows() {
        val c=command();val bad=listOf(c.copy(profileId=ProfileId("other")) to ContractError.INVALID_INPUT,
            c.copy(grant=c.grant.copy(source=InputSource.VEHICLE)) to ContractError.INVALID_INPUT,
            c.copy(evidence=c.evidence.copy(revision=Revision(1))) to ContractError.STATE_CONFLICT,
            c.copy(contentVersion=ContentVersion("old")) to ContractError.STATE_CONFLICT,
            c.copy(garageCapacity=4) to ContractError.STATE_CONFLICT)
        val before=snapshot()
        for((input,error) in bad) {assertEquals(failure(error),ledger.commitInitialization(input));assertEquals(before,snapshot())}
        supplied=ContractResult.Success(InitializationAuthority(source,content,emptySet()))
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),ledger.commitInitialization(c));assertEquals(before,snapshot())
    }
    fun testExistingDisposedIdentifierCannotBeReplaced() {
        val c=command();val old=(c.grant.vehicle as VehicleWrite.Add).vehicle.copy(lifecycle=VehicleLifecycle.SOLD,revision=Revision(9))
        profile();raw { db ->db.execSQL("INSERT INTO vehicles VALUES(?,?)",arrayOf(old.ownedVehicleId.value,LedgerJson.vehicle(old).toString())) }
        val before=snapshot();assertEquals(failure(ContractError.STATE_CONFLICT),ledger.commitInitialization(c));assertEquals(before,snapshot())
    }
    fun testCapacityUsesSharedShopEvenWithoutGachaProgress() {
        ok(ledger.commitInitialization(command()))
        for(i in 1..3) {
            val car=ok(store.listOwnedVehicles()).first().copy(ownedVehicleId=OwnedVehicleId("extra$i"))
            val input=GameChange(RequestId("extra$i"),TransactionKind.GARAGE,source,content.ruleVersion,vehicle=VehicleWrite.Add(car))
            if(i<3) ok(store.commitGameChange(input)) else assertEquals(failure(ContractError.CAPACITY_EXCEEDED),store.commitGameChange(input))
        }
        assertEquals(3,ok(store.rental.shop()).capacity);assertEquals(3,ok(store.listOwnedVehicles()).size)
    }
    fun testLaterWriteFailureRollsBackMoneyVehicleShopMarkerAndReceipt() {
        val c=command();val before=snapshot()
        raw { it.execSQL("CREATE TRIGGER initialization_abort BEFORE INSERT ON initialization_requests BEGIN SELECT RAISE(ABORT,'expected failure'); END") }
        assertEquals(failure(ContractError.STORAGE_FAILURE),ledger.commitInitialization(c));assertEquals(before,snapshot())
        raw { it.execSQL("DROP TRIGGER initialization_abort") }
        val first=ok(ledger.commitInitialization(c));assertEquals(first,ok(ledger.commitInitialization(c)))
    }
    fun testCommitBoundaryFailureIsAtomic() {
        val c=command();val before=snapshot();store.beforeCommit={throw SQLiteException("commit failure")}
        assertEquals(failure(ContractError.STORAGE_FAILURE),ledger.commitInitialization(c));store.beforeCommit=null
        assertEquals(before,snapshot());ok(ledger.commitInitialization(c))
    }
    fun testMissingFunctionHistoryAndMarkerAreNotNormalReplay() {
        val c=command();ok(ledger.commitInitialization(c));raw { it.execSQL("DELETE FROM initialization_requests") }
        val before=snapshot();assertEquals(failure(ContractError.STORAGE_FAILURE),ledger.commitInitialization(c));assertEquals(before,snapshot())
    }
    fun testMissingCommonReceiptIsStorageFailure() {
        val c=command();ok(ledger.commitInitialization(c));raw { it.execSQL("DELETE FROM settlements WHERE request_id=?",arrayOf(c.grant.requestId.value)) }
        val before=snapshot();assertEquals(failure(ContractError.STORAGE_FAILURE),ledger.commitInitialization(c));assertEquals(before,snapshot())
    }
    fun testMissingMarkerAndChangedReceiptPreserveCorruptOriginal() {
        val c=command();ok(ledger.commitInitialization(c))
        raw {it.execSQL("UPDATE initialization_profile SET marker_request=NULL")}
        var before=snapshot();assertEquals(failure(ContractError.STORAGE_FAILURE),ledger.commitInitialization(c));assertEquals(before,snapshot())
        raw {db ->db.execSQL("UPDATE initialization_profile SET marker_request=?",arrayOf(c.grant.requestId.value))
            db.rawQuery("SELECT result FROM settlements WHERE request_id=?",arrayOf(c.grant.requestId.value)).use { row ->
                row.moveToFirst();val changed=JSONObject(row.getString(0)).put("balance",999).toString()
                db.execSQL("UPDATE settlements SET result=? WHERE request_id=?",arrayOf(changed,c.grant.requestId.value))
            }}
        before=snapshot();assertEquals(failure(ContractError.STORAGE_FAILURE),ledger.commitInitialization(c));assertEquals(before,snapshot())
    }
    fun testFractionalTextAndOverflowNumbersAreNotCoerced() {
        val c=command();ok(ledger.commitInitialization(c));var original=""
        raw { db ->db.rawQuery("SELECT original_state FROM initialization_requests",null).use{it.moveToFirst();original=it.getString(0)} }
        for(value in listOf<Any>("1000",1000.5)) {
            val changed=JSONObject(original).put("initialMoney",value).toString()
            raw {it.execSQL("UPDATE initialization_requests SET original_state=?",arrayOf(changed))}
            val before=snapshot();assertEquals(failure(ContractError.STORAGE_FAILURE),ledger.commitInitialization(c));assertEquals(before,snapshot())
        }
        raw {it.execSQL("UPDATE initialization_requests SET original_state=?",arrayOf(original.replace("\"initialMoney\":1000","\"initialMoney\":9223372036854775808")))}
        val before=snapshot();assertEquals(failure(ContractError.STORAGE_FAILURE),ledger.commitInitialization(c));assertEquals(before,snapshot())
    }
    fun testSharedNamespacesRejectBothDirectionsWithoutMutation() {
        val c=command();val terms=RentalTerms(content.ruleVersion,SimulatedCustomer(30,36),0,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)
        ok(store.rental.assign(RentalRequest(FirstGrant.REQUEST_ID,VehicleId("hyundai_casper"),1),content.catalog,
            mapOf(VehicleId("hyundai_casper") to RentalCategory.SEDAN),mapOf(VehicleId("hyundai_casper") to GameMoney(0)),terms))
        val before=snapshot();assertEquals(failure(ContractError.STATE_CONFLICT),ledger.commitInitialization(c));assertEquals(before,snapshot())
    }
    fun testInitializationFirstBlocksGenericMaterialRepairAndGachaIds() {
        val c=command();val result=ok(ledger.commitInitialization(c));val car=result.grant.changedVehicle!!;val before=snapshot()
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitGameChange(c.grant))
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitRepair(RepairCommand(FirstGrant.REQUEST_ID,source,c.contentVersion,c.grant.ruleVersion,VehicleRef(car.ownedVehicleId,car.revision),0,GameMoney(0))))
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitMaterial(MaterialCommand(FirstGrant.REQUEST_ID,source,c.contentVersion,c.grant.ruleVersion,
            VehicleWrite.Update(car.ownedVehicleId,car.revision,2,VehicleLifecycle.OWNED,0),VehicleWrite.Update(OwnedVehicleId("absent"),Revision(0),1,VehicleLifecycle.CONSUMED,0),GameMoney(0))))
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitGacha(GachaCommand(FirstGrant.REQUEST_ID,source,c.contentVersion,c.grant.ruleVersion,Revision(0),1,3,0,GachaTicketUse.FREE,
            GachaAward(car.copy(ownedVehicleId=OwnedVehicleId("draw"),acquisitionSource=VehicleAcquisitionSource.GACHA),1,false))))
        assertEquals(before,snapshot())
    }
    fun testTwoConnectionsDifferentChoicesCommitOnlyOne() {
        val first=command();val other=(first.grant.vehicle as VehicleWrite.Add).vehicle.copy(vehicleId=VehicleId("hyundai_avante"))
        val second=first.copy(grant=first.grant.copy(vehicle=VehicleWrite.Add(other)))
        val store2=SqliteGameChangeStore(context,source,name);val ledger2=InitializationLedger(store2,{supplied})
        val pool=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
        try {
            val tasks=listOf(pool.submit(Callable {start.await();ledger.commitInitialization(first)}),pool.submit(Callable{start.await();ledger2.commitInitialization(second)}))
            start.countDown();val results=tasks.map{it.get(10,TimeUnit.SECONDS)}
            assertEquals(1,results.count{it is ContractResult.Success});assertEquals(1,results.count{it==failure(ContractError.STATE_CONFLICT)})
            assertEquals(GameMoney(1000),ok(store.balance()));assertEquals(1,ok(store.listOwnedVehicles()).size)
        } finally {pool.shutdownNow();store2.close()}
    }
}
