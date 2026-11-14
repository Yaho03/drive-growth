package io.drivegrowth.app.ui.delivery

import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.ui.presentation.ReferenceSceneFrame
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
        val initial = DeliveryFrame(1, DeliveryScreen.Destination(null, "예상 36 km", null, true))
        view.render(initial, true)
        val labels = nodes(view).filterIsInstance<TextView>()
        assertTrue(labels.none { "36 km" in it.text })
        assertNull(view.findViewWithTag<View>("delivery:motion-art"))
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
        view.findViewWithTag<Button>("delivery:tab:purchase").performClick()
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

    fun testConfirmationActionsStayVisibleAt936And320Dp() = onUi {
        val context = instrumentation.targetContext
        val data = DeliveryPreviewStates
        for (width in listOf(936, 320)) {
            val view = DeliveryView(context, onRequest = {})
            layoutAt(view, width, 430)
            val frame = if (width == 320) data.longText else data.offers
            for (offer in listOf(data.purchase, data.income)) {
                view.render(frame, true)
                val tab = if (offer is DeliveryOffer.Purchase) "purchase" else "income"
                view.findViewWithTag<Button>("delivery:tab:$tab").performClick()
                checkNotNull(view.findViewWithTag<Button>("delivery:offer:${offer.id.value}")).performClick()
                layoutAt(view, width, 430)
                val back = checkNotNull(view.findViewWithTag<Button>("delivery:back"))
                val confirm = checkNotNull(view.findViewWithTag<Button>("delivery:confirm"))
                assertSame(back.parent, confirm.parent)
                for (button in listOf(back, confirm)) {
                    val bounds = Rect()
                    assertTrue(button.getGlobalVisibleRect(bounds))
                    assertEquals(button.height, bounds.height())
                }
                val facts = checkNotNull(view.findViewWithTag<View>("delivery:review-facts"))
                val labels = nodes(facts).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(context.getString(R.string.delivery_estimated, "12.5 km") in labels)
                assertNull(view.findViewWithTag<View>("delivery:motion-art"))
                if (width == 320) {
                    val scroll = nodes(view).filterIsInstance<ScrollView>().single()
                    val before = Rect()
                    val after = Rect()
                    assertTrue(confirm.getGlobalVisibleRect(before))
                    scroll.scrollTo(0, scroll.getChildAt(0).height)
                    assertTrue(scroll.scrollY > 0)
                    assertTrue(confirm.getGlobalVisibleRect(after))
                    assertEquals(before, after)
                }
                assertTrue(back.performClick())
            }
        }
    }

    fun testPagedOffersRenderOnlyCurrentTypeAndPage() = onUi {
        val view = DeliveryView(instrumentation.targetContext, onRequest = {})
        val data = DeliveryPreviewStates
        view.render(data.offers, true)
        assertNotNull(view.findViewWithTag<View>("delivery:offer:offer-0"))
        assertNull(view.findViewWithTag<View>("delivery:offer:offer-1"))
        view.findViewWithTag<Button>("delivery:tab:purchase").performClick()
        assertNull(view.findViewWithTag<View>("delivery:offer:offer-0"))
        assertNotNull(view.findViewWithTag<View>("delivery:offer:offer-1"))
        view.render(data.many, true)
        layoutAt(view, 320, 440)
        val first = checkNotNull(view.findViewWithTag<View>("delivery:offer:offer-0"))
        val next = checkNotNull(view.findViewWithTag<Button>("delivery:offers:next"))
        assertFalse(view.findViewWithTag<Button>("delivery:offers:prev").isEnabled)
        val before = Rect()
        assertTrue(next.getGlobalVisibleRect(before))
        val scroll = nodes(view).filterIsInstance<ScrollView>().single()
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        val after = Rect()
        assertTrue(next.getGlobalVisibleRect(after))
        assertEquals(before, after)
        next.performClick()
        assertFalse(first.isShown)
        assertNotNull(view.findViewWithTag<View>("delivery:offer:offer-1"))
        assertNull(view.findViewWithTag<View>("delivery:offer:offer-0"))
    }

    fun testHostNavigationIsOnlyShownForDestinationAndClearedWhenRestricted() = onUi {
        val context = instrumentation.targetContext
        val view = DeliveryView(context, onRequest = {},
            navigationControls = { Button(context).apply { tag = "test:navigation" } },
            hostStatus = { Button(context).apply { tag = "test:receipt" } },
        )
        val offers = DeliveryPreviewStates.offers
        view.render(offers, true)
        assertNotNull(view.findViewWithTag<Button>("test:receipt"))
        assertNull(view.findViewWithTag<Button>("test:navigation"))
        val destination = DeliveryPreviewStates.missing
        view.render(destination, true)
        assertNotNull(view.findViewWithTag<Button>("test:navigation"))
        view.render(destination, false)
        assertNull(view.findViewWithTag<Button>("test:navigation"))
        assertNull(view.findViewWithTag<Button>("test:receipt"))
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
        val departureArt = checkNotNull(view.findViewWithTag<View>("delivery:motion-art"))
        assertTrue(departureArt.getGlobalVisibleRect(Rect()))
        val screenArt = Rect()
        departureArt.getGlobalVisibleRect(screenArt)
        assertTrue(screenArt.width() in (width * .95f).toInt()..width)
        assertTrue(screenArt.height() <= height)
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

    fun testWideActiveRoadSitsLeftOfSummaryAndUsesLessThanHalfTheViewport() = onUi {
        val view = DeliveryView(instrumentation.targetContext, onRequest = {})
        val frame = incomeFrame()
        view.showHeading = false
        layoutAt(view, 960, 500)
        view.render(frame, true)
        layoutAt(view, 960, 500)
        assertTrue(view.hasActiveRoad)
        val summary = checkNotNull(view.findViewWithTag<View>("delivery:highway-status"))
        val road = checkNotNull(view.findViewWithTag<View>("delivery:motion-art"))
        val summaryBounds = Rect()
        val roadBounds = Rect()
        assertTrue(summary.getGlobalVisibleRect(summaryBounds))
        assertTrue(road.getGlobalVisibleRect(roadBounds))
        assertTrue(roadBounds.right <= summaryBounds.left)
        assertTrue(road.height in (view.height / 5)..(view.height / 2))
        assertNull(view.findViewWithTag<View>("delivery:scene:DEPARTURE"))

        view.render(frame.copy(version = frame.version + 1), false)
        assertFalse(view.hasActiveRoad)
        assertNull(view.findViewWithTag<View>("delivery:highway"))
    }

    fun testActiveHighwayShowsObservedStatusAndFixedActionsWithoutDestinationConfirmation() = onUi {
        val context = instrumentation.targetContext
        val destinationControls = "test:host-destination"
        val view = DeliveryView(context, onRequest = {}, navigationControls = {
            Button(context).apply { tag = destinationControls; text = "시연 목적지 확인" }
        }).apply { onReturnToShop = {} }
        layoutAt(view, 700, 440)
        val original = incomeFrame()
        val active = (original.screen as DeliveryScreen.Active).delivery
        val fullDestination = "긴 목적지 이름과 상세 위치 ".repeat(5).trimEnd()
        val staleDistanceLabel = "출처가 다른 관측값 5000 m"
        val frame = original.copy(screen = DeliveryScreen.Active(active.copy(
            destinationName = fullDestination, observedDistanceLabel = staleDistanceLabel,
        )))
        val missing = Observation.Missing(MissingReason.NO_DATA)
        val observed = Observation.Available(ObservedDistance(5000, InputSource.SIMULATION))
        val nav = DeliveryNavigationSnapshot(null, null, missing, observed, missing, null)
        val read = DeliveryHostRead(GameIdentity(ProfileId("fixture"), InputSource.SIMULATION), frame.screen, navigation = nav)
        view.render(frame, true, read)
        layoutAt(view, 700, 440)
        val status = checkNotNull(view.findViewWithTag<View>("delivery:highway-status"))
        val road = checkNotNull(view.findViewWithTag<View>("delivery:motion-art"))
        assertNull(view.findViewWithTag<View>(destinationControls))
        val visibleStatus = Rect()
        val visibleRoad = Rect()
        assertTrue(status.getGlobalVisibleRect(visibleStatus))
        assertTrue("고속도로가 상태 띠 아래에 있어야 한다", road.getGlobalVisibleRect(visibleRoad))
        assertTrue(visibleStatus.bottom <= visibleRoad.top)
        val shop = checkNotNull(view.findViewWithTag<Button>("delivery:shop"))
        val change = checkNotNull(view.findViewWithTag<Button>("delivery:destination"))
        val abandon = checkNotNull(view.findViewWithTag<Button>("delivery:review-abandon"))
        val before = Rect()
        assertTrue(shop.getGlobalVisibleRect(before))
        val scroll = nodes(view).filterIsInstance<ScrollView>().single()
        assertSame(shop.parent, change.parent)
        assertSame(shop.parent, abandon.parent)
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        val after = Rect()
        assertTrue(shop.getGlobalVisibleRect(after))
        assertEquals(before, after)
        assertEquals(40, nodes(status).filterIsInstance<ProgressBar>().single().progress)
        assertNotNull(status.findViewWithTag<View>("delivery:progress-truck"))
        val fullName = nodes(status).filterIsInstance<TextView>().single { it.text.toString() == fullDestination }
        assertTrue(fullName.lineCount > 1)
        val labels = nodes(status).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(context.getString(R.string.delivery_estimated, "12.5 km") in labels)
        assertTrue(context.getString(R.string.delivery_observed, "5 km") in labels)
        assertTrue(context.getString(R.string.delivery_source_simulation) in labels)
        val foreign = Observation.Available(ObservedDistance(5000, InputSource.VEHICLE))
        val foreignRead = read.copy(navigation = nav.copy(observed = foreign))
        view.render(frame.copy(version = frame.version + 1), true, foreignRead)
        val nextStatus = checkNotNull(view.findViewWithTag<View>("delivery:highway-status"))
        assertTrue(nodes(nextStatus).none { it is ProgressBar })
        assertNull(nextStatus.findViewWithTag<View>("delivery:progress-truck"))
        val unavailable = context.getString(R.string.delivery_observed, context.getString(R.string.delivery_information_missing))
        assertTrue(nodes(nextStatus).filterIsInstance<TextView>().any { unavailable in it.text.toString() })
        assertTrue(nodes(view).filterIsInstance<TextView>().none { staleDistanceLabel in it.text.toString() })
    }

    fun testConfirmedIncomeArtworkAndDistinctSceneExitActions() = onUi {
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
        for ((width, height, exit) in listOf(
            Triple(320, 440, "skip"), Triple(700, 280, "back"), Triple(1360, 948, "confirm"),
        )) {
            val requests = mutableListOf<DeliveryRequest>()
            var confirmations = 0
            val view = DeliveryView(context, requests::add).apply { onConfirmSceneResult = { confirmations++ } }
            layoutAt(view, width, height)
            assertTrue(view.render(frame, true, read))
            layoutAt(view, width, height)
            val reward = checkNotNull(view.findViewWithTag<TextView>("delivery:confirmed-reward"))
            val art = checkNotNull(view.findViewWithTag<View>("delivery:motion-art"))
            val close = checkNotNull(view.findViewWithTag<Button>("delivery:skip-motion"))
            val rewardLabel = context.getString(R.string.delivery_scene_reward)
            assertEquals(context.getString(R.string.delivery_money, rewardLabel,
                (order.offer as DeliveryOffer.Income).reward.units), reward.contentDescription.toString())
            assertTrue(reward.getGlobalVisibleRect(Rect()))
            val artRect = Rect()
            assertTrue(art.getGlobalVisibleRect(artRect))
            assertTrue("도착 그림 너비: ${artRect.width()}/${view.width}", artRect.width() >= view.width * .5f)
            assertTrue(close.getGlobalVisibleRect(Rect()))
            when (exit) {
                "skip" -> assertTrue(close.performClick())
                "back" -> assertTrue(view.handleBack())
                "confirm" -> {
                    (art as DeliveryMotionView).onSceneFrame?.invoke(1.8f)
                    layoutAt(view, width, height)
                    val confirm = checkNotNull(view.findViewWithTag<Button>("delivery:confirm-scene"))
                    assertTrue(confirm.getGlobalVisibleRect(Rect()))
                    assertFalse(close.isShown)
                    assertTrue(confirm.performClick())
                    confirm.performClick() // 분리된 버튼으로 다시 호출해도 중복 확인하지 않는다.
                }
            }
            assertEquals(if (exit == "confirm") 1 else 0, confirmations)
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
            for (child in nodes(view)) {
                val virtualStage = generateSequence(child.parent as? View) { it.parent as? View }
                    .filterIsInstance<ReferenceSceneFrame>().firstOrNull()
                if (virtualStage == null) {
                    assertTrue("${frame.screen.javaClass.simpleName}: ${child.tag} ${child.width}/$w", child.width <= w)
                } else {
                    val stage = virtualStage.getChildAt(0)
                    assertTrue("도로 무대가 좁은 창 너비를 넘지 않아야 한다", stage.width * stage.scaleX <= w + 1)
                }
            }
        }
    }
}
