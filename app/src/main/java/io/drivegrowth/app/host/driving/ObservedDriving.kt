package io.drivegrowth.app.host.driving

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import java.math.BigDecimal
import java.time.ZoneId

/** 기술 시험 정책. 수신 신선도/연속성은 호출자가 기존 input 정책에서 명시 공급한다. */
data class RecordingPolicy(val maxAgeMs:Long,val maxGapMs:Long,val maxUtcSkewMs:Long,val parkMs:Long,val stationaryMps:Double,val movingMps:Double) {
 init {require(maxAgeMs>0&&maxGapMs>0&&maxUtcSkewMs>=0&&parkMs>0&&stationaryMps.isFinite()&&stationaryMps>=0&&movingMps.isFinite()&&movingMps>stationaryMps)}
}
data class RecordingClock(val elapsed:Long,val utc:EpochMillis) {init{require(elapsed>=0)}}
enum class DrivingGapReason { MISSING_SIGNAL, STALE, CLOCK_REVERSED, CLOCK_DISCONTINUITY, SIGNAL_GAP, RECOVERY, APP_STOPPED, INVALID_SIGNAL }
data class DrivingGap(val reason:DrivingGapReason,val from:RecordingClock?,val to:RecordingClock,val observation:VehicleObservation?)
data class DrivingSegment(val from:VehicleObservation,val to:VehicleObservation,val meters:BigDecimal)
data class ObservedTrip(val id:String,val departure:VehicleObservation,val zone:Observation<ZoneId>,val rules:ContractResult<DrivingIncomeRules>,
 val meters:BigDecimal=BigDecimal.ZERO,val segments:List<DrivingSegment> = emptyList(),val gaps:List<DrivingGap> = emptyList()) {
 val source:InputSource get()=departure.source
 val departedAt:EpochMillis get()=departure.receivedAt
 init {require(id.isNotBlank()&&departedAt.value>=0&&meters.signum()>=0)}
 fun confirmed(source:InputSource):ConfirmedDrive? {
  if(source!=this.source)return null
  val z=(zone as? Observation.Available)?.value?:return null
  return ConfirmedDrive(id,java.time.Instant.ofEpochMilli(departedAt.value).atZone(z).toLocalDate(),ObservedDistance(meters.setScale(0,java.math.RoundingMode.DOWN).longValueExact(),source))
 }
}
data class RecordingState(val identity:GameIdentity,val revision:Long,val receiver:String?,val trip:ObservedTrip?,
 val anchor:VehicleObservation?,val boundary:RecordingClock?,val parkSince:Long?,val outbox:List<ObservedTrip>) {
 init {require(revision>=0)}
}
/** 수신 원본에 짝지은 zone만 사용한다. 옛 LocalDate를 현재 zone으로 재해석하지 않는다. */
data class DrivingFrame(val observation:VehicleObservation,val zone:Observation<ZoneId>)
class ObservedDrivingReducer(internal val policy:RecordingPolicy,private val nextId:()->String) {
 fun observe(state:RecordingState,receiver:String,frame:DrivingFrame,now:RecordingClock,rules:ContractResult<DrivingIncomeRules>):ContractResult<RecordingState> {
  val input=frame.observation
  if(receiver.isBlank()||input.source!=state.identity.source||now.utc.value<0||input.receivedAt.value<0)
   return ContractResult.Failure(ContractError.INVALID_INPUT)
  if(state.receiver==receiver&&state.anchor==input) return ContractResult.Success(state)
  val at=RecordingClock(input.receivedElapsedMs,input.receivedAt)
  val speed=(input.speedMps as? Observation.Available)?.value
  val gear=(input.gear as? Observation.Available)?.value
  val ux=(input.uxRestricted as? Observation.Available)?.value
  val reason=when {
   now.elapsed<input.receivedElapsedMs||now.utc.value<input.receivedAt.value -> DrivingGapReason.CLOCK_REVERSED
   now.elapsed-input.receivedElapsedMs>policy.maxAgeMs -> DrivingGapReason.STALE
   kotlin.math.abs((now.utc.value-input.receivedAt.value)-(now.elapsed-input.receivedElapsedMs))>policy.maxUtcSkewMs -> DrivingGapReason.CLOCK_DISCONTINUITY
   speed==null||gear==null||ux==null -> DrivingGapReason.MISSING_SIGNAL
   !speed.isFinite()||kotlin.math.abs(speed)>100||gear==Gear.UNKNOWN||
    (gear==Gear.PARK&&kotlin.math.abs(speed)>policy.stationaryMps) -> DrivingGapReason.INVALID_SIGNAL
   else -> null
  }
  if(reason!=null) return revised(state.copy(receiver=receiver,trip=gap(state.trip,reason,state.boundary,at,input),anchor=null,boundary=boundary(state,receiver,at,reason),parkSince=null))
  val previous=state.anchor
  val discontinuity=when {
   state.receiver!=null&&state.receiver!=receiver -> DrivingGapReason.RECOVERY
   state.boundary!=null&&(at.elapsed<=state.boundary.elapsed||at.utc.value<state.boundary.utc.value) -> DrivingGapReason.CLOCK_REVERSED
   previous==null&&state.trip!=null -> DrivingGapReason.RECOVERY
   previous==null -> null
   at.elapsed<=previous.receivedElapsedMs||at.utc.value<previous.receivedAt.value -> DrivingGapReason.CLOCK_REVERSED
   at.elapsed-previous.receivedElapsedMs>policy.maxGapMs -> DrivingGapReason.SIGNAL_GAP
   kotlin.math.abs((at.utc.value-previous.receivedAt.value)-(at.elapsed-previous.receivedElapsedMs))>policy.maxUtcSkewMs -> DrivingGapReason.CLOCK_DISCONTINUITY
   else -> null
  }
  if(discontinuity==DrivingGapReason.CLOCK_REVERSED||discontinuity==DrivingGapReason.CLOCK_DISCONTINUITY)
   return revised(state.copy(receiver=receiver,trip=gap(state.trip,discontinuity,state.boundary,at,input),anchor=null,boundary=boundary(state,receiver,at,discontinuity),parkSince=null))
  var trip=state.trip
  var parkSince=if(discontinuity==null)state.parkSince else null
  if(discontinuity!=null) trip=gap(trip,discontinuity,state.boundary,at,input)
  if(trip==null&&kotlin.math.abs(speed!!)>=policy.movingMps&&(gear==Gear.DRIVE||gear==Gear.REVERSE)) {
   val id=nextId()
   if(id.isBlank()||state.outbox.any{it.id==id}) return ContractResult.Failure(ContractError.STATE_CONFLICT)
   if(rules is ContractResult.Success&&rules.value.dailyCapMeters!=500000L) return ContractResult.Failure(ContractError.INVALID_INPUT)
   trip=ObservedTrip(id,input,frame.zone,rules)
  } else if(trip!=null&&previous!=null&&discontinuity==null) {
   val before=(previous.speedMps as Observation.Available).value
   val meters=BigDecimal.valueOf(kotlin.math.abs(before)).add(BigDecimal.valueOf(kotlin.math.abs(speed!!)))
    .multiply(BigDecimal.valueOf(at.elapsed-previous.receivedElapsedMs)).divide(BigDecimal(2000))
   trip=trip.copy(meters=trip.meters+meters,segments=trip.segments+DrivingSegment(previous,input,meters))
  }
  var outbox=state.outbox
  val parked=gear==Gear.PARK&&kotlin.math.abs(speed!!)<=policy.stationaryMps&&ux==false
  if(trip!=null&&parked) {
   if(parkSince==null)parkSince=at.elapsed
   if(at.elapsed-parkSince>=policy.parkMs) {outbox=outbox+trip;trip=null;parkSince=null}
  } else parkSince=null
  return revised(state.copy(receiver=receiver,trip=trip,anchor=input,boundary=at,parkSince=parkSince,outbox=outbox))
 }
 fun pause(state:RecordingState,receiver:String,at:RecordingClock):ContractResult<RecordingState> {
  if(receiver.isBlank()||at.utc.value<0) return ContractResult.Failure(ContractError.INVALID_INPUT)
  return revised(state.copy(receiver=receiver,trip=gap(state.trip,DrivingGapReason.APP_STOPPED,state.boundary,at,null),anchor=null,boundary=boundary(state,receiver,at,DrivingGapReason.APP_STOPPED),parkSince=null))
 }
 // 같은 수신 세션의 역전 표본은 마지막 확인 경계도 뒤로 돌리지 않는다.
 private fun boundary(state:RecordingState,receiver:String,at:RecordingClock,reason:DrivingGapReason):RecordingClock? {
  if(state.receiver!=receiver)return at
  val old=state.boundary?:return null
  return if(reason==DrivingGapReason.CLOCK_REVERSED||reason==DrivingGapReason.CLOCK_DISCONTINUITY||at.elapsed<old.elapsed||at.utc.value<old.utc.value)old else at
 }
 private fun gap(trip:ObservedTrip?,reason:DrivingGapReason,from:RecordingClock?,to:RecordingClock,observation:VehicleObservation?)=
  trip?.copy(gaps=trip.gaps+DrivingGap(reason,from,to,observation))
 private fun revised(state:RecordingState):ContractResult<RecordingState> =
  if(state.revision==Long.MAX_VALUE) ContractResult.Failure(ContractError.STATE_CONFLICT)
  else ContractResult.Success(state.copy(revision=state.revision+1))
}
