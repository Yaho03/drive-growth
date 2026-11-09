package io.drivegrowth.core.contract

import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.*
import org.junit.Test

/** 순수 준비·과거 결과 대조 시험. 실제 원장/DB commit을 흉내 내지 않는다. */
class RepairContractTest {
    private val current get() = RepairContractFixtures.state()
    private fun command(state: RepairState = current) = RepairContractFixtures.command(state)
    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun fails(error: ContractError, result: ContractResult<*>) = assertEquals(ContractResult.Failure(error), result)

    @Test fun `수리비와 회복을 함께 준비하고 성장 취득 사고 누계를 보존한다`() {
        val state = current
        val result = ok(prepareRepairChange(command(state), state))
        assertEquals(GameMoney(945), result.balance)
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(55)), result.money)
        assertEquals(state.vehicle.copy(revision = Revision(4)), result.vehicle)
        assertEquals(VehicleActivity.IDLE, result.activity)
        assertEquals(3, result.accidentCount)
        assertEquals(VehicleActivity.NEEDS_REPAIR, state.activity)
        assertEquals(GameMoney(1000), state.balance)
    }

    @Test fun `확인된 무료 수리는 거래금액 null이고 누락 비용과 다르다`() {
        val state = RepairContractFixtures.state(modelPrice = 0)
        val result = ok(prepareRepairChange(command(state), state))
        assertNull(result.money)
        assertEquals(state.balance, result.balance)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), RepairContractFixtures.missingState)
    }

    @Test fun `유휴 대여 반납 활동은 수리할 수 없다`() {
        for (activity in listOf(VehicleActivity.IDLE, VehicleActivity.RENTED, VehicleActivity.RETURNING)) {
            val state = current.copy(activity = activity)
            fails(ContractError.STATE_CONFLICT, prepareRepairChange(command(), state))
        }
    }

    @Test fun `다른 개체와 처분한 개체는 조회 불가다`() {
        fails(ContractError.DATA_UNAVAILABLE, prepareRepairChange(command(), current.copy(vehicle = current.vehicle.copy(ownedVehicleId = OwnedVehicleId("other")))))
        for (lifecycle in listOf(VehicleLifecycle.SOLD, VehicleLifecycle.SCRAPPED, VehicleLifecycle.CONSUMED)) {
            fails(ContractError.DATA_UNAVAILABLE, prepareRepairChange(command(), current.copy(vehicle = current.vehicle.copy(lifecycle = lifecycle))))
        }
    }

    @Test fun `revision 사고 누계 비용 규칙 변경은 충돌이다`() {
        val base = command()
        val variants = listOf(
            base.copy(target = VehicleRef(base.target.ownedVehicleId, Revision(2))),
            base.copy(expectedAccidentCount = 2), base.copy(cost = GameMoney(54)),
            base.copy(contentVersion = ContentVersion("other")), base.copy(ruleVersion = RuleVersion("other")),
        )
        for (changed in variants) fails(ContractError.STATE_CONFLICT, prepareRepairChange(changed, current))
    }

    @Test fun `다른 출처 음수 사고와 revision overflow는 거절한다`() {
        fails(ContractError.INVALID_INPUT, prepareRepairChange(command().copy(source = InputSource.VEHICLE), current))
        fails(ContractError.INVALID_INPUT, prepareRepairChange(command().copy(expectedAccidentCount = -1), current))
        fails(ContractError.INVALID_INPUT, prepareRepairChange(command(), current.copy(accidentCount = -1)))
        val state = current.copy(vehicle = current.vehicle.copy(revision = Revision(Int.MAX_VALUE)))
        fails(ContractError.INVALID_INPUT, prepareRepairChange(command(state), state))
    }

    @Test fun `잔액 부족은 실패하고 정확한 잔액과 최대 잔액은 안전하게 차감한다`() {
        fails(ContractError.INSUFFICIENT_FUNDS, prepareRepairChange(command(), current.copy(balance = GameMoney(54))))
        assertEquals(GameMoney(0), ok(prepareRepairChange(command(), current.copy(balance = GameMoney(55)))).balance)
        assertEquals(GameMoney(Long.MAX_VALUE - 55), ok(prepareRepairChange(command(), current.copy(balance = GameMoney(Long.MAX_VALUE)))).balance)
    }

    @Test fun `기존 비용 산식을 사용하며 overflow는 명시 거절한다`() {
        assertEquals(GameMoney(55), current.repairCost)
        fails(ContractError.INVALID_INPUT, RepairContractFixtures.quote(Long.MAX_VALUE,
            io.drivegrowth.core.progression.VehicleCare(io.drivegrowth.core.progression.VehicleCareRules(repairCostPercent = Int.MAX_VALUE))))
        fails(ContractError.INVALID_INPUT, RepairContractFixtures.quote(-1))
    }

    @Test fun `조회 누락과 저장 읽기 실패는 성공이나 무료 수리가 아니다`() {
        for (error in listOf(ContractError.DATA_UNAVAILABLE, ContractError.STORAGE_FAILURE, ContractError.PERMISSION_DENIED)) {
            fails(error, prepareRepairChange(command()) { ContractResult.Failure(error) })
        }
        assertEquals(prepareRepairChange(command(), current), prepareRepairChange(command()) { ContractResult.Success(current) })
    }

    @Test fun `과거 결과는 현재 상태를 읽지 않고 당시 전체 값을 재생한다`() {
        val recorded = RepairContractFixtures.recordedExample()
        assertSame(recorded, ok(replayRepairChange(recorded.command, recorded)))
        assertEquals(GameMoney(945), recorded.balance)
        assertEquals(Revision(4), recorded.vehicle.revision)
        assertNull(ok(replayRepairChange(command(), null)))
    }

    @Test fun `요청 종류 전역 충돌은 저장 책임이고 수리 전체 입력 변경은 충돌이다`() {
        val recorded = RepairContractFixtures.recordedExample()
        val base = recorded.command
        val variants = listOf(base.copy(requestId = RequestId("other")), base.copy(source = InputSource.VEHICLE),
            base.copy(contentVersion = ContentVersion("other")), base.copy(ruleVersion = RuleVersion("other")),
            base.copy(target = VehicleRef(OwnedVehicleId("other"), base.target.revision)),
            base.copy(target = base.target.copy(revision = Revision(9))), base.copy(expectedAccidentCount = 4), base.copy(cost = GameMoney(0)))
        for (changed in variants) fails(ContractError.STATE_CONFLICT, replayRepairChange(changed, recorded))
    }

    @Test fun `재생 결과 DTO는 입력 상태와 독립적이고 최대 레벨 무료 취득을 보존한다`() {
        val state = current.copy(vehicle = current.vehicle.copy(level = 20, levelProgressPercent = 0,
            acquisitionCost = GameMoney(0)))
        val result = ok(prepareRepairChange(command(state), state))
        val changed = result.vehicle.copy(level = 1, revision = Revision(8))
        assertEquals(20, result.vehicle.level)
        assertEquals(GameMoney(0), result.vehicle.acquisitionCost)
        assertNotEquals(changed, result.vehicle)
        assertEquals(3, result.accidentCount)
    }
    @Test fun `저장된 결과의 금액 거래 연결 활동 revision 사고 누계 손상은 저장 오류다`() {
        val recorded = RepairContractFixtures.recordedExample()
        val variants = listOf(recorded.copy(activity = VehicleActivity.NEEDS_REPAIR),
            recorded.copy(accidentCount = 0), recorded.copy(vehicle = recorded.vehicle.copy(revision = Revision(3))),
            recorded.copy(vehicle = recorded.vehicle.copy(lifecycle = VehicleLifecycle.SOLD)),
            recorded.copy(transaction = recorded.transaction.copy(requestId = RequestId("other"))),
            recorded.copy(transaction = recorded.transaction.copy(kind = TransactionKind.RENTAL)),
            recorded.copy(transaction = recorded.transaction.copy(source = InputSource.VEHICLE)),
            recorded.copy(transaction = recorded.transaction.copy(ruleVersion = RuleVersion("other"))),
            recorded.copy(transaction = recorded.transaction.copy(money = null)))
        for (corrupt in variants) fails(ContractError.STORAGE_FAILURE, replayRepairChange(recorded.command, corrupt))
    }

}
