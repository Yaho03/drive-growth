package io.drivegrowth.app.ui.results

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.graphics.drawable.BitmapDrawable
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.data.SettlementHistoryCause
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.app.ui.presentation.RewardCelebrationView
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.SettlementId
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.rental.CompletedRental
import io.drivegrowth.core.rental.RentalReceipt
import java.time.Instant
import java.util.TimeZone

/** 실제 Android 위젯의 상태·layout 시험. assertion 실패는 시험 스레드로 돌려준다. */
@Suppress("DEPRECATION")
class ResultsViewTest : InstrumentationTestCase() {
    private fun <T> onUi(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { result = block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun texts(v: View) = descendants(v).filterIsInstance<TextView>().map { it.text.toString() }
    private fun button(v: View, tag: String) = v.findViewWithTag<Button>(tag)
    private fun open(view: ResultsView, id: String) { button(view, "results:open:$id").performClick() }
    private fun layout(view: ResultsView, widthDp: Int, heightDp: Int) {
        val d = view.resources.displayMetrics.density
        fun pass() {
            val w = (widthDp * d).toInt(); val h = (heightDp * d).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
        }
        onUi { pass() }; instrumentation.waitForIdleSync(); onUi { pass() }
    }

    fun testDetailsUseMoneyChangeAndBalanceAsDifferentFacts() {
        val view = onUi { ResultsView(instrumentation.targetContext) {}.apply { render(ResultsPreviewStates.normal, true) } }
        layout(view, 480, 500)
        onUi { open(view, "fixture-result-1") }
        val labels = onUi { texts(view) }
        val shown = labels.joinToString("\n")
        assertTrue(shown.contains("−350 게임머니"))
        assertTrue(shown.contains("정산 후 잔액 9,001 게임머니"))
        assertTrue(shown.contains("차량을 얻는 데 쓴 금액\n0 게임머니"))
        assertFalse(shown.contains("fixture-request-1"))
        assertFalse(shown.contains("ui-fixture-v1"))
        assertFalse(shown.contains("fixture-free-1"))
        assertFalse(shown.contains("hyundai_casper"))
        assertFalse(shown.contains("revision"))
        assertNotNull(onUi { view.findViewWithTag<Button>("results:mark:fixture-result-1") })
        assertTrue(shown.contains("모의 입력"))
        assertTrue(shown.contains("고정 시험 입력"))
        assertTrue(instrumentation.targetContext.getString(R.string.results_details_unsupported) in labels)
        assertFalse(shown.contains("+9,001"))
    }

    fun testGarageCategoriesRequireSavedAcquisitionEvidence() = onUi {
        val template = ResultsPreviewStates.normal
        val base = template.results.last()
        val settled = base.settlement
        val awarded = checkNotNull(settled.changedVehicle).copy(level = 1, levelProgressPercent = 0)
        val first = awarded.copy(ownedVehicleId = OwnedVehicleId("initial-grant"),
            acquisitionSource = VehicleAcquisitionSource.INITIAL_GRANT, rarity = Rarity.COMMON)
        val paid = awarded.copy(acquisitionCost = GameMoney(500))
        fun row(id: String, request: RequestId, vehicle: OwnedVehicle?, money: MoneyChange? = null,
                cause: SettlementHistoryCause? = null) =
            base.copy(settlement = settled.copy(settlementId = SettlementId(id), changedVehicle = vehicle,
                transaction = settled.transaction.copy(requestId = request, money = money)), cause = cause)
        val entries = listOf(
            row("first", FirstGrant.REQUEST_ID, first, MoneyChange(MoneyDirection.CREDIT, GameMoney(1000)),
                SettlementHistoryCause.INITIAL_GRANT),
            row("draw-paid", RequestId("draw-paid"), paid, MoneyChange(MoneyDirection.DEBIT, GameMoney(500)),
                SettlementHistoryCause.GACHA),
            row("draw-free", RequestId("draw-free"), awarded, cause = SettlementHistoryCause.GACHA),
            row("growth", RequestId("growth"), awarded.copy(level = 2, revision = Revision(1))),
            row("sold", RequestId("sold"), awarded.copy(lifecycle = VehicleLifecycle.SOLD, revision = Revision(1))),
            row("first-generic-add", FirstGrant.REQUEST_ID, first, MoneyChange(MoneyDirection.CREDIT, GameMoney(1000))),
            row("draw-generic-add", RequestId("draw-generic-add"), paid, MoneyChange(MoneyDirection.DEBIT, GameMoney(500))),
            row("unknown", RequestId("unknown"), null),
        )
        val view = ResultsView(instrumentation.targetContext) {}
        view.render(template.copy(results = entries), true)

        val expected = listOf("첫 지급", "차량 뽑기", "차량 뽑기") + List(5) { "차량 관리" }
        entries.zip(expected).forEach { (entry, kind) ->
            val id = entry.settlement.settlementId.value
            assertTrue(button(view, "results:open:$id").contentDescription.contains("· $kind ·"))
            open(view, id)
            assertTrue(kind in texts(view))
            if (id == "draw-free") assertTrue("기록만" in texts(view))
            assertTrue(view.closeDetail())
        }
    }

    private fun accidentResult(credit: GameMoney = GameMoney(0)): ResultPresentation {
        val template = ResultsPreviewStates.normal.results.first().settlement
        val rentalId = RequestId("tested-rental")
        val settlement = template.copy(
            settlementId = SettlementId("tested-rental-result"),
            transaction = template.transaction.copy(
                requestId = RequestId("rental/settle/${rentalId.value}"),
                kind = TransactionKind.RENTAL,
                money = MoneyChange(MoneyDirection.CREDIT, credit),
            ),
            changedVehicle = null,
        )
        return ResultPresentation(settlement, rental = CompletedRental(rentalId, OwnedVehicleId("tested-car"),
            RentalReceipt(settlement, accident = true, matchReview = false, washReview = false,
                facilityBonus = GameMoney(0), regularCustomer = null)))
    }

    fun testStoredUnreadAccidentOpensOnceWithoutMarkingViewed() = onUi {
        val result = accidentResult()
        val id = result.settlement.settlementId
        val state = ResultsScreenState.Content(listOf(result), ResultsOrigin.STORED, historyAvailable = true)
        val events = mutableListOf<ResultsEvent>()
        val view = ResultsView(instrumentation.targetContext, events::add)

        view.render(state, true)
        assertNull(view.findViewWithTag<View>("results:rental-accident-effect"))
        open(view, id.value)
        assertTrue("가상 영업 사고: 발생" in texts(view))
        assertEquals(ValueAnimator.areAnimatorsEnabled(),
            view.findViewWithTag<View>("results:rental-accident-effect") != null)
        assertEquals(listOf(id.value), view.saveDisplayedAccidents())
        assertTrue(descendants(view).none { it is RewardCelebrationView })
        assertEquals(listOf(ResultsEvent.SelectResult(id)), events.toList())
        view.render(state, true)
        assertTrue(view.closeDetail())
        assertNull(view.findViewWithTag<View>("results:rental-accident-effect"))
        open(view, id.value)
        assertNull(view.findViewWithTag<View>("results:rental-accident-effect"))

        val restored = ResultsView(instrumentation.targetContext, events::add)
        restored.restoreDisplayedAccidents(view.saveDisplayedAccidents())
        restored.restoreUiState(view.uiState)
        restored.render(state, true)
        assertNull(restored.findViewWithTag<View>("results:rental-accident-effect"))
        assertTrue(restored.closeDetail())
        open(restored, id.value)
        assertNull(restored.findViewWithTag<View>("results:rental-accident-effect"))
        assertTrue(events.none { it is ResultsEvent.MarkViewed })
    }

    fun testAccidentRequiresMatchingUnreadStoredRentalAndCurrentAccess() = onUi {
        val result = accidentResult(GameMoney(15))
        val id = result.settlement.settlementId
        val rental = checkNotNull(result.rental)
        val excluded = listOf(
            result to ResultsOrigin.FIXTURE,
            result.copy(viewed = true) to ResultsOrigin.STORED,
            result.copy(rental = null) to ResultsOrigin.STORED,
            result.copy(rental = rental.copy(receipt = rental.receipt.copy(accident = false))) to ResultsOrigin.STORED,
            result.copy(rental = rental.copy(rentalId = RequestId("different-rental"))) to ResultsOrigin.STORED,
            result.copy(rental = rental.copy(receipt = rental.receipt.copy(
                settlement = result.settlement.copy(balance = GameMoney(42))))) to ResultsOrigin.STORED,
        )
        for ((row, origin) in excluded) {
            val view = ResultsView(instrumentation.targetContext) {}
            view.restoreUiState(ResultsUiState(scope = ResultsScope.ALL))
            view.render(ResultsScreenState.Content(listOf(row), origin, historyAvailable = true), true)
            open(view, id.value)
            assertTrue(view.saveDisplayedAccidents().isEmpty())
            assertNull(view.findViewWithTag<View>("results:rental-accident-effect"))
        }

        val state = ResultsScreenState.Content(listOf(result), ResultsOrigin.STORED, historyAvailable = true)
        val view = ResultsView(instrumentation.targetContext) {}
        view.render(state, true)
        val oldButton = button(view, "results:open:${id.value}")
        view.render(state, false)
        oldButton.performClick()
        assertNull(view.selectedSettlementId)
        assertTrue(view.saveDisplayedAccidents().isEmpty())
        view.render(state, true)
        open(view, id.value)
        assertEquals(ValueAnimator.areAnimatorsEnabled(),
            view.findViewWithTag<View>("results:rental-accident-effect") != null)
        assertEquals(1, descendants(view).count { it is RewardCelebrationView })
        view.render(ResultsScreenState.Loading, true)
        assertNull(view.findViewWithTag<View>("results:rental-accident-effect"))
        view.render(state, true)
        assertNull(view.findViewWithTag<View>("results:rental-accident-effect"))
    }

    fun testAttachedAccidentEffectNeverDrawsAfterHideBeforeFirstFrame() {
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        val host = instrumentation.startActivitySync(
            Intent(context, CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options,
        )
        try {
            val root = onUi { FrameLayout(host).also(host::setContentView) }
            val deadline = SystemClock.uptimeMillis() + 4_000
            while (!onUi { root.isLaidOut && root.hasWindowFocus() } && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
            }
            onUi {
                assertTrue(root.isLaidOut && root.hasWindowFocus())
                assertTrue("픽셀 대조에는 시스템 모션이 켜져야 한다", ValueAnimator.areAnimatorsEnabled())
                for (hideBeforeDraw in listOf(false, true)) {
                    val panel = FrameLayout(host)
                    val effect = RentalAccidentEffectView(host)
                    panel.addView(effect, FrameLayout.LayoutParams(76, 42))
                    root.addView(panel, FrameLayout.LayoutParams(76, 42))
                    panel.measure(View.MeasureSpec.makeMeasureSpec(76, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(42, View.MeasureSpec.EXACTLY))
                    panel.layout(0, 0, 76, 42)
                    if (hideBeforeDraw) {
                        panel.visibility = View.INVISIBLE
                        panel.visibility = View.VISIBLE
                    }
                    assertTrue(effect.isAttachedToWindow && effect.hasWindowFocus() && effect.isShown)
                    val bitmap = Bitmap.createBitmap(76, 42, Bitmap.Config.ARGB_8888)
                    try {
                        effect.draw(Canvas(bitmap))
                        assertEquals(!hideBeforeDraw, bitmap.getPixel(20, 33) != Color.TRANSPARENT)
                    } finally {
                        bitmap.recycle()
                        root.removeView(panel)
                    }
                }
            }
        } finally {
            onUi { host.finish() }
        }
    }

    fun testAccidentEffectHiddenBeforeFirstDrawStaysFinished() = onUi {
        val effect = RentalAccidentEffectView(instrumentation.targetContext)
        effect.onVisibilityAggregated(false)
        assertFalse("초기 부착 전에는 가시성을 기다린다", effect.finished)
        effect.onVisibilityAggregated(true)
        effect.onVisibilityAggregated(false)
        effect.onVisibilityAggregated(true)
        assertTrue("첫 프레임 전 숨겨져도 복귀에서 재생하지 않는다", effect.finished)
    }

    fun testDateUsesLocalTimeAndKeepsDayAcrossMidnight() = onUi {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            val view = ResultsView(instrumentation.targetContext) {}
            val normal = ResultsPreviewStates.normal
            val original = normal.results.first()
            val epoch = EpochMillis(Instant.parse("2026-11-10T16:05:00Z").toEpochMilli())
            val row = original.copy(settlement = original.settlement.copy(confirmedAt = epoch))
            view.render(normal.copy(results = listOf(row)), true)
            val local = "2026년 11월 11일 오전 1:05"
            assertTrue(local in texts(view))
            assertFalse(texts(view).any { it.contains("GMT+") })
            assertTrue(button(view, "results:open:${row.settlement.settlementId.value}").contentDescription.contains(local))
            open(view, row.settlement.settlementId.value)
            assertTrue(local in texts(view))
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    fun testWideListUsesWindowUntilDetailIsSelected() {
        val base = instrumentation.targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = 1f })
        val view = onUi { ResultsView(context) {}.apply { render(ResultsPreviewStates.normal, true) } }
        layout(view, 1360, 948)
        onUi { view.render(ResultsPreviewStates.normal, true) }
        layout(view, 1360, 948)
        val before = onUi {
            assertNull(view.findViewWithTag<ScrollView>("results:detail"))
            val list = view.findViewWithTag<ScrollView>("results:list")
            assertEquals(view.width - view.paddingLeft - view.paddingRight, list.width)
            open(view, "fixture-result-1")
            list.width
        }
        layout(view, 1360, 948)
        onUi {
            assertNotNull(view.findViewWithTag<ScrollView>("results:detail"))
            assertTrue(view.findViewWithTag<ScrollView>("results:list").width < before)
        }
    }

    fun testVehicleArtUsesTheSettledVehicleIdAndHidesWhenRestricted() = onUi {
        val context = instrumentation.targetContext
        val source = ResultsPreviewStates.normal
        val original = source.results.first { it.settlement.changedVehicle != null }
        val id = checkNotNull(original.settlement.changedVehicle).vehicleId
        val state = source.copy(results = listOf(original))
        val view = ResultsView(context) {}
        view.render(state, true)
        open(view, original.settlement.settlementId.value)
        val image = checkNotNull(view.findViewWithTag<ImageView>("results:vehicle-art:${id.value}"))
        val expected = checkNotNull(VehicleSprites(context).bitmap(id))
        assertTrue(expected.sameAs((image.drawable as BitmapDrawable).bitmap))
        view.render(state, false)
        assertNull(view.findViewWithTag<ImageView>("results:vehicle-art:${id.value}"))
    }

    fun testMarkViewedWaitsForResponseAndRetriesAfterFailure() {
        val events = mutableListOf<ResultsEvent>()
        val id = SettlementId("fixture-result-1")
        val view = onUi { ResultsView(instrumentation.targetContext, events::add).apply {
            render(ResultsPreviewStates.normal, true); open(this, id.value)
            button(this, "results:mark:${id.value}").performClick()
            button(this, "results:mark:${id.value}").performClick()
            render(ResultsPreviewStates.normal, true)
        } }
        assertEquals(1, events.filterIsInstance<ResultsEvent.MarkViewed>().size)
        assertTrue(onUi { texts(view).contains("열람 표시 저장 중") })
        assertFalse(onUi { texts(view).contains("열람 표시 저장 완료") })
        onUi {
            view.completeMarkViewed(id, ResultsPreviewStates.readFailure)
            button(view, "results:mark:${id.value}").performClick()
            view.completeMarkViewed(id, ResultsPreviewStates.readSuccess)
        }
        assertEquals(2, events.filterIsInstance<ResultsEvent.MarkViewed>().size)
        assertTrue(onUi { texts(view).contains("열람 표시 저장 완료") })
        assertFalse(onUi { button(view, "results:mark:${id.value}").isEnabled })
    }

    fun testRestrictedHidesFactsAndOldButtonsCannotEmitActions() {
        val events = mutableListOf<ResultsEvent>()
        val view = onUi { ResultsView(instrumentation.targetContext, events::add).apply { render(ResultsPreviewStates.normal, true) } }
        val stale = onUi { button(view, "results:open:fixture-result-1") }
        onUi { view.render(ResultsPreviewStates.normal, false); stale.performClick() }
        assertTrue(events.isEmpty())
        assertTrue(onUi { descendants(view).none { it is Button } })
        assertFalse(onUi { texts(view).any { it.contains("9,001") || it.contains("fixture-result") || it.contains("캐스퍼") } })
        assertFalse(onUi { view.closeDetail() })
    }

    fun testLoadingEmptyMissingUnsupportedAndExplicitRetryCompletion() {
        val events = mutableListOf<ResultsEvent>()
        val view = onUi { ResultsView(instrumentation.targetContext, events::add) }
        onUi { view.render(ResultsPreviewStates.empty, true) }
        assertTrue(onUi { texts(view).contains("미확인 정산이 없어요") })
        onUi { view.render(ResultsPreviewStates.loading, true) }
        assertTrue(onUi { texts(view).contains("미확인 결과를 불러오고 있어요") })
        onUi { view.render(ResultsPreviewStates.unsupported, true) }
        assertTrue(onUi { descendants(view).none { it is Button } })
        onUi { view.render(ResultsPreviewStates.missing, true) }
        assertFalse(onUi { texts(view).contains("확정 정산") })
        onUi {
            view.render(ResultsPreviewStates.error, true)
            button(view, "results:retry").performClick()
            view.render(ResultsPreviewStates.error, true)
            button(view, "results:retry").performClick()
        }
        assertEquals(1, events.count { it == ResultsEvent.Retry })
        onUi { view.completeRetry(); button(view, "results:retry").performClick() }
        assertEquals(2, events.count { it == ResultsEvent.Retry })
    }

    fun testRestoredSelectionMissingRecordAndClosedPendingRequest() {
        val id = SettlementId("fixture-result-1")
        val original = onUi { ResultsView(instrumentation.targetContext) {}.apply {
            render(ResultsPreviewStates.normal, true); restoreSelection(id)
            button(this, "results:mark:${id.value}").performClick()
        } }
        val restored = onUi { ResultsView(instrumentation.targetContext) {}.apply {
            restoreUiState(original.uiState); render(ResultsPreviewStates.normal, true)
        } }
        assertEquals(id, onUi { restored.selectedSettlementId })
        assertFalse(onUi { button(restored, "results:mark:${id.value}").isEnabled })
        assertTrue(onUi { restored.closeDetail() })
        assertFalse(onUi { restored.closeDetail() })
        onUi { restored.restoreSelection(id); restored.render(ResultsPreviewStates.empty, true) }
        assertTrue(onUi { texts(restored).contains("선택한 정산이 현재 목록에 없어요") })
        assertTrue(onUi { descendants(restored).filterIsInstance<Button>().none { it.tag == "results:mark:${id.value}" } })
    }

    fun testLongTextAndManyResultsFitNarrowAndWideAndPreserveListPosition() {
        val view = onUi { ResultsView(instrumentation.targetContext) {}.apply { render(ResultsPreviewStates.many, true) } }
        layout(view, 480, 360)
        onUi { view.findViewWithTag<ScrollView>("results:list").scrollTo(0, 700) }
        val before = onUi { view.findViewWithTag<ScrollView>("results:list").scrollY }
        assertEquals(700, before)
        onUi { view.render(ResultsPreviewStates.loading, true) }; layout(view, 480, 360)
        onUi { view.render(ResultsPreviewStates.many, true) }; layout(view, 480, 360)
        assertEquals(before, onUi { view.findViewWithTag<ScrollView>("results:list").scrollY })
        onUi { view.render(ResultsPreviewStates.error, true) }; layout(view, 480, 360)
        onUi { view.render(ResultsPreviewStates.many, false) }; layout(view, 480, 360)
        onUi { view.render(ResultsPreviewStates.many, true) }; layout(view, 480, 360)
        assertEquals(before, onUi { view.findViewWithTag<ScrollView>("results:list").scrollY })
        onUi { view.render(ResultsPreviewStates.longText, true); view.restoreSelection(SettlementId("fixture-result-1")) }
        layout(view, 480, 360)
        val overflows = onUi {
            descendants(view).filterIsInstance<TextView>().mapNotNull { text ->
                val content = text.layout ?: return@mapNotNull null
                // getLineWidth에는 줄 끝 공백이 포함된다. 화면에 표시되는 양쪽 경계를 확인한다.
                val lines = (0 until content.lineCount).filter { line ->
                    content.getLineLeft(line) < -2 || content.getLineRight(line) > content.width + 2
                }
                if (lines.isEmpty()) null else buildString {
                    append("${text.text} (layoutWidth=${content.width}, ")
                    append(lines.joinToString { line ->
                        "line=$line left=${content.getLineLeft(line)} right=${content.getLineRight(line)}"
                    })
                    append(")")
                }
            }
        }
        assertTrue("긴 텍스트가 가로로 넘치면 안 된다: $overflows", overflows.isEmpty())
        layout(view, 1360, 948)
        // 이 시험은 window 미부착 위젯이다. attach 전 post queue 대신 다음 render를 명시한다.
        onUi { view.render(ResultsPreviewStates.longText, true) }
        layout(view, 1360, 948)
        assertNotNull(onUi { view.findViewWithTag<ScrollView>("results:list") })
        assertNotNull(onUi { view.findViewWithTag<ScrollView>("results:detail") })
    }
}
