package io.drivegrowth.app.host.rental

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.app.runtime.rental.RentalMotion
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.DrivingIncomeRules
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class SimulationRestRentalContinuityTest {
    private val boot = "unified-simulation-v1"
    private val policy = RecordingPolicy(60_000, 60_000, 1000, 3000, 0.1, 0.5)

    @Test fun `일반 주차에서 휴식 출발로 전환해도 대여 관측의 원래 경계를 유지한다`() {
        val identity = GameIdentity(ProfileId("same-profile"), InputSource.SIMULATION)
        val reducer = ObservedDrivingReducer(policy) { "rest-drive" }
        val rules = ContractResult.Success(DrivingIncomeRules(RuleVersion("trial"), 500_000, GameMoney(10)))
        val park = VehicleObservation(identity.source, 14_420_000, EpochMillis(1_794_294_020_000),
            Observation.Available(0.0), Observation.Available(Gear.PARK), Observation.Available(false))
        val drive = park.copy(receivedElapsedMs = park.receivedElapsedMs + 1_000,
            receivedAt = EpochMillis(park.receivedAt.value + 1_000), speedMps = Observation.Available(10.0),
            gear = Observation.Available(Gear.DRIVE), uxRestricted = Observation.Available(true))
        val parkClock = RentalClock(boot, park.receivedAt, park.receivedElapsedMs)
        val driveClock = RentalClock(boot, drive.receivedAt, drive.receivedElapsedMs)
        val state = RecordingState(identity, 264, "ordinary", null, park,
            RecordingClock(park.receivedElapsedMs, park.receivedAt), null, emptyList())
        val next = (reducer.observe(state, "unified-simulation-rest", DrivingFrame(drive, Observation.Available(ZoneId.of("UTC"))),
            RecordingClock(drive.receivedElapsedMs, drive.receivedAt), rules) as ContractResult.Success).value
        val before = SessionRentalProductSource.AcceptedFrame(parkClock,
            Observation.Available(RentalMotion.Parked(EpochMillis(park.receivedAt.value - 3_000))),
            state.receiver, state.revision, null)

        assertEquals(1_000L, before.deltaTo(driveClock, next, park, boot, policy.maxGapMs))
        assertEquals(null, before.deltaTo(driveClock, next, park, null, policy.maxGapMs))
        assertEquals(null, before.deltaTo(driveClock, next, park, "other", policy.maxGapMs))
        assertEquals(null, before.deltaTo(driveClock, next.copy(revision = next.revision + 1), park, boot, policy.maxGapMs))
        assertEquals(null, before.deltaTo(driveClock, next, park, boot, 999))
        assertEquals(null, before.deltaTo(driveClock, next, park.copy(uxRestricted = Observation.Available(true)), boot, policy.maxGapMs))
        assertEquals(null, before.deltaTo(driveClock, next.copy(identity = identity.copy(source = InputSource.VEHICLE)),
            park, boot, policy.maxGapMs))
        assertEquals(1_000L, before.copy(receiver = next.receiver).deltaTo(driveClock, next, park, null, policy.maxGapMs))
    }
}
