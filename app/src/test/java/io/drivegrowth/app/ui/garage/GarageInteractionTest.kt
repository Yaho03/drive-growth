package io.drivegrowth.app.ui.garage

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleAction
import org.junit.Assert.*
import org.junit.Test

class GarageInteractionTest {
    private val first = VehiclePresentation(
        OwnedVehicle(OwnedVehicleId("first"), VehicleId("hyundai_casper"), 3, Revision(7), VehicleLifecycle.OWNED),
        "캐스퍼", "#1", actions = mapOf(VehicleAction.SELL to ActionPresentation(true, "예상 지급액 확인 중")),
    )
    private val second = first.copy(vehicle = first.vehicle.copy(ownedVehicleId = OwnedVehicleId("second")), instanceLabel = "#2")
    private fun content(vehicles: List<VehiclePresentation> = listOf(first, second)) = GarageScreenState.Content(
        GarageContent(vehicles, "보유 2대 · 공간 확인 중", "시험 입력"),
    )

    @Test fun sameModelSelectsTheExactInstanceAndRevision() {
        val events = mutableListOf<GarageEvent>()
        val interaction = GarageInteraction(events::add)
        interaction.render(content(), true)
        interaction.select(second.vehicle.ownedVehicleId)
        assertEquals(second, interaction.selectedVehicle)
        assertTrue(interaction.request(VehicleAction.SELL))
        assertEquals(GarageEvent.RequestAction(second.vehicle.ownedVehicleId, Revision(7), VehicleAction.SELL), events.last())
    }

    @Test fun repeatedRequestsStayBlockedAcrossRendersUntilHostAcknowledges() {
        val events = mutableListOf<GarageEvent>()
        val interaction = GarageInteraction(events::add)
        interaction.render(content(), true)
        interaction.select(first.vehicle.ownedVehicleId)
        assertTrue(interaction.request(VehicleAction.SELL))
        interaction.render(content(), true)
        assertFalse(interaction.request(VehicleAction.SELL))
        assertEquals(1, events.filterIsInstance<GarageEvent.RequestAction>().size)
        assertEquals(2, (interaction.state as GarageScreenState.Content).data.vehicles.size)
        interaction.completeRequest()
        assertTrue(interaction.request(VehicleAction.SELL))
    }

    @Test fun disabledMissingRestrictedAndStaleSelectionCannotSendRequests() {
        val events = mutableListOf<GarageEvent>()
        val interaction = GarageInteraction(events::add)
        interaction.render(content(), true)
        interaction.select(first.vehicle.ownedVehicleId)
        assertFalse(interaction.request(VehicleAction.REPAIR))
        interaction.render(content(listOf(first.copy(actions = mapOf(VehicleAction.SELL to ActionPresentation(false, "대여 중"))))), true)
        assertFalse(interaction.request(VehicleAction.SELL))
        interaction.render(content(), false)
        assertFalse(interaction.request(VehicleAction.SELL))
        interaction.render(content(emptyList()), true)
        assertNull(interaction.selectedVehicle)
        assertEquals(first.vehicle.ownedVehicleId, interaction.selectedId)
        assertFalse(interaction.request(VehicleAction.SELL))
        assertTrue(events.none { it is GarageEvent.RequestAction })
    }

    @Test fun retryIsOnlyAvailableForErrorAndOnlyOncePerRender() {
        val events = mutableListOf<GarageEvent>()
        val interaction = GarageInteraction(events::add)
        interaction.render(GarageScreenState.Loading, true)
        assertFalse(interaction.retry())
        interaction.render(GarageScreenState.Error("읽기 실패"), true)
        assertTrue(interaction.retry())
        assertFalse(interaction.retry())
        interaction.render(GarageScreenState.Error("읽기 실패"), false)
        assertFalse(interaction.retry())
        assertEquals(listOf(GarageEvent.Retry), events)
    }

    @Test fun closingDetailDoesNotReleaseAnUnconfirmedRequest() {
        val interaction = GarageInteraction {}
        interaction.render(content(), true)
        interaction.select(first.vehicle.ownedVehicleId)
        interaction.request(VehicleAction.SELL)
        interaction.closeDetail()
        interaction.select(second.vehicle.ownedVehicleId)
        assertFalse(interaction.request(VehicleAction.SELL))
    }
    @Test fun staleButtonCannotRequestForAnotherInstanceOrRevision() {
        val events = mutableListOf<GarageEvent>()
        val interaction = GarageInteraction(events::add)
        interaction.render(content(), true)
        interaction.select(second.vehicle.ownedVehicleId)
        assertFalse(interaction.request(VehicleAction.SELL, first.vehicle.ownedVehicleId, Revision(7)))
        assertFalse(interaction.request(VehicleAction.SELL, second.vehicle.ownedVehicleId, Revision(6)))
        assertTrue(events.none { it is GarageEvent.RequestAction })
    }

    @Test fun hostCanRestoreSelectionIncludingAnInstanceThatDisappeared() {
        val events = mutableListOf<GarageEvent>()
        val interaction = GarageInteraction(events::add)
        interaction.restoreSelection(first.vehicle.ownedVehicleId)
        interaction.render(content(), true)
        assertEquals(first, interaction.selectedVehicle)
        interaction.render(content(emptyList()), true)
        assertNull(interaction.selectedVehicle)
        assertEquals(first.vehicle.ownedVehicleId, interaction.selectedId)
        assertTrue(events.isEmpty())
    }

}
