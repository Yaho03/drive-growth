package io.drivegrowth.app.ui.results

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import io.drivegrowth.core.rental.RentalReceipt
import java.math.BigDecimal
import java.math.RoundingMode

/** 실제 View/mapper를 사용한다. DB 시험1은 자기 고유 DB, 나머지 표시는 명시 fixture이며 SDK/실차 시험이 아니다. */
@Suppress("DEPRECATION")
class ResultsRentalBasisViewTest : InstrumentationTestCase() {
    private fun <T> ui(block: () -> T): T {
        var value: T? = null; var failure: Throwable? = null
        instrumentation.runOnMainSync { try { value = block() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }; @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun <T> ok(r: ContractResult<T>): T { assertTrue(r.toString(), r is ContractResult.Success); return (r as ContractResult.Success).value }
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun shown(v: View) = all(v).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    /** 기존 정산 카드의 ID 표시는 범위 밖이다. 새 시간 상세에서만 내부 식별/기술 설명을 검사한다. */
    private fun assertNoInternalBasisCopy(text: String, basis: RentalResultBasis) {
        val detail = text.substringAfter("저장된 영업 시간 근거")
        for (internal in listOf(basis.rentalId.value, basis.profileId.value, basis.ruleVersion.value,
            "영업 요청:", "경제용 원점", "당시 정책", "밀리초까지", "구간별 금액", "비활성"))
            assertFalse("일반 시간 상세에 내부 설명이 노출됨: $internal", detail.contains(internal))
        basis.periods.mapNotNull { it.policy?.version?.value }.forEach { assertFalse(detail.contains(it)) }
    }

    private fun layout(v: View, width: Int, height: Int) {
        val density = v.resources.displayMetrics.density
        fun pass() { val w = (width * density).toInt(); val h = (height * density).toInt()
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); v.layout(0, 0, w, h) }
        ui { pass() }; instrumentation.waitForIdleSync(); ui { pass() }
    }
    private val settlement = Settlement(SettlementId("basis-view"), Transaction(TransactionId("basis-tx"), RequestId("rental/settle/r"),
        TransactionKind.RENTAL, InputSource.SIMULATION, RuleVersion("original-rental"), MoneyChange(MoneyDirection.CREDIT, GameMoney(348))),
        GameMoney(448), null, EpochMillis(5001))
    private val rental = CompletedRental(RequestId("r"), OwnedVehicleId("original-car"), RentalReceipt(settlement, false, false, false, GameMoney(0), null))
    private fun basis(periods: List<RentalIncomePeriod>) = RentalResultBasis(rental.rentalId, settlement.settlementId, ProfileId("view-profile"),
        settlement.transaction.source, settlement.transaction.ruleVersion, periods.first().from, periods.last().through, periods)
    private fun content(value: Observation<RentalResultBasis>): ResultsScreenState.Content = ResultsHostMapper().history(ContractResult.Success(
        SettlementHistorySnapshot(GameIdentity(ProfileId("view-profile"), InputSource.SIMULATION), listOf(SettlementHistoryEntry(settlement, false, rental,
            rentalBasis = value))))) as ResultsScreenState.Content
    private fun driving(from: Long, through: Long) = RentalIncomePeriod(EpochMillis(from), EpochMillis(through), RentalIncomeKind.DRIVING, null,
        through - from, 0, 0, (through - from).toBigDecimal(), null)
    private fun open(state: ResultsScreenState.Content, events: MutableList<ResultsEvent> = mutableListOf()) = ui {
        ResultsView(instrumentation.targetContext, events::add).apply { render(state, true); restoreSelection(state.results.single().settlement.settlementId) }
    }

    fun testActualStoredMixedBasisRendersAfterReopenAndReadDoesNotWrite() {
        val context = instrumentation.targetContext; val name = "results-basis-view-${System.nanoTime()}.db"
        var store = SqliteGameChangeStore(context, InputSource.SIMULATION, name)
        try {
            val car = OwnedVehicle(OwnedVehicleId("results-basis-car"), VehicleId("model"), 1, Revision(0), VehicleLifecycle.OWNED,
                Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
            ok(store.commitGameChange(GameChange(RequestId("results-basis-add"), TransactionKind.GARAGE, InputSource.SIMULATION,
                RuleVersion("grant"), MoneyChange(MoneyDirection.CREDIT, GameMoney(100)), VehicleWrite.Add(car))))
            val id = RequestId("results-basis-rental")
            val catalog = VehicleCatalogSnapshot(ContentVersion("fixture"), listOf(VehicleDefinition(car.vehicleId, "저장된 시험차")))
            ok(store.rental.assign(RentalRequest(id, car.vehicleId, 6), catalog, mapOf(car.vehicleId to RentalCategory.SEDAN),
                mapOf(car.vehicleId to GameMoney(100)), RentalTerms(RuleVersion("original-rental"), SimulatedCustomer(30, 36),
                    99, EpochMillis(18000000), 15, GameMoney(0), RoundingMode.DOWN)))
            ok(store.rental.account(id, EpochMillis(18000000), EpochMillis(19800000), null))
            ok(store.rental.accountInactive(id, EpochMillis(19800000), EpochMillis(32400000), EpochMillis(0)))
            ok(store.rental.account(id, EpochMillis(32400000), EpochMillis(36000000), null))
            ok(store.rental.account(id, EpochMillis(36000000), EpochMillis(39600000), EpochMillis(36000000)))
            val original = ok(store.rental.settle(id)); assertEquals(GameMoney(448), original.settlement.balance)
            store.close(); store = SqliteGameChangeStore(context, InputSource.SIMULATION, name)
            val snapshot = ok(SettlementHistoryReader(store).read())
            val entry = snapshot.entries.single { it.rental != null }
            val state = ResultsHostMapper().history(ContractResult.Success(snapshot.copy(entries = listOf(entry)))) as ResultsScreenState.Content
            val savedBasis = (entry.rentalBasis as Observation.Available).value
            assertEquals(RuleVersion("original-rental"), savedBasis.ruleVersion)
            assertEquals(RuleVersion("parking-income-v1"), savedBasis.periods[1].policy!!.version)
            val before = ok(store.read { db -> db.rawQuery("SELECT payload FROM rental_result_basis WHERE rental_id=?", arrayOf(id.value)).use { assertTrue(it.moveToFirst()); it.getString(0) } })
            val view = open(state)
            ui {
                val text = shown(view)
                assertTrue(text, text.contains("정상 적용 시간: 2시간"))
                assertTrue(text.contains("감액 적용 시간: 3시간")); assertTrue(text.contains("상한 제외 시간: 1시간"))
                assertTrue(text.contains("자동 영업 반영")); assertTrue(text.contains("처음 30분 정상"))
                assertTrue(text.contains("이후 30%")); assertTrue(text.contains("인정 상한 8시간"))
                assertTrue(text.contains("수익 계산 기준 시각:")); assertTrue(text.contains("UTC"))
                assertNoInternalBasisCopy(text, savedBasis)
                assertTrue(text.contains("확정 매출: 348 게임머니")); assertTrue(text.contains(entry.settlement.settlementId.value))
                assertFalse(text.contains("확정 매출: 448 게임머니"))
            }
            assertEquals(original, ok(store.rental.settle(id)))
            val after = ok(store.read { db -> db.rawQuery("SELECT payload FROM rental_result_basis WHERE rental_id=?", arrayOf(id.value)).use { assertTrue(it.moveToFirst()); it.getString(0) } })
            assertEquals(before, after); assertEquals(GameMoney(448), ok(store.balance()))
        } finally { store.close(); context.deleteDatabase(name) }
    }

    fun testArchivedPolicyAndMillisecondsAreDisplayedWithoutCurrentDefaults() {
        val policy = RentalIncomePolicy(RuleVersion("archived-fixture"), 1001, 3001, BigDecimal("0.58"))
        val p = RentalIncomePeriod(EpochMillis(0), EpochMillis(5001), RentalIncomeKind.INACTIVE, EpochMillis(0), 1001, 2000, 2000, BigDecimal("2161"), policy)
        val original = basis(listOf(p))
        val state = content(Observation.Available(original))
        val mapped = (state.results.single().rentalBasis as Observation.Available).value
        assertSame(original, mapped)
        assertEquals(RuleVersion("archived-fixture"), mapped.periods.single().policy!!.version)
        val view = open(state)
        ui {
            val text = shown(view)
            assertTrue(text, text.contains("정상 적용 시간: 1.001초")); assertTrue(text.contains("감액 적용 시간: 2초"))
            assertTrue(text.contains("상한 제외 시간: 2초")); assertTrue(text.contains("58%")); assertTrue(text.contains("3.001초"))
            assertTrue(text.contains("적용 기준:"))
            assertTrue(text.contains("자동 영업 반영")); assertNoInternalBasisCopy(text, mapped)
        }
    }

    fun testMissingBasisKeepsUnknownInsteadOfInventingZeroTimeFromMoney() {
        val view = open(content(Observation.Missing(MissingReason.NO_DATA)))
        ui {
            val text = shown(view)
            assertTrue(text, text.contains("감액·상한 적용 내역: 확인할 수 없어요."))
            assertFalse(text.contains("정상 적용 시간:")); assertFalse(text.contains("상한 제외 시간: 0초"))
            assertTrue(text.contains("확정 매출: 348 게임머니"))
        }
    }

    fun testRestrictedLoadingErrorAndEmptyHideStoredBasisAndOldActions() {
        val events = mutableListOf<ResultsEvent>(); val state = content(Observation.Available(basis(listOf(driving(0, 1)))))
        val view = open(state, events)
        ui {
            val old = view.findViewWithTag<Button>("results:mark:basis-view")
            view.render(state, false); old.performClick()
            assertTrue(events.none { it is ResultsEvent.MarkViewed }); assertFalse(shown(view).contains("0.001초"))
            for (next in listOf(ResultsScreenState.Loading, ResultsScreenState.Error("읽기 실패"), ResultsScreenState.Missing("시간 근거 없음"),
                ResultsScreenState.Content(emptyList(), ResultsOrigin.STORED, true))) {
                view.render(next, true); assertFalse(shown(view).contains("정상 적용 시간:"))
            }
        }
    }

    fun testNewViewAndBundleKeepScopeSelectionAndPendingWithoutInventingAcknowledgement() {
        val state = content(Observation.Available(basis(listOf(driving(0, 1)))))
        val old = ui {
            ResultsView(instrumentation.targetContext) {}.apply { render(state, true) }
        }
        val saved = ui {
            old.findViewWithTag<Button>("results:scope:ALL").performClick()
            old.findViewWithTag<Button>("results:open:basis-view").performClick()
            old.findViewWithTag<Button>("results:mark:basis-view").performClick()
            ResultsStateBundle.encode(old.uiState)
        }
        val events = mutableListOf<ResultsEvent>()
        val recreated = ui { ResultsView(instrumentation.targetContext, events::add).apply {
            restoreUiState(ResultsStateBundle.decode(saved)); render(state, true)
        } }
        ui {
            assertEquals(settlement.settlementId, recreated.selectedSettlementId); assertEquals(ResultsScope.ALL, recreated.uiState.scope)
            assertEquals(ResultsReadState.Pending, recreated.uiState.readStates[settlement.settlementId])
            assertFalse(recreated.findViewWithTag<Button>("results:mark:basis-view").isEnabled)
            assertTrue(shown(recreated).contains("정상 적용 시간: 0.001초")); assertTrue(events.isEmpty())
            recreated.completeMarkViewed(settlement.settlementId, ContractResult.Success(Unit))
            assertEquals(ResultsReadState.Done, recreated.uiState.readStates[settlement.settlementId])
        }
    }

    fun testLongPeriodsAndHugeDurationFitNarrowAndWideWithoutLosingDetailScroll() {
        val periods = (0 until 40).map { driving(it * 1001L, (it + 1) * 1001L) }
        val state = content(Observation.Available(basis(periods))); val view = open(state)
        layout(view, 440, 360)
        ui { view.findViewWithTag<ScrollView>("results:detail").scrollTo(0, 600) }
        val before = ui { view.findViewWithTag<ScrollView>("results:detail").scrollY }; assertEquals(600, before)
        ui { view.render(ResultsScreenState.Loading, true); view.render(state, true) }; layout(view, 440, 360)
        ui {
            assertEquals(before, view.findViewWithTag<ScrollView>("results:detail").scrollY)
            val overflow = all(view).filterIsInstance<TextView>().filter { t -> t.layout?.let { l -> (0 until l.lineCount).any { l.getLineWidth(it) > l.width + 2 } } == true }
            assertTrue(overflow.map { it.text }.toString(), overflow.isEmpty())
            assertTrue(shown(view).contains("구간 40"))
        }
        ui { view.render(content(Observation.Available(basis(listOf(driving(0, Long.MAX_VALUE))))), true) }
        layout(view, 1360, 948); ui { view.render(content(Observation.Available(basis(listOf(driving(0, Long.MAX_VALUE))))), true) }; layout(view, 1360, 948)
        ui { assertTrue(shown(view).contains("2,562,047,788,015시간 12분 55.807초")) }
    }
}
