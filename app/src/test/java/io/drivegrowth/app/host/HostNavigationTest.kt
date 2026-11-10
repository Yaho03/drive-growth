package io.drivegrowth.app.host

import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.progression.VehicleAction
import org.junit.Assert.*
import org.junit.Test

class HostNavigationTest {
    @Test fun restoredPreparationAndSelectionCloseBeforeParentAndRoot() {
        val id = OwnedVehicleId("saved-owned")
        val request = GarageEvent.RequestAction(id, Revision(5), VehicleAction.SELL)
        val navigation = HostNavigation(HostPage.GARAGE, id, request)
        assertSame(request, navigation.pendingGarageRequest)
        assertFalse(navigation.back())
        assertNull(navigation.pendingGarageRequest)
        assertEquals(id, navigation.selectedVehicleId)
        assertFalse(navigation.back())
        assertNull(navigation.selectedVehicleId)
        assertEquals(HostPage.GARAGE, navigation.page)
        assertFalse(navigation.back())
        assertEquals(HostPage.HOME, navigation.page)
        assertTrue(navigation.back())
    }

    @Test fun selectionSurvivesLeavingGarageWithoutClearingPendingRequest() {
        val id = OwnedVehicleId("saved-owned")
        val request = GarageEvent.RequestAction(id, Revision(9), VehicleAction.REPAIR)
        val navigation = HostNavigation(HostPage.GARAGE, id, request)
        navigation.page = HostPage.COLLECTION
        assertEquals(id, navigation.selectedVehicleId)
        assertEquals(request, navigation.pendingGarageRequest)
    }
    @Test fun gachaAwardDetailBackReturnsToGachaBeforeHome() {
        val navigation = HostNavigation()
        navigation.openAward(OwnedVehicleId("award"))
        assertEquals(HostPage.GARAGE, navigation.page)
        assertFalse(navigation.back()); assertNull(navigation.selectedVehicleId)
        assertFalse(navigation.back()); assertEquals(HostPage.GACHA, navigation.page)
        assertFalse(navigation.back()); assertEquals(HostPage.HOME, navigation.page)
        assertTrue(navigation.back())
    }
}
