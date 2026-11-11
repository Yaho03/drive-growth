package io.drivegrowth.app.simulation

import io.drivegrowth.app.host.driving.DrivingFrame
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import java.time.ZoneId

/** 시연 논리 시각. OS/sensor 시각이 아니며 고정 단계는 경제 계산이 아닌 모의 입력이다. */
data class SimulationTimeline(val elapsedMs: Long = 0, val stage: Int = 0) {
    init { require(elapsedMs in 0..(Long.MAX_VALUE - 1_794_279_600_000L - 3_605_000L) && stage in 0..65) }
    init { require(elapsedMs >= when { stage == 0 -> 0; stage <= 61 -> 1000 + (stage - 1) * 60_000L; else -> 3_601_000L + (stage - 61) * 1000L }) }
    val controlsAllowed get() = stage == 0 || stage == 65
    val arrival get() = stage == 62
    val clock get() = RentalClock("unified-simulation-v1", EpochMillis(1_794_279_600_000L + elapsedMs), elapsedMs)
    fun begin(): SimulationTimeline? = if (controlsAllowed) copy(elapsedMs = Math.addExact(elapsedMs, 1000), stage = 1) else null
    fun next(): SimulationTimeline? = if (stage in 1..64) copy(
        elapsedMs = Math.addExact(elapsedMs, if (stage <= 60) 60_000 else 1000), stage = stage + 1) else null
    fun frame(): DrivingFrame = DrivingFrame(VehicleObservation(InputSource.SIMULATION, elapsedMs, clock.epoch,
        Observation.Available(if (stage in 1..61) 10.0 else 0.0),
        Observation.Available(if (stage in 1..61) Gear.DRIVE else Gear.PARK), Observation.Available(stage in 1..61)),
        Observation.Available(ZoneId.of("UTC")))
}
