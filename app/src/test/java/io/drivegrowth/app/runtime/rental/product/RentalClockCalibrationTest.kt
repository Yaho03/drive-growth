package io.drivegrowth.app.runtime.rental.product

import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class RentalClockCalibrationTest {
    private fun at(epoch: Long, elapsed: Long, boot: String = "actual-boot") = RentalClock(boot, EpochMillis(epoch), elapsed)
    private val unavailable = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)

    @Test fun firstActualPairAndSubsequentSamplingNoiseUseOneCalibration() {
        val clock = RentalClockCalibration(10)
        assertEquals(ContractResult.Success(at(1000, 10)), clock.project(at(1000, 10)))
        assertEquals(ContractResult.Success(at(1010, 20)), clock.project(at(1012, 20)))
    }
    @Test fun persistedSchedulePairRestoresCalibrationWithoutUsingCurrentWallAsOrigin() {
        val clock = RentalClockCalibration(10)
        clock.restore(at(5000, 100)) // nextAt가 미래여도 보정 쌍이다.
        assertEquals(ContractResult.Success(at(4950, 50)), clock.project(at(4952, 50)))
        assertEquals(ContractResult.Success(at(5100, 200)), clock.project(at(5101, 200)))
    }
    @Test fun clockJumpDoesNotResetAnchor() {
        val clock = RentalClockCalibration(10)
        clock.restore(at(1000, 10))
        assertEquals(unavailable, clock.project(at(1200, 20)))
        assertEquals(ContractResult.Success(at(1010, 20)), clock.project(at(1010, 20)))
    }
    @Test fun rebootMissingBootNegativeAndOverflowAreExplicitFailures() {
        val clock = RentalClockCalibration(10)
        clock.restore(at(1000, 10))
        for (raw in listOf(at(1010, 20, "other-boot"), at(1010, 20, ""), at(-1, 20), at(1010, -1)))
            assertEquals(unavailable, clock.project(raw))
        val overflow = RentalClockCalibration(10)
        overflow.restore(at(Long.MAX_VALUE, 0))
        assertEquals(unavailable, overflow.project(at(Long.MAX_VALUE, 1)))
    }
}
