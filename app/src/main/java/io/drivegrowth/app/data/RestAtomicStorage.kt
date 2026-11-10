package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.rest.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.json.JSONObject

internal data class RestModuleState(val identity:GameIdentity,val revision:Long,val nextStop:Long,val headCount:Long,val headSequence:Long,
 val contextCount:Long,val confirmedCount:Long,val activeGrant:RestActiveGrant?)
data class RestDeparturePacket(val proof:RestProof,val rules:RestRules,val policy:RestEvidencePolicy,val clock:RestClockDomain)
internal data class StoredRestContext(val ordinal:Long,val value:RestDriveContext)

/** 같은 helper의 원 증거/명령 metadata다. 재화·정산·버프 원장은 기존 표를 사용한다. */
internal object RestAtomicStorage {
 fun createTables(db:SQLiteDatabase) {
  RestLedger.createTables(db)
  db.execSQL("CREATE TABLE rest_module(profile TEXT PRIMARY KEY NOT NULL,source TEXT NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL,digest TEXT NOT NULL)")
  db.execSQL("CREATE TABLE rest_contexts(drive_id TEXT PRIMARY KEY NOT NULL,ordinal INTEGER UNIQUE NOT NULL,profile TEXT NOT NULL,source TEXT NOT NULL,payload TEXT NOT NULL,digest TEXT NOT NULL)")
  db.execSQL("CREATE TABLE rest_departures(request_id TEXT PRIMARY KEY NOT NULL,stop INTEGER UNIQUE NOT NULL,exit_key TEXT UNIQUE NOT NULL,drive_id TEXT NOT NULL,anchor INTEGER NOT NULL,profile TEXT NOT NULL,source TEXT NOT NULL,payload TEXT NOT NULL,digest TEXT NOT NULL)")
  db.execSQL("CREATE TABLE rest_driving_receipts(drive_id TEXT PRIMARY KEY NOT NULL,profile TEXT NOT NULL,source TEXT NOT NULL,payload TEXT NOT NULL,digest TEXT NOT NULL)")
 }
 private fun n(j:JSONObject,k:String)=LedgerJson.integer(j,k,0,Long.MAX_VALUE)
 private fun module(s:RestModuleState)=JSONObject().put("type","rest-module-v1").put("profile",s.identity.profileId.value).put("source",s.identity.source.name).put("revision",s.revision)
  .put("nextStop",s.nextStop).put("headCount",s.headCount).put("headSequence",s.headSequence).put("contextCount",s.contextCount).put("confirmedCount",s.confirmedCount).put("activeGrant",s.activeGrant?.let(RestDrivingJson::grant)?:JSONObject.NULL).toString()
 private fun values(s:RestModuleState)=ContentValues().apply{val raw=module(s);put("profile",s.identity.profileId.value);put("source",s.identity.source.name);put("revision",s.revision);put("payload",raw);put("digest",ObservedDrivingJson.digest(raw))}
 private fun evidenceExists(db:SQLiteDatabase):Boolean {
  if(db.rawQuery("SELECT 1 FROM rest_log UNION ALL SELECT 1 FROM rest_head UNION ALL SELECT 1 FROM rest_contexts UNION ALL SELECT 1 FROM rest_departures UNION ALL SELECT 1 FROM rest_driving_receipts",null).use{it.moveToFirst()})return true
  return db.rawQuery("SELECT command FROM driving_drives",null).use{r->var found=false;while(r.moveToNext()){val j=JSONObject(r.text(0));if(j.has("rest")||j.optString("type")=="driving-rest-v1")found=true};found}
 }
 fun marker(db:SQLiteDatabase,id:GameIdentity):RestModuleState? {
  return db.rawQuery("SELECT profile,source,revision,payload,digest FROM rest_module",null).use {
   if(!it.moveToFirst())return@use null
   val raw=it.text(3);val j=JSONObject(raw);if(j.getString("type")!="rest-module-v1")throw SQLiteException("휴식 기능 형식")
   val s=RestModuleState(GameIdentity(ProfileId(j.getString("profile")),InputSource.valueOf(j.getString("source"))),n(j,"revision"),n(j,"nextStop"),n(j,"headCount"),n(j,"headSequence"),n(j,"contextCount"),n(j,"confirmedCount"),if(j.isNull("activeGrant"))null else RestDrivingJson.grant(j.getJSONObject("activeGrant")))
   if(s.identity!=id||it.text(0)!=id.profileId.value||it.text(1)!=id.source.name||it.integer(2,0,Long.MAX_VALUE)!=s.revision||module(s)!=raw||it.text(4)!=ObservedDrivingJson.digest(raw)||it.moveToNext())throw SQLiteException("휴식 기능 원문/프로필/출처 손상")
   if((s.headCount==0L)!=(s.headSequence==0L)||s.confirmedCount>s.contextCount)throw SQLiteException("휴식 기능 head/커서 불완전")
   s
  }
 }
 fun read(store:SqliteGameChangeStore,db:SQLiteDatabase,id:GameIdentity):ContractResult<RestModuleState> {
  val state=marker(db,id)
  if(state==null)return if(evidenceExists(db))throw SQLiteException("휴식 기능 marker 없는 원이력") else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  when(val observed=store.observedDriving.read(db,id)) {
   is ContractResult.Failure->if(observed.error==ContractError.DATA_UNAVAILABLE)throw SQLiteException("휴식 기능 이후 원관측 모듈 소실") else return observed
   is ContractResult.Success->Unit
  }
  val bonus=RestLedger(store).stateIn(db);val head=head(db)
  if(head.first!=state.headSequence||head.second!=state.headCount)throw SQLiteException("휴식 로그/머리 동시 소실 또는 갱신 누락")
  val contexts=contexts(db,id)
  if(contexts.size.toLong()!=state.contextCount||contexts.withIndex().any{it.value.ordinal!=it.index+1L})throw SQLiteException("휴식 원구간/커서 소실")
  val events=departures(db,id)
  if(events.size.toLong()!=state.nextStop||events.withIndex().any{it.value.packet.proof.stopNumber!=it.index.toLong()})throw SQLiteException("휴식 allocator 원기록 소실")
  for(e in events) {
   val c=contexts.firstOrNull{it.value.trip.id==e.packet.proof.driveId}?:throw SQLiteException("휴식 출발의 원주행 소실")
   val at=if(e.segment==-1)c.value.trip.departure else c.value.trip.segments.getOrNull(e.segment)?.to?:throw SQLiteException("휴식 출발 구간 손상")
   val clock=if(e.segment==-1)c.value.departureClock else c.value.segments[e.segment].let{RestClockDomain(it.clockDomain,it.span.bootId)}
   if(e.packet.clock!=clock||e.packet.clock.bootId!=e.packet.proof.departure.bootId||ObservedDrivingJson.observation(at).toString()!=ObservedDrivingJson.observation(e.packet.proof.departure.to).toString()||RestEvidenceValidator(e.packet.policy).stop(e.packet.proof) !is ContractResult.Success)throw SQLiteException("휴식 출발 증거/실제 표본 손상")
   if(c.ordinal<=state.confirmedCount && !hasReceipt(db,c.value.trip.id))throw SQLiteException("휴식 확정 출발의 원결과 소실")
  }
  val receipts=db.rawQuery("SELECT drive_id,profile,source FROM rest_driving_receipts",null).use {r->buildSet{while(r.moveToNext()){if(r.text(1)!=id.profileId.value||r.text(2)!=id.source.name)throw SQLiteException("휴식 원결과 권위 손상");add(r.text(0))}}}
  if(receipts!=contexts.filter{it.ordinal<=state.confirmedCount}.map{it.value.trip.id}.toSet())throw SQLiteException("휴식 확정 커서/원결과 불일치")
  state.activeGrant?.let {g->
   val e=events.firstOrNull{it.packet.proof.stopNumber==g.stopNumber}?:throw SQLiteException("휴식 활성 grant 원문 소실")
   val c=contexts.single{it.value.trip.id==e.packet.proof.driveId}
   val stop=(RestEvidenceValidator(e.packet.policy).stop(e.packet.proof) as? ContractResult.Success)?.value?:throw SQLiteException("휴식 grant 증거 손상")
   if(c.ordinal>state.confirmedCount||g.rules!=e.packet.rules||RestBonus(g.rules).classify(stop)!=RestOutcome.ACCEPTED||bonus.remainingMs>g.rules.bonusDurationMs)throw SQLiteException("휴식 활성 grant 규칙/확정 경계 손상")
  }
  if(bonus.remainingMs>0&&state.activeGrant==null)throw SQLiteException("휴식 활성 grant 누락")
  return ContractResult.Success(state)
 }
 fun head(db:SQLiteDatabase):Pair<Long,Long> = db.rawQuery("SELECT last,count FROM rest_head WHERE id=1",null).use{if(it.moveToFirst())it.integer(0,1,Long.MAX_VALUE) to it.integer(1,1,Long.MAX_VALUE) else 0L to 0L}
 fun activate(store:SqliteGameChangeStore,db:SQLiteDatabase,id:GameIdentity):ContractResult<RestModuleState> {
  when(val old=read(store,db,id)){is ContractResult.Success->return old;is ContractResult.Failure->if(old.error!=ContractError.DATA_UNAVAILABLE)return old}
  val s=RestModuleState(id,0,0,0,0,0,0,null)
  if(db.insertOrThrow("rest_module",null,values(s))<0)throw SQLiteException("휴식 기능 marker 무삽입")
  if((read(store,db,id) as? ContractResult.Success)?.value!=s)throw SQLiteException("휴식 marker 실제 삽입 불일치")
  return ContractResult.Success(s)
 }
 fun update(store:SqliteGameChangeStore,db:SQLiteDatabase,before:RestModuleState,next:RestModuleState) {
  if(next.identity!=before.identity||before.revision==Long.MAX_VALUE||next.revision!=before.revision+1)throw SQLiteException("휴식 기능 revision 불일치")
  if(db.update("rest_module",values(next),"profile=? AND revision=?",arrayOf(before.identity.profileId.value,before.revision.toString()))!=1)throw SQLiteException("휴식 기능 CAS 무갱신")
  if((read(store,db,next.identity) as? ContractResult.Success)?.value!=next)throw SQLiteException("휴식 기능 실제 쓰기 불일치")
 }
 fun contexts(db:SQLiteDatabase,id:GameIdentity):List<StoredRestContext> {
  val result=db.rawQuery("SELECT drive_id,ordinal,profile,source,payload,digest FROM rest_contexts ORDER BY ordinal",null).use {r->buildList{while(r.moveToNext()) {
  val raw=r.text(4);val c=RestDrivingJson.context(JSONObject(raw));val ordinal=r.integer(1,1,Long.MAX_VALUE)
  if(r.text(0)!=c.trip.id||c.identity!=id||r.text(2)!=id.profileId.value||r.text(3)!=id.source.name||RestDrivingJson.context(c).toString()!=raw||r.text(5)!=ObservedDrivingJson.digest(raw))throw SQLiteException("휴식 원구간 원문 손상")
  add(StoredRestContext(ordinal,c))
 }}}
  if(!RestTimeline.clockOrder(result.map{it.value}))throw SQLiteException("휴식 원 context의 물리 시간 커서 겹침/역전")
  return result
 }
 fun saveContext(db:SQLiteDatabase,c:RestDriveContext,ordinal:Long,insert:Boolean) {
  val raw=RestDrivingJson.context(c).toString();val v=ContentValues().apply{put("drive_id",c.trip.id);put("ordinal",ordinal);put("profile",c.identity.profileId.value);put("source",c.identity.source.name);put("payload",raw);put("digest",ObservedDrivingJson.digest(raw))}
  if(insert) {if(db.insertOrThrow("rest_contexts",null,v)<0)throw SQLiteException("휴식 원구간 무삽입")}
  else if(db.update("rest_contexts",v,"drive_id=? AND ordinal=?",arrayOf(c.trip.id,ordinal.toString()))!=1)throw SQLiteException("휴식 원구간 무갱신")
  if(contexts(db,c.identity).singleOrNull{it.value.trip.id==c.trip.id}?.let{RestDrivingJson.context(it.value).toString()}!=raw)throw SQLiteException("휴식 원구간 실제 쓰기 불일치")
 }
 fun exitKey(p:RestProof)=ObservedDrivingJson.digest(p.identity.source.name+"\n"+p.departure.bootId+"\n"+p.departure.to.receivedElapsedMs+"\n"+p.departure.to.receivedAt.value)
 fun departures(db:SQLiteDatabase,id:GameIdentity):List<RestAnchoredDeparture> = db.rawQuery("SELECT request_id,stop,exit_key,drive_id,anchor,profile,source,payload,digest FROM rest_departures ORDER BY stop",null).use {r->buildList{while(r.moveToNext()) {
  val raw=r.text(7);val e=RestDrivingJson.anchored(JSONObject(raw));val p=e.packet.proof
  if(e.requestId.value!=r.text(0)||p.stopNumber!=r.integer(1,0,Long.MAX_VALUE)||exitKey(p)!=r.text(2)||p.driveId!=r.text(3)||e.segment.toLong()!=r.integer(4,-1,Int.MAX_VALUE.toLong())||p.identity!=id||r.text(5)!=id.profileId.value||r.text(6)!=id.source.name||RestDrivingJson.anchored(e).toString()!=raw||r.text(8)!=ObservedDrivingJson.digest(raw))throw SQLiteException("휴식 출발 원문 손상")
  add(e)
 }}}
 fun insertDeparture(db:SQLiteDatabase,e:RestAnchoredDeparture) {
  val p=e.packet.proof;val raw=RestDrivingJson.anchored(e).toString()
  if(db.insertOrThrow("rest_departures",null,ContentValues().apply{put("request_id",e.requestId.value);put("stop",p.stopNumber);put("exit_key",exitKey(p));put("drive_id",p.driveId);put("anchor",e.segment);put("profile",p.identity.profileId.value);put("source",p.identity.source.name);put("payload",raw);put("digest",ObservedDrivingJson.digest(raw))})<0)throw SQLiteException("휴식 원출발 무삽입")
  if(departures(db,p.identity).singleOrNull{it.requestId==e.requestId}?.let{RestDrivingJson.anchored(it).toString()}!=raw)throw SQLiteException("휴식 원출발 실제 쓰기 불일치")
 }
 fun hasReceipt(db:SQLiteDatabase,driveId:String)=db.rawQuery("SELECT 1 FROM rest_driving_receipts WHERE drive_id=?",arrayOf(driveId)).use{it.moveToFirst()}
}
