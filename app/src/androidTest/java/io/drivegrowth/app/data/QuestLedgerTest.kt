package io.drivegrowth.app.data
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.quest.*
import java.time.*
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch

/** 기존25개 모듈 경계 시험. E schema11 helper의 실제 프로필을 쓰고 시험 사건 준비만 명시한다. */
@Suppress("DEPRECATION")
class QuestLedgerTest:InstrumentationTestCase() {
 private val context get()=instrumentation.targetContext
 private var issuedProfile:ProfileId?=null;private val p get()=requireNotNull(issuedProfile);private val source=InputSource.SIMULATION
 private var now=Instant.parse("2026-11-10T03:00:00Z").toEpochMilli()
 private lateinit var name:String;private lateinit var store:SqliteGameChangeStore;private lateinit var ledger:QuestLedger
 private lateinit var receipt:Settlement
 private val zone=ZoneId.of("Asia/Seoul");private val v=RuleVersion("quest-fixture-v1")
 private fun definitions()=QuestDefinitions(v,QuestCadence.entries.flatMap{c->QuestAction.entries.map{a->QuestDefinition(c.name.lowercase()+"-"+a.slug,v,c,a,1,GameMoney(50),zone,true)}})
 private var supplied:ContractResult<QuestDefinitions>?=null
 private fun open()=SqliteGameChangeStore(context,source,name,clock={now})
 private fun module(s:SqliteGameChangeStore)=QuestLedger(s,{ContractResult.Success(p)},{supplied?:ContractResult.Success(definitions())})
 private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
 override fun setUp(){super.setUp();name="quest-module-${System.nanoTime()}.db";store=open();issuedProfile=ok(store.localProfileId());ledger=module(store)
  receipt=ok(store.commitGameChange(GameChange(RequestId("fixture-result"),TransactionKind.GARAGE,source,v,MoneyChange(MoneyDirection.CREDIT,GameMoney(100)))))
  ok(store.markViewed(receipt.settlementId))
  // 초기 열람 hook 사건은 이 모듈의 명시 record 경계를 시험하기 위해 fixture에서만 제거한다.
  raw{it.execSQL("DELETE FROM quest_events")}
 }
 override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
 private fun raw(f:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(f)
 private fun event(id:String="event",at:EpochMillis?=EpochMillis(now))=QuestEvent(id,p,source,QuestCause.ResultViewed(receipt.transaction.requestId,receipt.settlementId),at)
 private fun record(e:QuestEvent=event())=store.atomic{ledger.recordConfirmedEvent(it,e)}
 private fun state()=ok(ledger.read(p,Observation.Available(EpochMillis(now)))).first{it.definition.id=="daily-result-viewed"}
 private fun command(id:String="one"):QuestClaimCommand {val s=state();return QuestClaimCommand(RequestId("quest/claim/$id"),p,source,s.definition,s.period,s.revision!!)}
 private fun rows():List<String> = buildList{raw{db->for(t in listOf("account","settlements","quest_tracking","quest_events","quest_claims")) db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{r->while(r.moveToNext())add(t+":"+(0 until r.columnCount).joinToString("|"){if(r.isNull(it))"<null>" else r.getString(it)})}}}
 fun testEventReplayAndClaimAreOnceAcrossReopen(){ok(record());ok(record());assertEquals(1,state().count)
  val c=command();val first=ok(ledger.claim(c));assertEquals(GameMoney(150),first.settlement.balance);assertEquals(QuestStatus.CLAIMED,state().status)
  val before=rows();store.close();store=open();ledger=module(store);assertEquals(first,ok(ledger.claim(c)));assertEquals(before,rows());assertEquals(setOf(c.requestId),ok(ledger.claimedRequestIds(p)))
 }
 fun testSameEventOtherMeaningOrAliasCannotAdvance(){ok(record());val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),record(event().copy(occurredAt=EpochMillis(now+1))))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),record(event("alias")));assertEquals(before,rows())
 }
 fun testSameRequestOtherRewardAndDifferentRequestSamePeriodCannotPayTwice(){ok(record());val c=command();ok(ledger.claim(c));val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),ledger.claim(c.copy(definition=c.definition.copy(reward=GameMoney(51)))))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),ledger.claim(c.copy(requestId=RequestId("quest/claim/other"))));assertEquals(before,rows())
 }
 fun testClaimReplayPrecedesExpiryDefinitionFailureAndLaterSpending(){ok(record());val c=command();val first=ok(ledger.claim(c))
  ok(store.commitGameChange(GameChange(RequestId("later"),TransactionKind.GARAGE,source,v,MoneyChange(MoneyDirection.DEBIT,GameMoney(100)))))
  now+=8*86400000L;supplied=ContractResult.Failure(ContractError.DATA_UNAVAILABLE);val before=rows();assertEquals(first,ok(ledger.claim(c)));assertEquals(GameMoney(50),ok(store.balance()));assertEquals(before,rows())
 }
 fun testExpiredOriginalCommandDoesNotPay(){ok(record());val c=command();now=c.period.endsAt.value;val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),ledger.claim(c));assertEquals(before,rows())
 }
 fun testUnknownTimeAndIncompleteLegacyCoverageAreNotZero(){ok(record(event(at=null)));assertEquals(QuestStatus.UNKNOWN,state().status);assertNull(state().count)
  raw{it.execSQL("DELETE FROM quest_events");it.execSQL("UPDATE quest_tracking SET complete_since=?",arrayOf(now))}
  assertEquals(QuestStatus.UNKNOWN,state().status);assertNull(state().count)
  assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),ledger.read(p,Observation.Missing(MissingReason.NO_DATA)))
 }
 fun testWrongProfileSourceAndUnviewedReceiptAreRejected(){val before=rows()
  assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),record(event().copy(profileId=ProfileId("other"))))
  assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),record(event().copy(source=InputSource.VEHICLE)))
  raw{it.execSQL("UPDATE settlements SET viewed=0 WHERE settlement_id=?",arrayOf(receipt.settlementId.value))}
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),record());raw{it.execSQL("UPDATE settlements SET viewed=1 WHERE settlement_id=?",arrayOf(receipt.settlementId.value))};assertEquals(before,rows())
 }
 fun testFailureAfterAllWritesRollsBackMoneyMarkerAndReceipt(){ok(record());val c=command();val before=rows();store.beforeCommit={throw SQLiteException("Quest commit fixture failure")}
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ledger.claim(c));store.beforeCommit=null;assertEquals(before,rows());assertEquals(GameMoney(150),ok(ledger.claim(c)).settlement.balance)
 }
 fun testIgnoredAccountUpdateCannotCommitClaim(){ok(record());val c=command();raw{it.execSQL("CREATE TRIGGER ignore_quest_account BEFORE UPDATE ON account BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ledger.claim(c));assertEquals(before,rows())
 }
 fun testTwoConnectionsSameClaimReplayAndConflictingRequestsCannotDoublePay(){ok(record());val c=command();val second=open();val other=module(second);val pool=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
  try{val a=pool.submit<ContractResult<QuestClaimSettlement>>{start.await();ledger.claim(c)};val b=pool.submit<ContractResult<QuestClaimSettlement>>{start.await();other.claim(c)};start.countDown();assertEquals(ok(a.get()),ok(b.get()));assertEquals(GameMoney(150),ok(store.balance()))}finally{pool.shutdownNow();second.close()}
 }
 fun testMissingCommonReceiptOrNumericDamageNeverRepairsOriginal(){ok(record());val c=command();ok(ledger.claim(c));raw{it.execSQL("DELETE FROM settlements WHERE request_id=?",arrayOf(c.requestId.value))};val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ledger.claim(c));assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ledger.claimedRequestIds(p));assertEquals(before,rows())
 }
 fun testNewDefinitionVersionOrZoneCannotReclaimOverlappingPeriod(){ok(record());val c=command();ok(ledger.claim(c));val changed=c.copy(requestId=RequestId("quest/claim/v2"),definition=c.definition.copy(version=RuleVersion("changed")))
  val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),ledger.claim(changed));assertEquals(before,rows())
 }
 fun testDeletedActualCauseCannotBecomeClaimableFromEventAlone(){ok(record());val c=command();raw{it.execSQL("DELETE FROM settlements WHERE request_id=?",arrayOf(receipt.transaction.requestId.value))};val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ledger.read(p,Observation.Available(EpochMillis(now))));assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ledger.claim(c));assertEquals(before,rows())
 }
 fun testRawEventTimeIsNotTruncatedOrRepaired(){ok(record());raw{db->db.execSQL("UPDATE quest_events SET payload=replace(payload,?,?)",arrayOf("\"at\":"+now,"\"at\":350.75"))};val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ledger.read(p,Observation.Available(EpochMillis(now))));assertEquals(before,rows())
 }

 fun testIgnoredClaimInsertCannotLeaveMoneyOrOrphanReceipt(){ok(record());val c=command();raw{it.execSQL("CREATE TRIGGER ignore_claim BEFORE INSERT ON quest_claims BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ledger.claim(c));assertEquals(before,rows())
 }
 fun testIgnoredEventInsertCannotReportActionRecorded(){raw{it.execSQL("CREATE TRIGGER ignore_event BEFORE INSERT ON quest_events BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),record());assertEquals(before,rows())
 }
 fun testReplayStillRequiresActualProfileIdentity(){ok(record());val c=command();ok(ledger.claim(c));val wrong=QuestLedger(store,{ContractResult.Success(ProfileId("other"))},{ContractResult.Success(definitions())});val before=rows()
  assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),wrong.claim(c));assertEquals(before,rows())
 }

 fun testLaterRecordedActionDoesNotChangePastProgressReplay(){ok(record());val c=command();val original=ok(ledger.claim(c));now++
  val later=ok(store.commitGameChange(GameChange(RequestId("later-result"),TransactionKind.GARAGE,source,v,MoneyChange(MoneyDirection.DEBIT,GameMoney(1)))))
  ok(store.markViewed(later.settlementId));ok(store.atomic{ledger.onResultViewed(it,later,false)});assertEquals(original,ok(ledger.claim(c)))
 }
 fun testDeletedEventCannotBeReplacedByStoredProgressNumber(){ok(record());val c=command();ok(ledger.claim(c));raw{it.execSQL("DELETE FROM quest_events")};val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ledger.claim(c));assertEquals(before,rows())
 }
 fun testActualGrowthAndCompletedRentalHooksFeedSeparateActions(){
  val car=OwnedVehicle(OwnedVehicleId("growth"),VehicleId("fixture-car"),1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT)
  val added=ok(store.commitGameChange(GameChange(RequestId("add"),TransactionKind.GARAGE,source,v,null,VehicleWrite.Add(car))))
  ok(store.atomic{ledger.onVehicleChanged(it,null,added.changedVehicle,added)});assertEquals(0,ok(ledger.read(p,Observation.Available(EpochMillis(now)))).first{it.definition.id=="daily-vehicle-level-up"}.count)
  val grown=ok(store.commitGameChange(GameChange(RequestId("grow"),TransactionKind.GARAGE,source,v,MoneyChange(MoneyDirection.DEBIT,GameMoney(1)),VehicleWrite.Update(car.ownedVehicleId,Revision(0),2,VehicleLifecycle.OWNED,0))))
  ok(store.atomic{ledger.onVehicleChanged(it,car,grown.changedVehicle,grown)});assertEquals(1,ok(ledger.read(p,Observation.Available(EpochMillis(now)))).first{it.definition.id=="daily-vehicle-level-up"}.count)
  val rental=RequestId("rental");val catalog=VehicleCatalogSnapshot(ContentVersion("fixture"),listOf(VehicleDefinition(car.vehicleId,"시험 차량")))
  ok(store.rental.assign(io.drivegrowth.core.rental.RentalRequest(rental,car.vehicleId,1),catalog,mapOf(car.vehicleId to io.drivegrowth.core.rental.RentalCategory.SEDAN),mapOf(car.vehicleId to GameMoney(0)),
   RentalTerms(v,io.drivegrowth.core.rental.SimulatedCustomer(30,36),99,EpochMillis(0),15,GameMoney(0),java.math.RoundingMode.DOWN)))
  ok(store.rental.account(rental,EpochMillis(0),EpochMillis(3600000),null));val finished=ok(store.rental.settle(rental))
  ok(store.atomic{ledger.onRentalCompleted(it,rental,finished.settlement)});assertEquals(1,ok(ledger.read(p,Observation.Available(EpochMillis(now)))).first{it.definition.id=="daily-rental-completed"}.count)
 }
 fun testTwoConnectionsDifferentRequestsOnlyPayOnePeriodMarker(){ok(record());val c=command();val second=open();val other=module(second);val pool=Executors.newFixedThreadPool(2);val start=CountDownLatch(1)
  try{val a=pool.submit<ContractResult<QuestClaimSettlement>>{start.await();ledger.claim(c)};val b=pool.submit<ContractResult<QuestClaimSettlement>>{start.await();other.claim(c.copy(requestId=RequestId("quest/claim/competing")))};start.countDown();val results=listOf(a.get(),b.get());assertEquals(1,results.count{it is ContractResult.Success});assertEquals(1,results.count{it==ContractResult.Failure(ContractError.STATE_CONFLICT)});assertEquals(GameMoney(150),ok(store.balance()))}finally{pool.shutdownNow();second.close()}
 }

 fun testIgnoredCommonReceiptAndDeletedClaimMarkerRollBackAllWrites(){ok(record());val c=command();raw{it.execSQL("CREATE TRIGGER ignore_quest_receipt BEFORE INSERT ON settlements BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ledger.claim(c));assertEquals(before,rows())
  raw{it.execSQL("DROP TRIGGER ignore_quest_receipt");it.execSQL("CREATE TRIGGER delete_claim AFTER INSERT ON quest_claims BEGIN DELETE FROM quest_claims WHERE request_id=NEW.request_id; END")}
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ledger.claim(c));assertEquals(before,rows())
 }
 fun testDefinitionFailureDoesNotDiscardActualEventOrBecomeZeroProgress(){supplied=ContractResult.Failure(ContractError.DATA_UNAVAILABLE);ok(record())
  assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),ledger.read(p,Observation.Available(EpochMillis(now))));supplied=null;assertEquals(1,state().count)
 }

 fun testInvalidStoredPeriodZoneReturnsFailureAndPreservesRows(){ok(record());val c=command();ok(ledger.claim(c));raw{it.execSQL("UPDATE quest_claims SET command=replace(command,'Asia/Seoul','unknown/zone')")};val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),ledger.claim(c));assertEquals(before,rows())
 }

 fun testForeignLegacyRequestWithSamePrefixIsConflictNotQuestReceipt(){ok(record());val change=GameChange(RequestId("quest/claim/legacy-general"),TransactionKind.GARAGE,source,v,MoneyChange(MoneyDirection.CREDIT,GameMoney(1)));// 신규 quest/ 예약 이전의 GENERAL 정산을 원문 그대로 보존한 legacy fixture다.
  val foreign=ok(store.atomic{db->val value=Settlement(SettlementId("legacy-quest-settlement"),Transaction(TransactionId("legacy-quest-transaction"),change.requestId,change.kind,change.source,change.ruleVersion,change.money),GameMoney(101),null,EpochMillis(now));store.insertSettlement(db,change.requestId,LedgerJson.command(change).toString(),value);db.execSQL("UPDATE account SET balance=101 WHERE id=1");ContractResult.Success(value)});val c=command().copy(requestId=change.requestId);val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),ledger.claim(c));assertTrue(ok(ledger.claimedRequestIds(p)).isEmpty());assertEquals(foreign,ok(store.commitGameChange(change)));assertEquals(before,rows())
 }

}
