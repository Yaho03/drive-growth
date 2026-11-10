package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import org.json.JSONObject

/** 실제 공용 금융 원본의 소수를 typed snapshot과 같은 정수로 절삭해 수용하지 않는다. */
@Suppress("DEPRECATION")
class DecorationMoneyRecordBoundaryTest:InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private var actor:ProfileId?=null
    private val profile get()=checkNotNull(actor)
    private val source=InputSource.SIMULATION
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    override fun setUp() {
        super.setUp();name="decoration-money-boundary-${System.nanoTime()}.db";store=SqliteGameChangeStore(context,source,name);actor=ok(store.localProfileId())
        ok(store.commitGameChange(GameChange(RequestId("fixture-fund"),TransactionKind.GARAGE,source,RuleVersion("fixture"),MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)))))
        ok(store.decorations.activate(profile));raw{it.execSQL("UPDATE shop SET level=2,capacity=5 WHERE id=1")}
    }
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    private fun raw(f:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(f)
    private fun rows():List<String> = buildList{raw{db->for(t in listOf("account","settlements","decoration_module","decoration_requests","host_command_checkpoint"))db.rawQuery("SELECT * FROM $t ORDER BY rowid",null).use{r->while(r.moveToNext())add(t+":"+(0 until r.columnCount).joinToString("|"){if(r.isNull(it))"<null>"else r.getString(it)})}}}
    fun testFractionalCommonBalanceAndTimeCannotReplayAsCanonicalIntegers() {
        val c=DecorationCommand(RequestId("paid-boundary"),profile,source,"blue-sign",DecorationAction.BUY_AND_APPLY,trialDecorationDefinitions().ruleVersion,Revision(0),GameMoney(100))
        val original=ok(store.decorations.commit(c));assertEquals(GameMoney(900),original.state.balance)
        var healthy="";raw{db->db.rawQuery("SELECT result FROM settlements WHERE request_id=?",arrayOf(c.requestId.value)).use{it.moveToFirst();healthy=it.getString(0)}}
        for(key in listOf("balance","at")) {
            val j=JSONObject(healthy);j.put(key,j.getLong(key).toDouble()+0.5)
            raw{it.execSQL("UPDATE settlements SET result=? WHERE request_id=?",arrayOf(j.toString(),c.requestId.value))};val before=rows()
            assertEquals(key,ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.state(profile))
            assertEquals(key,ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.settlement(c))
            assertEquals(key,ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.commit(c));assertEquals(key,before,rows())
        }
    }
}
