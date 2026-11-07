package io.drivegrowth.app.ui.delivery

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

@Suppress("DEPRECATION")
class DeliveryViewTest : InstrumentationTestCase() {
    private fun <T> onUi(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { result = block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun nodes(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { nodes(v.getChildAt(it)) } else emptyList()

    fun testMissingDestinationAndRestrictionNeverShowReadyOrEmitStaleActions() = onUi {
        val requests = mutableListOf<DeliveryRequest>()
        val view = DeliveryView(instrumentation.targetContext, requests::add)
        val initial = DeliveryFrame(1, DeliveryScreen.Destination(null, null, null, true))
        view.render(initial, true)
        val stale = view.findViewWithTag<Button>("delivery:destination")
        assertNotNull(stale)
        view.render(initial, false)
        stale.performClick()
        assertTrue(requests.isEmpty())
        assertTrue(nodes(view).none { it is Button })
        assertTrue(nodes(view).filterIsInstance<TextView>().none { it.text.contains("0 km") })
    }

    fun testPendingAndLateResponseDoNotOpenRestrictedContent() = onUi {
        val requests = mutableListOf<DeliveryRequest>()
        val view = DeliveryView(instrumentation.targetContext, requests::add)
        val initial = DeliveryFrame(1, DeliveryScreen.Destination("시험 목적지", "12 km · 예상", "모의 입력", true))
        view.render(initial, true)
        val button = view.findViewWithTag<Button>("delivery:destination")
        button.performClick(); button.performClick()
        assertEquals(1, requests.size)
        view.render(initial, false)
        assertTrue(view.complete(requests.single(), DeliveryFrame(2, DeliveryScreen.Failure("경로 조회 실패", true))))
        assertTrue(nodes(view).none { it is Button })
        assertFalse(view.render(initial, true))
        view.release()
        assertFalse(view.render(DeliveryFrame(3, DeliveryScreen.Loading), true))
    }

    fun testLongFailureAtNarrowWidthIsScrollableAndRetryIsSingleRequest() = onUi {
        val requests = mutableListOf<DeliveryRequest>()
        val view = DeliveryView(instrumentation.targetContext, requests::add)
        view.render(DeliveryFrame(1, DeliveryScreen.Failure("경로를 확인할 수 없어요. ".repeat(30), true)), true)
        val density = view.resources.displayMetrics.density
        val w = (320 * density).toInt(); val h = (440 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        assertTrue(nodes(view).all { it.width <= w })
        val retry = view.findViewWithTag<Button>("delivery:refresh")
        retry.performClick(); retry.performClick()
        assertEquals(1, requests.size)
        assertTrue(requests.single().command == DeliveryCommand.Refresh)
    }

    fun testPurchaseConfirmationKeepsOriginalProposalAndWaitsForCommit() = onUi {
        val requests = mutableListOf<DeliveryRequest>()
        val data = DeliveryPreviewStates
        val view = DeliveryView(instrumentation.targetContext, requests::add)
        view.render(data.offers, true)
        view.findViewWithTag<Button>("delivery:offer:offer-1").performClick()
        assertTrue(requests.isEmpty())
        val confirm = view.findViewWithTag<Button>("delivery:confirm")
        assertNotNull(confirm)
        confirm.performClick(); confirm.performClick()
        assertEquals(1, requests.size)
        assertEquals(DeliveryCommand.Select(data.proposed, data.purchase.id), requests.single().command)
        assertFalse(nodes(view).filterIsInstance<TextView>().any { it.text.contains("지급 완료") })
        assertTrue(view.complete(requests.single(), data.activeFrame))
        assertTrue(nodes(view).filterIsInstance<TextView>().any { it.text.contains("도착") })
    }

    fun testReviewBackNeverSubmitsAbandonAndMissingAmountsDisableConfirmation() = onUi {
        val requests = mutableListOf<DeliveryRequest>()
        val data = DeliveryPreviewStates
        val view = DeliveryView(instrumentation.targetContext, requests::add)
        val state = data.abandon.screen as DeliveryScreen.AbandonReview
        view.render(DeliveryFrame(3, state.copy(refund = null)), true)
        assertFalse(view.findViewWithTag<Button>("delivery:abandon").isEnabled)
        assertTrue(view.handleBack())
        assertEquals(DeliveryCommand.ExitReview, requests.single().command)
        assertTrue(requests.none { it.command is DeliveryCommand.Abandon })
    }

    fun testPreviewStatesLargeTextAndManyItemsNeverExceedNarrowRoot() = onUi {
        val data = DeliveryPreviewStates
        val states = listOf(data.empty, data.missing, data.loading, data.failure, data.unsupported,
            data.activeFrame, data.awaiting, data.change, data.abandon, data.many, data.longText)
        for (frame in states) {
            val view = DeliveryView(instrumentation.targetContext, onRequest = {})
            view.render(frame, true)
            val w = (320 * view.resources.displayMetrics.density).toInt()
            val h = (440 * view.resources.displayMetrics.density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            assertTrue(nodes(view).all { it.width <= w })
        }
    }
}
