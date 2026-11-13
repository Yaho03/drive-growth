package io.drivegrowth.app.ui.results

import org.junit.Assert.*
import org.junit.Test

class ResultsRentalDurationTest {
    @Test fun positiveSubsecondAndSubminuteTimesNeverBecomeZero() {
        for ((millis, expected) in listOf(0L to "0초", 1L to "0.001초", 10L to "0.01초", 999L to "0.999초",
            1001L to "1.001초", 59999L to "59.999초", 60001L to "1분 0.001초", 3600001L to "1시간 0.001초")) {
            assertEquals(expected, rentalDuration(millis))
        }
    }
    @Test fun wholeHoursAndHugeDurationsRemainExactWithoutOverflow() {
        assertEquals("2시간", rentalDuration(7200000))
        assertEquals("2,562,047,788,015시간 12분 55.807초", rentalDuration(Long.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { rentalDuration(-1) }
    }
}
