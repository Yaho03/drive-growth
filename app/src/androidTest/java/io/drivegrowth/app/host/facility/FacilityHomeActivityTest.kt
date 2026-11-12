package io.drivegrowth.app.host.facility

import android.graphics.Rect
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.R
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.ui.onboarding.OnboardingView
import io.drivegrowth.app.ui.shop.FacilityControlsView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant

/** cold 격리 앱의 실제 SDK gate/UI. DB는 공개 지급 뒤 읽기만 하며 신호/신규근거/재화를 주입하지 않는다. */
@Suppress("DEPRECATION")
class FacilityHomeActivityTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private fun tree(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()
    private fun menu(a: MainActivity) = a.window.decorView.findViewWithTag<Spinner>("host:destination")
    private fun await(a: MainActivity, label: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        var success = false
        var gate = false
        while (!success && SystemClock.uptimeMillis() < until) {
            instrumentation.runOnMainSync {
                gate = menu(a)?.let { it.isShown && it.isEnabled } == true
                success = check()
            }
            if (!success) SystemClock.sleep(50)
        }
        if (!success && !gate) throw IllegalStateException("ENVIRONMENT_GATE_TIMEOUT: $label; 실제 SDK 허용 메뉴 미관측")
        assertTrue("ASSERTION_STATE: $label", success)
    }
    private fun click(a: MainActivity, tag: Any) {
        await(a, "활성 UI 버튼 $tag") {
            menu(a)?.isEnabled == true && a.window.decorView.findViewWithTag<Button>(tag)?.let { it.isShown && it.isEnabled } == true
        }
        instrumentation.runOnMainSync {
            val button = a.window.decorView.findViewWithTag<Button>(tag)!!
            button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
            assertTrue(button.performClick())
        }
    }
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    private fun <T> read(action: (GameSession) -> T) = ok(GameSessionFactory(instrumentation.targetContext).open()).use(action)

    fun testActualHomeRestingRequestSurvivesRecreationAndChangesNoMoneyOrVehicle() {
        val context = instrumentation.targetContext
        assertTrue("제품 패키지 실행 금지", context.packageName != "io.drivegrowth.app")
        for (name in listOf("game-ledger-vehicle.db", "game-ledger-simulation.db"))
            assertFalse("새 격리 package가 필요하며 기존 DB를 지우면 안 된다: $name", context.getDatabasePath(name).exists())
        val first = activity
        await(first, "실제 NEW 안내") { tree(first.window.decorView).any { it is OnboardingView } }
        click(first, R.string.onboarding_start)
        click(first, VehicleId("hyundai_casper"))
        click(first, R.string.onboarding_review)
        click(first, R.string.onboarding_confirm)
        await(first, "공개 첫 지급 확정") {
            first.window.decorView.findViewWithTag<Button>(R.string.onboarding_to_shop)?.isEnabled == true
        }
        val initial = read { game ->
            assertEquals(InputSource.VEHICLE, game.identity.source)
            assertEquals(FirstGrant.INITIAL_MONEY, ok(game.store.balance()))
            game.identity to ok(game.store.listOwnedVehicles()).single()
        }
        click(first, R.string.onboarding_to_shop)
        await(first, "실제 HOME의 중앙 주유소 보너스") {
            tree(first.window.decorView).filterIsInstance<TextView>().any {
                it.text.toString() == context.getString(R.string.shop_effect, "대여당 10 게임머니 추가 수익")
            }
        }
        val tag = "facility:rest:${initial.second.ownedVehicleId.value}"
        await(first, "실제 HOME 시설·쉬기 화면과 현재 OFF") {
            tree(first.window.decorView).any { it is FacilityControlsView } &&
                first.window.decorView.findViewWithTag<Button>(tag)?.let { it.isEnabled && it.text.toString() == "영업 쉬기 켜기" } == true
        }
        click(first, tag)
        await(first, "원래 비금융 ON 결과") { first.window.decorView.findViewWithTag<View>("facility:receipt")?.isShown == true }
        val original = read { game ->
            val saved = ok(game.store.hostCommands.load(game.identity.profileId))!!
            assertTrue(saved.command is HostCheckpointCommand.VehicleResting)
            assertTrue(ok(game.store.rental.restingVehicles()).contains(initial.second.ownedVehicleId))
            assertEquals(FirstGrant.INITIAL_MONEY, ok(game.store.balance()))
            assertEquals(listOf(initial.second), ok(game.store.listOwnedVehicles()))
            saved
        }
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var recreated: MainActivity? = null
        try {
            instrumentation.runOnMainSync { first.recreate() }
            recreated = instrumentation.waitForMonitorWithTimeout(monitor, 15000) as? MainActivity
            assertNotNull(recreated)
            val next = recreated!!
            assertNotSame(first, next)
            await(next, "같은 원래 ON 결과 복원") {
                next.window.decorView.findViewWithTag<View>("facility:receipt")?.isShown == true
            }
            read { game -> assertEquals(initial.first, game.identity); assertEquals(original, ok(game.store.hostCommands.load(game.identity.profileId))) }
            click(next, "facility:ack")
            await(next, "현재 ON 재조회") { next.window.decorView.findViewWithTag<Button>(tag)?.let { it.isEnabled && it.text.toString() == "영업 쉬기 끄기" } == true }
            click(next, tag)
            await(next, "원래 OFF 결과") { next.window.decorView.findViewWithTag<View>("facility:receipt")?.isShown == true }
            click(next, "facility:ack")
            await(next, "현재 OFF 재조회") { next.window.decorView.findViewWithTag<Button>(tag)?.let { it.isEnabled && it.text.toString() == "영업 쉬기 켜기" } == true }
            read { game ->
                assertEquals(initial.first, game.identity)
                assertTrue(ok(game.store.rental.restingVehicles()).isEmpty())
                assertEquals(FirstGrant.INITIAL_MONEY, ok(game.store.balance()))
                assertEquals(listOf(initial.second), ok(game.store.listOwnedVehicles()))
                assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
                val counts = ok(game.store.read { db ->
                    listOf("facility_command_results", "settlements").map { table ->
                        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
                    }
                })
                assertEquals(listOf(2L, 1L), counts)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            recreated?.let { next -> instrumentation.runOnMainSync { next.finish() } }
        }
    }
}
