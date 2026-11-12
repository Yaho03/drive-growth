package io.drivegrowth.app.host

import android.graphics.Rect
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import io.drivegrowth.app.mainNavigation
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.R
import io.drivegrowth.app.data.SettlementHistoryReader
import io.drivegrowth.app.data.SettlementHistorySnapshot
import io.drivegrowth.app.ui.onboarding.OnboardingView
import io.drivegrowth.app.ui.results.ResultsReadState
import io.drivegrowth.app.ui.results.ResultsScope
import io.drivegrowth.app.ui.results.ResultsView
import io.drivegrowth.app.ui.shop.FacilityControlsView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant

/** 실제 Main/UI의 첫 지급·전체 이력 연결. 별도 fresh package 전용이며 DB seed/삭제·gate 주입을 하지 않는다. */
@Suppress("DEPRECATION")
class ActualMainResultsHistoryIndependentTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private fun tree(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()

    private fun menu(a: MainActivity) = a.window.decorView.mainNavigation()
    private fun results(a: MainActivity) = tree(a.window.decorView).filterIsInstance<ResultsView>().singleOrNull()
    private fun stage(value: String) { Log.i("ActualMainResultsF", value) }

    private fun await(a: MainActivity, label: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        var success = false
        var gate = false
        while (!success && SystemClock.uptimeMillis() < until) {
            instrumentation.runOnMainSync {
                gate = menu(a)?.let { it.isShown && it.isEnabled } == true
                success = gate && check()
            }
            if (!success) SystemClock.sleep(50)
        }
        if (!success && !gate) throw IllegalStateException("ENVIRONMENT_GATE_TIMEOUT: $label; 실제 P/SDK 허용 메뉴 미관측, 우회 금지")
        assertTrue("ASSERTION_STATE: $label", success)
    }

    private fun click(a: MainActivity, tag: Any) {
        await(a, "활성 UI 버튼 $tag") {
            a.window.decorView.findViewWithTag<Button>(tag)?.let { it.isShown && it.isEnabled } == true
        }
        instrumentation.runOnMainSync {
            val button = a.window.decorView.findViewWithTag<Button>(tag)!!
            button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
            assertTrue(button.performClick())
        }
    }

    private fun openResults(a: MainActivity) {
        await(a, "실제 화면 선택 메뉴") { menu(a) != null }
        instrumentation.runOnMainSync {
            a.window.decorView.openMainPage(HostPage.RESULTS)
        }
        await(a, "실제 Results 전체 이력 조회 완료") {
            results(a)?.isShown == true && a.window.decorView.findViewWithTag<View>("results:list")?.isShown == true &&
                a.window.decorView.findViewWithTag<Button>("results:scope:ALL")?.isEnabled == true
        }
    }

    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }

    private fun recreate(a: MainActivity): MainActivity {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync { a.recreate() }
            val next = instrumentation.waitForMonitorWithTimeout(monitor, 15000) as? MainActivity
            assertNotNull("실제 새 MainActivity 인스턴스", next)
            assertNotSame(a, next)
            return next!!
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    private fun <T> read(action: (GameSession) -> T) = ok(GameSessionFactory(instrumentation.targetContext).open()).use(action)
    private fun history(): SettlementHistorySnapshot = ok(SettlementHistoryReader.read(GameSessionFactory(instrumentation.targetContext)))
    private fun viewedEvents(): Int = read { game ->
        ok(game.store.read { db -> db.rawQuery("SELECT count(*) FROM quest_events WHERE action='RESULT_VIEWED'", null).use {
            it.moveToFirst(); it.getInt(0)
        } })
    }

    /** viewed/Quest의 합법적 변경만 제외한다. 실제 금액·차량·원래 정산/초기화 원문은 전후 동일해야 한다. */
    private fun financialRows(): Map<String, List<List<String?>>> = read { game ->
        ok(game.store.read { db ->
            val queries = listOf("account", "vehicles", "shop", "initialization_profile", "initialization_requests")
                .associateWith { "SELECT * FROM $it ORDER BY 1" } +
                ("settlements" to "SELECT request_id,settlement_id,command,result FROM settlements ORDER BY rowid")
            queries.mapValues { (_, query) -> db.rawQuery(query, null).use { cursor -> buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column -> cursor.getString(column) })
            } } }
        })
    }

    fun testActualHomeFullHistoryFilterSelectionViewedBackAndRecreation() {
        val context = instrumentation.targetContext
        assertEquals("격리 package 전용", "io.drivegrowth.verify.mainresultsf", context.packageName)
        for (name in listOf("game-ledger-vehicle.db", "game-ledger-simulation.db"))
            assertFalse("PRECONDITION_FRESH_PACKAGE_REQUIRED: 기존 DB를 지우지 말고 새 격리 package를 사용: $name", context.getDatabasePath(name).exists())
        stage("PRECONDITION_FRESH_PACKAGE_OK")
        var current: MainActivity? = null
        try {
            current = activity
            var a = current!!
            await(a, "실제 NEW 안내 및 SDK gate") { tree(a.window.decorView).any { it is OnboardingView } }
            click(a, R.string.onboarding_start)
            click(a, VehicleId("hyundai_casper"))
            click(a, R.string.onboarding_review)
            click(a, R.string.onboarding_confirm)
            await(a, "공개 첫 지급 확정") {
                a.window.decorView.findViewWithTag<Button>(R.string.onboarding_to_shop)?.isEnabled == true
            }
            val original = read { game ->
                assertEquals(InputSource.VEHICLE, game.identity.source)
                assertEquals(FirstGrant.INITIAL_MONEY, ok(game.store.balance()))
                assertEquals(VehicleId("hyundai_casper"), ok(game.store.listOwnedVehicles()).single().vehicleId)
                (ok(game.store.readInitialization(game.identity.profileId)).status as ProfileInitializationStatus.Existing).original!!
            }
            val id = original.grant.settlementId
            val openTag = "results:open:${id.value}"
            val markTag = "results:mark:${id.value}"
            val originalRows = financialRows()
            val firstHistory = history()
            assertEquals(listOf(original.grant), firstHistory.entries.map { it.settlement })
            assertFalse(firstHistory.entries.single().viewed)
            assertEquals(0, viewedEvents())
            click(a, R.string.onboarding_to_shop)
            await(a, "실제 HOME 시설 화면") { tree(a.window.decorView).any { it is FacilityControlsView && it.isShown } }
            stage("PUBLIC_FIRST_GRANT_AND_HOME_OK")

            openResults(a)
            await(a, "기본 미확인 목록에 원래 지급 정산") {
                results(a)?.uiState?.scope == ResultsScope.UNVIEWED && results(a)?.selectedSettlementId == null &&
                    a.window.decorView.findViewWithTag<Button>(openTag)?.isShown == true
            }
            click(a, "results:scope:ALL")
            click(a, openTag)
            await(a, "전체 필터·원래 정산 선택") {
                results(a)?.uiState?.scope == ResultsScope.ALL && results(a)?.selectedSettlementId == id &&
                    a.window.decorView.findViewWithTag<Button>(markTag)?.isEnabled == true
            }
            a = recreate(a); current = a
            await(a, "열람 전 재생성 뒤 전체 필터·선택·조작 복원") {
                results(a)?.isShown == true && results(a)?.uiState?.scope == ResultsScope.ALL && results(a)?.selectedSettlementId == id &&
                    a.window.decorView.findViewWithTag<Button>(markTag)?.isEnabled == true
            }
            assertEquals(firstHistory, history())
            assertEquals(originalRows, financialRows())
            assertEquals(0, viewedEvents())
            stage("UNVIEWED_SELECTION_AND_ALL_SCOPE_RECREATION_OK")

            click(a, markTag)
            await(a, "실제 markViewed 완료·중복 버튼 비활성") {
                results(a)?.uiState?.readStates?.get(id) == ResultsReadState.Done &&
                    a.window.decorView.findViewWithTag<Button>(markTag)?.let { it.isShown && !it.isEnabled } == true
            }
            assertEquals(firstHistory.copy(entries = firstHistory.entries.map { it.copy(viewed = true) }), history())
            assertEquals(1, viewedEvents())
            stage("PUBLIC_MARK_VIEWED_AND_ONE_QUEST_EVENT_OK")

            back()
            await(a, "Back 한 번은 상세만 닫음") {
                results(a)?.isShown == true && results(a)?.selectedSettlementId == null &&
                    a.window.decorView.findViewWithTag<Button>("results:scope:UNVIEWED")?.isShown == true
            }
            click(a, "results:scope:UNVIEWED")
            await(a, "열람한 원래 정산이 미확인 필터에서만 제외됨") {
                results(a)?.uiState?.scope == ResultsScope.UNVIEWED && a.window.decorView.findViewWithTag<View>(openTag) == null
            }
            click(a, "results:scope:ALL")
            click(a, openTag)
            await(a, "전체 기록에 열람 완료 정산 유지") {
                results(a)?.selectedSettlementId == id && a.window.decorView.findViewWithTag<Button>(markTag)?.isEnabled == false
            }
            a = recreate(a); current = a
            await(a, "열람 뒤 재생성에서도 전체·선택·완료 상태 복원") {
                results(a)?.isShown == true && results(a)?.uiState?.scope == ResultsScope.ALL && results(a)?.selectedSettlementId == id &&
                    a.window.decorView.findViewWithTag<Button>(markTag)?.let { it.isShown && !it.isEnabled } == true
            }
            stage("VIEWED_FILTER_AND_SECOND_RECREATION_OK")

            back()
            await(a, "상세 Back 뒤 목록 유지") { results(a)?.isShown == true && results(a)?.selectedSettlementId == null }
            back()
            await(a, "목록 Back은 HOME 복귀") { tree(a.window.decorView).any { it is FacilityControlsView && it.isShown } }
            openResults(a)
            await(a, "HOME 재진입 뒤 전체 필터·원래 행 유지, 닫은 선택은 null") {
                results(a)?.uiState?.scope == ResultsScope.ALL && results(a)?.selectedSettlementId == null &&
                    a.window.decorView.findViewWithTag<Button>(openTag)?.isShown == true
            }
            click(a, openTag)
            await(a, "재진입한 열람 완료 정산은 다시 쓰기 불가") {
                results(a)?.selectedSettlementId == id && a.window.decorView.findViewWithTag<Button>(markTag)?.let { it.isShown && !it.isEnabled } == true
            }
            assertEquals(originalRows, financialRows())
            assertEquals(firstHistory.copy(entries = firstHistory.entries.map { it.copy(viewed = true) }), history())
            assertEquals(1, viewedEvents())
            assertFalse("실제 VEHICLE 시험이 SIMULATION 원장을 만들면 안 됨", context.getDatabasePath("game-ledger-simulation.db").exists())
            stage("FINAL_HOME_REENTRY_ORIGINAL_FINANCIAL_ROWS_AND_ONCE_ONLY_VIEWED_OK")
        } finally {
            current?.let { a -> instrumentation.runOnMainSync { a.finish() } }
        }
    }
}
