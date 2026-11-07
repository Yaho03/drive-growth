package io.drivegrowth.core.garage

import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.content.GameContentLoader
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InMemoryGameChangeStore
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.VehicleWrite
import org.junit.Assert.*
import org.junit.Test

class FirstGrantTest {
    private val content = GameContentFixtures.loaded()
    private val firstGrant = FirstGrant(content)
    private val casper = VehicleId("hyundai_casper")
    private val avante = VehicleId("hyundai_avante")
    private val src = InputSource.SIMULATION
    private fun newStore() = InMemoryGameChangeStore(emptyList())
    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun fail(error: ContractError) = ContractResult.Failure(error)
    private fun spend(store: InMemoryGameChangeStore, id: String, amount: Long) =
        ok(store.commitGameChange(GameChange(RequestId(id), TransactionKind.GARAGE, src, content.ruleVersion,
            money = MoneyChange(MoneyDirection.DEBIT, GameMoney(amount))))).balance

    @Test fun `고른 첫 차와 초기 재화를 한 번에 지급하고 같은 요청을 반복해도 한 번만 반영한다`() {
        val store = newStore()
        val first = ok(firstGrant.grant(store, avante, src))
        assertEquals(FirstGrant.INITIAL_MONEY, first.balance)
        val car = first.changedVehicle!!
        assertEquals(avante, car.vehicleId)
        assertEquals(listOf(Rarity.COMMON, 1, 0), listOf(car.rarity, car.level, car.levelProgressPercent))
        assertEquals(GameMoney(0), car.acquisitionCost)
        assertEquals(VehicleAcquisitionSource.INITIAL_GRANT, car.acquisitionSource)

        repeat(3) { assertEquals(first, ok(firstGrant.grant(store, avante, src))) } // 재실행·연타
        assertEquals(listOf(car), ok(store.listOwnedVehicles()))
        assertEquals(GameMoney(900), spend(store, "check", 100)) // 1,000이 한 번만 들어갔다
    }

    @Test fun `확정 뒤 다른 차를 고르면 거절하고 처음 선택을 유지한다`() {
        val store = newStore()
        val car = ok(firstGrant.grant(store, casper, src)).changedVehicle!!
        assertEquals(fail(ContractError.STATE_CONFLICT), firstGrant.grant(store, avante, src))
        assertEquals(listOf(car), ok(store.listOwnedVehicles()))
    }

    @Test fun `첫 차를 처분하고 재화를 쓴 프로필도 새 사용자로 보고 다시 지급하지 않는다`() {
        val store = newStore()
        val first = ok(firstGrant.grant(store, casper, src))
        assertEquals(GameMoney(700), spend(store, "spend", 300))
        ok(store.commitGameChange(GameChange(RequestId("consume"), TransactionKind.GARAGE, src, content.ruleVersion,
            vehicle = VehicleWrite.Update(first.changedVehicle!!.ownedVehicleId, Revision(0), 1, VehicleLifecycle.CONSUMED, 0))))
        assertTrue(ok(store.listOwnedVehicles()).isEmpty()) // 보유 0대여도

        assertEquals(first, ok(firstGrant.grant(store, casper, src))) // 원래 정산 재생, 새 지급 없음
        assertTrue(ok(store.listOwnedVehicles()).isEmpty())
        assertEquals(GameMoney(600), spend(store, "after", 100))
    }

    @Test fun `후보가 아니거나 콘텐츠에 없거나 저장이 실패하면 아무것도 지급하지 않고 다시 고를 수 있다`() {
        val store = newStore()
        assertEquals(fail(ContractError.INVALID_INPUT), firstGrant.grant(store, VehicleId("hyundai_sonata"), src))

        val noAvante = GameContentFixtures.content.lines().filterNot { it.startsWith("hyundai_avante") }.joinToString("\n")
        val missing = FirstGrant((GameContentLoader.load(GameContentFixtures.facts, noAvante) as ContentLoad.Loaded).snapshot)
        assertEquals(fail(ContractError.DATA_UNAVAILABLE), missing.grant(store, avante, src))

        store.nextCommitFailure = ContractError.STORAGE_FAILURE
        assertEquals(fail(ContractError.STORAGE_FAILURE), firstGrant.grant(store, avante, src))
        assertTrue(ok(store.listOwnedVehicles()).isEmpty()) // 선택 전 상태

        // 실패는 확정이 아니므로 다른 후보도 고를 수 있다
        assertEquals(casper, ok(firstGrant.grant(store, casper, src)).changedVehicle!!.vehicleId)
    }

    @Test fun `공개 후보 목록은 외부에서 바꿀 수 없고 후보 밖 차종은 계속 거절한다`() {
        @Suppress("UNCHECKED_CAST")
        val exposed = FirstGrant.CANDIDATES as MutableList<VehicleId>
        assertThrows(UnsupportedOperationException::class.java) { exposed[0] = VehicleId("hyundai_sonata") }
        assertThrows(UnsupportedOperationException::class.java) { exposed.add(VehicleId("hyundai_sonata")) }
        assertEquals(listOf(casper, avante), FirstGrant.CANDIDATES)

        val store = newStore()
        assertEquals(fail(ContractError.INVALID_INPUT), firstGrant.grant(store, VehicleId("hyundai_sonata"), src))
        val first = ok(firstGrant.grant(store, casper, src))
        assertEquals(first, ok(firstGrant.grant(store, casper, src)))
    }
}
