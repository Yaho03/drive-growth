package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.shop.*
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.rental.*
import org.json.JSONObject
import java.math.RoundingMode

/** 실제 helper/profile/완료원장·asset·checkpoint를 사용한다. 제품 MainActivity/실차 시험이 아니다. */
@Suppress("DEPRECATION")
class ShopExpansionLiveSourceTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private val origin=InputSource.SIMULATION
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private lateinit var content:GameContentSnapshot
    private var loadedProfile:ProfileId?=null
    private val profile get()=checkNotNull(loadedProfile)
    private lateinit var definition:ContractResult<ShopExpansionDefinition>
    private var failAfterExpansion=false
    private fun <T> ok(r:ContractResult<T>):T{assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    private fun open():SqliteGameChangeStore {
        val s=SqliteGameChangeStore(context,origin,name,initializationAuthority={ContractResult.Success(InitializationAuthority(origin,content,FirstGrant.CANDIDATES.toSet()))})
        s.beforeCommit={
            if(failAfterExpansion && ok(s.read{db->db.rawQuery("SELECT level FROM shop WHERE id=1",null).use{it.moveToFirst();it.getInt(0)}})==2)
                throw SQLiteException("확장 실제 쓰기 뒤 실패")
        }
        return s
    }
    private fun source(p:ProfileId=profile, input:InputSource=origin)=StoredShopExpansionSource(context,input,p,::open,ShopExpansionDefinitionSource{definition})
    override fun setUp(){super.setUp();name="shop-live-${System.nanoTime()}.db"
        content=(AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        definition=AssetShopExpansionProvider(context.assets).load();store=open();loadedProfile=ok(store.localProfileId())
        ok(FirstGrant(content).choose(store,profile,VehicleId("hyundai_casper")))
    }
    override fun tearDown(){failAfterExpansion=false;store.close();context.deleteDatabase(name);super.tearDown()}
    private fun raw(block:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun rows():List<String> = buildList {raw{db->for(t in listOf("account","vehicles","rentals","visits","settlements","shop","host_command_checkpoint","initialization_profile","initialization_requests")){
        db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{r->while(r.moveToNext())add(t+":"+(0 until r.columnCount).joinToString("|"){if(r.isNull(it))"<null>"else r.getString(it)})}
    }}}
    private fun complete(index:Int){
        val car=ok(store.listOwnedVehicles()).single();val start=index*4_500_000L;val id=RequestId("shop-rental-$index")
        assertEquals(car.ownedVehicleId,ok(store.rental.assign(RentalRequest(id,car.vehicleId,1),content.catalog,
            mapOf(car.vehicleId to RentalCategory.SEDAN),mapOf(car.vehicleId to GameMoney(0)),
            RentalTerms(content.ruleVersion,SimulatedCustomer(30,36),99,EpochMillis(start),15,GameMoney(0),RoundingMode.DOWN))))
        ok(store.rental.account(id,EpochMillis(start),EpochMillis(start+3_600_000),null));ok(store.rental.settle(id));ok(store.rental.finishReturn(id,EpochMillis(start+4_500_000)))
    }
    private fun request(snapshot:ShopExpansionSnapshot,id:String="actual-expand")=ShopRequest(RequestId(id),
        ShopSelection(snapshot.screen.revision,snapshot.screen.expansion!!),"same-attempt")
    fun testActualAssetAndPreviewUseCompletedCountWithoutWriting() {
        val offers=ok(definition).offers
        assertEquals(listOf(3,10,25),offers.map{it.requiredCustomers});assertEquals(listOf(500L,1500L,3000L),offers.map{it.price.units})
        val before=rows();val snapshot=ok(source().read());assertEquals(before,rows())
        assertEquals(0,snapshot.completedRentals);assertEquals(ShopAvailability.LOCKED,snapshot.screen.expansion!!.availability)
        assertTrue(snapshot.screen.facilities.all{it.option.command==null && it.option.price==null})
        val ui=ShopInteraction({fail("미리보기/취소에서 명령을 보내면 안 된다")});ui.render(snapshot.screen,true)
        assertNull(ui.selection);assertEquals(before,rows());assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testThreeCompletedExpandThroughDurableSourceAndReplayOnce() {
        repeat(3){complete(it)};val adapter=source();val snapshot=ok(adapter.read());val before=ok(store.balance())
        val previewRows=rows()
        val ui=ShopInteraction({fail("선택/취소만으로 확정하면 안 된다")});ui.render(snapshot.screen,true)
        val selection=request(snapshot).selection;ui.select(selection);assertEquals(selection,ui.selection);assertTrue(ui.cancel())
        assertEquals(previewRows,rows())
        val command=ok(adapter.prepare(snapshot,request(snapshot)))
        assertEquals(before,ok(store.balance()));assertEquals(HostCheckpointPhase.SAVED,ok(store.hostCommands.load(profile))!!.phase)
        val result=ok(adapter.execute(command));assertEquals(GameMoney(500),result.balance)
        assertEquals(result,ok(adapter.execute(command)));assertEquals(HostCheckpointPhase.STARTED,ok(store.hostCommands.load(profile))!!.phase)
        assertEquals(result,ok(adapter.finish(command)));assertNull(ok(adapter.pending()))
        val after=ok(adapter.read());assertEquals(5,after.screen.capacity);assertEquals(2,after.screen.level);assertEquals(GameMoney(500),after.screen.balance)
    }
    fun testResponseLossReopensOriginalCommandBeforeUnavailableDefinition() {
        repeat(3){complete(it)};val adapter=source();val snapshot=ok(adapter.read());val command=ok(adapter.prepare(snapshot,request(snapshot)))
        val original=ok(adapter.execute(command));store.close();store=open();definition=ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val restored=source();assertEquals(command,ok(restored.pending()))
        assertEquals(original,ok(restored.execute(command)));assertEquals(original,ok(restored.finish(command)))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),restored.read())
        assertEquals(GameMoney(500),ok(store.balance()));assertEquals(5,ok(store.rental.shop()).capacity)
    }
    fun testChangedDefinitionBeforeSaveRejectsStaleSelectionWithoutCharge() {
        repeat(3){complete(it)};val adapter=source();val snapshot=ok(adapter.read());val before=rows()
        val text=context.assets.open("content/shop-expansions.csv").bufferedReader().use{it.readText()}.replace("2026-11-10-v1","2026-11-10-v2")
        definition=parseShopExpansionDefinition(text)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),adapter.prepare(snapshot,request(snapshot)))
        assertEquals(before,rows());assertNull(ok(adapter.pending()))
    }
    fun testMoneyRaceRejectsAndOnlyExplicitSavedCancellationClearsSlot() {
        repeat(3){complete(it)};val adapter=source();val snapshot=ok(adapter.read());val command=ok(adapter.prepare(snapshot,request(snapshot)))
        ok(store.commitGameChange(GameChange(RequestId("other-spend"),TransactionKind.GARAGE,origin,content.ruleVersion,
            MoneyChange(MoneyDirection.DEBIT,GameMoney(501)))))
        val before=rows();assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS),adapter.execute(command))
        assertEquals(before,rows());assertEquals(HostCheckpointPhase.SAVED,ok(store.hostCommands.load(profile))!!.phase)
        ok(adapter.cancelSaved(command));assertNull(ok(adapter.pending()));assertEquals(GameMoney(499),ok(store.balance()))
    }
    fun testSameProfileSourceAndOtherKindPendingCannotBeSubstituted() {
        repeat(3){complete(it)};val adapter=source();val snapshot=ok(adapter.read());val before=rows()
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),source(ProfileId("foreign")).read())
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),source(input=InputSource.VEHICLE).read());assertEquals(before,rows())
        val car=ok(store.listOwnedVehicles()).single()
        val other=HostCheckpointCommand.Garage(content.catalog.contentVersion,GameChange(RequestId("other-kind"),TransactionKind.GARAGE,origin,
            content.ruleVersion,vehicle=VehicleWrite.Update(car.ownedVehicleId,car.revision,2,VehicleLifecycle.OWNED,0)))
        ok(store.hostCommands.save(profile,other));val savedRows=rows()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),adapter.pending())
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),adapter.prepare(snapshot,request(snapshot)));assertEquals(savedRows,rows())
    }
    fun testValidDigestWithFractionalPriceIsRejectedWithoutDefaultOrWrite() {
        repeat(3){complete(it)};val adapter=source();val snapshot=ok(adapter.read());val command=ok(adapter.prepare(snapshot,request(snapshot)))
        val payload=HostCheckpointJson.encode(profile,command)
        val bad=JSONObject(payload).apply{getJSONObject("command").put("price",500.5)}.toString()
        raw{it.execSQL("UPDATE host_command_checkpoint SET payload=?,digest=? WHERE id=1",arrayOf(bad,HostCheckpointJson.digest(bad,0)))}
        val before=rows();assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),adapter.pending())
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),adapter.execute(command));assertEquals(before,rows())
        raw{it.execSQL("UPDATE host_command_checkpoint SET payload=?,digest=? WHERE id=1",arrayOf(payload,HostCheckpointJson.digest(payload,0)))}
        assertEquals(GameMoney(500),ok(adapter.execute(command)).balance)
    }
    fun testActualExpansionWriteFailureRestoresSavedWholeStateThenRetries() {
        repeat(3){complete(it)};val adapter=source();val snapshot=ok(adapter.read());val command=ok(adapter.prepare(snapshot,request(snapshot)))
        val before=rows();failAfterExpansion=true
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),adapter.execute(command));assertEquals(before,rows())
        failAfterExpansion=false;val result=ok(adapter.execute(command));assertEquals(GameMoney(500),result.balance)
        assertEquals(result,ok(adapter.finish(command)));assertNull(ok(adapter.pending()))
    }
    fun testFinishDeleteFailureKeepsStartedAndOriginalResult() {
        repeat(3){complete(it)};val adapter=source();val snapshot=ok(adapter.read());val command=ok(adapter.prepare(snapshot,request(snapshot)))
        val result=ok(adapter.execute(command))
        raw{it.execSQL("CREATE TRIGGER ignore_expand_finish BEFORE DELETE ON host_command_checkpoint BEGIN SELECT RAISE(IGNORE); END")}
        val before=rows();assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),adapter.finish(command));assertEquals(before,rows())
        raw{it.execSQL("DROP TRIGGER ignore_expand_finish")}
        assertEquals(result,ok(source().execute(command)));assertEquals(result,ok(source().finish(command)));assertEquals(GameMoney(500),ok(store.balance()))
    }
}
