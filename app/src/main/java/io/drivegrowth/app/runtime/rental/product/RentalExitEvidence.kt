package io.drivegrowth.app.runtime.rental.product

import io.drivegrowth.app.host.driving.RecordingPolicy
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import kotlin.math.abs

enum class RentalExitMode { DRIVING, KNOWN_PARKED }

/** 실제 stop에서만 호출한다. Missing 원점은 Driving 선택의 근거가 아니다. */
fun rentalExitMode(configurationChange: Boolean, source: InputSource, since: RentalClock,
    latest: VehicleObservation?, actualTripOpen: Boolean, lastDriveEnd: RentalClock?,
    policy: RecordingPolicy, rebasedClock: Boolean = false): RentalExitMode? {
    if (configurationChange || latest == null || latest.source != source ||
        since.elapsedMs < latest.receivedElapsedMs || since.elapsedMs - latest.receivedElapsedMs > policy.maxAgeMs)
        return null
    val speed = (latest.speedMps as? Observation.Available)?.value ?: return null
    val gear = (latest.gear as? Observation.Available)?.value ?: return null
    if (!speed.isFinite()) return null
    if (actualTripOpen && gear in setOf(Gear.DRIVE, Gear.REVERSE) && abs(speed) > policy.movingMps)
        return RentalExitMode.DRIVING
    if (gear == Gear.PARK && abs(speed) <= policy.stationaryMps && lastDriveEnd != null &&
        lastDriveEnd.bootId == since.bootId && lastDriveEnd.elapsedMs <= since.elapsedMs &&
        lastDriveEnd.epoch.value <= since.epoch.value &&
        (since.elapsedMs - lastDriveEnd.elapsedMs == since.epoch.value - lastDriveEnd.epoch.value ||
            (rebasedClock && since.isForwardWallJumpFrom(lastDriveEnd))))
        return RentalExitMode.KNOWN_PARKED
    return null
}
