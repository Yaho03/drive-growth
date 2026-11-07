package io.drivegrowth.app.host

import io.drivegrowth.app.ui.garage.*
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.*
import io.drivegrowth.core.progression.GachaRules
import io.drivegrowth.core.progression.VehicleAction
import org.junit.Assert.*
import org.junit.Test

class GarageHostMapperTest {
    private val model = VehicleId("fixture-model")
    private val content = GameContentSnapshot(VehicleCatalogSnapshot(ContentVersion("fixture-v1"),
        listOf(VehicleDefinition(model, "시험 차량"))), RuleVersion("fixture-rule"), true, emptyList(), GachaRules())
    private fun vehicle(id: String, rarity: Rarity = Rarity.RARE) = OwnedVehicle(OwnedVehicleId(id), model, 4,
        Revision(7), VehicleLifecycle.OWNED, rarity, 63, GameMoney(250), VehicleAcquisitionSource.GACHA)
    private fun data(vararg vehicles: OwnedVehicle) = HostSnapshot(content, InputSource.SIMULATION,
        GameMoney(700), vehicles.toList(), emptyList())
    private val mapper = GarageHostMapper()

    @Test fun mapsSameModelAsDistinctTypedOwnedVehiclesWithoutInventingDetails() {
        val first = vehicle("owned-a")
        val second = vehicle("owned-b", Rarity.LEGEND)
        val state = mapper.garage(data(first, second)) as GarageScreenState.Content
        assertEquals(listOf(first, second), state.data.vehicles.map { it.vehicle })
        assertEquals(listOf(Revision(7), Revision(7)), state.data.vehicles.map { it.vehicle.revision })
        assertNotEquals(state.data.vehicles[0].instanceLabel, state.data.vehicles[1].instanceLabel)
        assertNull(state.data.capacityLabel)
        assertTrue(state.data.sourceLabel.contains("모의 원장"))
        assertTrue(state.data.sourceLabel.contains("fixture-v1"))
        for (shown in state.data.vehicles) {
            assertNull(shown.activity)
            assertNull(shown.accidentLabel)
            assertNull(shown.modelPriceLabel)
            assertEquals(GameMoney(250), shown.vehicle.acquisitionCost)
            assertEquals(63, shown.vehicle.levelProgressPercent)
            for (action in VehicleAction.entries) {
                assertFalse(shown.actions.getValue(action).enabled)
                assertTrue(shown.actions.getValue(action).explanation.isNotBlank())
            }
        }
    }

    @Test fun emptyStoreRemainsEmptyAndInvalidReferencesBecomeAnError() {
        assertTrue((mapper.garage(data()) as GarageScreenState.Content).data.vehicles.isEmpty())
        val unknown = vehicle("bad").copy(vehicleId = VehicleId("not-in-catalog"))
        assertTrue(mapper.garage(data(unknown)) is GarageScreenState.Error)
        val duplicate = vehicle("same-id")
        assertTrue(mapper.garage(data(duplicate, duplicate)) is GarageScreenState.Error)
    }

    @Test fun unavailableHistoryDoesNotTurnCurrentOwnershipIntoPermanentCollection() {
        val state = mapper.collection(data(vehicle("current")), ContractResult.Failure(ContractError.DATA_UNAVAILABLE))
        assertTrue(state is CollectionScreenState.Error)
        assertTrue((state as CollectionScreenState.Error).message.contains("이력"))
    }

    @Test fun collectionUsesOnlyExplicitHistoricalKeysAndKeepsOwnedAfterDisposal() {
        val key = CollectionKey(model, Rarity.RARE)
        val discovered = CollectionKey(model, Rarity.ADVANCED)
        val history = CollectionHistory(setOf(key), setOf(discovered))
        val state = mapper.collection(data(), ContractResult.Success(history)) as CollectionScreenState.Content
        assertEquals(CollectionState.OWNED, state.entries.single { it.entry.key == key }.entry.state)
        assertEquals(CollectionState.DISCOVERED, state.entries.single { it.entry.key == discovered }.entry.state)
        assertNull(state.entries.first().unlockLabel)
        val noHistory = mapper.collection(data(vehicle("current")),
            ContractResult.Success(CollectionHistory(emptySet(), emptySet()))) as CollectionScreenState.Content
        assertTrue(noHistory.entries.all { it.entry.state == CollectionState.UNDISCOVERED })
    }
}
