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
class FirstGiftIgnoredRequestIndependentTest : InstrumentationTestCase() {
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
    fun testIgnoredWholeGiftRequestInsertMustRollbackEntireGift() {
        val first=initialized();complete(first.grant.changedVehicle!!);val c=prepared()
        fun rows(): Map<String,List<String>> {
            val result=linkedMapOf<String,List<String>>()
            raw { db -> for(table in listOf("account","vehicles","gacha_progress","first_gift_requests","first_gift_markers","settlements","initialization_profile","initialization_requests")) {
                db.rawQuery("SELECT * FROM $table ORDER BY 1",null).use { row ->
                    val values=mutableListOf<String>();while(row.moveToNext())values.add((0 until row.columnCount).joinToString("|") { if(row.isNull(it)) "<NULL>" else row.getString(it) });result[table]=values
                }
            } }
            return result
        }
        val before=rows()
        raw { it.execSQL("CREATE TRIGGER ignore_gift_request BEFORE INSERT ON first_gift_requests BEGIN SELECT RAISE(IGNORE); END") }
        val result=store.commitFirstRentalGift(c)
        val after=rows()
        val progress=store.readGachaState()
        val gift=store.readFirstRentalGift(profile())
        val evidence="result=$result\nactualProgress=$progress\nactualGift=$gift\nbeforeRows=$before\nafterRows=$after"
        context.openFileOutput("gift-ignore-request-observed.txt",0).use { it.write(evidence.toByteArray()) }
        instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","\nGIFT_REQUEST_IGNORE_OBSERVED\n$evidence\n") })
        assertEquals(failure(ContractError.STORAGE_FAILURE),result)
        assertEquals(before,after)
    }
}
