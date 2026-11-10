package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.rental.*
import io.drivegrowth.core.progression.VehicleActivity
import java.math.RoundingMode

/** 같은 공용 slot/helper의 실제 SQLite 시험. 제품 UI/실차 실행과 구분한다. */
@Suppress("DEPRECATION")
class FacilityCheckpointTest:InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private val source=InputSource.SIMULATION
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private lateinit var definition:TrialDefinition
    private var issuedProfile:ProfileId?=null
    private val profile get()=checkNotNull(issuedProfile)
    private var available=true
    private val car=OwnedVehicle(OwnedVehicleId("rest-car"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT)
    private fun open()=SqliteGameChangeStore(context,source,name).apply{trialDefinitions=TrialDefinitionSource{if(available)ContractResult.Success(definition)else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)}}
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    override fun setUp() {
        super.setUp();name="facility-checkpoint-${System.nanoTime()}.db";definition=ok(AssetTrialDefinitionProvider(context.assets).load());store=open();issuedProfile=ok(store.localProfileId())
        ok(store.commitGameChange(GameChange(RequestId("fund"),TransactionKind.GARAGE,source,definition.rules,MoneyChange(MoneyDirection.CREDIT,GameMoney(10000)),VehicleWrite.Add(car))))
    }
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    private fun raw(f:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(f)
    private fun rows():List<String> = buildList{raw{db->for(t in listOf("account","vehicles","settlements","rentals","visits","shop","facilities","rental_rest","facility_command_results","host_command_checkpoint"))db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{r->while(r.moveToNext())add(t+":"+(0 until r.columnCount).joinToString("|"){if(r.isNull(it))"<null>"else r.getString(it)})}}}
    private fun buy(id:String="facility-buy")=HostCheckpointCommand.FacilityPurchase(RequestId(id),source,definition.version,definition.facilityOffers.getValue(Facility.CAR_WASH))
    private fun rest(id:String="rest-on",expected:Boolean=false,desired:Boolean=true)=HostCheckpointCommand.VehicleResting(RequestId(id),source,VehicleRef(car.ownedVehicleId,car.revision),expected,desired)
    private fun unlock(){raw{it.execSQL("UPDATE shop SET level=3,capacity=7")}} // 정상 확장 후 상태 fixture; 확장을 새로 구현하지 않는다.
    private fun execute(c:HostCheckpointCommand):HostCheckpointResult {ok(store.hostCommands.save(profile,c));return ok(store.hostCommands.execute(profile,c))}
    private fun finish(c:HostCheckpointCommand)=ok(store.hostCommands.finish(profile,c))
    fun testActualPurchaseUsesCentralOfferAndOriginalSettlementAfterReopenAndDefinitionFailure() {
        unlock();val c=buy();val result=execute(c) as HostCheckpointResult.FacilityPurchase;assertEquals(GameMoney(9000),result.value.balance);assertEquals(setOf(Facility.CAR_WASH),ok(store.rental.shop()).facilities)
        store.close();available=false;store=open();assertEquals(result,ok(store.hostCommands.execute(profile,c)));assertEquals(result,finish(c));assertEquals(result,ok(store.hostCommands.facilityResult(profile,c)))
        val before=rows();assertEquals(result,execute(c));finish(c);assertEquals(GameMoney(9000),ok(store.balance()));assertEquals(before,rows())
    }
    fun testLockedChangedOfferAndMissingDefinitionDoNotCreateSavedCommand() {
        val c=buy();val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.save(profile,c));assertEquals(before,rows());unlock()
        for(changed in listOf(c.copy(definitionVersion=ContentVersion("different")),c.copy(offer=c.offer.copy(price=GameMoney(1)))))assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.save(profile,changed))
        available=false;assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.hostCommands.save(profile,c));assertNull(ok(store.hostCommands.load(profile)))
    }
    fun testInsufficientMoneyIsConfirmedRejectionWithoutSlotOrInstallation() {
        unlock();ok(store.commitGameChange(GameChange(RequestId("spend"),TransactionKind.GARAGE,source,definition.rules,MoneyChange(MoneyDirection.DEBIT,GameMoney(10000)))))
        val before=rows();assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS),store.hostCommands.save(profile,buy()));assertEquals(before,rows())
    }
    fun testIgnoredCheckpointFacilityMoneyOrMetadataRollBackWholePurchase() {
        unlock();val c=buy();ok(store.hostCommands.save(profile,c))
        for((table,event) in listOf("host_command_checkpoint" to "UPDATE","facilities" to "INSERT","account" to "UPDATE","facility_command_results" to "INSERT")) {
            raw{it.execSQL("CREATE TRIGGER ignore_facility BEFORE $event ON $table BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
            assertEquals(table,ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,c));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER ignore_facility")}
        }
        assertTrue(ok(store.hostCommands.execute(profile,c)) is HostCheckpointResult.FacilityPurchase)
    }
    fun testAfterInsertFacilityResetCannotReturnSuccess() {
        unlock();val c=buy();ok(store.hostCommands.save(profile,c));raw{it.execSQL("CREATE TRIGGER reset_facility AFTER INSERT ON facilities BEGIN DELETE FROM facilities WHERE facility=NEW.facility; END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,c));assertEquals(before,rows())
    }
    fun testRestingIsNonfinancialAndOldOnReplayDoesNotOverwriteLaterOffOrGrowth() {
        val on=rest();val original=execute(on) as HostCheckpointResult.VehicleResting;assertEquals(false,original.value.before);assertEquals(true,original.value.after);finish(on)
        val off=rest("rest-off",true,false);execute(off);finish(off)
        ok(store.commitGameChange(GameChange(RequestId("grow"),TransactionKind.GARAGE,source,definition.rules,null,VehicleWrite.Update(car.ownedVehicleId,Revision(0),2,VehicleLifecycle.OWNED,0))))
        store.close();store=open();val before=rows();assertEquals(original,execute(on));finish(on);assertEquals(before,rows());assertTrue(ok(store.rental.restingVehicles()).isEmpty());assertEquals(GameMoney(10000),ok(store.balance()));assertEquals(Revision(1),ok(store.listOwnedVehicles()).single().revision)
        raw{db->db.rawQuery("SELECT COUNT(*) FROM settlements WHERE request_id IN ('rest-on','rest-off')",null).use{it.moveToFirst();assertEquals(0,it.getInt(0))}}
    }
    fun testStaleReferenceExpectedStateAndNoopAreRejectedWithoutMutation() {
        val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.save(profile,rest().copy(vehicle=VehicleRef(car.ownedVehicleId,Revision(1)))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.save(profile,rest(expected=true,desired=false)))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),store.hostCommands.save(profile,rest(desired=false)));assertEquals(before,rows())
    }
    fun testRestCanBeSetWhileRentedWithoutChangingActivityVehicleOrBalance() {
        val id=RequestId("active-rental");val catalog=VehicleCatalogSnapshot(definition.version,listOf(VehicleDefinition(car.vehicleId,"시험")))
        ok(store.rental.assign(RentalRequest(id,car.vehicleId,1),catalog,mapOf(car.vehicleId to RentalCategory.SMALL),mapOf(car.vehicleId to GameMoney(100)),RentalTerms(definition.rules,SimulatedCustomer(30,48),99,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN,false)))
        val actual=ok(store.listOwnedVehicles()).single();val c=rest().copy(vehicle=VehicleRef(actual.ownedVehicleId,actual.revision));execute(c);finish(c)
        assertEquals(VehicleActivity.RENTED,ok(store.rental.activity(car.ownedVehicleId)));assertEquals(actual,ok(store.listOwnedVehicles()).single());assertEquals(GameMoney(10000),ok(store.balance()))
    }
    fun testIgnoredRestInsertDeleteAndMetadataPreserveCurrentStateAndOriginalResult() {
        val on=rest();ok(store.hostCommands.save(profile,on));raw{it.execSQL("CREATE TRIGGER ignore_rest BEFORE INSERT ON rental_rest BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,on));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER ignore_rest")};ok(store.hostCommands.execute(profile,on));finish(on)
        val off=rest("off",true,false);ok(store.hostCommands.save(profile,off))
        for((table,event) in listOf("rental_rest" to "DELETE","facility_command_results" to "INSERT")) {
            raw{it.execSQL("CREATE TRIGGER ignore_rest BEFORE $event ON $table BEGIN SELECT RAISE(IGNORE); END")};val previous=rows()
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,off));assertEquals(previous,rows());raw{it.execSQL("DROP TRIGGER ignore_rest")}
        }
        ok(store.hostCommands.execute(profile,off));finish(off)
    }
    fun testOriginalLookupSeparatesTrueAbsenceForeignIdAndDamagedOwnResult() {
        assertNull(ok(store.hostCommands.facilityResult(profile,rest())));assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.facilityResult(profile,rest().copy(requestId=RequestId("fund"))))
        val c=rest();execute(c);raw{it.execSQL("UPDATE facility_command_results SET result='{}'")};val before=rows();assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.facilityResult(profile,c));assertEquals(before,rows())
    }
    fun testStartedWithoutOriginalNeverReexecutesAndFinishCannotCreateResult() {
        val c=rest();execute(c);raw{it.execSQL("DELETE FROM facility_command_results")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.hostCommands.execute(profile,c));assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.hostCommands.finish(profile,c));assertEquals(before,rows())
    }
    fun testSavedCancelAndForeignCommandNeverClearEachOther() {
        val c=rest();ok(store.hostCommands.save(profile,c));val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.save(profile,buy()));assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.cancelUnstarted(profile,buy()));assertEquals(before,rows());ok(store.hostCommands.cancelUnstarted(profile,c));assertNull(ok(store.hostCommands.load(profile)))
    }
    fun testIgnoredOrReinsertedCheckpointDeletionDoesNotConfirmFinish() {
        val c=rest();execute(c);raw{it.execSQL("CREATE TRIGGER ignore_finish BEFORE DELETE ON host_command_checkpoint BEGIN SELECT RAISE(IGNORE); END")};val before=rows();assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.finish(profile,c));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER ignore_finish")}
        raw{it.execSQL("CREATE TRIGGER reinsert_finish AFTER DELETE ON host_command_checkpoint BEGIN INSERT INTO host_command_checkpoint VALUES(OLD.id,OLD.payload,OLD.phase,OLD.digest); END")};val original=rows();assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.finish(profile,c));assertEquals(original,rows())
    }
    fun testSchema14UpgradePreservesWholeLedgerAndStartsWithNoNewTypedResults() {
        val before=rows().filterNot{it.startsWith("facility_command_results:")};store.close();raw{it.execSQL("DROP TABLE facility_command_results");it.version=14};store=open();ok(store.localProfileId());assertEquals(before,rows());raw{assertEquals(15,it.version)};assertNull(ok(store.hostCommands.facilityResult(profile,rest())))
    }
    fun testIndependentCompletedRestingRequestIdCannotBeReusedByGeneralMoneyCommand() {
        val command=rest("independent-shared-request");execute(command);finish(command);val before=rows()
        val different=GameChange(command.requestId,TransactionKind.GARAGE,source,definition.rules,
            MoneyChange(MoneyDirection.CREDIT,GameMoney(1)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.commitGameChange(different))
        assertEquals(before,rows())
    }

}
