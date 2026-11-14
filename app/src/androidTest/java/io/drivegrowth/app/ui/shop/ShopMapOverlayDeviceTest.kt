package io.drivegrowth.app.ui.shop

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.MotionEvent
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
    fun testFacilityMenuIsCenteredAndWrapsShortContent() = onUi {
        val context = instrumentation.targetContext
        val sheet = ShopMapSheet(context, TextView(context), 0, true, {})

        assertCenteredPanel(sheet, 840, 1060, 420)
        assertCenteredPanel(sheet, 1360, 900, 420)
        assertTrue(sheet.getChildAt(0).height < ShopStyle(context).dp(180))
    }

    fun testExpansionCentersAndFitsNarrowPortraitAndLandscapeWindows() = onUi {
        val context = instrumentation.targetContext
        val interaction = ShopInteraction({})
        interaction.render(ShopPreviewStates.normal(), true)
        val overlay = ShopMapOverlayView(context, {}, {})
        overlay.render(ShopMapTarget.Expansion, interaction)

        assertCenteredPanel(overlay, 300, 240, 276)
        assertCenteredPanel(overlay, 840, 1060, 376)
        assertCenteredPanel(overlay, 1360, 900, 376)
    }

    fun testSelectedFacilityReservesTheOppositeSideAndBackRestoresOverview() = onUi {
        val view = ShopView(instrumentation.targetContext) { fail("지도 탐색으로 거래 요청 발생") }
        view.render(ShopPreviewStates.normal(), true)
        fun layout(width: Int, height: Int) {
            val density = view.resources.displayMetrics.density
            val w = (width * density).toInt()
            val h = (height * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            try { view.draw(android.graphics.Canvas(bitmap)) } finally { bitmap.recycle() }
        }
        fun camera() = checkNotNull(view.findViewWithTag<ShopSceneView>("shop:scene").displayedCamera())
        for ((w, h) in listOf(960 to 580, 360 to 720)) {
            layout(w, h)
            val before = camera()
            for (facility in listOf(Facility.FUEL_STATION, Facility.CAR_WASH)) {
                val target = ShopMapTarget.FacilityLot(facility)
                view.showMapPanel(target)
                layout(w, h)
                val overlay = view.findViewWithTag<FrameLayout>("shop:map-overlay")
                val panel = overlay.getChildAt(0)
                if (w > h) {
                    val gap = ShopStyle(view.context).dp(12)
                    assertEquals(view.width / 2 - gap, panel.width)
                    assertEquals(view.height - gap * 2, panel.height)
                }
                val bounds = checkNotNull(target.focusRegion())
                val selected = camera()
                val left = selected.x + bounds.left * selected.scale
                val right = selected.x + bounds.right * selected.scale
                val bottom = selected.y + bounds.bottom * selected.scale
                if (w < h) assertTrue(bottom <= panel.top + 1)
                else if (facility == Facility.CAR_WASH) assertTrue(left >= panel.right - 1)
                else assertTrue(right <= panel.left + 1)
                assertTrue(overlay.findViewWithTag<Button>("shop:map-close").isEnabled)
                assertTrue(view.handleBack())
                layout(w, h)
                assertEquals(before, camera())
            }
        }
    }

    fun testInsuranceCloseSurvivesAnUnchangedHostRenderBetweenDownAndUp() {
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val state = ShopPreviewStates.normal()
            lateinit var view: ShopView
            onUi {
                view = ShopView(host) { fail("닫기 입력으로 거래 요청 발생") }
                host.setContentView(view)
                view.render(state, true)
                view.showMapPanel(ShopMapTarget.FacilityLot(Facility.INSURANCE))
            }
            waitForLayout(view)
            onUi {
                val release = pressMapButton(view, "shop:map-close")
                view.render(state, true)
                view.completeRetry()
                release()
            }
            instrumentation.waitForIdleSync()
            onUi {
                assertNull("같은 화면을 다시 받은 뒤에도 보험 시설이 닫힌다",
                    view.findViewWithTag<View>("shop:map-overlay"))
            }
        } finally {
            onUi { host.finish() }
        }
    }

    fun testExpansionPressIsDiscardedWhenTheHostRestrictsControls() = onUi {
        val events = mutableListOf<ShopEvent>()
        val view = ShopView(instrumentation.targetContext) { events += it }
        val state = ShopPreviewStates.normal()
        view.render(state, true)
        layoutMapForTouch(view)
        view.showMapPanel(ShopMapTarget.Expansion)
        layoutMapForTouch(view)
        val release = pressMapButton(view, "shop:map-confirm")
        view.render(state, false)
        layoutMapForTouch(view)
        assertNull(view.findViewWithTag<View>("shop:map-overlay"))
        release()
        assertTrue("제한 전 확장 입력으로 거래 요청이 생기지 않는다", events.isEmpty())
    }

    private fun layoutMapForTouch(view: ShopView) {
        val width = ShopStyle(view.context).dp(960)
        val height = ShopStyle(view.context).dp(580)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun pressMapButton(view: ShopView, tag: String): () -> Unit {
        val button = view.findViewWithTag<Button>(tag)
        val bounds = android.graphics.Rect()
        assertTrue(button.getGlobalVisibleRect(bounds))
        val time = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        assertTrue("$tag 버튼이 DOWN을 받았다", button.isPressed)
        return { send(MotionEvent.ACTION_UP) }
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

    fun testNarrowAttachedOverlayScrollsWhileBothActionsRemainVisible() {
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
            val confirmBefore = android.graphics.Rect()
            onUi {
                assertTrue(overlay.isAttachedToWindow)
                val close = overlay.findViewWithTag<Button>("shop:map-close")
                val scroll = overlay.findViewWithTag<ScrollView>("shop:map-scroll")
                assertTrue(close.getGlobalVisibleRect(before))
                assertEquals(close.height, before.height())
                val confirm = overlay.findViewWithTag<Button>("shop:map-confirm")
                assertTrue(confirm.getGlobalVisibleRect(confirmBefore))
                assertEquals(confirm.height, confirmBefore.height())
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
                assertEquals(confirmBefore, visibleConfirm)
                assertEquals(confirm.width, visibleConfirm.width())
                assertEquals(confirm.height, visibleConfirm.height())
            }
        } finally {
            onUi { host.finish() }
        }
    }

    private fun assertCenteredPanel(root: FrameLayout, widthDp: Int, heightDp: Int, panelWidthDp: Int) {
        val style = ShopStyle(root.context)
        val width = style.dp(widthDp)
        val height = style.dp(heightDp)
        val gap = style.dp(12)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val panel = root.getChildAt(0)
        val size = "${widthDp}×${heightDp}dp"
        assertTrue("panel width at $size", kotlin.math.abs(style.dp(panelWidthDp) - panel.width) <= 2)
        assertTrue("horizontal center at $size", kotlin.math.abs(panel.left - (width - panel.right)) <= 1)
        assertTrue("vertical center at $size", kotlin.math.abs(panel.top - (height - panel.bottom)) <= 1)
        assertTrue("panel fits vertically at $size", panel.top >= gap && height - panel.bottom >= gap)
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
