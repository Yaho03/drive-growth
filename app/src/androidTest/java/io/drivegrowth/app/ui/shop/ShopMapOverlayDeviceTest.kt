package io.drivegrowth.app.ui.shop

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.rental.Facility

@Suppress("DEPRECATION")
class ShopMapOverlayDeviceTest : InstrumentationTestCase() {
    fun testExpansionSendsOnlyCurrentOfferAndWaitsForStoredRefresh() = onUi {
        val events = mutableListOf<ShopEvent>()
        val state = ShopPreviewStates.normal()
        val interaction = ShopInteraction({ events += it })
        val overlay = ShopMapOverlayView(instrumentation.targetContext, {}, {})
        interaction.render(state, true)
        overlay.render(ShopMapTarget.Expansion, interaction, mapOf("expand" to GameMoney(900)))
        assertTrue(text(overlay).contains("5 → 7칸"))
        assertTrue(text(overlay).contains("900 게임머니"))
        assertTrue(events.isEmpty())

        val stale = overlay.findViewWithTag<Button>("shop:map-confirm")
        val revised = state.copy(revision = Revision(state.revision.value + 1))
        interaction.render(revised, true)
        stale.performClick()
        assertTrue(events.isEmpty())

        overlay.render(ShopMapTarget.Expansion, interaction)
        val confirm = overlay.findViewWithTag<Button>("shop:map-confirm")
        confirm.performClick()
        confirm.performClick()
        val request = (events.single() as ShopEvent.Submit).request
        assertEquals(ShopSelection(revised.revision, revised.expansion!!), request.selection)
        assertEquals(5, (interaction.state as ShopScreenState.Content).capacity)
        interaction.complete(request, ContractResult.Success(Unit))
        overlay.render(ShopMapTarget.Expansion, interaction)
        assertFalse(overlay.findViewWithTag<Button>("shop:map-confirm").isEnabled)
        assertEquals(5, (interaction.state as ShopScreenState.Content).capacity)
    }

    fun testLockedFacilityAndDecorationPreviewStayReadOnlyUntilExplicitConfirm() = onUi {
        val events = mutableListOf<ShopEvent>()
        val state = ShopPreviewStates.normal()
        val interaction = ShopInteraction({ events += it })
        var closes = 0
        val overlay = ShopMapOverlayView(instrumentation.targetContext, { closes++ }, {})
        interaction.render(state, true)
        overlay.render(ShopMapTarget.FacilityLot(Facility.CAR_WASH), interaction)
        assertTrue(text(overlay).contains("잠김"))
        assertTrue(text(overlay).contains("가게 레벨 3 필요"))
        assertNull(overlay.findViewWithTag<Button>("shop:map-confirm"))

        overlay.render(ShopMapTarget.Decorations, interaction)
        overlay.findViewWithTag<Button>("shop:map-preview:blue-sign").performClick()
        assertEquals("blue-sign", interaction.selection?.option?.id)
        assertEquals(state.appearance, (interaction.state as ShopScreenState.Content).appearance)
        overlay.render(ShopMapTarget.Decorations, interaction)
        assertTrue(text(overlay).contains("파란 가게"))
        val oldConfirm = overlay.findViewWithTag<Button>("shop:map-confirm")
        assertTrue(overlay.handleBack())
        oldConfirm.performClick()
        assertNull(interaction.selection)
        assertEquals(1, closes)
        assertTrue(events.isEmpty())

        overlay.render(ShopMapTarget.Decorations, interaction)
        overlay.findViewWithTag<Button>("shop:map-preview:blue-sign").performClick()
        overlay.render(ShopMapTarget.Decorations, interaction)
        val restricted = overlay.findViewWithTag<Button>("shop:map-confirm")
        interaction.render(state, false)
        restricted.performClick()
        overlay.render(ShopMapTarget.Decorations, interaction)
        assertEquals(View.GONE, overlay.visibility)
        assertTrue(events.isEmpty())

        interaction.render(state, true)
        overlay.render(ShopMapTarget.Decorations, interaction)
        overlay.findViewWithTag<Button>("shop:map-confirm").performClick()
        val request = (events.single() as ShopEvent.Submit).request
        assertEquals("blue-sign", request.selection.option.id)
        assertEquals(state.appearance, (interaction.state as ShopScreenState.Content).appearance)
    }

    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
        else -> ""
    }

    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}
