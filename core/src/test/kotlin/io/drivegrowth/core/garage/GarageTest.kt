package io.drivegrowth.core.garage

import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.InMemoryGameChangeStore
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.VehicleWrite
import io.drivegrowth.core.progression.Rarity
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.*
import org.junit.Test

class GarageTest {
    private val garage = Garage(GameContentFixtures.loaded().catalog)
    private val casper = VehicleId("hyundai_casper")
    private val g90 = VehicleId("genesis_g90")

    private fun vehicle(id: String, vehicleId: VehicleId = casper, lifecycle: VehicleLifecycle = VehicleLifecycle.OWNED) =
        OwnedVehicle(OwnedVehicleId(id), vehicleId, 1, Revision(0), lifecycle)

    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value

    @Test fun `같은 차종 두 대를 따로 조회하고 한 대를 소비해도 다른 대는 그대로다`() {
        val store = InMemoryGameChangeStore(listOf(vehicle("v1"), vehicle("v2")))
        val before = ok(garage.owned(ok(store.listOwnedVehicles())))
        assertEquals(listOf("v1", "v2"), before.map { it.ownedVehicleId.value })
        assertEquals(setOf(casper), before.map { it.vehicleId }.toSet())

        val consume = GameChange(RequestId("r-consume-v1"), TransactionKind.GARAGE, InputSource.SIMULATION, RuleVersion("fixture-rule-v0"),
            vehicle = VehicleWrite.Update(OwnedVehicleId("v1"), Revision(0), 1, VehicleLifecycle.CONSUMED))
        assertTrue(store.commitGameChange(consume) is ContractResult.Success)

        val after = ok(garage.owned(ok(store.listOwnedVehicles())))
        assertEquals(listOf(before[1]), after) // v2는 revision·레벨까지 변화 없음
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), garage.find(ok(store.listOwnedVehicles()), OwnedVehicleId("v1")))
        assertEquals(before[1], ok(garage.find(ok(store.listOwnedVehicles()), OwnedVehicleId("v2"))))
    }

    @Test fun `처분된 차량은 보유·대여 목록에서 빠진다`() {
        val history = listOf(
            vehicle("v1"),
            vehicle("v2", lifecycle = VehicleLifecycle.SOLD),
            vehicle("v3", lifecycle = VehicleLifecycle.SCRAPPED),
            vehicle("v4", lifecycle = VehicleLifecycle.CONSUMED),
        )
        assertEquals(listOf("v1"), ok(garage.owned(history)).map { it.ownedVehicleId.value })
        assertEquals(listOf("v1"), ok(garage.availableForRental(history) { VehicleActivity.IDLE }).map { it.ownedVehicleId.value })
        listOf("v2", "v3", "v4").forEach {
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), garage.find(history, OwnedVehicleId(it)))
        }
    }

    @Test fun `대여 중·반납 중·수리 필요 차량은 대여 가능 목록에서 빠진다`() {
        val vehicles = listOf(vehicle("idle"), vehicle("rented"), vehicle("returning"), vehicle("repair"))
        val activity = mapOf(
            "idle" to VehicleActivity.IDLE, "rented" to VehicleActivity.RENTED,
            "returning" to VehicleActivity.RETURNING, "repair" to VehicleActivity.NEEDS_REPAIR,
        )
        val available = ok(garage.availableForRental(vehicles) { activity.getValue(it.value) })
        assertEquals(listOf("idle"), available.map { it.ownedVehicleId.value })
        assertEquals(4, ok(garage.owned(vehicles)).size) // 조회만으로 상태를 바꾸지 않는다
    }

    @Test fun `콘텐츠에 없는 차종이나 중복 개체 ID는 손상으로 거절한다`() {
        val unknown = listOf(vehicle("v1", VehicleId("hyundai_unknown")))
        val duplicated = listOf(vehicle("v1"), vehicle("v1", g90))
        for (bad in listOf(unknown, duplicated)) {
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), garage.owned(bad))
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), garage.find(bad, OwnedVehicleId("v1")))
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), garage.availableForRental(bad) { VehicleActivity.IDLE })
        }
    }

    @Test fun `도감은 차종×희귀도 칸마다 미발견·발견·보유를 주고 처분 후에도 보유를 유지한다`() {
        val soldLegend = CollectionKey(g90, Rarity.LEGEND)   // 판매했지만 보유했던 기록
        val seenRare = CollectionKey(casper, Rarity.RARE)    // 가챠 결과에서 봄
        val ownedCommon = CollectionKey(casper, Rarity.COMMON)
        val entries = ok(garage.collection(everOwned = setOf(soldLegend, ownedCommon), discovered = setOf(seenRare, ownedCommon)))

        assertEquals(5 * 4, entries.size)
        val state = entries.associate { it.key to it.state }
        assertEquals(CollectionState.OWNED, state[soldLegend])
        assertEquals(CollectionState.OWNED, state[ownedCommon]) // 발견+보유면 보유
        assertEquals(CollectionState.DISCOVERED, state[seenRare])
        assertEquals(CollectionState.UNDISCOVERED, state[CollectionKey(g90, Rarity.COMMON)])
        assertEquals(17, entries.count { it.state == CollectionState.UNDISCOVERED })
        // 같은 입력이면 같은 결과(저장된 기록을 다시 읽은 경우와 같다)
        assertEquals(entries, ok(garage.collection(setOf(soldLegend, ownedCommon), setOf(seenRare, ownedCommon))))
    }

    @Test fun `도감 기록에 콘텐츠에 없는 차종이 있으면 거절하고 결과 목록은 바꿀 수 없다`() {
        val unknown = CollectionKey(VehicleId("hyundai_unknown"), Rarity.COMMON)
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), garage.collection(setOf(unknown), emptySet()))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), garage.collection(emptySet(), setOf(unknown)))

        val entries = ok(garage.collection(emptySet(), emptySet()))
        assertTrue(entries.all { it.state == CollectionState.UNDISCOVERED })
        assertThrows(UnsupportedOperationException::class.java) { (entries as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (ok(garage.owned(listOf(vehicle("v1")))) as MutableList<*>).clear() }
    }
}
