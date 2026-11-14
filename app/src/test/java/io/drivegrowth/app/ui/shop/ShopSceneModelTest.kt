package io.drivegrowth.app.ui.shop

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.Facility
import org.junit.Assert.*
import org.junit.Test
class ShopSceneModelTest {
    private fun content(level: Int? = 1, capacity: Int? = 3, vehicles: List<ShopVehicle> = emptyList(), facilities: List<ShopFacility> = emptyList()) =
        ShopScreenState.Content(Revision(1), "가게", level, null, capacity, null, ShopAppearance("간판", 0xff3266ff.toInt()), vehicles, facilities, null, emptyList(), ShopOrigin.STORED)
    @Test fun missingCapacityAndLevelStayUnknown() { val scene=shopSceneModel(content(null,null));assertNull(scene.slots) }
    @Test fun suppliedCapacityAndLevelChangeSceneWithoutEconomicAction() { assertEquals(3,shopSceneModel(content()).slots);val expanded=shopSceneModel(content(2,6));assertEquals(6,expanded.slots) }
    @Test fun growthArtUsesConfirmedLevelAndCompatibleCapacity() {
        assertEquals(TownGrowthArt.LEVEL_1, TownGrowthArt.matching(1, 3))
        assertEquals(TownGrowthArt.LEVEL_2, TownGrowthArt.matching(2, 5))
        assertEquals(TownGrowthArt.LEVEL_3, TownGrowthArt.matching(3, 7))
        assertEquals(TownGrowthArt.LEVEL_4, TownGrowthArt.matching(4, 10))
        assertEquals("pixel/town-growth/town-lv4.png", TownGrowthArt.LEVEL_4.assetPath)
        assertNull(TownGrowthArt.matching(null, 5))
        assertNull(TownGrowthArt.matching(2, null))
        assertNull(TownGrowthArt.matching(2, 6))
        assertNull(TownGrowthArt.matching(5, 10))
        assertEquals(6, shopSceneModel(content(2, 6)).slots)
    }
    @Test fun onlyInstalledFacilityGetsConstructedAsset() {
        val facilities=Facility.entries.mapIndexed { i,f -> ShopFacility(f,ShopOption("$i", "시설", "조건", "효과", if(i==0)ShopAvailability.INSTALLED else ShopAvailability.LOCKED)) }
        val scene = shopSceneModel(content(facilities = facilities))
        assertEquals(ShopAvailability.INSTALLED, scene.facilityStates[Facility.entries[0]])
        assertEquals(ShopAvailability.LOCKED, scene.facilityStates[Facility.entries[1]])
        assertEquals(ShopAvailability.UNKNOWN, shopSceneModel(content()).facilityStates[Facility.FUEL_STATION])
    }
    @Test fun largeSuppliedListsUseBoundedSceneAndExplicitRemainder() {
        val cars=(1..20).map{ShopVehicle(OwnedVehicleId("$it"),"차량$it","대기")};val scene=shopSceneModel(content(capacity=30,vehicles=cars))
        assertEquals(10,scene.slots);assertEquals(10,scene.vehicles.size);assertEquals(10,scene.additionalVehicles)
    }
    @Test fun providedVehicleDetailsDoNotBecomeInventedOwnedCount() {
        val car = ShopVehicle(OwnedVehicleId("one"), "차량", "상태 미제공")
        val scene = shopSceneModel(content(capacity = null, vehicles = listOf(car)))
        assertTrue(scene.vehicles.isEmpty())
        assertEquals(1, scene.additionalVehicles)
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

    @Test
    fun mapCoversWideAndPortraitWindowsWithoutStretchingOrPanningPastEdges() {
        for ((width, height) in listOf(1360 to 400, 320 to 600, 936 to 416, 840 to 1184)) {
            val left = 100f
            val top = 40f
            val right = 1500f
            val bottom = 990f
            val initial = ShopMapCamera.cover(width, height, left, top, right, bottom, ShopMapOffset())
            assertEquals(maxOf(width / (right - left), height / (bottom - top)), initial.scale, .001f)
            for (offset in listOf(ShopMapOffset(), ShopMapOffset(9000f, -9000f), ShopMapOffset(-9000f, 9000f))) {
                val camera = ShopMapCamera.cover(width, height, left, top, right, bottom, offset)
                assertEquals(initial.scale, camera.scale, .001f)
                assertTrue(camera.x + left * camera.scale <= .001f)
                assertTrue(camera.y + top * camera.scale <= .001f)
                assertTrue(camera.x + right * camera.scale >= width - .001f)
                assertTrue(camera.y + bottom * camera.scale >= height - .001f)
            }
        }
    }

    @Test fun focusKeepsTheCompleteSubjectOutsideThePanelInSupportedWindows() {
        val subjects = listOf(
            ShopFocusRegion(92f, 0f, 513f, 309f),
            ShopFocusRegion(1150f, 365f, 1536f, 700f),
            ShopFocusRegion.vehicle(townSlots.first()),
            ShopFocusRegion.vehicle(townSlots.last()),
        )
        for ((width, height, density) in listOf(
            Triple(1700, 1185, 1.25f), Triple(960, 720, 1.375f),
            Triple(840, 1184, 1f), Triple(320, 600, 1f),
        )) {
            for (region in subjects) {
                val panel = ShopFocusLayout.fit(width, height, density)
                if (panel.side) {
                    assertEquals(width / 2, panel.panelWidth + panel.margin)
                    assertEquals(height, panel.maxPanelHeight + panel.margin * 2)
                }
                val overview = ShopMapCamera.cover(width, height, 0f, 0f, 1536f, 1024f, ShopMapOffset())
                val camera = panel.camera(width, height, region, overview.scale)
                val left = camera.x + region.left * camera.scale
                val right = camera.x + region.right * camera.scale
                val top = camera.y + region.top * camera.scale
                val bottom = camera.y + region.bottom * camera.scale
                val size = "${width}×${height} ${region.panelOnLeft}"
                assertTrue(size, left >= -.01f && top >= -.01f)
                assertTrue(size, right <= width + .01f && bottom <= height + .01f)
                if (!panel.side) assertTrue(size, bottom <= height - panel.maxPanelHeight - panel.margin + .01f)
                else if (region.panelOnLeft) assertTrue(size, left >= panel.panelWidth + panel.margin - .01f)
                else assertTrue(size, right <= width - panel.panelWidth - panel.margin + .01f)
            }
        }
        assertFalse(subjects[0].panelOnLeft)
        assertTrue(subjects[1].panelOnLeft)
        assertFalse(subjects[2].panelOnLeft)
        assertTrue(subjects[3].panelOnLeft)
    }

}
