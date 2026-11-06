package io.drivegrowth.core.contract

import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.garage.CollectionState
import io.drivegrowth.core.garage.Garage
import org.junit.Assert.*
import org.junit.Test

class OwnedVehicleContractTest {
    private fun change(id: String, write: VehicleWrite) = GameChange(
        RequestId(id), TransactionKind.GARAGE, InputSource.SIMULATION,
        RentalContractFixtures.ruleVersion, vehicle = write,
    )

    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value

    @Test fun `fixture 입력 변경과 반환 목록 변경으로 저장 상태를 바꿀 수 없다`() {
        val input = mutableListOf(RentalContractFixtures.firstVehicle, RentalContractFixtures.secondVehicle)
        val store = InMemoryGameChangeStore(input)
        val snapshot = ok(store.listOwnedVehicles())
        input.clear()
        assertEquals(2, snapshot.size)
        assertEquals(snapshot, ok(store.listOwnedVehicles()))
        assertThrows(UnsupportedOperationException::class.java) { (snapshot as MutableList<*>).clear() }
    }

    @Test fun `처분 명령으로 레벨을 함께 바꾸면 거절하고 원본을 보존한다`() {
        val store = RentalContractFixtures.store()
        val first = RentalContractFixtures.firstVehicle
        val result = store.commitGameChange(change("invalid-dispose", VehicleWrite.Update(
            first.ownedVehicleId, first.revision, first.level + 1, VehicleLifecycle.SOLD, first.levelProgressPercent,
        )))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), result)
        assertEquals(listOf(first, RentalContractFixtures.secondVehicle), ok(store.listOwnedVehicles()))
    }

    @Test fun `레벨과 진행량은 퍼센트포인트 경계를 검증하고 최대 레벨은 진행량이 없다`() {
        val vehicle = RentalContractFixtures.paidGachaVehicle
        for (level in 1 until MAX_VEHICLE_LEVEL) {
            for (progress in listOf(0, 99)) assertEquals(progress, vehicle.copy(level = level, levelProgressPercent = progress).levelProgressPercent)
        }
        assertEquals(20, vehicle.copy(level = 20, levelProgressPercent = 0).level)
        for (level in listOf(Int.MIN_VALUE, 0, 21, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { vehicle.copy(level = level) }
            assertThrows(IllegalArgumentException::class.java) {
                VehicleWrite.Update(vehicle.ownedVehicleId, vehicle.revision, level, VehicleLifecycle.OWNED, 0)
            }
        }
        for (progress in listOf(-1, 100, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { vehicle.copy(levelProgressPercent = progress) }
            assertThrows(IllegalArgumentException::class.java) {
                VehicleWrite.Update(vehicle.ownedVehicleId, vehicle.revision, 2, VehicleLifecycle.OWNED, progress)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { vehicle.copy(level = 20, levelProgressPercent = 1) }
        assertThrows(IllegalArgumentException::class.java) {
            VehicleWrite.Update(vehicle.ownedVehicleId, vehicle.revision, 20, VehicleLifecycle.OWNED, 1)
        }
        assertThrows(IllegalArgumentException::class.java) { GameMoney(-1) }
    }

    @Test fun `첫 지급과 무료 가챠의 실제 0은 출처로 구분하고 임의 취득값은 거절한다`() {
        val initial = RentalContractFixtures.firstVehicle
        val freeGacha = RentalContractFixtures.freeGachaVehicle
        assertEquals(GameMoney(0), initial.acquisitionCost)
        assertEquals(GameMoney(0), freeGacha.acquisitionCost)
        assertEquals(VehicleAcquisitionSource.INITIAL_GRANT, initial.acquisitionSource)
        assertEquals(VehicleAcquisitionSource.GACHA, freeGacha.acquisitionSource)
        assertThrows(IllegalArgumentException::class.java) { initial.copy(acquisitionCost = GameMoney(1)) }
        assertThrows(IllegalArgumentException::class.java) { initial.copy(rarity = Rarity.RARE) }
    }

    @Test fun `공통 예제는 같은 차종의 희귀도 성장과 무료 할인 유료 취득을 구분한다`() {
        val examples = RentalContractFixtures.vehicleExamples()
        assertEquals(setOf(RentalContractFixtures.vehicleId), examples.map { it.vehicleId }.toSet())
        assertEquals(Rarity.entries.toSet(), examples.map { it.rarity }.toSet())
        assertTrue(examples.map { it.level to it.levelProgressPercent }.toSet().size > 1)
        assertEquals(listOf(0L, 250L, 500L, 0L, 800L), examples.map { it.acquisitionCost.units })
        assertThrows(UnsupportedOperationException::class.java) { (examples as MutableList<*>).clear() }
        assertEquals(5, RentalContractFixtures.vehicleExamples().size)
        val store = InMemoryGameChangeStore(emptyList())
        for (vehicle in examples) {
            assertEquals(vehicle, ok(store.commitGameChange(change("example-${vehicle.ownedVehicleId.value}", VehicleWrite.Add(vehicle)))).changedVehicle)
        }
        assertEquals(examples, ok(store.listOwnedVehicles()))
    }

    @Test fun `생성 이후 Garage는 개체 희귀도 진행량 취득 정보를 그대로 반환한다`() {
        val store = InMemoryGameChangeStore(emptyList())
        val vehicle = RentalContractFixtures.freeGachaVehicle
        val saved = ok(store.commitGameChange(change("create-free", VehicleWrite.Add(vehicle))))
        val garage = Garage(ok(RentalContractFixtures.catalog().load()))
        val owned = ok(garage.owned(ok(store.listOwnedVehicles())))
        assertEquals(listOf(vehicle), owned)
        assertEquals(vehicle, ok(garage.find(owned, vehicle.ownedVehicleId)))
        assertEquals(vehicle, saved.changedVehicle)
        val entries = ok(garage.collection(owned.map { CollectionKey(it.vehicleId, it.rarity) }.toSet(), emptySet()))
        assertEquals(CollectionState.OWNED, entries.single { it.key == CollectionKey(vehicle.vehicleId, Rarity.LEGEND) }.state)
    }

    @Test fun `성장과 처분은 취득 정보와 다른 개체를 보존하고 재시도는 당시 정산을 반환한다`() {
        val vehicle = RentalContractFixtures.paidGachaVehicle
        val other = RentalContractFixtures.freeGachaVehicle
        val store = InMemoryGameChangeStore(listOf(other))
        val add = change("create-paid", VehicleWrite.Add(vehicle))
        val creation = ok(store.commitGameChange(add))
        val growth = change("grow", VehicleWrite.Update(vehicle.ownedVehicleId, Revision(0), 3, VehicleLifecycle.OWNED, 75))
        val grown = vehicle.copy(level = 3, levelProgressPercent = 75, revision = Revision(1))
        val growthResult = ok(store.commitGameChange(growth))
        assertEquals(grown, growthResult.changedVehicle)
        assertEquals(vehicle, ok(store.commitGameChange(add)).changedVehicle)
        assertEquals(creation, ok(store.commitGameChange(add)))
        assertEquals(other, ok(store.listOwnedVehicles()).single { it.ownedVehicleId == other.ownedVehicleId })
        val disposal = change("sell", VehicleWrite.Update(grown.ownedVehicleId, grown.revision, 3, VehicleLifecycle.SOLD, 75))
        val sold = ok(store.commitGameChange(disposal)).changedVehicle!!
        assertEquals(grown.copy(lifecycle = VehicleLifecycle.SOLD, revision = Revision(2)), sold)
        assertEquals(growthResult, ok(store.commitGameChange(growth)))
        assertEquals(listOf(other), ok(store.listOwnedVehicles()))
        assertEquals(vehicle, creation.changedVehicle)
    }

    @Test fun `새 개체 필드 하나만 다른 동일 요청도 충돌하며 저장값은 불변이다`() {
        val vehicle = RentalContractFixtures.freeGachaVehicle
        val store = InMemoryGameChangeStore(emptyList())
        val add = change("same-add", VehicleWrite.Add(vehicle))
        val saved = ok(store.commitGameChange(add))
        val alternatives = listOf(
            vehicle.copy(rarity = Rarity.RARE),
            vehicle.copy(levelProgressPercent = 1),
            vehicle.copy(acquisitionCost = GameMoney(1)),
            vehicle.copy(acquisitionSource = VehicleAcquisitionSource.PURCHASE_DELIVERY),
        )
        for (alternative in alternatives) {
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGameChange(add.copy(vehicle = VehicleWrite.Add(alternative))))
        }
        assertEquals(listOf(vehicle), ok(store.listOwnedVehicles()))
        assertEquals(saved, ok(store.commitGameChange(add)))
    }

    @Test fun `진행량만 다른 변경의 동일 요청은 충돌하고 오래된 revision도 거절한다`() {
        val vehicle = RentalContractFixtures.paidGachaVehicle
        val store = InMemoryGameChangeStore(listOf(vehicle))
        val write = VehicleWrite.Update(vehicle.ownedVehicleId, vehicle.revision, 2, VehicleLifecycle.OWNED, 80)
        val command = change("progress", write)
        val result = ok(store.commitGameChange(command))
        assertEquals(ContractResult.Success(result), store.commitGameChange(command))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGameChange(command.copy(vehicle = write.copy(levelProgressPercent = 81))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGameChange(command.copy(requestId = RequestId("stale-progress"))))
        assertEquals(80, ok(store.listOwnedVehicles()).single().levelProgressPercent)
    }

    @Test fun `처분 명령으로 진행량을 바꾸면 금액과 차량 모두 미확정으로 남는다`() {
        val vehicle = RentalContractFixtures.paidGachaVehicle
        val store = InMemoryGameChangeStore(listOf(vehicle), initialBalance = GameMoney(100))
        val invalid = change("invalid-disposal-progress", VehicleWrite.Update(vehicle.ownedVehicleId, vehicle.revision,
            vehicle.level, VehicleLifecycle.CONSUMED, vehicle.levelProgressPercent + 1)).copy(
            money = MoneyChange(MoneyDirection.CREDIT, GameMoney(50)),
        )
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), store.commitGameChange(invalid))
        assertEquals(listOf(vehicle), ok(store.listOwnedVehicles()))
        val balance = ok(store.commitGameChange(GameChange(RequestId("balance-probe"), TransactionKind.GARAGE,
            InputSource.SIMULATION, RentalContractFixtures.ruleVersion, money = MoneyChange(MoneyDirection.CREDIT, GameMoney(0))))).balance
        assertEquals(GameMoney(100), balance)
    }


    @Test fun `판매 폐차 소비는 성장값과 취득 정보를 모두 보존한다`() {
        val vehicle = RentalContractFixtures.paidGachaVehicle
        for (lifecycle in listOf(VehicleLifecycle.SOLD, VehicleLifecycle.SCRAPPED, VehicleLifecycle.CONSUMED)) {
            val store = InMemoryGameChangeStore(listOf(vehicle))
            val command = change("dispose-${lifecycle.name}", VehicleWrite.Update(vehicle.ownedVehicleId,
                vehicle.revision, vehicle.level, lifecycle, vehicle.levelProgressPercent))
            val result = ok(store.commitGameChange(command))
            assertEquals(vehicle.copy(lifecycle = lifecycle, revision = Revision(1)), result.changedVehicle)
            assertTrue(ok(store.listOwnedVehicles()).isEmpty())
            assertEquals(result, ok(store.commitGameChange(command)))
        }
    }

}
