package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.quest.*
import io.drivegrowth.core.rental.*
import java.time.Instant
import java.time.ZoneId
import java.math.RoundingMode

/** 실제 helper·발급 프로필·원자 hook·중앙 asset 공급. A의 장치 실행 전에는 컴파일 근거뿐이다. */
@Suppress("DEPRECATION")
class QuestStoreIntegrationTest: InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private val source=InputSource.SIMULATION
    private val zone=ZoneId.of("UTC")
    private var now=Instant.parse("2026-11-10T03:00:00Z").toEpochMilli()
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private var issuedProfile:ProfileId?=null
    private val profile get()=requireNotNull(issuedProfile)
    private lateinit var definition:TrialDefinition
    private var definitionsAvailable=true
    private fun <T> ok(value:ContractResult<T>):T {assertTrue(value.toString(),value is ContractResult.Success);return(value as ContractResult.Success).value}
    private fun open()=SqliteGameChangeStore(context,source,name,questDefinitions={if(definitionsAvailable)definition.questDefinitions(zone) else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)},clock={now})
    override fun setUp(){super.setUp();definition=ok(AssetTrialDefinitionProvider(context.assets).load());name="quest-common-${System.nanoTime()}.db";store=open();issuedProfile=ok(store.localProfileId())
        ok(store.commitGameChange(GameChange(RequestId("fund"),TransactionKind.GARAGE,source,definition.rules,MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)))))
    }
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    private fun raw(f:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(f)
    private fun rows():List<String> = buildList{raw{db->for(t in listOf("account","vehicles","settlements","rentals","visits","shop","quest_tracking","quest_events","quest_claims","host_command_checkpoint"))db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{r->while(r.moveToNext())add(t+":"+(0 until r.columnCount).joinToString("|"){if(r.isNull(it))"<null>"else r.getString(it)})}}}
    private fun states()=ok(store.quests.read(profile,Observation.Available(EpochMillis(now))))
    private fun daily(action:QuestAction)=states().single{it.definition.cadence==QuestCadence.DAILY&&it.definition.action==action}
    private fun receipt()=ok(store.listUnviewedSettlements()).single{it.transaction.requestId==RequestId("fund")}
    private fun command():QuestClaimCommand {val p=daily(QuestAction.RESULT_VIEWED);return QuestClaimCommand(RequestId("quest/claim/actual"),profile,source,p.definition,p.period,p.revision!!)}
    private fun car()=OwnedVehicle(OwnedVehicleId("quest-car"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT)
    private fun addCar(){ok(store.commitGameChange(GameChange(RequestId("add-car"),TransactionKind.GARAGE,source,definition.rules,null,VehicleWrite.Add(car()))))}
    private fun grow()=store.commitGameChange(GameChange(RequestId("grow-car"),TransactionKind.GARAGE,source,definition.rules,MoneyChange(MoneyDirection.DEBIT,GameMoney(1)),VehicleWrite.Update(car().ownedVehicleId,Revision(0),2,VehicleLifecycle.OWNED,0)))
    private fun completeRental():ContractResult<RentalReceipt> {
        val id=RequestId("actual-rental");val catalog=VehicleCatalogSnapshot(definition.version,listOf(VehicleDefinition(car().vehicleId,"시험 차")))
        ok(store.rental.assign(RentalRequest(id,car().vehicleId,1),catalog,mapOf(car().vehicleId to RentalCategory.SEDAN),mapOf(car().vehicleId to GameMoney(100)),RentalTerms(definition.rules,SimulatedCustomer(30,36),99,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)))
        ok(store.rental.account(id,EpochMillis(0),EpochMillis(3600000),null))
        return store.rental.settle(id)
    }
    fun testActualFreshHelperIssuesTrackingAndCentralSixDefinitionsWithoutReentry() {
        val before=rows();assertEquals(6,states().size);assertTrue(states().all{it.count==0&&it.status==QuestStatus.IN_PROGRESS&&it.definition.zone==zone})
        raw{db->assertEquals(20, db.version)
            for(table in listOf("quest_tracking","quest_events","quest_claims","host_command_checkpoint","observed_driving_marker","observed_driving_state","observed_driving_receipts"))db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{assertTrue(table,it.moveToFirst())}
            for(table in listOf("delivery_requests","delivery_changes","delivery_outcomes","delivery_hold_outcomes"))db.rawQuery("PRAGMA table_info($table)",null).use{r->var found=false;while(r.moveToNext())if(r.getString(1)=="host_command")found=true;assertTrue(table,found)}
            db.rawQuery("SELECT profile,source,complete_since FROM quest_tracking",null).use{assertTrue(it.moveToFirst());assertEquals(profile.value,it.getString(0));assertEquals(source.name,it.getString(1));assertEquals(0L,it.getLong(2));assertFalse(it.moveToNext())}}
        assertEquals(before,rows())
    }
    fun testActualViewedGrowthAndCompletedRentalRecordOnceWithoutManualHooks() {
        val r=receipt();ok(store.markViewed(r.settlementId));ok(store.markViewed(r.settlementId));assertEquals(1,daily(QuestAction.RESULT_VIEWED).count)
        addCar();assertEquals(0,daily(QuestAction.VEHICLE_LEVEL_UP).count);val grown=ok(grow());assertEquals(grown,ok(grow()));assertEquals(1,daily(QuestAction.VEHICLE_LEVEL_UP).count)
        val done=ok(completeRental());assertEquals(done,ok(store.rental.settle(RequestId("actual-rental"))));assertEquals(1,daily(QuestAction.RENTAL_COMPLETED).count)
        raw{db->db.rawQuery("SELECT COUNT(*) FROM quest_events",null).use{it.moveToFirst();assertEquals(3,it.getInt(0))}}
    }
    fun testActualClaimWholeReplayPrecedesNewDefinitionTimeAndCurrentBalance() {
        ok(store.markViewed(receipt().settlementId));val c=command();val paid=ok(store.quests.claim(c));assertEquals(GameMoney(1100),paid.settlement.balance)
        ok(store.commitGameChange(GameChange(RequestId("later-spend"),TransactionKind.GARAGE,source,definition.rules,MoneyChange(MoneyDirection.DEBIT,GameMoney(200)))))
        now+=8*86400000L;definitionsAvailable=false;val before=rows();store.close();store=open()
        val collision = ContractResult.Failure(ContractError.STATE_CONFLICT)
        val foreign = GameChange(c.requestId, TransactionKind.GARAGE, source, definition.rules,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(100)))
        val offer = ShopExpansion(definition.rules, true, 1, 3, 5, 0, GameMoney(1))
        assertEquals(collision, store.commitGameChange(foreign))
        assertEquals(collision, store.rental.expand(c.requestId, offer))
        assertEquals(paid,ok(store.quests.claim(c)));assertEquals(GameMoney(900),ok(store.balance()));assertEquals(setOf(c.requestId),ok(store.quests.claimedRequestIds(profile)));assertEquals(before,rows())
    }
    fun testIgnoredViewedFlagCannotRecordEventAndRetriesOnce() {
        val r=receipt();raw{it.execSQL("CREATE TRIGGER ignore_view BEFORE UPDATE OF viewed ON settlements BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.markViewed(r.settlementId));assertEquals(before,rows())
        raw{it.execSQL("DROP TRIGGER ignore_view")};ok(store.markViewed(r.settlementId));assertEquals(1,daily(QuestAction.RESULT_VIEWED).count)
    }
    fun testIgnoredEventRollsBackViewedFlagAndActualGrowth() {
        addCar();raw{it.execSQL("CREATE TRIGGER ignore_event BEFORE INSERT ON quest_events BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.markViewed(receipt().settlementId));assertEquals(before,rows())
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),grow());assertEquals(before,rows())
        raw{it.execSQL("DROP TRIGGER ignore_event")};ok(grow());assertEquals(1,daily(QuestAction.VEHICLE_LEVEL_UP).count)
    }
    fun testIgnoredEventRollsBackRentalSettlementAndRetryDoesNotDuplicate() {
        addCar();val id=RequestId("actual-rental");val catalog=VehicleCatalogSnapshot(definition.version,listOf(VehicleDefinition(car().vehicleId,"시험 차")))
        ok(store.rental.assign(RentalRequest(id,car().vehicleId,1),catalog,mapOf(car().vehicleId to RentalCategory.SEDAN),mapOf(car().vehicleId to GameMoney(100)),RentalTerms(definition.rules,SimulatedCustomer(30,36),99,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)))
        ok(store.rental.account(id,EpochMillis(0),EpochMillis(3600000),null));raw{it.execSQL("CREATE TRIGGER ignore_event BEFORE INSERT ON quest_events BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.rental.settle(id));assertEquals(before,rows())
        raw{it.execSQL("DROP TRIGGER ignore_event")};ok(store.rental.settle(id));assertEquals(1,daily(QuestAction.RENTAL_COMPLETED).count)
    }
    fun testClaimMoneyReceiptAndMarkerIgnoredWritesAreAtomic() {
        ok(store.markViewed(receipt().settlementId));val c=command()
        for((table,event)in listOf("account" to "UPDATE","settlements" to "INSERT","quest_claims" to "INSERT")){
            raw{it.execSQL("CREATE TRIGGER ignore_claim BEFORE $event ON $table BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.quests.claim(c));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER ignore_claim")}
        }
        assertEquals(GameMoney(1100),ok(store.quests.claim(c)).settlement.balance)
    }
    fun testClaimActualAccountPostconditionAndStrictCurrentNumber() {
        ok(store.markViewed(receipt().settlementId));val c=command();raw{it.execSQL("UPDATE account SET balance=1000.5")};val malformed=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.quests.claim(c));assertEquals(malformed,rows());raw{it.execSQL("UPDATE account SET balance=1000");it.execSQL("CREATE TRIGGER reset_claim AFTER UPDATE ON account BEGIN UPDATE account SET balance=1000 WHERE id=1; END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.quests.claim(c));assertEquals(before,rows())
    }
    fun testMissingDefinitionsDoNotDiscardRealEventsOrInventProgress() {
        definitionsAvailable=false;ok(store.markViewed(receipt().settlementId));assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.quests.read(profile,Observation.Available(EpochMillis(now))))
        definitionsAvailable=true;assertEquals(1,daily(QuestAction.RESULT_VIEWED).count)
    }
    fun testV10UpgradePreservesOriginalRowsAndKeepsEarlierPeriodUnknown() {
        val r=receipt();ok(store.markViewed(r.settlementId));val before=rows().filter{!it.startsWith("quest_")};store.close()
        raw{db->listOf("quest_events","quest_claims","quest_tracking").forEach{db.execSQL("DROP TABLE $it")};stripDeliveryCheckpointFixture(db);db.version=10}
        store=open();assertEquals(profile,ok(store.localProfileId()));assertEquals(before,rows().filter{!it.startsWith("quest_")});assertTrue(states().all{it.status==QuestStatus.UNKNOWN&&it.count==null})
        ok(store.markViewed(r.settlementId));raw{db->db.rawQuery("SELECT COUNT(*) FROM quest_events",null).use{it.moveToFirst();assertEquals(0,it.getInt(0))}}
    }
    fun testV10MigrationFailurePreservesVersionAndWholeFile() {
        store.close();raw{it.version=10};val file=context.getDatabasePath(name);val before=file.readBytes();store=open()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.balance());store.close();assertTrue(before.contentEquals(file.readBytes()));raw{assertEquals(10,it.version);it.version=18};store=open()
    }
    fun testQuestNamespaceReservedFromNewGenericAndCheckpointCommands() {
        val change=GameChange(RequestId("quest/claim/wrong-owner"),TransactionKind.GARAGE,source,definition.rules,MoneyChange(MoneyDirection.CREDIT,GameMoney(1)))
        val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.commitGameChange(change))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),store.hostCommands.save(profile,HostCheckpointCommand.Garage(definition.version,change)));assertEquals(before,rows())
    }
}
