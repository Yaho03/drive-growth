package io.drivegrowth.app.host.progression

import io.drivegrowth.app.exposeMainPage
import io.drivegrowth.app.mainPage
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.host.HostPage
import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.data.questDefinitions
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.simulation.SimulationTimeline
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.app.ui.progress.ProgressView
import io.drivegrowth.core.achievement.ACHIEVEMENT_CLAIM_PREFIX
import io.drivegrowth.core.achievement.AchievementClaimSettlement
import io.drivegrowth.core.achievement.AchievementMetric
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.garage.GachaPull
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.RentalCategory
import io.drivegrowth.core.rental.RentalRequest
import io.drivegrowth.core.rental.SimulatedCustomer
import java.math.RoundingMode
import java.time.ZoneOffset

/** Fixtures use production ledger commands. Only the three achievement claims are driven through Main. */
@Suppress("DEPRECATION")
class RemainingAchievementsMainTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {
    private data class Target(val metric: AchievementMetric, val title: String, val progress: String, val reward: Long)
    private val targets = listOf(
        Target(AchievementMetric.CUSTOMERS, "누적 고객 수", "3 / 3", 150),
        Target(AchievementMetric.SHOP, "가게 레벨", "2 / 2", 200),
        Target(AchievementMetric.COLLECTION, "도감 완성률", "4 / 80칸 · 목표 5%", 150),
    )
    private val context get() = instrumentation.targetContext
    private val timeline = SimulationTimeline()
    private val database get() = context.getDatabasePath("unified-simulation.db")
    private val prefs get() = context.getSharedPreferences("unified-simulation-v1", 0)
    private val trial by lazy { ok(AssetTrialDefinitionProvider(context.assets).load()) }
    private val games by lazy {
        GameSessionFactory(context, mapOf(InputSource.SIMULATION to database.name), InputSource.SIMULATION,
            { trial.questDefinitions(ZoneOffset.UTC) }, { timeline.clock.epoch.value }).also { it.enableAchievements() }
    }
    private val automation by lazy {
        checkNotNull(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
    }

    fun testClaimsCustomersShopAndCollectionInMainExactlyOnce() {
        requireIsolated()
        assertFalse(database.exists())
        assertFalse(prefs.contains("stage"))
        assertFalse(prefs.contains("elapsed"))
        prepareGoals()
        val root = openProgress()
        val start = snapshot()
        assertEquals(emptyList<AchievementClaimSettlement>(), start.claims)
        for (target in targets) {
            tap(root, achievementKey(target.metric))
            assertDetail(root, target, claimed = false)
            val before = snapshot()
            tap(root, "progress-claim", twice = true)
            assertDetail(root, target, claimed = true)
            await("수령 뒤 공용 슬롯 해제") { isSlotEmpty() }
            val after = snapshot()
            val added = after.claims.single { it.command.milestone.definition.metric == target.metric }
            assertReceipt(added, target, before.profile)
            assertEquals(GameMoney(before.balance.units + target.reward), after.balance)
            assertEquals(after.balance, added.settlement.balance)
            assertEquals(before.copy(balance = after.balance, settlements = before.settlements + added.settlement,
                claims = before.claims + added), after)

            tap(root, "progress-claim", enabled = false)
            assertDetail(root, target, claimed = true)
            assertEquals(after, snapshot())
            closeDetail(root, target)
        }
        val end = snapshot()
        assertEquals(GameMoney(start.balance.units + 500), end.balance)
        report("CLAIMED", end)
    }

    fun testNewProcessReadsSameThreeClaimsAndCannotPayAgain() {
        requireIsolated()
        assertTrue("이전 selector의 시연 원장을 유지해야 한다", database.exists())
        assertEquals(timeline.stage, prefs.getInt("stage", -1))
        assertEquals(timeline.elapsedMs, prefs.getLong("elapsed", -1))
        val before = snapshot()
        assertEquals(3, before.claims.size)
        val root = openProgress()
        for (target in targets) {
            assertReceipt(before.claims.single { it.command.milestone.definition.metric == target.metric }, target, before.profile)
            tap(root, achievementKey(target.metric))
            assertDetail(root, target, claimed = true)
            tap(root, "progress-claim", enabled = false)
            assertDetail(root, target, claimed = true)
            closeDetail(root, target)
        }
        val after = snapshot()
        assertEquals(before, after)
        report("REOPENED", after)
    }

    private fun prepareGoals() {
        // Prepare the stage before creating the isolated DB. Main restricts old DBs without a timeline.
        assertTrue(prefs.edit().putInt("stage", timeline.stage).putLong("elapsed", timeline.elapsedMs).commit())
        val onboarding = StoredOnboardingHostSource(games)
        val identity = ok(onboarding.read()).identity
        val casper = VehicleId("hyundai_casper")
        ok(onboarding.choose(identity, casper))
        ok(games.open()).use { game ->
            val store = game.store
            val content = ok(game.content)
            val first = ok(store.listOwnedVehicles()).single()
            assertEquals(casper, first.vehicleId)
            assertEquals(GameMoney(1_000), ok(store.balance()))
            val rate = trial.rentalRates.getValue(casper)
            assertEquals(GameMoney(100), rate)
            val hireMs = 3 * 3_600_000L
            val returnMs = 15 * 60_000L
            val slot = hireMs + returnMs
            repeat(3) { index ->
                val id = RequestId("achievement-main-rental-$index")
                val start = EpochMillis(timeline.clock.epoch.value - (3 - index) * slot)
                val end = EpochMillis(start.value + hireMs)
                val assigned = ok(store.rental.assign(RentalRequest(id, casper, 3), content.catalog,
                    mapOf(casper to RentalCategory.SMALL), mapOf(casper to rate),
                    RentalTerms(content.ruleVersion, SimulatedCustomer(30, 48), 99, start, 15,
                        GameMoney(0), RoundingMode.DOWN, false)))
                assertEquals(first.ownedVehicleId, assigned)
                ok(store.rental.account(id, start, end, null))
                val rental = ok(store.rental.settle(id))
                assertFalse(rental.accident)
                assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(360)), rental.settlement.transaction.money)
                assertEquals(VehicleActivity.IDLE, ok(store.rental.finishReturn(id, EpochMillis(end.value + returnMs))))
            }
            assertEquals(3, ok(store.rental.completedRentalCount()))
            val expansion = trial.expansions.offers.single { it.fromLevel == 1 }
            assertEquals(GameMoney(500), expansion.price)
            ok(store.rental.expand(RequestId("achievement-main-expansion"), expansion))
            val shop = ok(store.rental.shop())
            assertEquals(2 to 5, shop.progress.shopLevel to shop.capacity)

            val keys = mutableSetOf(CollectionKey(first.vehicleId, first.rarity))
            val pull = GachaPull(content)
            repeat(3) { index ->
                val command = (0 until 2_000).asSequence().map { seed ->
                    val id = RequestId("achievement-main-collection-$index-$seed")
                    ok(pull.prepare(store, id, GachaTicketUse.PURCHASED))
                }.first { CollectionKey(it.award.vehicle.vehicleId, it.award.vehicle.rarity) !in keys }
                val result = ok(store.commitGacha(command)).settlement
                val vehicle = checkNotNull(result.changedVehicle)
                assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(500)), result.transaction.money)
                assertTrue(keys.add(CollectionKey(vehicle.vehicleId, vehicle.rarity)))
                Log.i(TAG, "FIXTURE_SEED request=${command.requestId.value} seed=${command.requestId.value.hashCode()} " +
                    "vehicle=${vehicle.vehicleId.value} rarity=${vehicle.rarity}")
            }
            assertEquals(4, keys.size)
            assertEquals(80, content.catalog.vehicles.size * Rarity.entries.size)
            assertEquals(GameMoney(80), ok(store.balance()))
        }
    }

    private fun requireIsolated() {
        assertEquals("io.drivegrowth.app.verify.progressclaims", context.packageName)
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        assertFalse(context.getDatabasePath("game-ledger-simulation.db").exists())
    }

    private fun openProgress(): View {
        val root = activity.window.decorView
        awaitUi("Main의 퀘스트·진행 페이지와 실제 업적 항목") {
            val shown = progressView(root)
            if (shown != null && targets.all { shown.findViewWithTag<Button>(achievementKey(it.metric))?.isShown == true }) {
                return@awaitUi true
            }
            val menu = root.exposeMainPage(HostPage.PROGRESSION) ?: return@awaitUi false
            if (!menu.isShown || !menu.isEnabled || !menu.isLaidOut || menu.isLayoutRequested) return@awaitUi false
            root.openMainPage(HostPage.PROGRESSION)
            false
        }
        return root
    }

    private fun assertDetail(root: View, target: Target, claimed: Boolean) {
        awaitUi("업적 상세: ${target.title}, 수령=$claimed") {
            val button = root.findViewWithTag<Button>("progress-claim") ?: return@awaitUi false
            val card = button.parent as ViewGroup
            val labels = (0 until card.childCount).mapNotNull { card.getChildAt(it) as? TextView }.map { it.text.toString() }
            val state = context.getString(if (claimed) R.string.progress_claimed else R.string.progress_claimable)
            val action = context.getString(if (claimed) R.string.progress_no_claim else R.string.progress_claim)
            button.isShown && button.isEnabled == !claimed && button.text.toString() == action &&
                labels.containsAll(listOf(target.title, target.progress, "${target.reward} 게임머니", state))
        }
    }

    private fun closeDetail(root: View, target: Target) {
        input("input keyevent 4")
        awaitUi("Back으로 같은 업적 목록 복귀") { root.findViewWithTag<Button>(achievementKey(target.metric))?.isShown == true }
    }

    private fun tap(root: View, tag: Any, enabled: Boolean = true, twice: Boolean = false) {
        var previous: Pair<Int, Pair<Int, Int>>? = null
        var point: Pair<Int, Int>? = null
        awaitUi("터치 대상의 전체 영역과 위치: $tag") {
            val button = root.findViewWithTag<Button>(tag)
            if (button == null || !button.isShown || !button.isAttachedToWindow || !button.isLaidOut ||
                button.isLayoutRequested || button.isEnabled != enabled) {
                previous = null
                return@awaitUi false
            }
            val rect = Rect()
            if (!button.getGlobalVisibleRect(rect) || rect.width() != button.width || rect.height() != button.height) {
                button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                previous = null
                return@awaitUi false
            }
            val location = IntArray(2)
            button.getLocationOnScreen(location)
            val center = location[0] + button.width / 2 to location[1] + button.height / 2
            val current = System.identityHashCode(button) to center
            val stable = current == previous
            previous = current
            if (stable) point = center
            stable
        }
        val (x, y) = checkNotNull(point)
        val count = if (twice) 2 else 1
        val window = activity.window
        val observed = mutableListOf<TouchDelivery>()
        val selected = onUi { progressView(root)?.uiState?.selectedKey }
        val original = onUi {
            checkNotNull(window.callback).also { delegate ->
                window.callback = object : Window.Callback by delegate {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        val action = event.actionMasked
                        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) {
                            return delegate.dispatchTouchEvent(event)
                        }
                        val button = root.findViewWithTag<Button>(tag)
                        val bounds = Rect()
                        if (button?.isShown == true && button.isAttachedToWindow && button.getLocalVisibleRect(bounds)) {
                            val location = IntArray(2)
                            button.getLocationOnScreen(location)
                            bounds.offset(location[0], location[1])
                        } else {
                            bounds.setEmpty()
                        }
                        val atPoint = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
                            event.rawX.toInt() == x && event.rawY.toInt() == y
                        val atButton = bounds.contains(x, y)
                        val pending = progressView(root)?.uiState?.pending?.key
                        val hiddenForPending = button == null && selected != null && pending == selected
                        val enabledAtDispatch = button?.isEnabled
                        val downTime = event.downTime
                        val handled = delegate.dispatchTouchEvent(event)
                        observed.add(TouchDelivery(action, downTime, atPoint, atButton, hiddenForPending,
                            enabledAtDispatch, handled))
                        return handled
                    }
                }
            }
        }
        val output = try {
            input("input tap $x $y", count)
        } finally {
            onUi { window.callback = original }
        }
        val deliveries = onUi { observed.toList() }
        val evidence = "대상=$tag 좌표=($x,$y) 입력출력=$output 이벤트=$deliveries"
        assertEquals(evidence, count * 2, deliveries.size)
        assertEquals(evidence, enabled, deliveries.first().enabled)
        for ((index, pair) in deliveries.chunked(2).withIndex()) {
            val (down, up) = pair
            assertEquals(evidence, MotionEvent.ACTION_DOWN, down.action)
            assertEquals(evidence, MotionEvent.ACTION_UP, up.action)
            assertEquals(evidence, down.downTime, up.downTime)
            for (event in pair) {
                assertTrue(evidence, event.atPoint && event.handled &&
                    (event.atButton || (twice && index == 1 && event.hiddenForPending)))
            }
        }
        assertEquals(evidence, count, deliveries.map { it.downTime }.distinct().size)
        Log.i(TAG, "TOUCH $evidence")
    }

    private fun progressView(root: View): ProgressView? = when (root) {
        is ProgressView -> root.takeIf { it.isShown }
        is ViewGroup -> (0 until root.childCount).firstNotNullOfOrNull { progressView(root.getChildAt(it)) }
        else -> null
    }

    private data class TouchDelivery(
        val action: Int, val downTime: Long, val atPoint: Boolean, val atButton: Boolean,
        val hiddenForPending: Boolean, val enabled: Boolean?, val handled: Boolean,
    )

    private fun input(command: String, count: Int = 1): List<String> {
        val output = List(count) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
                it.readBytes().decodeToString().trim()
            }
        }
        instrumentation.waitForIdleSync()
        return output
    }

    private data class Snapshot(val profile: ProfileId, val balance: GameMoney, val settlements: List<Settlement>,
        val claims: List<AchievementClaimSettlement>, val vehicles: List<OwnedVehicle>, val customers: Int, val shop: Pair<Int, Int>)

    private fun snapshot(): Snapshot = ok(games.open()).use { game ->
        val store = game.store
        assertNull(ok(store.hostCommands.load(game.identity.profileId)))
        val rows = ok(store.listUnviewedSettlements())
        val claims = rows.filter { it.transaction.requestId.value.startsWith(ACHIEVEMENT_CLAIM_PREFIX) }.map { row ->
            checkNotNull(ok(store.achievements.settlement(row.transaction.requestId))).also { assertEquals(row, it.settlement) }
        }
        val shop = ok(store.rental.shop())
        Snapshot(game.identity.profileId, ok(store.balance()), rows, claims,
            ok(store.listVehicleHistory()).sortedBy { it.ownedVehicleId.value }, ok(store.rental.completedRentalCount()),
            shop.progress.shopLevel to shop.capacity)
    }

    private fun isSlotEmpty(): Boolean = ok(games.open()).use { game ->
        ok(game.store.hostCommands.load(game.identity.profileId)) == null
    }

    private fun assertReceipt(claim: AchievementClaimSettlement, target: Target, profile: ProfileId) {
        val milestone = claim.command.milestone
        assertEquals(profile, milestone.profileId)
        assertEquals(InputSource.SIMULATION, milestone.source)
        assertEquals(target.metric, milestone.definition.metric)
        assertEquals(RuleVersion("achievement-trial-v1"), milestone.definition.version)
        assertEquals(GameMoney(target.reward), milestone.definition.reward)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(target.reward)), claim.settlement.transaction.money)
        assertEquals(claim.command.requestId, claim.settlement.transaction.requestId)
        when (target.metric) {
            AchievementMetric.CUSTOMERS -> { assertEquals(3, milestone.definition.goal); assertEquals(3, milestone.measure.count); assertNull(milestone.measure.total) }
            AchievementMetric.SHOP -> { assertEquals(2, milestone.definition.goal); assertEquals(2, milestone.measure.count); assertNull(milestone.measure.total) }
            AchievementMetric.COLLECTION -> { assertEquals(5, milestone.definition.goal); assertEquals(4, milestone.measure.count); assertEquals(80, milestone.measure.total) }
            AchievementMetric.OWNED -> error("보유 차량 업적은 기존 검증 범위")
        }
    }

    private fun report(phase: String, state: Snapshot) {
        val keys = state.claims.joinToString(",") { "${it.command.requestId.value}:${it.settlement.settlementId.value}:${it.settlement.balance.units}" }
        Log.i(TAG, "$phase pid=${Process.myPid()} profile=${state.profile.value} balance=${state.balance.units} claims=$keys")
    }

    private fun awaitUi(label: String, condition: () -> Boolean) = await(label) { onUi(condition) }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail(label)
    }

    private fun <T> onUi(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    private companion object {
        const val TAG = "RemainingAchievementsMain"
    }
}
