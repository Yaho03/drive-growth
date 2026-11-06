package io.drivegrowth.app.vehicle

import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleInteraction
import io.drivegrowth.core.contract.VehicleObservation
import io.drivegrowth.core.driving.InteractionPolicy
import io.drivegrowth.core.driving.VehicleInteractionGate

/** 시험값이다. 기존 전경 어댑터의 값을 그대로 옮겼으며 실차 근거로 확정한 기준이 아니다. */
internal val TRIAL_INTERACTION_POLICY = InteractionPolicy(
    maxStationarySpeedMps = 0.1,
    requiredParkMs = 3000,
    maxSignalAgeMs = 2500,
    maxSampleGapMs = 2500,
)

/** 앱이 전면에 있는 동안의 관측으로 조작 허용 상태를 화면에 전달한다. */
internal class ForegroundInteraction(policy: InteractionPolicy = TRIAL_INTERACTION_POLICY) {
    private val gate = VehicleInteractionGate(policy)

    fun accept(observation: VehicleObservation) = gate.accept(observation)

    fun reset() = gate.reset()

    fun state(now: Long): Observation<VehicleInteraction> = gate.state(now)
}
