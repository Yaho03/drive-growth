package io.drivegrowth.app.ui.delivery

import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.data.ArrivalCommit
import io.drivegrowth.app.data.DeliveryCommit
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.delivery.DeliveryHostOperation
import io.drivegrowth.app.host.delivery.DeliveryHostRead
import io.drivegrowth.app.host.delivery.DeliveryHostReceipt
import io.drivegrowth.app.host.delivery.DeliveryNavigationSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DeliveryInProgress
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.delivery.DeliveryOutcome
import io.drivegrowth.core.delivery.DeliverySelected
import io.drivegrowth.core.delivery.DeliverySettlement
import io.drivegrowth.core.delivery.DeliverySettlementRules

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

    fun testHostControlsShareOfferScrollAndDisappearWhenRestricted() = onUi {
        val context = instrumentation.targetContext
        val view = DeliveryView(context, onRequest = {},
            navigationControls = { Button(context).apply { tag = "test:navigation" } },
            hostStatus = { Button(context).apply { tag = "test:receipt" } },
        )
        val offers = DeliveryPreviewStates.offers
        view.render(offers, true)
        val scroll = nodes(view).filterIsInstance<ScrollView>().single()
        val tags = nodes(scroll).map { it.tag }
        val offer = tags.indexOf("delivery:offer:offer-1")
        assertTrue(tags.indexOf("test:receipt") in 0 until offer)
        assertTrue(tags.indexOf("test:navigation") > offer)

        view.render(offers, false)
        assertNull(view.findViewWithTag<Button>("test:navigation"))
        assertNull(view.findViewWithTag<Button>("test:receipt"))
        assertNull(view.findViewWithTag<Button>("delivery:offer:offer-1"))
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

    fun testConfirmedDepartureSkipShowsHighwayWithoutSubmittingAndRedrawDoesNotReplay() = onUi {
        val requests = mutableListOf<DeliveryRequest>()
        val cues = mutableListOf<DeliveryPresentationCue>()
        val view = DeliveryView(instrumentation.targetContext, requests::add, onPresentationCue = cues::add)
        val frame = incomeFrame()
        val read = selectionRead(frame)
        view.render(frame, true, read)
        assertNotNull(view.findViewWithTag<View>("delivery:scene:DEPARTURE"))
        val width = (320 * view.resources.displayMetrics.density).toInt()
        val height = (440 * view.resources.displayMetrics.density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        assertTrue(nodes(view).all { it.width <= width })
        view.findViewWithTag<Button>("delivery:skip-motion").performClick()
        assertNull(view.findViewWithTag<View>("delivery:scene:DEPARTURE"))
        assertNotNull(view.findViewWithTag<View>("delivery:highway"))
        view.render(frame.copy(version = frame.version + 1), true, read)
        assertNull(view.findViewWithTag<View>("delivery:scene:DEPARTURE"))
        assertTrue(requests.isEmpty())
        assertTrue(cues.isEmpty()) // 분리된 View에서는 효과음도 시작하지 않는다.
    }

    fun testRestrictionRemovesSceneAndHighwayAndLateReceiptCannotReplay() = onUi {
        val view = DeliveryView(instrumentation.targetContext, onRequest = {})
        val frame = incomeFrame()
        val read = selectionRead(frame)
        view.render(frame, true, read)
        assertNotNull(view.findViewWithTag<View>("delivery:scene:DEPARTURE"))
        val staleSkip = view.findViewWithTag<Button>("delivery:skip-motion")
        view.render(frame.copy(version = frame.version + 1), false, read)
        staleSkip.performClick()
        assertNull(view.findViewWithTag<View>("delivery:scene:DEPARTURE"))
        assertNull(view.findViewWithTag<View>("delivery:highway"))
        assertFalse(view.render(frame, true, read))
        assertNull(view.findViewWithTag<View>("delivery:highway"))
        view.render(frame.copy(version = frame.version + 2), true, read)
        assertNull(view.findViewWithTag<View>("delivery:scene:DEPARTURE"))
        assertNotNull(view.findViewWithTag<View>("delivery:highway"))
    }

    fun testActiveHighwayAndStatusPrecedeHostDestinationControls() = onUi {
        val context = instrumentation.targetContext
        val destinationControls = "test:host-destination"
        val view = DeliveryView(context, onRequest = {}, navigationControls = {
            Button(context).apply { tag = destinationControls; text = "시연 목적지 확인" }
        })
        layoutAt(view, 700, 320)
        val original = incomeFrame()
        val active = (original.screen as DeliveryScreen.Active).delivery
        val fullDestination = "긴 목적지 이름과 상세 위치 ".repeat(20).trimEnd()
        val staleDistanceLabel = "출처가 다른 관측값 5000 m"
        val frame = original.copy(screen = DeliveryScreen.Active(active.copy(
            destinationName = fullDestination, observedDistanceLabel = staleDistanceLabel,
        )))
        val missing = Observation.Missing(MissingReason.NO_DATA)
        val observed = Observation.Available(ObservedDistance(5000, InputSource.SIMULATION))
        val nav = DeliveryNavigationSnapshot(null, null, missing, observed, missing, null)
        val read = DeliveryHostRead(GameIdentity(ProfileId("fixture"), InputSource.SIMULATION), frame.screen, navigation = nav)
        view.render(frame, true, read)
        layoutAt(view, 700, 320)
        val status = checkNotNull(view.findViewWithTag<View>("delivery:highway-status"))
        val road = checkNotNull(view.findViewWithTag<View>("delivery:motion-art"))
        val host = checkNotNull(view.findViewWithTag<View>(destinationControls))
        assertTrue(visibleWithoutScrolling(status))
        val visibleRoad = Rect()
        road.getGlobalVisibleRect(visibleRoad)
        assertTrue("고속도로 bounds=${bounds(road)} visible=$visibleRoad status=${bounds(status)} viewport=${bounds(view)}",
            visibleWithoutScrolling(road))
        assertTrue(bounds(status).bottom <= bounds(road).top)
        assertTrue(bounds(road).bottom <= bounds(host).top)
        assertEquals(40, nodes(status).filterIsInstance<ProgressBar>().single().progress)
        val shortName = nodes(status).filterIsInstance<TextView>().single { it.text.toString() == fullDestination }
        assertEquals(1, shortName.lineCount)
        assertTrue(shortName.layout.getEllipsisCount(0) > 0)
        val fullName = nodes(view).filterIsInstance<TextView>().single { it !== shortName && it.text.toString() == fullDestination }
        assertTrue(fullName.lineCount > 1)
        val foreign = Observation.Available(ObservedDistance(5000, InputSource.VEHICLE))
        val foreignRead = read.copy(navigation = nav.copy(observed = foreign))
        view.render(frame.copy(version = frame.version + 1), true, foreignRead)
        val nextStatus = checkNotNull(view.findViewWithTag<View>("delivery:highway-status"))
        assertTrue(nodes(nextStatus).none { it is ProgressBar })
        val unavailable = context.getString(R.string.delivery_observed, context.getString(R.string.delivery_information_missing))
        assertTrue(nodes(nextStatus).filterIsInstance<TextView>().any { unavailable in it.text.toString() })
        assertTrue(nodes(view).filterIsInstance<TextView>().none { staleDistanceLabel in it.text.toString() })
    }

    fun testConfirmedIncomeAndCloseAreVisibleWithArrivalArtworkOnFirstLayout() = onUi {
        val context = instrumentation.targetContext
        val order = (incomeFrame().screen as DeliveryScreen.Active).delivery.active
        val route = RouteId("fixture-route")
        val time = EpochMillis(1)
        val rules = DeliverySettlementRules(order.proposed.list.ruleVersion, 100, 100, 5)
        val evidence = Observation.Available(DeliveryArrivalEvidence(route, InputSource.SIMULATION, time))
        val outcome = (DeliverySettlement(rules).resolve(DeliveryInProgress(order, route, time), evidence, time, 1)
            as ContractResult.Success).value as DeliveryOutcome.IncomeCompleted
        val change = outcome.change
        val receipt = Settlement(SettlementId("fixture-settlement"),
            Transaction(TransactionId("fixture-transaction"), change.requestId, change.kind, change.source,
                change.ruleVersion, change.money), GameMoney(200), null, time)
        val frame = DeliveryPreviewStates.missing.copy(version = 3)
        val read = DeliveryHostRead(GameIdentity(ProfileId("fixture"), InputSource.SIMULATION), frame.screen,
            pending = DeliveryHostOperation.Arrival(rules, order.requestId, evidence, time, order.proposed.list, order.proposed.list),
            receipt = DeliveryHostReceipt.Arrived(ArrivalCommit(outcome, receipt)))
        for ((width, height) in listOf(320 to 440, 700 to 280, 1360 to 948)) {
            val requests = mutableListOf<DeliveryRequest>()
            val view = DeliveryView(context, requests::add)
            layoutAt(view, width, height)
            assertTrue(view.render(frame, true, read))
            layoutAt(view, width, height)
            val reward = checkNotNull(view.findViewWithTag<TextView>("delivery:confirmed-reward"))
            val art = checkNotNull(view.findViewWithTag<View>("delivery:motion-art"))
            val close = checkNotNull(view.findViewWithTag<Button>("delivery:skip-motion"))
            val rewardLabel = context.getString(R.string.delivery_scene_reward)
            assertEquals(context.getString(R.string.delivery_money, rewardLabel,
                (order.offer as DeliveryOffer.Income).reward.units), reward.text.toString())
            assertTrue(visibleWithoutScrolling(reward))
            assertTrue(visibleWithoutScrolling(art))
            if (width == 1360) assertTrue("공식 가로 도착 그림 너비: ${art.width}/${view.width}",
                art.width >= view.width * .85f)
            assertTrue(visibleWithoutScrolling(close))
            assertTrue(close.performClick())
            assertTrue(requests.isEmpty())
            assertNull(view.findViewWithTag<View>("delivery:confirmed-reward"))
        }
    }

    private fun layoutAt(view: DeliveryView, widthDp: Int, heightDp: Int) {
        val density = view.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun bounds(view: View): Rect {
        val result = Rect(0, 0, view.width, view.height)
        val root = view.rootView as ViewGroup
        root.offsetDescendantRectToMyCoords(view, result)
        return result
    }

    private fun visibleWithoutScrolling(view: View): Boolean {
        val visible = Rect()
        return view.width > 0 && view.height > 0 && view.getGlobalVisibleRect(visible) &&
            visible.width() == view.width && visible.height() == view.height
    }

    private fun incomeFrame(): DeliveryFrame {
        val data = DeliveryPreviewStates
        return data.activeFrame.copy(screen = DeliveryScreen.Active(data.active.copy(
            active = data.active.active.copy(offer = data.income),
            vehicleName = data.proposal.vehicleNames[data.income.vehicleId],
        )))
    }

    private fun selectionRead(frame: DeliveryFrame): DeliveryHostRead {
        val order = (frame.screen as DeliveryScreen.Active).delivery.active
        check(order.offer is DeliveryOffer.Income)
        return DeliveryHostRead(GameIdentity(ProfileId("fixture"), InputSource.SIMULATION), frame.screen,
            pending = DeliveryHostOperation.Select(order.proposed, order.offer.id, order.requestId, RouteId("fixture-route"), EpochMillis(1)),
            receipt = DeliveryHostReceipt.Selected(DeliveryCommit(DeliverySelected(order, null), null)))
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
