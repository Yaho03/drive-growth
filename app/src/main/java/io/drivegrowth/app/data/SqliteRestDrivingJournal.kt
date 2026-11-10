package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.content.ContentValues
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.rest.RestEvidenceValidator
import io.drivegrowth.app.host.rest.RestProof
import io.drivegrowth.app.host.driving.ObservedDrivingReceipt
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.json.JSONObject

/** worker 전용 같은 DB 입력 보관. 관측 save와 captureIn은 호출자의 같은 atomic에서 실행한다. */
class SqliteRestDrivingJournal internal constructor(private val store:SqliteGameChangeStore) {
 private fun conflict()=ContractResult.Failure(ContractError.STATE_CONFLICT)
 private fun identity(db:SQLiteDatabase,id:GameIdentity):Boolean {
  check(db.inTransaction())
  if(id.source!=store.source)return false
  return db.rawQuery("SELECT profile_id,source FROM initialization_profile WHERE id=1",null).use{it.moveToFirst()&&it.text(0)==id.profileId.value&&it.text(1)==id.source.name}
 }
 fun activate(profile:ProfileId):ContractResult<RestDrivingProjection> = store.atomic { db->
  val id=GameIdentity(profile,store.source)
  if(!identity(db,id))return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
  when(val old=RestAtomicStorage.read(store,db,id)) {
   is ContractResult.Success->return@atomic projectionIn(db,id)
   is ContractResult.Failure->if(old.error!=ContractError.DATA_UNAVAILABLE)return@atomic old
  }
  when(val actual=store.observedDriving.read(db,id)) {
   is ContractResult.Failure->return@atomic actual
   is ContractResult.Success->if(actual.value.trip!=null||actual.value.outbox.isNotEmpty())return@atomic conflict()
  }
  when(val r=RestAtomicStorage.activate(store,db,id)){is ContractResult.Failure->r;is ContractResult.Success->projectionIn(db,id)}
 }
 fun state(profile:ProfileId):ContractResult<RestDrivingProjection> = store.atomic{projectionIn(it,GameIdentity(profile,store.source))}
 internal fun projectionIn(db:SQLiteDatabase,id:GameIdentity):ContractResult<RestDrivingProjection> {
  if(!identity(db,id))return ContractResult.Failure(ContractError.INVALID_INPUT)
  val module=when(val r=RestAtomicStorage.read(store,db,id)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
  val confirmed=RestLedger(store).stateIn(db);var effective=confirmed;var grant=module.activeGrant
  val contexts=RestAtomicStorage.contexts(db,id)
  for(c in contexts.filter{it.ordinal<=module.confirmedCount})when(val original=committedIn(db,id,c.value.trip.id)) {
   is ContractResult.Failure->return original
   is ContractResult.Success->if(original.value==null)throw SQLiteException("휴식 현재 상태의 복합 원결과 소실")
  }
  val pending=contexts.filter{it.ordinal>module.confirmedCount}
  val departures=RestAtomicStorage.departures(db,id)
  for(c in pending)when(val r=RestTimeline.plan(c.value,departures.filter{it.packet.proof.driveId==c.value.trip.id},effective,grant)) {
   is ContractResult.Failure->return r
   is ContractResult.Success->{effective=r.value.after;grant=r.value.activeGrant}
  }
  return ContractResult.Success(RestDrivingProjection(module.revision,confirmed,effective,module.confirmedCount,pending.size,grant))
 }
 /** raw sidecar를 늦게 현재 boot로 만들지 않는다. caller는 실제 관측 저장 transaction에서 공급한다. */
 fun captureIn(db:SQLiteDatabase,id:GameIdentity,expectedRevision:Long,observedRevision:Long,input:RestDriveContext):ContractResult<RestDrivingProjection> {
  if(!identity(db,id)||input.identity!=id)return ContractResult.Failure(ContractError.INVALID_INPUT)
  val c=RestDrivingJson.context(RestDrivingJson.context(input))
  val module=when(val r=RestAtomicStorage.read(store,db,id)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
  val contexts=RestAtomicStorage.contexts(db,id);val old=contexts.firstOrNull{it.value.trip.id==c.trip.id}
  val raw=RestDrivingJson.context(c).toString()
  if(old!=null&&RestDrivingJson.context(old.value).toString()==raw)return projectionIn(db,id)
  if(module.revision!=expectedRevision||module.revision==Long.MAX_VALUE||old?.let{it.ordinal<=module.confirmedCount}==true)return conflict()
  val actual=when(val r=store.observedDriving.read(db,id)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
  val actualTrips=actual.outbox+listOfNotNull(actual.trip)
  if(actual.revision!=observedRevision||actualTrips.none{ObservedDrivingJson.trip(it).toString()==ObservedDrivingJson.trip(c.trip).toString()})return conflict()
  val position=actualTrips.indexOfFirst{it.id==c.trip.id}
  if(actualTrips.take(position).any{t->contexts.none{it.value.trip.id==t.id}})return conflict()
  if(c.segments.size!=c.trip.segments.size||c.segments.map{it.index}!=c.trip.segments.indices.toList())return ContractResult.Failure(ContractError.INVALID_INPUT)
  for(e in c.segments) {
   val s=c.trip.segments[e.index]
   if(e.clockDomain.isBlank()||e.span.bootId.isBlank()||ObservedDrivingJson.observation(e.span.from).toString()!=ObservedDrivingJson.observation(s.from).toString()||ObservedDrivingJson.observation(e.span.to).toString()!=ObservedDrivingJson.observation(s.to).toString())return conflict()
  }
  if(old==null) {
   if(module.contextCount==Long.MAX_VALUE||DrivingRecordReader(id.source).readDrive(db,c.trip.id)!=null)return conflict()
   val definition=when(val r=store.trialDefinitions.load()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
   if(c.restRules!=definition.rest||c.pricingPolicy!=definition.restPricing)return conflict()
  }else {
   val prior=old.value
   if(c.segments.size<prior.segments.size||c.trip.gaps.size<prior.trip.gaps.size||c.restRules!=prior.restRules||c.pricingPolicy!=prior.pricingPolicy)return conflict()
   val prefix=c.copy(trip=c.trip.copy(meters=prior.trip.meters,segments=c.trip.segments.take(prior.trip.segments.size),gaps=c.trip.gaps.take(prior.trip.gaps.size)),segments=c.segments.take(prior.segments.size))
   if(RestDrivingJson.context(prefix).toString()!=RestDrivingJson.context(prior).toString())return conflict()
  }
  // 계획 실패는 저장 전에 반환한다. old pending 운전은 확정 지급/소모가 아니다.
  when(val r=RestTimeline.plan(c,RestAtomicStorage.departures(db,id).filter{it.packet.proof.driveId==c.trip.id},RestBonusState.NONE,null)) {
   is ContractResult.Failure->return r
   is ContractResult.Success->Unit
  }
  val ordinal=old?.ordinal?:module.contextCount+1
  val ordered=if(old==null)contexts.map{it.value}+c else contexts.map{if(it.ordinal==ordinal)c else it.value}
  if(!RestTimeline.clockOrder(ordered))return conflict()
  RestAtomicStorage.saveContext(db,c,ordinal,old==null)
  RestAtomicStorage.update(store,db,module,module.copy(revision=module.revision+1,contextCount=if(old==null)ordinal else module.contextCount))
  return projectionIn(db,id)
 }
 fun prepareDepartureIn(db:SQLiteDatabase,id:GameIdentity,expectedRevision:Long,input:RestDepartureInput):ContractResult<RestAnchoredDeparture> {
  if(!identity(db,id)||input.identity!=id)return ContractResult.Failure(ContractError.INVALID_INPUT)
  val module=when(val r=RestAtomicStorage.read(store,db,id)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
  val events=RestAtomicStorage.departures(db,id)
  fun packet(stop:Long)=RestDeparturePacket(RestProof(id,stop,input.driveId,input.driving,input.parked,input.departure),input.rules,input.policy,input.clock)
  events.firstOrNull{it.requestId==input.requestId}?.let { old->
   val proposed=old.copy(packet=packet(old.packet.proof.stopNumber))
   return if(RestDrivingJson.anchored(old).toString()==RestDrivingJson.anchored(proposed).toString())ContractResult.Success(old) else conflict()
  }
  if(module.revision!=expectedRevision||module.revision==Long.MAX_VALUE||module.nextStop==Long.MAX_VALUE||store.requestUnavailable(db,input.requestId))return conflict()
  val contexts=RestAtomicStorage.contexts(db,id)
  val context=contexts.firstOrNull{it.value.trip.id==input.driveId}?:return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  if(context.ordinal<=module.confirmedCount)return conflict()
  val definition=when(val r=store.trialDefinitions.load()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
  if(input.rules!=definition.rest)return conflict()
  val p=packet(module.nextStop)
  when(val r=RestEvidenceValidator(input.policy).stop(p.proof)){is ContractResult.Failure->return r;is ContractResult.Success->Unit}
  val at=ObservedDrivingJson.observation(input.departure.to).toString()
  val first=at==ObservedDrivingJson.observation(context.value.trip.departure).toString()&&input.clock==context.value.departureClock
  val anchor=if(first)-1 else context.value.trip.segments.indices.firstOrNull{index->
   ObservedDrivingJson.observation(context.value.trip.segments[index].to).toString()==at&&context.value.segments[index].let{RestClockDomain(it.clockDomain,it.span.bootId)}==input.clock
  }?:return conflict()
  val actualClock=if(anchor<0)context.value.departureClock else context.value.segments[anchor].let{RestClockDomain(it.clockDomain,it.span.bootId)}
  if(input.clock!=actualClock||input.clock.bootId!=input.departure.bootId)return conflict()
  if(events.any{RestAtomicStorage.exitKey(it.packet.proof)==RestAtomicStorage.exitKey(p.proof)})return conflict()
  events.lastOrNull()?.let{last->val previous=contexts.single{it.value.trip.id==last.packet.proof.driveId};if(previous.ordinal>context.ordinal||(previous.ordinal==context.ordinal&&last.segment>=anchor))return conflict()}
  val event=RestDrivingJson.anchored(RestDrivingJson.anchored(RestAnchoredDeparture(input.requestId,anchor,p)))
  RestAtomicStorage.insertDeparture(db,event)
  RestAtomicStorage.update(store,db,module,module.copy(revision=module.revision+1,nextStop=module.nextStop+1))
  // pending grant의 UI projection을 확인하되 금융/RestLedger 확정은 하지 않는다.
  when(val r=projectionIn(db,id)){is ContractResult.Failure->return r;is ContractResult.Success->Unit}
  return ContractResult.Success(event)
 }

 fun committed(profile:ProfileId,driveId:String):ContractResult<RestDrivingCommit?> = store.atomic{db->committedIn(db,GameIdentity(profile,store.source),driveId)}
 fun settleAndAck(profile:ProfileId,driveId:String):ContractResult<RestDrivingCommit> = store.atomic{db->settleAndAckIn(db,GameIdentity(profile,store.source),driveId)}
 private fun committedIn(db:SQLiteDatabase,id:GameIdentity,driveId:String):ContractResult<RestDrivingCommit?> {
  if(!identity(db,id))return ContractResult.Failure(ContractError.INVALID_INPUT)
  when(val r=RestAtomicStorage.read(store,db,id)){is ContractResult.Failure->return r;is ContractResult.Success->Unit}
  val stored=DrivingRecordReader(id.source).readDrive(db,driveId)
  if(stored==null) {
   if(RestAtomicStorage.hasReceipt(db,driveId))throw SQLiteException("휴식 복합 결과의 주행 원문 소실")
   return ContractResult.Success(null)
  }
  val rest=stored.rest?:return conflict()
  val actual=when(val r=store.observedDriving.committed(db,id,driveId)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
  val expected=ObservedDrivingReceipt(rest.command.context.trip,stored.commit)
  if(actual==null||ObservedDrivingJson.receipt(actual).toString()!=ObservedDrivingJson.receipt(expected).toString())throw SQLiteException("휴식 복합 결과의 관측 원문 소실")
  return ContractResult.Success(RestDrivingCommit(rest.command.context,rest.command.departures,actual,rest.calculation.timeline.after,rest.calculation.price))
 }
 /** 첫 outbox 한 건만 처리한다. 지급·확정 Rest chain·원결과·cursor·provenance·ack의 외부 거래는 하나다. */
 fun settleAndAckIn(db:SQLiteDatabase,id:GameIdentity,driveId:String):ContractResult<RestDrivingCommit> {
  when(val old=committedIn(db,id,driveId)){is ContractResult.Failure->return old;is ContractResult.Success->old.value?.let{return ContractResult.Success(it)}}
  val module=when(val r=RestAtomicStorage.read(store,db,id)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
  val state=when(val r=store.observedDriving.read(db,id)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
  if(state.revision==Long.MAX_VALUE||module.revision==Long.MAX_VALUE||state.outbox.firstOrNull()?.id!=driveId)return conflict()
  val context=RestAtomicStorage.contexts(db,id).firstOrNull{it.value.trip.id==driveId}?:return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  if(context.ordinal!=module.confirmedCount+1||ObservedDrivingJson.trip(state.outbox.first()).toString()!=ObservedDrivingJson.trip(context.value.trip).toString())return conflict()
  val drive=context.value.trip.confirmed(id.source)?:return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  val command=RestPricedCommand(context.value,java.util.Collections.unmodifiableList(RestAtomicStorage.departures(db,id).filter{it.packet.proof.driveId==driveId}),RestLedger(store).stateIn(db),module.activeGrant,DrivingRecordReader(id.source).readDay(db,drive.departedOn),context.ordinal)
  val planned=when(val r=RestDrivingRecords.calculate(command)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
  val balanceBefore=store.readBalance(db)
  val ledger=RestLedger(store)
  for(op in planned.timeline.operations)when(op) {
   is RestTimelineOperation.Use->when(val r=ledger.consumeIn(db,op.rules,op.consumeId,op.afterStop,op.elapsedMs)) {
    is ContractResult.Failure->return r
    is ContractResult.Success->if(r.value!=op.after)throw SQLiteException("휴식 소모 실제 결과 불일치")
   }
   is RestTimelineOperation.Depart->{val p=op.command.packet;val stop=(RestEvidenceValidator(p.policy).stop(p.proof) as? ContractResult.Success)?.value?:throw SQLiteException("휴식 출발 원증거 소실")
    when(val r=ledger.departIn(db,p.rules,stop)){is ContractResult.Failure->return r;is ContractResult.Success->if(r.value!=op.result)throw SQLiteException("휴식 출발 실제 결과 불일치")}
   }
  }
  val paid=when(val r=store.driving.settleRestIn(db,command)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
  val raw=RestDrivingJson.receipt(command,planned,paid).toString()
  if(db.insertOrThrow("rest_driving_receipts",null,ContentValues().apply{put("drive_id",driveId);put("profile",id.profileId.value);put("source",id.source.name);put("payload",raw);put("digest",ObservedDrivingJson.digest(raw))})<0)throw SQLiteException("휴식 복합 원결과 무삽입")
  val head=RestAtomicStorage.head(db)
  RestAtomicStorage.update(store,db,module,module.copy(revision=module.revision+1,headSequence=head.first,headCount=head.second,confirmedCount=context.ordinal,activeGrant=planned.timeline.activeGrant))
  val original=DrivingRecordReader(id.source).readDrive(db,driveId)?:throw SQLiteException("휴식 실제 주행 원결과 소실")
  if(original.commit!=paid||original.command!=RestDrivingJson.priced(command).toString())throw SQLiteException("휴식 실제 주행 쓰기 불일치")
  val observed=ObservedDrivingReceipt(context.value.trip,paid)
  when(val r=store.observedDriving.recordCommit(db,id,observed)){is ContractResult.Failure->return r;is ContractResult.Success->Unit}
  val ack=state.copy(revision=state.revision+1,outbox=state.outbox.drop(1))
  when(val r=store.observedDriving.save(db,id,state.revision,ack)){is ContractResult.Failure->return r;is ContractResult.Success->Unit}
  val expectedBalance=paid.settlement?.balance?:balanceBefore
  if(store.readBalance(db)!=expectedBalance||ledger.stateIn(db)!=planned.timeline.after)throw SQLiteException("휴식 지급/소모/ack 최종 실제 상태 불일치")
  return when(val r=committedIn(db,id,driveId)) {
   is ContractResult.Failure->r
   is ContractResult.Success->r.value?.let{ContractResult.Success(it)}?:throw SQLiteException("휴식 최종 복합 원결과 소실")
  }
 }
}
