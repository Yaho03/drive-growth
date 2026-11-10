package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRepair
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 같은 DB의 실제 SQLite 격리 시험. helper 연결 전이므로 checkpoint 표만 setUp에서 명시 생성한다. */
@Suppress("DEPRECATION")
class HostCommandCheckpointLedgerTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val origin=InputSource.SIMULATION
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private lateinit var content:GameContentSnapshot
    private var loadedProfile:ProfileId?=null
    private val profile get()=checkNotNull(loadedProfile)
    private val vehicle=OwnedVehicle(OwnedVehicleId("car"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(500),VehicleAcquisitionSource.GACHA)
    private val material=vehicle.copy(ownedVehicleId=OwnedVehicleId("material"))
    private fun open(provide:Boolean=true)=SqliteGameChangeStore(context,origin,name,
        gachaAuthority=if(provide)({ContractResult.Success(GachaAuthority(origin,content.catalog.contentVersion,content.ruleVersion,1,3,GameMoney(500),30,Rarity.RARE,setOf(vehicle.vehicleId),Rarity.entries.toSet()))})else null,
        initialGachaProgress=GachaProgress(Revision(0),0,1),
        materialVersions=if(provide)({ContractResult.Success(MaterialVersions(content.catalog.contentVersion,content.ruleVersion))})else null,
        repairContent=if(provide)({ContractResult.Success(content)})else null)
    private fun ledger()=HostCommandCheckpointLedger(store)
    private fun <T> ok(r:ContractResult<T>):T{assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    private fun fail(e:ContractError)=ContractResult.Failure(e)
    private fun garage(id:String="upgrade")=HostCheckpointCommand.Garage(content.catalog.contentVersion,
        GameChange(RequestId(id),TransactionKind.GARAGE,origin,content.ruleVersion,MoneyChange(MoneyDirection.DEBIT,GameMoney(10)),
            VehicleWrite.Update(vehicle.ownedVehicleId,Revision(0),2,VehicleLifecycle.OWNED,0)))
    private fun gacha()=HostCheckpointCommand.Gacha(GachaCommand(RequestId("draw"),origin,content.catalog.contentVersion,content.ruleVersion,Revision(0),1,3,0,GachaTicketUse.PURCHASED,
        GachaAward(vehicle.copy(ownedVehicleId=OwnedVehicleId("awarded")),1,false)))
    private fun material()=HostCheckpointCommand.Material(MaterialCommand(RequestId("upgrade-material"),origin,content.catalog.contentVersion,content.ruleVersion,
        VehicleWrite.Update(vehicle.ownedVehicleId,Revision(0),2,VehicleLifecycle.OWNED,0),VehicleWrite.Update(material.ownedVehicleId,Revision(0),1,VehicleLifecycle.CONSUMED,0),GameMoney(0)))
    private fun repair()=HostCheckpointCommand.Repair(RepairCommand(RequestId("repair"),origin,content.catalog.contentVersion,content.ruleVersion,VehicleRef(vehicle.ownedVehicleId,Revision(0)),1,GameMoney(50)))
    private fun reopen(provide:Boolean=true){store.close();store=open(provide)}
    private fun raw(block:(SQLiteDatabase)->Unit){SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(block)}
    override fun setUp(){super.setUp();name="host-checkpoint-"+System.nanoTime()+".db";content=(AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot;store=open()
        loadedProfile=ok(store.localProfileId());ok(store.atomic{db->db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='host_command_checkpoint'",null).use{if(!it.moveToFirst())HostCommandCheckpointLedger.createTables(db)};ContractResult.Success(Unit)})
        ok(store.commitGameChange(GameChange(RequestId("fund"),TransactionKind.GARAGE,origin,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)),VehicleWrite.Add(vehicle))))
        ok(store.commitGameChange(GameChange(RequestId("material-fund"),TransactionKind.GARAGE,origin,content.ruleVersion,vehicle=VehicleWrite.Add(material))))}
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    fun testAllFourWholeCommandsRoundTripAndUnstartedCancel(){
        for(c in listOf(garage(),gacha(),material(),repair())){
            assertNull(ok(ledger().load(profile)));val saved=ok(ledger().save(profile,c));assertEquals(HostCheckpointPhase.SAVED,saved.phase)
            reopen();assertEquals(saved,ok(ledger().load(profile)));assertEquals(saved,ok(ledger().save(profile,c)))
            ok(ledger().cancelUnstarted(profile,c));assertNull(ok(ledger().load(profile)))
        };assertEquals(GameMoney(1000),ok(store.balance()));assertEquals(2,ok(store.listOwnedVehicles()).size)
    }
    fun testSaveFailureDoesNotAllowExecutionOrMoneyChange(){
        val c=garage();store.beforeCommit={throw SQLiteException("save abort")};assertEquals(fail(ContractError.STORAGE_FAILURE),ledger().save(profile,c));store.beforeCommit=null
        assertNull(ok(ledger().load(profile)));assertEquals(fail(ContractError.DATA_UNAVAILABLE),ledger().execute(profile,c));assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testNoSaveCannotExecuteAndUnconfirmedResultCannotClear(){
        val c=garage();assertEquals(fail(ContractError.DATA_UNAVAILABLE),ledger().execute(profile,c));ok(ledger().save(profile,c))
        assertEquals(fail(ContractError.STATE_CONFLICT),ledger().finish(profile,c));assertNotNull(ok(ledger().load(profile)));assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testCommitThenReopenBeforeClearReplaysPastResultAndPreservesNewState(){
        val c=garage();ok(ledger().save(profile,c));val original=ok(ledger().execute(profile,c));assertEquals(GameMoney(990),ok(store.balance()))
        reopen(false);assertEquals(HostCheckpointPhase.STARTED,ok(ledger().load(profile))!!.phase)
        assertEquals(fail(ContractError.STATE_CONFLICT),ledger().cancelUnstarted(profile,c))
        ok(store.commitGameChange(GameChange(RequestId("later"),TransactionKind.GARAGE,origin,content.ruleVersion,MoneyChange(MoneyDirection.DEBIT,GameMoney(5)),VehicleWrite.Update(vehicle.ownedVehicleId,Revision(1),3,VehicleLifecycle.OWNED,0))))
        assertEquals(original,ok(ledger().execute(profile,c)));assertEquals(original,ok(ledger().finish(profile,c)));assertNull(ok(ledger().load(profile)))
        assertEquals(GameMoney(985),ok(store.balance()));assertEquals(3,ok(store.listOwnedVehicles()).single{it.ownedVehicleId==vehicle.ownedVehicleId}.level)
    }
    fun testGachaCommitReopenWithoutCurrentAuthorityDoesNotRepayOrRedraw(){
        val c=gacha();ok(ledger().save(profile,c));val original=ok(ledger().execute(profile,c));reopen(false)
        assertEquals(original,ok(ledger().execute(profile,c)));assertEquals(original,ok(ledger().finish(profile,c)))
        assertEquals(GameMoney(500),ok(store.balance()));assertEquals(3,ok(store.listOwnedVehicles()).size)
    }
    fun testMaterialCommitReopenWithoutCurrentVersionsReplaysBothSnapshots(){
        val c=material();ok(ledger().save(profile,c));val original=ok(ledger().execute(profile,c));reopen(false)
        assertEquals(original,ok(ledger().execute(profile,c)));assertEquals(original,ok(ledger().finish(profile,c)))
        assertEquals(1,ok(store.listOwnedVehicles()).size);assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testRepairCommitReopenWithoutCurrentQuoteReplaysOriginal(){
        val rental=RequestId("damaged")
        ok(store.rental.assign(RentalRequest(rental,vehicle.vehicleId,1),content.catalog,mapOf(vehicle.vehicleId to RentalCategory.SMALL),mapOf(vehicle.vehicleId to GameMoney(0)),RentalTerms(content.ruleVersion,SimulatedCustomer(30,36),0,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)))
        ok(store.rental.account(rental,EpochMillis(0),EpochMillis(3600000),null));ok(store.rental.settle(rental));ok(store.rental.finishReturn(rental,EpochMillis(4500000)))
        val c=HostCheckpointCommand.Repair(ok(VehicleRepair(content).prepare(store,RequestId("repair-real"),origin,VehicleRef(vehicle.ownedVehicleId,Revision(1)))))
        ok(ledger().save(profile,c));val original=ok(ledger().execute(profile,c));reopen(false)
        assertEquals(original,ok(ledger().execute(profile,c)));assertEquals(original,ok(ledger().finish(profile,c)))
        assertEquals(GameMoney(950),ok(store.balance()));assertEquals(1,ok(store.rental.accidentCount(vehicle.ownedVehicleId)))
    }
    fun testDifferentPendingAndSameIdChangedInputCannotOverwriteOrClear(){
        val c=garage();val saved=ok(ledger().save(profile,c));val changed=c.copy(value=c.value.copy(money=MoneyChange(MoneyDirection.DEBIT,GameMoney(11))))
        for(other in listOf(garage("other"),changed,gacha())){
            assertEquals(fail(ContractError.STATE_CONFLICT),ledger().save(profile,other));assertEquals(fail(ContractError.STATE_CONFLICT),ledger().execute(profile,other))
            assertEquals(fail(ContractError.STATE_CONFLICT),ledger().cancelUnstarted(profile,other));assertEquals(saved,ok(ledger().load(profile)))
        };assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testWrongProfileAndSourceCannotWrite(){
        val c=garage();assertEquals(fail(ContractError.INVALID_INPUT),ledger().save(ProfileId("other"),c))
        assertEquals(fail(ContractError.INVALID_INPUT),ledger().save(profile,c.copy(value=c.value.copy(source=InputSource.VEHICLE))))
        assertNull(ok(ledger().load(profile)));assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testClearFailureRollsBackOnlyCheckpointRemovalAndDoesNotRecharge(){
        val c=garage();ok(ledger().save(profile,c));val original=ok(ledger().execute(profile,c))
        ok(store.atomic{it.execSQL("CREATE TRIGGER reject_checkpoint_clear BEFORE DELETE ON host_command_checkpoint BEGIN SELECT RAISE(ABORT,'clear'); END");ContractResult.Success(Unit)})
        assertEquals(fail(ContractError.STORAGE_FAILURE),ledger().finish(profile,c));assertNotNull(ok(ledger().load(profile)));assertEquals(GameMoney(990),ok(store.balance()))
        ok(store.atomic{it.execSQL("DROP TRIGGER reject_checkpoint_clear");ContractResult.Success(Unit)});assertEquals(original,ok(ledger().finish(profile,c)))
    }
    fun testNestedCommitFailureRollsBackStartedAndMoney(){
        val c=garage();ok(ledger().save(profile,c));store.beforeCommit={throw SQLiteException("start abort")}
        assertEquals(fail(ContractError.STORAGE_FAILURE),ledger().execute(profile,c));store.beforeCommit=null
        assertEquals(HostCheckpointPhase.SAVED,ok(ledger().load(profile))!!.phase);assertEquals(GameMoney(1000),ok(store.balance()));ok(ledger().cancelUnstarted(profile,c))
    }
    fun testConfirmedInsufficientFundsRestoresSavedAndCanBeExplicitlyCancelled(){
        val c=garage().copy(value=garage().value.copy(money=MoneyChange(MoneyDirection.DEBIT,GameMoney(1001))))
        ok(ledger().save(profile,c));assertEquals(fail(ContractError.INSUFFICIENT_FUNDS),ledger().execute(profile,c))
        assertEquals(fail(ContractError.STATE_CONFLICT),ledger().finish(profile,c));assertEquals(HostCheckpointPhase.SAVED,ok(ledger().load(profile))!!.phase)
        ok(ledger().cancelUnstarted(profile,c));ok(ledger().save(profile,garage("new")));assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testCommittedForeignRequestCannotCreateNewCheckpoint(){
        val c=garage();ok(store.commitGameChange(c.value));assertEquals(fail(ContractError.STATE_CONFLICT),ledger().save(profile,c));assertNull(ok(ledger().load(profile)))
    }
    fun testCorruptEnvelopeAndRawNumbersPreserveDatabaseBytes(){
        val c=garage();ok(ledger().save(profile,c));store.close();var original=""
        raw{db->db.rawQuery("SELECT payload FROM host_command_checkpoint",null).use{it.moveToFirst();original=it.getString(0)}}
        for(bad in listOf(original.replaceFirst("\"profile\":\"${profile.value}\"","\"profile\":\"other\""),
            original.replaceFirst("\"source\":\"SIMULATION\"","\"source\":\"VEHICLE\""),
            original.replaceFirst("\"request\":\"upgrade\"","\"request\":\"other\""),
            original.replaceFirst("\"amount\":10","\"amount\":1.5"),original.replaceFirst("\"amount\":10","\"amount\":\"10\""),
            original.replaceFirst("\"revision\":0","\"revision\":4294967296"),original.replaceFirst("\"kind\":\"garage\"","\"kind\":\"future\""))) {
            assertTrue(bad!=original);raw{it.execSQL("UPDATE host_command_checkpoint SET payload=?",arrayOf(bad))};val file=context.getDatabasePath(name);val bytes=file.readBytes();store=open()
            assertEquals(fail(ContractError.STORAGE_FAILURE),ledger().load(profile));assertEquals(fail(ContractError.STORAGE_FAILURE),ledger().save(profile,c));assertEquals(fail(ContractError.STORAGE_FAILURE),ledger().execute(profile,c))
            store.close();assertTrue(bytes.contentEquals(file.readBytes()))
        };raw{it.execSQL("UPDATE host_command_checkpoint SET payload=?,digest=?",arrayOf(original,HostCheckpointJson.digest(original,0)))};store=open();assertNotNull(ok(ledger().load(profile)))
    }
    private fun race(a:()->ContractResult<*>,b:()->ContractResult<*>):List<ContractResult<*>>{
        val pool=Executors.newFixedThreadPool(2);val gate=CountDownLatch(1)
        try{val jobs=listOf(a,b).map{f->pool.submit(Callable{gate.await();f()})};gate.countDown();return jobs.map{it.get(10,TimeUnit.SECONDS)}}finally{pool.shutdownNow()}
    }
    fun testTwoConnectionsDifferentPendingHaveOneWinner(){
        val other=open();try{val results=race({ledger().save(profile,garage())},{HostCommandCheckpointLedger(other).save(profile,gacha())})
            assertEquals(1,results.count{it is ContractResult.Success});assertEquals(1,results.count{it==fail(ContractError.STATE_CONFLICT)});assertEquals(GameMoney(1000),ok(store.balance()))
        }finally{other.close()}
    }
    fun testTwoConnectionsSameCommandHaveOneChargeAndPending(){
        val other=open();try{val c=garage();race({ledger().save(profile,c)},{HostCommandCheckpointLedger(other).save(profile,c)}).forEach{ok(it)}
            val results=race({ledger().execute(profile,c)},{HostCommandCheckpointLedger(other).execute(profile,c)});assertEquals(ok(results[0]),ok(results[1]))
            assertEquals(GameMoney(990),ok(store.balance()));assertNotNull(ok(ledger().load(profile)))
        }finally{other.close()}
    }
    fun testNpcAssignmentAfterSaveRejectsUpgradeAndExplicitCancelUnlocksSlot(){
        val c=garage();val saved=ok(ledger().save(profile,c))
        ok(store.rental.assign(RentalRequest(RequestId("npc"),vehicle.vehicleId,1),content.catalog,
            mapOf(vehicle.vehicleId to RentalCategory.SMALL),mapOf(vehicle.vehicleId to GameMoney(100)),
            RentalTerms(content.ruleVersion,SimulatedCustomer(30,36),99,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)))
        val money=ok(store.balance());val cars=ok(store.listVehicleHistory());val receipts=ok(store.listUnviewedSettlements());val pending=ok(store.rental.pending())
        assertEquals(fail(ContractError.STATE_CONFLICT),ledger().execute(profile,c))
        assertEquals(saved,ok(ledger().load(profile)));assertEquals(money,ok(store.balance()));assertEquals(cars,ok(store.listVehicleHistory()));assertEquals(receipts,ok(store.listUnviewedSettlements()));assertEquals(pending,ok(store.rental.pending()))
        ok(ledger().cancelUnstarted(profile,c));assertNull(ok(ledger().load(profile)));ok(ledger().save(profile,garage("new-after-npc")))
    }

    fun testPriorStartedWithoutReceiptRemainsRecoveryRequired(){
        val c=garage().copy(value=garage().value.copy(money=MoneyChange(MoneyDirection.DEBIT,GameMoney(1001))))
        ok(ledger().save(profile,c));ok(store.atomic{it.execSQL("UPDATE host_command_checkpoint SET phase=1,digest=?",arrayOf(HostCheckpointJson.digest(HostCheckpointJson.encode(profile,c),1)));ContractResult.Success(Unit)})
        assertEquals(fail(ContractError.INSUFFICIENT_FUNDS),ledger().execute(profile,c))
        assertEquals(HostCheckpointPhase.STARTED,ok(ledger().load(profile))!!.phase)
        assertEquals(fail(ContractError.STATE_CONFLICT),ledger().cancelUnstarted(profile,c));assertEquals(fail(ContractError.DATA_UNAVAILABLE),ledger().finish(profile,c))
        assertEquals(fail(ContractError.STATE_CONFLICT),ledger().save(profile,garage("different")));assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testMissingRealProfileNeverCreatesReplacementIdOrClearsPending(){
        val c=garage();ok(ledger().save(profile,c));ok(store.atomic{it.execSQL("DELETE FROM initialization_profile");ContractResult.Success(Unit)})
        assertEquals(fail(ContractError.STORAGE_FAILURE),ledger().load(profile));assertEquals(fail(ContractError.STORAGE_FAILURE),ledger().execute(profile,c))
        assertEquals(GameMoney(1000),ok(store.balance()))
        store.close();raw{db->db.rawQuery("SELECT count(*) FROM host_command_checkpoint",null).use{it.moveToFirst();assertEquals(1,it.getInt(0))}};store=open()
    }

    fun testValidAmountOrPhaseMutationWithoutDigestIsStorageFailure(){
        val c=garage();ok(ledger().save(profile,c));store.close()
        raw{db->db.rawQuery("SELECT payload FROM host_command_checkpoint",null).use{it.moveToFirst();val changed=it.getString(0).replaceFirst("\"amount\":10","\"amount\":11");db.execSQL("UPDATE host_command_checkpoint SET payload=?",arrayOf(changed))}}
        store=open();assertEquals(fail(ContractError.STORAGE_FAILURE),ledger().load(profile));store.close()
        raw{it.execSQL("UPDATE host_command_checkpoint SET payload=?,phase=1",arrayOf(HostCheckpointJson.encode(profile,c)))}
        store=open();assertEquals(fail(ContractError.STORAGE_FAILURE),ledger().load(profile));assertEquals(GameMoney(1000),ok(store.balance()))
    }

}
