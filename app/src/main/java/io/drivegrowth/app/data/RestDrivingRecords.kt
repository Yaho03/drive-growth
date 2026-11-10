package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.app.host.rest.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.json.JSONObject

internal data class RestPricedCommand(val context:RestDriveContext,val departures:List<RestAnchoredDeparture>,
 val before:RestBonusState,val grantBefore:RestActiveGrant?,val dayBefore:DrivingDay?,val ordinal:Long)
internal data class RestPriceCalculation(val timeline:RestTimelinePlan,val price:RestDrivingPrice)
internal data class VerifiedRestDrive(val command:RestPricedCommand,val calculation:RestPriceCalculation,val logCount:Long,val lastSequence:Long)

/** 저장 당시 원문에서 같은 순수 계획/산식을 재생한다. 현재 정의/현재 잔액을 승인 근거로 쓰지 않는다. */
internal object RestDrivingRecords {
 fun calculate(c:RestPricedCommand):ContractResult<RestPriceCalculation> {
  val timeline=when(val r=RestTimeline.plan(c.context,c.departures,c.before,c.grantBefore)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
  val windows=timeline.boosts.map{RestPricingWindow(it.segment,it.fromMs,it.toMs,it.percent)}
  val price=when(val r=RestDrivingPricing.price(c.context.trip,c.dayBefore,windows)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
  if(price.policyVersion!=c.context.pricingPolicy.id||price.boostedMs!=timeline.boostedMs)return ContractResult.Failure(ContractError.INVALID_INPUT)
  return ContractResult.Success(RestPriceCalculation(timeline,price))
 }
 fun verify(db:SQLiteDatabase,source:InputSource,c:RestPricedCommand):VerifiedRestDrive {
  val id=c.context.identity
  if(id.source!=source||c.ordinal<1)throw SQLiteException("휴식 주행 원명령 권위/순번 손상")
  db.rawQuery("SELECT profile_id,source FROM initialization_profile WHERE id=1",null).use{if(!it.moveToFirst()||it.text(0)!=id.profileId.value||it.text(1)!=id.source.name)throw SQLiteException("휴식 주행 실제 프로필 불일치")}
  val module=RestAtomicStorage.marker(db,id)?:throw SQLiteException("휴식 주행 marker 소실")
  val contexts=RestAtomicStorage.contexts(db,id)
  if(contexts.size.toLong()!=module.contextCount||contexts.withIndex().any{it.value.ordinal!=it.index+1L})throw SQLiteException("휴식 주행 context 커서 소실")
  val receiptIds=db.rawQuery("SELECT drive_id FROM rest_driving_receipts",null).use{r->buildSet{while(r.moveToNext())add(r.text(0))}}
  if(receiptIds!=contexts.filter{it.ordinal<=module.confirmedCount}.map{it.value.trip.id}.toSet())throw SQLiteException("휴식 주행 복합 원결과/커서 소실")
  val context=contexts.singleOrNull{it.ordinal==c.ordinal}?:throw SQLiteException("휴식 주행 원구간 소실")
  if(RestDrivingJson.context(context.value).toString()!=RestDrivingJson.context(c.context).toString()||module.confirmedCount<c.ordinal)throw SQLiteException("휴식 주행 원구간/확정 커서 손상")
  val events=RestAtomicStorage.departures(db,id).filter{it.packet.proof.driveId==c.context.trip.id}
  if(events.map{RestDrivingJson.anchored(it).toString()}!=c.departures.map{RestDrivingJson.anchored(it).toString()})throw SQLiteException("휴식 주행 원출발 연결 손상")
  val previous=if(c.ordinal==1L)null else {
   val prior=contexts.singleOrNull{it.ordinal==c.ordinal-1}?:throw SQLiteException("휴식 주행 앞 커서 소실")
   DrivingRecordReader(source).readDrive(db,prior.value.trip.id)?.rest?:throw SQLiteException("휴식 주행 앞 원결과 소실")
  }
  if(c.before!=(previous?.calculation?.timeline?.after?:RestBonusState.NONE)||c.grantBefore!=previous?.calculation?.timeline?.activeGrant)throw SQLiteException("휴식 주행 원 grant/상태 이어짐 손상")
  val calculated=when(val r=calculate(c)){is ContractResult.Failure->throw SQLiteException("휴식 주행 재계산 실패: ${r.error}");is ContractResult.Success->r.value}
  var sequence=previous?.lastSequence?:0L;var count=previous?.logCount?:0L
  for(op in calculated.timeline.operations) {
   val kind:String;val key:String;val command:JSONObject;val result:JSONObject
   when(op) {
    is RestTimelineOperation.Use->{kind="CONSUME";key=op.consumeId;command=RestJson.consumeCommand(op.rules,op.consumeId,op.afterStop,op.elapsedMs);result=RestJson.consumeResult(op.before,op.after)}
    is RestTimelineOperation.Depart->{kind="DEPART";val p=op.command.packet;key=p.proof.stopNumber.toString();val stop=(RestEvidenceValidator(p.policy).stop(p.proof) as? ContractResult.Success)?.value?:throw SQLiteException("휴식 원출발 증거 손상");command=RestJson.departCommand(p.rules,stop);result=RestJson.departResult(op.before,op.result)}
   }
   sequence=db.rawQuery("SELECT seq,prev,command,result FROM rest_log WHERE kind=? AND key=?",arrayOf(kind,key)).use{
    if(!it.moveToFirst()||it.integer(1,0,Long.MAX_VALUE)!=sequence||it.text(2)!=command.toString()||it.text(3)!=result.toString())throw SQLiteException("휴식 실제 소모/출발 원결과 소실 또는 불일치")
    val seq=it.integer(0,1,Long.MAX_VALUE);if(seq<=sequence||it.moveToNext())throw SQLiteException("휴식 원순서 손상");seq
   }
   count=Math.addExact(count,1)
  }
  val head=RestAtomicStorage.head(db)
  db.rawQuery("SELECT COUNT(*),MAX(seq) FROM rest_log",null).use{it.moveToFirst();if(it.integer(0,0,Long.MAX_VALUE)!=module.headCount||(if(it.isNull(1))0L else it.integer(1,1,Long.MAX_VALUE))!=module.headSequence)throw SQLiteException("휴식 실제 전체 로그 소실")}
  if(head.first!=module.headSequence||head.second!=module.headCount||module.headCount<count||
   (module.confirmedCount==c.ordinal&&(head.first!=sequence||head.second!=count)))throw SQLiteException("휴식 실제 head/원소모 커서 손상")
  return VerifiedRestDrive(c,calculated,count,sequence)
 }
 fun verifyReceipt(db:SQLiteDatabase,v:VerifiedRestDrive,commit:DriveCommit) {
  db.rawQuery("SELECT profile,source,payload,digest FROM rest_driving_receipts WHERE drive_id=?",arrayOf(commit.driveId)).use{
   if(!it.moveToFirst())throw SQLiteException("휴식 복합 원결과 누락")
   val raw=RestDrivingJson.receipt(v.command,v.calculation,commit).toString()
   if(it.text(0)!=v.command.context.identity.profileId.value||it.text(1)!=v.command.context.identity.source.name||it.text(2)!=raw||it.text(3)!=ObservedDrivingJson.digest(raw))throw SQLiteException("휴식 복합 원결과 불일치")
  }
 }
}
