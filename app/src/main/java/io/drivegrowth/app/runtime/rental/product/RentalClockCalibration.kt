package io.drivegrowth.app.runtime.rental.product

import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.core.contract.*

/** epoch는 보정값이다. 원시 wall clock과의 허용 차이는 기술 정책으로 명시 공급한다. */
class RentalClockCalibration(private val maxSkewMs: Long) {
    init { require(maxSkewMs >= 0) }
    private var anchor: RentalClock? = null
    fun restore(original: RentalClock) {
        require(valid(original))
        anchor = original
    }
    fun project(raw: RentalClock): ContractResult<RentalClock> {
        if (!valid(raw)) return unavailable()
        val original = anchor ?: raw
        if (raw.bootId != original.bootId) return unavailable()
        return try {
            val epoch = Math.addExact(original.epoch.value, Math.subtractExact(raw.elapsedMs, original.elapsedMs))
            val skew = Math.subtractExact(raw.epoch.value, epoch)
            if (epoch < 0 || skew < -maxSkewMs || skew > maxSkewMs) return unavailable()
            if (anchor == null) anchor = original
            ContractResult.Success(raw.copy(epoch = EpochMillis(epoch)))
        } catch (_: ArithmeticException) { unavailable() }
    }
    private fun valid(clock: RentalClock) = clock.bootId.isNotBlank() && clock.epoch.value >= 0 && clock.elapsedMs >= 0
    private fun unavailable() = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
}
