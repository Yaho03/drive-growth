package io.drivegrowth.app.ui.shop

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.Facility
import org.junit.Assert.*
import org.junit.Test
class ShopSceneModelTest {
    private fun content(level: Int? = 1, capacity: Int? = 3, vehicles: List<ShopVehicle> = emptyList(), facilities: List<ShopFacility> = emptyList()) =
        ShopScreenState.Content(Revision(1), "가게", level, null, capacity, null, ShopAppearance("간판", 0xff3266ff.toInt()), vehicles, facilities, null, emptyList(), ShopOrigin.STORED)
    @Test fun missingCapacityAndLevelStayUnknown() { val scene=shopSceneModel(content(null,null));assertNull(scene.slots);assertFalse(scene.expandedBuilding) }
    @Test fun suppliedCapacityAndLevelChangeSceneWithoutEconomicAction() { assertEquals(3,shopSceneModel(content()).slots);val expanded=shopSceneModel(content(2,6));assertEquals(6,expanded.slots);assertTrue(expanded.expandedBuilding) }
    @Test fun onlyInstalledFacilityGetsConstructedAsset() {
        val facilities=Facility.entries.mapIndexed { i,f -> ShopFacility(f,ShopOption("$i", "시설", "조건", "효과", if(i==0)ShopAvailability.INSTALLED else ShopAvailability.LOCKED)) }
        assertEquals(setOf(Facility.entries[0]),shopSceneModel(content(facilities=facilities)).installed)
    }
    @Test fun largeSuppliedListsUseBoundedSceneAndExplicitRemainder() {
        val cars=(1..20).map{ShopVehicle(OwnedVehicleId("$it"),"차량$it","대기")};val scene=shopSceneModel(content(capacity=30,vehicles=cars))
        assertEquals(12,scene.slots);assertEquals(12,scene.vehicles.size);assertEquals(8,scene.additionalVehicles)
    }
    @Test fun providedVehicleDetailsDoNotBecomeInventedOwnedCount() {
        val car=ShopVehicle(OwnedVehicleId("one"),"차량","상태 미제공");assertEquals(listOf(car),shopSceneModel(content(capacity=null,vehicles=listOf(car))).vehicles)
    }

    @Test fun pendingRequestBlocksModeChangeAndPreservesSavedScroll() {
        val c=content();val option=ShopOption("pending","처리","조건","효과",ShopAvailability.UNKNOWN)
        val pending=ShopRequest(RequestId("pending"),ShopSelection(c.revision,option),"attempt")
        val ui=ShopInteraction({ fail("복원은 명령을 보내면 안 됨") })
        ui.restoreUiState(ShopUiState(mode=ShopMode.VEHICLES,pending=pending,sceneScrollY=180,controlsScrollY=240))
        ui.render(c,true);ui.mode(ShopMode.OVERALL)
        assertEquals(ShopMode.VEHICLES,ui.uiState.mode);assertEquals(pending,ui.uiState.pending)
        assertEquals(180,ui.uiState.sceneScrollY);assertEquals(240,ui.uiState.controlsScrollY)
    }
}
