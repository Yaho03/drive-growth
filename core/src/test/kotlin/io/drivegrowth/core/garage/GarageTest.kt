package io.drivegrowth.core.garage

import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.contract.ContentVersion
import io.drivegrowth.core.contract.VehicleCatalogSnapshot
import io.drivegrowth.core.contract.VehicleDefinition
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
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.*
import org.junit.Test

class GarageTest {
    private val garage = Garage(GameContentFixtures.loaded().catalog)
    private val casper = VehicleId("hyundai_casper")
    private val g90 = VehicleId("genesis_g90")

    private fun vehicle(id: String, vehicleId: VehicleId = casper, lifecycle: VehicleLifecycle = VehicleLifecycle.OWNED) =
        OwnedVehicle(OwnedVehicleId(id), vehicleId, 1, Revision(0), lifecycle, Rarity.COMMON, 0, GameMoney(100), VehicleAcquisitionSource.GACHA)

    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value

    @Test fun `같은 차종 두 대를 따로 조회하고 한 대를 소비해도 다른 대는 그대로다`() {
        val store = InMemoryGameChangeStore(listOf(vehicle("v1"), vehicle("v2")))
        val before = ok(garage.owned(ok(store.listOwnedVehicles())))
        assertEquals(listOf("v1", "v2"), before.map { it.ownedVehicleId.value })
        assertEquals(setOf(casper), before.map { it.vehicleId }.toSet())

        val consume = GameChange(RequestId("r-consume-v1"), TransactionKind.GARAGE, InputSource.SIMULATION, RuleVersion("fixture-rule-v0"),
            vehicle = VehicleWrite.Update(OwnedVehicleId("v1"), Revision(0), 1, VehicleLifecycle.CONSUMED, 0))
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
        assertEquals(listOf("v1"), ok(garage.availableForRental(history) { ContractResult.Success(VehicleActivity.IDLE) }).map { it.ownedVehicleId.value })
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
        val available = ok(garage.availableForRental(vehicles) { ContractResult.Success(activity.getValue(it.value)) })
        assertEquals(listOf("idle"), available.map { it.ownedVehicleId.value })
        assertEquals(4, ok(garage.owned(vehicles)).size) // 조회만으로 상태를 바꾸지 않는다
    }

