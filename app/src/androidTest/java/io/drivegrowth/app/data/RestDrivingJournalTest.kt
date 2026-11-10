package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.rest.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import java.math.BigDecimal
import java.time.ZoneId

/** 실제 같은 helper/SQLite의 명시 활성화·원관측/출발 보관. 입력은 SIMULATION fixture다. */
@Suppress("DEPRECATION")
class RestDrivingJournalTest:InstrumentationTestCase() {
 private val context get()=instrumentation.targetContext
 private lateinit var name:String
 private lateinit var store:SqliteGameChangeStore
 private var actualIdentity:GameIdentity?=null
 private val id get()=checkNotNull(actualIdentity)
 private lateinit var definition:TrialDefinition
 private var definitionAvailable=true
 private val missing=Observation.Missing(MissingReason.NO_DATA)
 private val policy=RestEvidencePolicy(4000000,4000000,100,0.5,0.1)
 private fun open()=SqliteGameChangeStore(context,InputSource.SIMULATION,name).apply{trialDefinitions=TrialDefinitionSource{if(definitionAvailable)ContractResult.Success(definition)else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)}}
 private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
 override fun setUp(){super.setUp();name="rest-driving-${System.nanoTime()}.db";definition=ok(AssetTrialDefinitionProvider(context.assets).load());store=open();actualIdentity=GameIdentity(ok(store.localProfileId()),store.source);ok(store.activateObservedDriving(id.profileId))}
 override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
 private fun raw(action:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(action)
 private fun rows():List<String> = buildList{raw{db->for(t in listOf("account","settlements","driving_days","driving_drives","observed_driving_state","observed_driving_receipts","rest_module","rest_contexts","rest_departures","rest_driving_receipts","rest_log","rest_head"))db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{r->while(r.moveToNext())add(t+":"+(0 until r.columnCount).joinToString("|"){if(r.isNull(it))"<null>" else r.getString(it)})}}}
 private fun o(t:Long,gear:Gear=Gear.DRIVE)=VehicleObservation(id.source,t,EpochMillis(10000000+t),Observation.Available(if(gear==Gear.PARK)0.0 else 10.0),Observation.Available(gear),Observation.Available(gear!=Gear.PARK))
 private fun area(t:Long,inside:Boolean)=Observation.Available(RestAreaProof("fixture-area",RestPlace.SERVICE_AREA,ContentVersion("fixture-dataset"),"https://example.test/rest","fixture-licence","2026-11-10",id.source,inside,t,EpochMillis(10000000+t)))
 private fun drive(duration:Long=1000):RestDriveContext {
  val s=RestObservedSpan("actual-fixture-boot",o(3901001),o(3901001+duration),missing,missing)
  val meters=BigDecimal.valueOf(20.0).multiply(BigDecimal.valueOf(duration)).divide(BigDecimal(2000))
  val trip=ObservedTrip("original-outgoing",s.from,Observation.Available(ZoneId.of("Asia/Seoul")),ContractResult.Success(definition.driving),meters,listOf(DrivingSegment(s.from,s.to,meters)))
  return RestDriveContext(id,trip,definition.rest,definition.restPricing,listOf(RestSegmentEvidence(0,"fixture-receiver-1",s,policy)),RestClockDomain("fixture-receiver-1","actual-fixture-boot"))
 }
 private fun observed(c:RestDriveContext=drive()):RestDriveContext {
  ok(store.atomic{db->store.observedDriving.save(db,id,0,RecordingState(id,1,"fixture-receiver-1",null,null,null,null,listOf(c.trip)))})
  return c
 }
 private fun capture(c:RestDriveContext=drive(),revision:Long=0)=store.atomic{db->store.restDriving.captureIn(db,id,revision,1,c)}
 private fun departure():RestDepartureInput {
  val driving=RestObservedSpan("actual-fixture-boot",o(0),o(3600000),missing,missing)
  val parked=RestObservedSpan("actual-fixture-boot",o(3600001,Gear.PARK),o(3900001,Gear.PARK),area(3600001,true),area(3900001,true))
  val exit=RestObservedSpan("actual-fixture-boot",parked.to,o(3901001),area(3900001,true),area(3901001,false))
  return RestDepartureInput(RequestId("actual-rest-exit"),id,"original-outgoing",listOf(driving),listOf(parked),exit,definition.rest,policy,RestClockDomain("fixture-receiver-1","actual-fixture-boot"))
 }
 private fun prepare(input:RestDepartureInput=departure(),revision:Long=1)=store.atomic{db->store.restDriving.prepareDepartureIn(db,id,revision,input)}
 fun testActivationIsExplicitAndKeepsActualProfileAfterReopen(){
  assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.restDriving.state(id.profileId))
  val active=ok(store.restDriving.activate(id.profileId));assertEquals(RestBonusState.NONE,active.confirmed);assertEquals(RestBonusState.NONE,active.effective);assertEquals(0L,active.revision)
  store.close();store=open();assertEquals(active,ok(store.restDriving.state(id.profileId)))
  val before=rows();assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),store.restDriving.activate(ProfileId("foreign")));assertEquals(before,rows())
 }
 fun testLegacyCurrentTripCannotBeActivatedWithInventedBootContext(){
  observed();val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.restDriving.activate(id.profileId));assertEquals(before,rows())
 }
 fun testWholeActualDepartureIsPendingProjectionWithoutPaymentOrConfirmedBuff(){
  ok(store.restDriving.activate(id.profileId));val c=observed();assertEquals(1L,ok(capture(c)).revision)
  val event=ok(prepare());assertEquals(0L,event.packet.proof.stopNumber);assertEquals(-1,event.segment)
  val state=ok(store.restDriving.state(id.profileId));assertEquals(RestBonusState.NONE,state.confirmed);assertEquals(RestBonusState(1199000,0),state.effective);assertEquals(1,state.pendingDrives)
  assertEquals(GameMoney(0),ok(store.balance()));assertEquals(RestBonusState.NONE,ok(RestLedger(store).state()))
  raw{db->for(t in listOf("settlements","driving_drives","rest_log","rest_driving_receipts"))db.rawQuery("SELECT COUNT(*) FROM $t",null).use{it.moveToFirst();assertEquals(t,0,it.getInt(0))}}
 }
 fun testOriginalDepartureReplayPrecedesCurrentDefinitionAndCursor(){
  ok(store.restDriving.activate(id.profileId));ok(capture(observed()));val first=ok(prepare());val before=rows();definitionAvailable=false
  assertEquals(first,ok(prepare(revision=0)));assertEquals(before,rows());assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepare(departure().copy(rules=definition.rest.copy(bonusPercent=151)),0));assertEquals(before,rows())
 }
 fun testPendingNonfinancialDepartureOwnsGlobalRequestId(){
  ok(store.restDriving.activate(id.profileId));ok(capture(observed()));val event=ok(prepare());val before=rows()
  val change=GameChange(event.requestId,TransactionKind.GARAGE,id.source,definition.rules,MoneyChange(MoneyDirection.CREDIT,GameMoney(1)))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.commitGameChange(change));assertEquals(before,rows())
 }
 fun testMissingSidecarAndChangedOriginalEndpointDoNotBecomeDefaults(){
  ok(store.restDriving.activate(id.profileId));val c=observed();val before=rows()
  assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),capture(c.copy(segments=emptyList())));assertEquals(before,rows())
  val e=c.segments.single();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),capture(c.copy(segments=listOf(e.copy(span=e.span.copy(to=o(3902000)))))));assertEquals(before,rows())
  ok(capture(c));val after=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),capture(c.copy(segments=listOf(e.copy(clockDomain="current-invented"))),1));assertEquals(after,rows())
 }
 fun testIgnoredContextDepartureAndModuleWritesRollBackWholeMetadata(){
  ok(store.restDriving.activate(id.profileId));val c=observed()
  for((table,event) in listOf("rest_contexts" to "INSERT","rest_module" to "UPDATE")){
   raw{it.execSQL("CREATE TRIGGER rest_ignore BEFORE $event ON $table BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
   assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),capture(c));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER rest_ignore")}
  }
  ok(capture(c));for((table,event) in listOf("rest_departures" to "INSERT","rest_module" to "UPDATE")){
   raw{it.execSQL("CREATE TRIGGER rest_ignore BEFORE $event ON $table BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
   assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),prepare());assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER rest_ignore")}
  }
 }
 fun testMarkerOrOriginalContextLossDoesNotReinitializeNone(){
  ok(store.restDriving.activate(id.profileId));ok(capture(observed()));raw{it.execSQL("DELETE FROM rest_module")};val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.restDriving.activate(id.profileId));assertEquals(before,rows())
 }
 fun testSchema15UpgradeKeepsFinancialAndObservedRowsWithoutBootBackfill(){
  observed();val kept=rows().filter{it.startsWith("account:")||it.startsWith("observed_driving_state:")};store.close();raw{stripRestFixture(it);it.version=15};store=open();assertEquals(id.profileId,ok(store.localProfileId()))
  assertEquals(kept,rows().filter{it.startsWith("account:")||it.startsWith("observed_driving_state:")});assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.restDriving.state(id.profileId));raw{assertEquals(16,it.version)}
 }
 fun testRawRestHistoryWithoutMarkerCannotBeAdoptedAsCurrentEvidence(){
  ok(RestLedger(store).depart(definition.rest,RestStop(1,id.source,Observation.Available(RestPlace.SERVICE_AREA),Observation.Available(3600000),Observation.Available(300000))));val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.restDriving.activate(id.profileId));assertEquals(before,rows())
 }
 fun testSingleFinalPaymentConsumptionProvenanceAndAckSurviveReopen(){
  ok(store.restDriving.activate(id.profileId));ok(capture(observed(drive(100000))));ok(prepare())
  val first=ok(store.restDriving.settleAndAck(id.profileId,"original-outgoing"))
  assertEquals(GameMoney(15),first.observed.commit.amount);assertEquals(1000L,first.observed.commit.recordedMeters);assertEquals(RestBonusState(1100000,0),first.remaining);assertEquals(100000L,first.price.boostedMs)
  assertEquals(GameMoney(15),ok(store.balance()));assertTrue(ok(store.atomic{store.observedDriving.read(it,id)}).outbox.isEmpty())
  val state=ok(store.restDriving.state(id.profileId));assertEquals(first.remaining,state.confirmed);assertEquals(state.confirmed,state.effective);assertEquals(0,state.pendingDrives)
  val before=rows();store.close();definitionAvailable=false;store=open();assertEquals(first,ok(store.restDriving.settleAndAck(id.profileId,"original-outgoing")));assertEquals(before,rows())
  assertEquals(first.observed,ok(store.observedDrivingReceipt("original-outgoing")))
  raw{db->db.rawQuery("SELECT COUNT(*) FROM settlements",null).use{it.moveToFirst();assertEquals(1,it.getInt(0))}}
 }
 fun testZeroPaymentStillConsumesAndAcknowledgesWithoutSyntheticSettlement(){
  ok(store.restDriving.activate(id.profileId));ok(capture(observed()));ok(prepare());val first=ok(store.restDriving.settleAndAck(id.profileId,"original-outgoing"))
  assertEquals(GameMoney(0),first.observed.commit.amount);assertNull(first.observed.commit.settlement);assertEquals(RestBonusState(1199000,0),first.remaining);assertTrue(ok(store.atomic{store.observedDriving.read(it,id)}).outbox.isEmpty())
  raw{db->db.rawQuery("SELECT COUNT(*) FROM settlements",null).use{it.moveToFirst();assertEquals(0,it.getInt(0))}}
 }
 fun testCapConsumesKnownTimeAfterRewardPrefixAndPreservesOriginalBaseRecord(){
  val c=drive(100000);val day=c.trip.confirmed(id.source)!!.departedOn;val oldDrive=ConfirmedDrive("legacy-base",day,ObservedDistance(499400,id.source));val old=ok(store.driving.settle(definition.driving,oldDrive))
  ok(store.restDriving.activate(id.profileId));ok(capture(observed(c)));ok(prepare());val first=ok(store.restDriving.settleAndAck(id.profileId,c.trip.id))
  assertEquals(600L,first.observed.commit.rewardedMeters);assertEquals(GameMoney(9),first.observed.commit.amount);assertEquals(500000L,first.observed.commit.day.rewardedMeters);assertEquals(RestBonusState(1100000,0),first.remaining)
  assertEquals(old,ok(store.driving.settle(definition.driving,oldDrive)))
 }
 fun testEveryRequiredFinancialRestAndAckWriteRollsBackTogether(){
  ok(store.restDriving.activate(id.profileId));ok(capture(observed(drive(100000))));ok(prepare())
  for((table,event) in listOf("rest_log" to "INSERT","rest_head" to "INSERT","account" to "UPDATE","settlements" to "INSERT","driving_days" to "INSERT","driving_drives" to "INSERT","rest_driving_receipts" to "INSERT","rest_module" to "UPDATE","observed_driving_receipts" to "INSERT","observed_driving_state" to "UPDATE")){
   raw{it.execSQL("CREATE TRIGGER rest_ignore BEFORE $event ON $table BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
   assertEquals(table,ContractResult.Failure(ContractError.STORAGE_FAILURE),store.restDriving.settleAndAck(id.profileId,"original-outgoing"));assertEquals(table,before,rows());raw{it.execSQL("DROP TRIGGER rest_ignore")}
  }
  assertEquals(GameMoney(15),ok(store.restDriving.settleAndAck(id.profileId,"original-outgoing")).observed.commit.amount)
 }
 fun testAckAfterTriggerCannotReturnPaymentWithOriginalOutboxStillPresent(){
  ok(store.restDriving.activate(id.profileId));ok(capture(observed(drive(100000))));ok(prepare())
  raw{it.execSQL("CREATE TRIGGER restore_ack AFTER UPDATE ON observed_driving_state BEGIN UPDATE observed_driving_state SET revision=OLD.revision,payload=OLD.payload,digest=OLD.digest WHERE profile=OLD.profile; END")};val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.restDriving.settleAndAck(id.profileId,"original-outgoing"));assertEquals(before,rows())
 }
 fun testRestEnabledMissingContextNeverFallsBackToBasePayout(){
  ok(store.restDriving.activate(id.profileId));val c=observed();val before=rows()
  assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.restDriving.settleAndAck(id.profileId,c.trip.id))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.driving.settle(definition.driving,c.trip.confirmed(id.source)!!));assertEquals(before,rows())
 }
 fun testMissingOriginalCompositeReceiptCannotRepayOrRebuild(){
  ok(store.restDriving.activate(id.profileId));ok(capture(observed(drive(100000))));ok(prepare());ok(store.restDriving.settleAndAck(id.profileId,"original-outgoing"))
  raw{it.execSQL("DELETE FROM rest_driving_receipts")};val before=rows()
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.restDriving.settleAndAck(id.profileId,"original-outgoing"));assertEquals(before,rows())
 }
 fun testActualDepartureClockIsRequiredEvenBeforeFirstKnownSegment(){
  ok(store.restDriving.activate(id.profileId));val full=drive();val zero=full.copy(trip=full.trip.copy(meters=BigDecimal.ZERO,segments=emptyList()),segments=emptyList());ok(capture(observed(zero)));val before=rows()
  for(clock in listOf(RestClockDomain("other-receiver","actual-fixture-boot"),RestClockDomain("fixture-receiver-1","other-boot"))) {
   assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepare(departure().copy(clock=clock)));assertEquals(before,rows())
  }
  ok(prepare());assertEquals(RestBonusState(1200000,0),ok(store.restDriving.state(id.profileId)).effective)
 }

}
