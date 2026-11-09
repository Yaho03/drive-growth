package io.drivegrowth.app.host

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.ui.garage.CollectionScreenState
import io.drivegrowth.app.ui.garage.GarageScreenState
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.garage.CollectionState
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.UUID

/** 실제 SQLite 표적 준비. 전용 격리 패키지/이름 DB만 쓰고 제품 원장은 열지 않는다. */
@Suppress("DEPRECATION")
class GarageStoredHostStorageTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private lateinit var source: StoredHostSource
    private lateinit var first: OwnedVehicle
    private lateinit var second: OwnedVehicle
    private lateinit var sold: OwnedVehicle
    private val input = InputSource.SIMULATION
    private val mapper = GarageHostMapper()

    private fun <T> ok(value: ContractResult<T>): T {
        assertTrue(value.toString(), value is ContractResult.Success)
        return (value as ContractResult.Success).value
    }
    private fun open() = SqliteGameChangeStore(context, input, name)
    override fun setUp() {
        super.setUp()
        assertEquals("io.drivegrowth.app.garageread52", context.packageName)
        name = "garage-read-" + UUID.randomUUID() + ".db"
        source = StoredHostSource(context, name)
        val content = ok(source.read()).content
        first = OwnedVehicle(OwnedVehicleId("first"), content.catalog.vehicles.first().vehicleId, 4, Revision(0),
            VehicleLifecycle.OWNED, Rarity.RARE, 20, GameMoney(500), VehicleAcquisitionSource.GACHA)
        second = first.copy(ownedVehicleId = OwnedVehicleId("second"), rarity = Rarity.COMMON)
        sold = first.copy(ownedVehicleId = OwnedVehicleId("sold"), rarity = Rarity.LEGEND)
        open().use { store ->
            for (vehicle in listOf(first, second, sold)) ok(store.commitGameChange(GameChange(
                RequestId("add-" + vehicle.ownedVehicleId.value), TransactionKind.GARAGE, input, content.ruleVersion,
                if (vehicle == first) MoneyChange(MoneyDirection.CREDIT, GameMoney(10000)) else null, VehicleWrite.Add(vehicle))))
            ok(store.commitGameChange(GameChange(RequestId("sell"), TransactionKind.GARAGE, input, content.ruleVersion,
                vehicle = VehicleWrite.Update(sold.ownedVehicleId, sold.revision, sold.level, VehicleLifecycle.SOLD, sold.levelProgressPercent))))
            ok(store.recordDiscoveries(content.catalog, setOf(CollectionKey(first.vehicleId, Rarity.ADVANCED))))
        }
    }
    override fun tearDown() {
        if (::name.isInitialized) context.deleteDatabase(name)
        super.tearDown()
    }
    fun testActualReadAndReopenKeepDisposedCollectionDistinctInstancesAndGamePrice() {
        val before = ok(source.read())
        val reopened = ok(StoredHostSource(context, name).read())
        val shown = (mapper.garage(reopened) as GarageScreenState.Content).data
        assertEquals(listOf(first, second), shown.vehicles.map { it.vehicle })
        assertTrue(shown.vehicles.all { it.activity == VehicleActivity.IDLE && it.accidentLabel == "0" })
        val price = reopened.content.vehicles.single { it.vehicleId == first.vehicleId }.price.units
        assertTrue(shown.vehicles.all { it.modelPriceLabel == "$price 게임머니" })
        assertEquals("보유 2 / 3칸", shown.capacityLabel)
        assertTrue(shown.sourceLabel.contains("모의 원장"))
        val collection = mapper.collection(reopened, reopened.collectionHistory) as CollectionScreenState.Content
        assertEquals(CollectionState.OWNED, collection.entries.single { it.entry.key == CollectionKey(first.vehicleId, Rarity.LEGEND) }.entry.state)
        assertEquals(CollectionState.DISCOVERED, collection.entries.single { it.entry.key == CollectionKey(first.vehicleId, Rarity.ADVANCED) }.entry.state)
        assertEquals(before.balance, reopened.balance)
        assertEquals(before.unviewedSettlements, reopened.unviewedSettlements)
        assertTrue(shown.vehicles.all { it.actions.values.all { action -> !action.enabled } })
    }
    private fun completeAccident(): SettlementId {
        val content = ok(source.read()).content
        return open().use { store ->
            ok(store.rental.setResting(second.ownedVehicleId, true))
            val terms = RentalTerms(content.ruleVersion, SimulatedCustomer(30, 36), 0, EpochMillis(0), 15, GameMoney(0), RoundingMode.DOWN)
            assertEquals(first.ownedVehicleId, ok(store.rental.assign(RentalRequest(RequestId("r1"), first.vehicleId, 1),
                content.catalog, mapOf(first.vehicleId to RentalCategory.SMALL), mapOf(first.vehicleId to GameMoney(100)), terms)))
            ok(store.rental.account(RequestId("r1"), EpochMillis(0), EpochMillis(3600000), null))
            val receipt = ok(store.rental.settle(RequestId("r1")))
            assertTrue(receipt.accident)
            assertEquals(VehicleActivity.NEEDS_REPAIR, ok(store.rental.finishReturn(RequestId("r1"), EpochMillis(4500000))))
            ok(store.rental.expand(RequestId("expand"), ShopExpansion(content.ruleVersion, true, 1, 3, 6, 0, GameMoney(20))))
            receipt.settlement.settlementId
        }
    }
    fun testActualActivityAccidentAndExpandedCapacityReachTheExistingMapper() {
        completeAccident()
        val snapshot = ok(source.read())
        val shown = (mapper.garage(snapshot) as GarageScreenState.Content).data
        val firstRow = shown.vehicles.single { it.vehicle.ownedVehicleId == first.ownedVehicleId }
        val secondRow = shown.vehicles.single { it.vehicle.ownedVehicleId == second.ownedVehicleId }
        assertEquals(Revision(1), firstRow.vehicle.revision)
        assertEquals(VehicleActivity.NEEDS_REPAIR, firstRow.activity)
        assertEquals("1", firstRow.accidentLabel)
        assertEquals(VehicleActivity.IDLE, secondRow.activity)
        assertEquals("0", secondRow.accidentLabel)
        assertEquals("보유 2 / 6칸", shown.capacityLabel)
        assertTrue(firstRow.actions.values.all { !it.enabled })
    }
    fun testMissingCompletedSettlementIsAnAccidentErrorInsteadOfZero() {
        val settlementId = completeAccident()
        // 격리 DB의 저장 누락 주입이다. 제품 SQL/저장 구현을 수정하는 경로가 아니다.
        open().use { store -> ok(store.atomic { db ->
            db.delete("settlements", "settlement_id=?", arrayOf(settlementId.value))
            ContractResult.Success(Unit)
        }) }
        val snapshot = ok(source.read())
        val details = ok(snapshot.garageState).vehicles.single { it.vehicle.ownedVehicleId == first.ownedVehicleId }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), details.accidents)
        val row = (mapper.garage(snapshot) as GarageScreenState.Content).data.vehicles.single { it.vehicle.ownedVehicleId == first.ownedVehicleId }
        assertNull(row.accidentLabel)
        assertEquals(VehicleActivity.NEEDS_REPAIR, row.activity)
        assertTrue(row.informationNote.orEmpty().contains("STORAGE_FAILURE"))
    }
}