    @Test fun `콘텐츠에 없는 차종이나 중복 개체 ID는 손상으로 거절한다`() {
        val unknown = listOf(vehicle("v1", VehicleId("hyundai_unknown")))
        val duplicated = listOf(vehicle("v1"), vehicle("v1", g90))
        for (bad in listOf(unknown, duplicated)) {
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), garage.owned(bad))
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), garage.find(bad, OwnedVehicleId("v1")))
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), garage.availableForRental(bad) { ContractResult.Success(VehicleActivity.IDLE) })
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
        // 도감 키를 직접 주입한 계산 시험이며 실제 저장·재실행 시험은 아니다.
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

    @Test fun `카탈로그 원본을 비워도 생성 시 도감과 개체 검증을 유지한다`() {
        val definitions = mutableListOf(VehicleDefinition(casper, "캐스퍼"))
        val snapshotGarage = Garage(VehicleCatalogSnapshot(ContentVersion("snapshot-test"), definitions))
        val before = ok(snapshotGarage.collection(setOf(CollectionKey(casper, Rarity.RARE)), emptySet()))
        definitions.clear()

        assertEquals(before, ok(snapshotGarage.collection(setOf(CollectionKey(casper, Rarity.RARE)), emptySet())))
        assertEquals(listOf(vehicle("v1")), ok(snapshotGarage.owned(listOf(vehicle("v1")))))
    }

    @Test fun `카탈로그 원본에 추가한 차종은 생성 시 도감과 검증에 들어가지 않는다`() {
        val definitions = mutableListOf(VehicleDefinition(casper, "캐스퍼"))
        val snapshotGarage = Garage(VehicleCatalogSnapshot(ContentVersion("snapshot-test"), definitions))
        val before = ok(snapshotGarage.collection(emptySet(), emptySet()))
        definitions.add(VehicleDefinition(g90, "G90"))

        assertEquals(before, ok(snapshotGarage.collection(emptySet(), emptySet())))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), snapshotGarage.owned(listOf(vehicle("v2", g90))))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), snapshotGarage.collection(setOf(CollectionKey(g90, Rarity.COMMON)), emptySet()))
    }

    @Test fun `활동 기록 누락을 정상 대여 후보로 처리하지 않는다`() {
        val activity = emptyMap<OwnedVehicleId, VehicleActivity>()
        val result = garage.availableForRental(listOf(vehicle("v1"))) { id ->
            activity[id]?.let { ContractResult.Success(it) } ?: ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        }
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), result)
    }

    @Test fun `활동 조회의 DATA_UNAVAILABLE을 그대로 반환한다`() {
        val failure = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        assertEquals(failure, garage.availableForRental(listOf(vehicle("v1"))) { failure })
    }

    @Test fun `일부 후보를 찾은 뒤 STORAGE_FAILURE가 나도 전체 조회는 실패한다`() {
        val vehicles = listOf(vehicle("v1"), vehicle("v2"))
        val before = vehicles.toList()
        val visited = mutableListOf<String>()
        val result = garage.availableForRental(vehicles) {
            visited.add(it.value)
            if (it.value == "v1") ContractResult.Success(VehicleActivity.IDLE)
            else ContractResult.Failure(ContractError.STORAGE_FAILURE)
        }
        assertEquals(listOf("v1", "v2"), visited)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), result)
        assertEquals(before, vehicles)
    }

    @Test fun `한 개체의 활동과 입력 목록 변경이 다른 개체와 반환 목록을 바꾸지 않는다`() {
        val vehicles = mutableListOf(vehicle("v1"), vehicle("v2"))
        val before = vehicles.toList()
        val owned = ok(garage.owned(vehicles))
        val available = ok(garage.availableForRental(vehicles) {
            ContractResult.Success(if (it.value == "v1") VehicleActivity.RENTED else VehicleActivity.IDLE)
        })
        assertEquals(listOf(before[1]), available)
        assertEquals(before, vehicles)
        vehicles.clear()
        assertEquals(before, owned)
        assertEquals(listOf(before[1]), available)
        assertThrows(UnsupportedOperationException::class.java) { (available as MutableList<*>).clear() }
    }

    @Test fun `빈 입력과 처분 개체에는 활동 조회를 호출하지 않는다`() {
        assertTrue(ok(garage.availableForRental(emptyList()) { error("빈 입력의 활동 조회") }).isEmpty())
        val disposed = listOf(VehicleLifecycle.SOLD, VehicleLifecycle.SCRAPPED, VehicleLifecycle.CONSUMED)
            .mapIndexed { index, lifecycle -> vehicle("v$index", lifecycle = lifecycle) }
        assertTrue(ok(garage.availableForRental(disposed) { error("처분 개체의 활동 조회") }).isEmpty())
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), garage.find(emptyList(), OwnedVehicleId("missing")))
    }

    @Test fun `예상하지 못한 callback 오류를 정상 후보나 공통 실패로 숨기지 않는다`() {
        val error = IllegalStateException("공급자 구현 오류")
        assertSame(error, assertThrows(IllegalStateException::class.java) {
            garage.availableForRental(listOf(vehicle("v1"))) { throw error }
        })
    }

    @Test fun `도감 입력 집합을 바꿔도 이미 반환한 결과는 유지된다`() {
        val key = CollectionKey(casper, Rarity.LEGEND)
        val everOwned = mutableSetOf(key)
        val discovered = mutableSetOf(CollectionKey(g90, Rarity.RARE))
        val entries = ok(garage.collection(everOwned, discovered))
        val before = entries.toList()
        everOwned.clear()
        discovered.clear()
        assertEquals(before, entries)
        assertEquals(CollectionState.OWNED, entries.first { it.key == key }.state)
    }
}
