package io.drivegrowth.app.ui.shop

import android.test.InstrumentationTestCase
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import android.widget.TextView
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
        openManagement(view)
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
        openManagement(view)
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
        val sharedExplanation = "누르면 비용과 결과를 먼저 보여 드려요"
        val repairReason = "현재 활동에서는 이 관리를 할 수 없습니다"
        val first = GaragePreviewStates.normal.data.vehicles.first().copy(
            name = "긴 차량 이름이 잘리지 않는지 확인하는 캐스퍼 일렉트릭",
            actions = mapOf(
                VehicleAction.LEVEL_UP to ActionPresentation(true, sharedExplanation),
                VehicleAction.USE_MATERIAL to ActionPresentation(true, sharedExplanation),
                VehicleAction.REPAIR to ActionPresentation(false, repairReason),
            ),
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
        openManagement(view)
        assertFalse(view.findViewWithTag<Button>("shop:focus-rest").isEnabled)

        val current = stale.copy(vehicle = VehicleRef(id, first.vehicle.revision))
        view.render(state, true, listOf(first, second), focusRest = mapOf(id to current))
        val density = context.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (340 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val panel = view.findViewWithTag<LinearLayout>("map:vehicle-detail")
        val actions = panel.findViewWithTag<ScrollView>("map:vehicle-detail-scroll")
        val back = panel.findViewWithTag<Button>("map:vehicle-close")
        assertTrue(back.width > 0 && back.height > 0)
        assertTrue(actions.height > 0 && actions.bottom <= panel.height)
        val actionRows = actions.getChildAt(0) as LinearLayout
        assertTrue(actionRows.height > actions.height)
        val rows = (0 until actionRows.childCount).map(actionRows::getChildAt)
        assertTrue(rows.filterIsInstance<TextView>().any { it.text.toString().contains(first.name) })
        val repair = panel.findViewWithTag<Button>(VehicleAction.REPAIR)
        assertFalse(repair.isEnabled)
        assertEquals(repairReason, (rows[rows.indexOf(repair) + 1] as TextView).text.toString())
        view.findViewWithTag<Button>("shop:focus-rest").performClick()
        assertEquals(listOf(current), rests)
        mapButton(view, second).performClick()
        assertEquals(second.vehicle.ownedVehicleId, view.focusedVehicleId)
        openManagement(view)
        assertFalse(view.findViewWithTag<Button>("shop:focus-rest").isEnabled)
    }

    fun testGarageFailureKeepsFocusAndDisablesManagementUntilFreshFactsArrive() = onUi {
        val vehicle = GaragePreviewStates.normal.data.vehicles.first()
        val home = ShopPreviewStates.normal().copy(vehicles = listOf(mapVehicle(vehicle)))
        val view = ShopView(instrumentation.targetContext) { fail("조회 상태가 가게 거래를 요청했습니다") }
        val actions = mutableListOf<GarageEvent.RequestAction>()
        val rests = mutableListOf<FacilityControlsEvent>()
        view.onFocusedVehicleAction = actions::add
        view.onFocusedRest = rests::add
        val id = vehicle.vehicle.ownedVehicleId
        val rest = FacilityControlsEvent.SetResting(4, VehicleRef(id, vehicle.vehicle.revision), false, true)
        view.render(home, true, listOf(vehicle), focusRest = mapOf(id to rest))
        mapButton(view, vehicle).performClick()
        openManagement(view)
        val oldAction = view.findViewWithTag<Button>(VehicleAction.LEVEL_UP)
        val oldRest = view.findViewWithTag<Button>("shop:focus-rest")
        assertTrue(oldAction.isEnabled)
        assertTrue(oldRest.isEnabled)

        view.render(home, true, listOf(vehicle), focusRest = mapOf(id to rest), focusError = "차량 정보를 읽지 못했어요")
        assertEquals(id, view.focusedVehicleId)
        assertEquals("차량 정보를 읽지 못했어요", view.findViewWithTag<TextView>("shop:focus-query-status").text.toString())
        assertNull(view.findViewWithTag<Button>(VehicleAction.LEVEL_UP))
        assertNull(view.findViewWithTag<Button>("shop:focus-rest"))
        oldAction.performClick()
        oldRest.performClick()
        assertTrue(actions.isEmpty())
        assertTrue(rests.isEmpty())

        view.render(home, true, focusVehicles = null)
        assertEquals(view.context.getString(R.string.garage_loading),
            view.findViewWithTag<TextView>("shop:focus-query-status").text.toString())
        view.render(home, true, listOf(vehicle))
        assertNull(view.findViewWithTag<TextView>("shop:focus-query-status"))
        openManagement(view)
        assertTrue(view.findViewWithTag<Button>(VehicleAction.LEVEL_UP).isEnabled)
    }

    fun testRestoredFocusWaitsForParkedContentAndClearsWhenVehicleIsAbsent() = onUi {
        val vehicle = GaragePreviewStates.normal.data.vehicles.first()
        val id = vehicle.vehicle.ownedVehicleId
        val home = ShopPreviewStates.normal().copy(vehicles = listOf(mapVehicle(vehicle)))
        val view = ShopView(instrumentation.targetContext) { fail("선택 복원이 가게 거래를 요청했습니다") }
        view.onFocusedVehicleAction = { fail("선택 복원이 차량 관리 명령을 요청했습니다") }

        view.restoreFocusedVehicle(id)
        assertNull(view.findViewWithTag<View>("shop:focus-panel"))
        view.render(home, false, listOf(vehicle))
        assertNull(view.findViewWithTag<View>("shop:focus-panel"))
        view.render(home, true, listOf(vehicle))
        assertEquals(id, view.focusedVehicleId)
        assertNotNull(view.findViewWithTag<View>("shop:focus-panel"))
        view.render(home.copy(vehicles = emptyList()), true, emptyList())
        assertNull(view.focusedVehicleId)
        assertNull(view.findViewWithTag<View>("shop:focus-panel"))
    }

    private fun openManagement(view: ShopView) {
        view.findViewWithTag<Button>("map:vehicle-manage")?.let { assertTrue(it.performClick()) }
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
