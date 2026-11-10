package io.drivegrowth.app.host.facility

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.*
import io.drivegrowth.core.rental.*
import java.util.UUID
import java.util.concurrent.Executor

@Suppress("DEPRECATION")
class CheckpointFacilityFactoryTest:InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private lateinit var names:Map<InputSource,String>
    private lateinit var games:GameSessionFactory
    private lateinit var source:FactoryFacilityHostSource
    private fun <T> ok(r:ContractResult<T>):T{assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    override fun setUp(){super.setUp();val prefix="typed-facility-${UUID.randomUUID()}";names=InputSource.entries.associateWith{"$prefix-${it.name}.db"};games=GameSessionFactory(context,names,InputSource.SIMULATION)
        val onboarding=StoredOnboardingHostSource(games);val id=ok(onboarding.read()).identity;ok(onboarding.choose(id,FirstGrant.CANDIDATES.first()));source=FactoryFacilityHostSource(context,games,CheckpointFacilityDurablePort())
    }
    override fun tearDown(){names.values.forEach(context::deleteDatabase);super.tearDown()}
    private fun raw(action:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(names.getValue(InputSource.SIMULATION)).path,null,SQLiteDatabase.OPEN_READWRITE).use(action)
    private fun rows()=buildList{raw{db->for(t in listOf("account","vehicles","settlements","shop","facilities","rental_rest","host_command_checkpoint","facility_command_results"))db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{c->while(c.moveToNext())add(t+":"+(0 until c.columnCount).joinToString("|"){if(c.isNull(it))"<null>" else c.getString(it)})}}}
    private fun rest(id:String="on",expected:Boolean=false,desired:Boolean=true):FacilityCommand.Rest{val f=ok(source.read());val v=f.vehicles.single();return FacilityCommand.Rest(f.identity,RequestId(id),VehicleRef(v.ownedVehicleId,v.revision),expected,desired)}
    private fun apply(c:FacilityCommand):FacilityReceipt{ok(source.save(c));return ok(source.execute(c))}
    fun testOldOnPublicSaveReplaysAfterOffAndGrowthWithoutChangingCurrentState() {
        val on=rest();val receipt=apply(on);ok(source.finish(on));val off=rest("off",true,false);apply(off);ok(source.finish(off))
        ok(games.open()).use{g->val v=ok(g.store.listOwnedVehicles()).single();ok(g.store.commitGameChange(GameChange(RequestId("growth"),TransactionKind.GARAGE,g.identity.source,RuleVersion("fixture"),null,VehicleWrite.Update(v.ownedVehicleId,v.revision,2,VehicleLifecycle.OWNED,0))))}
        source=FactoryFacilityHostSource(context,games,CheckpointFacilityDurablePort());val before=rows();val current=ok(source.read());assertTrue(current.resting.isEmpty());assertEquals(Revision(1),current.vehicles.single().revision)
        assertEquals(receipt,apply(on));ok(source.finish(on));assertEquals(before,rows());assertTrue(ok(source.read()).resting.isEmpty());assertEquals(GameMoney(1000),ok(source.read()).balance)
        raw{db->db.rawQuery("SELECT count(*) FROM settlements WHERE request_id IN ('on','off')",null).use{it.moveToFirst();assertEquals(0,it.getInt(0))}}
    }
    fun testPurchaseUsesActualCentralOfferAndOriginalReceiptOnlyOnce() {
        raw{it.execSQL("UPDATE shop SET level=3,capacity=7")};val f=ok(source.read());val c=FacilityCommand.Buy(f.identity,RequestId("buy"),f.definitionVersion,f.offers.getValue(Facility.CAR_WASH))
        val result=apply(c) as FacilityReceipt.Purchased;assertEquals(GameMoney(0),result.settlement.balance);ok(source.finish(c));val before=rows()
        assertEquals(result,apply(c));ok(source.finish(c));assertEquals(before,rows());assertEquals(setOf(Facility.CAR_WASH),ok(source.read()).shop.facilities)
    }
    fun testSavedAndStartedReopenRecoverSameCommandAndAcknowledgeActualResult() {
        val c=rest();ok(source.save(c));assertEquals(FacilityPending.Own(c),ok(source.pending()))
        val controller=FacilityHostController(FactoryFacilityHostSource(context,games,CheckpointFacilityDurablePort()),Executor{it.run()},Executor{it.run()});controller.setControlsEnabled(true)
        assertEquals(c,controller.state.pending);assertEquals(FacilityReceipt.Rested(c,false,true),controller.state.receipt);controller.release()
        val reopened=FacilityHostController(source,Executor{it.run()},Executor{it.run()});reopened.setControlsEnabled(true);assertEquals(c,reopened.state.receipt!!.command);assertTrue(reopened.acknowledge());assertEquals(FacilityPending.Empty,ok(source.pending()));assertTrue(ok(source.read()).resting.contains(c.vehicle.ownedVehicleId))
    }
    fun testForeignSlotIsNotExecutedOrCancelledAndSavedOwnCanCancel() {
        val f=ok(source.read());val car=f.vehicles.single()
        val foreign=HostCheckpointCommand.Garage(f.definitionVersion,GameChange(RequestId("foreign"),TransactionKind.GARAGE,f.identity.source,RuleVersion("fixture"),null,VehicleWrite.Update(car.ownedVehicleId,car.revision,car.level,VehicleLifecycle.OWNED,car.levelProgressPercent)))
        ok(games.open()).use{ok(it.store.hostCommands.save(it.identity.profileId,foreign))};val before=rows();assertEquals(FacilityPending.Foreign,ok(source.pending()))
        val host=FacilityHostController(source,Executor{it.run()},Executor{it.run()});host.setControlsEnabled(true);assertTrue(host.state.foreign);assertFalse(host.cancelSaved());assertEquals(before,rows())
        ok(games.open()).use{ok(it.store.hostCommands.cancelUnstarted(it.identity.profileId,foreign))};val own=rest();ok(source.save(own));ok(source.cancelSaved(own));assertEquals(FacilityPending.Empty,ok(source.pending()));assertTrue(ok(source.read()).resting.isEmpty())
    }
    fun testMissingStartedOriginalAndFailedFinishPreservePendingAndRows() {
        val c=rest();apply(c);val started=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),source.cancelSaved(c));assertEquals(started,rows());raw{it.execSQL("CREATE TRIGGER reject_finish BEFORE DELETE ON host_command_checkpoint BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),source.finish(c));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER reject_finish");it.execSQL("DELETE FROM facility_command_results")};val damaged=rows()
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),source.execute(c));assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),source.finish(c));assertEquals(FacilityPending.Own(c),ok(source.pending()));assertEquals(damaged,rows())
    }
    fun testRestDuringActualRentalPreservesActivityVehicleAndMoney() {
        val f=ok(source.read());val car=f.vehicles.single()
        ok(games.open()).use{g->
            val catalog=VehicleCatalogSnapshot(f.definitionVersion,listOf(VehicleDefinition(car.vehicleId,"시험 차량")))
            ok(g.store.rental.assign(RentalRequest(RequestId("active-rental"),car.vehicleId,1),catalog,mapOf(car.vehicleId to RentalCategory.SMALL),mapOf(car.vehicleId to GameMoney(100)),RentalTerms(RuleVersion("fixture"),SimulatedCustomer(30,48),99,EpochMillis(0),15,GameMoney(0),java.math.RoundingMode.DOWN,false)))
        }
        val active=ok(source.read());assertEquals(io.drivegrowth.core.progression.VehicleActivity.RENTED,active.activities[car.ownedVehicleId]);val c=rest();apply(c);ok(source.finish(c));val after=ok(source.read())
        assertEquals(active.balance,after.balance);assertEquals(active.vehicles,after.vehicles);assertEquals(active.activities,after.activities);assertTrue(car.ownedVehicleId in after.resting)
    }
    fun testConfirmedRejectionAndForeignIdentityCannotMutateOrLeaveNewSlot() {
        val c=rest();val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),source.save(c.copy(vehicle=c.vehicle.copy(revision=Revision(9)))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),source.save(c.copy(identity=c.identity.copy(profileId=ProfileId("foreign")))))
        assertEquals(before,rows());assertEquals(FacilityPending.Empty,ok(source.pending()));assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
}
