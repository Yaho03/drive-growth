package io.drivegrowth.core.driving

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DrivingIncomeTest {
    private val rules = DrivingFixtures.trialRules // 10 / km, 하루 500km
    private val income = DrivingIncome(rules)
    private val today = LocalDate.of(2026, 11, 7)

    private fun drive(meters: Long, id: String = "d1", on: LocalDate = today, source: InputSource = InputSource.SIMULATION) =
        ConfirmedDrive(id, on, ObservedDistance(meters, source))

    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value

    @Test fun `확인된 거리만큼 기본 주행 수익을 주행 거래로 지급한다`() {
        val settled = ok(income.settle(null, drive(30_000)))
        assertEquals(30_000L, settled.rewardedMeters)
        assertEquals(GameMoney(300), settled.amount)
        val change = requireNotNull(settled.change)
        assertEquals(RequestId("drive/d1"), change.requestId)
        assertEquals(TransactionKind.DRIVING, change.kind)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(300)), change.money)
        assertNull(change.vehicle)
        assertEquals(DrivingDay(today, 30_000), settled.day)
    }

    @Test fun `499km 뒤 2km 주행은 1km만 반영하고 원본 거리는 보존한다`() {
        val settled = ok(income.settle(DrivingDay(today, 499_000), drive(2_000)))
        assertEquals(2_000L, settled.recordedMeters)
        assertEquals(1_000L, settled.rewardedMeters)
        assertEquals(GameMoney(10), settled.amount)
        assertEquals(DrivingDay(today, 500_000), settled.day)
    }

    @Test fun `상한에 닿은 뒤에는 0원이라 지급 거래가 없고 원본 거리는 남는다`() {
        val settled = ok(income.settle(DrivingDay(today, 500_000), drive(5_000)))
        assertEquals(5_000L, settled.recordedMeters)
        assertEquals(0L, settled.rewardedMeters)
        assertNull(settled.change)
        assertEquals(DrivingDay(today, 500_000), settled.day)
    }

    @Test fun `한 번에 501km를 달려도 500km까지만 반영한다`() {
        val settled = ok(income.settle(null, drive(501_000)))
        assertEquals(500_000L, settled.rewardedMeters)
        assertEquals(GameMoney(5_000), settled.amount)
    }

    @Test fun `출발한 날로 계산하며 다른 날의 상태를 넘기면 거절한다`() {
        val yesterday = today.minusDays(1)
        val settled = ok(income.settle(DrivingDay(yesterday, 499_000), drive(3_000, on = yesterday)))
        assertEquals(1_000L, settled.rewardedMeters)
        assertEquals(yesterday, settled.day.day)
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), income.settle(DrivingDay(yesterday, 0), drive(3_000)))
        assertEquals(DrivingDay(today, 3_000), ok(income.settle(null, drive(3_000))).day)
    }

    @Test fun `1km 미만 금액은 주행 한 건마다 내림한다`() {
        assertEquals(GameMoney(9), ok(income.settle(null, drive(999))).amount)
        assertNull(ok(income.settle(null, drive(99))).change)
    }

    @Test fun `같은 주행은 같은 요청 ID와 금액이라 원장에서 한 번만 지급된다`() {
        assertEquals(ok(income.settle(null, drive(30_000))).change, ok(income.settle(null, drive(30_000))).change)
        assertNotEquals(ok(income.settle(null, drive(30_000))).change?.requestId, ok(income.settle(null, drive(30_000, id = "d2"))).change?.requestId)
    }

    @Test fun `입력 출처를 지급 거래에 보존한다`() {
        assertEquals(InputSource.VEHICLE, ok(income.settle(null, drive(1_000, source = InputSource.VEHICLE))).change?.source)
    }

    @Test fun `금액이 범위를 넘으면 예외 없이 거절한다`() {
        val huge = DrivingIncome(rules.copy(incomePerKm = GameMoney(Long.MAX_VALUE)))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), huge.settle(null, drive(2_000)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `0 이하 상한은 거절한다`() {
        rules.copy(dailyCapMeters = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `빈 주행 ID는 거절한다`() {
        drive(1_000, id = " ")
    }
}
