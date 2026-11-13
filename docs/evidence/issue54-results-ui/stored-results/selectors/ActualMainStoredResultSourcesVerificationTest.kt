package io.drivegrowth.app.host

import android.os.Process
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.data.RentalIncomeKind
import io.drivegrowth.app.data.RentalResultBasis
import io.drivegrowth.app.data.RentalResultBasisReader
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.data.SettlementHistoryReader
import io.drivegrowth.app.data.ShopOriginalResultReader
import io.drivegrowth.app.host.shop.SessionShopExpansionSource
import io.drivegrowth.app.ui.results.ResultsScope
import io.drivegrowth.app.ui.results.ResultsView
import io.drivegrowth.app.ui.shop.ShopAvailability
import io.drivegrowth.app.ui.shop.ShopCommand
import io.drivegrowth.app.ui.shop.ShopRequest
import io.drivegrowth.app.ui.shop.ShopSelection
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.RentalCategory
import io.drivegrowth.core.rental.RentalRequest
import io.drivegrowth.core.rental.SimulatedCustomer
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** #204에서 회수한 격리 SIMULATION 원장만 사용한다. Main의 gate는 실제 SDK다. */
@Suppress("DEPRECATION")
class ActualMainStoredResultSourcesVerificationTest :
    ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private val driveId = "51adc13d-10af-4678-b5ee-20881765ba93"
    private val deliveryId = RequestId("delivery-choice-1eb2c3d4-eda4-4af1-b707-f605e7758dcb")
    private val firstRentalId = RequestId("rental-visit-d2a22077-5f0f-4616-b513-5c12ea1c4b06")
    private val mixedId = RequestId("results54-stored-basis")
    private val expansionId = RequestId("results54-facility-expansion")

    private fun <T> ok(value: ContractResult<T>): T {
        assertTrue(value.toString(), value is ContractResult.Success)
        return (value as ContractResult.Success).value
    }

    private fun isolate() {
        assertEquals("io.drivegrowth.verify.results54originalf", context.packageName)
        assertTrue(context.getDatabasePath("game-ledger-simulation.db").isFile)
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        assertFalse(context.getDatabasePath("unified-simulation.db").exists())
    }

    private fun <T> read(action: (GameSession) -> T): T =
        ok(GameSessionFactory(context).open()).use { game ->
            assertEquals(InputSource.SIMULATION, game.identity.source)
            assertEquals(ProfileId("local-137dce8d-6f1d-4a96-a118-9b6b0de7e1e2"), game.identity.profileId)
            action(game)
        }

    private fun drive(game: GameSession) =
        requireNotNull(ok(game.store.observedDrivingReceipt(driveId))?.commit?.settlement)

    private fun delivery(game: GameSession) =
        requireNotNull(ok(game.store.delivery.arrivalResult(deliveryId))?.settlement)

    private fun rental(game: GameSession, id: RequestId) =
        requireNotNull(ok(game.store.rental.completedRental(id)))

    private fun stage(value: String) = Log.i("Results54OriginalF", "pid=${Process.myPid()} $value")

    fun testPrepareFromCapturedSimulatedOriginals() {
        isolate()
        read { game ->
            val ledger = game.store.rental
            assertEquals(GameMoney(360), drive(game).transaction.money?.amount)
            assertEquals(GameMoney(1480), drive(game).balance)
            assertEquals(GameMoney(120), delivery(game).transaction.money?.amount)
            assertEquals(GameMoney(1600), delivery(game).balance)
            assertEquals(GameMoney(120), rental(game, firstRentalId).receipt.settlement.transaction.money?.amount)
            assertEquals(GameMoney(1120), rental(game, firstRentalId).receipt.settlement.balance)
            assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
            assertNotNull(ok(game.store.readFirstRentalGift(game.identity.profileId)).granted)
            assertNull(ok(ledger.completedRental(mixedId)))
            assertEquals(1, ok(ledger.completedRentalCount()))
            val returned = ok(ledger.pending()).single()
            assertEquals(firstRentalId, returned.id)
            ok(ledger.finishReturn(returned.id, returned.returnEnd))
            val car = ok(game.store.listOwnedVehicles()).single()
            val content = ok(game.content)
            val definition = ok(game.store.trialDefinitions.load())
            val shortId = RequestId("results54-third-customer-precondition")
            val shortEnd = EpochMillis(returned.returnEnd.value + 3_600_000)
            fun assign(id: RequestId, hours: Int, start: EpochMillis, roll: Int) {
                ok(ledger.assign(RentalRequest(id, car.vehicleId, hours), content.catalog,
                    mapOf(car.vehicleId to RentalCategory.SMALL), definition.rentalRates,
                    RentalTerms(definition.rules, SimulatedCustomer(30, 36), roll, start, 15,
                        GameMoney(0), RoundingMode.DOWN)))
            }
            assign(shortId, 1, returned.returnEnd, 99)
            ok(ledger.account(shortId, returned.returnEnd, shortEnd, null))
            ok(ledger.settle(shortId))
            val shortReturn = ok(ledger.pending()).single { it.id == shortId }
            ok(ledger.finishReturn(shortId, shortReturn.returnEnd))

            val offset = shortReturn.returnEnd.value
            fun time(relative: Long) = EpochMillis(offset + relative)
            assign(mixedId, 6, time(18_000_000), 0)
            // #199의 6시간 원구간에서 최초 1분만 명시적 미관측으로 공급한다.
            ok(ledger.accountObserved(mixedId, time(18_060_000), time(19_800_000), null, time(18_000_000)))
            ok(ledger.accountInactive(mixedId, time(19_800_000), time(32_400_000), time(0)))
            ok(ledger.account(mixedId, time(32_400_000), time(36_000_000), null))
            ok(ledger.account(mixedId, time(36_000_000), time(39_600_000), time(36_000_000)))
            assertTrue(ok(ledger.settle(mixedId)).accident)
            ok(ledger.finishReturn(mixedId, ok(ledger.pending()).single { it.id == mixedId }.returnEnd))
            assertEquals(3, ok(ledger.completedRentalCount()))
        }
        val expansion = SessionShopExpansionSource(context, GameSessionFactory(context))
        val snapshot = ok(expansion.read())
        val option = requireNotNull(snapshot.screen.expansion)
        assertEquals(ShopAvailability.AVAILABLE, option.availability)
        val command = ok(expansion.prepare(snapshot,
            ShopRequest(expansionId, ShopSelection(snapshot.screen.revision, option), "results54-expansion")))
        val settled = ok(expansion.execute(command))
        assertEquals(TransactionKind.FACILITY, settled.transaction.kind)
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(500)), settled.transaction.money)
        assertEquals(settled, ok(expansion.finish(command)))
        stage("PREPARED_STORED_ORIGINALS")
    }

    private fun rows(game: GameSession): Map<String, List<List<String?>>> = ok(game.store.read { db ->
        listOf("account", "vehicles", "settlements", "rental_result_basis", "rentals", "shop", "facilities",
            "quest_events", "observed_driving_receipts", "delivery_outcomes", "host_command_checkpoint").associateWith { table ->
            db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) })
                }
            }
        }
    })

    private fun assertBasis(basis: RentalResultBasis, settlement: Settlement, identity: GameIdentity) {
        assertEquals(settlement.settlementId, basis.settlementId)
        assertEquals(identity.profileId, basis.profileId)
        assertEquals(identity.source, basis.source)
        assertEquals(settlement.transaction.ruleVersion, basis.ruleVersion)
        assertEquals(7_140_000L, basis.normalMillis)
        assertEquals(10_800_000L, basis.reducedMillis)
        assertEquals(3_600_000L, basis.cappedMillis)
        assertEquals(60_000L, basis.unobservedMillis)
        val gap = basis.periods.single { it.kind == RentalIncomeKind.UNOBSERVED }
        assertEquals(0, gap.weightedMillis.signum())
        assertNull(gap.policy)
        assertTrue(basis.periods.any { it.policy?.reducedMultiplier?.toPlainString() == "0.30" ||
            it.policy?.reducedMultiplier?.toPlainString() == "0.3" })
    }

    private fun tree(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()

    private fun menu(a: MainActivity) = a.window.decorView.findViewWithTag<Spinner>("host:destination")
    private fun result(a: MainActivity) = tree(a.window.decorView).filterIsInstance<ResultsView>().singleOrNull { it.isShown }

    private fun await(label: String, seconds: Long = 20, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + seconds * 1000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = check() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("CHECK_TIMEOUT: $label")
    }

    private fun click(a: MainActivity, tag: String) {
        await("활성 버튼 $tag") {
            a.window.decorView.findViewWithTag<Button>(tag)?.takeIf { it.isShown && it.isEnabled }?.performClick() == true
        }
    }

    private fun detail(a: MainActivity, id: SettlementId): List<String> {
        var captured: List<String>? = null
        await("부착된 상세와 같은 선택 $id") {
            val v = result(a) ?: return@await false
            val pane = v.findViewWithTag<View>("results:detail") ?: return@await false
            if (v.uiState.scope != ResultsScope.ALL || v.selectedSettlementId != id || !pane.isShown ||
                pane.findViewWithTag<Button>("results:mark:${id.value}")?.isEnabled != true) return@await false
            captured = tree(pane).filterIsInstance<TextView>().map { it.text.toString() }
            true
        }
        return requireNotNull(captured)
    }

    private fun checkDetail(a: MainActivity, expected: Settlement, kind: String, rentalLines: List<String> = emptyList()) {
        val labels = detail(a, expected.settlementId)
        fun money(value: GameMoney) = NumberFormat.getIntegerInstance(Locale.KOREA).format(value.units) + " 게임머니"
        val amount = requireNotNull(expected.transaction.money)
        val signed = (if (amount.direction == MoneyDirection.CREDIT) "+" else "−") + money(amount.amount)
        for (line in listOf(kind, signed, "요청 입력 출처\n모의 입력",
            "정산 후 확정 잔액 · 이번 수익 합계 아님\n${money(expected.balance)}",
            "정산 ID\n${expected.settlementId.value}", "요청 ID\n${expected.transaction.requestId.value}",
            "적용 규칙 버전\n${expected.transaction.ruleVersion.value}") + rentalLines) {
            assertTrue("원정산 ${expected.settlementId}: 누락 $line / $labels", line in labels)
        }
        if (rentalLines.isEmpty()) assertFalse(labels.any { it.startsWith("가상 영업 사고:") })
        else assertTrue("대여 매출의 원정산 금액", "확정 매출: ${money(amount.amount)}" in labels)
        stage("STORED_DETAIL kind=${expected.transaction.kind} id=${expected.settlementId.value} money=$signed")
    }

    private fun awaitRestricted(a: MainActivity) = await("D 제한 안내와 상세·선택 메뉴 제거", 60) {
        menu(a) == null && result(a) == null && tree(a.window.decorView).filterIsInstance<TextView>().any {
            it.isShown && it.text.toString() == "주차 후 조작이 허용되면 이용할 수 있습니다."
        }
    }

    fun testStoredOriginalsInMainAndRestoreRentalDetailAfterActualDrivePark() {
        isolate()
        val originals = read { game ->
            val saved = rental(game, mixedId)
            assertTrue(saved.receipt.accident)
            val basis = (ok(RentalResultBasisReader(game.store).read(mixedId)) as Observation.Available).value
            assertBasis(basis, saved.receipt.settlement, game.identity)
            val offer = ok(game.store.trialDefinitions.load()).expansions.offers.single { it.fromLevel == 1 }
            val facility = requireNotNull(ok(ShopOriginalResultReader(game.store).expansion(game.identity.profileId, expansionId, offer)))
            val expected = listOf(drive(game), delivery(game), facility, saved.receipt.settlement)
            val history = ok(SettlementHistoryReader(game.store).read())
            expected.forEach { original ->
                val entry = history.entries.single { it.settlement.settlementId == original.settlementId }
                assertEquals(original, entry.settlement)
                assertFalse(entry.viewed)
            }
            assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
            expected
        }
        val before = read(::rows)
        val old = activity
        await("실제 P와 현재 부착 메뉴로 Results 진입") {
            if (result(old)?.findViewWithTag<Button>("results:scope:ALL")?.isEnabled == true) return@await true
            val spinner = menu(old)?.takeIf { it.isShown && it.isEnabled && it.isLaidOut && !it.isLayoutRequested }
                ?: return@await false
            val index = (0 until spinner.count).single { spinner.getItemAtPosition(it).toString() == "미확인 정산" }
            if (spinner.selectedItemPosition != index) spinner.setSelection(index)
            false
        }
        click(old, "results:scope:ALL")
        val labels = listOf("주행", "탁송", "시설", "렌터카 영업")
        val rentalLines = listOf("정상 적용 시간: 1시간 59분", "감액 적용 시간: 3시간",
            "상한 제외 시간: 1시간", "미관측 제외 시간: 1분", "구간 1 · 미관측 · 수익 제외",
            "적용 기준: 처음 30분 정상, 이후 30% · 인정 상한 8시간", "가상 영업 사고: 발생",
            "매출에 포함된 시설 보너스: 0 게임머니")
        originals.forEachIndexed { index, settlement ->
            click(old, "results:open:${settlement.settlementId.value}")
            checkDetail(old, settlement, labels[index], if (index == originals.lastIndex) rentalLines else emptyList())
            if (index != originals.lastIndex) click(old, "results:close")
        }
        val selected = originals.last()
        stage("REQUEST_DRIVE_STATIONARY rental=${selected.settlementId.value}")
        awaitRestricted(old)
        stage("RESTRICTED_BEFORE_RECREATE")
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val restored = try {
            instrumentation.runOnMainSync { old.recreate() }
            requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 20_000) as? MainActivity)
        } finally { instrumentation.removeMonitor(monitor) }
        assertNotSame(old, restored)
        awaitRestricted(restored)
        stage("REQUEST_PARK_AFTER_RECREATE")
        // 상세 재선택 없이 새 Activity에서 P 연속 허용이 같은 상태를 복원해야 한다.
        checkDetail(restored, selected, labels.last(), rentalLines)
        assertEquals(before, read(::rows))
        stage("RESTORED_SAME_RENTAL_NO_FINANCIAL_WRITE")
    }
}
