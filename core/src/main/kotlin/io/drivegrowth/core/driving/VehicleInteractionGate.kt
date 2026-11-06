package io.drivegrowth.core.driving

import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleInteraction
import io.drivegrowth.core.contract.VehicleObservation

/** 판정 기준은 호출자가 명시한다. 이 클래스는 제품 수치를 정하지 않는다. */
data class InteractionPolicy(
    val maxStationarySpeedMps: Double,
    val requiredParkMs: Long,
    val maxSignalAgeMs: Long,
    val maxSampleGapMs: Long,
) {
    init {
        require(maxStationarySpeedMps.isFinite() && maxStationarySpeedMps >= 0)
        require(requiredParkMs >= 0 && maxSignalAgeMs >= 0 && maxSampleGapMs > 0)
    }
}

/**
 * 게임 조작 허용 여부를 정한다. 차량 출처의 새 표본이 P·정차·UX 허용을 연속으로 유지할 때만 ALLOWED다.
 * 역순·중복 표본은 버린다. 최신 값을 되돌리지 않으므로 수신이 멈추면 STALE이 된다.
 */
class VehicleInteractionGate(private val policy: InteractionPolicy) {
    private var latest: VehicleObservation? = null
    private var parkedSinceMs: Long? = null

    fun accept(observation: VehicleObservation) {
        val previous = latest
        if (previous != null && observation.receivedElapsedMs <= previous.receivedElapsedMs) return
        latest = observation
        val continuous = previous != null &&
            observation.receivedElapsedMs - previous.receivedElapsedMs <= policy.maxSampleGapMs
        parkedSinceMs = when {
            !observation.isParkCandidate(policy.maxStationarySpeedMps) -> null
            continuous -> parkedSinceMs ?: observation.receivedElapsedMs
            else -> observation.receivedElapsedMs
        }
    }

    fun reset() {
        latest = null
        parkedSinceMs = null
    }

    fun state(nowElapsedMs: Long): Observation<VehicleInteraction> {
        val observation = latest ?: return Observation.Missing(MissingReason.NO_DATA)
        if (nowElapsedMs - observation.receivedElapsedMs !in 0..policy.maxSignalAgeMs) {
            return Observation.Missing(MissingReason.STALE)
        }
        if (observation.source != InputSource.VEHICLE) return Observation.Missing(MissingReason.NO_DATA)
        missingReason(observation)?.let { return Observation.Missing(it) }
        val since = parkedSinceMs
        val allowed = since != null && observation.receivedElapsedMs - since >= policy.requiredParkMs
        return Observation.Available(if (allowed) VehicleInteraction.ALLOWED else VehicleInteraction.RESTRICTED)
    }

    /** 사용자가 조치할 수 있는 원인을 먼저 전달한다. */
    private fun missingReason(observation: VehicleObservation): MissingReason? {
        val reasons = listOf(observation.speedMps, observation.gear, observation.uxRestricted)
            .filterIsInstance<Observation.Missing>().map { it.reason }
        return REASON_PRIORITY.firstOrNull { it in reasons }
    }

    private companion object {
        val REASON_PRIORITY = listOf(
            MissingReason.PERMISSION_DENIED,
            MissingReason.UNSUPPORTED,
            MissingReason.STALE,
            MissingReason.NO_DATA,
        )
    }
}
