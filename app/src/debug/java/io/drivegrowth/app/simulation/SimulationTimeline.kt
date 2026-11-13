package io.drivegrowth.app.simulation

import io.drivegrowth.app.host.driving.DrivingFrame
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import java.time.ZoneId

/** 시연 논리 시각. OS/sensor 시각이 아니며 고정 단계는 경제 계산이 아닌 모의 입력이다. */
data class SimulationTimeline(val elapsedMs: Long = 0, val stage: Int = 0, val restRun: Boolean = false) {
    private val terminalStage get() = if (restRun) 72 else 65
    init { require(elapsedMs in 0..(Long.MAX_VALUE - EPOCH_BASE - 4_007_000L) && stage in 0..terminalStage) }
    init { require((!restRun || stage != 0) && elapsedMs >= offset(stage)) }

    val terminal get() = stage == terminalStage
    val controlsAllowed get() = stage == 0 || terminal
    val arrival get() = !restRun && stage == 62
    val inRestArea get() = restRun && stage in 62..67
    val runStartMs get() = elapsedMs - offset(stage)
    val clock get() = RentalClock("unified-simulation-v1", EpochMillis(EPOCH_BASE + elapsedMs), elapsedMs)

    fun begin(): SimulationTimeline? = if (controlsAllowed) SimulationTimeline(Math.addExact(elapsedMs, 1_000), 1) else null
    fun beginRest(): SimulationTimeline? = begin()?.copy(restRun = true)

    fun next(): SimulationTimeline? = if (stage in 1 until terminalStage) copy(
        elapsedMs = Math.addExact(elapsedMs, offset(stage + 1) - offset(stage)), stage = stage + 1) else null

    private fun offset(step: Int): Long = when {
        step == 0 -> 0
        step <= 61 -> 1_000 + (step - 1) * 60_000L
        !restRun -> 3_601_000L + (step - 61) * 1_000L
        step <= 67 -> 3_602_000L + (step - 62) * 60_000L
        step == 68 -> 3_903_000L
        step == 69 -> 3_963_000L
        step == 70 -> 4_003_000L
        step == 71 -> 4_004_000L
        else -> 4_007_000L
    }

    fun frame(): DrivingFrame {
        val moving = stage in 1..61 || restRun && stage in 68..70
        return DrivingFrame(VehicleObservation(InputSource.SIMULATION, elapsedMs, clock.epoch,
            Observation.Available(if (moving) 10.0 else 0.0),
            Observation.Available(if (moving) Gear.DRIVE else Gear.PARK), Observation.Available(moving)),
            Observation.Available(ZoneId.of("UTC")))
    }

    private companion object { const val EPOCH_BASE = 1_794_279_600_000L }
}
