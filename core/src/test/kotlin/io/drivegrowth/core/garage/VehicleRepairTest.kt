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
import io.drivegrowth.core.contract.RepairFixtureConfiguration
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

    /** 조회만 공급하는 시험용 저장소. 거절 경로 시험용이며 commit은 아래 메모리 참조 저장소(#155)로 시험한다. */
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

    // ── #155 메모리 참조 commitRepair로 실제 확정 ──
    private fun memoryStore() = InMemoryGameChangeStore(listOf(car), GameMoney(1000), repairConfiguration = RepairFixtureConfiguration(
        src, content.catalog.contentVersion, content.ruleVersion,
        mapOf(car.ownedVehicleId to ContractResult.Success(VehicleActivity.NEEDS_REPAIR)),
        mapOf(car.ownedVehicleId to ContractResult.Success(2)),
        mapOf(car.ownedVehicleId to ContractResult.Success(GameMoney(440)))))
    private fun prepare(s: InMemoryGameChangeStore, id: String = "fix", ref: VehicleRef = VehicleRef(car.ownedVehicleId, car.revision)) =
        repair.prepare(s, RequestId(id), src, ref)

    @Test fun `수리 명령을 저장소에 확정하면 차감과 회복이 한 정산이고 재제출은 처음 정산이다`() {
        val store = memoryStore()
        val command = ok(prepare(store))
        val first = ok(store.commitRepair(command))
        assertEquals(GameMoney(560), first.balance)
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(440)), first.transaction.money)
        assertEquals(car.copy(revision = Revision(4)), first.vehicle)
        assertEquals(listOf<Any>(VehicleActivity.IDLE, 2), listOf(first.activity, first.accidentCount))
        val after = ok(store.readRepairState(car.ownedVehicleId))
        assertEquals(listOf<Any>(VehicleActivity.IDLE, GameMoney(560), Revision(4)), listOf(after.activity, after.balance, after.vehicle.revision))

        assertEquals(first, ok(store.commitRepair(command))) // 응답 유실 후 같은 명령 재제출
        assertEquals(GameMoney(560), ok(store.readRepairState(car.ownedVehicleId)).balance) // 두 번 차감 안 됨
        // 수리 뒤 옛 화면으로 다시 수리하려 하면 거절(이미 IDLE·revision 변경)
        assertEquals(fail(ContractError.STATE_CONFLICT), prepare(store, "again"))
    }

    @Test fun `수리 저장 실패는 잔액·수리 필요·revision을 모두 보존하고 같은 명령으로 다시 확정한다`() {
        val store = memoryStore()
        val command = ok(prepare(store))
        store.nextCommitFailure = ContractError.STORAGE_FAILURE
        assertEquals(fail(ContractError.STORAGE_FAILURE), store.commitRepair(command))
        val kept = ok(store.readRepairState(car.ownedVehicleId))
        assertEquals(listOf<Any>(VehicleActivity.NEEDS_REPAIR, GameMoney(1000), Revision(3)), listOf(kept.activity, kept.balance, kept.vehicle.revision))
        assertEquals(GameMoney(560), ok(store.commitRepair(command)).balance)
    }
}
