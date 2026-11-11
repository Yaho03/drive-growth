package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 동일 helper/공용 원장의 실제 SQLite 시험. 해금 단계와 재화는 명시 SIMULATION fixture다. */
@Suppress("DEPRECATION")
class DecorationLedgerTest : InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private val source=InputSource.SIMULATION
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private var issuedProfile:ProfileId?=null
    private val profile get()=checkNotNull(issuedProfile)
    private var definitions=trialDecorationDefinitions()
    private var definitionsAvailable=true
    private var definitionReads=0
    private fun open()=SqliteGameChangeStore(context,source,name).apply {
        decorationDefinitions={definitionReads++;if(definitionsAvailable)ContractResult.Success(definitions)else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)}
    }
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    override fun setUp() {
        super.setUp();name="decoration-${System.nanoTime()}.db";store=open();issuedProfile=ok(store.localProfileId())
        ok(store.commitGameChange(GameChange(RequestId("fixture-fund"),TransactionKind.GARAGE,source,RuleVersion("fixture"),MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)))))
    }
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    private fun raw(f:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(f)
    private fun rows():List<String> = buildList {raw { db->
        val tables=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'android_%' AND name NOT LIKE 'sqlite_%' ORDER BY name",null).use {r->buildList{while(r.moveToNext())add(r.getString(0))}}
        for(t in tables)db.rawQuery("SELECT * FROM $t ORDER BY rowid",null).use {r->while(r.moveToNext())add(t+":"+(0 until r.columnCount).joinToString("|"){if(r.isNull(it))"<null>"else r.getString(it)})}
    }}
    private fun activate()=ok(store.decorations.activate(profile))
    private fun unlock(){raw{it.execSQL("UPDATE shop SET level=2,capacity=5 WHERE id=1")}} // 정상 확장 뒤 상태 fixture; 제품 확장 성공 시험이 아니다.
    private fun command(id:String="buy-blue",item:String="blue-sign",revision:Int=0,action:DecorationAction=DecorationAction.BUY_AND_APPLY,price:Long=100)=
        DecorationCommand(RequestId(id),profile,source,item,action,definitions.ruleVersion,Revision(revision),GameMoney(price))
    private fun wrap(c:DecorationCommand)=HostCheckpointCommand.Decoration(c)
    private fun execute(c:DecorationCommand):DecorationSettlement {ok(store.hostCommands.save(profile,wrap(c)));return(ok(store.hostCommands.execute(profile,wrap(c))) as HostCheckpointResult.Decoration).value}
    private fun finish(c:DecorationCommand)=ok(store.hostCommands.finish(profile,wrap(c)))
    private fun settlementCount(id:RequestId):Int {var n=0;raw{db->db.rawQuery("SELECT count(*) FROM settlements WHERE request_id=?",arrayOf(id.value)).use{it.moveToFirst();n=it.getInt(0)}};return n}

    fun testExplicitActivationAndPreviewDoNotInventOwnershipOrPersistPreview() {
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.decorations.state(profile))
        val s=activate();assertEquals(Revision(0),s.revision);assertEquals(GameMoney(1000),s.balance);assertEquals(emptySet<String>(),s.ownedIds);assertNull(s.selectedId)
        val before=rows();val free=command("preview","neutral-sign",price=0)
        val preview=ok(prepareDecorationChange(free,s,definitions));assertEquals("neutral-sign",preview.state.selectedId)
        assertEquals(before,rows());assertEquals(s,ok(store.decorations.state(profile)));assertEquals(s,activate());assertEquals(before,rows())
    }
    fun testFreePurchaseHasTypedOriginalWithoutSyntheticFinancialSettlement() {
        activate();val c=command("free","neutral-sign",price=0);val r=execute(c);finish(c)
        assertNull(r.settlement);assertEquals(0,settlementCount(c.requestId));assertEquals(GameMoney(1000),r.state.balance);assertEquals(Revision(1),r.state.revision)
        assertEquals(setOf("neutral-sign"),r.state.ownedIds);assertEquals("neutral-sign",r.state.selectedId)
        val before=rows();store.close();definitionsAvailable=false;store=open();val reads=definitionReads
        assertEquals(r,ok(store.decorations.commit(c)));assertEquals(r,ok(store.decorations.settlement(c)));assertEquals(reads,definitionReads);assertEquals(before,rows())
    }
    fun testPaidPurchaseUsesSingleCommonDebitAndRemainsReadableInFullHistory() {
        activate();unlock();val c=command();val r=execute(c);finish(c)
        assertEquals(GameMoney(900),r.state.balance);assertEquals(GameMoney(900),ok(store.balance()));assertEquals(Revision(1),r.state.revision)
        val money=checkNotNull(r.settlement);assertEquals(TransactionKind.FACILITY,money.transaction.kind);assertEquals(MoneyChange(MoneyDirection.DEBIT,GameMoney(100)),money.transaction.money)
        assertEquals(c.requestId,money.transaction.requestId);assertEquals(1,settlementCount(c.requestId));assertEquals(setOf("blue-sign"),r.state.ownedIds)
        assertTrue(ok(SettlementHistoryReader(store).read()).entries.any{it.settlement==money})
        store.close();definitionsAvailable=false;store=open();val before=rows();val reads=definitionReads
        assertEquals(r,execute(c));finish(c);assertEquals(reads,definitionReads);assertEquals(before,rows())
    }
    fun testOwnedReapplyIsFreeAndOldReplayDoesNotOverwriteNewSelection() {
        activate();unlock();val free=command("free","neutral-sign",price=0);execute(free);finish(free)
        val paid=command(revision=1);val original=execute(paid);finish(paid)
        val apply=command("apply-neutral","neutral-sign",2,DecorationAction.APPLY_OWNED,0);val changed=execute(apply);finish(apply)
        assertNull(changed.settlement);assertEquals(0,settlementCount(apply.requestId));assertEquals(GameMoney(900),changed.state.balance);assertEquals(Revision(3),changed.state.revision)
        assertEquals(setOf("neutral-sign","blue-sign"),changed.state.ownedIds);assertEquals("neutral-sign",changed.state.selectedId)
        val before=rows();assertEquals(original,ok(store.decorations.commit(paid)));assertEquals(changed.state,ok(store.decorations.state(profile)));assertEquals(before,rows())
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.decorations.commit(command("buy-again",revision=3)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.decorations.commit(command("same-selection","neutral-sign",3,DecorationAction.APPLY_OWNED,0)));assertEquals(before,rows())
    }
    fun testInvalidMissingLockedStaleAndInsufficientInputsPreserveAllRows() {
        activate();val c=command();val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.decorations.commit(c));assertEquals(before,rows());unlock()
        val ready=rows()
        for(bad in listOf(c.copy(expectedRevision=Revision(1)),c.copy(expectedPrice=GameMoney(1)),c.copy(ruleVersion=RuleVersion("old")),c.copy(action=DecorationAction.APPLY_OWNED,expectedPrice=GameMoney(0))))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.decorations.commit(bad))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),store.decorations.commit(c.copy(profileId=ProfileId("foreign"))))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),store.decorations.commit(c.copy(source=InputSource.VEHICLE)))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.decorations.commit(c.copy(decorationId="missing")));assertEquals(ready,rows())
        ok(store.commitGameChange(GameChange(RequestId("spend"),TransactionKind.GARAGE,source,RuleVersion("fixture"),MoneyChange(MoneyDirection.DEBIT,GameMoney(1000)))))
        val empty=rows();assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS),store.decorations.commit(c));assertEquals(empty,rows())
    }
    fun testChangedContentsOrForeignRequestOwnerCannotReplayOrCharge() {
        activate();val free=command("free","neutral-sign",price=0);val original=ok(store.decorations.commit(free));val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.decorations.settlement(free.copy(expectedPrice=GameMoney(1))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.commitGameChange(GameChange(free.requestId,TransactionKind.GARAGE,source,RuleVersion("fixture"),MoneyChange(MoneyDirection.CREDIT,GameMoney(1)))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.decorations.commit(free.copy(requestId=RequestId("fixture-fund"))))
        assertEquals(original,ok(store.decorations.settlement(free)));assertEquals(before,rows())
    }
    fun testForeignSavedSlotCannotBeExecutedCancelledOrReplacedByDecoration() {
        activate();val d=ok(AssetTrialDefinitionProvider(context.assets).load());val offer=d.expansions.offers.first()
        val foreign=HostCheckpointCommand.Expand(RequestId("foreign-expand"),source,d.version,offer)
        ok(store.hostCommands.save(profile,foreign));val c=command("free","neutral-sign",price=0);val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.save(profile,wrap(c)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.execute(profile,wrap(c)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.cancelUnstarted(profile,wrap(c)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.decorations.commit(c));assertEquals(before,rows())
        ok(store.hostCommands.cancelUnstarted(profile,foreign));assertNotNull(ok(store.decorations.commit(c)))
    }
    fun testBalanceAndDefinitionAreRecheckedAfterSavedWithoutLosingRetry() {
        activate();unlock();val c=command();ok(store.hostCommands.save(profile,wrap(c)))
        definitionsAvailable=false;val saved=rows();assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.hostCommands.execute(profile,wrap(c)));assertEquals(saved,rows());definitionsAvailable=true
        val prior=definitions;definitions=DecorationDefinitions(RuleVersion("new-definition"),true,prior.items)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.execute(profile,wrap(c)));assertEquals(saved,rows());definitions=prior
        ok(store.commitGameChange(GameChange(RequestId("spend"),TransactionKind.GARAGE,source,RuleVersion("fixture"),MoneyChange(MoneyDirection.DEBIT,GameMoney(1000)))))
        val empty=rows();assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS),store.hostCommands.execute(profile,wrap(c)));assertEquals(empty,rows());assertEquals(HostCheckpointPhase.SAVED,ok(store.hostCommands.load(profile))!!.phase)
        ok(store.commitGameChange(GameChange(RequestId("refund-fixture"),TransactionKind.GARAGE,source,RuleVersion("fixture"),MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)))))
        assertEquals(GameMoney(900),(ok(store.hostCommands.execute(profile,wrap(c))) as HostCheckpointResult.Decoration).value.state.balance)
    }
    fun testTwoIndependentConnectionsCannotBuySameItemTwice() {
        activate();unlock();val other=open();ok(other.localProfileId());val start=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        try {
            val a=pool.submit<ContractResult<DecorationSettlement>>{start.await();store.decorations.commit(command("race-a"))}
            val b=pool.submit<ContractResult<DecorationSettlement>>{start.await();other.decorations.commit(command("race-b"))};start.countDown()
            val result=listOf(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))
            assertEquals(1,result.count{it is ContractResult.Success});assertEquals(1,result.count{it==ContractResult.Failure(ContractError.STATE_CONFLICT)})
            assertEquals(GameMoney(900),ok(store.balance()));assertEquals(Revision(1),ok(store.decorations.state(profile)).revision)
            assertEquals(1,settlementCount(RequestId("race-a"))+settlementCount(RequestId("race-b")))
        } finally {pool.shutdownNow();other.close()}
    }
    fun testEveryRequiredWriteFailureRollsBackMoneyOwnershipSelectionAndCheckpoint() {
        activate();unlock();val c=command();ok(store.hostCommands.save(profile,wrap(c)))
        for((table,event) in listOf("host_command_checkpoint" to "UPDATE","account" to "UPDATE","settlements" to "INSERT","decoration_module" to "UPDATE","decoration_requests" to "INSERT")) {
            raw{it.execSQL("CREATE TRIGGER ignore_decoration BEFORE $event ON $table BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
            assertEquals(table,ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,wrap(c)));assertEquals(table,before,rows());raw{it.execSQL("DROP TRIGGER ignore_decoration")}
        }
        assertEquals(GameMoney(900),(ok(store.hostCommands.execute(profile,wrap(c))) as HostCheckpointResult.Decoration).value.state.balance)
    }
    fun testSaveFinishAndCancelDoNotHideLateNonfinancialMoneyChanges() {
        activate();unlock();val c=command()
        raw{it.execSQL("CREATE TRIGGER late_slot_money AFTER INSERT ON host_command_checkpoint BEGIN UPDATE account SET balance=balance-1 WHERE id=1; END")};val empty=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.save(profile,wrap(c)));assertEquals(empty,rows());raw{it.execSQL("DROP TRIGGER late_slot_money")}
        execute(c);raw{it.execSQL("CREATE TRIGGER late_slot_money AFTER DELETE ON host_command_checkpoint BEGIN UPDATE account SET balance=balance-1 WHERE id=1; END")};val started=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.finish(profile,wrap(c)));assertEquals(started,rows());raw{it.execSQL("DROP TRIGGER late_slot_money")};finish(c)
        val free=command("free","neutral-sign",1,price=0);ok(store.hostCommands.save(profile,wrap(free)))
        raw{it.execSQL("CREATE TRIGGER late_slot_money AFTER DELETE ON host_command_checkpoint BEGIN UPDATE account SET balance=balance-1 WHERE id=1; END")};val saved=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.cancelUnstarted(profile,wrap(free)));assertEquals(saved,rows())
    }
    fun testLateExistingModuleDamageIsNotRepairedByNewApplication() {
        activate();unlock();val c=command();ok(store.hostCommands.save(profile,wrap(c)))
        raw{it.execSQL("CREATE TRIGGER damage_previous_module AFTER UPDATE ON account BEGIN UPDATE decoration_module SET digest='broken' WHERE id=1; END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,wrap(c)));assertEquals(before,rows())
    }
    fun testLateModuleResetCannotReturnSuccessfulPurchase() {
        activate();unlock();val c=command();ok(store.hostCommands.save(profile,wrap(c)))
        raw{it.execSQL("CREATE TRIGGER reset_decoration AFTER UPDATE ON decoration_module BEGIN UPDATE decoration_module SET payload=OLD.payload,digest=OLD.digest WHERE id=1; END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,wrap(c)));assertEquals(before,rows())
    }
    fun testLateCheckpointResetCannotLeavePaymentWithSavedCommand() {
        activate();unlock();val c=command();val wrapped=wrap(c);ok(store.hostCommands.save(profile,wrapped));val digest=HostCheckpointJson.digest(HostCheckpointJson.encode(profile,wrapped),0)
        raw{it.execSQL("CREATE TRIGGER reset_decoration_slot AFTER INSERT ON decoration_requests BEGIN UPDATE host_command_checkpoint SET phase=0,digest='$digest' WHERE id=1; END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,wrapped));assertEquals(before,rows())
    }
    fun testSavedAndStartedReopenUseOriginalWithoutCurrentDefinition() {
        activate();unlock();val c=command();ok(store.hostCommands.save(profile,wrap(c)));store.close();store=open()
        assertEquals(HostCheckpointPhase.SAVED,ok(store.hostCommands.load(profile))!!.phase);val r=ok(store.hostCommands.execute(profile,wrap(c)))
        store.close();definitionsAvailable=false;store=open();val before=rows();val reads=definitionReads
        assertEquals(HostCheckpointPhase.STARTED,ok(store.hostCommands.load(profile))!!.phase);assertEquals(r,ok(store.hostCommands.execute(profile,wrap(c))));assertEquals(before,rows())
        assertEquals(r,finish(c));assertNull(ok(store.hostCommands.load(profile)));assertEquals(reads,definitionReads);assertEquals(GameMoney(900),ok(store.balance()))
    }
    fun testLostOriginalOrModuleNeverBecomesEmptyOrReexecutesStarted() {
        activate();unlock();val c=command();execute(c);raw{it.execSQL("DELETE FROM decoration_requests")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.state(profile));assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.activate(profile))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,wrap(c)));assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.finish(profile,wrap(c)));assertEquals(before,rows())
    }
    fun testMissingIntroductionMarkerIsStorageFailure() {
        raw{it.execSQL("DELETE FROM decoration_module")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.state(profile));assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.activate(profile));assertEquals(before,rows())
    }
    fun testValidDigestCannotMakeFractionalStoredRevisionNormal() {
        activate();raw{db->val j=db.rawQuery("SELECT payload FROM decoration_module WHERE id=1",null).use{it.moveToFirst();JSONObject(it.getString(0))};j.getJSONObject("state").put("revision",0.5);val text=j.toString();db.execSQL("UPDATE decoration_module SET payload=?,digest=? WHERE id=1",arrayOf(text,ObservedDrivingJson.digest(text)))}
        val before=rows();assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.state(profile));assertEquals(before,rows())
    }
    fun testIgnoredOrReinsertedSlotDeletionCannotConfirmFinishOrCancel() {
        activate();val c=command("free","neutral-sign",price=0);val r=execute(c)
        for(sql in listOf("CREATE TRIGGER bad_finish BEFORE DELETE ON host_command_checkpoint BEGIN SELECT RAISE(IGNORE); END","CREATE TRIGGER bad_finish AFTER DELETE ON host_command_checkpoint BEGIN INSERT INTO host_command_checkpoint VALUES(OLD.id,OLD.payload,OLD.phase,OLD.digest); END")) {
            raw{it.execSQL(sql)};val before=rows();assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.finish(profile,wrap(c)));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER bad_finish")}
        }
        finish(c);assertEquals(r,ok(store.decorations.settlement(c)))
        unlock();val paid=command(revision=1);ok(store.hostCommands.save(profile,wrap(paid)));raw{it.execSQL("CREATE TRIGGER bad_cancel AFTER DELETE ON host_command_checkpoint BEGIN INSERT INTO host_command_checkpoint VALUES(OLD.id,OLD.payload,OLD.phase,OLD.digest); END")};val saved=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.cancelUnstarted(profile,wrap(paid)));assertEquals(saved,rows())
    }
    fun testLostPaidSettlementCannotBecomeOriginalAbsenceOrChargeAgain() {
        activate();unlock();val c=command();ok(store.decorations.commit(c));raw{it.delete("settlements","request_id=?",arrayOf(c.requestId.value))};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.state(profile));assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.settlement(c))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.commit(c));assertEquals(before,rows())
    }
    fun testUnknownActionOrFractionalMoneyWithValidDigestNeverBecomesNormalRecord() {
        activate();val c=command("free","neutral-sign",price=0);ok(store.decorations.commit(c))
        var original="";raw{db->db.rawQuery("SELECT payload FROM decoration_requests WHERE request_id=?",arrayOf(c.requestId.value)).use{it.moveToFirst();original=it.getString(0)}}
        for(mutate in listOf<(JSONObject)->Unit>({it.getJSONObject("command").put("action","FUTURE_ACTION")},{it.getJSONObject("before").put("balance",1000.5)})) {
            val j=JSONObject(original);mutate(j);val text=j.toString();raw{it.execSQL("UPDATE decoration_requests SET payload=?,digest=?",arrayOf(text,ObservedDrivingJson.digest(text)))};val before=rows()
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.state(profile));assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.commit(c));assertEquals(before,rows())
        }
    }
    fun testFailedSchema16UpgradePreservesOriginalFileAndVersion() {
        store.close();raw{it.version=16};val file=context.getDatabasePath(name);val before=file.readBytes();store=open()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.balance());store.close();assertTrue(before.contentEquals(file.readBytes()));raw{assertEquals(16,it.version);it.version=18};store=open()
    }
    fun testSchema16UpgradeKeepsExistingRowsAndExplicitlyUnintroducedDecoration() {
        val before=rows().filterNot{it.startsWith("decoration_")};store.close();raw{stripDecorationFixture(it);it.version=16};store=open();ok(store.localProfileId())
        assertEquals(before,rows().filterNot{it.startsWith("decoration_")});assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.decorations.state(profile));raw{assertEquals(18,it.version)}
        assertEquals(emptySet<String>(),activate().ownedIds);assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testExistingSchema18MissingTableIsNotSilentlyRecreated() {
        activate();raw{it.execSQL("DROP TABLE decoration_requests")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.state(profile));assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.decorations.activate(profile));assertEquals(before,rows())
    }
}
