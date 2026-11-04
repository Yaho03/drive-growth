package io.drivegrowth.app.vehicle

import io.drivegrowth.core.Gear
import io.drivegrowth.core.InputSource
import io.drivegrowth.core.Sample
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleInteraction
import kotlin.math.abs

/** #36의 조작 허용 관측을 연결하기 전, 기존 전경 표본으로만 동작하는 어댑터. */
internal class ForegroundInteraction {
    private var latest: Sample? = null
    private var parkedSince: Long? = null

    fun accept(sample: Sample) {
        val previous = latest
        latest = sample
        val parked = sample.valid && sample.source == InputSource.VEHICLE &&
            sample.gear == Gear.PARK && sample.uxRestricted == false &&
            sample.speedMps?.let { it.isFinite() && abs(it) <= 0.1 } == true
        parkedSince = if (!parked) null else {
            if (previous != null && sample.elapsedMs - previous.elapsedMs in 1..2500) {
                parkedSince ?: sample.elapsedMs
            } else sample.elapsedMs
        }
    }

    fun reset() {
        latest = null
        parkedSince = null
    }

    fun state(now: Long): Observation<VehicleInteraction> {
        val sample = latest ?: return Observation.Missing(MissingReason.NO_DATA)
        if (now - sample.elapsedMs !in 0..2500) return Observation.Missing(MissingReason.STALE)
        if (!sample.valid || sample.speedMps == null || sample.uxRestricted == null ||
            sample.gear == Gear.UNKNOWN || sample.source != InputSource.VEHICLE
        ) return Observation.Missing(MissingReason.NO_DATA)
        val since = parkedSince
        val allowed = since != null && sample.elapsedMs - since >= 3000
        return Observation.Available(if (allowed) VehicleInteraction.ALLOWED else VehicleInteraction.RESTRICTED)
    }
}
