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
class RestMissingObservedModuleIndependentTest:InstrumentationTestCase() {
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
 fun testConfirmedRestWithoutAnyObservedMetadataIsCorruptionNotLegacyMissing(){
  ok(store.restDriving.activate(id.profileId));ok(capture(observed(drive(100000))));ok(prepare())
  ok(store.restDriving.settleAndAck(id.profileId,"original-outgoing"))
  raw { db ->
   assertEquals(1,db.delete("observed_driving_receipts",null,null))
   assertEquals(1,db.delete("observed_driving_state",null,null))
   assertEquals(1,db.delete("observed_driving_marker",null,null))
  }
  val before=rows()
  val current=store.restDriving.state(id.profileId)
  val receipt=store.observedDrivingReceipt("original-outgoing")
  val proof=io.drivegrowth.app.host.ranking.RankingProvenance(id.source,{store.localProfileId()},{store.quests.claimedRequestIds(it)},{store.observedDrivingReceipt(it)})
  val zone=try { proof.departureZone("original-outgoing") } catch(e:io.drivegrowth.app.host.ranking.RankingProvenanceFailure) { ContractResult.Failure(e.error) }
  assertEquals("current=$current receipt=$receipt zone=$zone",ContractResult.Failure(ContractError.STORAGE_FAILURE),receipt)
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),current)
  assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),zone)
  assertEquals(before,rows())
 }
}
