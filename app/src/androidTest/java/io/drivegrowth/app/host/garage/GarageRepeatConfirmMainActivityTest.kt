package io.drivegrowth.app.host.garage

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.HostCheckpointPhase
import io.drivegrowth.app.data.HostCommandCheckpoint
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.simulation.SimulationTimeline
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.app.ui.garage.GarageView
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.progression.VehicleAction
import java.util.concurrent.FutureTask

/** 실제 Debug Main의 강화 확인 버튼에 중복 클릭을 전달하는 격리 계측. */
@Suppress("DEPRECATION")
class GarageRepeatConfirmMainActivityTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {

    private val context get() = instrumentation.targetContext
    private val database get() = context.getDatabasePath("unified-simulation.db")
    private val games by lazy {
        GameSessionFactory(context, mapOf(InputSource.SIMULATION to database.name), InputSource.SIMULATION)
    }

    fun testRepeatedLevelUpConfirmProducesOneSettlement() {
        seedFirstVehicle()
        val before = snapshot()
        val original = before.vehicles.single()
        assertEquals(1, original.level)
        assertNull(before.checkpoint)

        val root = activity.window.decorView
        await("보유 차량 화면 부착") {
            val garage = shownGarage(root)
            if (garage != null) return@await garage
            val menu = root.findViewWithTag<Spinner>("host:destination")
            if (menu != null && menu.isShown && menu.isEnabled && menu.isLaidOut && !menu.isLayoutRequested) {
                val position = (0 until menu.count).singleOrNull { menu.getItemAtPosition(it).toString() == "보유 차량" }
                if (position != null && menu.selectedItemPosition != position) menu.setSelection(position)
            }
            null
        }
        tap(root) { it.tag == original.ownedVehicleId }
        await("대상 개체 상세") { shownGarage(root)?.takeIf { it.selectedVehicleId == original.ownedVehicleId } }
        tap(root) { it.tag == VehicleAction.LEVEL_UP }
        await("활성 강화 확정 버튼") { button(root) { it.text.toString() == "확정" } }
        assertEquals("검토까지 원장 무변경", before, snapshot())

        onUi {
            val confirm = checkNotNull(button(root) { it.text.toString() == "확정" })
            // 저장 결과가 두 클릭 사이에 렌더링되지 않도록 한 UI 작업에서 연속 호출한다.
            assertTrue("첫 클릭이 확인 리스너에 전달됨", confirm.performClick())
            assertFalse("첫 클릭 후 기존 확인 버튼이 분리됨", confirm.isAttachedToWindow)
            assertTrue("분리된 동일 버튼에 두 번째 클릭 전달", confirm.performClick())
        }

        await("확정 결과 화면") { button(root) { it.text.toString() == "결과 확인" } }
        val after = snapshot()
        val expectedVehicle = original.copy(level = 2, revision = Revision(original.revision.value + 1))
        assertEquals(listOf(expectedVehicle), after.vehicles)
        val receipt = after.settlements.drop(before.settlements.size).single()
        assertEquals(before.settlements + receipt, after.settlements)
        val debit = checkNotNull(receipt.transaction.money)
        assertEquals(MoneyDirection.DEBIT, debit.direction)
        assertTrue("유료 레벨 강화 비용", debit.amount.units > 0)
        assertEquals(GameMoney(before.balance.units - debit.amount.units), after.balance)
        assertEquals(after.balance, receipt.balance)
        assertEquals(expectedVehicle, receipt.changedVehicle)
        val checkpoint = checkNotNull(after.checkpoint)
        assertEquals(HostCheckpointPhase.STARTED, checkpoint.phase)
        val command = (checkpoint.command as HostCheckpointCommand.Garage).value
        assertEquals(receipt.transaction.requestId, command.requestId)
        assertEquals(debit, command.money)

        tap(root) { it.text.toString() == "결과 확인" }
        await("결과 확인 후 같은 차량 상세") {
            shownGarage(root)?.takeIf { it.selectedVehicleId == original.ownedVehicleId }
        }
        assertEquals("확인 뒤 슬롯만 해제", after.copy(checkpoint = null), snapshot())
    }

    private fun seedFirstVehicle() {
        assertEquals("반복 확인 전용 계측 패키지", "io.drivegrowth.verify.garage52repeat", context.packageName)
        assertTrue((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        assertFalse("새 시연 DB 필요", database.exists())
        val preferences = context.getSharedPreferences("unified-simulation-v1", Context.MODE_PRIVATE)
        assertFalse(preferences.contains("stage"))
        assertFalse(preferences.contains("elapsed"))
        val timeline = SimulationTimeline()
        assertTrue(preferences.edit().putInt("stage", timeline.stage).putLong("elapsed", timeline.elapsedMs).commit())
        val onboarding = StoredOnboardingHostSource(games)
        ok(onboarding.choose(ok(onboarding.read()).identity, VehicleId("hyundai_avante")))
    }

    private data class Snapshot(
        val balance: GameMoney,
        val vehicles: List<OwnedVehicle>,
        val settlements: List<Settlement>,
        val checkpoint: HostCommandCheckpoint?,
    )

    private fun snapshot() = ok(games.open()).use { game ->
        val store = game.store
        Snapshot(ok(store.balance()), ok(store.listVehicleHistory()), ok(store.listUnviewedSettlements()),
            ok(store.hostCommands.load(game.identity.profileId)))
    }

    private fun <T> ok(result: ContractResult<T>): T = when (result) {
        is ContractResult.Success -> result.value
        is ContractResult.Failure -> error(result.toString())
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun shownGarage(root: View): GarageView? =
        views(root).filterIsInstance<GarageView>().firstOrNull { it.isShown }

    private fun button(root: View, matches: (Button) -> Boolean): Button? =
        views(root).filterIsInstance<Button>().firstOrNull { it.isShown && it.isEnabled && matches(it) }

    private data class TapTarget(val button: Button, val x: Float, val y: Float)

    private fun tap(root: View, matches: (Button) -> Boolean) {
        var previous: TapTarget? = null
        val target = await("좌표 입력 대상의 배치·초점·가시성 안정") {
            val button = button(root, matches)
            val visible = Rect()
            val point = if (button != null && button.isAttachedToWindow && button.hasWindowFocus() &&
                button.isLaidOut && !button.isLayoutRequested) {
                button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                if (button.getGlobalVisibleRect(visible) && visible.width() == button.width &&
                    visible.height() == button.height) {
                    val location = IntArray(2)
                    button.getLocationOnScreen(location)
                    TapTarget(button, location[0] + button.width / 2f, location[1] + button.height / 2f)
                } else null
            } else null
            val stable = point?.takeIf { it == previous }
            previous = point
            stable
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.x, target.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun <T : Any> await(label: String, action: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 20_000
        do {
            onUi(action)?.let { return it }
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        error(label)
    }

    private fun <T> onUi(action: () -> T): T {
        val task = FutureTask<T> { action() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
