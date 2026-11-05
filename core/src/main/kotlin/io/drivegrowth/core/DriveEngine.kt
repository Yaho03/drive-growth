package io.drivegrowth.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

typealias InputSource = io.drivegrowth.core.contract.InputSource
enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }
enum class DriveState { READY, RECORDING, WAITING_FOR_PARK, DATA_UNAVAILABLE, SETTLED }
data class Sample(val source: InputSource, val elapsedMs: Long, val epochMs: Long, val speedMps: Double?, val gear: Gear, val uxRestricted: Boolean?, val valid: Boolean = true)
data class ActiveTrip(val id: String, val source: InputSource, val startedEpochMs: Long, val validDurationMs: Long = 0, val distanceMeters: Double = 0.0, val harshEvents: Int = 0)
data class TripResult(val id: String, val source: InputSource, val startedEpochMs: Long, val endedEpochMs: Long, val validDurationMs: Long, val distanceMeters: Double, val harshEvents: Int, val score: Int?, val xp: Int, val interrupted: Boolean = false)
data class Snapshot(val state: DriveState = DriveState.READY, val totalXp: Int = 0, val trips: List<TripResult> = emptyList(), val active: ActiveTrip? = null, val lastSample: Sample? = null, val parkSinceMs: Long? = null, val lastEventBand: Int = 0) {
    val level: Int get() = 1 + totalXp / 20
    val firstBadgeUnlocked: Boolean get() = level >= 2
}
class DriveEngine(initial: Snapshot = Snapshot()) {
    var snapshot = initial
        private set
    fun ingest(sample: Sample): Snapshot {
        val old = snapshot
        val previous = old.lastSample
        if (previous != null && sample.elapsedMs <= previous.elapsedMs) return old
        if (!usable(sample) || (old.active != null && sample.source != old.active.source)) {
            snapshot = old.copy(state = DriveState.DATA_UNAVAILABLE, lastSample = sample, parkSinceMs = null, lastEventBand = 0)
            return snapshot
        }
        var active = old.active
        if (active == null && sample.gear in listOf(Gear.DRIVE, Gear.REVERSE) && abs(sample.speedMps!!) >= 0.5) {
            active = ActiveTrip("${sample.source}-${sample.epochMs}-${sample.elapsedMs}", sample.source, sample.epochMs)
        }
        var band = 0
        if (active != null && old.active != null && previous != null && usable(previous) && previous.source == sample.source) {
            val dt = sample.elapsedMs - previous.elapsedMs
            if (dt in 1..5000) {
                val acceleration = (abs(sample.speedMps!!) - abs(previous.speedMps!!)) / (dt / 1000.0)
                band = when { acceleration >= 3.0 -> 1; acceleration <= -3.0 -> -1; else -> 0 }
                active = active.copy(
                    validDurationMs = active.validDurationMs + dt,
                    distanceMeters = active.distanceMeters + (abs(previous.speedMps) + abs(sample.speedMps)) / 2 * dt / 1000.0,
                    harshEvents = active.harshEvents + if (band != 0 && band != old.lastEventBand) 1 else 0
                )
            }
        }
        val parked = sample.gear == Gear.PARK && abs(sample.speedMps!!) <= 0.1 && sample.uxRestricted == false
        val continuous = previous != null && usable(previous) && previous.source == sample.source &&
            sample.elapsedMs - previous.elapsedMs in 1..5000
        val parkSince = if (parked) {
            if (continuous) old.parkSinceMs ?: sample.elapsedMs else sample.elapsedMs
        } else null
        snapshot = old.copy(
            state = when {
                active != null && parked -> DriveState.WAITING_FOR_PARK
                active != null -> DriveState.RECORDING
                old.state == DriveState.SETTLED && parked -> DriveState.SETTLED
                else -> DriveState.READY
            }, active = active, lastSample = sample, parkSinceMs = parkSince, lastEventBand = band
        )
        if (active != null && parkSince != null && sample.elapsedMs - parkSince >= 3000) {
            val eligible = active.validDurationMs >= 60_000
            val paidToday = old.trips.filter { day(it.endedEpochMs) == day(sample.epochMs) }.sumOf { it.xp }
            val xp = if (eligible) (20 - paidToday).coerceIn(0, 20) else 0
            val result = TripResult(active.id, active.source, active.startedEpochMs, sample.epochMs,
                active.validDurationMs, active.distanceMeters, active.harshEvents,
                if (eligible) (100 - active.harshEvents * 5).coerceIn(0, 100) else null, xp)
            val alreadyPaid = old.trips.any { it.id == active.id }
            snapshot = snapshot.copy(state = DriveState.SETTLED, active = null,
                totalXp = old.totalXp + if (alreadyPaid) 0 else xp,
                trips = if (alreadyPaid) old.trips else old.trips + result)
        }
        return snapshot
    }

    fun recoverInterrupted(): Snapshot {
        val active = snapshot.active ?: run {
            snapshot = snapshot.copy(lastSample = null, parkSinceMs = null, lastEventBand = 0)
            return snapshot
        }
        val result = TripResult(active.id, active.source, active.startedEpochMs,
            snapshot.lastSample?.epochMs ?: active.startedEpochMs, active.validDurationMs,
            active.distanceMeters, active.harshEvents, null, 0, interrupted = true)
        snapshot = snapshot.copy(state = DriveState.DATA_UNAVAILABLE, active = null,
            trips = if (snapshot.trips.any { it.id == active.id }) snapshot.trips else snapshot.trips + result,
            lastSample = null, parkSinceMs = null, lastEventBand = 0)
        return snapshot
    }

    private fun usable(sample: Sample): Boolean = sample.valid && sample.speedMps != null &&
        sample.speedMps.isFinite() && abs(sample.speedMps) <= 100.0 && sample.gear != Gear.UNKNOWN && sample.uxRestricted != null

    private fun day(epochMs: Long) = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
}
