package io.drivegrowth.core.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RentalContractConsumerTest {
    private val fixtures = RentalContractFixtures

    @Test
    fun `콘텐츠 소비자는 버전과 차종을 읽고 오류를 그대로 받는다`() {
        val catalog: VehicleCatalog = fixtures.catalog()
        val snapshot = (catalog.load() as ContractResult.Success).value
        assertEquals(fixtures.contentVersion, snapshot.contentVersion)
        assertEquals(fixtures.vehicleId, snapshot.vehicles.single().vehicleId)
        for (error in listOf(ContractError.DATA_UNAVAILABLE, ContractError.PERMISSION_DENIED, ContractError.POLICY_UNRESOLVED)) {
            assertEquals(ContractResult.Failure(error), fixtures.catalog(error).load())
        }
    }

    @Test
    fun `관측과 예상은 별도 타입이며 누락은 0과 다르다`() {
        val input = fixtures.distanceInput()
        val observed = (input.observed as Observation.Available).value
        val estimated = (input.estimated as Observation.Available).value
        assertEquals(ObservedDistance(30_000, InputSource.VEHICLE), observed)
        assertEquals(EstimatedRouteDistance(20_000, InputSource.SIMULATION), estimated)
        val missing = input.copy(observed = Observation.Missing(MissingReason.PERMISSION_DENIED))
        assertNotEquals(Observation.Available(ObservedDistance(0, InputSource.VEHICLE)), missing.observed)
        assertEquals(Observation.Missing(MissingReason.PERMISSION_DENIED), missing.observed)
    }

    @Test
    fun `같은 차종 개체는 독립적이며 오래된 revision은 거절한다`() {
        val store: GameChangeStore = fixtures.store()
        val initial = (store.listOwnedVehicles() as ContractResult.Success).value
        assertEquals(listOf(fixtures.firstVehicle, fixtures.secondVehicle), initial)
        val update = VehicleWrite.Update(fixtures.firstVehicle.ownedVehicleId, Revision(3), 2, VehicleLifecycle.OWNED, 0)
        val change = credit("first").copy(vehicle = update)
        val saved = (store.commitGameChange(change) as ContractResult.Success).value
        assertEquals(fixtures.firstVehicle.copy(level = 2, revision = Revision(4)), saved.changedVehicle)
        val after = (store.listOwnedVehicles() as ContractResult.Success).value
        assertEquals(fixtures.secondVehicle, after.single { it.ownedVehicleId == fixtures.secondVehicle.ownedVehicleId })
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGameChange(change.copy(requestId = RequestId("stale"))))
    }

    @Test
    fun `같은 요청은 다시 지급하지 않고 다른 내용은 충돌로 거절한다`() {
        val store: GameChangeStore = fixtures.store()
        val change = credit("r1")
        val saved = (store.commitGameChange(change) as ContractResult.Success).value
        assertEquals(GameMoney(100), saved.balance)
        assertEquals(fixtures.time, saved.confirmedAt)
        assertEquals(ContractResult.Success(saved), store.commitGameChange(change))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGameChange(credit("r1", 200)))
        assertEquals(GameMoney(200), (store.commitGameChange(credit("r2")) as ContractResult.Success).value.balance)
    }

    @Test
    fun `저장 실패 후 같은 요청을 재시도해도 개체와 재화는 한 번만 반영된다`() {
        val fake = fixtures.store()
        val store: GameChangeStore = fake
        val added = fixtures.firstVehicle.copy(ownedVehicleId = OwnedVehicleId("new"), revision = Revision(0))
        val change = credit("add").copy(vehicle = VehicleWrite.Add(added))
        fake.nextCommitFailure = ContractError.STORAGE_FAILURE
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.commitGameChange(change))
        assertEquals(listOf(fixtures.firstVehicle, fixtures.secondVehicle), (store.listOwnedVehicles() as ContractResult.Success).value)
        val saved = (store.commitGameChange(change) as ContractResult.Success).value
        assertEquals(added, saved.changedVehicle)
        assertEquals(GameMoney(100), saved.balance)
        assertEquals(ContractResult.Success(saved), store.commitGameChange(change))
        assertEquals(3, (store.listOwnedVehicles() as ContractResult.Success).value.size)
    }

    @Test
    fun `재화 부족과 Long 범위 초과는 실패 결과를 반환한다`() {
        val store: GameChangeStore = fixtures.store()
        val debit = credit("debit").copy(money = MoneyChange(MoneyDirection.DEBIT, GameMoney(1)))
        assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS), store.commitGameChange(debit))
        assertEquals(GameMoney(Long.MAX_VALUE), (store.commitGameChange(credit("max", Long.MAX_VALUE)) as ContractResult.Success).value.balance)
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), store.commitGameChange(credit("overflow", 1)))
    }

    @Test
    fun `시험용 난수 시드는 반복 실행에 같은 값을 제공한다`() {
        val first = fixtures.random()
        val second = fixtures.random()
        assertEquals(List(5) { first.nextInt() }, List(5) { second.nextInt() })
    }

    private fun credit(id: String, units: Long = 100) = GameChange(
        RequestId(id), TransactionKind.DELIVERY, InputSource.SIMULATION, fixtures.ruleVersion,
        money = MoneyChange(MoneyDirection.CREDIT, GameMoney(units)),
    )
}
