package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import java.time.LocalDate

/** 실제 공통 helper/schema12/단일slot. SDK·도착증명 생성·제품 Activity 시험이 아니다. */
@Suppress("DEPRECATION")
class DeliveryCheckpointTest:InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private val source=InputSource.SIMULATION
    private val content=ContentVersion("checkpoint-content")
    private val rules=RuleVersion("checkpoint-delivery")
    private val settle=DeliverySettlementRules(rules,86400000,86400000,5)
    private val change=DeliveryChangeRules(rules,10)
    private val request=RequestId("delivery-host-original")
    private val route=RouteId("delivery-host-route")
    private val start=EpochMillis(1000000)
    private val income=DeliveryOffer.Income(DeliveryOfferId("offer-0"),VehicleId("hyundai_casper"),GameMoney(400))
    private val purchase=DeliveryOffer.Purchase(DeliveryOfferId("offer-1"),VehicleId("hyundai_avante"),Rarity.RARE,GameMoney(1000))
    private val list=DeliveryList(1,source,LocalDate.of(2026,11,10),content,rules,listOf(income,purchase),2)
    private val proposed=DeliveryProposed(DestinationKey("fixture-destination"),EstimatedRouteDistance(20000,source),list,list.offers)
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private val profile get()=ok(store.localProfileId())
    private fun open()=SqliteGameChangeStore(context,source,name)
    private fun <T> ok(value:ContractResult<T>):T {assertTrue(value.toString(),value is ContractResult.Success);return(value as ContractResult.Success).value}
    override fun setUp(){super.setUp();name="delivery-checkpoint-${System.nanoTime()}.db";store=open();ok(store.commitGameChange(GameChange(RequestId("fund"),TransactionKind.GARAGE,source,rules,MoneyChange(MoneyDirection.CREDIT,GameMoney(10000)))));ok(store.delivery.saveList(list))}
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    private fun raw(f:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(f)
    private fun rows():List<String> = buildList{raw{db->for(t in listOf("account","vehicles","settlements","delivery_lists","delivery_requests","delivery_active","delivery_routes","delivery_changes","delivery_outcomes","delivery_held","delivery_hold_outcomes","delivery_route_history","host_command_checkpoint"))db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{r->while(r.moveToNext())add(t+":"+(0 until r.columnCount).joinToString("|"){if(r.isNull(it))"<null>"else r.getString(it)})}}}
    private fun wrap(op:DeliveryCheckpointOperation)=HostCheckpointCommand.Delivery(content,source,op)
    private fun select(offer:DeliveryOffer=income)=wrap(DeliveryCheckpointOperation.Select(proposed,offer.id,request,route,start))
    private fun execute(c:HostCheckpointCommand.Delivery):DeliveryCheckpointReceipt {ok(store.hostCommands.save(profile,c));return(ok(store.hostCommands.execute(profile,c)) as HostCheckpointResult.Delivery).value}
    private fun finish(c:HostCheckpointCommand.Delivery)=ok(store.hostCommands.finish(profile,c))
    private fun choose(offer:DeliveryOffer=income){val c=select(offer);execute(c);finish(c)}
    private fun arrival(missing:Boolean=false):HostCheckpointCommand.Delivery {
        val replacement=list.copy(offers=list.offers.map{if(it.id==(ok(store.delivery.active())!!.offer.id))DeliveryOffer.Income(DeliveryOfferId("offer-2"),VehicleId("hyundai_casper"),GameMoney(500))else it},nextOfferNumber=3)
        val evidence=if(missing)Observation.Missing(MissingReason.NO_DATA) else Observation.Available(DeliveryArrivalEvidence(route,source,EpochMillis(start.value+1000)))
        return wrap(DeliveryCheckpointOperation.Arrival(settle,request,evidence,EpochMillis(start.value+1000),list,replacement))
    }
    private fun fillGarage(){repeat(3){n->val car=OwnedVehicle(OwnedVehicleId("full-$n"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT);ok(store.commitGameChange(GameChange(RequestId("add-$n"),TransactionKind.GARAGE,source,rules,null,VehicleWrite.Add(car))))}}
    fun testSelectIncomeStartsRouteAndFinishesWithoutCommonPayment() {
        val c=select();val result=execute(c);assertTrue(result is DeliveryCheckpointReceipt.Selected);assertNull((result as DeliveryCheckpointReceipt.Selected).value.settlement)
        assertEquals(route,ok(store.delivery.inProgress())!!.route);assertEquals(start,ok(store.delivery.inProgress())!!.startedAt);assertEquals(GameMoney(0),ok(store.delivery.terms())!!.paid)
        assertFalse(ok(store.delivery.terms())!!.changeUsed);finish(c);assertNull(ok(store.hostCommands.load(profile)));assertEquals(GameMoney(10000),ok(store.balance()))
    }
    fun testPurchaseSelectAndRouteFailureRollBackTogetherThenRetry() {
        val c=select(purchase);raw{it.execSQL("CREATE TRIGGER ignore_route BEFORE INSERT ON delivery_routes BEGIN SELECT RAISE(IGNORE); END")};ok(store.hostCommands.save(profile,c));val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,c));assertEquals(before,rows());assertNull(ok(store.delivery.active()))
        raw{it.execSQL("DROP TRIGGER ignore_route")};execute(c);assertEquals(GameMoney(9000),ok(store.balance()));assertEquals(GameMoney(1000),ok(store.delivery.terms())!!.paid);finish(c)
    }
    fun testPaidSelectionReplaysWholeOriginalAfterReopenAndAnotherActiveRequest() {
        val conflict = ContractResult.Failure(ContractError.STATE_CONFLICT)
        val beforeSelection = rows()
        assertEquals(conflict, store.delivery.select(proposed, purchase.id, RequestId("fund")))
        assertEquals(beforeSelection, rows())

        val originalCommand = select(purchase)
        val original = execute(originalCommand) as DeliveryCheckpointReceipt.Selected
        val payment = checkNotNull(original.value.selected.payment)
        val receipt = checkNotNull(original.value.settlement)
        assertEquals(request, receipt.transaction.requestId)
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(1000)), receipt.transaction.money)
        assertEquals(GameMoney(9000), receipt.balance)
        val committed = rows()
        assertEquals(conflict, store.commitGameChange(payment))
        assertEquals(committed, rows())
        finish(originalCommand)
        ok(store.delivery.abandon(change, request))

        val current = wrap(DeliveryCheckpointOperation.Select(
            proposed, income.id, RequestId("new-active"), RouteId("new-active-route"), start))
        execute(current)
        finish(current)
        val active = ok(store.delivery.inProgress())
        assertEquals(current.requestId, active?.active?.requestId)
        assertTrue(receipt.balance != ok(store.balance()))
        store.close()
        store = open()
        val beforeReplay = rows()
        assertEquals(original, execute(originalCommand))
        finish(originalCommand)
        assertEquals(active, ok(store.delivery.inProgress()))
        assertEquals(beforeReplay, rows())
    }

    fun testOrphanedPurchasePaymentCannotReplayThroughCommonWriter() {
        val command = select(purchase)
        val original = execute(command) as DeliveryCheckpointReceipt.Selected
        val payment = checkNotNull(original.value.selected.payment)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGameChange(payment))
        // Only selection history is missing. The common debit, STARTED checkpoint and route still identify the purchase.
        raw { it.execSQL("DELETE FROM delivery_requests WHERE request_id=?", arrayOf(request.value)) }
        store.close()
        store = open()
        val damaged = rows()
        assertEquals(HostCheckpointPhase.STARTED, ok(store.hostCommands.load(profile))?.phase)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.delivery.inProgress())
        val replay = store.commitGameChange(payment)
        val reason = store.lastStorageFailure
        assertEquals(damaged, rows())
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), replay)
        assertEquals(LedgerFailureReason.INVALID_RECORD, reason)
    }

    fun testOrphanedCompletedPurchasePaymentCannotReplayAfterReopen() {
        val command = select(purchase)
        val original = execute(command) as DeliveryCheckpointReceipt.Selected
        val payment = checkNotNull(original.value.selected.payment)
        finish(command)
        ok(store.delivery.abandon(change, request))
        assertNull(ok(store.delivery.inProgress()))
        assertNull(ok(store.hostCommands.load(profile)))
        raw { it.execSQL("DELETE FROM delivery_requests WHERE request_id=?", arrayOf(request.value)) }
        store.close()
        store = open()
        val damaged = rows()
        val replay = store.commitGameChange(payment)
        val reason = store.lastStorageFailure
        assertEquals(damaged, rows())
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), replay)
        assertEquals(LedgerFailureReason.INVALID_RECORD, reason)
    }

    fun testGeneralDeliveryDebitWithoutFeatureHistoryStillReplays() {
        val command = GameChange(RequestId("standalone-delivery-debit"), TransactionKind.DELIVERY,
            source, rules, MoneyChange(MoneyDirection.DEBIT, GameMoney(1000)))
        val original = ok(store.commitGameChange(command))
        assertEquals(GameMoney(9000), original.balance)
        store.close()
        store = open()
        val committed = rows()
        assertEquals(original, ok(store.commitGameChange(command)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            store.commitGameChange(command.copy(money = MoneyChange(MoneyDirection.DEBIT, GameMoney(1001)))))
        assertEquals(committed, rows())
    }

    fun testAwaitingEvidenceReturnsSavedWithoutFalseCompletionAndAllowsNewEvidence() {
        choose();val c=arrival(true);val result=execute(c);assertFalse(result.isFinal);assertTrue((result as DeliveryCheckpointReceipt.Arrived).value.outcome is DeliveryOutcome.AwaitingEvidence)
        assertEquals(HostCheckpointPhase.SAVED,ok(store.hostCommands.load(profile))!!.phase);assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.finish(profile,c))
        ok(store.hostCommands.cancelUnstarted(profile,c));val next=arrival();assertTrue(execute(next).isFinal);finish(next);assertEquals(GameMoney(10400),ok(store.balance()))
    }
    fun testFinalIncomeArrivalReplaysWholeOriginalAfterReopenAndNewBalance() {
        choose();val c=arrival();val original=execute(c);store.close();store=open();assertEquals(original,(ok(store.hostCommands.execute(profile,c)) as HostCheckpointResult.Delivery).value)
        finish(c);ok(store.commitGameChange(GameChange(RequestId("spend"),TransactionKind.GARAGE,source,rules,MoneyChange(MoneyDirection.DEBIT,GameMoney(100)))))
        assertEquals(original,execute(c));finish(c);assertEquals(GameMoney(10300),ok(store.balance()));assertNull(ok(store.delivery.active()))
    }
    fun testOriginalSelectReplaysAfterDeliveryFinishedWithoutReactivating() {
        val original=execute(select());finish(select());val arrival=arrival();execute(arrival);finish(arrival)
        assertEquals(original,execute(select()));finish(select());assertNull(ok(store.delivery.active()))
        val changed=select().copy(operation=(select().operation as DeliveryCheckpointOperation.Select).copy(at=EpochMillis(start.value+1)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.save(profile,changed))
    }
    fun testReplacementMismatchRollsBackAndDoesNotRerandomize() {
        choose();val c=arrival();val op=c.operation as DeliveryCheckpointOperation.Arrival;val bad=c.copy(operation=op.copy(expected=op.expected.copy(nextOfferNumber=99)))
        ok(store.hostCommands.save(profile,bad));val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.execute(profile,bad));assertEquals(before,rows())
        ok(store.hostCommands.cancelUnstarted(profile,bad));execute(c);finish(c)
    }
    fun testIncomeAbandonFinishesWithoutPaymentAndPreservesOriginal() {
        choose();val c=wrap(DeliveryCheckpointOperation.Abandon(change,request));val result=execute(c);assertNull((result as DeliveryCheckpointReceipt.Abandoned).value.settlement)
        finish(c);assertEquals(result,execute(c));finish(c);assertEquals(GameMoney(10000),ok(store.balance()))
    }
    fun testPurchaseChangeUsesWholeCommandAndActualTerms() {
        choose(purchase);val offer=DeliveryOffer.Purchase(DeliveryOfferId("offer-0"),VehicleId("hyundai_casper"),Rarity.COMMON,GameMoney(800))
        val nextList=list.copy(offers=listOf(offer),nextOfferNumber=1);val next=DeliveryProposed(DestinationKey("new-destination"),EstimatedRouteDistance(20000,source),nextList,nextList.offers)
        val c=wrap(DeliveryCheckpointOperation.Change(change,DeliveryChangeOffer(request,30000,50000,next),offer.id,RouteId("changed-route")))
        val result=execute(c);assertTrue(ok(store.delivery.terms())!!.changeUsed);assertEquals(GameMoney(800),ok(store.delivery.terms())!!.paid);assertEquals(GameMoney(9200),ok(store.balance()));finish(c)
        assertEquals(result,execute(c));finish(c);assertEquals(RouteId("changed-route"),ok(store.delivery.inProgress())!!.route)
    }
    fun testVehicleHeldAndStillHeldAreDifferentFromFinalRefund() {
        fillGarage();choose(purchase);val arrive=arrival();val held=execute(arrive);assertTrue((held as DeliveryCheckpointReceipt.Arrived).value.outcome is DeliveryOutcome.VehicleHeld);finish(arrive)
        val c=wrap(DeliveryCheckpointOperation.Hold(settle,request,EpochMillis(start.value+2000)));val waiting=execute(c);assertFalse(waiting.isFinal);assertEquals(HostCheckpointPhase.SAVED,ok(store.hostCommands.load(profile))!!.phase)
        ok(store.hostCommands.cancelUnstarted(profile,c));val refund=c.copy(operation=DeliveryCheckpointOperation.Hold(settle,request,EpochMillis(start.value+1000+86400000)))
        assertTrue(execute(refund).isFinal);finish(refund);assertEquals(GameMoney(9950),ok(store.balance()));assertTrue(ok(store.delivery.held()).isEmpty())
    }
    fun testIgnoredStartedOrMetadataCannotCommitPaymentAndOriginalRows() {
        for(table in listOf("host_command_checkpoint","delivery_requests")) {
            val c=select(purchase);ok(store.hostCommands.save(profile,c));raw{it.execSQL("CREATE TRIGGER ignore_host_delivery BEFORE UPDATE ON $table BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,c));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER ignore_host_delivery")};ok(store.hostCommands.cancelUnstarted(profile,c))
        }
    }
    fun testIgnoredActualMoneyCannotProduceSuccessfulPurchase() {
        val c=select(purchase);ok(store.hostCommands.save(profile,c));raw{it.execSQL("CREATE TRIGGER ignore_delivery_money BEFORE UPDATE ON account BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,c));assertEquals(before,rows())
    }
    fun testSavedCancellationAndForeignCommandNeverClearEachOther() {
        val c=select();ok(store.hostCommands.save(profile,c));val before=rows()
        val other=wrap(DeliveryCheckpointOperation.Abandon(change,request));assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.save(profile,other));assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.cancelUnstarted(profile,other));assertEquals(before,rows())
        ok(store.hostCommands.cancelUnstarted(profile,c));assertNull(ok(store.hostCommands.load(profile)))
    }
    fun testV11MigrationPreservesQuestCompletenessAndAllExistingRows() {
        val before=rows();val beforeBalance=ok(store.balance());var tracking:List<String> = emptyList();raw{db->tracking=db.rawQuery("SELECT * FROM quest_tracking",null).use{r->buildList{while(r.moveToNext())add((0 until r.columnCount).joinToString("|"){r.getString(it)})}}}
        store.close();raw{stripDeliveryCheckpointFixture(it);it.version=11};store=open()
        // helper 생성자는 lazy다. 공개 읽기로 실제 upgrade를 연 뒤 버전/전체 행을 대조한다.
        assertEquals(beforeBalance,ok(store.balance()));assertEquals(before,rows())
        raw{db->for(table in listOf("delivery_requests","delivery_changes","delivery_outcomes","delivery_hold_outcomes"))db.rawQuery("PRAGMA table_info($table)",null).use{r->var found=false;while(r.moveToNext())if(r.getString(1)=="host_command")found=true;assertTrue(table,found)}}
        raw{db->assertEquals(20, db.version);val after=db.rawQuery("SELECT * FROM quest_tracking",null).use{r->buildList{while(r.moveToNext())add((0 until r.columnCount).joinToString("|"){r.getString(it)})}};assertEquals(tracking,after)}
    }
    fun testIgnoredSaveAndNonfinalSavedRestorationCannotReportDurability() {
        val first=select();raw{it.execSQL("CREATE TRIGGER ignore_save BEFORE INSERT ON host_command_checkpoint BEGIN SELECT RAISE(IGNORE); END")};val initial=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.save(profile,first));assertEquals(initial,rows());raw{it.execSQL("DROP TRIGGER ignore_save")}
        choose();val c=arrival(true);ok(store.hostCommands.save(profile,c));raw{it.execSQL("CREATE TRIGGER ignore_restore BEFORE UPDATE OF phase ON host_command_checkpoint WHEN NEW.phase=0 BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,c));assertEquals(before,rows())
    }
    fun testFinishDeleteFailureAndMissingOriginalMetadataKeepPending() {
        val c=select();val first=execute(c);raw{it.execSQL("CREATE TRIGGER ignore_finish BEFORE DELETE ON host_command_checkpoint BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.finish(profile,c));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER ignore_finish")}
        assertEquals(first,(ok(store.hostCommands.execute(profile,c)) as HostCheckpointResult.Delivery).value)
        raw{it.execSQL("UPDATE delivery_requests SET host_command=NULL")};val damaged=rows()
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.hostCommands.finish(profile,c));assertEquals(damaged,rows());assertEquals(HostCheckpointPhase.STARTED,ok(store.hostCommands.load(profile))!!.phase)
    }


    // 독립 검토 재현: 이 사본에만 추가. 실제 기기 미실행이며 기대는 전체 rollback이다.
    fun testIndependentIgnoredHeldDeletionMustNotConfirmRefund() {
        fillGarage();choose(purchase);val arrive=arrival();execute(arrive);finish(arrive)
        val refund=wrap(DeliveryCheckpointOperation.Hold(settle,request,EpochMillis(start.value+1000+86400000)))
        ok(store.hostCommands.save(profile,refund))
        raw{it.execSQL("CREATE TRIGGER ignore_held_delete BEFORE DELETE ON delivery_held BEGIN SELECT RAISE(IGNORE); END")}
        val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,refund))
        assertEquals(before,rows())
    }

    // 독립 검토 재현: 보류 생성이 실제로 저장되지 않으면 활성/목록/원정산 전체를 보존해야 한다.
    fun testIndependentIgnoredHeldInsertMustNotCompleteArrival() {
        fillGarage();choose(purchase);val arrive=arrival()
        ok(store.hostCommands.save(profile,arrive))
        raw{it.execSQL("CREATE TRIGGER ignore_held_insert BEFORE INSERT ON delivery_held BEGIN SELECT RAISE(IGNORE); END")}
        val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,arrive))
        assertEquals(before,rows())
    }
}
