package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.rental.*
import java.math.RoundingMode

/** 실제 공용 helper/profile/초기화/선물 API. 초기progress0은 신규 DB 설정의 명시 SIMULATION 시험값이다. */
@Suppress("DEPRECATION")
class FirstGiftStoreIntegrationTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private val source=InputSource.SIMULATION
    private lateinit var name:String
    private lateinit var content:GameContentSnapshot
    private lateinit var supplied:ContractResult<GameContentSnapshot>
    private lateinit var store:SqliteGameChangeStore
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return (r as ContractResult.Success).value}
    private fun failure(e:ContractError)=ContractResult.Failure(e)
    private fun open()=SqliteGameChangeStore(context,source,name,
        gachaAuthority={ContractResult.Success(GachaAuthority(source,content.catalog.contentVersion,content.ruleVersion,1,3,GameMoney(500),
            content.gacha.pity,content.gacha.guaranteedFrom,content.unlockedAt(1).map{it.vehicleId}.toSet(),Rarity.entries.toSet()))},
        initialGachaProgress=GachaProgress(Revision(0),0,0),
        initializationAuthority={ContractResult.Success(InitializationAuthority(source,content,FirstGrant.CANDIDATES.toSet()))},firstGiftContent={supplied})
    override fun setUp(){super.setUp();name="gift-public-${System.nanoTime()}.db";content=(AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        supplied=ContractResult.Success(content);store=open()}
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    private fun profile()=ok(store.localProfileId())
    private fun initialized()=ok(FirstGrant(content).choose(store,profile(),VehicleId("hyundai_casper")))
    private fun complete(car:OwnedVehicle){
        val id=RequestId("completed");val terms=RentalTerms(content.ruleVersion,SimulatedCustomer(30,36),99,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)
        assertEquals(car.ownedVehicleId,ok(store.rental.assign(RentalRequest(id,car.vehicleId,1),content.catalog,mapOf(car.vehicleId to RentalCategory.SEDAN),mapOf(car.vehicleId to GameMoney(0)),terms)))
        ok(store.rental.account(id,EpochMillis(0),EpochMillis(3_600_000),null));ok(store.rental.settle(id));ok(store.rental.finishReturn(id,EpochMillis(4_500_000)))
    }
    private fun prepared()=ok(FirstGrant(content).prepareOpeningGift(store,profile(),source))
    private fun raw(block:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(block)
    fun testExplicitNewProgressInitializationCompletionGiftAndReopenUseOneProfile() {
        val p=profile();assertTrue(ok(store.readInitialization(p)).status is ProfileInitializationStatus.New)
        assertEquals(GachaProgress(Revision(0),0,0),ok(store.readGachaState()).progress)
        val first=initialized();complete(first.grant.changedVehicle!!);val cars=ok(store.listOwnedVehicles());val c=prepared();val r=ok(store.commitFirstRentalGift(c))
        assertEquals(p,c.profileId);assertEquals(GachaProgress(Revision(1),0,1),r.progress);assertEquals(GameMoney(1000),r.balance)
        assertNull(r.transaction.money);assertEquals(cars,ok(store.listOwnedVehicles()));assertEquals(3,ok(store.rental.shop()).capacity)
        store.close();store=open();assertEquals(p,profile());assertEquals(r,ok(store.commitFirstRentalGift(c)));assertEquals(GachaProgress(Revision(1),0,1),ok(store.readGachaState()).progress)
    }
    fun testUninitializedNewProfileWithCompletedRentalCannotClaim() {
        val p=profile();val car=OwnedVehicle(OwnedVehicleId("fixture-before-initialization"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.GACHA)
        ok(store.commitGameChange(GameChange(RequestId("fixture-add"),TransactionKind.GARAGE,source,content.ruleVersion,vehicle=VehicleWrite.Add(car))));complete(car)
        val rental=ok(store.rental.firstCompletedRental())!!
        val c=FirstRentalGiftCommand(RequestId("premature"),p,source,content.catalog.contentVersion,content.ruleVersion,
            FirstRentalGiftCause(rental.rentalId,rental.receipt.settlement.settlementId),Revision(0))
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),store.commitFirstRentalGift(c));assertEquals(GachaProgress(Revision(0),0,0),ok(store.readGachaState()).progress)
    }
    fun testV8MigrationKeepsInitializedProfileProgressAndExistingResults() {
        val first=initialized();complete(first.grant.changedVehicle!!);val p=profile();val c=prepared();val receipts=ok(store.listUnviewedSettlements())
        store.close();raw{db->db.execSQL("DROP TABLE first_gift_requests");db.execSQL("DROP TABLE first_gift_markers");listOf("quest_events","quest_claims","quest_tracking").forEach{db.execSQL("DROP TABLE $it")};db.execSQL("DROP TABLE host_command_checkpoint");stripDeliveryCheckpointFixture(db);db.version=8};store=open()
        assertEquals(p,profile());assertEquals(receipts,ok(store.listUnviewedSettlements()));assertEquals(GachaProgress(Revision(0),0,0),ok(store.readGachaState()).progress)
        assertEquals(GachaProgress(Revision(1),0,1),ok(store.commitFirstRentalGift(c)).progress);store.close();raw{assertEquals(19, it.version)};store=open()
    }
    fun testOriginalGiftReplaysBeforeCurrentContentAndMissingProgress() {
        val first=initialized();complete(first.grant.changedVehicle!!);val c=prepared();val r=ok(store.commitFirstRentalGift(c));val p=profile()
        store.close();raw{it.execSQL("DELETE FROM gacha_progress")};supplied=failure(ContractError.STORAGE_FAILURE);store=open()
        assertEquals(p,profile());assertEquals(r,ok(store.commitFirstRentalGift(c)));assertEquals(failure(ContractError.DATA_UNAVAILABLE),store.readGachaState())
    }
    fun testMarkerReservesIdForOtherDomainsWhenCommonReceiptWasDeleted() {
        val first=initialized();complete(first.grant.changedVehicle!!);val c=prepared();ok(store.commitFirstRentalGift(c));store.close()
        raw{it.execSQL("DELETE FROM settlements WHERE request_id=?",arrayOf(c.requestId.value))};store=open()
        val change=GameChange(c.requestId,TransactionKind.GARAGE,source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1)))
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitGameChange(change))
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitRepair(RepairCommand(c.requestId,source,c.contentVersion,c.ruleVersion,
            VehicleRef(first.grant.changedVehicle!!.ownedVehicleId,Revision(1)),0,GameMoney(0))))
        val target=VehicleWrite.Update(first.grant.changedVehicle!!.ownedVehicleId,Revision(1),2,VehicleLifecycle.OWNED,0)
        val material=VehicleWrite.Update(OwnedVehicleId("other"),Revision(0),1,VehicleLifecycle.CONSUMED,0)
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitMaterial(MaterialCommand(c.requestId,source,c.contentVersion,c.ruleVersion,target,material,GameMoney(0))))
        val award=first.grant.changedVehicle!!.copy(ownedVehicleId=OwnedVehicleId("draw"),acquisitionSource=VehicleAcquisitionSource.GACHA)
        assertEquals(failure(ContractError.STATE_CONFLICT),store.commitGacha(GachaCommand(c.requestId,source,c.contentVersion,c.ruleVersion,Revision(1),1,3,0,GachaTicketUse.FREE,GachaAward(award,1,false))))
        assertEquals(failure(ContractError.STORAGE_FAILURE),store.commitFirstRentalGift(c));assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testActualV7LegacyWithCompletionRemainsUnknownAndCannotClaim() {
        val car=OwnedVehicle(OwnedVehicleId("legacy-fixture"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.GACHA)
        ok(store.commitGameChange(GameChange(RequestId("legacy-add"),TransactionKind.GARAGE,source,content.ruleVersion,vehicle=VehicleWrite.Add(car))));complete(car)
        store.close();raw{db->listOf("initialization_requests","initialization_profile","first_gift_requests","first_gift_markers","host_command_checkpoint","quest_events","quest_claims","quest_tracking","observed_driving_marker","observed_driving_state","observed_driving_receipts").forEach{db.execSQL("DROP TABLE $it")};stripDeliveryCheckpointFixture(db);db.version=7};store=open()
        val p=profile();assertTrue(ok(store.readInitialization(p)).status is ProfileInitializationStatus.Unknown)
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),FirstGrant(content).prepareOpeningGift(store,p,source))
        assertEquals(GachaProgress(Revision(0),0,0),ok(store.readGachaState()).progress);assertEquals(GameMoney(0),ok(store.balance()))
    }
    fun testActualProfileGateAndPostWriteCommitFailureRollBackTogether() {
        val first=initialized();complete(first.grant.changedVehicle!!);val c=prepared();val before=ok(store.listUnviewedSettlements());val cars=ok(store.listOwnedVehicles())
        store.beforeCommit={
            val written=ok(store.read{db->db.rawQuery("SELECT 1 FROM first_gift_requests WHERE request_id=?",arrayOf(c.requestId.value)).use{it.moveToFirst()}})
            if(written)throw android.database.sqlite.SQLiteException("공개 선물 전체 쓰기 뒤 실패")
        }
        assertEquals(failure(ContractError.STORAGE_FAILURE),store.commitFirstRentalGift(c));store.beforeCommit=null
        assertEquals(before,ok(store.listUnviewedSettlements()));assertEquals(cars,ok(store.listOwnedVehicles()));assertEquals(GameMoney(1000),ok(store.balance()))
        assertNull(ok(store.readFirstRentalGift(profile())).granted);assertEquals(GachaProgress(Revision(0),0,0),ok(store.readGachaState()).progress)
        assertEquals(GachaProgress(Revision(1),0,1),ok(store.commitFirstRentalGift(c)).progress)
    }
    fun testExistingMissingProgressAndForeignProfileAreNeverDefaulted() {
        val first=initialized();complete(first.grant.changedVehicle!!);val c=prepared();val p=profile()
        assertEquals(failure(ContractError.INVALID_INPUT),store.commitFirstRentalGift(c.copy(profileId=ProfileId("foreign"))))
        assertEquals(failure(ContractError.INVALID_INPUT),FirstGrant(content).prepareOpeningGift(store,p,InputSource.VEHICLE))
        store.close();raw{it.execSQL("DELETE FROM gacha_progress")};store=open()
        assertEquals(failure(ContractError.DATA_UNAVAILABLE),store.commitFirstRentalGift(c));assertEquals(failure(ContractError.DATA_UNAVAILABLE),store.readGachaState())
    }
    fun testIgnoredWholeResultInsertRollsBackThenSameCommandRetriesOnce() {
        val first=initialized();complete(first.grant.changedVehicle!!);val c=prepared()
        val before=ok(store.listUnviewedSettlements());val balance=ok(store.balance())
        raw { it.execSQL("CREATE TRIGGER ignore_whole_gift BEFORE INSERT ON first_gift_requests BEGIN SELECT RAISE(IGNORE); END") }
        assertEquals(failure(ContractError.STORAGE_FAILURE),store.commitFirstRentalGift(c))
        assertEquals(GachaProgress(Revision(0),0,0),ok(store.readGachaState()).progress)
        assertEquals(before,ok(store.listUnviewedSettlements()));assertEquals(balance,ok(store.balance()))
        raw { it.execSQL("DROP TRIGGER ignore_whole_gift") }
        val result=ok(store.commitFirstRentalGift(c))
        assertEquals(GachaProgress(Revision(1),0,1),result.progress)
        assertEquals(result,ok(store.commitFirstRentalGift(c)))
        assertEquals(result.progress,ok(store.readGachaState()).progress)
    }
}
