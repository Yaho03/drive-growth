package io.drivegrowth.app.ui.shop

import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.rental.Facility

@Suppress("DEPRECATION")
class ShopMapOverlayDeviceTest : InstrumentationTestCase() {
    fun testFacilityMenuUsesBottomSheetInPortraitAndSidePanelInLandscape() = onUi {
        val context = instrumentation.targetContext
        val sheet = ShopMapSheet(context, TextView(context), 0, true, {})

        assertPanelPosition(sheet, 840, 1060, 8, atBottom = true)
        assertPanelPosition(sheet, 1360, 900, 8, atBottom = false)
    }

    fun testExpansionUsesAvailableWidthWhenTheSidePanelDoesNotFit() = onUi {
        val context = instrumentation.targetContext
        val interaction = ShopInteraction({})
        interaction.render(ShopPreviewStates.normal(), true)
        val overlay = ShopMapOverlayView(context, {}, {})
        overlay.render(ShopMapTarget.Expansion, interaction)

        assertPanelPosition(overlay, 698, 425, 16, atBottom = true)
        assertPanelPosition(overlay, 840, 1060, 16, atBottom = true)
        assertPanelPosition(overlay, 1360, 900, 16, atBottom = false)
    }

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
        assertEquals(0, closes)
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

    fun testFailedAndEmptyQueriesCanRetryOnceWithoutDroppingPendingRequest() = onUi {
        val events = mutableListOf<ShopEvent>()
        val interaction = ShopInteraction({ events += it })
        val overlay = ShopMapOverlayView(instrumentation.targetContext, {}, {})
        val state = ShopPreviewStates.normal()
        interaction.render(state, true)
        overlay.render(ShopMapTarget.Expansion, interaction)
        overlay.findViewWithTag<Button>("shop:map-confirm").performClick()
        val request = (events.single() as ShopEvent.Submit).request

        for (problem in listOf(ShopScreenState.Failure("조회 실패"), ShopScreenState.Empty("조회 결과 없음"))) {
            interaction.render(problem, true)
            overlay.render(ShopMapTarget.Expansion, interaction)
            val retry = overlay.findViewWithTag<Button>("shop:map-retry")
            val previousRetries = events.count { it == ShopEvent.Retry }
            assertTrue(retry.isEnabled)
            retry.performClick()
            retry.performClick()
            assertEquals(previousRetries + 1, events.count { it == ShopEvent.Retry })
            assertEquals(problem, interaction.state)
            assertEquals(request, interaction.uiState.pending)
            overlay.render(ShopMapTarget.Expansion, interaction)
            assertFalse(overlay.findViewWithTag<Button>("shop:map-retry").isEnabled)
            interaction.completeRetry()
        }
    }

    fun testPendingCloseAndBackKeepTheOriginalRequest() = onUi {
        val events = mutableListOf<ShopEvent>()
        val interaction = ShopInteraction({ events += it })
        var closes = 0
        val overlay = ShopMapOverlayView(instrumentation.targetContext, { closes++ }, {})
        interaction.render(ShopPreviewStates.normal(), true)
        overlay.render(ShopMapTarget.Expansion, interaction)
        overlay.findViewWithTag<Button>("shop:map-confirm").performClick()
        val request = (events.single() as ShopEvent.Submit).request
        assertNotNull(interaction.selection)
        overlay.render(ShopMapTarget.Expansion, interaction)
        val close = overlay.findViewWithTag<Button>("shop:map-close")
        assertEquals("닫기", close.text.toString())
        close.performClick()
        assertEquals(1, closes)
        assertEquals(request, interaction.uiState.pending)

        overlay.render(ShopMapTarget.Expansion, interaction)
        assertTrue(overlay.handleBack())
        assertEquals(2, closes)
        assertEquals(request, interaction.uiState.pending)
        assertEquals(1, events.size)
    }

    fun testNarrowAttachedOverlayScrollsWhileCloseRemainsVisible() {
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var container: FrameLayout
            lateinit var overlay: ShopMapOverlayView
            val width = (300 * host.resources.displayMetrics.density).toInt()
            val height = (240 * host.resources.displayMetrics.density).toInt()
            onUi {
                container = FrameLayout(host)
                host.setContentView(container)
                overlay = ShopMapOverlayView(host, {}, {})
                val state = ShopPreviewStates.normal()
                val expansion = state.expansion!!
                val interaction = ShopInteraction({})
                interaction.render(state.copy(expansion = expansion.copy(
                    condition = "긴 시설 조건과 현재 달성 상태를 확인합니다. ".repeat(14),
                )), true)
                overlay.render(ShopMapTarget.Expansion, interaction)
                container.addView(overlay, FrameLayout.LayoutParams(width, height))
            }
            waitForLayout(overlay)
            val before = android.graphics.Rect()
            onUi {
                assertTrue(overlay.isAttachedToWindow)
                val close = overlay.findViewWithTag<Button>("shop:map-close")
                val scroll = overlay.findViewWithTag<ScrollView>("shop:map-scroll")
                assertTrue(close.getGlobalVisibleRect(before))
                assertEquals(close.height, before.height())
                assertTrue(scroll.canScrollVertically(1))
                scroll.scrollTo(0, scroll.getChildAt(0).height)
            }
            instrumentation.waitForIdleSync()
            onUi {
                val scroll = overlay.findViewWithTag<ScrollView>("shop:map-scroll")
                assertTrue(scroll.scrollY > 0)
                val close = overlay.findViewWithTag<Button>("shop:map-close")
                val after = android.graphics.Rect()
                assertTrue(close.getGlobalVisibleRect(after))
                assertEquals(before, after)
                val confirm = overlay.findViewWithTag<Button>("shop:map-confirm")
                val visibleConfirm = android.graphics.Rect()
                assertTrue(confirm.getGlobalVisibleRect(visibleConfirm))
                assertEquals(confirm.width, visibleConfirm.width())
                assertEquals(confirm.height, visibleConfirm.height())
            }
        } finally {
            onUi { host.finish() }
        }
    }

    private fun assertPanelPosition(root: FrameLayout, widthDp: Int, heightDp: Int, gapDp: Int, atBottom: Boolean) {
        val style = ShopStyle(root.context)
        val width = style.dp(widthDp)
        val height = style.dp(heightDp)
        val gap = style.dp(gapDp)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val panel = root.getChildAt(0)
        val size = "${widthDp}×${heightDp}dp"
        assertEquals("right gap at $size", gap, width - panel.right)
        if (atBottom) {
            assertEquals("left gap at $size", gap, panel.left)
            assertEquals("bottom gap at $size", gap, height - panel.bottom)
        } else {
            assertTrue("side panel at $size", panel.left > width / 2)
            assertTrue("vertically centered at $size", kotlin.math.abs(panel.top - (height - panel.bottom)) <= 1)
        }
    }

    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString("\n") { text(view.getChildAt(it)) }
        else -> ""
    }

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

    private fun onUi(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}
