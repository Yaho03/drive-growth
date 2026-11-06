package io.drivegrowth.core.driving

import io.drivegrowth.core.Gear
import io.drivegrowth.core.Sample
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleObservation

/** Android Automotive 원값을 공통 관측값으로 바꾼다. 누락을 0이나 제한 해제로 치환하지 않는다. */
object VehicleSignals {
    private const val GEAR_NEUTRAL = 1
    private const val GEAR_REVERSE = 2
    private const val GEAR_PARK = 4
    private const val GEAR_DRIVE = 8

    fun speed(available: Boolean, metersPerSecond: Float?): Observation<Double> {
        val value = metersPerSecond?.toDouble()
        return if (available && value != null && value.isFinite()) Observation.Available(value)
        else Observation.Missing(MissingReason.NO_DATA)
    }

    fun gear(available: Boolean, raw: Int?): Observation<Gear> {
        val mapped = when (raw) {
            GEAR_PARK -> Gear.PARK
            GEAR_DRIVE -> Gear.DRIVE
            GEAR_REVERSE -> Gear.REVERSE
            GEAR_NEUTRAL -> Gear.NEUTRAL
            else -> null
        }
        return if (available && mapped != null) Observation.Available(mapped)
        else Observation.Missing(MissingReason.NO_DATA)
    }

    fun uxRestricted(requiresDistractionOptimization: Boolean, activeRestrictions: Int): Observation<Boolean> =
        Observation.Available(requiresDistractionOptimization || activeRestrictions != 0)
}

/** 기존 주행 기록 엔진에 넘기는 변환이다. 하나라도 누락되면 무효 표본이 되어 구간에 포함되지 않는다. */
fun VehicleObservation.toSample(): Sample {
    val speed = (speedMps as? Observation.Available)?.value
    val gearValue = (gear as? Observation.Available)?.value
    val restricted = (uxRestricted as? Observation.Available)?.value
    return Sample(
        source = source,
        elapsedMs = receivedElapsedMs,
        epochMs = receivedAt.value,
        speedMps = speed,
        gear = gearValue ?: Gear.UNKNOWN,
        uxRestricted = restricted,
        valid = speed != null && gearValue != null && restricted != null,
    )
}
