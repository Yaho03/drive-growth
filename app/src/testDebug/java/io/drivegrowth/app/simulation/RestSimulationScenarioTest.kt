package io.drivegrowth.app.simulation

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.ObservedDrivingReducer
import io.drivegrowth.app.host.driving.RecordingClock
import io.drivegrowth.app.host.driving.RecordingState
import io.drivegrowth.app.host.rest.ForegroundRestObservationProducer
import io.drivegrowth.app.host.rest.RestEvidenceValidator
import io.drivegrowth.app.host.rest.RestProof
import io.drivegrowth.app.data.RestDepartureInput
import io.drivegrowth.app.data.RestPricingPolicy
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.junit.Assert.*
import org.junit.Test

class RestSimulationScenarioTest {
    @Test fun displayPhasesFollowTheExistingFixtureAndKeepTheExitPhaseForFinalParking() {
        val rest = RestRules(RuleVersion("test-rest"), 3_600_000, 300_000, 1_200_000, 150)
        val phases = mutableListOf<RestSimulationScenario.Phase>()
        for ((_, area) in RestSimulationScenario.frames(rest)) {
            val phase = RestSimulationScenario.phaseFor(phases.lastOrNull(), area)
            if (phase != phases.lastOrNull()) phases += phase
        }
        assertEquals(listOf(RestSimulationScenario.Phase.DRIVE, RestSimulationScenario.Phase.REST,
            RestSimulationScenario.Phase.DEPARTURE), phases)
    }

    @Test fun simulatedTimelineYieldsExactlyOneQualifiedDeparture() {
        val rest = RestRules(RuleVersion("test-rest"), 3_600_000, 300_000, 1_200_000, 150)
        val driving = DrivingIncomeRules(RuleVersion("test-driving"), 500_000, GameMoney(100))
        val id = GameIdentity(ProfileId("test-simulation"), InputSource.SIMULATION)
        val producer = ForegroundRestObservationProducer(id, { ContractResult.Success(rest) },
            RestSimulationScenario.evidencePolicy, RestPricingPolicy.CHRONOLOGICAL_PREFIX_RATIONAL_V1)
        var ids = 0
        val reducer = ObservedDrivingReducer(RestSimulationScenario.recordingPolicy) { "drive-${++ids}" }
        var state = RecordingState(id, 0, null, null, null, null, null, emptyList())
        val departures = linkedMapOf<RequestId, RestDepartureInput>()
        var completed = 0
        val validator = RestEvidenceValidator(RestSimulationScenario.evidencePolicy)
        for ((frame, area) in RestSimulationScenario.frames(rest)) {
            val raw = frame.observation
            assertEquals(InputSource.SIMULATION, raw.source)
            ok(producer.observe("receiver", RestSimulationScenario.clockDomain, raw, area))
            val next = ok(reducer.observe(state, "receiver", frame,
                RecordingClock(raw.receivedElapsedMs, raw.receivedAt), ContractResult.Success(driving)))
            assertTrue(next.trip?.gaps.orEmpty().isEmpty())
            val batch = ok(producer.prepare(state, next))
            for (departure in batch.departures) {
                // 아직 정산되지 않은 출발 원문은 다음 capture에도 같은 ID로 전달된다.
                departures.put(departure.requestId, departure)?.let { assertEquals(it, departure) }
            }
            completed += next.outbox.size
            // 호스트 순수 시험에서만 ACK 경계를 모의한다. 실제 금융·저장은 별도 장치 시험이다.
            state = if (next.outbox.isEmpty()) next else next.copy(revision = next.revision + 1, outbox = emptyList())
        }
        assertEquals(2, ids)
        assertEquals(2, completed)
        val departure = departures.values.single()
        val stop = ok(validator.stop(RestProof(departure.identity, 0, departure.driveId,
            departure.driving, departure.parked, departure.departure)))
        assertEquals(Observation.Available(3_600_000L), stop.drivenMs)
        assertEquals(Observation.Available(300_000L), stop.parkedMs)
        assertEquals(RestOutcome.ACCEPTED, RestBonus(rest).classify(stop))
        assertTrue(RestSimulationScenario.label.contains("SIMULATION"))
        assertTrue(RestSimulationScenario.label.contains("시험 장소"))
    }

    private fun <T> ok(value: ContractResult<T>): T {
        assertTrue(value.toString(), value is ContractResult.Success)
        return (value as ContractResult.Success).value
    }
}
