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
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity

@Suppress("DEPRECATION")
class GaragePdfDetailDeviceTest : InstrumentationTestCase() {
    fun testSharedRestrictionsAndReachableScrapInGarageAndMapDetail() {
        val activity = instrumentation.startActivitySync(Intent(
            instrumentation.targetContext, CollectionScrollTestActivity::class.java,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val fixture = GaragePreviewStates.normal
            val sharedReason = "반납이 끝나면 관리할 수 있어요."
            val distinctReason = "판매 조건을 확인할 수 없어요."
            val selected = fixture.data.vehicles.first().copy(
                activity = VehicleActivity.RETURNING,
                actions = VehicleAction.entries.associateWith {
                    ActionPresentation(false, if (it == VehicleAction.SELL) distinctReason else sharedReason)
                },
            )
            val state = GarageScreenState.Content(fixture.data.copy(vehicles = listOf(selected)))
            val id = selected.vehicle.ownedVehicleId
            val garage = GarageView(activity) { event ->
                assertFalse(event is GarageEvent.RequestAction)
            }.apply { showHeading = false }
            val map = MapVehicleDetailView(activity) { event ->
                assertFalse(event is GarageEvent.RequestAction)
            }
            val root = FrameLayout(activity)
            onUi { activity.setContentView(root) }
            for ((view, width, height) in listOf(Triple(garage, 320, 326), Triple(map, 564, 276))) {
                onUi {
                    root.removeAllViews()
                    val density = activity.resources.displayMetrics.density
                    root.addView(view, FrameLayout.LayoutParams((width * density).toInt(), (height * density).toInt()))
                    if (view === garage) {
                        garage.render(state, true)
                        garage.restoreSelection(id)
                    } else map.render(state, id, true, false)
                }
                instrumentation.waitForIdleSync()
                if (view === map) {
                    onUi { assertTrue(map.findViewWithTag<Button>("map:vehicle-manage").performClick()) }
                    instrumentation.waitForIdleSync()
                }
                onUi {
                    val prefix = if (view === garage) "garage:detail" else "map:vehicle-detail"
                    val scroll = checkNotNull(view.findViewWithTag<ScrollView>(if (view === garage) prefix else "$prefix-scroll"))
                    val close = checkNotNull(view.findViewWithTag<Button>(if (view === garage) "$prefix-close" else "map:vehicle-close"))
                    val scrap = checkNotNull(view.findViewWithTag<Button>(VehicleAction.SCRAP))
                    val sell = checkNotNull(view.findViewWithTag<Button>(VehicleAction.SELL))
                    val labels = descendants(scroll).filterIsInstance<TextView>().map { it.text.toString() }
                    assertEquals(1, labels.count { it == sharedReason })
                    assertEquals(1, labels.count { it == distinctReason })
                    assertFalse(scrap.isEnabled)
                    assertFalse(sell.isEnabled)
                    assertTrue(sharedReason in scrap.contentDescription.toString())
                    assertTrue(distinctReason in sell.contentDescription.toString())
                    val before = visibleBounds(close)
                    scroll.scrollTo(0, scroll.getChildAt(0).height)
                    assertTrue(scroll.scrollY > 0)
                    visibleBounds(scrap)
                    assertEquals(before, visibleBounds(close))
                }
            }
        } finally {
            onUi { activity.finish() }
        }
    }

    private fun visibleBounds(view: View): Rect = Rect().also {
        assertTrue(view.getGlobalVisibleRect(it))
        assertEquals(view.height, it.height())
        assertEquals(view.width, it.width())
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun onUi(block: () -> Unit) {
        var error: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (failure: Throwable) { error = failure } }
        error?.let { throw it }
    }
}
