package io.drivegrowth.app.host.driving

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.DrivingIncomeRules
import java.time.ZoneId

/** 시험 DB/명시 fixture journal 경계 시험. E의 실제 active/outbox codec·재개방 검증을 대체하지 않는다. */
@Suppress("DEPRECATION")
class ObservedDrivingSourceTest:InstrumentationTestCase() {
 private val context get()=instrumentation.targetContext
 private lateinit var names:Map<InputSource,String>
 private lateinit var games:GameSessionFactory
 private lateinit var actor:GameIdentity
 private val rules=ContractResult.Success(DrivingIncomeRules(RuleVersion("instrumentation-fixture"),500000,GameMoney(10)))
 private fun <T> ok(value:ContractResult<T>):T {assertTrue(value.toString(),value is ContractResult.Success);return(value as ContractResult.Success).value}
 override fun setUp() {
  super.setUp()
  names=InputSource.entries.associateWith{"driving-source-fixture-${it.name}-${System.nanoTime()}.db"}
  games=GameSessionFactory(context,names,InputSource.SIMULATION)
  ok(games.open()).use {session->
   actor=session.identity
   ok(session.store.atomic {db->db.execSQL("CREATE TABLE driving_source_test_probe(value INTEGER NOT NULL)");ContractResult.Success(Unit)})
  }
 }
 override fun tearDown(){names.values.forEach{context.deleteDatabase(it)};super.tearDown()}
 private fun frame(source:InputSource)=DrivingFrame(VehicleObservation(source,0,EpochMillis(10000),Observation.Available(10.0),Observation.Available(Gear.DRIVE),Observation.Available(true)),Observation.Available(ZoneId.of("UTC")))
 private fun source(journal:ObservedDrivingJournal)=SessionObservedDrivingSource(games,journal,ObservedDrivingReducer(RecordingPolicy(2500,2500,1000,3000,0.1,0.5)){"fixture-drive"}){rules}
 private open inner class FixtureJournal:ObservedDrivingJournal {
  var saveCalls=0
  override fun read(db:SQLiteDatabase,identity:GameIdentity):ContractResult<RecordingState> = ContractResult.Success(RecordingState(identity,0,null,null,null,null,null,emptyList()))
  override fun save(db:SQLiteDatabase,identity:GameIdentity,expectedRevision:Long,next:RecordingState):ContractResult<Unit> {saveCalls++;return ContractResult.Failure(ContractError.STORAGE_FAILURE)}
  override fun recordCommit(db:SQLiteDatabase,identity:GameIdentity,receipt:ObservedDrivingReceipt):ContractResult<Unit> = error("지급 경로에 도달하면 안 됨")
  override fun committed(db:SQLiteDatabase,identity:GameIdentity,driveId:String):ContractResult<ObservedDrivingReceipt?> = error("지급 경로에 도달하면 안 됨")
 }
 private fun unchanged(){
  ok(games.open()).use {session->
   assertEquals(actor,session.identity);assertEquals(GameMoney(0),ok(session.store.balance()))
   assertTrue(ok(session.store.listOwnedVehicles()).isEmpty())
   assertNull(ok(session.store.driving.day(java.time.LocalDate.of(1970,1,1))))
   ok(session.store.atomic {db->db.rawQuery("SELECT COUNT(*) FROM driving_source_test_probe",null).use{assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))};ContractResult.Success(Unit)})
  }
 }
 fun testMissingJournalDoesNotCreateEmptyRecordingOrPay(){
  val journal=object:FixtureJournal(){override fun read(db:SQLiteDatabase,identity:GameIdentity):ContractResult<RecordingState> = ContractResult.Failure(ContractError.STORAGE_FAILURE)}
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),source(journal).observe("epoch",frame(actor.source),RecordingClock(0,EpochMillis(10000))))
  assertEquals(0,journal.saveCalls);unchanged()
 }
 fun testActualVehicleObservationCannotOpenAnotherProfileForExistingSimulation(){
  val journal=FixtureJournal()
  assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),source(journal).observe("epoch",frame(InputSource.VEHICLE),RecordingClock(0,EpochMillis(10000))))
  assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists());assertEquals(0,journal.saveCalls);unchanged()
 }
 fun testJournalWriteFailureRollsBackWithinSameStoreTransaction(){
  val journal=object:FixtureJournal(){override fun save(db:SQLiteDatabase,identity:GameIdentity,expectedRevision:Long,next:RecordingState):ContractResult<Unit>{
   db.execSQL("INSERT INTO driving_source_test_probe VALUES(1)");return ContractResult.Failure(ContractError.STORAGE_FAILURE)
  }}
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),source(journal).observe("epoch",frame(actor.source),RecordingClock(0,EpochMillis(10000))))
  unchanged()
 }
}
