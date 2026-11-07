package io.drivegrowth.app.ui.shop

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.core.contract.*

/** 장치 슬롯 배정 후 실행할 실제 Android 위젯 시험. fixture는 실제 저장 시험이 아니다. */
@Suppress("DEPRECATION")
class ShopViewTest : InstrumentationTestCase() {
    private fun <T> onUi(block: () -> T): T {
        var value: T? = null; var failure: Throwable? = null
        instrumentation.runOnMainSync { try { value = block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun text(view: View) = views(view).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun button(view: View, tag: String): Button = view.findViewWithTag(tag)
    private fun layout(view: View, width: Int, height: Int) = onUi {
        val density = view.resources.displayMetrics.density
        val w = (width * density).toInt(); val h = (height * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    fun testCarWashConditionAndCoreFactsInBothLayouts() {
        val state = ShopPreviewStates.normal()
        val view = onUi { ShopView(instrumentation.targetContext) {}.apply { render(state, true) } }
        for ((w, h) in listOf(840 to 1184, 1360 to 948, 480 to 500)) {
            layout(view, w, h)
            // 미부착 위젯은 post를 실행하지 않을 수 있어 실제 크기를 적용한 뒤 다시 그린다.
            onUi { view.render(state, true) }; layout(view, w, h)
            onUi {
                val shown = text(view)
                assertTrue(shown.contains("세차장")); assertTrue(shown.contains("가게 레벨 3 필요 · 현재 레벨 2"))
                assertTrue(shown.contains("캐스퍼")); assertTrue(shown.contains("보유 / 공간 · 2 / 5"))
                val label = views(view).filterIsInstance<TextView>().single { it.text.contains("가게 레벨 3 필요") }
                assertTrue(label.width > 0); assertTrue(label.height > 0)
                assertEquals(0, label.layout.getEllipsisCount(0))
            }
        }
    }

    fun testPreviewCancelAndFailureKeepCommittedFacts() {
        val events = mutableListOf<ShopEvent>()
        val view = onUi { ShopView(instrumentation.targetContext, events::add).apply { render(ShopPreviewStates.normal(), true) } }
        onUi {
            button(view, "shop:preview:blue-sign").performClick()
            assertTrue(text(view).contains("변경안 · 파란 가게"))
            assertTrue(text(view).contains("드라이브 가게"))
            assertTrue(text(view).contains("2,400 게임머니")); assertTrue(events.isEmpty())
            button(view, "shop:cancel").performClick(); assertFalse(text(view).contains("변경안 · 파란 가게"))
            button(view, "shop:preview:blue-sign").performClick(); button(view, "shop:confirm").performClick()
            val request = (events.single() as ShopEvent.Submit).request
            view.complete(request, ContractResult.Failure(ContractError.STORAGE_FAILURE))
            assertTrue(text(view).contains("STORAGE_FAILURE")); assertTrue(text(view).contains("2,400 게임머니"))
            assertFalse(text(view).contains("변경안 · 파란 가게"))
        }
    }

    fun testGateAndStaleDetachedButtonsCannotSubmit() {
        val events = mutableListOf<ShopEvent>(); val state = ShopPreviewStates.normal()
        val view = onUi { ShopView(instrumentation.targetContext, events::add).apply { render(state, true) } }
        onUi {
            button(view, "shop:preview:expand").performClick()
            val oldConfirm = button(view, "shop:confirm")
            view.render(state, false); oldConfirm.performClick()
            assertTrue(events.isEmpty()); assertFalse(text(view).contains("2,400"))
            assertEquals(0, views(view).filterIsInstance<Button>().size)
            view.render(state.copy(revision = Revision(13)), true); oldConfirm.performClick()
            assertTrue(events.isEmpty()); assertNull(view.uiState.selection)
        }
    }

    fun testPendingRestoreAndCallbackNeedExternalCommittedRefresh() {
        val events = mutableListOf<ShopEvent>(); val state = ShopPreviewStates.normal()
        val view = onUi { ShopView(instrumentation.targetContext, events::add).apply { render(state, true) } }
        onUi {
            button(view, "shop:preview:expand").performClick(); button(view, "shop:confirm").performClick()
            button(view, "shop:confirm").performClick(); view.render(state, true)
            assertEquals(1, events.size)
            val restored = ShopView(instrumentation.targetContext, events::add)
            restored.restoreUiState(view.uiState); restored.render(state, true)
            assertEquals(1, events.size)
            assertTrue(text(restored).contains("요청 처리 중"))
            val request = restored.uiState.pending!!
            restored.complete(request.copy(requestId = RequestId("unrelated")), ContractResult.Success(Unit)); assertNotNull(restored.uiState.pending)
            restored.complete(request, ContractResult.Success(Unit))
            assertTrue(text(restored).contains("보유 / 공간 · 2 / 5")); assertTrue(text(restored).contains("확정 상태 재조회"))
            restored.render(state.copy(revision = Revision(13), level = 3, capacity = 7, balance = GameMoney(900)), true)
            assertTrue(text(restored).contains("보유 / 공간 · 2 / 7")); assertTrue(text(restored).contains("900 게임머니"))
        }
    }

    fun testMissingFactsAndLoadStatesNeverShowInventedZeroOrSuccess() {
        val events = mutableListOf<ShopEvent>()
        val view = onUi { ShopView(instrumentation.targetContext, events::add) }
        onUi {
            view.render(ShopPreviewStates.missing(), true)
            assertTrue(text(view).contains("확정 잔액 · 정보 미제공"))
            assertTrue(text(view).contains("승인된 장식·배치 규칙 미제공"))
            assertFalse(text(view).contains("0 게임머니"))
            view.render(ShopScreenState.Loading, true); assertTrue(text(view).contains("불러오는 중"))
            view.render(ShopScreenState.Empty("프로필 확인 필요"), true); assertTrue(text(view).contains("프로필 확인 필요"))
            view.render(ShopScreenState.Failure("저장 조회 실패"), true)
            button(view, "shop:retry").performClick(); button(view, "shop:retry").performClick(); assertEquals(1, events.size)
            view.render(ShopScreenState.Unsupported("미지원 공급자"), true)
            assertFalse(views(view).filterIsInstance<Button>().any { it.tag == "shop:retry" })
        }
    }

    fun testModeSwitchBackAndLockedFacility() {
        val events = mutableListOf<ShopEvent>()
        val view = onUi { ShopView(instrumentation.targetContext, events::add).apply { render(ShopPreviewStates.normal(), true) } }
        onUi {
            assertFalse(button(view, "shop:preview:fuel").isEnabled)
            button(view, "shop:preview:fuel").performClick(); assertNull(view.uiState.selection)
            button(view, "shop:mode:VEHICLES").performClick(); assertEquals(ShopMode.VEHICLES, view.uiState.mode)
            assertTrue(text(view).contains("캐스퍼")); assertTrue(text(view).contains("세차장"))
            button(view, "shop:preview:blue-sign").performClick(); assertTrue(view.handleBack()); assertNull(view.uiState.selection)
            assertFalse(view.handleBack()); assertTrue(events.isEmpty())
        }
    }
    fun testFirstRestoreAfterRenderInvalidatesOldContentAndButtons() = onUi {
        val events = mutableListOf<ShopEvent>()
        val old = ShopPreviewStates.normal().copy(revision = Revision(3))
        val latest = old.copy(revision = Revision(5),capacity = 7,balance = GameMoney(900))
        val saved = ShopInteraction({}).apply { render(latest,true) }.uiState
        val view = ShopView(instrumentation.targetContext,events::add)
        view.render(old,true); button(view,"shop:preview:expand").performClick()
        val stale = button(view,"shop:confirm")
        view.restoreUiState(saved)
        assertTrue(text(view).contains("불러오는 중")); assertFalse(text(view).contains("2,400"))
        stale.performClick(); assertTrue(events.isEmpty())
        view.render(old,true); assertFalse(text(view).contains("2,400"))
        view.render(latest,false); assertTrue(views(view).none { it is Button })
        view.render(latest,true); assertTrue(text(view).contains("900 게임머니"))
        button(view,"shop:preview:expand").performClick(); button(view,"shop:confirm").performClick()
        assertEquals(Revision(5),(events.single() as ShopEvent.Submit).request.selection.revision)
    }

}
