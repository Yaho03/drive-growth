package io.drivegrowth.app.ui.shop

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.FrameLayout
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.RentalMapDirection
import io.drivegrowth.app.runtime.rental.RentalMapEvent
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity

@Suppress("DEPRECATION")
class RentalMapMotionViewTest : InstrumentationTestCase() {
    fun testSameVisibleSlotAnimatesOnceAndBackKeepsCommittedDisplayInNarrowWindow() {
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        val host = instrumentation.startActivitySync(Intent(context, CollectionScrollTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) as CollectionScrollTestActivity
        lateinit var view: ShopView
        val initial = ShopPreviewStates.normal().copy(origin = ShopOrigin.STORED)
        val identity = GameIdentity(ProfileId("fixture"), InputSource.SIMULATION)
        val vehicle = initial.vehicles.first().id
        val departure = RentalMapEvent(identity, RequestId("observed"), vehicle, RentalMapDirection.DEPARTURE, VehicleActivity.RENTED)
        val returning = departure.copy(direction = RentalMapDirection.RETURN, activity = VehicleActivity.NEEDS_REPAIR)
        fun state(revision: Int, activity: VehicleActivity) = initial.copy(revision = Revision(revision),
            vehicles = initial.vehicles.map { if (it.id == vehicle) it.copy(activity = activity) else it })
        fun scene() = checkNotNull(view.findViewWithTag<ShopSceneView>("shop:scene"))
        try {
            onUi {
                val root = FrameLayout(host)
                view = ShopView(host) { fail("지도 장식에서 거래 요청이 나가면 안 됩니다") }
                val width = (440 * host.resources.displayMetrics.density).toInt()
                val height = (360 * host.resources.displayMetrics.density).toInt()
                root.addView(view, FrameLayout.LayoutParams(width, height))
                host.setContentView(root)
                view.render(initial, true)
            }
            await { view.hasWindowFocus() && scene().isLaidOut }
            onUi {
                val enabled = ValueAnimator.areAnimatorsEnabled()
                if (enabled) assertNotNull(scene().visibleRentalSlot(vehicle))
                view.queueRentalMapEvents(identity, listOf(departure))
                view.render(state(13, VehicleActivity.RENTED), true)
                assertEquals(enabled, scene().showingRentalMapMotion)
                // HOME readers may publish another Loading/Content pair in the same main turn.
                view.render(ShopScreenState.Loading, true)
                view.render(state(14, VehicleActivity.RENTED), true)
                assertEquals(enabled, scene().showingRentalMapMotion)
                view.handleBack()
                assertFalse(scene().showingRentalMapMotion)
                assertEquals(VehicleActivity.RENTED, scene().model.vehicles.first().activity)
                view.queueRentalMapEvents(identity, listOf(departure))
                view.render(state(15, VehicleActivity.RETURNING), true)
                assertFalse(scene().showingRentalMapMotion)
            }
            await { scene().isLaidOut }
            onUi {
                val enabled = ValueAnimator.areAnimatorsEnabled()
                view.queueRentalMapEvents(identity, listOf(returning))
                view.render(state(16, VehicleActivity.NEEDS_REPAIR), true)
                assertEquals(enabled, scene().showingRentalMapMotion)
                view.render(state(16, VehicleActivity.NEEDS_REPAIR), false)
                assertNull(view.findViewWithTag<View>("shop:scene"))
                view.render(state(16, VehicleActivity.NEEDS_REPAIR), true)
                assertFalse(scene().showingRentalMapMotion)
                assertEquals(VehicleActivity.NEEDS_REPAIR, scene().model.vehicles.first().activity)
            }
        } finally { onUi { host.finish() } }
    }

    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onUi { ready = condition() }
            if (ready) return
            SystemClock.sleep(20L)
        }
        fail("지도 배치와 창 포커스를 확인하지 못했습니다")
    }
}
