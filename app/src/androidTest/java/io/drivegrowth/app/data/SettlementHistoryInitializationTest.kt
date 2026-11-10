package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import java.util.UUID

/** 초기화 writer의 실제 원문을 소비하며 신규 지급/현재 콘텐츠 없는 재개방도 조회만 수행한다. */
@Suppress("DEPRECATION")
class SettlementHistoryInitializationTest:InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private lateinit var names:Map<InputSource,String>
    private lateinit var games:GameSessionFactory
    private var issuedProfile:ProfileId?=null
    private val profile get()=checkNotNull(issuedProfile)
    private lateinit var original:InitializationSettlement
    private fun <T> ok(r:ContractResult<T>):T{assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    override fun setUp(){super.setUp();val prefix="history-init-${UUID.randomUUID()}";names=InputSource.entries.associateWith{"$prefix-${it.name}.db"};games=GameSessionFactory(context,names,InputSource.SIMULATION)
        val onboarding=StoredOnboardingHostSource(games);issuedProfile=ok(onboarding.read()).identity.profileId;ok(onboarding.choose(io.drivegrowth.app.host.GameIdentity(profile,InputSource.SIMULATION),FirstGrant.CANDIDATES.first()))
        ok(games.open()).use{original=(ok(it.store.readInitialization(profile)).status as ProfileInitializationStatus.Existing).original!!}
    }
    override fun tearDown(){names.values.forEach(context::deleteDatabase);super.tearDown()}
    private fun raw(action:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(names.getValue(InputSource.SIMULATION)).path,null,SQLiteDatabase.OPEN_READWRITE).use(action)
    private fun rows()=buildList{raw{db->for(t in listOf("account","vehicles","settlements","shop","initialization_profile","initialization_requests"))db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{c->while(c.moveToNext())add(t+":"+(0 until c.columnCount).joinToString("|"){c.getType(it).toString()+":"+c.getString(it)})}}}
    fun testOriginalInitializationRemainsInFullHistoryWithoutCurrentContentOrWrites() {
        val before=rows();val first=ok(SettlementHistoryReader.read(games));assertEquals(listOf(original.grant),first.entries.map{it.settlement});assertEquals(before,rows())
        SqliteGameChangeStore(context,InputSource.SIMULATION,names.getValue(InputSource.SIMULATION),initializationAuthority={ContractResult.Failure(ContractError.DATA_UNAVAILABLE)}).use{store->
            assertEquals(first.copy(vehicleNames=emptyMap()),ok(SettlementHistoryReader(store).read()));assertEquals(before,rows())
        }
    }
    private fun rejects(sql:String){val before=rows();raw{it.execSQL(sql)};val damaged=rows();assertFalse("손상 SQL이 실제 행을 바꿔야 함",before==damaged)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),SettlementHistoryReader.read(games));assertEquals(damaged,rows())}
    fun testInitializationProofDamageCannotBecomeNormalHistory()=rejects("UPDATE initialization_profile SET proof_revision=proof_revision+1")
    fun testInitializationMarkerDamageCannotBecomeNormalHistory()=rejects("UPDATE initialization_profile SET marker_request='foreign'")
    fun testNestedGrantCommandDamageCannotBecomeNormalHistory() {
        val amount=original.command.grant.money!!.amount.units
        rejects("UPDATE settlements SET command=replace(command,'\"amount\":$amount','\"amount\":$amount.5')")
    }
}
