package io.drivegrowth.app.host.shop

import io.drivegrowth.app.host.*
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.progression.GachaRules
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.*
import org.junit.Test

class ShopHostMapperTest {
    private val id = VehicleId("test-car")
    private val content = GameContentSnapshot(VehicleCatalogSnapshot(ContentVersion("test-content"), listOf(VehicleDefinition(id, "시험 차량"))),
        RuleVersion("test-rule"), true, emptyList(), GachaRules())
    private val car = OwnedVehicle(OwnedVehicleId("owned"), id, 2, Revision(0), VehicleLifecycle.OWNED,
        Rarity.RARE, 25, GameMoney(250), VehicleAcquisitionSource.GACHA)
    private fun snapshot() = HostSnapshot(content, InputSource.SIMULATION, GameMoney(750), listOf(car), emptyList())
    @Test fun mapsActualMoneyAndVehicleWithoutInventingShopAuthority() {
        val state = ShopHostMapper().map(HostQueryState.Content(snapshot()), Revision(2)) as ShopScreenState.Content
        assertEquals(GameMoney(750), state.balance); assertEquals(1, state.ownedCount)
        assertEquals(car.ownedVehicleId, state.vehicles.single().id)
        assertNull(state.level); assertNull(state.capacity)
        assertEquals(ShopOrigin.STORED, state.origin)
        assertTrue(state.facilities.all { it.option.availability == ShopAvailability.UNKNOWN && it.option.command == null })
        assertNull(state.expansion!!.command); assertTrue(state.decorations.isEmpty())
        assertTrue(state.vehicles.single().status.contains("미확인"))
    }
    @Test fun verifiedStoredCapacityAndActivitiesAreMappedWithMatchingVersions() {
        val data = snapshot().copy(garageState = ContractResult.Success(StoredGarageState(InputSource.SIMULATION,
            content.catalog.contentVersion, content.ruleVersion,
            listOf(StoredVehicleState(car, ContractResult.Success(VehicleActivity.RENTED), ContractResult.Success(0))), ContractResult.Success(8))))
        val state = ShopHostMapper().map(HostQueryState.Content(data), Revision(3)) as ShopScreenState.Content
        assertEquals(8, state.capacity); assertEquals("대여 중", state.vehicles.single().status)
    }
    @Test fun mismatchedStoredVehicleOrVersionIsRejected() {
        for (stored in listOf(
            StoredGarageState(InputSource.VEHICLE, content.catalog.contentVersion, content.ruleVersion, emptyList(), ContractResult.Success(3)),
            StoredGarageState(InputSource.SIMULATION, content.catalog.contentVersion, content.ruleVersion,
                listOf(StoredVehicleState(car.copy(revision = Revision(9)), ContractResult.Success(VehicleActivity.IDLE), ContractResult.Success(0))), ContractResult.Success(3)))) {
            assertTrue(ShopHostMapper().map(HostQueryState.Content(snapshot().copy(garageState = ContractResult.Success(stored))), Revision(1)) is ShopScreenState.Failure)
        }
    }
    @Test fun invalidOwnershipAndFailedQueriesDoNotBecomeEmptySuccess() {
        assertTrue(ShopHostMapper().map(HostQueryState.Content(snapshot().copy(ownedVehicles = listOf(car, car))), Revision(0)) is ShopScreenState.Failure)
        assertTrue(ShopHostMapper().map(HostQueryState.Failure(ContractError.STORAGE_FAILURE), Revision(0)) is ShopScreenState.Failure)
        assertEquals(ShopScreenState.Loading, ShopHostMapper().map(HostQueryState.Loading, Revision(0)))
    }
}
