package io.drivegrowth.core.driving

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservationCoverageTest {
    private fun coverage() = ObservationCoverage(maxTickGapMs = 2500, minSleepMs = 1000)

    @Test
    fun `정상 주기에는 공백이 없다`() {
        val coverage = coverage()
        assertNull(coverage.tick(ObservationClock(0, 0)))
        assertNull(coverage.tick(ObservationClock(1000, 1000)))
        assertNull(coverage.tick(ObservationClock(3500, 3500)))
    }

    @Test
    fun `깨어 있는 상태의 긴 공백은 신호 중단이다`() {
        val coverage = coverage()
        coverage.tick(ObservationClock(0, 0))
        assertEquals(ObservationGap(ObservationGapKind.SIGNAL_INTERRUPTED, 10_000), coverage.tick(ObservationClock(10_000, 10_000)))
    }

    @Test
    fun `기기 절전은 uptime이 멈춘 구간으로 구분한다`() {
        val coverage = coverage()
        coverage.tick(ObservationClock(0, 0))
        assertEquals(ObservationGap(ObservationGapKind.DEVICE_SLEEP, 60_000), coverage.tick(ObservationClock(60_000, 100)))
    }

    @Test
    fun `앱을 숨긴 구간과 숨긴 동안의 절전을 구분한다`() {
        val hidden = coverage()
        hidden.tick(ObservationClock(0, 0))
        hidden.stop(ObservationClock(1000, 1000))
        assertEquals(ObservationGap(ObservationGapKind.APP_HIDDEN, 4000), hidden.tick(ObservationClock(5000, 5000)))
        assertNull(hidden.tick(ObservationClock(6000, 6000)))

        val slept = coverage()
        slept.stop(ObservationClock(1000, 1000))
        assertEquals(ObservationGap(ObservationGapKind.DEVICE_SLEEP, 9000), slept.tick(ObservationClock(10_000, 1500)))
    }

    @Test
    fun `시각이 되돌아가면 원인을 추측하지 않는다`() {
        val coverage = coverage()
        coverage.stop(ObservationClock(5000, 5000))
        assertEquals(ObservationGap(ObservationGapKind.UNKNOWN, 0), coverage.tick(ObservationClock(1000, 1000)))
    }
}
