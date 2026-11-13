package io.drivegrowth.app.ui.shop

import android.test.InstrumentationTestCase
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import io.drivegrowth.app.ui.garage.ActionPresentation
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.app.ui.garage.GaragePreviewStates
import io.drivegrowth.app.ui.garage.VehiclePresentation
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.garage.VehicleRef

@Suppress("DEPRECATION")
class ShopVehicleFocusDeviceTest : InstrumentationTestCase() {
    fun testFocusUsesOwnedIdAndCurrentRevisionAndReturnsWhenRemoved() = onUi {
        val events = mutableListOf<GarageEvent.RequestAction>()
        val first = GaragePreviewStates.normal.data.vehicles.first().let { shown ->
            shown.copy(actions = mapOf(VehicleAction.LEVEL_UP to ActionPresentation(true, "비용 확인")))
        }
        val second = first.copy(vehicle = first.vehicle.copy(ownedVehicleId = OwnedVehicleId("focus-second")))
        val before = ShopPreviewStates.normal().copy(vehicles = listOf(first, second).map(::mapVehicle))
        val view = ShopView(instrumentation.targetContext) { fail("지도 표시가 가게 거래를 요청했습니다") }
        view.onFocusedVehicleAction = events::add
        view.render(before, true, focusVehicles = listOf(first, second))

        mapButton(view, second).performClick()
        assertEquals(second.vehicle.ownedVehicleId, view.focusedVehicleId)
        val stale = view.findViewWithTag<Button>(VehicleAction.LEVEL_UP)
        mapButton(view, first).performClick()
        stale.performClick()
        assertTrue(events.isEmpty())
        assertEquals(first.vehicle.ownedVehicleId, view.focusedVehicleId)

        view.render(ShopScreenState.Loading, true, focusVehicles = null)
        assertNotNull(view.findViewWithTag<View>("shop:focus-panel"))
        assertEquals(first.vehicle.ownedVehicleId, view.focusedVehicleId)
        assertTrue(events.isEmpty())

        val updated = first.copy(vehicle = first.vehicle.copy(revision = Revision(first.vehicle.revision.value + 1)))
        val next = before.copy(revision = Revision(before.revision.value + 1))
        view.render(next, true, focusVehicles = listOf(updated, second))
        assertEquals(first.vehicle.ownedVehicleId, view.focusedVehicleId)
        view.findViewWithTag<Button>(VehicleAction.LEVEL_UP).performClick()
        assertEquals(listOf(GarageEvent.RequestAction(first.vehicle.ownedVehicleId, updated.vehicle.revision, VehicleAction.LEVEL_UP)), events)

        val previousAction = view.findViewWithTag<Button>(VehicleAction.LEVEL_UP)
        view.render(next, false, focusVehicles = listOf(updated, second))
        previousAction.performClick()
        assertEquals(1, events.size)
        assertNull(view.findViewWithTag<View>("shop:focus-panel"))
        view.render(next, true, focusVehicles = listOf(updated, second))
        view.handleBack()
        assertNull(view.focusedVehicleId)
        mapButton(view, first).performClick()
        view.render(next.copy(revision = Revision(next.revision.value + 1), vehicles = listOf(mapVehicle(second))),
            true, focusVehicles = listOf(second))
        assertNull(view.focusedVehicleId)
        assertNull(view.findViewWithTag<View>("shop:focus-panel"))
    }

    fun testCompactPanelKeepsLongNamesAndUsesOnlyMatchingRestingFacts() = onUi {
        val context = instrumentation.targetContext
        val rests = mutableListOf<FacilityControlsEvent>()
        val first = GaragePreviewStates.normal.data.vehicles.first().copy(
            name = "긴 차량 이름이 잘리지 않는지 확인하는 캐스퍼 일렉트릭",
        )
        val second = first.copy(vehicle = first.vehicle.copy(ownedVehicleId = OwnedVehicleId("focus-other")))
        val state = ShopPreviewStates.normal().copy(vehicles = listOf(first, second).map(::mapVehicle))
        val view = ShopView(context) { fail("지도에서 가게 거래를 요청했습니다") }
        view.onFocusedVehicleAction = { fail("지도 이동에서 차량 관리를 요청했습니다") }
        view.onFocusedRest = rests::add
        val id = first.vehicle.ownedVehicleId
        val stale = FacilityControlsEvent.SetResting(4, VehicleRef(id, Revision(first.vehicle.revision.value + 1)), false, true)
        view.render(state, true, listOf(first, second), focusRest = mapOf(id to stale))
        mapButton(view, first).performClick()
        assertFalse(view.findViewWithTag<Button>("shop:focus-rest").isEnabled)

        val current = stale.copy(vehicle = VehicleRef(id, first.vehicle.revision))
        view.render(state, true, listOf(first, second), focusRest = mapOf(id to current))
        val density = context.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (340 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val panel = view.findViewWithTag<ShopVehicleFocusPanel>("shop:focus-panel")
        val actions = panel.findViewWithTag<ScrollView>("shop:focus-actions")
        val choose = panel.findViewWithTag<Button>("shop:focus-choose:${second.vehicle.ownedVehicleId.value}")
        val back = panel.findViewWithTag<Button>("shop:focus-back")
        assertTrue(back.width > 0 && back.height > 0)
        assertTrue(actions.height > 0 && actions.bottom <= panel.height && actions.top >= back.bottom)
        assertTrue(actions.getChildAt(0).height > actions.height)
        assertTrue(choose.text.contains(second.name))
        view.findViewWithTag<Button>("shop:focus-rest").performClick()
        assertEquals(listOf(current), rests)
        choose.performClick()
        assertEquals(second.vehicle.ownedVehicleId, view.focusedVehicleId)
        assertFalse(view.findViewWithTag<Button>("shop:focus-rest").isEnabled)
    }

    private fun mapVehicle(shown: VehiclePresentation) = ShopVehicle(
        shown.vehicle.ownedVehicleId, shown.name, "대기 중", shown.vehicle.vehicleId, shown.vehicle.rarity, shown.activity,
    )

    private fun mapButton(view: ShopView, shown: VehiclePresentation): View =
        view.findViewWithTag("shop:map-vehicle:${shown.vehicle.ownedVehicleId.value}")

    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}
