package io.drivegrowth.app.ui.driving

import io.drivegrowth.app.vehicle.TRIAL_INTERACTION_POLICY
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleObservation
import kotlin.math.abs

/** 제한 화면 표현에만 쓰는 이동 근거. 게임 조작 허용은 기존 gate가 결정한다. */
internal class DrivingRestrictionEvidence {
    private var latest: VehicleObservation? = null
    private var observedAt = 0L

    fun accept(observation: VehicleObservation, observedAt: Long = observation.receivedElapsedMs) {
        val previous = latest
        if (previous?.source == observation.source && previous.receivedElapsedMs >= observation.receivedElapsedMs) return
        latest = observation
        this.observedAt = observedAt
    }

    fun reset() {
        latest = null
    }

    fun confirmsMotion(source: InputSource, now: Long): Boolean {
        val current = latest ?: return false
        if (current.source != source || now - observedAt !in 0..TRIAL_INTERACTION_POLICY.maxSignalAgeMs) return false
        val speed = (current.speedMps as? Observation.Available)?.value ?: return false
        val gear = (current.gear as? Observation.Available)?.value ?: return false
        return current.uxRestricted is Observation.Available && gear != Gear.PARK && gear != Gear.UNKNOWN &&
            speed.isFinite() && abs(speed) > TRIAL_INTERACTION_POLICY.maxStationarySpeedMps
    }
}

internal fun drivingSceneAsset(hour: Int, simulationLoop: Boolean, animatorsEnabled: Boolean): String {
    val time = if (hour >= 19 || hour < 6) "night" else "day"
    val part = if (simulationLoop && animatorsEnabled) "loop" else "still"
    return "pixel/driving/rrr-$time-$part.webp"
}
