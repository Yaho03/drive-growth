package io.drivegrowth.app.host.rest

import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId

/** 명시 축소 SIMULATION. 실제 SDK 장소·부팅·운전 또는 지급 성공 근거가 아니다. */
class ForegroundRestObservationProducerTest {
    private val identity=GameIdentity(ProfileId("producer-fixture"),InputSource.SIMULATION)
    private val rules=RestRules(RuleVersion("rest-fixture"),1000,1000,2000,150)
    private val policy=RestEvidencePolicy(1500,1500,0,0.5,0.1)
    private val clock=RestClockDomain("system-elapsed-realtime","actual-fixture-boot")
    private val missing=Observation.Missing(MissingReason.NO_DATA)
    private fun producer(r:()->ContractResult<RestRules> = {ContractResult.Success(rules)})=
        ForegroundRestObservationProducer(identity,r,policy,RestPricingPolicy.CHRONOLOGICAL_PREFIX_RATIONAL_V1)
    private fun o(t:Long,park:Boolean=false,epoch:Long=100000+t)=VehicleObservation(identity.source,t,EpochMillis(epoch),
        Observation.Available(if(park)0.0 else 10.0),Observation.Available(if(park)Gear.PARK else Gear.DRIVE),Observation.Available(!park))
    private fun area(o:VehicleObservation,inside:Boolean)=Observation.Available(RestAreaProof("verified-fixture-area",RestPlace.SERVICE_AREA,
        ContentVersion("fixture-dataset"),"https://example.test/fixture","explicit simulation","2026-11-10",identity.source,inside,o.receivedElapsedMs,o.receivedAt))
    private fun empty()=RecordingState(identity,0,null,null,null,null,null,emptyList())
    private fun state(samples:List<VehicleObservation>,id:String="one-drive",revision:Long=1,prior:List<DrivingSegment> = emptyList()):RecordingState {
        val segments=prior+samples.zipWithNext().filter{(a,b)->b.receivedElapsedMs-a.receivedElapsedMs in 1..policy.maxGapMs}.map{(a,b)->
            val av=(a.speedMps as Observation.Available).value;val bv=(b.speedMps as Observation.Available).value
            DrivingSegment(a,b,BigDecimal.valueOf(av).add(BigDecimal.valueOf(bv)).multiply(BigDecimal.valueOf(b.receivedElapsedMs-a.receivedElapsedMs)).divide(BigDecimal(2000))) }
        val departure=prior.firstOrNull()?.from?:samples.first()
        val trip=ObservedTrip(id,departure,Observation.Available(ZoneId.of("Asia/Seoul")),ContractResult.Success(DrivingIncomeRules(rules.ruleVersion,500000,GameMoney(1000))),segments.fold(BigDecimal.ZERO){a,s->a+s.meters},segments)
        return empty().copy(revision=revision,receiver="receiver-1",trip=trip,anchor=samples.last())
    }
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    private fun record(p:ForegroundRestObservationProducer,samples:List<VehicleObservation>,known:Boolean=false,c:RestClockDomain=clock) {
        for(s in samples)ok(p.observe("receiver-1",c,s,if(known)area(s,s.gear==Observation.Available(Gear.PARK))else missing))
    }
    @Test fun missingLocationPreservesOriginalContextAndBasePrice() {
        val p=producer();val samples=listOf(o(0),o(1000));record(p,samples)
        val next=state(samples);val batch=ok(p.prepare(empty(),next));val context=batch.contexts.single()
        assertEquals(next.trip,context.trip);assertEquals(clock,context.departureClock);assertEquals(0,context.segments.single().index)
        assertEquals(missing,context.segments.single().span.fromPlace);assertTrue(batch.departures.isEmpty())
        val priced=ok(RestDrivingRecords.calculate(RestPricedCommand(context,emptyList(),RestBonusState.NONE,null,null,1)))
        assertEquals(GameMoney(10),priced.price.settlement.amount);assertEquals(10L,priced.price.settlement.rewardedMeters)
    }
    @Test fun actualTwoExitsKeepOneDriveAndCarryRefreshOrder() {
        val p=producer();val samples=listOf(o(0),o(1000),o(1500,true),o(2500,true),o(3500),o(4500),o(5000,true),o(6000,true),o(7000),o(8000));record(p,samples,true)
        val next=state(samples);val batch=ok(p.prepare(empty(),next));val c=batch.contexts.single()
        assertEquals(next.trip,c.trip);assertEquals(listOf(3500L,7000L),batch.departures.map{it.departure.to.receivedElapsedMs})
        assertEquals(2,batch.departures.map{it.requestId}.toSet().size)
        val anchored=batch.departures.mapIndexed{i,d->RestAnchoredDeparture(d.requestId,c.trip.segments.indexOfFirst{it.to==d.departure.to},RestDeparturePacket(RestProof(identity,i+1L,d.driveId,d.driving,d.parked,d.departure),d.rules,d.policy,d.clock))}
        val plan=ok(RestTimeline.plan(c,anchored,RestBonusState(500,0),RestActiveGrant(0,rules)))
        assertEquals(2500L,plan.boostedMs);assertEquals(RestBonusState(1000,2),plan.after)
        assertEquals(batch,ok(p.prepare(empty(),next)))
    }
    @Test fun duplicateRawInputIsIdempotentButLaterPlaceCannotRewriteMissing() {
        val p=producer();val a=o(0);ok(p.observe("receiver-1",clock,a,missing));ok(p.observe("receiver-1",clock,a,missing))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),p.observe("receiver-1",clock,a,area(a,true)))
        val b=o(1000);ok(p.observe("receiver-1",clock,b,missing));val batch=ok(p.prepare(empty(),state(listOf(a,b))))
        assertEquals(missing,batch.contexts.single().segments.single().span.fromPlace)
    }
    @Test fun retryDoesNotReadNewRulesOrReplaceOriginalSidecar() {
        var calls=0;var fail=false;val p=producer{calls++;if(fail)ContractResult.Failure(ContractError.DATA_UNAVAILABLE)else ContractResult.Success(rules)}
        val samples=listOf(o(0),o(1000));record(p,samples);val next=state(samples);val first=ok(p.prepare(empty(),next));fail=true
        assertEquals(first,ok(p.prepare(empty(),next)));assertEquals(1,calls)
    }
    @Test fun initialParkOrMissingPlaceNeverInventsAQualifiedExit() {
        for(known in listOf(false,true)) {
            val p=producer();val samples=listOf(o(0,true),o(1000,true),o(2000));record(p,samples,known)
            assertTrue(ok(p.prepare(empty(),state(samples))).departures.isEmpty())
        }
    }
    @Test fun ordinaryParkingAndStillInsideCannotBecomeAcceptedRest() {
        val p=producer();val samples=listOf(o(0),o(1000),o(1500,true),o(2500,true),o(3500))
        for(s in samples)ok(p.observe("receiver-1",clock,s,area(s,true)))
        assertTrue(ok(p.prepare(empty(),state(samples))).departures.isEmpty())
    }
    @Test fun shortRealParkingIsClassifiedByExistingRulesNotExtendedToThreshold() {
        val p=producer();val samples=listOf(o(0),o(1000),o(1500,true),o(2000,true),o(2500));record(p,samples,true)
        val d=ok(p.prepare(empty(),state(samples))).departures.single();val stop=ok(RestEvidenceValidator(policy).stop(RestProof(identity,1,d.driveId,d.driving,d.parked,d.departure)))
        assertEquals(Observation.Available(500L),stop.parkedMs);assertEquals(RestOutcome.PARKING_TOO_SHORT,RestBonus(rules).classify(stop))
    }
    @Test fun foregroundPausePreservesKnownPrefixButBreaksRestEvidence() {
        val p=producer();val first=listOf(o(0),o(1000));record(p,first,true);val before=state(first);val original=ok(p.prepare(empty(),before)).contexts.single();p.pause()
        val second=listOf(o(5000,true),o(6000,true),o(7000),o(8000));val nextClock=clock.copy(domain="receiver-2");record(p,second,true,nextClock)
        val after=state(second,revision=2,prior=before.trip!!.segments);val b=ok(p.prepare(before,after))
        assertEquals(original.segments,b.contexts.single().segments.take(1));assertTrue(b.departures.isEmpty());assertEquals(nextClock.domain,b.contexts.single().segments.last().clockDomain)
    }
    @Test fun restoreRequiresActualStoredContextInsteadOfCurrentBootBackfill() {
        val first=listOf(o(0),o(1000));val original=producer();record(original,first);val before=state(first);val context=ok(original.prepare(empty(),before)).contexts.single()
        val p=producer();val more=listOf(o(5000),o(6000));record(p,more,c=clock.copy(domain="receiver-2"));val after=state(more,revision=2,prior=before.trip!!.segments)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),p.prepare(before,after));ok(p.restore(listOf(context)))
        val restored=ok(p.prepare(before,after)).contexts.single();assertEquals(context.departureClock,restored.departureClock);assertEquals(context.segments,restored.segments.take(1))
    }
    @Test fun sourceIdentityAndRevisionCannotBeRebound() {
        val p=producer();assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),p.observe("receiver-1",clock,o(0).copy(source=InputSource.VEHICLE),missing))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),p.prepare(empty(),empty().copy(identity=identity.copy(profileId=ProfileId("other")))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),p.prepare(empty(),empty().copy(revision=9)))
    }
    @Test fun unavailableRulesDoNotInventProductDefaults() {
        val p=producer{ContractResult.Failure(ContractError.POLICY_UNRESOLVED)};val samples=listOf(o(0),o(1000));record(p,samples)
        assertEquals(ContractResult.Failure(ContractError.POLICY_UNRESOLVED),p.prepare(empty(),state(samples)))
    }
    @Test fun rawClockRegressionAndBootSwitchCannotCompletePreviousStop() {
        val p=producer();val initial=listOf(o(0),o(1000),o(1500,true),o(2500,true));record(p,initial,true)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),p.observe("receiver-1",clock,o(2400,true),area(o(2400,true),true)))
        val exit=o(3500);ok(p.observe("receiver-1",clock.copy(bootId="new-boot"),exit,area(exit,false)))
        assertTrue(ok(p.prepare(empty(),state(initial).copy(anchor=exit))).departures.isEmpty())
    }
    @Test fun alreadyPreparedExitSurvivesForegroundPauseWithoutReinventingIt() {
        val p=producer();val samples=listOf(o(0),o(1000),o(1500,true),o(2500,true),o(3500));record(p,samples,true)
        val next=state(samples);val first=ok(p.prepare(empty(),next));assertEquals(1,first.departures.size);p.pause()
        assertEquals(first,ok(p.prepare(empty(),next)))
    }
    @Test fun departureOrderUsesOriginalAnchorInsteadOfElapsedAcrossReboots() {
        val p=producer();val first=listOf(o(0),o(1000),o(1500,true),o(2500,true),o(3500),o(4500));record(p,first,true)
        val second=listOf(o(0,epoch=200000),o(1000,epoch=201000),o(1250,true,201250),o(2250,true,202250),o(2500,epoch=202500));record(p,second,true,clock.copy(domain="receiver-2",bootId="next-physical-boot"))
        val batch=ok(p.prepare(empty(),state(first+second)))
        assertEquals(listOf(103500L,202500L),batch.departures.map{it.departure.to.receivedAt.value})
    }
    @Test fun realReducerOutboxAndNewDepartureKeepTheirActualTripOrder() {
        for(ack in listOf(false,true)) {
            val p=producer();var number=0;val reducer=ObservedDrivingReducer(RecordingPolicy(1500,1500,0,500,0.1,0.5)){"drive-${++number}"}
            var current=empty();var batch=RestObservationBatch(emptyList(),emptyList())
            for(sample in listOf(o(0),o(1000),o(1500,true),o(2500,true),o(3500))) {
                ok(p.observe("receiver-1",clock,sample,area(sample,sample.gear==Observation.Available(Gear.PARK))))
                val next=ok(reducer.observe(current,"receiver-1",DrivingFrame(sample,Observation.Available(ZoneId.of("Asia/Seoul"))),RecordingClock(sample.receivedElapsedMs,sample.receivedAt),ContractResult.Success(DrivingIncomeRules(rules.ruleVersion,500000,GameMoney(1000)))))
                batch=ok(p.prepare(current,next));current=next
                if(ack&&sample.receivedElapsedMs==2500L)current=current.copy(revision=current.revision+1,outbox=emptyList()) // 실제 writer ack 후 다음 snapshot을 모사한다.
            }
            assertEquals(if(ack)listOf("drive-2")else listOf("drive-1","drive-2"),batch.contexts.map{it.trip.id})
            assertEquals("drive-2",batch.departures.single().driveId);assertEquals(current.trip,batch.contexts.last().trip)
            assertTrue(batch.contexts.last().segments.isEmpty());assertEquals(current.trip!!.departure,batch.departures.single().departure.to)
        }
    }
    @Test fun failedStorageAttemptCanPrepareANewRawSegmentFromTheSameActualPrevious() {
        val p=producer();val initial=listOf(o(0),o(1000));record(p,initial);val before=state(initial);ok(p.prepare(empty(),before))
        ok(p.observe("receiver-1",clock,o(1500),missing));ok(p.prepare(before,state(listOf(o(1000),o(1500)),revision=2,prior=before.trip!!.segments)))
        ok(p.observe("receiver-1",clock,o(2000),missing));val replacement=state(listOf(o(1000),o(2000)),revision=2,prior=before.trip.segments)
        val actual=ok(p.prepare(before,replacement)).contexts.single();assertEquals(replacement.trip,actual.trip)
        assertEquals(listOf(1000L,2000L),actual.segments.map{it.span.to.receivedElapsedMs})
    }
    @Test fun publishedListsCannotMutateCachedWholeContext() {
        val p=producer();val samples=listOf(o(0),o(1000));record(p,samples);val next=state(samples);val batch=ok(p.prepare(empty(),next))
        assertThrows(UnsupportedOperationException::class.java){(batch.contexts as MutableList).clear()}
        assertThrows(UnsupportedOperationException::class.java){(batch.contexts.single().trip.segments as MutableList).clear()}
        assertThrows(UnsupportedOperationException::class.java){(batch.contexts.single().segments as MutableList).clear()}
        assertEquals(batch,ok(p.prepare(empty(),next)))
    }
    @Test fun staleOrForeignAreaProofDoesNotChangeKnownDrivingIntoRest() {
        for(foreign in listOf(false,true)) {
            val p=producer();val samples=listOf(o(0),o(1000),o(1500,true),o(2500,true),o(3500))
            for(sample in samples) {
                val actual=area(sample,sample.gear==Observation.Available(Gear.PARK)).value
                val invalid=if(foreign)actual.copy(source=InputSource.VEHICLE)else actual.copy(elapsedMs=0,utc=EpochMillis(100000))
                ok(p.observe("receiver-1",clock,sample,Observation.Available(invalid)))
            }
            val next=state(samples);val batch=ok(p.prepare(empty(),next));assertEquals(next.trip,batch.contexts.single().trip);assertTrue(batch.departures.isEmpty())
        }
    }

    @Test fun newReceiverWithSameActualClockCannotCompleteTheEarlierParkingProof() {
        val p=producer();val before=listOf(o(0),o(1000),o(1500,true),o(2500,true));record(p,before,true)
        val prior=state(before);ok(p.prepare(empty(),prior))
        val exit=o(3500);ok(p.observe("receiver-2",clock,exit,area(exit,false)))
        val context=state(listOf(exit),id="new-receiver-drive",revision=2).copy(receiver="receiver-2",outbox=listOf(prior.trip!!))
        assertTrue(ok(p.prepare(prior,context)).departures.isEmpty())
    }

    @Test fun recorderRecoveryGapCannotCountAsPriorContinuousDriving() {
        for(freshDrive in listOf(false,true)) {
            val p=producer();val samples=if(freshDrive)listOf(o(0),o(1000),o(2000),o(2500,true),o(3500,true),o(4500)) else listOf(o(0),o(1000),o(1500,true),o(2500,true),o(3500))
            record(p,samples,true);val initial=state(samples);val original=initial.trip!!;val known=original.segments.drop(1)
            val next=initial.copy(trip=original.copy(segments=known,meters=known.fold(BigDecimal.ZERO){a,s->a+s.meters},gaps=listOf(DrivingGap(DrivingGapReason.RECOVERY,RecordingClock(0,EpochMillis(100000)),RecordingClock(1000,EpochMillis(101000)),o(1000)))))
            val batch=ok(p.prepare(empty(),next));assertEquals(next.trip,batch.contexts.single().trip)
            if(freshDrive) {
                val proof=batch.departures.single();assertEquals(1000L,proof.driving.sumOf{it.to.receivedElapsedMs-it.from.receivedElapsedMs});assertEquals(o(1000),proof.driving.first().from)
            } else assertTrue(batch.departures.isEmpty())
        }
    }

    @Test fun uncommittedQualifiedExitCannotBeRetiredByAnotherProposedDrive() {
        val p=producer();val samples=listOf(o(0),o(1000),o(1500,true),o(2500,true),o(3500));record(p,samples,true)
        val proposed=state(samples);val original=ok(p.prepare(empty(),proposed));assertEquals(1,original.departures.size)
        val later=o(4500);ok(p.observe("receiver-1",clock,later,missing))
        val other=state(listOf(later),id="replacement-after-rollback")
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),p.prepare(empty(),other))
        p.pause();assertEquals(original,ok(p.prepare(empty(),proposed)))
    }

    @Test fun missingAreaDoesNotBlockAReplacementAfterStorageRollback() {
        val p=producer();val samples=listOf(o(0),o(1000),o(1500,true),o(2500,true),o(3500));record(p,samples)
        ok(p.prepare(empty(),state(samples)))
        val later=o(4500);ok(p.observe("receiver-1",clock,later,missing));val other=state(listOf(later),id="replacement-after-rollback")
        assertEquals(other.trip,ok(p.prepare(empty(),other)).contexts.single().trip)
    }

    @Test fun uncommittedDrivingFrameIsNotCountedInAReplacementProof() {
        val p=producer();val initial=listOf(o(0),o(1000));record(p,initial,true);val before=state(initial);ok(p.prepare(empty(),before))
        ok(p.observe("receiver-1",clock,o(1500),area(o(1500),false)))
        ok(p.prepare(before,state(listOf(o(1000),o(1500)),revision=2,prior=before.trip!!.segments)))
        val rest=listOf(o(2000),o(2500,true),o(3500,true),o(4500));record(p,rest,true)
        val next=state(listOf(o(1000))+rest,revision=2,prior=before.trip.segments)
        val d=ok(p.prepare(before,next)).departures.single()
        assertEquals(listOf(1000L,2000L),d.driving.map{it.to.receivedElapsedMs})
    }

    @Test fun duplicateCurrentFrameOnNewReceiverBreaksEvidenceWithoutRewritingItsClock() {
        val p=producer();val before=listOf(o(0),o(1000),o(1500,true),o(2500,true));record(p,before,true)
        val prior=state(before);val original=ok(p.prepare(empty(),prior))
        ok(p.observe("receiver-2",clock,before.last(),area(before.last(),true)))
        val same=prior.copy(receiver="receiver-2",revision=2);assertEquals(original.contexts,ok(p.prepare(prior,same)).contexts)
        val exit=o(3500);ok(p.observe("receiver-2",clock,exit,area(exit,false)))
        val next=state(listOf(exit),id="new-receiver-drive",revision=3).copy(receiver="receiver-2",outbox=listOf(prior.trip!!))
        assertTrue(ok(p.prepare(same,next)).departures.isEmpty())
    }

    @Test fun rawCacheLimitIsExplicitAndPauseAllowsFreshSamples() {
        val p=producer()
        for(t in 0L until 32768L)ok(p.observe("receiver-1",clock,o(t),missing))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),p.observe("receiver-1",clock,o(32768),missing))
        p.pause();ok(p.observe("receiver-2",clock,o(40000),missing))
        assertEquals(clock,ok(p.prepare(empty(),state(listOf(o(40000))))).contexts.single().departureClock)
    }

    @Test fun actualPreviousAcknowledgementRetiresPreparedExitOnlyAfterItsDriveLeavesTheOutbox() {
        val p=producer();var number=0;val reducer=ObservedDrivingReducer(RecordingPolicy(1500,1500,0,500,0.1,0.5)){"drive-${++number}"}
        var current=empty();var prepared=0
        for(sample in listOf(o(0),o(1000),o(1500,true),o(2500,true),o(3500),o(4500),o(5000,true),o(6000,true))) {
            ok(p.observe("receiver-1",clock,sample,area(sample,sample.gear==Observation.Available(Gear.PARK))))
            val next=ok(reducer.observe(current,"receiver-1",DrivingFrame(sample,Observation.Available(ZoneId.of("Asia/Seoul"))),RecordingClock(sample.receivedElapsedMs,sample.receivedAt),ContractResult.Success(DrivingIncomeRules(rules.ruleVersion,500000,GameMoney(1000)))))
            prepared+=ok(p.prepare(current,next)).departures.size;current=next
        }
        assertTrue(prepared>0);assertEquals(listOf("drive-1","drive-2"),current.outbox.map{it.id})
        val acknowledged=current.copy(revision=current.revision+1,outbox=emptyList())
        val drained=ok(p.prepare(acknowledged,acknowledged));assertTrue(drained.contexts.isEmpty());assertTrue(drained.departures.isEmpty())
    }

    @Test fun anOldCachedFrameCannotMoveANewReceiverClockBackwards() {
        val p=producer();record(p,listOf(o(0),o(1000)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),p.observe("receiver-2",clock,o(0),missing))
    }

    @Test fun missingParkingAreaPreservesOriginalDrivingAndExistingGrant() {
        val p=producer();val samples=listOf(o(0),o(1000),o(1500,true),o(2500,true),o(3500),o(4500))
        for(s in samples)ok(p.observe("receiver-1",clock,s,if(s.receivedElapsedMs==1500L)missing else area(s,s.gear==Observation.Available(Gear.PARK))))
        val next=state(samples);val batch=ok(p.prepare(empty(),next));val c=batch.contexts.single();assertEquals(next.trip,c.trip)
        val d=batch.departures.single();val event=RestAnchoredDeparture(d.requestId,c.trip.segments.indexOfFirst{it.to==d.departure.to},RestDeparturePacket(RestProof(identity,1,d.driveId,d.driving,d.parked,d.departure),d.rules,d.policy,d.clock))
        val original=rules.copy(ruleVersion=RuleVersion("old-grant"),bonusDurationMs=10000,bonusPercent=200)
        val grant=RestActiveGrant(0,original);val plan=ok(RestTimeline.plan(c,listOf(event),RestBonusState(4000,0),grant))
        assertEquals(RestOutcome.UNCONFIRMED,plan.operations.filterIsInstance<RestTimelineOperation.Depart>().single().result.outcome)
        assertEquals(grant,plan.activeGrant);assertEquals(2000L,plan.boostedMs);assertEquals(RestBonusState(2000,1),plan.after)
        assertEquals(listOf(200,200),plan.boosts.map{it.percent})
    }

}
