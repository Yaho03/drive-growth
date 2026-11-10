package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.garage.VehicleUpgrade
import io.drivegrowth.core.quest.*
import java.time.Instant
import java.time.ZoneId

/** C 추가 반례. E 원본37을 바꾸지 않고 실제 helper/자동 hook을 호출한다. 컴파일과 기기 실행은 별도다. */
@Suppress("DEPRECATION")
class QuestIndependentPostconditionTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private val inputSource=InputSource.SIMULATION
    private val time=Instant.parse("2026-11-10T03:00:00Z").toEpochMilli()
    private lateinit var content:GameContentSnapshot
    private lateinit var definitions:TrialDefinition
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private val target=OwnedVehicle(OwnedVehicleId("target"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.RARE,0,GameMoney(0),VehicleAcquisitionSource.GACHA)
    private fun <T> ok(value:ContractResult<T>):T {assertTrue(value.toString(),value is ContractResult.Success);return(value as ContractResult.Success).value}
    override fun setUp(){super.setUp();content=(AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot;definitions=ok(AssetTrialDefinitionProvider(context.assets).load());name="quest-postcondition-c-${System.nanoTime()}.db"
        store=SqliteGameChangeStore(context,inputSource,name,materialVersions={ContractResult.Success(MaterialVersions(content.catalog.contentVersion,content.ruleVersion))},questDefinitions={definitions.questDefinitions(ZoneId.of("UTC"))},clock={time})
        ok(store.commitGameChange(GameChange(RequestId("fund"),TransactionKind.GARAGE,inputSource,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)))))
    }
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    private fun raw(block:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun add(car:OwnedVehicle)=ok(store.commitGameChange(GameChange(RequestId("add-"+car.ownedVehicleId.value),TransactionKind.GARAGE,inputSource,content.ruleVersion,vehicle=VehicleWrite.Add(car))))
    private fun resetTarget(){raw{it.execSQL("""CREATE TRIGGER reset_target AFTER UPDATE OF payload ON vehicles
        WHEN NEW.owned_id='target' AND NEW.payload LIKE '%"revision":1,%'
        BEGIN UPDATE vehicles SET payload=OLD.payload WHERE owned_id=NEW.owned_id; END""")}}
    private fun rows():List<String> = buildList {raw{db->for(table in listOf("account","vehicles","settlements","material_requests","quest_tracking","quest_events","quest_claims"))
        db.rawQuery("SELECT * FROM $table ORDER BY 1",null).use{r->while(r.moveToNext())add(table+":"+(0 until r.columnCount).joinToString("|"){i->r.getType(i).toString()+":"+if(r.isNull(i))"<null>"else r.getString(i)})}}}
    private fun growthCount():Int? = ok(store.quests.read(ok(store.localProfileId()),Observation.Available(EpochMillis(time))))
        .single{it.definition.cadence==QuestCadence.DAILY&&it.definition.action==QuestAction.VEHICLE_LEVEL_UP}.count
    fun testGenericAfterUpdateResetCannotEmitGrowthOrLeaveReceiptAndHistoricalReplayStillWorks(){
        add(target);resetTarget();val before=rows()
        val c=GameChange(RequestId("generic-growth"),TransactionKind.GARAGE,inputSource,content.ruleVersion,MoneyChange(MoneyDirection.DEBIT,GameMoney(1)),VehicleWrite.Update(target.ownedVehicleId,Revision(0),2,VehicleLifecycle.OWNED,0))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.commitGameChange(c));assertEquals(before,rows());assertEquals(0,growthCount())
        raw{it.execSQL("DROP TRIGGER reset_target")};val original=ok(store.commitGameChange(c));assertEquals(1,growthCount())
        ok(store.commitGameChange(c.copy(requestId=RequestId("later-growth"),vehicle=VehicleWrite.Update(target.ownedVehicleId,Revision(1),3,VehicleLifecycle.OWNED,0))))
        assertEquals(original,ok(store.commitGameChange(c)));assertEquals(3,ok(store.listOwnedVehicles()).single().level);assertEquals(GameMoney(998),ok(store.balance()));assertEquals(2,growthCount())
    }
    fun testMaterialAfterUpdateResetCannotConsumeMaterialOrEmitGrowthAndReplayAfterSaleStillWorks(){
        val a=target.copy(level=2,levelProgressPercent=50);val b=target.copy(ownedVehicleId=OwnedVehicleId("material"),rarity=Rarity.ADVANCED)
        add(a);add(b);val c=ok(VehicleUpgrade(content).prepareMaterial(store,RequestId("material-growth"),inputSource,VehicleRef(a.ownedVehicleId,a.revision),VehicleRef(b.ownedVehicleId,b.revision)))
        resetTarget();val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.commitMaterial(c));assertEquals(before,rows());assertEquals(0,growthCount())
        raw{it.execSQL("DROP TRIGGER reset_target")};val original=ok(store.commitMaterial(c));assertEquals(1,growthCount())
        ok(store.commitGameChange(GameChange(RequestId("sale-after-material"),TransactionKind.GARAGE,inputSource,content.ruleVersion,vehicle=VehicleWrite.Update(a.ownedVehicleId,original.target.revision,original.target.level,VehicleLifecycle.SOLD,original.target.levelProgressPercent))))
        assertEquals(original,ok(store.commitMaterial(c)));assertEquals(VehicleLifecycle.SOLD,ok(store.listVehicleHistory()).single{it.ownedVehicleId==a.ownedVehicleId}.lifecycle);assertEquals(1,growthCount())
    }
}
