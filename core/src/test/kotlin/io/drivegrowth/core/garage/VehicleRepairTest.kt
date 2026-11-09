package io.drivegrowth.core.garage

import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.contract.ContentVersion
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameChangeStore
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InMemoryGameChangeStore
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RepairChangeStore
import io.drivegrowth.core.contract.RepairCommand
import io.drivegrowth.core.contract.RepairSettlement
import io.drivegrowth.core.contract.RepairState
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.prepareRepairChange
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.*
import org.junit.Test

class VehicleRepairTest {
    private val content = GameContentFixtures.loaded() // 캐스퍼 차종가 8,000
    private val repair = VehicleRepair(content)
    private val src = InputSource.SIMULATION
    private val car = OwnedVehicle(OwnedVehicleId("car"), VehicleId("hyundai_casper"), 7, Revision(3), VehicleLifecycle.OWNED,
        Rarity.RARE, 42, GameMoney(250), VehicleAcquisitionSource.GACHA)
    // 8,000 × 5% × 희귀 1.1 = 440 (취득액 250과 무관)
    private val state = RepairState(src, content.catalog.contentVersion, content.ruleVersion, GameMoney(1000), car,
        VehicleActivity.NEEDS_REPAIR, 2, GameMoney(440))

    /** 조회만 공급하는 시험용 저장소. 실제 commitRepair는 아직 메모리·SQLite 모두 미제공이다(#121·#122). */
    private class ReadOnlyRepairStore(private val state: ContractResult<RepairState>) :
        RepairChangeStore, GameChangeStore by InMemoryGameChangeStore(emptyList()) {
        override fun readRepairState(target: OwnedVehicleId) = state
        override fun repairSettlement(requestId: RequestId): ContractResult<RepairSettlement?> = throw UnsupportedOperationException()
        override fun commitRepair(command: RepairCommand): ContractResult<RepairSettlement> = throw UnsupportedOperationException()
    }

    private fun prepare(s: RepairState = state, ref: VehicleRef = VehicleRef(car.ownedVehicleId, car.revision)) =
        repair.prepare(ReadOnlyRepairStore(ContractResult.Success(s)), RequestId("fix"), src, ref)
    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun fail(error: ContractError) = ContractResult.Failure(error)

    @Test fun `수리 명령은 차종 가격 기준 견적과 확정 사고 누계를 고정하고 예정 결과는 차감과 IDLE 회복이다`() {
        val command = ok(prepare())
        assertEquals(RepairCommand(RequestId("fix"), src, content.catalog.contentVersion, content.ruleVersion,
            VehicleRef(car.ownedVehicleId, Revision(3)), 2, GameMoney(440)), command)
        val planned = ok(prepareRepairChange(command, state))
        assertEquals(GameMoney(560), planned.balance)
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(440)), planned.money)
        assertEquals(VehicleActivity.IDLE, planned.activity)
        assertEquals(car.copy(revision = Revision(4)), planned.vehicle) // 성장·희귀도·취득 정보 보존
        assertEquals(2, planned.accidentCount) // 수리해도 사고 누계는 줄지 않음
    }

    @Test fun `견적·버전이 앱 계산과 다르거나 콘텐츠에 없는 차종이면 명령을 만들지 않는다`() {
        assertEquals(fail(ContractError.STATE_CONFLICT), prepare(state.copy(repairCost = GameMoney(439))))
        assertEquals(fail(ContractError.STATE_CONFLICT), prepare(state.copy(contentVersion = ContentVersion("other"))))
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), prepare(state.copy(vehicle = car.copy(vehicleId = VehicleId("unknown")))))
    }

    @Test fun `수리 필요가 아니거나 옛 화면·잔액 부족·조회 실패는 거절한다`() {
        for (activity in listOf(VehicleActivity.IDLE, VehicleActivity.RENTED, VehicleActivity.RETURNING))
            assertEquals(fail(ContractError.STATE_CONFLICT), prepare(state.copy(activity = activity)))
        assertEquals(fail(ContractError.STATE_CONFLICT), prepare(ref = VehicleRef(car.ownedVehicleId, Revision(2))))
        assertEquals(fail(ContractError.INSUFFICIENT_FUNDS), prepare(state.copy(balance = GameMoney(439))))
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), prepare(state.copy(vehicle = car.copy(lifecycle = VehicleLifecycle.SOLD))))
        for (error in listOf(ContractError.DATA_UNAVAILABLE, ContractError.STORAGE_FAILURE))
            assertEquals(fail(error), repair.prepare(ReadOnlyRepairStore(fail(error)), RequestId("fix"), src, VehicleRef(car.ownedVehicleId, car.revision)))
    }
}
