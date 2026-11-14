package io.drivegrowth.app.ui.garage

import android.content.Intent
import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.core.contract.MAX_VEHICLE_LEVEL
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.progression.VehicleAction

@Suppress("DEPRECATION")
class MapVehicleDetailViewTest : InstrumentationTestCase() {
    private fun waitForLayout(view: View) {
        var state = ""
        repeat(150) {
            instrumentation.waitForIdleSync()
            var ready = false
            onUi {
                ready = view.isAttachedToWindow && view.isLaidOut && !view.isLayoutRequested &&
                    view.width > 0 && view.height > 0
                state = "display=${view.display?.displayId}, attached=${view.isAttachedToWindow}, " +
                    "laidOut=${view.isLaidOut}, requested=${view.isLayoutRequested}, " +
                    "size=${view.width}x${view.height}, window=${view.windowVisibility}, focus=${view.hasWindowFocus()}"
            }
            if (ready) return
            Thread.sleep(20)
        }
        fail("패널의 실제 배치 대기 실패: $state")
    }

    private fun onUi(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    fun testPanelKeepsMapVisibleAndFitsPortraitAndShortWideWindows() = onUi {
        val view = MapVehicleDetailView(instrumentation.targetContext) {}
        val state = GaragePreviewStates.normal
        val id = state.data.vehicles.last().vehicle.ownedVehicleId
        view.render(state, id, controlsEnabled = true, actionsBlocked = false)
        val panel = view.findViewWithTag<View>("map:vehicle-detail")

        layout(view, 840, 1100)
        assertEquals(dp(view, 12), panel.left)
        assertEquals(dp(view, 1100) - dp(view, 12), panel.bottom)
        assertTrue(panel.height <= dp(view, 660))
        assertTrue(texts(view).any { "긴 차종명" in it })
        assertTrue(texts(view).contains("활동 상태 확인 불가"))

        layout(view, 1000, 340)
        assertEquals(dp(view, 1000) - dp(view, 12), panel.right)
        assertEquals(dp(view, 1000) / 2 - dp(view, 12), panel.width)
        assertEquals(dp(view, 340) - 2 * dp(view, 12), panel.height)
        view.findViewWithTag<Button>("map:vehicle-manage").performClick()
        layout(view, 320, 360)
        val scroll = view.findViewWithTag<ScrollView>("map:vehicle-detail-scroll")
        assertTrue(scroll.getChildAt(0).height > scroll.height)
        assertTrue(view.findViewWithTag<Button>("map:vehicle-close").bottom <= panel.height)
    }

    fun testSummaryKeepsKnownAndMissingFactsReadOnlyInTheNarrowPanel() = onUi {
        val view = MapVehicleDetailView(instrumentation.targetContext) { fail("요약에서 차량 관리 요청 발생") }
        val state = GaragePreviewStates.normal
        val selected = state.data.vehicles.first().copy(accidentLabel = null, modelPriceLabel = null)
        view.render(state.copy(data = state.data.copy(vehicles = listOf(selected))),
            selected.vehicle.ownedVehicleId, controlsEnabled = true, actionsBlocked = false)
        layout(view, 320, 360)

        val labels = texts(view)
        assertTrue(labels.any { it.startsWith("취득 기준액 · ") })
        assertTrue(labels.contains("게임 내 사고 이력 확인 불가"))
        assertTrue(labels.contains("게임 차종 가격 확인 불가"))
        for (tag in listOf("map:vehicle-artwork", "map:vehicle-exp")) {
            val info = view.findViewWithTag<View>(tag)
            assertNotNull(info)
            assertTrue(info.width > 0 && info.height > 0)
            assertFalse(info.isClickable)
            assertFalse(info.isFocusable)
        }
        val close = view.findViewWithTag<Button>("map:vehicle-close")
        val panel = view.findViewWithTag<View>("map:vehicle-detail")
        assertTrue(close.bottom <= panel.height)
    }

    fun testCloseAndManageAreEntirelyVisibleInAttachedCompactPanel() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val state = GaragePreviewStates.normal
            val view = MapVehicleDetailView(activity) {}
            onUi {
                val root = FrameLayout(activity)
                root.addView(view, FrameLayout.LayoutParams(dp(view, 320), dp(view, 340)))
                activity.setContentView(root)
                view.render(state, state.data.vehicles.first().vehicle.ownedVehicleId, true, false)
            }
            waitForLayout(view)
            onUi {
                assertEntirelyVisible(view.findViewWithTag("map:vehicle-close"))
                val manage = view.findViewWithTag<Button>("map:vehicle-manage")
                assertEntirelyVisible(manage)
                assertTrue(manage.performClick())
            }
            instrumentation.waitForIdleSync()
            onUi { assertEntirelyVisible(view.findViewWithTag("map:vehicle-close")) }
        } finally {
            onUi { activity.finish() }
        }
    }

    private fun assertEntirelyVisible(view: View) {
        val bounds = Rect()
        assertTrue(view.getGlobalVisibleRect(bounds))
        assertEquals(view.width, bounds.width())
        assertEquals(view.height, bounds.height())
    }

    fun testSelectionChangesAndRestrictionInvalidateOldActions() = onUi {
        val events = mutableListOf<GarageEvent>()
        val view = MapVehicleDetailView(instrumentation.targetContext, events::add)
        val state = GaragePreviewStates.normal
        val first = state.data.vehicles[0]
        val second = state.data.vehicles[3]
        view.render(state, first.vehicle.ownedVehicleId, controlsEnabled = true, actionsBlocked = false)
        view.findViewWithTag<Button>("map:vehicle-manage").performClick()
        val previous = view.findViewWithTag<Button>(VehicleAction.SELL)
        view.render(state, second.vehicle.ownedVehicleId, controlsEnabled = true, actionsBlocked = false)
        previous.performClick()
        assertTrue(events.isEmpty())
        assertTrue(texts(view).contains("수리 필요"))
        assertTrue(texts(view).contains("75%"))
        assertTrue(hasContentDescription(view, "레벨 진행량 · 75%"))

        view.findViewWithTag<Button>("map:vehicle-manage").performClick()
        val repair = view.findViewWithTag<Button>(VehicleAction.REPAIR)
        assertFalse(view.findViewWithTag<Button>(VehicleAction.SELL).isEnabled)
        repair.performClick()
        repair.performClick()
        assertEquals(listOf(GarageEvent.RequestAction(second.vehicle.ownedVehicleId, second.vehicle.revision, VehicleAction.REPAIR)), events)
        assertFalse(view.handleBack())

        view.render(state, second.vehicle.ownedVehicleId, controlsEnabled = false, actionsBlocked = true)
        repair.performClick()
        assertEquals(View.GONE, view.visibility)
        assertTrue(texts(view).isEmpty())
        assertEquals(1, events.size)
        view.render(state, second.vehicle.ownedVehicleId, controlsEnabled = true, actionsBlocked = false)
        assertFalse(view.findViewWithTag<Button>("map:vehicle-manage").isEnabled)
        view.completeRequest()
        assertTrue(view.findViewWithTag<Button>("map:vehicle-manage").isEnabled)
    }

    fun testFreshRevisionUsesHostActionAndBackClosesOnlyPresentation() = onUi {
        val events = mutableListOf<GarageEvent>()
        val view = MapVehicleDetailView(instrumentation.targetContext, events::add)
        val state = GaragePreviewStates.normal
        val sample = state.data.vehicles.first()
        val id = sample.vehicle.ownedVehicleId
        view.render(state, id, controlsEnabled = true, actionsBlocked = false)
        view.findViewWithTag<Button>("map:vehicle-manage").performClick()
        val previous = view.findViewWithTag<Button>(VehicleAction.SELL)
        val updated = sample.copy(vehicle = sample.vehicle.copy(revision = Revision(5)))
        view.render(state.copy(data = state.data.copy(vehicles = listOf(updated))), id,
            controlsEnabled = true, actionsBlocked = false)
        previous.performClick()
        assertTrue(events.isEmpty())
        view.findViewWithTag<Button>(VehicleAction.SELL).performClick()
        assertEquals(listOf(GarageEvent.RequestAction(id, Revision(5), VehicleAction.SELL)), events)
        view.completeRequest()
        assertTrue(view.handleBack())
        assertNotNull(view.findViewWithTag<Button>("map:vehicle-manage"))
        assertTrue(view.handleBack())
        assertEquals(GarageEvent.CloseDetail, events.last())
        assertEquals(View.GONE, view.visibility)
    }

    fun testLoadingReadFailureAndRemovedVehicleDoNotOfferManagement() = onUi {
        val events = mutableListOf<GarageEvent>()
        val view = MapVehicleDetailView(instrumentation.targetContext, events::add)
        val state = GaragePreviewStates.normal
        val sample = state.data.vehicles.first()
        val id = sample.vehicle.ownedVehicleId
        val maxLevel = sample.copy(vehicle = sample.vehicle.copy(level = MAX_VEHICLE_LEVEL))
        view.render(state.copy(data = state.data.copy(vehicles = listOf(maxLevel))), id,
            controlsEnabled = true, actionsBlocked = true)
        assertTrue(texts(view).contains("최대 레벨"))
        assertFalse(view.findViewWithTag<Button>("map:vehicle-manage").isEnabled)

        view.render(GarageScreenState.Loading, id, controlsEnabled = true, actionsBlocked = false)
        assertNull(view.findViewWithTag<Button>("map:vehicle-manage"))
        view.render(GaragePreviewStates.error, id, controlsEnabled = true, actionsBlocked = false)
        val retry = view.findViewWithTag<Button>("map:vehicle-retry")
        retry.performClick()
        retry.performClick()
        assertEquals(listOf(GarageEvent.Retry), events)
        assertNull(view.findViewWithTag<Button>("map:vehicle-manage"))
        view.render(GaragePreviewStates.removed, id, controlsEnabled = true, actionsBlocked = false)
        assertTrue(texts(view).contains("선택한 차량을 찾을 수 없어요"))
        assertNull(view.findViewWithTag<Button>("map:vehicle-manage"))
    }

    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()

    private fun layout(view: View, width: Int, height: Int) {
        val widthPx = dp(view, width)
        val heightPx = dp(view, height)
        view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, widthPx, heightPx)
    }

    private fun hasContentDescription(view: View, label: String): Boolean =
        view.contentDescription?.toString() == label ||
            (view is ViewGroup && (0 until view.childCount).any { hasContentDescription(view.getChildAt(it), label) })

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
