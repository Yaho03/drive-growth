package io.drivegrowth.core.driving

/** 관측이 끊긴 원인이다. 판단할 수 없으면 추측하지 않고 UNKNOWN으로 남긴다. */
enum class ObservationGapKind { APP_HIDDEN, DEVICE_SLEEP, SIGNAL_INTERRUPTED, UNKNOWN }

data class ObservationGap(val kind: ObservationGapKind, val durationMs: Long)

/** elapsed는 절전 시간을 포함하고 uptime은 포함하지 않는다. 둘의 차이로 기기 절전을 구분한다. */
data class ObservationClock(val elapsedMs: Long, val uptimeMs: Long)

/** 관측 공백을 분류한다. 공백을 주행 거리로 채우지 않으며 기록과 판정은 다른 클래스가 맡는다. */
class ObservationCoverage(private val maxTickGapMs: Long, private val minSleepMs: Long) {
    private var lastTick: ObservationClock? = null
    private var stoppedAt: ObservationClock? = null

    init {
        require(maxTickGapMs > 0 && minSleepMs > 0)
    }

    /** 수신 주기마다 호출한다. 공백이 없으면 null이다. */
    fun tick(now: ObservationClock): ObservationGap? {
        val previous = lastTick
        val resumedFrom = stoppedAt
        lastTick = now
        stoppedAt = null
        if (resumedFrom != null) return gap(resumedFrom, now, ObservationGapKind.APP_HIDDEN)
        if (previous == null) return null
        if (now.elapsedMs - previous.elapsedMs in 0..maxTickGapMs) return null
        return gap(previous, now, ObservationGapKind.SIGNAL_INTERRUPTED)
    }

    /** 관측을 의도적으로 멈출 때 호출한다. */
    fun stop(now: ObservationClock) {
        lastTick = null
        stoppedAt = now
    }

    private fun gap(from: ObservationClock, to: ObservationClock, awakeKind: ObservationGapKind): ObservationGap {
        val duration = to.elapsedMs - from.elapsedMs
        if (duration < 0) return ObservationGap(ObservationGapKind.UNKNOWN, 0)
        val slept = duration - (to.uptimeMs - from.uptimeMs)
        return ObservationGap(if (slept >= minSleepMs) ObservationGapKind.DEVICE_SLEEP else awakeKind, duration)
    }
}
