package io.drivegrowth.app.host.rest

import io.drivegrowth.app.host.driving.ObservedTrip
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections

/** 검증된 원 segment 안의 반열린 배율 구간. grant/clock/place 권위와 소모는 호출자가 검증한다. */
data class RestPricingWindow(
    val segmentIndex: Int,
    val fromOffsetMs: Long,
    val untilOffsetMs: Long,
    val bonusPercent: Int,
)

/** 저장 adapter가 그대로 보존할 0 이상 기약분수 거리(m). 소수 근사값이 아니다. */
data class RestDistanceFraction(val numerator: BigInteger, val denominator: BigInteger) {
    init { require(numerator.signum() >= 0 && denominator.signum() > 0 && numerator.gcd(denominator) == BigInteger.ONE) }
}
data class RestPricedSlice(
    val window: RestPricingWindow,
    val rawMeters: RestDistanceFraction,
    val rewardedMeters: RestDistanceFraction,
)
data class RestDrivingPrice(
    val policyVersion: String,
    val settlement: DrivingSettlement,
    val slices: List<RestPricedSlice>,
    /** 배율 적용 window 시간 합. 전체 eligible 운전 소모시간/남은 버프 상태를 대신하지 않는다. */
    val boostedMs: Long,
)

/**
 * 원 drive의 정수 보상거리 prefix와 정확한 부분 적분으로 최종 금액을 한 번 내린다.
 * 실제 입금/Rest 소모/NEW 판정은 하지 않는다. 동일 atomic writer가 이 계획을 다시 검증한다.
 */
object RestDrivingPricing {
    const val POLICY_VERSION = "chronological-prefix-rational-v1"

    fun price(trip: ObservedTrip, day: DrivingDay?, windows: List<RestPricingWindow>): ContractResult<RestDrivingPrice> {
        val rules = when (val original = trip.rules) {
            is ContractResult.Failure -> return original
            is ContractResult.Success -> original.value
        }
        return try {
            require(rules.dailyCapMeters == 500000L)
            val drive = trip.confirmed(trip.source) ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            require(day == null || day.day == drive.departedOn)
            val segments = trip.segments.toList()
            val selected = windows.toList()
            var rawTotal = BigDecimal.ZERO
            val prefixes = ArrayList<Rational>(segments.size)
            var prefix = Rational.ZERO
            for (s in segments) {
                require(s.from.source == trip.source && s.to.source == trip.source)
                require(s.from.receivedElapsedMs >= 0 && s.to.receivedElapsedMs > s.from.receivedElapsedMs)
                require(s.from.receivedAt.value >= 0 && s.to.receivedAt.value >= s.from.receivedAt.value)
                val a = speed(s.from); val b = speed(s.to)
                val duration = s.to.receivedElapsedMs - s.from.receivedElapsedMs
                // 원 관측 writer와 같은 전체 segment 표현을 확인하고, 부분 구간만 유리수로 적분한다.
                val originalMeters = a.add(b).multiply(BigDecimal.valueOf(duration)).divide(BigDecimal(2000))
                require(originalMeters == s.meters)
                prefixes += prefix
                prefix += Rational.decimal(originalMeters)
                rawTotal += originalMeters
            }
            require(rawTotal.compareTo(trip.meters) == 0)
            val before = day ?: DrivingDay(drive.departedOn, 0)
            val rewarded = minOf(drive.distance.meters, (rules.dailyCapMeters - before.rewardedMeters).coerceAtLeast(0))
            val limit = Rational.integer(rewarded)
            var extra = Rational.ZERO
            var boostedMs = 0L
            var previous: RestPricingWindow? = null
            val slices = selected.map { window ->
                require(window.segmentIndex in segments.indices && window.bonusPercent > 0)
                val s = segments[window.segmentIndex]
                val duration = s.to.receivedElapsedMs - s.from.receivedElapsedMs
                require(window.fromOffsetMs >= 0 && window.untilOffsetMs > window.fromOffsetMs && window.untilOffsetMs <= duration)
                previous?.let { old ->
                    require(window.segmentIndex > old.segmentIndex ||
                        (window.segmentIndex == old.segmentIndex && window.fromOffsetMs >= old.untilOffsetMs))
                }
                require(driving(s.from) && driving(s.to))
                val a = Rational.decimal(speed(s.from)); val b = Rational.decimal(speed(s.to))
                val start = integral(a,b,duration,window.fromOffsetMs)
                val end = integral(a,b,duration,window.untilOffsetMs)
                val offset = prefixes[window.segmentIndex]
                val paid = (offset + end).min(limit) - (offset + start).min(limit)
                extra += paid * Rational.integer(window.bonusPercent.toLong() - 100)
                boostedMs = Math.addExact(boostedMs, window.untilOffsetMs - window.fromOffsetMs)
                previous = window
                RestPricedSlice(window,(end-start).distance(),paid.distance())
            }
            val amount = GameMoney(((limit * Rational.integer(100) + extra) *
                Rational.integer(rules.incomePerKm.units) / Rational.integer(100000)).floorLong())
            val change = if (amount.units == 0L) null else GameChange(
                RequestId("drive/${drive.driveId}"), TransactionKind.DRIVING, drive.distance.source,
                rules.ruleVersion, money = MoneyChange(MoneyDirection.CREDIT,amount))
            val settlement = DrivingSettlement(drive,drive.distance.meters,rewarded,amount,
                before.copy(rewardedMeters = Math.addExact(before.rewardedMeters,rewarded)),change)
            ContractResult.Success(RestDrivingPrice(POLICY_VERSION,settlement,
                Collections.unmodifiableList(slices),boostedMs))
        } catch (_: IllegalArgumentException) {
            ContractResult.Failure(ContractError.INVALID_INPUT)
        } catch (_: ArithmeticException) {
            ContractResult.Failure(ContractError.INVALID_INPUT)
        }
    }

