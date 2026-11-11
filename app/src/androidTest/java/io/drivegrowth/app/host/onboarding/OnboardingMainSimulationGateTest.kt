package io.drivegrowth.app.host.onboarding

import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.widget.Button
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.R
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostSession
import io.drivegrowth.app.simulation.SimulationTimeline
import io.drivegrowth.app.simulation.UnifiedSimulation
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.ProfileInitializationStatus
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.garage.FirstGrant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** 실제 Main·SQLite를 사용한다. 제한 전환은 SIMULATION의 gate 입력이며 SDK D/P 관측은 아니다. */
@Suppress("DEPRECATION")
class OnboardingMainSimulationGateTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private val parked = SimulationTimeline()
    private val restricted = checkNotNull(parked.begin())

    fun testSimulationRestrictionBeforeConfirmAndAfterCommitRestoresOneOriginalGrant() {
        assertFalse("제품 패키지에서는 실행하지 않는다", context.packageName == "io.drivegrowth.app")
        for (name in listOf("unified-simulation.db", "game-ledger-vehicle.db", "game-ledger-simulation.db")) {
            assertFalse("기존 DB는 보존한다. 새 격리 패키지가 필요하다: $name", context.getDatabasePath(name).exists())
        }
        assertFalse(context.getSharedPreferences("unified-simulation-v1", 0).contains("stage"))

        val main = activity
        val choice = VehicleId("hyundai_casper")
        click(main, R.string.onboarding_start)
        click(main, choice)
        click(main, R.string.onboarding_review)
        awaitButton(main, R.string.onboarding_confirm)
        val session = MainActivity::class.java.getDeclaredField("session")
            .apply { isAccessible = true }.get(main) as HostSession
        val simulator = session.simulation as UnifiedSimulation
        val progress = UnifiedSimulation::class.java.getDeclaredField("progress").apply { isAccessible = true }
        val worker = HostSession::class.java.getDeclaredField("worker")
            .apply { isAccessible = true }.get(session) as Executor

        fun gate(timeline: SimulationTimeline) {
            // 모의 관측을 원장에 보내지 않고 기존 시연 gate와 Main의 변경 콜백만 구동한다.
            progress.set(simulator, timeline)
            checkNotNull(simulator.changed).invoke()
            assertEquals(timeline.controlsAllowed, simulator.controlsAllowed)
        }

        instrumentation.runOnMainSync {
            val staleConfirm = checkNotNull(button(main, R.string.onboarding_confirm))
            val stalePrevious = checkNotNull(button(main, R.string.onboarding_previous))
            val before = session.onboarding.uiState
            assertEquals(OnboardingStep.CONFIRMING, before.step)
            assertEquals(choice, before.selected)
            assertEquals(InputSource.SIMULATION, before.identity?.source)
            assertNull(before.pending)
            gate(restricted)
            assertNull(button(main, R.string.onboarding_confirm))
            stalePrevious.performClick()
            staleConfirm.performClick()
            assertEquals(before, session.onboarding.uiState)
        }
        val reader = GameSessionFactory(
            context, mapOf(InputSource.SIMULATION to "unified-simulation.db"), InputSource.SIMULATION,
        )
        ok(reader.open()).use { game ->
            val state = ok(game.store.readInitialization(game.identity.profileId))
            assertTrue(state.status is ProfileInitializationStatus.New)
            assertEquals(GameMoney(0), state.balance)
            assertEquals(0, state.ownedVehicleCount)
            assertEquals(emptyList<String>(), grantRequests(game))
        }

        instrumentation.runOnMainSync { gate(parked) }
        awaitButton(main, R.string.onboarding_confirm)
        instrumentation.runOnMainSync {
            assertEquals(OnboardingStep.CONFIRMING, session.onboarding.uiState.step)
            assertEquals(choice, session.onboarding.uiState.selected)
            val confirm = checkNotNull(button(main, R.string.onboarding_confirm))
            assertTrue(confirm.performClick())
            val pending = session.onboarding.uiState
            assertEquals(choice, pending.pending)
            // 지급 작업 뒤의 worker 표식으로 SQLite 확정을 기다린다. Main에 이미 큐잉된
            // 완료 응답은 이 UI 블록을 벗어난 뒤에만 실행되므로 제한 전환의 순서가 고정된다.
            val grantWorkFinished = CountDownLatch(1)
            worker.execute { grantWorkFinished.countDown() }
            assertTrue("초기 지급 worker 표식", grantWorkFinished.await(15, TimeUnit.SECONDS))
            gate(restricted)
            confirm.performClick()
            assertNull(button(main, R.string.onboarding_to_shop))
            assertEquals(pending, session.onboarding.uiState)
        }
        val original = ok(reader.open()).use { game ->
            val state = ok(game.store.readInitialization(game.identity.profileId))
            val result = checkNotNull((state.status as ProfileInitializationStatus.Existing).original)
            assertEquals(FirstGrant.REQUEST_ID, result.command.grant.requestId)
            assertEquals(result.command.grant.requestId, result.grant.transaction.requestId)
            assertEquals(choice, result.grant.changedVehicle?.vehicleId)
            assertEquals(listOf(FirstGrant.REQUEST_ID.value), grantRequests(game))
            result
        }
        instrumentation.runOnMainSync {
            assertEquals(choice, session.onboarding.uiState.pending)
            gate(parked)
        }
        awaitButton(main, R.string.onboarding_to_shop)
        instrumentation.runOnMainSync {
            assertEquals(OnboardingStep.CONFIRMING, session.onboarding.uiState.step)
            assertEquals(choice, session.onboarding.uiState.selected)
            assertNull(session.onboarding.uiState.pending)
            assertNull(button(main, R.string.onboarding_confirm))
        }
        click(main, R.string.onboarding_to_shop)
        ok(reader.open()).use { game ->
            val state = ok(game.store.readInitialization(game.identity.profileId))
            assertEquals(original, (state.status as ProfileInitializationStatus.Existing).original)
            assertEquals(GameMoney(1000), state.balance)
            assertEquals(1, state.ownedVehicleCount)
            assertEquals(3, original.garageCapacity)
            assertEquals(listOf(FirstGrant.REQUEST_ID.value), grantRequests(game))
        }
    }

    private fun grantRequests(game: GameSession): List<String> = ok(game.store.atomic { db ->
        db.rawQuery("SELECT request_id FROM initialization_requests ORDER BY request_id", null).use { rows ->
            ContractResult.Success(buildList { while (rows.moveToNext()) add(rows.getString(0)) })
        }
    })

    private fun button(main: MainActivity, tag: Any): Button? =
        main.window.decorView.findViewWithTag<Button>(tag)?.takeIf { it.isShown && it.isEnabled }

    private fun awaitButton(main: MainActivity, tag: Any) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var shown = false
        while (!shown && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { shown = button(main, tag) != null }
            if (!shown) SystemClock.sleep(40)
        }
        assertTrue("활성 버튼을 기다립니다: $tag", shown)
    }

    private fun click(main: MainActivity, tag: Any) {
        awaitButton(main, tag)
        instrumentation.runOnMainSync { assertTrue(checkNotNull(button(main, tag)).performClick()) }
    }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
}
