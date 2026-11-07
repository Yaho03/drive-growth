package io.drivegrowth.core.garage

import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InMemoryGameChangeStore
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.VehicleWrite
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.*
import org.junit.Test

class VehicleUpgradeTest {
    private val upgrade = VehicleUpgrade(GameContentFixtures.loaded()) // 캐스퍼 차종가 8,000
    private val casper = VehicleId("hyundai_casper")

    private fun car(id: String, rarity: Rarity = Rarity.COMMON, level: Int = 1, progress: Int = 0,
                    vehicleId: VehicleId = casper, lifecycle: VehicleLifecycle = VehicleLifecycle.OWNED) =
        OwnedVehicle(OwnedVehicleId(id), vehicleId, level, Revision(0), lifecycle, rarity, progress, GameMoney(500),
            VehicleAcquisitionSource.GACHA)

    private val target = car("target", Rarity.RARE, level = 5, progress = 40)
    private fun ref(v: OwnedVehicle) = VehicleRef(v.ownedVehicleId, v.revision)
    private val idle: (OwnedVehicleId) -> VehicleActivity = { VehicleActivity.IDLE }
    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun fail(error: ContractError) = ContractResult.Failure(error)
    private fun levelUp(store: InMemoryGameChangeStore, v: OwnedVehicle, id: String = "up",
                        activity: (OwnedVehicleId) -> VehicleActivity = idle) =
        upgrade.levelUp(store, RequestId(id), InputSource.SIMULATION, ref(v), activity)

    @Test fun `레벨업은 희귀도 비용을 한 번 차감하고 진행량을 유지한 채 한 레벨 올린다`() {
        val store = InMemoryGameChangeStore(listOf(target), GameMoney(1_000))
        val settlement = ok(levelUp(store, target))
        // 8,000 × 2% × 5레벨 × 희귀 1.1 = 880
        assertEquals(GameMoney(120), settlement.balance)
        val after = settlement.changedVehicle!!
        assertEquals(listOf(6, 40, 1), listOf(after.level, after.levelProgressPercent, after.revision.value))
        assertEquals(listOf(Rarity.RARE, GameMoney(500)), listOf(after.rarity, after.acquisitionCost)) // 취득 정보 보존

        // 재실행으로 같은 요청을 다시 보내도 두 번 오르거나 차감되지 않는다
        assertEquals(fail(ContractError.STATE_CONFLICT), levelUp(store, target))
        assertEquals(listOf(after), ok(store.listOwnedVehicles()))
    }

    @Test fun `19레벨에서 올리면 20레벨 진행량 0이 된다`() {
        val v = car("v", level = 19, progress = 70)
        val store = InMemoryGameChangeStore(listOf(v), GameMoney(10_000))
        val after = ok(levelUp(store, v)).changedVehicle!!
        assertEquals(listOf(20, 0), listOf(after.level, after.levelProgressPercent))
        assertEquals(fail(ContractError.INVALID_INPUT), levelUp(store, after, "again")) // 최대 레벨
    }

    @Test fun `레벨업 거절은 잔액과 차량을 그대로 둔다`() {
        val sold = car("sold", lifecycle = VehicleLifecycle.SOLD)
        fun store(balance: Long = 1_000) = InMemoryGameChangeStore(listOf(target, sold), GameMoney(balance))
        val cases = mapOf(
            "잔액 879" to (store(879) to { s: InMemoryGameChangeStore -> levelUp(s, target) }),
            "대여 중" to (store() to { s -> levelUp(s, target, activity = { VehicleActivity.RENTED }) }),
            "반납 중" to (store() to { s -> levelUp(s, target, activity = { VehicleActivity.RETURNING }) }),
            "수리 필요" to (store() to { s -> levelUp(s, target, activity = { VehicleActivity.NEEDS_REPAIR }) }),
            "오래된 revision" to (store() to { s -> levelUp(s, target.copy(revision = Revision(7))) }),
            "판매한 차" to (store() to { s -> levelUp(s, sold) }),
            "저장 실패" to (store().apply { nextCommitFailure = ContractError.STORAGE_FAILURE } to { s -> levelUp(s, target) }),
        )
        val expected = mapOf(
            "잔액 879" to ContractError.INSUFFICIENT_FUNDS, "대여 중" to ContractError.STATE_CONFLICT,
            "반납 중" to ContractError.STATE_CONFLICT, "수리 필요" to ContractError.STATE_CONFLICT,
            "오래된 revision" to ContractError.STATE_CONFLICT, "판매한 차" to ContractError.DATA_UNAVAILABLE,
            "저장 실패" to ContractError.STORAGE_FAILURE,
        )
        for ((name, case) in cases) {
            val (s, run) = case
            assertEquals(name, fail(expected.getValue(name)), run(s))
            assertEquals(name, listOf(target), ok(s.listOwnedVehicles()))
        }
    }

