package io.drivegrowth.app.host.garage

import android.app.UiAutomation
import android.content.Context
import android.content.pm.ApplicationInfo
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
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.simulation.SimulationTimeline
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.RentalCategory
import io.drivegrowth.core.rental.RentalRequest
import io.drivegrowth.core.rental.SimulatedCustomer
import java.math.RoundingMode
import java.util.concurrent.FutureTask

/** Debug Main의 첫 확장 좌표 입력과 같은 시연 원장의 새 프로세스 복원. 대여는 시험용 준비 입력이다. */
@Suppress("DEPRECATION")
class GarageGrowthMainActivityTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private val database get() = context.getDatabasePath("unified-simulation.db")
    private val casper = VehicleId("hyundai_casper")
    private val initialTimeline = SimulationTimeline()
    private val fixtureEpoch = initialTimeline.clock.epoch.value + 3_600_000
    private val simulationPreferences get() = context.getSharedPreferences("unified-simulation-v1", Context.MODE_PRIVATE)
    private val games by lazy {
        GameSessionFactory(context, mapOf(InputSource.SIMULATION to database.name), InputSource.SIMULATION)
    }

    fun testNewPlayerChoosesFirstExpansionInMainAfterThreeCompletedRentals() {
        requireFresh()
        for (tag in listOf(R.string.onboarding_start, casper, R.string.onboarding_review,
            R.string.onboarding_confirm, R.string.onboarding_to_shop)) tapTag(tag)
        awaitUi("가게로 버튼을 누른 뒤 실제 HOME 선택") {
            activityRoot().findViewWithTag<Spinner>("host:destination")?.selectedItem?.toString() == "가게"
        }
        read { game ->
            assertEquals(GameMoney(1_000), ok(game.store.balance()))
            assertEquals(3, ok(game.store.rental.shop()).capacity)
            val first = ok(game.store.listOwnedVehicles()).single()
            assertEquals(casper, first.vehicleId)
            repeat(3) { index ->
                val rental = assign(game, current(game, first.ownedVehicleId), index, accident = false)
                val rent = settle(game, rental, index)
                assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(120)), rent.settlement.transaction.money)
                finishReturn(game, rental, index, VehicleActivity.IDLE)
            }
            assertEquals(3, ok(game.store.rental.completedRentalCount()))
        }
        tapText("새로고침")
        val before = awaitStored("확장 전 첫 영업 선물의 원래 정산 완료") { state ->
            state.settlements.count { it.transaction.requestId == RequestId("opening-gift") } == 1
        }
        assertEquals(1 to 3, before.shopLevel to before.capacity)
        assertEquals(GameMoney(1_360), before.balance)
        tapTag("shop:preview:expand")
        awaitLabel("레벨 1 → 2 · 공간 3 → 5")
        assertEquals(before, snapshot())
        tapTag("shop:confirm")
        awaitFirstExpansionUi()
        val after = awaitStored("첫 확장의 durable 완료") { it.shopLevel == 2 && it.checkpoint == null }
        assertEquals(5, after.capacity)
        assertEquals(GameMoney(before.balance.units - 500), after.balance)
        assertEquals(before.vehicles, after.vehicles)
        val receipt = after.settlements.drop(before.settlements.size).single()
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(500)), receipt.transaction.money)
        assertEquals(after.balance, receipt.balance)
        assertEquals(before.settlements + receipt, after.settlements)
        report("FIRST_EXPANSION", read { it.identity.profileId }, after)
    }

    fun testColdMainReadsFirstExpansionWithoutAnotherDebit() {
        requirePreparedSimulation()
        assertTrue("이전 selector의 시연 DB가 필요합니다.", database.exists())
        val before = snapshot()
        assertEquals(2 to 5, before.shopLevel to before.capacity)
        assertEquals(GameMoney(860), before.balance)
        assertNull(before.checkpoint)
        assertEquals(casper, before.vehicles.single().vehicleId)
        assertEquals(1, before.settlements.count {
            it.transaction.kind == TransactionKind.FACILITY && it.transaction.money == MoneyChange(MoneyDirection.DEBIT, GameMoney(500))
        })
        awaitFirstExpansionUi()
        assertEquals(before, snapshot())
        report("FIRST_EXPANSION_REOPENED", read { it.identity.profileId }, before)
    }

    private fun awaitFirstExpansionUi() = awaitUi("첫 확장 Lv2·5칸과 잔액의 확정 화면") {
        "가게 레벨 · 2 · 보유 / 공간 · 1 / 5" in labels() && "확정 잔액 · 860 게임머니" in labels()
    }

    private fun assign(game: GameSession, car: OwnedVehicle, index: Int, accident: Boolean): RequestId {
        val id = RequestId("main-garage-fixture-rental-$index")
        val category = if (car.vehicleId == casper) RentalCategory.SMALL else RentalCategory.SEDAN
        val content = ok(game.content)
        val assigned = ok(game.store.rental.assign(RentalRequest(id, car.vehicleId, 1), content.catalog,
            mapOf(car.vehicleId to category), mapOf(car.vehicleId to GameMoney(100)),
            RentalTerms(content.ruleVersion, SimulatedCustomer(30, 48), if (accident) 0 else 99,
                start(index), 15, GameMoney(0), RoundingMode.DOWN, false)))
        assertEquals(car.ownedVehicleId, assigned)
        return id
    }

    private fun start(index: Int) = EpochMillis(fixtureEpoch + index * 4_500_000)
    private fun settle(game: GameSession, id: RequestId, index: Int): io.drivegrowth.app.data.RentalReceipt {
        ok(game.store.rental.account(id, start(index), EpochMillis(start(index).value + 3_600_000), null))
        return ok(game.store.rental.settle(id))
    }

    private fun finishReturn(game: GameSession, id: RequestId, index: Int, expected: VehicleActivity) {
        assertEquals(expected, ok(game.store.rental.finishReturn(id, EpochMillis(start(index).value + 4_500_000))))
    }

    private fun current(game: GameSession, id: OwnedVehicleId) =
        ok(game.store.listVehicleHistory()).single { it.ownedVehicleId == id }

    private data class Snapshot(val balance: GameMoney, val vehicles: List<OwnedVehicle>, val settlements: List<Settlement>,
                                val checkpoint: io.drivegrowth.app.data.HostCommandCheckpoint?, val shopLevel: Int, val capacity: Int)

    private fun snapshot() = read { game ->
        val store = game.store
        val shop = ok(store.rental.shop())
        Snapshot(ok(store.balance()), ok(store.listVehicleHistory()).sortedBy { it.ownedVehicleId.value },
            ok(store.listUnviewedSettlements()), ok(store.hostCommands.load(game.identity.profileId)),
            shop.progress.shopLevel, shop.capacity)
    }

    private fun awaitStored(label: String, predicate: (Snapshot) -> Boolean): Snapshot {
        val end = SystemClock.uptimeMillis() + 20_000
        do {
            val value = snapshot()
            if (predicate(value)) return value
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < end)
        error(label)
    }

    private fun requireIsolated() {
        assertTrue("별도 Debug 앱에서만 실행", context.packageName != "io.drivegrowth.app" &&
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
    }

    private fun requireFresh() {
        requireIsolated()
        assertFalse(database.exists())
        assertFalse(simulationPreferences.contains("stage"))
        assertFalse(simulationPreferences.contains("elapsed"))
    }

    private fun requirePreparedSimulation() {
        requireIsolated()
        assertEquals("초기 주차 단계 보존", initialTimeline.stage, simulationPreferences.getInt("stage", -1))
        assertEquals("초기 주차 경과 시각 보존", initialTimeline.elapsedMs, simulationPreferences.getLong("elapsed", -1))
    }

    private fun report(phase: String, profile: ProfileId, state: Snapshot) {
        Log.i("GarageGrowthMain", "$phase pid=${Process.myPid()} profile=${profile.value} " +
            "balance=${state.balance.units} settlements=${state.settlements.size}")
    }

    private fun <T> read(action: (GameSession) -> T): T = ok(games.open()).use(action)
    private fun <T> ok(result: ContractResult<T>): T = when (result) {
        is ContractResult.Success -> result.value
        is ContractResult.Failure -> error(result.toString())
    }

    private fun activityRoot() = activity.window.decorView
    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }
    private fun labels() = views(activityRoot()).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()

    private fun awaitLabel(text: String) = awaitUi(text) { text in labels() }

    private fun tapTag(tag: Any) = tapButton(target = "tag=$tag") { it.tag == tag }
    private fun tapText(text: String) = tapButton(target = "text=$text") { it.text.toString() == text }

    private data class TapTarget(val button: Button, val x: Float, val y: Float)

    private fun tapButton(
        target: String = "predicate",
        predicate: (Button) -> Boolean,
    ) {
        val root = activityRoot()
        val deadline = SystemClock.uptimeMillis() + 20_000
        var lastBlocked = ""
        var nextReport = 0L
        var previous: TapTarget? = null
        fun blocked(reason: String) {
            lastBlocked = reason
            previous = null
            val now = SystemClock.uptimeMillis()
            if (now >= nextReport) {
                Log.i("GarageGrowthInput", "$target blocked: $reason")
                nextReport = now + 2_000
            }
        }
        do {
            val point = onUi {
                val matches = views(root).filterIsInstance<Button>().filter(predicate)
                val button = matches.firstOrNull { it.isShown && it.isEnabled }
                if (button == null) {
                    val pages = views(root).filterIsInstance<ScrollView>().filter { it.isShown }
                        .joinToString { geometry(it) }
                    blocked("no eligible match; matches=${matches.joinToString { geometry(it) }} scrolls=$pages")
                    return@onUi null
                }
                if (!button.isLaidOut || button.isLayoutRequested) {
                    blocked("await layout; ${geometry(button)}")
                    return@onUi null
                }
                val scrolls = generateSequence(button.parent) { it.parent }.filterIsInstance<ScrollView>().toList()
                val before = scrolls.map { it.scrollY }
                val moved = button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                val visible = Rect()
                if (!button.isAttachedToWindow || !button.getGlobalVisibleRect(visible) ||
                    visible.width() != button.width || visible.height() != button.height) {
                    val after = scrolls.joinToString { geometry(it) }
                    blocked("clipped; moved=$moved scrollYBefore=$before button=${geometry(button)} scrolls=$after")
                    return@onUi null
                }
                val offset = IntArray(2)
                button.getLocationOnScreen(offset)
                TapTarget(button, offset[0] + button.width / 2f, offset[1] + button.height / 2f)
            }
            if (point != null && point == previous) {
                touch(point, target)
                return
            }
            previous = point
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        error("좌표 입력 대상 버튼이 현재 화면에 온전히 보여야 합니다 ($target; $lastBlocked)")
    }

    private data class TapEvent(val action: Int, val downTime: Long, val atTarget: Boolean)

    private fun touch(point: TapTarget, target: String) {
        val observed = mutableListOf<TapEvent>()
        val tag = point.button.tag
        val verify = target == "tag=shop:preview:expand" || target == "tag=shop:confirm" ||
            tag is VehicleId || tag in listOf(R.string.onboarding_start, R.string.onboarding_review,
                R.string.onboarding_confirm, R.string.onboarding_to_shop)
        val x = point.x.toInt()
        val y = point.y.toInt()
        if (verify) onUi {
            point.button.setOnTouchListener { view, event ->
                val visible = Rect()
                val atTarget = view.getLocalVisibleRect(visible) && visible.contains(event.x.toInt(), event.y.toInt()) &&
                    event.rawX.toInt() == x && event.rawY.toInt() == y &&
                    event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                observed.add(TapEvent(event.actionMasked, event.downTime, atTarget))
                false
            }
            Log.i("GarageGrowthInput", "$target sending system tap at (${point.x},${point.y}) ${geometry(point.button)}")
        }
        try {
            if (verify) {
                val automation = checkNotNull(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES))
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap $x $y"))
                    .bufferedReader().use { it.readText() }
            } else {
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                }
            }
            instrumentation.waitForIdleSync()
            if (verify) {
                val events = onUi { observed.toList() }
                Log.i("GarageGrowthInput", "$target system touch events=$events")
                assertEquals("$target DOWN/UP", listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP),
                    events.map { it.action })
                assertTrue("$target exact touchscreen coordinates and one gesture",
                    events.all { it.atTarget } && events.map { it.downTime }.distinct().size == 1)
            }
        } finally {
            if (verify) onUi { point.button.setOnTouchListener(null) }
        }
    }

    private fun geometry(view: View): String {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val local = Rect()
        val shown = view.getLocalVisibleRect(local)
        val id = view.tag ?: (view as? Button)?.text
        return "$id shown=${view.isShown} enabled=${view.isEnabled} laidOut=${view.isLaidOut} " +
            "layoutRequested=${view.isLayoutRequested} attached=${view.isAttachedToWindow} " +
            "size=${view.width}x${view.height} screen=(${location[0]},${location[1]}) " +
            "localVisible=$shown:${local.toShortString()} scrollY=${view.scrollY} " +
            "canScrollUp=${view.canScrollVertically(-1)} canScrollDown=${view.canScrollVertically(1)}"
    }

    private fun awaitUi(label: String, condition: () -> Boolean) {
        activityRoot()
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onUi(condition)) return
            SystemClock.sleep(40)
        }
        fail(label)
    }

    private fun <T> onUi(action: () -> T): T {
        val task = FutureTask<T> { action() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
