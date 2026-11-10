package io.drivegrowth.app.host.rest

import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import java.util.Collections
import java.util.UUID
import kotlin.math.abs

/**
 * 같은 serial worker에서 observe 후 기존 SessionObservedDrivingSource를 호출한다.
 * raw 장소 관측은 그대로 보존한다. DB·금융·순번 발급·SDK/영역 판정은 수행하지 않는다.
 * pause는 전경 연속성을 끊되 이미 만든 원 context는 유지한다. 새 인스턴스는 실제 저장 context만 restore한다.
 */
class ForegroundRestObservationProducer(
    private val identity:GameIdentity,
    private val rules:()->ContractResult<RestRules>,
    private val policy:RestEvidencePolicy,
    private val pricing:RestPricingPolicy,
):RestObservationProvider {
    private data class Sample(val receiver:String,val clock:RestClockDomain,val raw:VehicleObservation,val place:Observation<RestAreaProof>)
    private data class Stop(val driving:List<RestObservedSpan>,val parked:List<RestObservedSpan>,val lastPark:Sample)
    private data class Exit(val stop:Stop,val span:RestObservedSpan,val clock:RestClockDomain)
    private val samples=linkedMapOf<String,MutableList<Sample>>()
    private var sampleCount=0
    private var latest:Sample?=null
    private val staged=mutableListOf<Sample?>()
    private val durableIds=mutableSetOf<String>()
    private data class Recognition(val last:Sample?,val started:Boolean,val driving:List<RestObservedSpan>,
        val beforePark:List<RestObservedSpan>,val parked:List<RestObservedSpan>,val parking:Boolean,val pending:Stop?)
    private val checkpoints=linkedMapOf<List<Any?>,Recognition>()
    private data class Prepared(val previous:List<Any?>,val next:List<Any?>,val recognition:Recognition,val batch:RestObservationBatch)
    private var prepared:Prepared?=null
    private val contexts=linkedMapOf<String,List<RestDriveContext>>()
    private val exits=linkedMapOf<String,Exit>()
    private val inputs=linkedMapOf<String,RestDepartureInput>()
    private var last:Sample?=null
    private var driveStarted=false
    private val driving=mutableListOf<RestObservedSpan>()
    private var beforePark:List<RestObservedSpan> = emptyList()
    private val parked=mutableListOf<RestObservedSpan>()
    private var parking=false
    private var pendingStop:Stop?=null
    private val validator=RestEvidenceValidator(policy)

    private fun failure(error:ContractError)=ContractResult.Failure(error)
    private fun raw(o:VehicleObservation)=listOf(o.source,o.receivedElapsedMs,o.receivedAt,
        when(val speed=o.speedMps){is Observation.Available->Observation.Available(speed.value.toRawBits());is Observation.Missing->speed},o.gear,o.uxRestricted).toString()
    private fun trip(t:ObservedTrip):List<Any?> = listOf(t.id,raw(t.departure),t.zone,t.rules,t.meters,
        t.segments.map{listOf(raw(it.from),raw(it.to),it.meters)},
        t.gaps.map{listOf(it.reason,it.from,it.to,it.observation?.let(::raw))})
    private fun state(s:RecordingState)=listOf(s.identity,s.revision,s.receiver,s.trip?.let(::trip),s.anchor?.let(::raw),s.boundary,s.parkSince,s.outbox.map(::trip))
    private fun key(s:Sample)=s.clock.bootId+"\n"+s.clock.domain+"\n"+raw(s.raw)
    private fun anchor(c:RestDriveContext,to:VehicleObservation,clock:RestClockDomain):Int? {
        if(raw(c.trip.departure)==raw(to)&&c.departureClock==clock)return -1
        return c.segments.indexOfFirst{raw(it.span.to)==raw(to)&&RestClockDomain(it.clockDomain,it.span.bootId)==clock}.takeIf{it>=0}
    }
    private fun <T> frozen(values:List<T>):List<T> = Collections.unmodifiableList(ArrayList(values))
    private fun snapshot(c:RestDriveContext)=c.copy(trip=c.trip.copy(segments=frozen(c.trip.segments),gaps=frozen(c.trip.gaps)),segments=frozen(c.segments))
    private fun sample(o:VehicleObservation)=samples[raw(o)]?.singleOrNull()
    private fun isDrive(o:VehicleObservation):Boolean {
        val speed=(o.speedMps as? Observation.Available)?.value ?: return false
        return speed.isFinite() && abs(speed)<=100 && o.uxRestricted is Observation.Available &&
            (o.gear==Observation.Available(Gear.DRIVE)||o.gear==Observation.Available(Gear.REVERSE))
    }
    private fun moving(o:VehicleObservation)=isDrive(o) && abs((o.speedMps as Observation.Available).value)>=policy.movingMps
    private fun isPark(o:VehicleObservation):Boolean {
        val speed=(o.speedMps as? Observation.Available)?.value ?: return false
        return speed.isFinite() && abs(speed)<=policy.stationaryMps && o.gear==Observation.Available(Gear.PARK) && o.uxRestricted==Observation.Available(false)
    }
    private fun span(a:Sample,b:Sample)=RestObservedSpan(a.clock.bootId,a.raw,b.raw,a.place,b.place)
    private fun continuous(a:Sample,b:Sample):Boolean {
        if(a.receiver!=b.receiver||a.clock!=b.clock)return false
        val elapsed=b.raw.receivedElapsedMs-a.raw.receivedElapsedMs
        if(elapsed !in 1..policy.maxGapMs)return false
        val utc=b.raw.receivedAt.value-a.raw.receivedAt.value
        return utc>=0 && abs(utc-elapsed)<=policy.maxUtcSkewMs
    }
    private fun breakEvidence(){driveStarted=false;driving.clear();beforePark=emptyList();parked.clear();parking=false;pendingStop=null}

    private fun invalidate(){if(staged.isEmpty()||staged.last()!=null)staged+=null}

    /** 해당 raw 표본에 붙인 당시 clock/장소만 수락한다. 늦은 장소 응답으로 Missing을 덮지 않는다. */
    fun observe(receiver:String,clock:RestClockDomain,observation:VehicleObservation,place:Observation<RestAreaProof>):ContractResult<Unit> {
        if(observation.source!=identity.source||observation.receivedElapsedMs<0||observation.receivedAt.value<0)return failure(ContractError.INVALID_INPUT)
        if(receiver.isBlank())return failure(ContractError.INVALID_INPUT)
        val same=samples[raw(observation)]?.firstOrNull{it.clock==clock}
        if(same!=null) {
            if(same.place!=place)return failure(ContractError.STATE_CONFLICT)
            if(latest?.receiver!=receiver) {
                if(latest?.let{it.clock!=clock||raw(it.raw)!=raw(observation)}==true){invalidate();return failure(ContractError.STATE_CONFLICT)}
                if(staged.size>=32768){invalidate();return failure(ContractError.DATA_UNAVAILABLE)}
                staged+=same.copy(receiver=receiver);latest=same.copy(receiver=receiver)
            }
            return ContractResult.Success(Unit)
        }
        val old=latest
        if(old!=null && old.clock.bootId==clock.bootId && observation.receivedElapsedMs<=old.raw.receivedElapsedMs) {
            invalidate();return failure(ContractError.STATE_CONFLICT)
        }
        if(sampleCount>=32768){invalidate();return failure(ContractError.DATA_UNAVAILABLE)} // 전경 raw 캐시의 기술 상한.
        val current=Sample(receiver,clock,observation,place)
        samples.getOrPut(raw(observation)){mutableListOf()}.add(current);sampleCount++
        staged+=current;latest=current
        return ContractResult.Success(Unit)
    }

    private fun advance(current:Sample) {
        val old=last
        val observation=current.raw
        val clock=current.clock
        if(old==null||!continuous(old,current)) {
            breakEvidence();driveStarted=moving(observation)
        } else when {
            isDrive(old.raw)&&isDrive(observation)->{
                if(driveStarted)driving+=span(old,current) else if(moving(observation))driveStarted=true
            }
            isDrive(old.raw)&&isPark(observation)->{
                beforePark=frozen(driving);driving.clear();driveStarted=false;parked.clear();parking=true;pendingStop=null
            }
            isPark(old.raw)&&isPark(observation)->if(parking)parked+=span(old,current)
            isPark(old.raw)&&isDrive(observation)->{
                if(parking && beforePark.isNotEmpty() && parked.isNotEmpty())pendingStop=Stop(beforePark,frozen(parked),old)
                beforePark=emptyList();parked.clear();parking=false;driving.clear();driveStarted=moving(observation)
            }
            else->breakEvidence()
        }
        pendingStop?.let { stop ->
            if(stop.lastPark.clock!=clock||observation.receivedElapsedMs-stop.lastPark.raw.receivedElapsedMs>policy.maxGapMs)pendingStop=null
            else if(moving(observation)) {
                val exit=span(stop.lastPark,current)
                if(validator.stop(RestProof(identity,0,"validation-only",stop.driving,stop.parked,exit)) is ContractResult.Success) {
                    exits[key(current)]=Exit(stop,exit,clock);pendingStop=null
                }
            }
        }
        last=current
    }

    /** foreground/receiver 수명이 끝나면 원점·운전/주차 연속성을 버린다. durable context를 현재값으로 재작성하지 않는다. */
    fun pause(){prepared=null;breakEvidence();last=null;latest=null;staged.clear();checkpoints.clear();samples.clear();sampleCount=0}

    // outbox 지급 ACK는 같은 실제 anchor의 휴식 연속성을 바꾸지 않는다. 제안 next는 저장 ACK가 아니다.
    private fun evidenceKey(s:RecordingState):List<Any?> = listOf(s.receiver,s.anchor?.let(::raw),s.trip?.id,s.boundary,s.parkSince)
    private fun recognition()=Recognition(last,driveStarted,frozen(driving),beforePark,frozen(parked),parking,pendingStop)
    private fun resume(r:Recognition?) {
        breakEvidence();last=r?.last
        if(r!=null){driveStarted=r.started;driving+=r.driving;beforePark=r.beforePark;parked+=r.parked;parking=r.parking;pendingStop=r.pending}
    }
    private fun eligible(input:RestDepartureInput):Boolean {
        val stop=validator.stop(RestProof(identity,0,input.driveId,input.driving,input.parked,input.departure))
        return stop is ContractResult.Success && RestBonus(input.rules).classify(stop.value)==RestOutcome.ACCEPTED
    }

    /** caller가 실제 저장소에서 원 ordinal 순서로 읽은 whole context만 공급한다. 장소/현재 boot를 복원하지 않는다. */
    fun restore(originals:List<RestDriveContext>):ContractResult<Unit> {
        if(originals.any{it.identity!=identity||it.trip.source!=identity.source})return failure(ContractError.INVALID_INPUT)
        if(originals.map{it.trip.id}.toSet().size!=originals.size||!RestTimeline.clockOrder(originals))return failure(ContractError.STATE_CONFLICT)
        for(c in originals) {
            when(val valid=RestTimeline.plan(c,emptyList(),RestBonusState.NONE,null)){is ContractResult.Failure->return valid;is ContractResult.Success->Unit}
            val prior=contexts[c.trip.id]
            if(prior!=null&&prior.none{trip(it.trip)==trip(c.trip)&&it.copy(trip=c.trip)==c})return failure(ContractError.STATE_CONFLICT)
        }
        for(c in originals){contexts[c.trip.id]=listOf(snapshot(c));durableIds+=c.trip.id}
        return ContractResult.Success(Unit)
    }

    override fun prepare(previous:RecordingState,next:RecordingState):ContractResult<RestObservationBatch> {
        if(previous.identity!=identity||next.identity!=identity)return failure(ContractError.INVALID_INPUT)
        if(next.revision!=previous.revision && (previous.revision==Long.MAX_VALUE||next.revision!=previous.revision+1))return failure(ContractError.STATE_CONFLICT)
        if(next.revision==previous.revision&&state(previous)!=state(next))return failure(ContractError.STATE_CONFLICT)
        // 같은 원 제안의 저장 재시도는 next의 recognition도 그대로 재생한다. 새 staged 입력은 소비하지 않는다.
        prepared?.let { original->
            if(original.previous==state(previous)&&original.next==state(next)) {
                resume(original.recognition)
                return ContractResult.Success(original.batch)
            }
        }
        val prior=previous.outbox+listOfNotNull(previous.trip)
        durableIds+=prior.map{it.id}
        val ordered=next.outbox+listOfNotNull(next.trip)
        if(ordered.any{it.source!=identity.source}||ordered.map{it.id}.toSet().size!=ordered.size)return failure(ContractError.STATE_CONFLICT)
        val result=mutableListOf<RestDriveContext>()
        for(t in ordered) {
            val variants=contexts[t.id].orEmpty()
            val same=variants.firstOrNull{trip(it.trip)==trip(t)}
            if(same!=null){result+=same;continue}
            val priorTrip=prior.firstOrNull{it.id==t.id}
            val prefix=priorTrip?.let{old->variants.firstOrNull{trip(it.trip)==trip(old)}}
            if(priorTrip!=null&&prefix==null)return failure(ContractError.DATA_UNAVAILABLE)
            if(prefix!=null) {
                val old=prefix.trip
                if(t.segments.size<old.segments.size||t.gaps.size<old.gaps.size)return failure(ContractError.STATE_CONFLICT)
                val retained=t.copy(meters=old.meters,segments=t.segments.take(old.segments.size),gaps=t.gaps.take(old.gaps.size))
                if(trip(retained)!=trip(old))return failure(ContractError.STATE_CONFLICT)
            }
            val header=prefix?.departureClock?:sample(t.departure)?.clock?:return failure(ContractError.DATA_UNAVAILABLE)
            val originalRules=if(prefix!=null)prefix.restRules else when(val r=rules()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
            val sidecars=prefix?.segments.orEmpty().toMutableList()
            for(index in sidecars.size until t.segments.size) {
                val segment=t.segments[index]
                val from=sample(segment.from)?:return failure(ContractError.DATA_UNAVAILABLE)
                val to=sample(segment.to)?:return failure(ContractError.DATA_UNAVAILABLE)
                if(from.clock!=to.clock)return failure(ContractError.STATE_CONFLICT)
                sidecars+=RestSegmentEvidence(index,from.clock.domain,span(from,to),policy)
            }
            val context=snapshot(RestDriveContext(identity,t,originalRules,prefix?.pricingPolicy?:pricing,sidecars,header))
            when(val valid=RestTimeline.plan(context,emptyList(),RestBonusState.NONE,null)){is ContractResult.Failure->return valid;is ContractResult.Success->Unit}
            contexts[t.id]=listOfNotNull(prefix,context).distinctBy{trip(it.trip)}
            result+=context
        }
        if(!RestTimeline.clockOrder(result))return failure(ContractError.STATE_CONFLICT)
        // 기록기가 받아들인 원 표본만 사용한다. RECOVERY 원점은 새 연속성의 시작일 뿐 이전 구간 증거가 아니다.
        val accepted=result.flatMap{c->listOf(c.trip.departure)+c.trip.segments.flatMap{listOf(it.from,it.to)}}.map(::raw).toSet()+listOfNotNull(next.anchor?.let(::raw))
        val gaps=ordered.flatMap{it.gaps}.mapNotNull{it.observation?.let(::raw)}.toSet()
        val priorKey=evidenceKey(previous)
        val nextKey=evidenceKey(next)
        resume(checkpoints[priorKey]?:if(staged.isEmpty())checkpoints[nextKey]else null)
        for(s in staged) {
            if(s==null||raw(s.raw) !in accepted){breakEvidence();last=null;continue}
            if(raw(s.raw) in gaps){breakEvidence();last=null}
            advance(s)
        }
        staged.clear()
        val ids=(prior+ordered).map{it.id}.toSet()
        val retired=inputs.filterValues{it.driveId in durableIds && it.driveId !in ids}.keys.toList()
        for(k in retired){inputs.remove(k);exits.remove(k)}
        val departures=mutableListOf<RestDepartureInput>()
        for((exitKey,exit) in exits) {
            val candidates=result.filter{anchor(it,exit.span.to,exit.clock)!=null}
            if(candidates.isEmpty()) {
                if(inputs[exitKey]?.let(::eligible)==true)return failure(ContractError.STATE_CONFLICT)
                continue
            }
            if(candidates.size!=1)return failure(ContractError.STATE_CONFLICT)
            val context=candidates.single()
            val input=inputs[exitKey]?:RestDepartureInput(RequestId("rest/departure/"+UUID.nameUUIDFromBytes((identity.profileId.value+"\n"+identity.source.name+"\n"+context.trip.id+"\n"+exitKey).toByteArray(Charsets.UTF_8))),identity,context.trip.id,
                exit.stop.driving,exit.stop.parked,exit.span,context.restRules,policy,exit.clock).also{inputs[exitKey]=it}
            if(input.driveId!=context.trip.id)return failure(ContractError.STATE_CONFLICT)
            departures+=input
        }
        val acknowledged=contexts.keys.filter{it in durableIds && it !in ids}
        for(id in acknowledged){contexts.remove(id);durableIds.remove(id)}
        checkpoints[nextKey]=recognition()
        checkpoints.keys.retainAll(setOf(priorKey,nextKey))
        val batch=RestObservationBatch(frozen(result),frozen(departures.sortedWith(compareBy<RestDepartureInput>{d->ordered.indexOfFirst{it.id==d.driveId}}.thenBy{d->anchor(result.single{it.trip.id==d.driveId},d.departure.to,d.clock)!!})))
        prepared=Prepared(state(previous),state(next),recognition(),batch)
        return ContractResult.Success(batch)
    }
}
