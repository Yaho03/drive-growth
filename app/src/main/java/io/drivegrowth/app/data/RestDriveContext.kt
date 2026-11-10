package io.drivegrowth.app.data

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.ObservedTrip
import io.drivegrowth.app.host.rest.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*

/** root가 채택한 시간순 정수 보상거리/정확 분할/원 주행 한 번 내림의 기술 버전이다. */
enum class RestPricingPolicy(val id:String) { CHRONOLOGICAL_PREFIX_RATIONAL_V1("chronological-prefix-rational-v1") }

/** 원 구간마다 당시 공급한 clock domain/boot/정책을 보존한다. 현재 receiver로 복구하지 않는다. */
data class RestSegmentEvidence(val index:Int,val clockDomain:String,val span:RestObservedSpan,val policy:RestEvidencePolicy)
data class RestClockDomain(val domain:String,val bootId:String) { init{require(domain.isNotBlank()&&bootId.isNotBlank())} }
data class RestDriveContext(val identity:GameIdentity,val trip:ObservedTrip,val restRules:RestRules,
 val pricingPolicy:RestPricingPolicy,val segments:List<RestSegmentEvidence>,val departureClock:RestClockDomain)
data class RestDepartureInput(val requestId:RequestId,val identity:GameIdentity,val driveId:String,
 val driving:List<RestObservedSpan>,val parked:List<RestObservedSpan>,val departure:RestObservedSpan,
 val rules:RestRules,val policy:RestEvidencePolicy,val clock:RestClockDomain)
data class RestDrivingProjection(val revision:Long,val confirmed:RestBonusState,val effective:RestBonusState,
 val confirmedDrives:Long,val pendingDrives:Int,val activeGrant:RestActiveGrant?)
/** remaining은 당시 원결과다. 현재 표시는 state의 pending projection을 별도로 조회한다. */
data class RestDrivingCommit(val context:RestDriveContext,val departures:List<RestAnchoredDeparture>,
 val observed:io.drivegrowth.app.host.driving.ObservedDrivingReceipt,val remaining:RestBonusState,
 val price:RestDrivingPrice)
/** segment=-1은 원 출발 표본, 그 외에는 해당 원 segment의 실제 to 표본 뒤의 사건이다. */
data class RestAnchoredDeparture(val requestId:RequestId,val segment:Int,val packet:RestDeparturePacket)
data class RestActiveGrant(val stopNumber:Long,val rules:RestRules)
data class RestBoostSlice(val segment:Int,val fromMs:Long,val toMs:Long,val percent:Int)
sealed interface RestTimelineOperation {
 data class Use(val consumeId:String,val rules:RestRules,val afterStop:Long?,val elapsedMs:Long,
  val before:RestBonusState,val after:RestBonusState):RestTimelineOperation
 data class Depart(val command:RestAnchoredDeparture,val before:RestBonusState,val result:RestDeparture):RestTimelineOperation
}
data class RestTimelinePlan(val before:RestBonusState,val after:RestBonusState,val activeGrant:RestActiveGrant?,
 val operations:List<RestTimelineOperation>,val boosts:List<RestBoostSlice>,val boostedMs:Long)

