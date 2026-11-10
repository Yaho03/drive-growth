package io.drivegrowth.app.host.driving

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.rest.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import java.time.ZoneId

/** 실제 source/SQLite의 고립 DB. 신호·시간·장소는 명시 SIMULATION이며 실제 SDK/실차 시험이 아니다. */
@Suppress("DEPRECATION")
class RestSourceRetryTest:InstrumentationTestCase() {
 private val context get()=instrumentation.targetContext
 private lateinit var names:Map<InputSource,String>
 private lateinit var games:GameSessionFactory
 private lateinit var id:GameIdentity
 private lateinit var definition:TrialDefinition
 private lateinit var producer:ForegroundRestObservationProducer
 private lateinit var source:SessionObservedDrivingSource
 private val journal=SqliteObservedDrivingJournal()
 private val policy=RestEvidencePolicy(4000000,4000000,0,0.5,0.1)
 private val domain=RestClockDomain("simulation-elapsed","simulation-physical-boot")
 private val receiver="simulation-receiver"
 private var nextIds=0
 private var prepares=0
 private var failPreparation=false
 private val batches=mutableListOf<RestObservationBatch>()
 private val exitAt=3901500L
 private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
 private fun <T> session(action:(GameSession)->T):T=ok(games.open()).use(action)
 override fun setUp() {
  super.setUp()
  names=InputSource.entries.associateWith{"rest-source-retry-${it.name}-${System.nanoTime()}.db"}
  games=GameSessionFactory(context,names,InputSource.SIMULATION)
  definition=ok(AssetTrialDefinitionProvider(context.assets).load())
  session {game->id=game.identity;ok(game.store.activateObservedDriving(id.profileId));ok(game.store.restDriving.activate(id.profileId))}
  producer=ForegroundRestObservationProducer(id,{ContractResult.Success(definition.rest)},policy,definition.restPricing)
  source=SessionObservedDrivingSource(games,journal,
   ObservedDrivingReducer(RecordingPolicy(4000000,4000000,0,500,0.1,0.5)){"retry-drive-${++nextIds}"}){ContractResult.Success(definition.driving)}
  source.restObservationProvider=RestObservationProvider {before,next->
   prepares++
   if(failPreparation&&next.trip?.departure?.receivedElapsedMs==exitAt) {
    failPreparation=false;ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
   } else producer.prepare(before,next).also {if(it is ContractResult.Success)batches+=it.value}
  }
 }
 override fun tearDown(){names.values.forEach{context.deleteDatabase(it)};super.tearDown()}
 private fun o(at:Long,park:Boolean=false)=VehicleObservation(id.source,at,EpochMillis(10000000+at),
  Observation.Available(if(park)0.0 else 10.0),Observation.Available(if(park)Gear.PARK else Gear.DRIVE),Observation.Available(!park))
 private fun frame(raw:VehicleObservation)=DrivingFrame(raw,Observation.Available(ZoneId.of("UTC")))
 private fun clock(raw:VehicleObservation)=RecordingClock(raw.receivedElapsedMs,raw.receivedAt)
 private fun observe(raw:VehicleObservation):ContractResult<DrivingRecordingResult> {
  val area=Observation.Available(RestAreaProof("simulation-verified-area",RestPlace.SERVICE_AREA,ContentVersion("simulation-dataset"),
   "https://example.test/rest-retry","fixture-only","2026-11-11",id.source,raw.gear==Observation.Available(Gear.PARK),raw.receivedElapsedMs,raw.receivedAt))
  ok(producer.observe(receiver,domain,raw,area))
  return source.observe(receiver,frame(raw),clock(raw))
 }
 private fun prefix() {for(raw in listOf(o(0),o(3600000),o(3600500,true),o(3900500,true)))ok(observe(raw));assertEquals(1,nextIds)}
 private fun raw(action:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(names.getValue(id.source)).path,null,SQLiteDatabase.OPEN_READWRITE).use(action)
 private fun trigger(table:String)=raw{it.execSQL("CREATE TRIGGER rest_retry_fault BEFORE INSERT ON $table BEGIN SELECT RAISE(IGNORE); END")}
 private fun drop()=raw{it.execSQL("DROP TRIGGER rest_retry_fault")}
 private fun rows():List<String> = buildList {raw{db->
  for(t in listOf("account","settlements","driving_days","driving_drives","observed_driving_state","observed_driving_receipts",
   "rest_module","rest_contexts","rest_departures","rest_driving_receipts","rest_log","rest_head"))db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use {c->
   while(c.moveToNext())add(t+":"+(0 until c.columnCount).joinToString("|"){if(c.isNull(it))"<null>" else c.getString(it)})}
 }}
 private fun state():RecordingState=session{ok(it.store.atomic{db->journal.read(db,id)})}
 private fun assertOriginalExit(input:RestDepartureInput) = session {game->ok(game.store.atomic {db->
  val stored=RestAtomicStorage.departures(db,id).single()
  assertEquals("retry-drive-2",stored.packet.proof.driveId);assertEquals(input.requestId,stored.requestId)
  assertEquals(input.departure,stored.packet.proof.departure);assertEquals(input.driving,stored.packet.proof.driving);assertEquals(input.parked,stored.packet.proof.parked)
  assertEquals("retry-drive-2",ok(journal.read(db,id)).trip!!.id)
  ContractResult.Success(Unit)
 })}
 private fun finishOutgoing():RestDrivingCommit {
  for(raw in listOf(o(4001500),o(4002000,true),o(4302000,true)))ok(observe(raw))
  val original=session{ok(it.store.restDriving.committed(id.profileId,"retry-drive-2"))!!}
  assertEquals(GameMoney(15),original.observed.commit.amount);assertEquals(100000L,original.price.boostedMs)
  assertEquals(RestBonusState(1100000,0),original.remaining);assertEquals(2,nextIds)
  val before=rows()
  games=GameSessionFactory(context,names,InputSource.SIMULATION)
  session {game->
   assertEquals(id,game.identity);assertEquals(original,ok(game.store.restDriving.settleAndAck(id.profileId,"retry-drive-2")))
   assertEquals(original.observed,ok(game.store.observedDrivingReceipt("retry-drive-2")))
  }
  assertEquals(before,rows())
  raw{db->for(t in listOf("settlements","driving_drives","rest_driving_receipts","observed_driving_receipts"))db.rawQuery("SELECT COUNT(*) FROM $t",null).use{it.moveToFirst();assertEquals(t,2,it.getInt(0))}}
  return original
 }
 fun testSameExitRetryReusesWholeDriveIdBatchAndOriginalFinancialResultAfterCaptureRollback() {
  prefix();val before=rows();trigger("rest_departures")
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),observe(o(exitAt)))
  val original=batches.last().departures.single();val prepareCount=prepares
  assertEquals("retry-drive-2",original.driveId);assertEquals(before,rows());drop()
  val retried=ok(observe(o(exitAt)))
  assertEquals("retry-drive-2",retried.state.trip!!.id);assertEquals(2,nextIds)
  assertEquals("원 batch 재제출은 provider를 다시 호출하지 않는다",prepareCount,prepares)
  assertOriginalExit(original);finishOutgoing()
 }
 fun testDifferentFrameClockReceiverAndPauseCannotOverwriteUncapturedProposal() {
  prefix();trigger("rest_departures");assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),observe(o(exitAt)));drop()
  val original=batches.last().departures.single();val before=rows();val preparesBefore=prepares
  val other=o(exitAt+1000)
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),observe(other))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),source.observe("other-receiver",frame(o(exitAt)),clock(o(exitAt))))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),source.observe(receiver,frame(o(exitAt)),RecordingClock(exitAt+1,o(exitAt).receivedAt)))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),source.pause(receiver,clock(other)))
  assertEquals(before,rows());assertEquals(2,nextIds);assertEquals(preparesBefore,prepares)
  ok(observe(o(exitAt)));assertOriginalExit(original)
 }
 fun testChangedActualPreviousRevisionCannotRebaseUncapturedOriginal() {
  prefix();trigger("rest_departures");assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),observe(o(exitAt)));drop()
  session {game->ok(game.store.atomic {db->val old=ok(journal.read(db,id));journal.save(db,id,old.revision,old.copy(revision=old.revision+1))})}
  val before=rows();val preparesBefore=prepares
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),observe(o(exitAt)))
  assertEquals(before,rows());assertEquals(2,nextIds);assertEquals(preparesBefore,prepares)
 }
 fun testCaptureSuccessThenFinancialDrainFailureAllowsNewInputAndKeepsOriginalPendingDrive() {
  for(raw in listOf(o(0),o(3600000),o(3600500,true)))ok(observe(raw))
  trigger("driving_drives")
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),observe(o(3900500,true)))
  assertEquals(listOf("retry-drive-1"),state().outbox.map{it.id})
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),observe(o(exitAt)))
  assertEquals("retry-drive-2",state().trip!!.id);assertEquals(2,nextIds)
  val original=batches.last().departures.single();assertOriginalExit(original);drop()
  ok(observe(o(exitAt)));assertEquals(2,nextIds);finishOutgoing()
 }
 fun testProviderPreparationFailureRetainsReducedOriginalBeforeAnyBatchExists() {
  prefix();val before=rows();failPreparation=true
  assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),observe(o(exitAt)))
  assertEquals(before,rows());assertEquals(2,nextIds)
  val retry=ok(observe(o(exitAt)));assertEquals("retry-drive-2",retry.state.trip!!.id);assertEquals(2,nextIds)
  assertOriginalExit(batches.last().departures.single())
 }
}
