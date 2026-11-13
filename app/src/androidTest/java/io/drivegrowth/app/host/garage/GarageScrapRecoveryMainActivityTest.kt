package io.drivegrowth.app.host.garage

import io.drivegrowth.app.exposeMainPage
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.mainNavigation
import io.drivegrowth.app.mainPage
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.HostCheckpointPhase
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostNavigation
import io.drivegrowth.app.host.HostPage
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.simulation.SimulationTimeline
import io.drivegrowth.app.simulation.UnifiedSimulationActivity
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.app.ui.garage.GarageView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.GachaPull
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.RentalCategory
import io.drivegrowth.core.rental.RentalRequest
import io.drivegrowth.core.rental.SimulatedCustomer
import java.math.RoundingMode
import java.util.concurrent.FutureTask

/** 격리 Debug Main의 차량 관리·폐차 복원. 가상 고객과 가챠 seed는 준비 입력이다. */
@Suppress("DEPRECATION")
class GarageScrapRecoveryMainActivityTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private val database get() = context.getDatabasePath("unified-simulation.db")
    private val casper = VehicleId("hyundai_casper")
    private val avante = VehicleId("hyundai_avante")
    private val scrapRequest = RequestId("garage-main-cold-scrap")
    private val initialTimeline = SimulationTimeline()
    private val fixtureEpoch = initialTimeline.clock.epoch.value + 3_600_000
    private val simulationPreferences get() = context.getSharedPreferences("unified-simulation-v1", Context.MODE_PRIVATE)
    private val games by lazy {
        GameSessionFactory(context, mapOf(InputSource.SIMULATION to database.name), InputSource.SIMULATION)
    }

    fun testGarageActionsAndRestrictionsPrepareUnstartedScrapForColdRecovery() {
        requireFresh()
        // Main requires a saved timeline when it opens a DB already created by the test.
        assertTrue(simulationPreferences.edit().putInt("stage", initialTimeline.stage)
            .putLong("elapsed", initialTimeline.elapsedMs).commit())
        requirePreparedSimulation()
        val onboarding = StoredOnboardingHostSource(games)
        val identity = ok(onboarding.read()).identity
        ok(onboarding.choose(identity, avante))
        val (target, material) = read { game ->
            val first = ok(game.store.listOwnedVehicles()).single()
            val finance = assign(game, first, 0, accident = false)
            settle(game, finance, 0)
            finishReturn(game, finance, 0, VehicleActivity.IDLE)
            val target = paidCasper(game, "target", Rarity.COMMON)
            val material = paidCasper(game, "material", Rarity.ADVANCED)
            ok(game.store.rental.setResting(material.ownedVehicleId, true))
            target to material
        }

        selectPage("보유 차량", HostPage.GARAGE)
        openFirstGarageDetail(target.ownedVehicleId)
        val leveled = transact(target, VehicleAction.LEVEL_UP, debit = 20) {
            it.copy(level = 2, revision = Revision(it.revision.value + 1))
        }
        tapTag(VehicleAction.USE_MATERIAL)
        val beforeMaterial = snapshot()
        tapButton { it.text.toString().startsWith("${material.ownedVehicleId.value} ·") }
        awaitLabel("비용 0 게임머니")
        tapText("확정")
        awaitLabel("당시 확정 잔액 ${beforeMaterial.balance.units} 게임머니")
        val materialResult = snapshot()
        assertEquals(beforeMaterial.balance, materialResult.balance)
        val materialReceipt = materialResult.settlements.drop(beforeMaterial.settlements.size).single()
        assertNull(materialReceipt.transaction.money)
        assertEquals(beforeMaterial.settlements + materialReceipt, materialResult.settlements)
        assertEquals(leveled.copy(level = 3, levelProgressPercent = 50, revision = Revision(leveled.revision.value + 1)),
            materialResult.vehicles.single { it.ownedVehicleId == target.ownedVehicleId })
        assertEquals(material.copy(lifecycle = VehicleLifecycle.CONSUMED, revision = Revision(material.revision.value + 1)),
            materialResult.vehicles.single { it.ownedVehicleId == material.ownedVehicleId })
        assertTrue(read { game -> ok(game.store.listOwnedVehicles()).none { it.ownedVehicleId == material.ownedVehicleId } })
        acknowledge()

        for (index in 1..3) {
            val rental = read { game -> assign(game, current(game, target.ownedVehicleId), index, accident = true) }
            if (index == 1) assertRestriction(target.ownedVehicleId, VehicleActivity.RENTED, "대여 중", emptySet())
            read { game -> assertTrue(settle(game, rental, index).accident) }
            if (index == 1) assertRestriction(target.ownedVehicleId, VehicleActivity.RETURNING, "반납 이동 중", emptySet())
            read { game ->
                finishReturn(game, rental, index, VehicleActivity.NEEDS_REPAIR)
                assertEquals(index, ok(game.store.rental.accidentCount(target.ownedVehicleId)))
            }
            val actions = setOf(VehicleAction.REPAIR, VehicleAction.SCRAP) +
                (if (index < 3) setOf(VehicleAction.SELL) else emptySet())
            assertRestriction(target.ownedVehicleId, VehicleActivity.NEEDS_REPAIR, "수리 필요", actions)
            if (index < 3) {
                val needsRepair = read { current(it, target.ownedVehicleId) }
                transact(needsRepair, VehicleAction.REPAIR, debit = 50, afterActivity = VehicleActivity.IDLE) {
                    it.copy(revision = Revision(it.revision.value + 1))
                }
                assertEquals(index, read { ok(it.store.rental.accidentCount(target.ownedVehicleId)) })
            }
        }
        tapTag(VehicleAction.SCRAP)
        awaitLabel("지급 100 게임머니")
        val before = snapshot()
        val damaged = before.vehicles.single { it.ownedVehicleId == target.ownedVehicleId }
        assertNull(before.checkpoint)

        // UI 확인 이전에는 durable 슬롯이 없다. 미실행 종료 조건은 공개 저널 save로만 준비한다.
        val adapter = CheckpointGarageCommandAdapter(context, sessions = games)
        val proposal = ok(adapter.prepare(GarageEvent.RequestAction(damaged.ownedVehicleId, damaged.revision,
            VehicleAction.SCRAP), scrapRequest, null)) as GaragePreparation.Ready
        ok(adapter.save(proposal.pending))
        val saved = snapshot()
        assertEquals(before, saved.copy(checkpoint = null))
        assertEquals(HostCheckpointPhase.SAVED, saved.checkpoint?.phase)
        assertEquals(proposal.pending.checkpoint(), saved.checkpoint?.command)
        report("SAVED", identity.profileId, saved)
    }

    fun testColdMainExecutesOriginalSavedScrapOnceAndLeavesReceipt() {
        requirePreparedSimulation()
        assertTrue("이전 selector의 시연 DB가 필요합니다.", database.exists())
        val before = snapshot()
        val saved = checkNotNull(before.checkpoint)
        assertEquals(HostCheckpointPhase.SAVED, saved.phase)
        val command = saved.command as HostCheckpointCommand.Garage
        assertEquals(scrapRequest, command.value.requestId)
        assertTrue(before.settlements.none { it.transaction.requestId == scrapRequest })
        val target = (command.value.vehicle as VehicleWrite.Update).ownedVehicleId
        val originalVehicle = before.vehicles.single { it.ownedVehicleId == target }
        assertEquals(VehicleLifecycle.OWNED, originalVehicle.lifecycle)
        assertEquals(3, read { ok(it.store.rental.accidentCount(target)) })
        showRecoveredCommand()
        awaitLabel("지급 100 게임머니")
        assertEquals(before, snapshot())
        tapText("보관 요청 처리 · 결과 확인")
        awaitLabel("당시 확정 잔액 ${before.balance.units + 100} 게임머니")
        val after = snapshot()
        val original = after.settlements.single { it.transaction.requestId == scrapRequest }
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(100)), original.transaction.money)
        assertEquals(GameMoney(before.balance.units + 100), original.balance)
        assertEquals(original.balance, after.balance)
        assertEquals(before.settlements + original, after.settlements)
        val scrapped = originalVehicle.copy(lifecycle = VehicleLifecycle.SCRAPPED,
            revision = Revision(originalVehicle.revision.value + 1))
        assertEquals(scrapped, original.changedVehicle)
        assertEquals(before.vehicles.map { if (it.ownedVehicleId == target) scrapped else it }, after.vehicles)
        assertEquals(saved.copy(phase = HostCheckpointPhase.STARTED), after.checkpoint)
        report("STARTED", saved.profileId, after)
    }

    fun testColdMainReplaysScrapReceiptAndAcknowledgesOnlyTheSlot() {
        requirePreparedSimulation()
        assertTrue("이전 selector의 시연 DB가 필요합니다.", database.exists())
        val before = snapshot()
        val saved = checkNotNull(before.checkpoint)
        assertEquals(HostCheckpointPhase.STARTED, saved.phase)
        val command = saved.command as HostCheckpointCommand.Garage
        assertEquals(scrapRequest, command.value.requestId)
        val original = before.settlements.single { it.transaction.requestId == scrapRequest }
        val vehicle = checkNotNull(original.changedVehicle)
        assertEquals(VehicleLifecycle.SCRAPPED, vehicle.lifecycle)
        assertEquals(vehicle, before.vehicles.single { it.ownedVehicleId == vehicle.ownedVehicleId })
        showRecoveredCommand()
        tapText("보관 요청 처리 · 결과 확인")
        awaitLabel("당시 확정 잔액 ${original.balance.units} 게임머니")
        assertEquals(before, snapshot())
        acknowledge()
        assertEquals(before.copy(checkpoint = null), snapshot())
        selectPage("도감", HostPage.COLLECTION)
        awaitUi("폐차한 캐스퍼의 일반 희귀도 보유 이력") {
            val heading = views(activityRoot()).filterIsInstance<TextView>().firstOrNull { it.text.toString() == "캐스퍼" }
            val groups = heading?.parent as? ViewGroup ?: return@awaitUi false
            val next = groups.getChildAt(groups.indexOfChild(heading) + 1) ?: return@awaitUi false
            (next as? ViewGroup)?.let { row ->
                (0 until row.childCount).map(row::getChildAt).any { card ->
                    val texts = views(card).filterIsInstance<TextView>().map { it.text.toString() }.toSet()
                    "일반" in texts && "보유 이력 있음" in texts
                }
            } == true
        }
        report("ACK", saved.profileId, snapshot())
    }

    private fun paidCasper(game: GameSession, label: String, rarity: Rarity): OwnedVehicle {
        val pull = GachaPull(ok(game.content))
        val command = (0 until 2_000).asSequence().map {
            ok(pull.prepare(game.store, RequestId("garage-fixture-$label-$it"), GachaTicketUse.PURCHASED))
        }.first { it.award.vehicle.vehicleId == casper && it.award.vehicle.rarity == rarity }
        val stored = checkNotNull(ok(game.store.commitGacha(command)).settlement.changedVehicle)
        assertEquals(GameMoney(500), stored.acquisitionCost)
        return stored
    }

    private fun assign(game: GameSession, car: OwnedVehicle, index: Int, accident: Boolean): RequestId {
        val id = RequestId("main-garage-fixture-rental-$index")
        val content = ok(game.content)
        // The rental planner validates the category of every eligible owned vehicle.
        val categories = mapOf(casper to RentalCategory.SMALL, avante to RentalCategory.SEDAN)
        val assigned = ok(game.store.rental.assign(RentalRequest(id, car.vehicleId, 1), content.catalog,
            categories, mapOf(car.vehicleId to GameMoney(100)),
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

    private fun assertRestriction(id: OwnedVehicleId, activity: VehicleActivity, label: String, enabled: Set<VehicleAction>) {
        assertEquals(activity, read { ok(it.store.rental.activity(id)) })
        tapTag("host:refresh")
        awaitUi("차고에서 현재 활동과 허용 행동 표시: $activity") {
            label in labels() && VehicleAction.entries.filter {
                activityRoot().findViewWithTag<Button>(it)?.isEnabled == true
            }.toSet() == enabled && activityRoot().findViewWithTag<Button>(VehicleAction.SCRAP) != null
        }
        val before = snapshot()
        // 비활성 레벨업 버튼의 실제 좌표 입력은 준비나 저장을 시작할 수 없다.
        tapButton(requireEnabled = false) { it.tag == VehicleAction.LEVEL_UP && !it.isEnabled }
        assertEquals(before, snapshot())
        awaitUi("제한 입력 후 차고 유지") { label in labels() }
    }

    private fun transact(car: OwnedVehicle, action: VehicleAction, debit: Long, afterActivity: VehicleActivity = VehicleActivity.IDLE,
                         expected: (OwnedVehicle) -> OwnedVehicle): OwnedVehicle {
        val before = snapshot()
        val current = before.vehicles.single { it.ownedVehicleId == car.ownedVehicleId }
        tapTag(action)
        awaitLabel(if (action == VehicleAction.REPAIR) "수리비 50 게임머니" else "차감 $debit 게임머니", prefix = true)
        assertEquals(before, snapshot())
        tapText("확정")
        awaitLabel("당시 확정 잔액 ${before.balance.units - debit} 게임머니")
        val after = snapshot()
        val updated = expected(current)
        val receipt = after.settlements.drop(before.settlements.size).single()
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(debit)), receipt.transaction.money)
        assertEquals(GameMoney(before.balance.units - debit), after.balance)
        assertEquals(after.balance, receipt.balance)
        assertEquals(before.settlements + receipt, after.settlements)
        assertEquals(before.vehicles.map { if (it.ownedVehicleId == updated.ownedVehicleId) updated else it }, after.vehicles)
        assertEquals(afterActivity, read { ok(it.store.rental.activity(updated.ownedVehicleId)) })
        acknowledge()
        assertEquals(after.copy(checkpoint = null), snapshot())
        return updated
    }

    private fun current(game: GameSession, id: OwnedVehicleId) =
        ok(game.store.listVehicleHistory()).single { it.ownedVehicleId == id }

    private fun showRecoveredCommand() {
        val route = "보관한 차량 관리 요청 확인"
        awaitUi("저장된 차고 관리 경로") { buttons().any { it.text.toString() == route || it.text.toString() == "보관 요청 처리 · 결과 확인" } }
        if (onUi { buttons().any { it.text.toString() == route } }) tapText(route)
        awaitLabel("보관한 요청입니다.", prefix = true)
    }

    private fun acknowledge() {
        tapText("결과 확인")
        awaitStored("확인 후 공용 슬롯 해제") { it.checkpoint == null }
    }

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
        assertEquals("폐차 복원 전용 계측 패키지", "io.drivegrowth.verify.garage466scrap", context.packageName)
        assertTrue((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
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
        val receipt = state.settlements.singleOrNull { it.transaction.requestId == scrapRequest }
        Log.i("GarageGrowthMain", "$phase pid=${Process.myPid()} profile=${profile.value} " +
            "balance=${state.balance.units} settlements=${state.settlements.size} scrap=${receipt?.settlementId?.value}")
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
    private fun buttons() = views(activityRoot()).filterIsInstance<Button>().filter { it.isShown }
    private fun labels() = views(activityRoot()).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()

    private fun awaitLabel(text: String, prefix: Boolean = false) =
        awaitUi(text) { labels().any { if (prefix) it.startsWith(text) else it == text } }

    private fun mainNavigation() = MainActivity::class.java.getDeclaredField("navigation")
        .apply { isAccessible = true }.get(activity) as HostNavigation

    private fun selectPage(label: String, page: HostPage) {
        val root = activityRoot()
        val navigation = mainNavigation()
        awaitUi("Main의 $label 라우트 수락") {
            if (navigation.page == page) return@awaitUi true
            val menu = root.exposeMainPage(page) ?: return@awaitUi false
            if (!menu.isShown || !menu.isEnabled || !menu.isLaidOut || menu.isLayoutRequested) return@awaitUi false
            root.openMainPage(page)
            false
        }
    }

    private data class GarageTarget(val button: Button, val x: Float, val y: Float, val scrollY: Int)

    private fun openFirstGarageDetail(id: OwnedVehicleId) {
        val root = activityRoot()
        val navigation = mainNavigation()
        val before = snapshot()
        var previous: GarageTarget? = null
        var target: GarageTarget? = null
        val events = mutableListOf<Int>()
        var nextReport = 0L
        fun blocked(reason: () -> String): Boolean {
            val now = SystemClock.uptimeMillis()
            if (now >= nextReport) {
                nextReport = now + 1_000
                Log.i("GarageGrowthInput", "detail blocked target=$id ${reason()}")
            }
            return false
        }
        awaitUi("GARAGE_INPUT_TARGET: 현재 차고의 $id 버튼 배치·스크롤·창 포커스 안정") {
            val garage = views(root).filterIsInstance<GarageView>().singleOrNull()
            val button = garage?.findViewWithTag<Button>(id)
            if (navigation.page != HostPage.GARAGE || garage == null || button == null || !button.isShown || !button.isEnabled ||
                !button.isAttachedToWindow || !button.hasWindowFocus() || generateSequence(button as View) { it.parent as? View }
                    .any { !it.isLaidOut || it.isLayoutRequested }) {
                previous = null
                return@awaitUi blocked {
                    val menu = root.mainNavigation()
                    "route=${navigation.page} menu=${menu?.isShown} rootFocus=${root.hasWindowFocus()} " +
                        "garage=${garage != null} button=${button?.let(::geometry)} " +
                        "layoutPending=${button?.let { v -> generateSequence(v as View) { it.parent as? View }
                            .filter { !it.isLaidOut || it.isLayoutRequested }.joinToString { it.javaClass.simpleName } }}"
                }
            }
            val viewport = generateSequence(button.parent) { it.parent }.filterIsInstance<ScrollView>().single()
            val visible = Rect()
            if (!button.getGlobalVisibleRect(visible) || visible.width() != button.width || visible.height() != button.height) {
                val moved = button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                previous = null
                return@awaitUi blocked { "clipped moved=$moved visible=$visible button=${geometry(button)} viewport=${geometry(viewport)}" }
            }
            val xy = IntArray(2)
            button.getLocationOnScreen(xy)
            val candidate = GarageTarget(button, xy[0] + button.width / 2f, xy[1] + button.height / 2f, viewport.scrollY)
            if (previous != candidate) {
                previous = candidate
                return@awaitUi blocked { "await stable ${geometry(button)} viewport=${geometry(viewport)}" }
            }
            assertNull("상세를 열기 전 차고 View 선택", garage.selectedVehicleId)
            assertNull("상세를 열기 전 Main 선택", navigation.selectedVehicleId)
            button.setOnTouchListener { _, event ->
                Log.i("GarageGrowthInput", "detail touch target=$id action=${event.actionMasked} raw=(${event.rawX},${event.rawY})")
                events.add(event.actionMasked)
                false
            }
            target = candidate
            Log.i("GarageGrowthInput", "detail target=$id center=(${candidate.x},${candidate.y}) " +
                "button=${geometry(button)} viewport=${geometry(viewport)}")
            true
        }
        val stable = checkNotNull(target)
        try {
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, stable.x, stable.y, 0)
                try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            }
            instrumentation.waitForIdleSync()
            onUi {
                assertEquals("GARAGE_INPUT_DELIVERY: 선택한 $id 버튼이 DOWN·UP을 받아야 합니다. " +
                    "attached=${stable.button.isAttachedToWindow}, page=${navigation.page}, selection=${navigation.selectedVehicleId}",
                    listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), events)
            }
        } finally {
            onUi { stable.button.setOnTouchListener(null) }
        }
        awaitUi("GARAGE_DETAIL_ROUTE: $id 선택 후 Main·View 상세와 강화 버튼 생성") {
            val garage = views(root).filterIsInstance<GarageView>().singleOrNull()
            navigation.page == HostPage.GARAGE && navigation.selectedVehicleId == id &&
                garage != null && garage.selectedVehicleId == id &&
                garage.findViewWithTag<Button>(VehicleAction.LEVEL_UP)?.isEnabled == true
        }
        assertEquals("상세 열기만으로 원장을 변경하지 않습니다", before, snapshot())
        Log.i("GarageGrowthInput", "detail verified target=$id mainId=$id garageId=$id ledgerUnchanged=true")
    }

    private fun tapTag(tag: Any) = tapButton(target = "tag=$tag") { it.tag == tag }
    private fun tapText(text: String) = tapButton(target = "text=$text") { it.text.toString() == text }

    private fun tapButton(
        requireEnabled: Boolean = true,
        target: String = "predicate",
        predicate: (Button) -> Boolean,
    ) {
        val root = activityRoot()
        val deadline = SystemClock.uptimeMillis() + 20_000
        var lastBlocked = ""
        var nextReport = 0L
        var previous: GarageTarget? = null
        val events = mutableListOf<Int>()
        fun blocked(reason: String) {
            previous = null
            lastBlocked = reason
            val now = SystemClock.uptimeMillis()
            if (now >= nextReport) {
                Log.i("GarageGrowthInput", "$target blocked: $reason")
                nextReport = now + 2_000
            }
        }
        do {
            val point = onUi {
                val matches = views(root).filterIsInstance<Button>().filter(predicate)
                val button = matches.firstOrNull { it.isShown && (!requireEnabled || it.isEnabled) }
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
                val current = GarageTarget(button, offset[0] + button.width / 2f,
                    offset[1] + button.height / 2f, scrolls.firstOrNull()?.scrollY ?: 0)
                if (previous != current) {
                    previous = current
                    return@onUi null
                }
                if (requireEnabled) button.setOnTouchListener { _, event ->
                    events.add(event.actionMasked)
                    false
                }
                Log.i("GarageGrowthInput", "$target sending center=(${current.x},${current.y}) ${geometry(button)}")
                current
            }
            if (point != null) {
                try {
                    val down = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
                        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                    }
                    instrumentation.waitForIdleSync()
                    if (requireEnabled) onUi {
                        Log.i("GarageGrowthInput", "$target received=$events attached=${point.button.isAttachedToWindow}")
                        assertEquals("GARAGE_INPUT_DELIVERY: $target", listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), events)
                    }
                } finally {
                    if (requireEnabled) onUi { point.button.setOnTouchListener(null) }
                }
                return
            }
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        error("좌표 입력 대상 버튼이 현재 화면에 온전히 보여야 합니다 ($target; $lastBlocked)")
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