    private fun speed(o: VehicleObservation): BigDecimal {
        val value = (o.speedMps as? Observation.Available)?.value
        require(value != null && value.isFinite() && kotlin.math.abs(value) <= 100)
        return BigDecimal.valueOf(kotlin.math.abs(value))
    }
    private fun driving(o: VehicleObservation): Boolean =
        ((o.gear as? Observation.Available)?.value.let { it == Gear.DRIVE || it == Gear.REVERSE }) &&
            o.uxRestricted is Observation.Available

    private fun integral(a: Rational, b: Rational, duration: Long, elapsed: Long): Rational {
        val x = Rational.integer(elapsed)
        return a*x/Rational.integer(1000) + (b-a)*x*x/(Rational.integer(2000)*Rational.integer(duration))
    }

    /** 중간 기울기는 음수일 수 있다. 외부 거리·금액으로 반환할 때는 음수를 거절한다. */
    private class Rational private constructor(val n: BigInteger, val d: BigInteger) {
        operator fun plus(other: Rational) = of(n*other.d+other.n*d,d*other.d)
        operator fun minus(other: Rational) = of(n*other.d-other.n*d,d*other.d)
        operator fun times(other: Rational) = of(n*other.n,d*other.d)
        operator fun div(other: Rational) = of(n*other.d,d*other.n)
        fun min(other: Rational) = if(n*other.d <= other.n*d) this else other
        fun distance() = RestDistanceFraction(n,d)
        fun floorLong(): Long {
            require(n.signum() >= 0)
            val units = n/d
            require(units.bitLength() < Long.SIZE_BITS)
            return units.toLong()
        }
        companion object {
            val ZERO = integer(0)
            fun integer(value: Long) = Rational(BigInteger.valueOf(value),BigInteger.ONE)
            fun decimal(value: BigDecimal): Rational = if(value.scale() >= 0)
                of(value.unscaledValue(),BigInteger.TEN.pow(value.scale()))
                else of(value.unscaledValue()*BigInteger.TEN.pow(-value.scale()),BigInteger.ONE)
            private fun of(n: BigInteger, d: BigInteger): Rational {
                require(d.signum() > 0)
                val gcd = n.gcd(d)
                return Rational(n/gcd,d/gcd)
            }
        }
    }
}
