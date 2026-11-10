package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import java.util.UUID

/** UNKNOWN STARTED 복구 입구의 실제 helper 회귀 소스. 재화/해금은 자기 UUID DB의 명시 SIMULATION fixture다. */
@Suppress("DEPRECATION")
class DecorationUnknownStartedIndependentTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private var issued:ProfileId?=null
    private val profile get()=checkNotNull(issued)
    private var definitionReads=0
    private val definitions=trialDecorationDefinitions()
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    override fun setUp(){
        super.setUp();name="decoration-started-independent-${UUID.randomUUID()}.db"
        store=SqliteGameChangeStore(context,InputSource.SIMULATION,name).apply{
            decorationDefinitions={definitionReads++;ContractResult.Success(definitions)}
        }
        issued=ok(store.localProfileId())
        ok(store.commitGameChange(GameChange(RequestId("explicit-fixture-fund"),TransactionKind.GARAGE,
            InputSource.SIMULATION,RuleVersion("explicit-fixture"),MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)))))
        ok(store.decorations.activate(profile))
    }
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    private fun rows():List<String> = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use{db->
        val tables=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name",null).use{r->buildList{while(r.moveToNext())add(r.getString(0))}}
        buildList{for(table in tables)db.rawQuery("SELECT * FROM $table ORDER BY rowid",null).use{r->while(r.moveToNext())add(table+":"+(0 until r.columnCount).joinToString("|"){i->r.getType(i).toString()+":"+if(r.isNull(i))"<null>"else r.getString(i)})}}
    }
    private fun checkUnknownStarted(paid:Boolean){
        val item=definitions.items.single{(it.price.units>0)==paid}
        if(paid)ok(store.atomic{db->db.execSQL("UPDATE shop SET level=?,capacity=5 WHERE id=1",arrayOf(item.requiredShopLevel));ContractResult.Success(Unit)}) // 해금 상태 fixture, 제품 확장 성공이 아니다.
        val command=DecorationCommand(RequestId(if(paid)"unknown-started-paid"else"unknown-started-free"),profile,
            InputSource.SIMULATION,item.id,DecorationAction.BUY_AND_APPLY,definitions.ruleVersion,Revision(0),item.price)
        val wrapped=HostCheckpointCommand.Decoration(command)
        ok(store.hostCommands.save(profile,wrapped))
        // 결과를 알 수 없는 유효 STARTED envelope를 복구 입력으로 만든다. 금융 확정 성공을 주입하지 않는다.
        ok(store.atomic{db->db.execSQL("UPDATE host_command_checkpoint SET phase=1,digest=? WHERE id=1",
            arrayOf(HostCheckpointJson.digest(HostCheckpointJson.encode(profile,wrapped),1)));ContractResult.Success(Unit)})
        val before=rows()
        assertEquals(Revision(0),ok(store.decorations.state(profile)).revision)
        assertNull(ok(store.decorations.settlement(command)))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.hostCommands.execute(profile,wrapped))
        assertEquals(before,rows())
        definitionReads=0
        val direct=store.decorations.commit(command)
        val after=rows()
        android.util.Log.i("BDecorationReview","unknown_started paid=$paid direct=$direct rowsPreserved=${before==after} definitionReads=$definitionReads")
        assertTrue("공개 commit이 UNKNOWN STARTED를 신규 확정하면 안 된다: $direct",direct is ContractResult.Failure)
        assertEquals("미확정 결과의 당시 조건을 현재 정의로 재계산하면 안 된다",0,definitionReads)
        assertEquals(before,after)
        assertEquals(GameMoney(1000),ok(store.balance()))
        assertEquals(HostCheckpointPhase.STARTED,ok(store.hostCommands.load(profile))!!.phase)
    }
    fun testUnknownStartedDirectFreeCommitCannotCreateOwnershipOrOriginal()=checkUnknownStarted(false)
    fun testUnknownStartedDirectPaidCommitCannotDebitOrInventOriginal()=checkUnknownStarted(true)
}
