package io.drivegrowth.app.host.rest

import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** 독립 JVM 반례. 명시 SIMULATION/축소 정책이며 저장 실패는 actual previous를 유지해 모사한다. SQLite 실행이 아니다. */
class ForegroundRestProducerRetryRegressionTest {
    private val identity=GameIdentity(ProfileId("independent-producer"),InputSource.SIMULATION)
    private val rules=RestRules(RuleVersion("review-rest"),1000,1000,2000,150)
    private val policy=RestEvidencePolicy(1500,1500,0,0.5,0.1)
    private val clock=RestClockDomain("actual-elapsed-fixture","physical-boot-fixture")
    private val receiver="one-receiver"
    private fun observation(at:Long,park:Boolean)=VehicleObservation(identity.source,at,EpochMillis(100000+at),
        Observation.Available(if(park)0.0 else 10.0),Observation.Available(if(park)Gear.PARK else Gear.DRIVE),Observation.Available(!park))
    private fun place(o:VehicleObservation)=Observation.Available(RestAreaProof("verified-review-fixture",RestPlace.SERVICE_AREA,
        ContentVersion("review-fixture"),"https://example.test/fixture","fixture-only","2026-11-11",identity.source,
        o.gear==Observation.Available(Gear.PARK),o.receivedElapsedMs,o.receivedAt))
    private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    private fun scenario(retryAt:Long?):Pair<RestObservationBatch,RecordingState> {
        val producer=ForegroundRestObservationProducer(identity,{ContractResult.Success(rules)},policy,RestPricingPolicy.CHRONOLOGICAL_PREFIX_RATIONAL_V1)
        var sequence=0
        val reducer=ObservedDrivingReducer(RecordingPolicy(1500,1500,0,500,0.1,0.5)){"original-drive-${++sequence}"}
        var current=RecordingState(identity,0,null,null,null,null,null,emptyList())
        var batch=RestObservationBatch(emptyList(),emptyList())
        for(at in listOf(0L,1000L,1500L,2500L,3500L)) {
            val raw=observation(at,at==1500L||at==2500L)
            val frame=DrivingFrame(raw,Observation.Available(ZoneId.of("Asia/Seoul")))
            val recordingClock=RecordingClock(raw.receivedElapsedMs,raw.receivedAt)
            ok(producer.observe(receiver,clock,raw,place(raw)))
            val next=ok(reducer.observe(current,receiver,frame,recordingClock,
                ContractResult.Success(DrivingIncomeRules(rules.ruleVersion,500000,GameMoney(1000)))))
            batch=ok(producer.prepare(current,next))
            if(at==retryAt) {
                // capture/save가 실패했다고 모사: actual current를 바꾸지 않고 같은 raw/frame/clock을 먼저 재시도한다.
                val proposed=batch
                ok(producer.observe(receiver,clock,raw,place(raw)))
                val sameNext=ok(reducer.observe(current,receiver,frame,recordingClock,
                    ContractResult.Success(DrivingIncomeRules(rules.ruleVersion,500000,GameMoney(1000)))))
                assertEquals("same original reducer state",next,sameNext)
                batch=ok(producer.prepare(current,sameNext))
                assertEquals("same whole context on retry",proposed,batch)
            }
            current=next // 여기서만 실제 저장 성공 snapshot을 다음 actual previous로 공급한다.
        }
        assertEquals(listOf("original-drive-1","original-drive-2"),batch.contexts.map{it.trip.id})
        assertEquals(current.outbox+listOfNotNull(current.trip),batch.contexts.map{it.trip})
        return batch to current
    }
    private fun requireQualifiedExit(retryAt:Long?) {
        val (batch,_)=scenario(retryAt)
        assertEquals("same raw successful retry must preserve one original qualified departure",1,batch.departures.size)
        val departure=batch.departures.single()
        assertEquals("original-drive-2",departure.driveId)
        assertEquals(3500L,departure.departure.to.receivedElapsedMs)
        val stop=ok(RestEvidenceValidator(policy).stop(RestProof(identity,1,departure.driveId,departure.driving,departure.parked,departure.departure)))
        assertEquals(Observation.Available(1000L),stop.drivenMs)
        assertEquals(Observation.Available(1000L),stop.parkedMs)
        assertEquals(RestOutcome.ACCEPTED,RestBonus(rules).classify(stop))
    }
    @Test fun noStorageFailureRetainsQualifiedDeparture(){requireQualifiedExit(null)}
    @Test fun sameOriginalDrivingFrameRetryPreservesLaterRestProof(){requireQualifiedExit(1000)}
    @Test fun sameOriginalFirstParkFrameRetryPreservesLaterRestProof(){requireQualifiedExit(1500)}
    @Test fun sameOriginalParkDurationFrameRetryPreservesLaterRestProof(){requireQualifiedExit(2500)}
}
