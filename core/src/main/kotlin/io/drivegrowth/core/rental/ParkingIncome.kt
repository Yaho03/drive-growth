package io.drivegrowth.core.rental

import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.RuleVersion
import java.math.BigDecimal

/** 기존 종료 영업 시험값의 단일 정의. 저장자는 수락 당시 값을 원본 근거에 보존한다. */
object ParkingIncomePolicy {
    val version = RuleVersion("parking-income-v1")
    const val normalMillis = 30 * 60_000L
    const val capMillis = 8 * 60 * 60_000L
    val reducedMultiplier: BigDecimal = BigDecimal("0.30")
}

data class ParkingIncomeWindow(val fullMillis: Long, val reducedMillis: Long, val accountedThrough: EpochMillis)

/** 마지막 주행 종료를 기준으로 자른다. 호출자는 수익과 커서를 같은 거래에서 확정한다. */
fun parkingIncomeWindow(parkedAt: EpochMillis, accountedThrough: EpochMillis, now: EpochMillis): ParkingIncomeWindow {
    require(parkedAt.value >= 0 && accountedThrough.value >= parkedAt.value && now.value >= 0)
    val from = accountedThrough.value - parkedAt.value
    val end = maxOf(accountedThrough.value, now.value)
    val to = end - parkedAt.value
    val normalEnd = ParkingIncomePolicy.normalMillis
    val cap = ParkingIncomePolicy.capMillis
    return ParkingIncomeWindow(
        to.coerceAtMost(normalEnd) - from.coerceAtMost(normalEnd),
        to.coerceIn(normalEnd, cap) - from.coerceIn(normalEnd, cap),
        EpochMillis(end),
    )
}

/** 정상 시간으로 환산한 정확한 밀리초. 여러 번 복귀해도 구간별 반올림 손실이 없다. */
fun ParkingIncomeWindow.equivalentMillis() =
    fullMillis.toBigDecimal() + reducedMillis.toBigDecimal().multiply(ParkingIncomePolicy.reducedMultiplier)
