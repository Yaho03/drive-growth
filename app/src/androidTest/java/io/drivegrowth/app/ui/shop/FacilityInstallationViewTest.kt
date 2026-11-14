package io.drivegrowth.app.ui.shop

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.facility.FacilityCommand
import io.drivegrowth.app.host.facility.FacilityReceipt
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.Facility
import io.drivegrowth.core.rental.FacilityOffer

@Suppress("DEPRECATION")
class FacilityInstallationViewTest : InstrumentationTestCase() {
    fun testNarrowFacilityResultsKeepExistingButtonReachableAndStopOnBack() {
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        val activity = instrumentation.startActivitySync(Intent(context, CollectionScrollTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) as CollectionScrollTestActivity
        lateinit var root: FrameLayout
        try {
            onUi {
                root = FrameLayout(activity)
                activity.setContentView(root)
            }
            await { root.hasWindowFocus() && root.isLaidOut }
            for ((index, lot) in Facility.entries.withIndex()) {
                lateinit var view: ShopView
                lateinit var request: ShopRequest
                var armed = false
                var acknowledgements = 0
                val identity = GameIdentity(ProfileId("installation-view"), InputSource.SIMULATION)
                val offer = FacilityOffer(lot, RuleVersion("trial-facility"), true, GameMoney(100))
                val option = ShopOption("fixture-facility", lot.name, "조건", "효과", ShopAvailability.AVAILABLE,
                    ShopCommand.BuyFacility(offer), offer.price, offer.ruleVersion, offer.trial)
                val state = ShopPreviewStates.normal().copy(origin = ShopOrigin.STORED,
                    facilities = listOf(ShopFacility(lot, option)))
                onUi {
                    view = ShopView(activity) { event ->
                        if (event is ShopEvent.Submit) {
                            request = event.request
                            armed = view.submitInstallation(identity, request)
                        }
                    }
                    view.facilityPanel = Button(activity).apply {
                        tag = "facility:ack"
                        text = "결과 확인"
                        setOnClickListener { acknowledgements++ }
                    }
                    val width = ((if (index % 2 == 0) 320 else 480) * activity.resources.displayMetrics.density).toInt()
                    root.removeAllViews()
                    root.addView(view, FrameLayout.LayoutParams(width, -1))
                    view.render(state, true)
                }
                await { view.hasWindowFocus() && view.isLaidOut }
                onUi { view.showMapPanel(ShopMapTarget.FacilityLot(lot)) }
                await { view.findViewWithTag<Button>("shop:map-confirm")?.isShown == true }
                onUi {
                    assertTrue(view.findViewWithTag<Button>("shop:map-confirm").performClick())
                    assertEquals(ValueAnimator.areAnimatorsEnabled(), armed)
                    val settlement = Settlement(SettlementId("receipt-$lot"), Transaction(TransactionId("tx-$lot"), request.requestId,
                        TransactionKind.FACILITY, identity.source, offer.ruleVersion, MoneyChange(MoneyDirection.DEBIT, offer.price)),
                        GameMoney(2_300), null, EpochMillis(1))
                    val receipt = FacilityReceipt.Purchased(FacilityCommand.Buy(identity, request.requestId, ContentVersion("content"), offer), settlement)
                    view.confirmInstallation(request, receipt)
                    view.complete(request, ContractResult.Success(Unit))
                    view.render(ShopScreenState.Loading, true, null, focusRestRecovery = true)
                }
                await {
                    view.findViewWithTag<View>("facility:ack")?.let { it.isShown && it.getGlobalVisibleRect(Rect()) } == true
                }
                onUi {
                    val scene = view.findViewWithTag<ShopSceneView>("shop:scene")
                    assertEquals(armed, scene?.showingFacilityInstallation == true)
                    assertTrue(view.findViewWithTag<Button>("facility:ack").performClick())
                    assertEquals(1, acknowledgements)
                    if (armed) {
                        assertTrue(view.handleBack())
                        assertFalse(scene?.showingFacilityInstallation == true)
                        assertTrue(view.findViewWithTag<Button>("shop:reopen-facility-result").performClick())
                        assertTrue(view.findViewWithTag<Button>("facility:ack").isShown)
                    }
                    val installed = state.copy(revision = Revision(state.revision.value + 1), facilities =
                        listOf(ShopFacility(lot, option.copy(availability = ShopAvailability.INSTALLED, command = null))))
                    view.render(installed, true)
                    assertFalse(view.findViewWithTag<ShopSceneView>("shop:scene")?.showingFacilityInstallation == true)
                }
            }
        } finally {
            onUi { activity.finish() }
        }
    }

    private fun onUi(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3_000L
        do {
            var satisfied = false
            onUi { satisfied = condition() }
            if (satisfied) return
            SystemClock.sleep(20L)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("부착된 테스트 창의 조작 가능 상태를 기다렸습니다")
    }
}
