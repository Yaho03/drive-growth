package io.drivegrowth.app.host.rest

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*

/** 장소 공급자는 확인된 좌표·사용조건·갱신일·영역 판정 정의를 보존해야 한다. 목적지 POI는 이 포트가 아니다. */
data class RestAreaProof(val areaId:String,val kind:RestPlace,val dataset:ContentVersion,val sourceUrl:String,
 val licence:String,val updatedOn:String,val source:InputSource,val inside:Boolean,val elapsedMs:Long,val utc:EpochMillis)
data class RestObservedSpan(val bootId:String,val from:VehicleObservation,val to:VehicleObservation,
 val fromPlace:Observation<RestAreaProof>,val toPlace:Observation<RestAreaProof>)
data class RestProof(val identity:GameIdentity,val stopNumber:Long,val driveId:String,
 val driving:List<RestObservedSpan>,val parked:List<RestObservedSpan>,val departure:RestObservedSpan)
data class RestEvidencePolicy(val maxGapMs:Long,val maxLocationAgeMs:Long,val maxUtcSkewMs:Long,val movingMps:Double,val stationaryMps:Double) {
 init{require(maxGapMs>0&&maxLocationAgeMs>0&&maxUtcSkewMs>=0&&movingMps>stationaryMps&&stationaryMps>=0)}
}
class RestEvidenceValidator(private val policy:RestEvidencePolicy) {
 fun stop(proof:RestProof):ContractResult<RestStop> {
  if(proof.stopNumber<0||proof.driveId.isBlank())return ContractResult.Failure(ContractError.INVALID_INPUT)
  val all=proof.driving+proof.parked+proof.departure
  if(all.any{it.bootId.isBlank()||it.bootId!=proof.departure.bootId||!validSpan(it,proof.identity.source)})
   return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  if(!chain(proof.driving)||!chain(proof.parked)||proof.driving.any{!driving(it.from)||!driving(it.to)}||proof.parked.any{!parked(it.from)||!parked(it.to)})
   return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  if(proof.driving.isNotEmpty()&&!moving(proof.driving.first().from))return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  val departure=proof.departure
  if(!parked(departure.from)||!moving(departure.to)||
   (proof.driving.isNotEmpty()&&proof.parked.isNotEmpty()&&!near(proof.driving.last().to,proof.parked.first().from))||
   (proof.parked.isNotEmpty()&&!near(proof.parked.last().to,departure.from)))
   return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  val entry=area(departure.fromPlace,departure.from)?:return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  val exit=area(departure.toPlace,departure.to)?:return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  if(!entry.inside||exit.inside||!sameArea(entry,exit))return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  val place=if(proof.parked.isNotEmpty()&&proof.parked.all{span->
   val a=area(span.fromPlace,span.from);val b=area(span.toPlace,span.to)
   a!=null&&b!=null&&a.inside&&b.inside&&sameArea(entry,a)&&sameArea(entry,b)
  })Observation.Available(entry.kind) else Observation.Missing(MissingReason.NO_DATA)
  return ContractResult.Success(RestStop(proof.stopNumber,proof.identity.source,place,duration(proof.driving),duration(proof.parked)))
 }
 /** 구간 사이 공백은 보존하되 그 시간은 반환 운전량에 더하지 않는다. */
 fun drivingMsForUse(identity:GameIdentity,spans:List<RestObservedSpan>):ContractResult<Long> {
  if(spans.any{it.bootId.isBlank()||it.bootId!=spans.first().bootId||!validSpan(it,identity.source)||!driving(it.from)||!driving(it.to)})
   return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  if(spans.zipWithNext().any{(a,b)->
   val d=b.from.receivedElapsedMs-a.to.receivedElapsedMs
   d<0||(d==0L&&a.to!=b.from)||b.from.receivedAt.value<a.to.receivedAt.value||kotlin.math.abs((b.from.receivedAt.value-a.to.receivedAt.value)-d)>policy.maxUtcSkewMs
  })return ContractResult.Failure(ContractError.STATE_CONFLICT)
  return ContractResult.Success(spans.sumOf{it.to.receivedElapsedMs-it.from.receivedElapsedMs})
 }
 private fun duration(spans:List<RestObservedSpan>):Observation<Long> = if(spans.isEmpty())Observation.Missing(MissingReason.NO_DATA)
  else Observation.Available(spans.sumOf{it.to.receivedElapsedMs-it.from.receivedElapsedMs})
 private fun chain(spans:List<RestObservedSpan>)=spans.zipWithNext().all{(a,b)->a.to==b.from}
 private fun near(a:VehicleObservation,b:VehicleObservation):Boolean {
  val delta=b.receivedElapsedMs-a.receivedElapsedMs
  return (delta!=0L||a==b)&&delta in 0..policy.maxGapMs&&b.receivedAt.value>=a.receivedAt.value&&
   kotlin.math.abs((b.receivedAt.value-a.receivedAt.value)-delta)<=policy.maxUtcSkewMs
 }
 private fun validSpan(s:RestObservedSpan,source:InputSource)=s.from.source==source&&s.to.source==source&&
  s.from.receivedAt.value>=0&&s.to.receivedAt.value>=0&&s.to.receivedElapsedMs>s.from.receivedElapsedMs&&near(s.from,s.to)
 private fun speed(o:VehicleObservation)=(o.speedMps as? Observation.Available)?.value?.takeIf{it.isFinite()&&kotlin.math.abs(it)<=100}
 private fun driving(o:VehicleObservation):Boolean {
  if(speed(o)==null)return false
  val gear=(o.gear as? Observation.Available)?.value
  return (gear==Gear.DRIVE||gear==Gear.REVERSE)&&o.uxRestricted is Observation.Available
 }
 private fun moving(o:VehicleObservation)=driving(o)&&kotlin.math.abs(speed(o)!!)>=policy.movingMps
 private fun parked(o:VehicleObservation)=speed(o)?.let{kotlin.math.abs(it)<=policy.stationaryMps}==true&&o.gear==Observation.Available(Gear.PARK)&&o.uxRestricted==Observation.Available(false)
 private fun area(raw:Observation<RestAreaProof>,o:VehicleObservation):RestAreaProof? {
  val a=(raw as? Observation.Available)?.value?:return null
  if(a.areaId.isBlank()||a.sourceUrl.isBlank()||a.licence.isBlank()||a.updatedOn.isBlank()||a.source!=o.source||a.elapsedMs<0||a.utc.value<0)return null
  val age=o.receivedElapsedMs-a.elapsedMs
  val utcAge=o.receivedAt.value-a.utc.value
  return a.takeIf{age in 0..policy.maxLocationAgeMs&&utcAge in 0..policy.maxLocationAgeMs&&kotlin.math.abs(age-utcAge)<=policy.maxUtcSkewMs}
 }
 private fun sameArea(a:RestAreaProof,b:RestAreaProof)=a.copy(inside=b.inside,elapsedMs=b.elapsedMs,utc=b.utc)==b
}
