package io.drivegrowth.app.data

import io.drivegrowth.core.contract.*
import java.math.BigDecimal
import java.util.Collections

/** 수락된 시간 경로다. INACTIVE 자체는 물리 주차나 앱 이탈 증거가 아니다. */
enum class RentalIncomeKind { DRIVING, PARKED, INACTIVE, UNOBSERVED }

data class RentalIncomePolicy(val version: RuleVersion, val normalMillis: Long,
    val capMillis: Long, val reducedMultiplier: BigDecimal) {
    init {
        require(normalMillis >= 0 && capMillis >= normalMillis)
        require(reducedMultiplier >= BigDecimal.ZERO && reducedMultiplier <= BigDecimal.ONE)
    }
}

data class RentalIncomePeriod(val from: EpochMillis, val through: EpochMillis,
    val kind: RentalIncomeKind, val origin: EpochMillis?, val normalMillis: Long,
    val reducedMillis: Long, val cappedMillis: Long, val weightedMillis: BigDecimal,
    val policy: RentalIncomePolicy?) {
    init {
        require(from.value >= 0 && through.value > from.value)
        val duration = through.value - from.value
        require(normalMillis >= 0 && reducedMillis >= 0 && cappedMillis >= 0)
        if (kind == RentalIncomeKind.UNOBSERVED) {
            require(origin == null && policy == null && normalMillis == 0L && reducedMillis == 0L && cappedMillis == 0L)
            require(weightedMillis.signum() == 0)
        } else {
            require(normalMillis.toBigInteger() + reducedMillis.toBigInteger() + cappedMillis.toBigInteger() == duration.toBigInteger())
            if (kind == RentalIncomeKind.DRIVING) {
                require(origin == null && policy == null && normalMillis == duration && reducedMillis == 0L && cappedMillis == 0L)
                require(weightedMillis.compareTo(duration.toBigDecimal()) == 0)
            } else {
                require(origin != null && origin.value in 0..from.value && policy != null)
                // 원본에 저장된 정책으로 형식/구간 일치만 확인한다. 현재 정책으로 과거를 보정하지 않는다.
                val a = from.value - origin.value; val b = through.value - origin.value
                require(normalMillis == b.coerceAtMost(policy.normalMillis) - a.coerceAtMost(policy.normalMillis))
                require(reducedMillis == b.coerceIn(policy.normalMillis, policy.capMillis) - a.coerceIn(policy.normalMillis, policy.capMillis))
                require(weightedMillis.compareTo(normalMillis.toBigDecimal() + reducedMillis.toBigDecimal() * policy.reducedMultiplier) == 0)
            }
        }
    }
}

/** 최초 정산에 연결된 시간 설명. 금액은 기존 Settlement에서만 읽는다. */
class RentalResultBasis internal constructor(val rentalId: RequestId, val settlementId: SettlementId,
    val profileId: ProfileId, val source: InputSource, val ruleVersion: RuleVersion,
    val start: EpochMillis, val end: EpochMillis, periods: List<RentalIncomePeriod>) {
    val periods: List<RentalIncomePeriod> = Collections.unmodifiableList(ArrayList(periods))
    init {
        require(start.value >= 0 && end.value > start.value && periods.isNotEmpty())
        require(periods.first().from == start && periods.last().through == end)
        require(periods.zipWithNext().all { (a, b) -> a.through == b.from })
    }
    val normalMillis: Long get() = periods.sumOf { it.normalMillis }
    val reducedMillis: Long get() = periods.sumOf { it.reducedMillis }
    val cappedMillis: Long get() = periods.sumOf { it.cappedMillis }
    val unobservedMillis: Long get() = periods.sumOf {
        if (it.kind == RentalIncomeKind.UNOBSERVED) it.through.value - it.from.value else 0L
    }
    val weightedMillis: BigDecimal get() = periods.fold(BigDecimal.ZERO) { sum, p -> sum + p.weightedMillis }
}