    @Test fun `재료는 희귀도가 달라도 같은 차종이면 진행량을 채우고 지정한 재료만 소비한다`() {
        val legend = car("m-legend", Rarity.LEGEND, level = 3, progress = 10)
        val common = car("m-common")
        val owned = listOf(target, legend, common)
        val plan = ok(upgrade.planMaterial(owned, ref(target), ref(legend), idle))
        // 5레벨 40% + 전설 400% = 9레벨 40%
        assertEquals(VehicleWrite.Update(target.ownedVehicleId, Revision(0), 9, VehicleLifecycle.OWNED, 40), plan.target)
        // 재료는 성장값을 보존한 채 소비. 다른 같은 차종 개체(m-common)는 건드리지 않는다.
        assertEquals(VehicleWrite.Update(legend.ownedVehicleId, Revision(0), 3, VehicleLifecycle.CONSUMED, 10), plan.material)

        val withCommon = ok(upgrade.planMaterial(owned, ref(target), ref(common), idle))
        assertEquals(listOf(6, 40), listOf(withCommon.target.level, withCommon.target.levelProgressPercent))
        assertEquals(common.ownedVehicleId, withCommon.material.ownedVehicleId)
    }

    @Test fun `최대 레벨 재료도 쓸 수 있고 20을 넘는 진행량은 버린다`() {
        val high = car("high", level = 19, progress = 50)
        val maxMaterial = car("max", Rarity.RARE, level = 20)
        val plan = ok(upgrade.planMaterial(listOf(high, maxMaterial), ref(high), ref(maxMaterial), idle))
        assertEquals(listOf(20, 0), listOf(plan.target.level, plan.target.levelProgressPercent))
    }

    @Test fun `잘못된 재료 요청은 거절한다`() {
        val material = car("m")
        val consumed = car("used", lifecycle = VehicleLifecycle.CONSUMED)
        val avante = car("avante", vehicleId = VehicleId("hyundai_avante"))
        val maxTarget = car("max", level = 20)
        val owned = listOf(target, material, consumed, avante, maxTarget)
        fun plan(t: OwnedVehicle, m: OwnedVehicle, activity: (OwnedVehicleId) -> VehicleActivity = idle) =
            upgrade.planMaterial(owned, ref(t), ref(m), activity)

        assertEquals(fail(ContractError.INVALID_INPUT), plan(target, target))         // 같은 개체
        assertEquals(fail(ContractError.INVALID_INPUT), plan(target, avante))         // 다른 차종
        assertEquals(fail(ContractError.INVALID_INPUT), plan(maxTarget, material))    // 최대 레벨 대상
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), plan(target, consumed))    // 이미 소비한 재료
        assertEquals(fail(ContractError.STATE_CONFLICT), plan(target, material.copy(revision = Revision(1)))) // 그사이 바뀜
        val rentedMaterial = { id: OwnedVehicleId -> if (id == material.ownedVehicleId) VehicleActivity.RENTED else VehicleActivity.IDLE }
        assertEquals(fail(ContractError.STATE_CONFLICT), plan(target, material, rentedMaterial)) // 대여 중 재료
        assertEquals(fail(ContractError.STATE_CONFLICT), plan(target, material) { VehicleActivity.NEEDS_REPAIR })
    }
}
