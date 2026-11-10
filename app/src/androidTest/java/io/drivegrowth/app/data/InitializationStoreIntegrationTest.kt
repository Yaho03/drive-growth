package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant

/** 공용 helper/capability와 실제 #151 소비 경로. 신규 근거를 시험 callback에서 주입하지 않는다. */
@Suppress("DEPRECATION")
class InitializationStoreIntegrationTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private val source=InputSource.SIMULATION
    private lateinit var name:String
    private lateinit var content:GameContentSnapshot
    private lateinit var authority:ContractResult<InitializationAuthority>
    private lateinit var store:SqliteGameChangeStore
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return (r as ContractResult.Success).value}
    private fun open()=SqliteGameChangeStore(context,source,name,initializationAuthority={authority})
    override fun setUp(){super.setUp();name="initialization-public-${System.nanoTime()}.db";content=(AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot
        authority=ContractResult.Success(InitializationAuthority(source,content,FirstGrant.CANDIDATES.toSet()));store=open()}
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    fun testActualPublicStoreCreatesNewProofThenSharedMoneyVehicleSpaceOnce() {
        val profile=ok(store.localProfileId());val before=ok(store.readInitialization(profile));assertTrue(before.status is ProfileInitializationStatus.New)
        val r=ok(FirstGrant(content).choose(store,profile,VehicleId("hyundai_casper")))
        assertEquals(GameMoney(1000),ok(store.balance()));assertEquals(3,ok(store.rental.shop()).capacity);assertEquals(r.grant.changedVehicle,ok(store.listOwnedVehicles()).single())
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.readGachaState())
        store.close();store=open();assertEquals(profile,ok(store.localProfileId()));assertEquals(r,ok(FirstGrant(content).choose(store,profile,VehicleId("hyundai_casper"))))
    }
    fun testMissingAuthorityDoesNotTurnBalanceOrEmptyGarageIntoNewGrant() {
        val profile=ok(store.localProfileId());authority=ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),FirstGrant(content).choose(store,profile,VehicleId("hyundai_casper")))
        assertEquals(GameMoney(0),ok(store.balance()));assertTrue(ok(store.listOwnedVehicles()).isEmpty())
    }
    fun testActualLegacyUpgradeHasIdentityButNoNewProofOrPayout() {
        ok(store.commitGameChange(GameChange(RequestId("legacy-income"),TransactionKind.RENTAL,source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(17)))))
        store.close();SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use{db->db.execSQL("DROP TABLE initialization_requests");db.execSQL("DROP TABLE initialization_profile");db.execSQL("DROP TABLE first_gift_requests");db.execSQL("DROP TABLE first_gift_markers");listOf("quest_events","quest_claims","quest_tracking").forEach{db.execSQL("DROP TABLE $it")};db.execSQL("DROP TABLE host_command_checkpoint");stripDeliveryCheckpointFixture(db);db.version=7}
        store=open();val profile=ok(store.localProfileId());assertTrue(ok(store.readInitialization(profile)).status is ProfileInitializationStatus.Unknown)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),FirstGrant(content).choose(store,profile,VehicleId("hyundai_casper")));assertEquals(GameMoney(17),ok(store.balance()))
        store.close();store=open();assertEquals(profile,ok(store.localProfileId()))
    }
    fun testOriginalCommandReplaysWithoutCurrentAuthorityThroughStoreAndWrapper() {
        val profile=ok(store.localProfileId());val r=ok(FirstGrant(content).choose(store,profile,VehicleId("hyundai_casper")))
        authority=ContractResult.Failure(ContractError.STORAGE_FAILURE)
        assertEquals(r,ok(store.commitInitialization(r.command)));assertEquals(r,ok(FirstGrant(content.copy(ruleVersion=RuleVersion("changed"))).choose(store,profile,VehicleId("hyundai_casper"))))
        assertEquals(GameMoney(1000),ok(store.balance()))
    }
}
