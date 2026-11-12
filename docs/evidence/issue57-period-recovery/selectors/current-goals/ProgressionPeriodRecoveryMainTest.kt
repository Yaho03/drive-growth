package io.drivegrowth.app.host.progression

import android.content.Context
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
import io.drivegrowth.app.R
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.HostCheckpointPhase
import io.drivegrowth.app.data.HostCommandCheckpoint
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.data.questDefinitions
import io.drivegrowth.app.data.requestId
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.ui.progress.ProgressGroup
import io.drivegrowth.app.ui.progress.ProgressView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.quest.*
import io.drivegrowth.core.rental.RentalCategory
import io.drivegrowth.core.rental.RentalRequest
import io.drivegrowth.core.rental.SimulatedCustomer
import java.math.RoundingMode
import java.time.ZoneId
import java.util.concurrent.FutureTask

/** Separate seed and Main selectors. The seed clock is isolated and Main uses the OS clock. */
@Suppress("DEPRECATION")
class ProgressionPeriodRecoveryMainTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.VEHICLE
    private val zone get() = ZoneId.systemDefault()
    private val trial get() = ok(AssetTrialDefinitionProvider(context.assets).load())
    private val definitions get() = ok(trial.questDefinitions(zone))
    private val evidence get() = context.getSharedPreferences("quest-period-recovery-test", Context.MODE_PRIVATE)

    fun testSeedExpiredDailyGrowthSaved() = seed("daily-vehicle-level-up", QuestCadence.DAILY, execute = false)
    fun testSeedPreviousWeeklyRentalStarted() = seed("weekly-rental-completed", QuestCadence.WEEKLY, execute = true)

    private data class CurrentGoalExpectation(
        val cadence: QuestCadence,
        val count: Int,
        val goal: Int,
        val claimEnabled: Boolean,
    )

    fun testActualMainDisplaysThreeRemainingCurrentGoalsWithoutClaiming() {
        checkIsolation()
        for (name in listOf("game-ledger-vehicle.db", "game-ledger-simulation.db")) {
            assertFalse("a new isolated install is required: $name", context.getDatabasePath(name).exists())
        }
        val actualNow = EpochMillis(System.currentTimeMillis())
        val daily = questPeriod(QuestCadence.DAILY, zone, actualNow)
        val weekly = questPeriod(QuestCadence.WEEKLY, zone, actualNow)
        val hireMs = 3 * 3_600_000L
        val returnMs = 15 * 60_000L
        val end = EpochMillis(actualNow.value - returnMs - 1_000L)
        assertTrue("ENVIRONMENT_PERIOD_BOUNDARY: run after the first 16 minutes of the local day",
            end.value >= daily.startsAt.value)
        val start = EpochMillis(end.value - hireMs)
        var clock = minOf(weekly.startsAt.value, start.value) - 1_000L
        val games = GameSessionFactory(context, mapOf(source to "game-ledger-vehicle.db"), source,
            questDefinitions = { ContractResult.Success(definitions) }, clock = { clock })
        val onboarding = StoredOnboardingHostSource(games)
        val identity = ok(onboarding.read()).identity
        val casper = VehicleId("hyundai_casper")
        val initial = ok(onboarding.choose(identity, casper)).grant
        ok(games.open()).use { game ->
            val store = game.store
            val content = ok(game.content)
            val rental = RequestId("current-goals-rental")
            clock = start.value
            ok(store.rental.assign(RentalRequest(rental, casper, 3), content.catalog,
                mapOf(casper to RentalCategory.SMALL), mapOf(casper to trial.rentalRates.getValue(casper)),
                RentalTerms(content.ruleVersion, SimulatedCustomer(30, 48), 99, start, 15,
                    GameMoney(0), RoundingMode.DOWN, false)))
            clock = end.value
            ok(store.rental.account(rental, start, end, null))
            val completed = ok(store.rental.settle(rental))
            clock += returnMs
            ok(store.rental.finishReturn(rental, EpochMillis(clock)))
            val gift = ok(FirstGrant(content).prepareOpeningGift(store, identity.profileId, source))
            ok(store.commitFirstRentalGift(gift))
            ok(store.markViewed(initial.settlementId))
            ok(store.markViewed(completed.settlement.settlementId))
            repeat(3) { index ->
                val vehicle = ok(store.listOwnedVehicles()).single()
                ok(store.commitGameChange(GameChange(RequestId("current-goals-level-$index"),
                    TransactionKind.GARAGE, source, trial.rules, MoneyChange(MoneyDirection.DEBIT, GameMoney(20)),
                    VehicleWrite.Update(vehicle.ownedVehicleId, vehicle.revision, vehicle.level + 1,
                        VehicleLifecycle.OWNED, 0))))
            }
        }
        val expected = mapOf(
            "daily-rental-completed" to CurrentGoalExpectation(QuestCadence.DAILY, 1, 1, true),
            "weekly-result-viewed" to CurrentGoalExpectation(QuestCadence.WEEKLY, 2, 3, false),
            "weekly-vehicle-level-up" to CurrentGoalExpectation(QuestCadence.WEEKLY, 3, 3, true),
        )
        val stored = ok(games.open()).use { game ->
            assertEquals(identity, game.identity)
            assertNull(ok(game.store.hostCommands.load(identity.profileId)))
            ok(game.store.quests.read(identity.profileId, Observation.Available(actualNow)))
                .filter { it.definition.id in expected }.associateBy { it.definition.id }
        }
        assertEquals(expected.keys, stored.keys)
        val before = snapshot()
        val main = activity
        openProgress(main)
        for ((id, expectation) in expected) {
            val (cadence, count, goalCount, claimEnabled) = expectation
            val goal = stored.getValue(id)
            assertEquals(cadence, goal.definition.cadence)
            assertEquals(goalCount, goal.definition.goal)
            val period = if (cadence == QuestCadence.DAILY) daily else weekly
            val group = if (cadence == QuestCadence.DAILY) ProgressGroup.DAILY else ProgressGroup.WEEKLY
            assertEquals(period, goal.period)
            assertEquals("ENVIRONMENT_PERIOD_CHANGED", period,
                questPeriod(cadence, zone, EpochMillis(System.currentTimeMillis())))
            assertEquals(count, goal.count)
            assertEquals(if (claimEnabled) QuestStatus.CLAIMABLE else QuestStatus.IN_PROGRESS, goal.status)
            val key = progressKey(goal.definition, period)
            await("$id current group") { main.window.decorView.findViewWithTag<Button>(group)?.isEnabled == true }
            onUi { assertTrue(checkNotNull(main.window.decorView.findViewWithTag<Button>(group)).performClick()) }
            await("$id current goal entry") { main.window.decorView.findViewWithTag<Button>(key)?.isEnabled == true }
            onUi { assertTrue(checkNotNull(main.window.decorView.findViewWithTag<Button>(key)).performClick()) }
            await("$id period, progress, and claim state") {
                val view = tree(main.window.decorView).filterIsInstance<ProgressView>().singleOrNull() ?: return@await false
                val labels = tree(view).filterIsInstance<TextView>().map { it.text.toString() }
                val claim = main.window.decorView.findViewWithTag<Button>("progress-claim")
                view.uiState.selectedKey == key && "$count / ${goalCount}회" in labels &&
                    labels.any { it.startsWith("${period.start} · ${period.zone.id}") } &&
                    "${goal.definition.reward.units} 게임머니" in labels &&
                    claim?.isShown == true && claim.isEnabled == claimEnabled
            }
            Log.i(TAG, "CURRENT_GOAL pid=${Process.myPid()} id=$id period=${period.start} zone=${period.zone.id} " +
                "progress=$count/$goalCount claimEnabled=$claimEnabled")
            onUi { assertTrue(tree(main.window.decorView).filterIsInstance<ProgressView>().single().handleBack()) }
        }
        assertEquals(before, snapshot())
        read { game -> assertNull(ok(game.store.hostCommands.load(identity.profileId))) }
    }

    fun testColdMainCancelsExpiredDailySavedAndShowsCurrentPeriod() {
        val old = requireColdSeed("daily-vehicle-level-up", HostCheckpointPhase.SAVED)
        val before = snapshot()
        val main = activity
        openProgress(main)
        await("the expired request reports a failure and keeps the explicit cancel action") {
            texts(main).contains(context.getString(R.string.progress_failure)) &&
                cancelButton(main)?.let { it.isShown && it.isEnabled } == true
        }
        assertEquals(before, snapshot())
        read { game ->
            assertEquals(old, ok(game.store.hostCommands.load(game.identity.profileId)))
            assertNull(ok(game.store.quests.settlement(old.command.requestId)))
        }
        onUi { assertTrue(checkNotNull(cancelButton(main)).performClick()) }
        assertCurrentGoal(main, old, ProgressGroup.DAILY)
        read { game ->
            assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
            assertNull(ok(game.store.quests.settlement(old.command.requestId)))
        }
        assertEquals(before, snapshot())
        Log.i(TAG, "EXPIRED_SAVED_CANCELED pid=${Process.myPid()} request=${old.command.requestId.value} noCredit=true")
    }

    fun testColdMainClearsPreviousWeeklyStartedAndPreservesSettlement() {
        val old = requireColdSeed("weekly-rental-completed", HostCheckpointPhase.STARTED)
        val before = snapshot()
        val original = read { game ->
            checkNotNull(ok(game.store.quests.settlement(old.command.requestId))).also {
                assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(300)), it.settlement.transaction.money)
            }
        }
        val main = activity
        openProgress(main)
        await("the current progress view is attached after recovery") {
            main.window.decorView.findViewWithTag<Button>(ProgressGroup.WEEKLY)?.isEnabled == true
        }
        assertCurrentGoal(main, old, ProgressGroup.WEEKLY)
        read { game ->
            assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
            assertEquals(original, ok(game.store.quests.settlement(old.command.requestId)))
        }
        assertEquals(before, snapshot())
        Log.i(TAG, "STARTED_CLEARED_ORIGINAL_SETTLEMENT_PRESERVED pid=${Process.myPid()} request=${old.command.requestId.value} settlement=${original.settlement.settlementId.value}")
    }

    private fun seed(id: String, cadence: QuestCadence, execute: Boolean) {
        checkIsolation()
        for (name in listOf("game-ledger-vehicle.db", "game-ledger-simulation.db")) {
            assertFalse("a new isolated install is required: $name", context.getDatabasePath(name).exists())
        }
        val current = questPeriod(cadence, zone, EpochMillis(System.currentTimeMillis()))
        val previous = questPeriod(cadence, zone, EpochMillis(current.startsAt.value - 1))
        var clock = previous.startsAt.value
        val games = GameSessionFactory(context, mapOf(source to "game-ledger-vehicle.db"), source,
            questDefinitions = { ContractResult.Success(definitions) }, clock = { clock })
        val onboarding = StoredOnboardingHostSource(games)
        val identity = ok(onboarding.read()).identity
        assertEquals(source, identity.source)
        val casper = VehicleId("hyundai_casper")
        ok(onboarding.choose(identity, casper))
        ok(games.open()).use { game ->
            val store = game.store
            val vehicle = ok(store.listOwnedVehicles()).single()
            if (cadence == QuestCadence.DAILY) {
                clock += 1_000L
                ok(store.commitGameChange(GameChange(RequestId("period-growth"), TransactionKind.GARAGE,
                    source, trial.rules, MoneyChange(MoneyDirection.DEBIT, GameMoney(20)),
                    VehicleWrite.Update(vehicle.ownedVehicleId, vehicle.revision, 2, VehicleLifecycle.OWNED, 0))))
            } else {
                val content = ok(game.content)
                val hire = 3 * 3_600_000L
                val returnTime = 15 * 60_000L
                repeat(3) { index ->
                    val rental = RequestId("period-rental-$index")
                    val start = EpochMillis(clock)
                    val end = EpochMillis(clock + hire)
                    ok(store.rental.assign(RentalRequest(rental, casper, 3), content.catalog,
                        mapOf(casper to RentalCategory.SMALL), mapOf(casper to trial.rentalRates.getValue(casper)),
                        RentalTerms(content.ruleVersion, SimulatedCustomer(30, 48), 99, start, 15,
                            GameMoney(0), RoundingMode.DOWN, false)))
                    clock = end.value
                    ok(store.rental.account(rental, start, end, null))
                    ok(store.rental.settle(rental))
                    clock += returnTime
                    ok(store.rental.finishReturn(rental, EpochMillis(clock)))
                }
                // Settle the unrelated first-rental gift before taking the cold-start baseline.
                val gift = ok(FirstGrant(content).prepareOpeningGift(store, identity.profileId, source))
                ok(store.commitFirstRentalGift(gift))
            }
            val state = ok(store.quests.read(identity.profileId, Observation.Available(EpochMillis(clock))))
                .single { it.definition.id == id }
            assertEquals(previous, state.period)
            assertEquals(QuestStatus.CLAIMABLE, state.status)
            assertEquals(state.definition.goal, state.count)
            assertEquals(if (execute) 3 else 1, state.count)
            assertEquals(GameMoney(if (execute) 300 else 100), state.definition.reward)
            val command = HostCheckpointCommand.Quest(QuestClaimCommand(RequestId("quest/claim/period-recovery"),
                identity.profileId, source, state.definition, state.period, checkNotNull(state.revision)))
            val before = ok(store.balance())
            ok(store.hostCommands.save(identity.profileId, command))
            if (execute) {
                ok(store.hostCommands.execute(identity.profileId, command))
                assertEquals(GameMoney(before.units + 300), ok(store.balance()))
            } else {
                assertEquals(before, ok(store.balance()))
                assertNull(ok(store.quests.settlement(command.requestId)))
            }
            assertEquals(if (execute) HostCheckpointPhase.STARTED else HostCheckpointPhase.SAVED,
                ok(store.hostCommands.load(identity.profileId))?.phase)
            assertTrue(evidence.edit().putInt("seedPid", Process.myPid()).putString("id", id)
                .putLong("endsAt", previous.endsAt.value).commit())
            Log.i(TAG, "SEEDED pid=${Process.myPid()} id=$id end=${previous.endsAt.value} execute=$execute")
        }
    }

    private fun requireColdSeed(id: String, phase: HostCheckpointPhase): HostCommandCheckpoint {
        checkIsolation()
        assertEquals(id, evidence.getString("id", null))
        val seedPid = evidence.getInt("seedPid", -1)
        assertTrue("run this selector in a different OS process", seedPid > 0 && seedPid != Process.myPid())
        assertTrue(System.currentTimeMillis() >= evidence.getLong("endsAt", Long.MAX_VALUE))
        return read { game ->
            val pending = checkNotNull(ok(game.store.hostCommands.load(game.identity.profileId)))
            assertEquals(phase, pending.phase)
            assertEquals(id, (pending.command as HostCheckpointCommand.Quest).value.definition.id)
            pending
        }
    }

    private fun assertCurrentGoal(main: MainActivity, old: HostCommandCheckpoint, group: ProgressGroup) {
        val command = (old.command as HostCheckpointCommand.Quest).value
        val period = questPeriod(command.definition.cadence, zone, EpochMillis(System.currentTimeMillis()))
        assertTrue(period.startsAt.value >= command.period.endsAt.value)
        val current = progressKey(command.definition, period)
        await("current period group") { main.window.decorView.findViewWithTag<Button>(group)?.isEnabled == true }
        onUi { assertTrue(checkNotNull(main.window.decorView.findViewWithTag<Button>(group)).performClick()) }
        await("current goal replaces the expired key") {
            main.window.decorView.findViewWithTag<Button>(current)?.isEnabled == true &&
                main.window.decorView.findViewWithTag<Button>(progressKey(command.definition, command.period)) == null
        }
        onUi { assertTrue(checkNotNull(main.window.decorView.findViewWithTag<Button>(current)).performClick()) }
        await("current period must not inherit progress or offer a claim") {
            val view = tree(main.window.decorView).filterIsInstance<ProgressView>().singleOrNull() ?: return@await false
            val labels = tree(view).filterIsInstance<TextView>().map { it.text.toString() }
            view.uiState.selectedKey == current && "0 / ${command.definition.goal}회" in labels &&
                main.window.decorView.findViewWithTag<Button>("progress-claim")?.isEnabled == false
        }
    }

    private fun openProgress(main: MainActivity) = await("actual Main opens progression with SDK controls allowed") {
        val root = main.window.decorView
        val menu = root.findViewWithTag<Spinner>("host:destination") ?: return@await false
        if (!menu.isEnabled || !menu.isLaidOut || menu.isLayoutRequested) return@await false
        if (tree(root).any { it is ProgressView && it.isShown }) return@await true
        val index = (0 until menu.count).single { menu.getItemAtPosition(it).toString() == "퀘스트·진행" }
        if (menu.selectedItemPosition != index) menu.setSelection(index)
        false
    }

    private fun cancelButton(main: MainActivity) = tree(main.window.decorView).filterIsInstance<Button>()
        .singleOrNull { it.text.toString() == "미시작 보상 청구 취소 확인" }

    private fun texts(main: MainActivity) = tree(main.window.decorView).filterIsInstance<TextView>()
        .filter { it.isShown }.map { it.text.toString() }

    private fun snapshot() = read { game ->
        ok(game.store.read { db ->
            listOf("account", "initialization_profile", "quest_events", "quest_claims", "settlements")
                .associateWith { table ->
                    db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { rows ->
                        buildList { while (rows.moveToNext()) add(List(rows.columnCount) { rows.getString(it) }) }
                    }
                }
        })
    }

    private fun checkIsolation() {
        assertEquals("io.drivegrowth.verify.questperiode", context.packageName)
    }

    private fun <T> read(action: (GameSession) -> T): T = ok(GameSessionFactory(context).open()).use(action)

    private fun tree(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { tree(view.getChildAt(it)) } else emptyList()

    private fun await(label: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            if (onUi(check)) return
            SystemClock.sleep(50)
        }
        fail(label)
    }

    private fun <T> onUi(action: () -> T): T {
        val task = FutureTask<T> { action() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    private companion object { const val TAG = "QuestPeriodRecovery" }
}
