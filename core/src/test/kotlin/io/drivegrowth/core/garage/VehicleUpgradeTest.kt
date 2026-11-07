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
    private val idle: (OwnedVehicleId) -> ContractResult<VehicleActivity> = { ContractResult.Success(VehicleActivity.IDLE) }
    private fun always(a: VehicleActivity): (OwnedVehicleId) -> ContractResult<VehicleActivity> = { ContractResult.Success(a) }
    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun fail(error: ContractError) = ContractResult.Failure(error)
    private fun plan(store: InMemoryGameChangeStore, v: OwnedVehicle, id: String = "up",
                     activity: (OwnedVehicleId) -> ContractResult<VehicleActivity> = idle) =
        upgrade.planLevelUp(ok(store.listOwnedVehicles()), RequestId(id), InputSource.SIMULATION, ref(v), activity)
    /** 계획을 만들고 바로 저장한다. 계획 단계 실패는 그대로 돌려준다. */
    private fun levelUp(store: InMemoryGameChangeStore, v: OwnedVehicle, id: String = "up",
                        activity: (OwnedVehicleId) -> ContractResult<VehicleActivity> = idle) =
        when (val p = plan(store, v, id, activity)) {
            is ContractResult.Failure -> p
            is ContractResult.Success -> store.commitGameChange(p.value)
        }

    @Test fun `레벨업은 희귀도 비용을 한 번 차감하고 진행량을 유지한 채 한 레벨 올린다`() {
        val store = InMemoryGameChangeStore(listOf(target), GameMoney(1_000))
        val settlement = ok(levelUp(store, target))
        // 8,000 × 2% × 5레벨 × 희귀 1.1 = 880
        assertEquals(GameMoney(120), settlement.balance)
        val after = settlement.changedVehicle!!
        assertEquals(listOf(6, 40, 1), listOf(after.level, after.levelProgressPercent, after.revision.value))
        assertEquals(listOf(Rarity.RARE, GameMoney(500)), listOf(after.rarity, after.acquisitionCost)) // 취득 정보 보존

        // 성공 뒤 계획을 새로 만들면 revision이 달라 새 요청으로 거절된다(두 번 오르지 않음)
        assertEquals(fail(ContractError.STATE_CONFLICT), levelUp(store, target))
        assertEquals(listOf(after), ok(store.listOwnedVehicles()))
    }

    @Test fun `응답을 잃어도 보관한 계획을 다시 제출하면 처음 정산을 재생하고 다른 내용은 충돌이다`() {
        val store = InMemoryGameChangeStore(listOf(target), GameMoney(10_000))
        val saved = ok(plan(store, target, "lost"))
        val first = ok(store.commitGameChange(saved)) // 저장 성공, 응답 유실 가정
        assertEquals(first, ok(store.commitGameChange(saved)))

        // 이후 다른 요청으로 더 성장해도 같은 계획 재제출은 처음 정산 그대로
        val grown = first.changedVehicle!!
        ok(levelUp(store, grown, "next"))
        assertEquals(first, ok(store.commitGameChange(saved)))

        // 같은 requestId에 다른 내용(다른 레벨)은 충돌, 상태 불변
        val before = ok(store.listOwnedVehicles())
        val tampered = saved.copy(vehicle = (saved.vehicle as VehicleWrite.Update).copy(level = 9))
        assertEquals(fail(ContractError.STATE_CONFLICT), store.commitGameChange(tampered))
        assertEquals(before, ok(store.listOwnedVehicles()))
    }

    @Test fun `활동 상태 조회가 실패하면 그 오류를 그대로 돌려주고 쓰지 않는다`() {
        val material = car("m")
        val owned = listOf(target, material)
        for (error in listOf(ContractError.DATA_UNAVAILABLE, ContractError.STORAGE_FAILURE)) {
            val store = InMemoryGameChangeStore(owned, GameMoney(10_000))
            val failing: (OwnedVehicleId) -> ContractResult<VehicleActivity> = { ContractResult.Failure(error) }
            assertEquals(fail(error), levelUp(store, target, activity = failing))
            assertEquals(owned, ok(store.listOwnedVehicles()))
            // 재료 쪽만 실패해도 계획을 만들지 않는다
            val materialFails = { id: OwnedVehicleId ->
                if (id == material.ownedVehicleId) ContractResult.Failure(error) else ContractResult.Success(VehicleActivity.IDLE)
            }
            assertEquals(fail(error), upgrade.planMaterial(owned, ref(target), ref(material), failing))
            assertEquals(fail(error), upgrade.planMaterial(owned, ref(target), ref(material), materialFails))
        }
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
            "대여 중" to (store() to { s -> levelUp(s, target, activity = always(VehicleActivity.RENTED)) }),
            "반납 중" to (store() to { s -> levelUp(s, target, activity = always(VehicleActivity.RETURNING)) }),
            "수리 필요" to (store() to { s -> levelUp(s, target, activity = always(VehicleActivity.NEEDS_REPAIR)) }),
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
        fun plan(t: OwnedVehicle, m: OwnedVehicle, activity: (OwnedVehicleId) -> ContractResult<VehicleActivity> = idle) =
            upgrade.planMaterial(owned, ref(t), ref(m), activity)

        assertEquals(fail(ContractError.INVALID_INPUT), plan(target, target))         // 같은 개체
        assertEquals(fail(ContractError.INVALID_INPUT), plan(target, avante))         // 다른 차종
        assertEquals(fail(ContractError.INVALID_INPUT), plan(maxTarget, material))    // 최대 레벨 대상
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), plan(target, consumed))    // 이미 소비한 재료
        assertEquals(fail(ContractError.STATE_CONFLICT), plan(target, material.copy(revision = Revision(1)))) // 그사이 바뀜
        val rentedMaterial = { id: OwnedVehicleId ->
            ContractResult.Success(if (id == material.ownedVehicleId) VehicleActivity.RENTED else VehicleActivity.IDLE)
        }
        assertEquals(fail(ContractError.STATE_CONFLICT), plan(target, material, rentedMaterial)) // 대여 중 재료
        assertEquals(fail(ContractError.STATE_CONFLICT), plan(target, material, always(VehicleActivity.NEEDS_REPAIR)))
    }
}