/** 지급하지 않는 원순서 계획. 실제 저장 writer가 동일 계획의 Ledger 결과를 하나씩 대조한다. */
internal object RestTimeline {
 fun plan(context:RestDriveContext,departures:List<RestAnchoredDeparture>,before:RestBonusState,
          activeGrant:RestActiveGrant?):ContractResult<RestTimelinePlan> {
  fun conflict()=ContractResult.Failure(ContractError.STATE_CONFLICT)
  if(context.identity.source!=context.trip.source||context.segments.size!=context.trip.segments.size||
   context.segments.map{it.index}!=context.trip.segments.indices.toList()||
   departures.map{it.segment}!=departures.map{it.segment}.sorted()||departures.map{it.segment}.toSet().size!=departures.size||
   departures.any{it.segment !in -1 until context.segments.size})return conflict()
  val lastStop=before.lastStopNumber
  if((before.remainingMs>0&&activeGrant==null)||activeGrant?.let{it.stopNumber<0||lastStop==null||it.stopNumber>lastStop||before.remainingMs>it.rules.bonusDurationMs}==true)return conflict()
  val previous=mutableMapOf<Pair<String,String>,RestSegmentEvidence>()
  for(e in context.segments) {
   val key=e.clockDomain to e.span.bootId
   previous[key]?.let { p->
    val elapsed=e.span.from.receivedElapsedMs-p.span.to.receivedElapsedMs
    if(elapsed<0||(elapsed==0L&&e.span.from!=p.span.to)||e.span.from.receivedAt.value<p.span.to.receivedAt.value)return conflict()
   }
   previous[key]=e
  }
  var state=before;var grant=activeGrant;var boosted=0L
  val operations=mutableListOf<RestTimelineOperation>();val boosts=mutableListOf<RestBoostSlice>()
  fun depart(at:Int):ContractResult<Unit> {
   val event=departures.firstOrNull{it.segment==at}?:return ContractResult.Success(Unit)
   val p=event.packet;val proof=p.proof
   val actual=if(at<0)context.trip.departure else context.trip.segments[at].to
   val clock=if(at<0)context.departureClock else context.segments[at].let{RestClockDomain(it.clockDomain,it.span.bootId)}
   if(proof.identity!=context.identity||proof.driveId!=context.trip.id||proof.departure.to!=actual||p.clock!=clock||p.clock.bootId!=proof.departure.bootId)return conflict()
   val stop=when(val r=RestEvidenceValidator(p.policy).stop(proof)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
   val result=RestBonus(p.rules).depart(state,stop)
   if(result.outcome==RestOutcome.ALREADY_HANDLED)return conflict()
   operations+=RestTimelineOperation.Depart(event,state,result);state=result.state
   if(result.started!=null)grant=RestActiveGrant(proof.stopNumber,p.rules)
   return ContractResult.Success(Unit)
  }
  when(val r=depart(-1)){is ContractResult.Failure->return r;is ContractResult.Success->Unit}
  try {
   for(e in context.segments) {
    val original=context.trip.segments[e.index];val span=e.span
    if(e.clockDomain.isBlank()||span.bootId.isBlank()||span.from!=original.from||span.to!=original.to)return conflict()
    if(span.from==context.trip.departure&&RestClockDomain(e.clockDomain,span.bootId)!=context.departureClock)return conflict()
    val gears=listOf(span.from.gear,span.to.gear)
    val eligible=gears.all{it==Observation.Available(Gear.DRIVE)||it==Observation.Available(Gear.REVERSE)}
    if(eligible) {
     val elapsed=when(val r=RestEvidenceValidator(e.policy).drivingMsForUse(context.identity,listOf(span))){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
     val amount=minOf(elapsed,state.remainingMs)
     if(amount>0) {
      val g=grant?:return conflict()
      boosts+=RestBoostSlice(e.index,0,amount,g.rules.bonusPercent);boosted=Math.addExact(boosted,amount)
     }
     val rules=grant?.rules?:context.restRules
     val after=when(val r=RestBonus(rules).consume(state,elapsed)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
     operations+=RestTimelineOperation.Use("rest/use/${context.trip.id}/${e.index}",rules,state.lastStopNumber,elapsed,state,after);state=after
    }
    when(val r=depart(e.index)){is ContractResult.Failure->return r;is ContractResult.Success->Unit}
   }
  }catch(_:ArithmeticException){return ContractResult.Failure(ContractError.INVALID_INPUT)}
  return ContractResult.Success(RestTimelinePlan(before,state,grant,java.util.Collections.unmodifiableList(operations),java.util.Collections.unmodifiableList(boosts),boosted))
 }
}
