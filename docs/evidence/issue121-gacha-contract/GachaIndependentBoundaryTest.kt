package io.drivegrowth.core.contract

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 독립 검토 사본 전용. 메모리 계약이며 제품 DB·SQLite 검증이 아니다. */
class GachaIndependentBoundaryTest {
    private val model = VehicleId("review-model")
    private fun authority(capacity: Int = 3, price: Long = 500, pity: Int = 30) = GachaAuthority(
        InputSource.SIMULATION, ContentVersion("review-content"), RuleVersion("review-rules"),
        1, capacity, GameMoney(price), pity, Rarity.RARE, setOf(model), Rarity.entries.toSet())
    private fun vehicle(id: String, price: Long = 500) = OwnedVehicle(OwnedVehicleId(id), model, 1, Revision(0),
        VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(price), VehicleAcquisitionSource.GACHA)
    private fun command(a: GachaAuthority, id: String = "draw", revision: Int = 0, counter: Int = 0,
        award: GachaAward = GachaAward(vehicle(id, a.purchasePrice.units), counter + 1, false)) =
        GachaCommand(RequestId(id), a.source, a.contentVersion, a.ruleVersion, Revision(revision), a.shopLevel,
            a.garageCapacity, counter, GachaTicketUse.PURCHASED, award)
    private fun <T> ok(result: ContractResult<T>): T = (result as ContractResult.Success).value
    private fun owned(store: GameChangeStore) = ok(store.listOwnedVehicles())
    private fun state(store: GachaChangeStore) = ok(store.readGachaState()).progress
    private fun race(first: () -> ContractResult<*>, second: () -> ContractResult<*>): List<ContractResult<*>> {
        val barrier = CyclicBarrier(2); val pool = Executors.newFixedThreadPool(2)
        return try { pool.invokeAll(listOf(first, second).map { call -> Callable {
            barrier.await(5, TimeUnit.SECONDS); call()
        } }, 5, TimeUnit.SECONDS).map { it.get() } } finally { pool.shutdownNow() }
    }
    @Test fun legacyAndGachaRacingTheSameIdCanCommitOnlyOneNamespace() {
        val a = authority(); val store = InMemoryGameChangeStore(emptyList(), GameMoney(2000),
            gachaState = GachaState(a, GachaProgress(Revision(0), 0, 1)))
        val c = command(a)
        val legacy = GameChange(c.requestId, TransactionKind.GARAGE, a.source, a.ruleVersion,
            MoneyChange(MoneyDirection.DEBIT, GameMoney(500)), VehicleWrite.Add(c.award.vehicle))
        val results = race({ store.commitGameChange(legacy) }, { store.commitGacha(c) })
        assertEquals(1, results.count { it is ContractResult.Success })
        assertEquals(1, results.count { it == ContractResult.Failure(ContractError.STATE_CONFLICT) })
        assertEquals(1, owned(store).size)
        if (results[1] is ContractResult.Success) {
            assertEquals(results[1], store.commitGacha(c)); assertEquals(Revision(1), state(store).revision)
        } else {
            assertEquals(results[0], store.commitGameChange(legacy)); assertEquals(Revision(0), state(store).revision)
        }
        val probe = ok(store.commitGameChange(GameChange(RequestId("probe"), TransactionKind.GARAGE, a.source,
            a.ruleVersion, MoneyChange(MoneyDirection.DEBIT, GameMoney(1)))))
        assertEquals(GameMoney(1499), probe.balance)
    }
    @Test fun legacyAndGachaRacingTheLastSlotShareCurrentOccupancyAndWallet() {
        val a = authority(capacity = 1); val store = InMemoryGameChangeStore(emptyList(), GameMoney(2000),
            gachaState = GachaState(a, GachaProgress(Revision(0), 0, 1)))
        val c = command(a)
        val legacy = GameChange(RequestId("legacy"), TransactionKind.GARAGE, a.source, a.ruleVersion,
            MoneyChange(MoneyDirection.DEBIT, GameMoney(500)), VehicleWrite.Add(vehicle("legacy")))
        val results = race({ store.commitGameChange(legacy) }, { store.commitGacha(c) })
        assertEquals(1, results.count { it is ContractResult.Success })
        assertEquals(1, results.count { it == ContractResult.Failure(ContractError.CAPACITY_EXCEEDED) })
        assertEquals(1, owned(store).size)
        val probe = ok(store.commitGameChange(GameChange(RequestId("probe"), TransactionKind.GARAGE, a.source,
            a.ruleVersion, MoneyChange(MoneyDirection.DEBIT, GameMoney(1)))))
        assertEquals(GameMoney(1499), probe.balance)
    }
    @Test fun soldVehicleFreesCapacityButItsInstanceIdCannotBeReused() {
        val a = authority(capacity = 1); val sold = vehicle("sold").copy(lifecycle = VehicleLifecycle.SOLD, revision = Revision(4))
        val input = mutableListOf(sold)
        val store = InMemoryGameChangeStore(input, GameMoney(1000), gachaState = GachaState(a, GachaProgress(Revision(0), 0, 1)))
        input.clear()
        val reused = command(a, award = GachaAward(vehicle("sold"), 1, false))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGacha(reused))
        assertEquals(GachaProgress(Revision(0), 0, 1), state(store)); assertTrue(owned(store).isEmpty())
        val first = ok(store.commitGacha(command(a)))
        assertEquals(GameMoney(500), first.settlement.balance)
        assertEquals(1, owned(store).size)
    }
    @Test fun maximumPricePityRevisionAndTicketValuesDoNotOverflowOrBlockReplay() {
        val a = authority(capacity = 1, price = Long.MAX_VALUE, pity = Int.MAX_VALUE)
        val store = InMemoryGameChangeStore(emptyList(), GameMoney(Long.MAX_VALUE),
            gachaState = GachaState(a, GachaProgress(Revision(Int.MAX_VALUE - 1), Int.MAX_VALUE - 1, Long.MAX_VALUE)))
        val award = GachaAward(vehicle("max", Long.MAX_VALUE).copy(rarity = Rarity.RARE), 0, true)
        val c = command(a, revision = Int.MAX_VALUE - 1, counter = Int.MAX_VALUE - 1, award = award)
        val first = ok(store.commitGacha(c))
        assertEquals(GameMoney(0), first.settlement.balance)
        assertEquals(GachaProgress(Revision(Int.MAX_VALUE), 0, Long.MAX_VALUE), first.progress)
        assertEquals(first, ok(store.commitGacha(c)))
        ok(store.commitGameChange(GameChange(RequestId("dispose-max"), TransactionKind.GARAGE, a.source, a.ruleVersion,
            vehicle = VehicleWrite.Update(award.vehicle.ownedVehicleId, Revision(0), 1, VehicleLifecycle.SOLD, 0))))
        val next = command(a, "next", revision = Int.MAX_VALUE)
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), store.commitGacha(next))
        assertEquals(first.progress, state(store)); assertTrue(owned(store).isEmpty())
        assertEquals(first, ok(store.commitGacha(c)))
    }
    @Test fun everyNestedAwardChangeConflictsBeforeValidationAndPublicCollectionsStayFrozen() {
        val a = authority(); val store = InMemoryGameChangeStore(emptyList(), GameMoney(2000),
            gachaState = GachaState(a, GachaProgress(Revision(0), 0, 1)))
        val c = command(a); val first = ok(store.commitGacha(c)); val v = c.award.vehicle
        for (changed in listOf(v.copy(ownedVehicleId = OwnedVehicleId("other")), v.copy(vehicleId = VehicleId("locked")),
            v.copy(level = 2), v.copy(levelProgressPercent = 1), v.copy(revision = Revision(1)),
            v.copy(lifecycle = VehicleLifecycle.SOLD), v.copy(acquisitionSource = VehicleAcquisitionSource.PURCHASE_DELIVERY))) {
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGacha(c.copy(award = c.award.copy(vehicle = changed))))
        }
        assertEquals(first, ok(store.commitGacha(c))); assertEquals(first.progress, state(store))
        assertThrows(UnsupportedOperationException::class.java) { (ok(store.readGachaState()).authority.eligibleRarities as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (owned(store) as MutableList).clear() }
        assertEquals(c.award.vehicle, owned(store).single())
    }
    @Test fun missingGachaAuthorityKeepsLegacyBehaviorAndDoesNotPretendToSupportGacha() {
        val store = InMemoryGameChangeStore(emptyList()); val a = authority()
        val legacy = GameChange(RequestId("legacy"), TransactionKind.GARAGE, InputSource.VEHICLE, a.ruleVersion,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(7)), VehicleWrite.Add(vehicle("legacy")))
        val first = ok(store.commitGameChange(legacy)); assertEquals(GameMoney(7), first.balance)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), store.readGachaState())
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), store.commitGacha(command(a)))
        assertEquals(first, ok(store.commitGameChange(legacy))); assertEquals(1, owned(store).size)
    }
}
