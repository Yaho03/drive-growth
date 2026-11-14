package io.drivegrowth.app.ui.gacha

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.app.ui.pixel.CleanUi
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import io.drivegrowth.core.contract.*

/** 실제 위젯 표적. 기기 배정 전에는 컴파일만 확인한다. */
@Suppress("DEPRECATION")
class GachaViewTest : InstrumentationTestCase() {
    private fun <T> onUi(block: () -> T): T {
        var value:T?=null;var failure:Throwable?=null
        instrumentation.runOnMainSync { try { value=block() } catch(t:Throwable) { failure=t } }
        failure?.let { throw it }; @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun nodes(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { nodes(v.getChildAt(it)) } else emptyList()
    private fun text(v:View)=nodes(v).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun button(v:View,tag:String):Button=v.findViewWithTag(tag)
    private fun make(events:MutableList<GachaEvent> = mutableListOf()) = GachaView(instrumentation.targetContext,events::add)

    fun testForeignCheckpointReplacesConflictWithOwnerPageNotice() = onUi {
        val view = make()
        val frame = GachaFrame(1, GachaScreen.Failure(ContractError.STATE_CONFLICT, "internal", true))
        assertTrue(view.render(frame, true))
        assertTrue("다시 조회" in text(view))
        view.setForeignCheckpoint(true)
        assertTrue("다른 화면에 보관된 요청을 먼저 확인해 주세요." in text(view))
        assertFalse("다시 조회" in text(view))
        assertFalse("internal" in text(view))
        view.setForeignCheckpoint(false)
        assertTrue("다시 조회" in text(view))
    }

    fun testAppliedProbabilitiesUseSuppliedCurrentTableAndKeepSpeciesSeparate()=onUi {
        val view=make();view.render(GachaPreviewStates.ready(),true)
        assertTrue(text(view).contains("일반 · 74.7%"));assertTrue(text(view).contains("500 게임머니"))
        assertTrue(text(view).contains("캐스퍼"));assertTrue(text(view).contains("등급은 뽑을 때 정해져요"))
        view.render(GachaPreviewStates.guaranteed(),true)
        assertTrue(text(view).contains("희귀 · 94.3396%"));assertFalse(text(view).contains("74.7%"))
        assertTrue(text(view).contains("이번 회차 · 보장 적용"));assertTrue(text(view).contains("남은 횟수 · 1회"))
    }
    fun testReviewCancelAndPendingNeverCreateAnAcquiredVehicle()=onUi {
        val events=mutableListOf<GachaEvent>();val view=make(events)
        val ready=GachaPreviewStates.ready();view.render(ready,true)
        button(view,"gacha:option:paid").performClick();assertTrue(events.isEmpty())
        assertTrue(text(view).contains("확인만으로 차감"));button(view,"gacha:cancel").performClick();assertTrue(events.isEmpty())
        button(view,"gacha:option:paid").performClick();val confirm=button(view,"gacha:confirm")
        confirm.performClick();confirm.performClick();assertEquals(1,events.size)
        assertTrue(text(view).contains("요청 처리 중"));assertFalse(text(view).contains("차량 획득"))
        val r=(events.single() as GachaEvent.Draw).request;view.complete(r,ContractResult.Success(Unit))
        assertFalse(button(view,"gacha:option:free").isEnabled)
        assertTrue(text(view).contains("확정 획득 결과 조회를 기다립니다"));assertTrue(text(view).contains("1,200 게임머니"))
    }
    fun testConfirmationActionsStayFixedWhileOffersScroll() = onUi {
        val view = make()
        view.render(GachaPreviewStates.ready(), true)
        button(view, "gacha:option:paid").performClick()
        val density = view.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (340 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val confirm = button(view, "gacha:confirm")
        val cancel = button(view, "gacha:cancel")
        assertSame(confirm.parent, cancel.parent)
        assertSame(view, (confirm.parent as View).parent)
        val before = Rect()
        assertTrue(confirm.getGlobalVisibleRect(before))
        val scroll = nodes(view).filterIsInstance<ScrollView>().single()
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        assertTrue(scroll.scrollY > 0)
        val after = Rect()
        assertTrue(confirm.getGlobalVisibleRect(after))
        assertEquals(before, after)
    }

    fun testStaleButtonsRestrictionAndConstructorPendingRestoration()=onUi {
        val events=mutableListOf<GachaEvent>();val ready=GachaPreviewStates.ready();val view=make(events)
        view.render(ready,true);button(view,"gacha:option:paid").performClick();val stale=button(view,"gacha:confirm")
        view.render(ready,false);stale.performClick();assertTrue(events.isEmpty());assertTrue(nodes(view).none { it is Button })
        view.render(ready,true);button(view,"gacha:confirm").performClick();assertEquals(1,events.size)
        val restored=GachaView(instrumentation.targetContext,events::add,view.uiState)
        restored.render(ready,true);button(restored,"gacha:confirm").performClick();assertEquals(1,events.size)
        assertFalse(restored.render(ready.copy(generation=0),false));assertTrue(nodes(restored).none { it is Button })
        restored.complete((events.single() as GachaEvent.Draw).request,ContractResult.Success(Unit))
        assertTrue(nodes(restored).none { it is Button })
    }
    fun testAlternatingFailureOptionsKeepTheirEconomicKeysAcrossNewView()=onUi {
        val events=mutableListOf<GachaEvent>();var view=make(events);val frame=GachaPreviewStates.ready();view.render(frame,true)
        fun submit(id:String):GachaRequest { button(view,"gacha:option:$id").performClick();button(view,"gacha:confirm").performClick();return (events.last() as GachaEvent.Draw).request }
        val a=submit("paid");view.complete(a,ContractResult.Failure(ContractError.STORAGE_FAILURE))
        val b=submit("free");view.complete(b,ContractResult.Failure(ContractError.STORAGE_FAILURE))
        view=GachaView(instrumentation.targetContext,events::add,view.uiState);view.render(frame,true)
        val retry=submit("paid");assertEquals(a.input.option.requestId,retry.input.option.requestId);assertFalse(a.attemptId==retry.attemptId)
        view.complete(a,ContractResult.Success(Unit));assertEquals(retry,view.uiState.pending)
    }
    fun testMissingBlockedStatesDoNotEnableDrawOrInventBaseProbability()=onUi {
        for(frame in GachaPreviewStates.blocked()) {
            val events=mutableListOf<GachaEvent>();val view=make(events);view.render(frame,true)
            nodes(view).filterIsInstance<Button>().filter { it.tag?.toString()?.startsWith("gacha:option:")==true }.forEach { assertFalse(it.isEnabled);it.performClick() }
            assertTrue(events.isEmpty());assertFalse(text(view).contains("차량 획득"))
            val value=(frame.screen as? GachaScreen.Offers)?.value
            if(value!=null && !value.ratesValid()) assertFalse(text(view).contains("74.7%"))
        }
    }
    fun testExternalResultUsesOriginalOwnedVehicleAndGarageIsOnlyANavigationRequest()=onUi {
        val events=mutableListOf<GachaEvent>();val view=make(events);view.render(GachaPreviewStates.result(),true)
        assertTrue(text(view).contains("아반떼 · 희귀"));assertFalse(text(view).contains("실제 취득 기준액"))
        assertFalse(text(view).contains("이번 뽑기에 쓴 금액")) // 고정 결과의 거래에는 실제 차감이 없다.
        assertTrue(text(view).contains("차량 획득"));assertFalse(text(view).contains("fixture-gacha-owned"))
        assertTrue(text(view).contains("실제 추첨·차감·지급 아님"))
        button(view,"gacha:garage").performClick();button(view,"gacha:garage").performClick();assertEquals(1,events.size)
        assertEquals(OwnedVehicleId("fixture-gacha-owned"),(events.single() as GachaEvent.OpenGarage).request.ownedVehicleId)
    }
    fun testCollectionNewBadgeRequiresCurrentSubmissionAndDoesNotRestore() = onUi {
        val events = mutableListOf<GachaEvent>()
        val view = make(events).apply { animationsEnabled = { false } }
        view.render(GachaPreviewStates.ready(), true)
        button(view, "gacha:option:paid").performClick()
        button(view, "gacha:confirm").performClick()
        val request = (events.single() as GachaEvent.Draw).request.input.option.requestId
        val supplied = GachaPreviewStates.result()
        val value = (supplied.screen as GachaScreen.Result).value.copy(origin = GachaOrigin.STORED)
        val frame = supplied.copy(screen = GachaScreen.Result(value))
        assertEquals(request, value.settlement.transaction.requestId)
        var takes = 0
        view.takeNewGachaResult = { receipt -> takes++; receipt == value.settlement }
        assertNull(view.findViewWithTag<View>("gacha:new-collection"))
        view.render(frame, true)
        val badge = view.findViewWithTag<TextView>("gacha:new-collection")
        assertEquals("NEW · 도감 등록", badge.text.toString())
        assertEquals(CleanUi.INK, badge.currentTextColor)
        view.render(frame, true)
        assertEquals(1, takes)
        val restored = GachaView(instrumentation.targetContext, events::add, view.uiState)
        restored.takeNewGachaResult = { fail("복원은 새 획득 통지를 요청하지 않는다"); false }
        restored.render(frame, true)
        assertNull(restored.findViewWithTag<View>("gacha:new-collection"))
        view.render(frame, false)
        view.render(frame, true)
        assertNull(view.findViewWithTag<View>("gacha:new-collection"))
    }

    fun testDrawAgainOnlyReloadsTheDisplayedReceiptAndRejectsStaleButtons() = onUi {
        val events = mutableListOf<GachaEvent>()
        val view = make(events)
        val supplied = GachaPreviewStates.result()
        val receipt = (supplied.screen as GachaScreen.Result).value.settlement
        view.render(supplied, true)
        val previous = button(view, "gacha:draw-again")
        view.render(supplied, false)
        previous.performClick()
        assertTrue(events.isEmpty())

        view.render(supplied, true)
        val current = button(view, "gacha:draw-again")
        current.performClick()
        current.performClick()
        val reload = events.single() as GachaEvent.Reload
        assertEquals(receipt.settlementId, reload.settlementId)
        assertFalse(button(view, "gacha:draw-again").isEnabled)
        assertFalse(button(view, "gacha:garage").isEnabled)
        view.completeReload("unrelated")
        assertFalse(button(view, "gacha:draw-again").isEnabled)
        view.completeReload(reload.token)
        assertTrue(button(view, "gacha:draw-again").isEnabled)
    }

    fun testStoredResultStageUsesFirstHeightAndShrinksAtTheSameWidth() = onUi {
        val events = mutableListOf<GachaEvent>()
        val view = make(events)
        view.render(GachaPreviewStates.result(), true)
        val density = view.resources.displayMetrics.density
        val width = (760 * density).toInt()
        fun layout(heightDp: Int) {
            val height = (heightDp * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }

        layout(760)
        val stage = view.findViewWithTag<View>("gacha:result-stage")
        val tallHeight = stage.height
        assertTrue("첫 배치부터 넓은 결과 그림을 사용해야 한다", tallHeight > (264 * density).toInt())
        layout(300)
        assertSame(stage, view.findViewWithTag<View>("gacha:result-stage"))
        assertTrue("같은 폭에서 높이가 줄면 결과 그림도 줄어야 한다", stage.height in 1 until tallHeight)
        assertTrue(button(view, "gacha:garage").isEnabled)
        assertTrue(events.isEmpty())
    }

    fun testBackSkipsOnlyTheLocalRevealAndRestoredResultIsStatic() = onUi {
        if (!ValueAnimator.areAnimatorsEnabled()) return@onUi
        val events = mutableListOf<GachaEvent>()
        val view = make(events)
        view.render(GachaPreviewStates.ready(), true)
        button(view, "gacha:option:paid").performClick()
        button(view, "gacha:confirm").performClick()
        assertEquals(1, events.size)

        val supplied = GachaPreviewStates.result()
        val result = (supplied.screen as GachaScreen.Result).value.copy(origin = GachaOrigin.STORED)
        val frame = supplied.copy(screen = GachaScreen.Result(result))
        view.takeNewGachaResult = { it == result.settlement }
        view.render(frame, true)
        val earlyReveal = button(view, "gacha:reveal")
        assertEquals(View.GONE, earlyReveal.visibility)
        assertFalse(earlyReveal.isEnabled)
        assertFalse(view.findViewWithTag<View>("gacha:reveal-scene").isClickable)
        earlyReveal.performClick()
        view.findViewWithTag<View>("gacha:reveal-scene").performClick()
        assertEquals(1, events.size)
        assertNull(view.findViewWithTag<View>("gacha:garage"))
        assertEquals(View.INVISIBLE, view.findViewWithTag<View>("gacha:reference-card").visibility)
        val hiddenBadge = view.findViewWithTag<TextView>("gacha:new-collection")
        assertFalse(hiddenBadge.isShown)
        assertEquals(ReferenceHudStyle(view.context).signInk, hiddenBadge.currentTextColor)

        val restored = GachaView(instrumentation.targetContext, events::add, view.uiState)
        restored.render(frame, true)
        assertNull(restored.findViewWithTag<View>("gacha:reveal"))
        assertTrue(button(restored, "gacha:garage").isEnabled)
        assertNull(restored.findViewWithTag<View>("gacha:new-collection"))

        assertTrue(view.handleBack())
        assertNull(view.findViewWithTag<View>("gacha:reveal"))
        assertTrue(button(view, "gacha:garage").isEnabled)
        val resultBadge = view.findViewWithTag<TextView>("gacha:new-collection")
        assertEquals(CleanUi.INK, resultBadge.currentTextColor)
        view.render(frame, false)
        view.render(frame, true)
        assertNull(view.findViewWithTag<View>("gacha:reveal"))
        assertEquals("표시와 Back은 원 요청 이외의 이벤트를 보내지 않는다", 1, events.size)
    }

    fun testRareAndLegendRequireExplicitRevealAndDoNotSubmitAgain() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        val activity = instrumentation.startActivitySync(Intent(context,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
        try {
            for (rarity in listOf(Rarity.RARE, Rarity.LEGEND)) {
                val events = mutableListOf<GachaEvent>()
                val audio = mutableListOf<Pair<Rarity, Boolean>>()
                var waitStartedAt: Long? = null
                val view = onUi {
                    GachaView(activity, events::add).also {
                        it.onGachaAudio = { rank, opening ->
                            if (!opening) waitStartedAt = SystemClock.uptimeMillis()
                            audio += rank to opening
                        }
                        activity.setContentView(it)
                    }
                }
                awaitCondition("가챠 창이 포커스를 받지 못했습니다") { view.isShown && view.hasWindowFocus() }
                onUi {
                    view.render(GachaPreviewStates.ready(), true)
                    assertNull(view.findViewWithTag<View>("gacha:reveal-scene"))
                    assertTrue(audio.isEmpty())
                    button(view, "gacha:option:paid").performClick()
                    assertNull(view.findViewWithTag<View>("gacha:reveal-scene"))
                    assertTrue(audio.isEmpty())
                    button(view, "gacha:confirm").performClick()
                    val supplied = GachaPreviewStates.result()
                    val result = (supplied.screen as GachaScreen.Result).value
                    val vehicle = checkNotNull(result.settlement.changedVehicle).copy(rarity = rarity)
                    val receipt = result.settlement.copy(changedVehicle = vehicle)
                    view.render(supplied.copy(screen = GachaScreen.Result(result.copy(
                        settlement = receipt, origin = GachaOrigin.STORED,
                    ))), true)
                    assertEquals(View.GONE, button(view, "gacha:reveal").visibility)
                    view.findViewWithTag<View>("gacha:reveal-scene").performClick()
                    assertTrue(audio.none { it.second })
                }
                awaitCondition("준비된 셔터의 공개 버튼이 나타나지 않았습니다") {
                    view.findViewWithTag<Button>("gacha:reveal")?.isEnabled == true
                }
                val (reveal, scene) = onUi {
                    val reveal = button(view, "gacha:reveal")
                    val scene = view.findViewWithTag<View>("gacha:reveal-scene")
                    assertEquals(View.VISIBLE, reveal.visibility)
                    assertTrue("셔터 준비 시간은 등급과 무관하다",
                        SystemClock.uptimeMillis() - checkNotNull(waitStartedAt) >= 1_980L)
                    assertTrue(scene.isClickable)
                    assertEquals(listOf(Rarity.COMMON to false), audio)
                    reveal to scene
                }
                // 과거의 자동 공개 시점을 넘긴 뒤에도 셔터가 닫힌 상태여야 한다.
                if (rarity == Rarity.LEGEND) SystemClock.sleep(4_500L)
                onUi {
                    assertEquals(listOf(Rarity.COMMON to false), audio)
                    assertNull(view.findViewWithTag<View>("gacha:garage"))
                    if (rarity == Rarity.RARE) reveal.performClick() else scene.performClick()
                    assertEquals(View.GONE, reveal.visibility)
                    assertFalse(reveal.isEnabled)
                    assertFalse(scene.isClickable)
                    reveal.performClick()
                    scene.performClick()
                    assertEquals(listOf(Rarity.COMMON to false, rarity to true), audio)
                    assertEquals(1, events.filterIsInstance<GachaEvent.Draw>().size)
                    if (rarity != Rarity.LEGEND) view.closeReveal()
                }
                awaitCondition("열린 셔터의 차량과 결과 카드가 유지되지 않았습니다") {
                    view.findViewWithTag<Button>("gacha:garage")?.isEnabled == true &&
                        (rarity != Rarity.LEGEND ||
                            view.findViewWithTag<View>("gacha:reference-card")?.isShown == true)
                }
                onUi { assertEquals(1, events.size) }
            }
        } finally {
            onUi { activity.finish() }
        }
    }

    private fun awaitCondition(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 6_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onUi(predicate)) return
            SystemClock.sleep(30)
        }
        fail(message)
    }

    fun testUnresolvedEconomicKeyCannotMoveToAnotherOptionAcrossNewView()=onUi {
        val events=mutableListOf<GachaEvent>();val view=make(events);val ready=GachaPreviewStates.ready()
        view.render(ready,true);button(view,"gacha:option:paid").performClick();button(view,"gacha:confirm").performClick()
        val request=(events.single() as GachaEvent.Draw).request
        view.complete(request,ContractResult.Failure(ContractError.STORAGE_FAILURE))
        val offers=(ready.screen as GachaScreen.Offers).value
        val restored=GachaView(instrumentation.targetContext,events::add,view.uiState)
        val moved=offers.copy(options=offers.options.map { if(it.id=="paid") it.copy(requestId=RequestId("replacement-paid")) else it.copy(requestId=request.input.option.requestId) })
        restored.render(GachaFrame(2,GachaScreen.Offers(moved)),true)
        val free=button(restored,"gacha:option:free");assertFalse(free.isEnabled);free.performClick()
        assertEquals(1,events.size);assertNull(restored.uiState.pending)
        assertTrue(restored.resolveFailedRequest(request.input.option.requestId!!))
        button(restored,"gacha:option:free").performClick();button(restored,"gacha:confirm").performClick()
        assertEquals(2,events.size)
    }
    fun testLateGarageCompletionKeepsSecondAttemptLockedAfterNewViewRestore()=onUi {
        val events=mutableListOf<GachaEvent>();val view=make(events);val frame=GachaPreviewStates.result()
        view.render(frame,true);button(view,"gacha:garage").performClick()
        val first=(events.single() as GachaEvent.OpenGarage).request
        view.completeGarageNavigation(first);button(view,"gacha:garage").performClick()
        val second=(events.last() as GachaEvent.OpenGarage).request
        assertFalse(first.token==second.token)
        val restored=GachaView(instrumentation.targetContext,events::add,view.uiState)
        restored.render(frame,true);restored.completeGarageNavigation(first)
        val locked=button(restored,"gacha:garage");assertFalse(locked.isEnabled);locked.performClick()
        assertEquals(second,restored.uiState.garagePending);assertEquals(2,events.size)
        restored.completeGarageNavigation(second);button(restored,"gacha:garage").performClick()
        assertEquals(3,events.size)
    }
    fun testLongInputAndCurrentStatesFitNarrowPortraitAndLandscapeWidths() {
        val ready=GachaPreviewStates.ready();val value=(ready.screen as GachaScreen.Offers).value
        val long=ready.copy(screen=GachaScreen.Offers(value.copy(options=value.options.map { it.copy(title="긴 옵션 설명 ".repeat(20),description="비용과 조건 설명 ".repeat(30)) })))
        for((w,h) in listOf(320 to 440,840 to 1184,1360 to 948)) for(frame in listOf(ready,long,GachaPreviewStates.guaranteed(),GachaPreviewStates.result())) {
            val view=onUi { make().apply { render(frame,true) } }
            fun layout()=onUi { val d=view.resources.displayMetrics.density;val width=(w*d).toInt();val height=(h*d).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height) }
            layout();onUi { view.render(frame,true) };layout()
            onUi { assertTrue(nodes(view).all { it.width<=view.width }) }
        }
    }
}
