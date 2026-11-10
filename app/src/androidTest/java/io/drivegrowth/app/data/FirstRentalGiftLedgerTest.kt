package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.rental.*
import org.json.JSONObject
import java.math.RoundingMode
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 실제 공용 DB/RentalLedger를 쓰되 profile/eligibility는 명시 SIMULATION 입력이다. A의 제품 NEW 발급 시험이 아니다. */
@Suppress("DEPRECATION")
class FirstRentalGiftLedgerTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source=InputSource.SIMULATION
    private val profile=ProfileId("explicit-simulation-profile")
    private val rentalId=RequestId("first-rental")
    private val progress=GachaProgress(Revision(7),2,4)
    private val car=OwnedVehicle(OwnedVehicleId("car"),VehicleId("hyundai_casper"),1,Revision(0),VehicleLifecycle.OWNED,
        Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.GACHA)
    private lateinit var name:String
    private lateinit var content:GameContentSnapshot
    private lateinit var contentResult:ContractResult<GameContentSnapshot>
    private var permission:ContractResult<Unit> = ContractResult.Success(Unit)
    private lateinit var store:SqliteGameChangeStore
    private lateinit var gift:FirstRentalGiftLedger
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return (r as ContractResult.Success).value}
    private fun fail(e:ContractError)=ContractResult.Failure(e)
    private fun open()=SqliteGameChangeStore(context,source,name,gachaAuthority={ContractResult.Success(GachaAuthority(source,content.catalog.contentVersion,
        content.ruleVersion,1,3,GameMoney(500),content.gacha.pity,content.gacha.guaranteedFrom,setOf(car.vehicleId),Rarity.entries.toSet()))},initialGachaProgress=progress)
    private fun module(s:SqliteGameChangeStore)=FirstRentalGiftLedger(s,{ContractResult.Success(profile)},{permission},{contentResult})
    override fun setUp() {
        super.setUp();name="first-gift-"+System.nanoTime()+".db"
        content=(AssetGameContent(context.assets).load() as ContentLoad.Loaded).snapshot;contentResult=ContractResult.Success(content);permission=ContractResult.Success(Unit)
        store=open();gift=module(store)
        // 선물 표 생성은 실제 공용 helper/schema9가 담당한다.
        ok(store.commitGameChange(GameChange(RequestId("seed"),TransactionKind.GARAGE,source,content.ruleVersion,
            MoneyChange(MoneyDirection.CREDIT,GameMoney(25)),VehicleWrite.Add(car))))
        val terms=RentalTerms(content.ruleVersion,SimulatedCustomer(30,36),99,EpochMillis(0),15,GameMoney(0),RoundingMode.DOWN)
        assertEquals(car.ownedVehicleId,ok(store.rental.assign(RentalRequest(rentalId,car.vehicleId,1),content.catalog,
            mapOf(car.vehicleId to RentalCategory.SEDAN),mapOf(car.vehicleId to GameMoney(0)),terms)))
        ok(store.rental.account(rentalId,EpochMillis(0),EpochMillis(3_600_000),null));ok(store.rental.settle(rentalId));ok(store.rental.finishReturn(rentalId,EpochMillis(4_500_000)))
    }
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    private fun command()=ok(FirstGrant(content).prepareOpeningGift(gift,profile,source))
    private fun reopen(){store.close();store=open();gift=module(store)}
    private fun raw(block:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun currentProgress()=ok(store.readGachaState()).progress
    private fun draw(id:RequestId=RequestId("draw"),p:GachaProgress=currentProgress())=GachaCommand(id,source,content.catalog.contentVersion,content.ruleVersion,p.revision,1,3,p.drawsSinceGuarantee,GachaTicketUse.FREE,
        GachaAward(car.copy(ownedVehicleId=OwnedVehicleId("drawn")),p.drawsSinceGuarantee+1,false))
    fun testActualFirstGrantPreparationCommitsOneFreeTicketAndReopensWholeResult() {
        val c=command();val cars=ok(store.listOwnedVehicles());val r=ok(gift.commitFirstRentalGift(c))
        assertEquals(GachaProgress(Revision(8),2,5),r.progress);assertEquals(GameMoney(25),r.balance);assertNull(r.transaction.money)
        assertEquals(TransactionKind.GARAGE,r.transaction.kind);assertEquals(c.requestId,r.transaction.requestId);assertEquals(source,r.transaction.source)
        assertEquals(FirstRentalGiftMarker(profile,c.requestId,c.cause),r.marker);assertEquals(cars,ok(store.listOwnedVehicles()))
        assertEquals(r,ok(gift.readFirstRentalGift(profile)).granted);assertEquals(r.progress,currentProgress())
        reopen();assertEquals(r,ok(gift.commitFirstRentalGift(c)));ok(store.markViewed(r.settlementId));assertEquals(r,ok(gift.commitFirstRentalGift(c)))
    }
    fun testZeroRevenueIsCompletedEvidence() {
        val completed=ok(store.rental.firstCompletedRental())!!;assertEquals(GameMoney(0),completed.receipt.settlement.transaction.money!!.amount)
        assertEquals(GachaProgress(Revision(8),2,5),ok(gift.commitFirstRentalGift(command())).progress)
    }
    fun testOriginalResultBeforeCurrentContentEligibilityAndProgressReads() {
        val c=command();val r=ok(gift.commitFirstRentalGift(c));ok(store.commitGacha(draw()))
        ok(store.commitGameChange(GameChange(RequestId("income"),TransactionKind.RENTAL,source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(9)))))
        contentResult=fail(ContractError.STORAGE_FAILURE);permission=fail(ContractError.DATA_UNAVAILABLE);reopen()
        assertEquals(r,ok(gift.commitFirstRentalGift(c)));assertEquals(GameMoney(34),ok(store.balance()))
        assertEquals(GachaProgress(Revision(9),3,4),currentProgress())
    }
    fun testMissingCurrentProgressDoesNotRegenerateOrBlockOriginalWholeReplay() {
        val c=command();val r=ok(gift.commitFirstRentalGift(c));store.close();raw{it.execSQL("DELETE FROM gacha_progress")};store=open();gift=module(store)
        assertEquals(r,ok(gift.commitFirstRentalGift(c)));assertEquals(fail(ContractError.STORAGE_FAILURE),gift.readFirstRentalGift(profile))
        assertEquals(fail(ContractError.DATA_UNAVAILABLE),store.readGachaState())
    }
    fun testNoProfileOrUnknownEligibilityNeverDefaultsToPayout() {
        val c=command();val unsupported=FirstRentalGiftLedger(store)
        assertEquals(fail(ContractError.DATA_UNAVAILABLE),unsupported.commitFirstRentalGift(c))
        for(e in listOf(ContractError.DATA_UNAVAILABLE,ContractError.STORAGE_FAILURE,ContractError.PERMISSION_DENIED)){
            permission=fail(e);assertEquals(fail(e),gift.commitFirstRentalGift(c));assertEquals(progress,currentProgress())
        }
    }
    fun testMissingCompletionAndBrokenCompletionAreNotBalanceOrCarEvidence() {
        val c=command();store.close();raw{it.execSQL("UPDATE rentals SET settlement_request='missing'")};store=open();gift=module(store)
        assertEquals(fail(ContractError.STORAGE_FAILURE),gift.commitFirstRentalGift(c));assertEquals(progress,currentProgress())
        store.close();raw{it.execSQL("DELETE FROM rentals")};store=open();gift=module(store)
        assertEquals(fail(ContractError.DATA_UNAVAILABLE),gift.commitFirstRentalGift(c));assertEquals(GameMoney(25),ok(store.balance()))
    }
    fun testCompletedRentalWithDebitReceiptIsNotValidGiftEvidence() {
        val c=command();store.close()
        raw{db->
            var saved="";db.rawQuery("SELECT result FROM settlements WHERE request_id='rental/settle/first-rental'",null).use{it.moveToFirst();saved=it.getString(0)}
            val damaged=JSONObject(saved);damaged.getJSONObject("money").put("direction","DEBIT")
            db.execSQL("UPDATE settlements SET result=? WHERE request_id='rental/settle/first-rental'",arrayOf(damaged.toString()))
        };store=open();gift=module(store)
        assertEquals(fail(ContractError.STORAGE_FAILURE),gift.readFirstRentalGift(profile));assertEquals(fail(ContractError.STORAGE_FAILURE),gift.commitFirstRentalGift(c))
        assertEquals(progress,currentProgress())
    }
    fun testForeignProfileAndSourceCannotClaimOrReplay() {
        val c=command()
        assertEquals(fail(ContractError.INVALID_INPUT),gift.commitFirstRentalGift(c.copy(profileId=ProfileId("foreign"))))
        assertEquals(fail(ContractError.INVALID_INPUT),gift.commitFirstRentalGift(c.copy(source=InputSource.VEHICLE)))
        val r=ok(gift.commitFirstRentalGift(c))
        assertEquals(fail(ContractError.INVALID_INPUT),gift.commitFirstRentalGift(c.copy(source=InputSource.VEHICLE)))
        assertEquals(fail(ContractError.INVALID_INPUT),gift.readFirstRentalGift(ProfileId("foreign")));assertEquals(r,ok(gift.commitFirstRentalGift(c)))
    }
    fun testAllChangedCommandFieldsAndSecondClaimConflict() {
        val c=command();val r=ok(gift.commitFirstRentalGift(c))
        val bad=listOf(c.copy(requestId=RequestId("second")),c.copy(contentVersion=ContentVersion("other")),c.copy(ruleVersion=RuleVersion("other")),
            c.copy(expectedProgressRevision=Revision(8)),c.copy(cause=c.cause.copy(rentalId=RequestId("other"))),c.copy(cause=c.cause.copy(settlementId=SettlementId("other"))))
        bad.forEach{assertEquals(fail(ContractError.STATE_CONFLICT),gift.commitFirstRentalGift(it))};assertEquals(r.progress,currentProgress())
    }
    fun testNewCommandVersionsAndRevisionAreCheckedAgainstCurrentState() {
        val c=command()
        for(bad in listOf(c.copy(contentVersion=ContentVersion("old")),c.copy(ruleVersion=RuleVersion("old")),c.copy(expectedProgressRevision=Revision(6))))
            assertEquals(fail(ContractError.STATE_CONFLICT),gift.commitFirstRentalGift(bad))
        assertEquals(progress,currentProgress())
    }
    fun testNoExistingProgressIsCreatedByClaim() {
        val c=command();store.close();raw{it.execSQL("DELETE FROM gacha_progress")};store=open();gift=module(store)
        assertEquals(fail(ContractError.DATA_UNAVAILABLE),gift.commitFirstRentalGift(c));assertEquals(fail(ContractError.DATA_UNAVAILABLE),store.readGachaState())
    }
    fun testTicketAndRevisionMaximumAndJustBeforeMaximum() {
        val c=command()
        for(p in listOf(progress.copy(revision=Revision(Int.MAX_VALUE)),progress.copy(freeTickets=Long.MAX_VALUE))){
            ok(store.atomic{db->db.execSQL("UPDATE gacha_progress SET revision=?,tickets=?",arrayOf(p.revision.value,p.freeTickets));ContractResult.Success(Unit)})
            assertEquals(fail(ContractError.INVALID_INPUT),gift.commitFirstRentalGift(c.copy(expectedProgressRevision=p.revision)));assertEquals(p,currentProgress())
        }
        ok(store.atomic{db->db.execSQL("UPDATE gacha_progress SET revision=?,tickets=?",arrayOf(Int.MAX_VALUE-1,Long.MAX_VALUE-1));ContractResult.Success(Unit)})
        assertEquals(GachaProgress(Revision(Int.MAX_VALUE),2,Long.MAX_VALUE),ok(gift.commitFirstRentalGift(c.copy(expectedProgressRevision=Revision(Int.MAX_VALUE-1)))).progress)
    }
    fun testCommitFailureLeavesNoProgressMarkerOrReceiptAndCanRetry() {
        val c=command();val before=ok(gift.readFirstRentalGift(profile));val receipts=ok(store.listUnviewedSettlements())
        store.beforeCommit={throw SQLiteException("선물 COMMIT 전 실패")};assertEquals(fail(ContractError.STORAGE_FAILURE),gift.commitFirstRentalGift(c));store.beforeCommit=null
        assertEquals(before,ok(gift.readFirstRentalGift(profile)));assertEquals(receipts,ok(store.listUnviewedSettlements()))
        assertEquals(GachaProgress(Revision(8),2,5),ok(gift.commitFirstRentalGift(c)).progress)
    }
    fun testResultInsertFailureRollsBackProgressCommonReceiptAndMarker() {
        val c=command();ok(store.atomic{db->db.execSQL("CREATE TRIGGER reject_gift BEFORE INSERT ON first_gift_requests BEGIN SELECT RAISE(ABORT,'gift'); END");ContractResult.Success(Unit)})
        val receipts=ok(store.listUnviewedSettlements());assertEquals(fail(ContractError.STORAGE_FAILURE),gift.commitFirstRentalGift(c));assertEquals(progress,currentProgress())
        assertEquals(receipts,ok(store.listUnviewedSettlements()));assertNull(ok(gift.readFirstRentalGift(profile)).granted)
    }
    private fun deletedHalf(table:String){val c=command();ok(gift.commitFirstRentalGift(c));store.close();raw{it.execSQL("DELETE FROM $table WHERE request_id=?",arrayOf(c.requestId.value))};store=open();gift=module(store)
        val file=context.getDatabasePath(name);store.close();val before=file.readBytes();store=open();gift=module(store)
        assertEquals(fail(ContractError.STORAGE_FAILURE),gift.commitFirstRentalGift(c));assertEquals(GachaProgress(Revision(8),2,5),currentProgress());store.close();assertTrue(before.contentEquals(file.readBytes()));store=open();gift=module(store)}
    fun testDeletedMarkerIsStorageFailure()=deletedHalf("first_gift_markers")
    fun testDeletedWholeResultIsStorageFailure()=deletedHalf("first_gift_requests")
    fun testDeletedCommonReceiptIsStorageFailure()=deletedHalf("settlements")
    fun testStrictNumbersInCommandOriginalAndResultPreserveBytes() {
        val c=command();ok(gift.commitFirstRentalGift(c));store.close()
        for(column in listOf("command","original_state","result")){
            var original="";raw{db->db.rawQuery("SELECT $column FROM first_gift_requests",null).use{it.moveToFirst();original=it.getString(0)}}
            val field=if(column=="command")"revision" else "tickets"
            for(literal in listOf("\"1\"","1.5","1e0","9223372036854775808","null","-1")){
                val bad=original.replaceFirst(Regex("\\\"$field\\\":\\d+"),"\"$field\":$literal");assertTrue(bad!=original)
                raw{it.execSQL("UPDATE first_gift_requests SET $column=?",arrayOf(bad))};val file=context.getDatabasePath(name);val bytes=file.readBytes();store=open();gift=module(store)
                assertEquals(fail(ContractError.STORAGE_FAILURE),gift.commitFirstRentalGift(c));store.close();assertTrue(bytes.contentEquals(file.readBytes()))
                raw{it.execSQL("UPDATE first_gift_requests SET $column=?",arrayOf(original))}
            }
        };store=open();gift=module(store);assertEquals(GachaProgress(Revision(8),2,5),ok(gift.commitFirstRentalGift(c)).progress)
    }
    fun testInvalidRawCurrentProgressDoesNotBecomeNormalCounterOrTickets() {
        val c=command()
        for(statement in listOf("UPDATE gacha_progress SET tickets=4.5","UPDATE gacha_progress SET counter=30","UPDATE gacha_progress SET revision=2147483648")){
            store.close();raw{it.execSQL(statement)};store=open();gift=module(store)
            assertEquals(fail(ContractError.STORAGE_FAILURE),gift.commitFirstRentalGift(c))
            store.close();raw{it.execSQL("UPDATE gacha_progress SET revision=7,counter=2,tickets=4")};store=open();gift=module(store)
        }
    }
    fun testMetadataReceiptDoesNotPretendToChangeMoneyOrVehicles() {
        val c=command();val r=ok(gift.commitFirstRentalGift(c));val receipt=ok(store.listUnviewedSettlements()).single{it.settlementId==r.settlementId}
        assertNull(receipt.transaction.money);assertNull(receipt.changedVehicle);assertEquals(GameMoney(25),receipt.balance)
    }
    fun testCodecRoundTripsActualWholeResultAndOriginalCompletedRental() {
        val c=command();val before=ok(gift.readFirstRentalGift(profile));val r=ok(gift.commitFirstRentalGift(c))
        assertEquals(c,FirstGiftJson.command(JSONObject(FirstGiftJson.command(c).toString())))
        assertEquals(before,FirstGiftJson.state(JSONObject(FirstGiftJson.state(before).toString())))
        assertEquals(r,FirstGiftJson.result(c,JSONObject(FirstGiftJson.result(r).toString())))
    }
    fun testGenericAndOtherDomainRequestsCannotBecomeGift() {
        val c=command();val id=RequestId("generic-first")
        ok(store.commitGameChange(GameChange(id,TransactionKind.GARAGE,source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1)))))
        for(key in listOf(id,RequestId("delivery/reserved"),RequestId("drive/reserved"),RequestId("rental/settle/first-rental")))
            assertEquals(fail(ContractError.STATE_CONFLICT),gift.commitFirstRentalGift(c.copy(requestId=key)))
        val r=ok(gift.commitFirstRentalGift(c));assertEquals(fail(ContractError.STATE_CONFLICT),store.commitGameChange(GameChange(c.requestId,TransactionKind.GARAGE,source,content.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1)))))
        assertEquals(r.progress,currentProgress())
    }
    private fun race(a:()->ContractResult<*>,b:()->ContractResult<*>):List<ContractResult<*>>{
        val pool=Executors.newFixedThreadPool(2);val gate=CountDownLatch(1)
        try{val jobs=listOf(a,b).map{fn->pool.submit(Callable{gate.await();fn()})};gate.countDown();return jobs.map{it.get(10,TimeUnit.SECONDS)}}finally{pool.shutdownNow()}
    }
    fun testTwoConnectionsDifferentGiftRequestsOnlyOnePays() {
        val c=command();val other=open();val otherGift=module(other)
        try{val results=race({gift.commitFirstRentalGift(c)},{otherGift.commitFirstRentalGift(c.copy(requestId=RequestId("second")))})
            assertEquals(1,results.count{it is ContractResult.Success});assertEquals(1,results.count{it==fail(ContractError.STATE_CONFLICT)});assertEquals(GachaProgress(Revision(8),2,5),currentProgress())
        }finally{other.close()}
    }
    fun testTwoConnectionsSameCommandReturnOneWholeReceipt() {
        val c=command();val other=open();val otherGift=module(other)
        try{val results=race({gift.commitFirstRentalGift(c)},{otherGift.commitFirstRentalGift(c)})
            assertEquals(ok(results[0]),ok(results[1]));assertEquals(GachaProgress(Revision(8),2,5),currentProgress())
        }finally{other.close()}
    }
    fun testGiftRacesFreeTicketConsumptionOnTheSameRevision() {
        val c=command();val d=draw();val other=open()
        try{val results=race({gift.commitFirstRentalGift(c)},{other.commitGacha(d)})
            assertEquals(1,results.count{it is ContractResult.Success});assertEquals(1,results.count{it==fail(ContractError.STATE_CONFLICT)})
            if(results[0] is ContractResult.Success)ok(other.commitGacha(draw(p=currentProgress())))
            else ok(gift.commitFirstRentalGift(c.copy(expectedProgressRevision=Revision(8))))
            assertEquals(GachaProgress(Revision(9),3,4),currentProgress());assertEquals(GameMoney(25),ok(store.balance()))
        }finally{other.close()}
    }
}
