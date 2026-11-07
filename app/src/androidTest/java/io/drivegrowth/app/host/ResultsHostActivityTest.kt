package io.drivegrowth.app.host

import android.app.Instrumentation
import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.ui.onboarding.OnboardingView
import io.drivegrowth.app.ui.results.ResultsReadState
import io.drivegrowth.app.ui.results.ResultsView
import io.drivegrowth.app.vehicle.ForegroundInteraction
import io.drivegrowth.core.contract.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 격리 DB의 저장 결과와 실제 MainActivity/SDK P gate를 연결한다. gate reset 한 단계만 제어 입력이다. */
@Suppress("DEPRECATION")
class ResultsHostActivityTest : InstrumentationTestCase() {
    private fun <T> ui(block: () -> T): T {
        var value: T? = null
        var error: Throwable? = null
        instrumentation.runOnMainSync { try { value = block() } catch (caught: Throwable) { error = caught } }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun texts(activity: MainActivity) = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun button(activity: MainActivity, label: String) = descendants(activity.window.decorView)
        .filterIsInstance<Button>().first { it.text.toString() == label }
    private fun results(activity: MainActivity) = descendants(activity.window.decorView).filterIsInstance<ResultsView>().single()
    private fun awaitUi(activity: MainActivity, description: String, condition: () -> Boolean) {
        val ready = CountDownLatch(1)
        val root = activity.window.decorView
        val listener = ViewTreeObserver.OnGlobalLayoutListener { if (condition()) ready.countDown() }
        ui { root.viewTreeObserver.addOnGlobalLayoutListener(listener); if (condition()) ready.countDown() }
        try { assertTrue(description, ready.await(15, TimeUnit.SECONDS)) }
        finally { ui { root.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener) } }
    }

    fun testRealResultsRecreationGateRejectionAcknowledgementAndUnavailableOnboarding() {
        val context = instrumentation.targetContext
        assertEquals("io.drivegrowth.app.integration51", context.packageName)
        val source = StoredHostSource(context)
        val content = (source.read() as ContractResult.Success).value.content
        val suffix = UUID.randomUUID().toString()
        val command = GameChange(RequestId("activity-$suffix"), TransactionKind.GARAGE, InputSource.SIMULATION,
            content.ruleVersion, MoneyChange(MoneyDirection.CREDIT, GameMoney(75)), VehicleWrite.Add(OwnedVehicle(
                OwnedVehicleId("activity-$suffix"), content.catalog.vehicles.first().vehicleId, 1, Revision(0),
                VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)))
        val stored = SqliteGameChangeStore(context, InputSource.SIMULATION).use {
            (it.commitGameChange(command) as ContractResult.Success).value
        }
        val before = (source.read() as ContractResult.Success).value
        var current = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            awaitUi(current, "실제 P SDK 관측으로 탐색이 열려야 한다") { texts(current).contains("미확인 정산") }
            ui { button(current, "미확인 정산").performClick() }
            val openTag = "results:open:${stored.settlementId.value}"
            val markTag = "results:mark:${stored.settlementId.value}"
            awaitUi(current, "저장된 정산이 실제 View에 전달돼야 한다") { current.window.decorView.findViewWithTag<View>(openTag) != null }
            ui { current.window.decorView.findViewWithTag<Button>(openTag).performClick() }
            val retained = ui { current.onRetainNonConfigurationInstance() }
            val monitor = Instrumentation.ActivityMonitor(MainActivity::class.java.name, null, false)
            instrumentation.addMonitor(monitor)
            ui { current.recreate() }
            val recreated = monitor.waitForActivityWithTimeout(10000) as? MainActivity
            instrumentation.removeMonitor(monitor)
            assertNotNull("실제 Activity 재생성이 완료돼야 한다", recreated)
            current = recreated!!
            ui {
                assertSame(retained, current.onRetainNonConfigurationInstance())
                assertTrue("재생성은 이전 차량 허용을 재사용하지 않는다", texts(current).contains("주차 후 조작이 허용되면 이용할 수 있습니다."))
            }
            awaitUi(current, "새 P 판정 뒤 정산 선택 ID가 복원돼야 한다") { current.window.decorView.findViewWithTag<View>(markTag) != null }
            val view = ui { results(current) }
            ui {
                // watchdog 전 gate 만료를 결정적으로 재현한다. 실제 SDK D 입력으로 기록하지 않는다.
                val gate = MainActivity::class.java.getDeclaredField("interaction").apply { isAccessible = true }.get(current) as ForegroundInteraction
                gate.reset()
                current.window.decorView.findViewWithTag<Button>(markTag).performClick()
                assertFalse("호스트가 거절한 미제출 Pending이 남으면 안 된다", view.uiState.readStates[stored.settlementId] == ResultsReadState.Pending)
            }
            assertTrue((source.read() as ContractResult.Success).value.unviewedSettlements.any { it.settlementId == stored.settlementId })
            awaitUi(current, "재허용 뒤 같은 ID를 다시 열람 처리할 수 있어야 한다") { current.window.decorView.findViewWithTag<Button>(markTag)?.isEnabled == true }
            ui { current.window.decorView.findViewWithTag<Button>(markTag).performClick() }
            awaitUi(current, "실제 저장 성공 응답 뒤에만 Done이어야 한다") { view.uiState.readStates[stored.settlementId] == ResultsReadState.Done }
            val after = (source.read() as ContractResult.Success).value
            assertFalse(after.unviewedSettlements.any { it.settlementId == stored.settlementId })
            assertEquals(before.balance, after.balance)
            assertEquals(before.ownedVehicles, after.ownedVehicles)
            SqliteGameChangeStore(context, InputSource.SIMULATION).use { assertEquals(ContractResult.Success(stored), it.commitGameChange(command)) }
            ui { current.onBackPressed(); assertNull(results(current).selectedSettlementId) }
            ui { current.onBackPressed() }
            awaitUi(current, "상세→목록→가게 Back") { texts(current).contains("드라이브 키우기") }
            ui { button(current, "첫 차량 안내").performClick() }
            ui {
                assertEquals(1, descendants(current.window.decorView).filterIsInstance<OnboardingView>().size)
                assertTrue(texts(current).any { it.contains("초기 지급 이력 조회가 연결되지 않아") })
                assertTrue("미지원 초기화는 선택/지급 버튼을 만들지 않는다", descendants(current.window.decorView)
                    .filterIsInstance<OnboardingView>().single().let { descendants(it).none { child -> child is Button } })
                current.onBackPressed()
            }
            assertEquals(after.balance, (source.read() as ContractResult.Success).value.balance)
        } finally { ui { current.finish() }; instrumentation.waitForIdleSync() }
    }
}
