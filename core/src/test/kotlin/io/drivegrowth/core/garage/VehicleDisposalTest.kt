package io.drivegrowth.core.garage

import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InMemoryGameChangeStore
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.CompletedRental
import io.drivegrowth.core.rental.CompletedRentalSource
import org.junit.Assert.*
import org.junit.Test

class VehicleDisposalTest {
    private val content = GameContentFixtures.loaded()
    private val disposal = VehicleDisposal(content.catalog, content.ruleVersion)
    private val src = InputSource.SIMULATION

    private fun car(id: String, cost: Long = 500, level: Int = 1, progress: Int = 0,
                    source: VehicleAcquisitionSource = VehicleAcquisitionSource.GACHA) =
        OwnedVehicle(OwnedVehicleId(id), VehicleId("hyundai_casper"), level, Revision(0), VehicleLifecycle.OWNED,
            Rarity.COMMON, progress, GameMoney(cost), source)

    /** 테스트용 사고 조회. 실제는 app의 RentalLedger(#116). */
    private class Accidents(private val counts: Map<String, ContractResult<Int>>) : CompletedRentalSource {
        override fun firstCompletedRental(): ContractResult<CompletedRental?> = throw UnsupportedOperationException()
        override fun completedRental(id: RequestId): ContractResult<CompletedRental?> = throw UnsupportedOperationException()
        override fun accidentCount(ownedVehicleId: OwnedVehicleId) =
            counts[ownedVehicleId.value] ?: ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    }
    private fun accidents(vararg pairs: Pair<String, Int>) = Accidents(pairs.associate { it.first to ContractResult.Success(it.second) })

    private fun activity(a: VehicleActivity): (OwnedVehicleId) -> ContractResult<VehicleActivity> = { ContractResult.Success(a) }
    private val idle = activity(VehicleActivity.IDLE)
    private fun ref(v: OwnedVehicle) = VehicleRef(v.ownedVehicleId, v.revision)
    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun fail(error: ContractError) = ContractResult.Failure(error)
    private fun owned(store: InMemoryGameChangeStore) = ok(store.listOwnedVehicles())

    @Test fun `판매는 사고 횟수를 반영한 금액 입금과 SOLD 전환을 한 거래로 저장하고 재제출은 처음 정산이다`() {
        val lv20 = car("lv20", level = 20)
        val store = InMemoryGameChangeStore(listOf(lv20), GameMoney(0))
        val plan = ok(disposal.planSale(owned(store), RequestId("sell"), src, ref(lv20), accidents("lv20" to 2), idle))
        val first = ok(store.commitGameChange(plan))
        assertEquals(GameMoney(345), first.balance) // 500 × (50% + 19%p)
        assertEquals(MoneyDirection.CREDIT, first.transaction.money!!.direction)
        val sold = first.changedVehicle!!
        assertEquals(listOf<Any>(VehicleLifecycle.SOLD, 20, 0), listOf(sold.lifecycle, sold.level, sold.levelProgressPercent))
        assertEquals(GameMoney(500), sold.acquisitionCost) // 취득 정보 보존
        assertTrue(owned(store).isEmpty())

        assertEquals(first, ok(store.commitGameChange(plan))) // 응답 유실 후 재제출
        // 처분된 차는 다시 판매·폐차 계획을 만들 수 없다
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), disposal.planSale(owned(store), RequestId("again"), src, ref(lv20), accidents("lv20" to 2), idle))
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), disposal.planScrap(owned(store), RequestId("again2"), src, ref(lv20), idle))
    }

    @Test fun `사고 3회 이상은 판매를 거절하고 폐차만 된다`() {
        val v = car("v")
        val store = InMemoryGameChangeStore(listOf(v), GameMoney(0))
        assertEquals(fail(ContractError.INVALID_INPUT), disposal.planSale(owned(store), RequestId("s"), src, ref(v), accidents("v" to 3), idle))
        val scrap = ok(store.commitGameChange(ok(disposal.planScrap(owned(store), RequestId("x"), src, ref(v), idle))))
        assertEquals(GameMoney(100), scrap.balance) // 500 × 20%
        assertEquals(VehicleLifecycle.SCRAPPED, scrap.changedVehicle!!.lifecycle)
    }

    @Test fun `무료 취득 차량은 금액 변경 없이 처분만 하고 수리 필요 차량도 처분할 수 있다`() {
        val first = car("first", cost = 0, source = VehicleAcquisitionSource.INITIAL_GRANT)
        val store = InMemoryGameChangeStore(listOf(first), GameMoney(50))
        val plan = ok(disposal.planSale(owned(store), RequestId("free"), src, ref(first), accidents("first" to 0), activity(VehicleActivity.NEEDS_REPAIR)))
        assertNull(plan.money)
        val settled = ok(store.commitGameChange(plan))
        assertEquals(GameMoney(50), settled.balance)
        assertEquals(VehicleLifecycle.SOLD, settled.changedVehicle!!.lifecycle)
    }

    @Test fun `대여·반납 중, 오래된 revision, 사고·활동 조회 실패는 쓰지 않고 거절한다`() {
        val v = car("v")
        val owned = listOf(v)
        val a = accidents("v" to 0)
        for (busy in listOf(VehicleActivity.RENTED, VehicleActivity.RETURNING)) {
            assertEquals(fail(ContractError.STATE_CONFLICT), disposal.planSale(owned, RequestId("s"), src, ref(v), a, activity(busy)))
            assertEquals(fail(ContractError.STATE_CONFLICT), disposal.planScrap(owned, RequestId("x"), src, ref(v), activity(busy)))
        }
        assertEquals(fail(ContractError.STATE_CONFLICT), disposal.planSale(owned, RequestId("s"), src, VehicleRef(v.ownedVehicleId, Revision(3)), a, idle))
        // 사고 조회 실패·누락·음수
        assertEquals(fail(ContractError.STORAGE_FAILURE), disposal.planSale(owned, RequestId("s"), src, ref(v),
            Accidents(mapOf("v" to ContractResult.Failure(ContractError.STORAGE_FAILURE))), idle))
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), disposal.planSale(owned, RequestId("s"), src, ref(v), accidents(), idle))
        assertEquals(fail(ContractError.INVALID_INPUT), disposal.planSale(owned, RequestId("s"), src, ref(v), accidents("v" to -1), idle))
        // 활동 조회 실패는 그대로
        val broken: (OwnedVehicleId) -> ContractResult<VehicleActivity> = { ContractResult.Failure(ContractError.DATA_UNAVAILABLE) }
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), disposal.planScrap(owned, RequestId("x"), src, ref(v), broken))
    }

    @Test fun `같은 차종 여러 대 중 지정한 개체만 처분하고 저장 실패는 전체 미반영이다`() {
        val a = car("a"); val b = car("b", level = 5)
        val store = InMemoryGameChangeStore(listOf(a, b), GameMoney(0))
        val plan = ok(disposal.planSale(owned(store), RequestId("sell-b"), src, ref(b), accidents("b" to 0), idle))
        store.nextCommitFailure = ContractError.STORAGE_FAILURE
        assertEquals(fail(ContractError.STORAGE_FAILURE), store.commitGameChange(plan))
        assertEquals(listOf(a, b), owned(store))
        assertEquals(GameMoney(320), ok(store.commitGameChange(plan)).balance) // 500 × (60% + 4%p)
        assertEquals(listOf(a), owned(store))
    }
}
