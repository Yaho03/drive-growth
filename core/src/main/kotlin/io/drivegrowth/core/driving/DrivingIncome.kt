package io.drivegrowth.core.driving

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.ObservedDistance
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.TransactionKind
import java.math.BigInteger
import java.time.LocalDate

/**
 * 기본 주행 수익 규칙. 1km당 금액은 #24에서 미정이라 기본값을 두지 않는다.
 *
 * @param dailyCapMeters 하루 보상 반영 거리 상한. 기본 주행 수익에만 적용한다.
 */
data class DrivingIncomeRules(
    val ruleVersion: RuleVersion,
    val dailyCapMeters: Long,
    val incomePerKm: GameMoney,
) {
    init { require(dailyCapMeters > 0) { "하루 상한은 양수" } }
}

/** 출발일 기준 하루에 이미 보상에 반영한 거리. */
data class DrivingDay(val day: LocalDate, val rewardedMeters: Long) {
    init { require(rewardedMeters >= 0) { "반영 거리는 0 이상" } }
}

/**
 * 확인된 주행 한 건. 관측이 끊긴 구간은 호출자가 빼고 넘긴다. 예상 경로 거리를 넣지 않는다.
 *
 * @param departedOn 출발한 날. 자정을 넘긴 주행도 이 날로 계산한다. 시간대 결정은 호출자가 맡는다.
 */
data class ConfirmedDrive(
    val driveId: String,
    val departedOn: LocalDate,
    val distance: ObservedDistance,
) {
    init { require(driveId.isNotBlank()) { "주행 ID 필요" } }
}

/**
 * @param recordedMeters 기록 원본 거리. 상한과 무관하게 그대로 보존한다.
 * @param rewardedMeters 이번 주행에서 보상에 반영한 거리.
 * @param day 이번 주행을 반영한 뒤의 하루 상태. 지급과 같은 트랜잭션에 저장해야 한다.
 * @param change 기본 주행 수익 지급. 금액이 0이면 null이다.
 */
data class DrivingSettlement(
    val drive: ConfirmedDrive,
    val recordedMeters: Long,
    val rewardedMeters: Long,
    val amount: GameMoney,
    val day: DrivingDay,
    val change: GameChange?,
)

/**
 * 기본 주행 수익을 계산한다. 하루 상한은 기본 주행 수익에만 적용하고 탁송·영업 보상과 무관하다.
 * 같은 주행은 같은 요청 ID(`drive/<driveId>`)라 원장에서 한 번만 지급된다. 호출자는 지급과 하루 상태를 한 트랜잭션으로 저장한다.
 * 금액은 주행 한 건마다 내림한다.
 */
class DrivingIncome(private val rules: DrivingIncomeRules) {
    /**
     * @param day 출발일의 저장된 하루 상태. 없으면 null이다. 다른 날의 상태를 넘기면 INVALID_INPUT이다.
     */
    fun settle(day: DrivingDay?, drive: ConfirmedDrive): ContractResult<DrivingSettlement> {
        if (day != null && day.day != drive.departedOn) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val base = day ?: DrivingDay(drive.departedOn, 0)
        val recorded = drive.distance.meters
        val remaining = (rules.dailyCapMeters - base.rewardedMeters).coerceAtLeast(0)
        val rewarded = minOf(recorded, remaining)
        val amount = amountFor(rewarded) ?: return ContractResult.Failure(ContractError.INVALID_INPUT)
        val change = if (amount.units == 0L) null else GameChange(
            requestId = RequestId("drive/${drive.driveId}"),
            kind = TransactionKind.DRIVING,
            source = drive.distance.source,
            ruleVersion = rules.ruleVersion,
            money = MoneyChange(MoneyDirection.CREDIT, amount),
        )
        return ContractResult.Success(
            DrivingSettlement(drive, recorded, rewarded, amount, base.copy(rewardedMeters = base.rewardedMeters + rewarded), change),
        )
    }

    private fun amountFor(meters: Long): GameMoney? {
        val units = BigInteger.valueOf(meters) * BigInteger.valueOf(rules.incomePerKm.units) / BigInteger.valueOf(1_000)
        return if (units.bitLength() < Long.SIZE_BITS) GameMoney(units.toLong()) else null
    }
}
