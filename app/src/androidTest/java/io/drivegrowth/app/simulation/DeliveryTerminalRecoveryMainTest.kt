package io.drivegrowth.app.simulation

import io.drivegrowth.app.mainPage
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.host.HostPage
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.data.ArrivalCommit
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.data.DeliveryCheckpointOperation
import io.drivegrowth.app.data.HoldCommit
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.HostCheckpointPhase
import io.drivegrowth.app.data.HostCommandCheckpoint
import io.drivegrowth.app.data.questDefinitions
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/** 준비 상태는 격리 SIMULATION 원장에 저장한다. 종료 거래와 복원은 실제 Debug Main이 처리한다. */
@Suppress("DEPRECATION")
class DeliveryTerminalRecoveryMainTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {

    private enum class Scenario(val suffix: String, val message: String) {
        MISSED("missed", "미도착 의뢰의 구매 대금 환급이 확정됐어요."),
        INCOME("income", "도착 기한이 지나 의뢰가 종료됐어요."),
        CLAIM("claim", "보관 차량 수령이 확정됐어요."),
        FEE("fee", "보관 만료 환급이 확정됐어요.");

        val isNoArrival get() = this == MISSED || this == INCOME
    }

    private val context get() = instrumentation.targetContext
    private val database get() = context.getDatabasePath("unified-simulation.db")
    private val prefs get() = context.getSharedPreferences("unified-simulation-v1", 0)
    private val evidence get() = context.filesDir.resolve("delivery39-terminal-original.json")
    private val request = RequestId("delivery39-terminal-fixture")
    private val route = RouteId("delivery39-terminal-fixture-route")
    private val source = InputSource.SIMULATION
    private val clockBase = SimulationTimeline().clock.epoch
    private val utc = ZoneId.of("UTC")
    private val day = Instant.ofEpochMilli(clockBase.value).atZone(utc).toLocalDate()
    private val definitions by lazy { ok(AssetTrialDefinitionProvider(context.assets).load()) }
    private val games by lazy {
        GameSessionFactory(context, mapOf(source to database.name), source,
            { definitions.questDefinitions(utc) }, { clockBase.value + prefs.getLong("elapsed", 0) })
    }

    fun testMainProcessesPreparedTerminalCondition() {
        val scenario = isolatedScenario()
        assertFalse(database.exists())
        assertFalse(prefs.contains("stage"))
        assertFalse(evidence.exists())
        val offer = prepare(scenario)
        if (scenario == Scenario.INCOME) captureIncomeSettlements("income-selected")
        val before = snapshot()
        assertNull(before.checkpoint)
        assertNull(before.holdResult)
        if (offer is DeliveryOffer.Purchase) {
            val originalPayment = before.settlements.single { it.transaction.requestId == request }
            assertEquals(MoneyChange(MoneyDirection.DEBIT, offer.price), originalPayment.transaction.money)
        } else {
            assertTrue(offer is DeliveryOffer.Income)
            assertEquals(offer, before.active?.active?.offer)
            assertEquals(GameMoney(1_000), before.balance)
            assertTrue(before.settlements.none { it.transaction.kind == TransactionKind.DELIVERY })
        }

        awaitReceipt(scenario, recovered = false)
        if (scenario == Scenario.INCOME) captureIncomeSettlements("income-expired")
        val after = snapshot()
        assertEquals(before.profile, after.profile)
        assertNull(after.active)
        assertTrue(after.held.isEmpty())
        assertEquals(before.list, after.list)
        assertEquals(before.selectionRows, after.selectionRows)
        val settlement = when (scenario) {
            Scenario.INCOME -> {
                val expiry = checkNotNull(after.arrival)
                assertEquals(DeliveryOutcome.IncomeExpired, expiry.outcome)
                assertNull(after.holdResult)
                assertNull(expiry.settlement)
                expiry.settlement
            }
            Scenario.MISSED -> {
                assertTrue(after.arrival?.outcome is DeliveryOutcome.PurchaseRefunded)
                assertNull(after.holdResult)
                checkNotNull(after.arrival?.settlement)
            }
            Scenario.CLAIM -> {
                assertEquals(before.arrival, after.arrival)
                assertTrue(after.holdResult?.outcome is HoldOutcome.Claimed)
                checkNotNull(after.holdResult?.settlement)
            }
            Scenario.FEE -> {
                assertEquals(before.arrival, after.arrival)
                assertTrue(after.holdResult?.outcome is HoldOutcome.Refunded)
                checkNotNull(after.holdResult?.settlement)
            }
        }
        val credit = when (scenario) {
            Scenario.MISSED -> (offer as DeliveryOffer.Purchase).price
            Scenario.FEE -> {
                val price = (offer as DeliveryOffer.Purchase).price.units
                GameMoney(price - price * 5 / 100)
            }
            Scenario.CLAIM, Scenario.INCOME -> null
        }
        val receivedVehicle = if (scenario == Scenario.CLAIM) before.held.single().vehicle else null
        if (scenario != Scenario.INCOME) {
            val original = checkNotNull(settlement)
            val suffix = if (scenario.isNoArrival) "settle" else "hold"
            assertEquals(RequestId("delivery/${request.value}/$suffix"), original.transaction.requestId)
            assertEquals(source, original.transaction.source)
            assertEquals(TransactionKind.DELIVERY, original.transaction.kind)
            assertEquals(credit?.let { MoneyChange(MoneyDirection.CREDIT, it) }, original.transaction.money)
            assertEquals(after.balance, original.balance)
            assertEquals(receivedVehicle, original.changedVehicle)
        }
        assertEquals(before.settlements + listOfNotNull(settlement), after.settlements)
        assertEquals(GameMoney(before.balance.units + (credit?.units ?: 0L)), after.balance)
        assertEquals((before.vehicles + listOfNotNull(receivedVehicle)).sortedBy { it.ownedVehicleId.value }, after.vehicles)
        val pending = checkNotNull(after.checkpoint)
        assertEquals(HostCheckpointPhase.STARTED, pending.phase)
        val operation = (pending.command as HostCheckpointCommand.Delivery).operation
        val operationRequest = when (scenario) {
            Scenario.MISSED, Scenario.INCOME -> {
                val expiry = operation as DeliveryCheckpointOperation.Arrival
                assertTrue(expiry.evidence is Observation.Missing)
                expiry.requestId
            }
            Scenario.CLAIM, Scenario.FEE -> (operation as DeliveryCheckpointOperation.Hold).requestId
        }
        assertEquals(request, operationRequest)
        evidence.writeText(JSONObject().put("pid", Process.myPid()).put("scenario", scenario.name)
            .put("request", request.value).put("settlement", settlement?.settlementId?.value ?: JSONObject.NULL)
            .put("snapshot", after.toString()).toString())
    }

    fun testAcknowledgingHeldVehicleReceiptClearsCollectionPendingRoute() {
        val scenario = isolatedScenario()
        assertEquals(Scenario.CLAIM, scenario)
        assertFalse(database.exists())
        assertFalse(prefs.contains("stage"))
        prepare(scenario)
        awaitReceipt(scenario, recovered = false)
        val before = snapshot()
        assertEquals(HostCheckpointPhase.STARTED, before.checkpoint?.phase)
        assertTrue(before.holdResult?.outcome is HoldOutcome.Claimed)

        val root = activity.window.decorView
        fun onCollection(): Boolean {
            val menu = root.mainPage(HostPage.COLLECTION)?.takeIf { it.isShown && it.isEnabled } ?: return false
            if (!menu.isSelected) root.openMainPage(HostPage.COLLECTION)
            return root.mainPage(HostPage.COLLECTION)?.isSelected == true
        }
        awaitUi("수령 확인 전 도감의 보관한 탁송 요청 버튼") { onCollection() && storedDeliveryButton(root) != null }
        awaitUi("도감에서 보관한 탁송 요청 열기") { storedDeliveryButton(root)?.performClick() == true }
        awaitUi("보관 차량 수령 결과 확인") { button("delivery:acknowledge")?.performClick() == true }
        await("공용 슬롯 해제") { snapshot().checkpoint == null }
        awaitUi("재조회 버튼을 누르지 않아도 도감의 보관한 탁송 요청 버튼이 사라져야 합니다") {
            onCollection() && views(root).filterIsInstance<Button>().none {
                it.isShown && it.text.toString() == "보관한 탁송 요청 확인"
            }
        }
        assertEquals(before.copy(checkpoint = null), snapshot())
    }

    fun testColdMainReopensOriginalTerminalResultAndClearsOnlyCheckpoint() {
        val scenario = isolatedScenario()
        assertTrue(database.isFile)
        val original = JSONObject(evidence.readText())
        assertEquals(scenario.name, original.getString("scenario"))
        assertEquals(request.value, original.getString("request"))
        assertFalse("새 OS 프로세스가 필요합니다.", original.getInt("pid") == Process.myPid())
        val before = snapshot()
        assertEquals(original.getString("snapshot"), before.toString())
        if (scenario == Scenario.INCOME) {
            assertTrue(original.isNull("settlement"))
            assertEquals(DeliveryOutcome.IncomeExpired, before.arrival?.outcome)
            assertNull(before.arrival?.settlement)
            assertTrue(before.settlements.none { it.transaction.kind == TransactionKind.DELIVERY })
        } else {
            assertEquals(original.getString("settlement"), before.settlements.last().settlementId.value)
        }
        assertEquals(HostCheckpointPhase.STARTED, before.checkpoint?.phase)

        awaitReceipt(scenario, recovered = true)
        if (scenario == Scenario.INCOME) captureIncomeSettlements("income-reopened")
        assertEquals("새 PID에서 원결과를 표시한 뒤 비교 상태가 유지됨", before, snapshot())
        awaitUi("원결과 확인") { button("delivery:acknowledge")?.performClick() == true }
        await("확인 슬롯만 해제") { snapshot().checkpoint == null }
        if (scenario == Scenario.INCOME) captureIncomeSettlements("income-acknowledged")
        assertEquals(before.copy(checkpoint = null), snapshot())
        context.filesDir.resolve("delivery39-terminal-reopened.json").writeText(
            JSONObject().put("pid", Process.myPid()).put("request", request.value)
                .put("settlement", original.get("settlement")).toString())
    }

    /** viewed 값과 직렬화된 원문을 포함해 정산 전 행을 읽는다. 앱의 열람 상태는 변경하지 않는다. */
    private fun captureIncomeSettlements(phase: String) {
        val rows = read { game ->
            ok(game.store.read { db ->
                db.rawQuery(
                    """SELECT quote(request_id), quote(settlement_id), quote(command), quote(result), quote(viewed)
                       FROM settlements ORDER BY request_id COLLATE BINARY""", null,
                ).use { cursor ->
                    val rows = JSONArray()
                    while (cursor.moveToNext()) {
                        val row = JSONArray()
                        for (index in 0 until cursor.columnCount) row.put(cursor.getString(index))
                        rows.put(row)
                    }
                    rows
                }
            })
        }
        context.filesDir.resolve("delivery39-$phase-settlements.json").writeText(
            JSONObject().put("phase", phase).put("pid", Process.myPid()).put("request", request.value)
                .put("columns", JSONArray(listOf("request_id", "settlement_id", "command", "result", "viewed")))
                .put("rows", rows).toString(),
        )
        val forbidden = setOf("'${request.value}'", "'delivery/${request.value}/settle'")
        for (index in 0 until rows.length()) {
            assertFalse("$phase 수익 만료에 금융 행이 없어야 합니다.", rows.getJSONArray(index).getString(0) in forbidden)
        }
        if (phase != "income-selected") {
            val original = JSONObject(context.filesDir.resolve("delivery39-income-selected-settlements.json").readText())
            assertEquals(request.value, original.getString("request"))
            assertEquals("$phase 전체 정산 행 보존", original.getJSONArray("rows").toString(), rows.toString())
        }
    }

    private fun prepare(scenario: Scenario): DeliveryOffer {
        setTimeline(clockBase)
        val onboarding = StoredOnboardingHostSource(games)
        ok(onboarding.choose(ok(onboarding.read()).identity, VehicleId("hyundai_casper")))
        return read { game ->
            assertEquals(source, game.identity.source)
            val store = game.store
            val content = ok(game.content)
            val shop = ok(store.rental.shop())
            assertEquals(3, shop.capacity)
            assertEquals(1, ok(store.listOwnedVehicles()).size)
            val rule = content.ruleVersion
            if (scenario != Scenario.INCOME) {
                ok(store.commitGameChange(GameChange(RequestId("delivery39-fixture-fund"), TransactionKind.GARAGE,
                    source, rule, MoneyChange(MoneyDirection.CREDIT, GameMoney(10_000)))))
            }
            val proposal = DeliveryProposal(ok(definitions.deliveryRules()))
            val proposed = ok(proposal.propose(emptyList(), DestinationKey("delivery39-simulation-destination"),
                Observation.Available(EstimatedRouteDistance(36_000, source)), content, shop.progress.shopLevel, day, Random(39)))
            val offer = if (scenario == Scenario.INCOME) proposed.offers.filterIsInstance<DeliveryOffer.Income>().first()
                else proposed.offers.filterIsInstance<DeliveryOffer.Purchase>().first()
            ok(store.delivery.saveList(proposed.list))
            ok(store.delivery.select(proposed, offer.id, request))
            val progress = ok(store.delivery.beginRoute(request, route, clockBase))
            val rules = ok(definitions.deliverySettlementRules())
            assertEquals(86_400_000L, rules.arrivalWindowMs)
            assertEquals(86_400_000L, rules.holdWindowMs)
            assertEquals(5, rules.holdRefundFeePercent)
            if (scenario.isNoArrival) {
                assertFalse(prefs.contains("arrivalRoute"))
                assertNull(ok(store.delivery.arrivalResult(request)))
                setTimeline(EpochMillis(progress.startedAt.value + rules.arrivalWindowMs))
                return@read offer
            }

            // 공용 원장의 SIMULATION 준비 입력. Main의 도착 처리나 실제 SDK 관측으로 집계하지 않는다.
            repeat(shop.capacity - 1) { index ->
                val car = OwnedVehicle(OwnedVehicleId("delivery39-filler-$index"), VehicleId("hyundai_casper"),
                    1, Revision(0), VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.GACHA)
                ok(store.commitGameChange(GameChange(RequestId("delivery39-add-$index"), TransactionKind.GARAGE,
                    source, rule, vehicle = VehicleWrite.Add(car))))
            }
            val arrivedAt = EpochMillis(progress.startedAt.value + 1_000)
            setTimeline(arrivedAt)
            val arrival = ok(store.delivery.settleArrival(rules, request,
                Observation.Available(DeliveryArrivalEvidence(route, source, arrivedAt)), arrivedAt) { list, id ->
                proposal.replaceCompleted(list, id, content, shop.progress.shopLevel, Random(40))
            })
            val held = (arrival.outcome as DeliveryOutcome.VehicleHeld).held
            assertNull(arrival.settlement)
            assertEquals(shop.capacity, ok(store.listOwnedVehicles()).size)
            assertEquals(listOf(held), ok(store.delivery.held()))
            assertNull(ok(store.delivery.active()))
            if (scenario == Scenario.CLAIM) {
                val filler = ok(store.listOwnedVehicles()).single { it.ownedVehicleId == OwnedVehicleId("delivery39-filler-0") }
                ok(store.commitGameChange(GameChange(RequestId("delivery39-free-slot"), TransactionKind.GARAGE, source,
                    rule, vehicle = VehicleWrite.Update(filler.ownedVehicleId, filler.revision, filler.level,
                        VehicleLifecycle.SOLD, filler.levelProgressPercent))))
                assertEquals(shop.capacity - 1, ok(store.listOwnedVehicles()).size)
                setTimeline(EpochMillis(held.deadline.value - 1))
            } else setTimeline(held.deadline)
            offer
        }
    }

    private fun setTimeline(at: EpochMillis) {
        assertTrue(prefs.edit().putInt("stage", 0).putLong("elapsed", at.value - clockBase.value).commit())
    }

    private fun isolatedScenario(): Scenario {
        assertTrue((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        return Scenario.entries.single { context.packageName == "io.drivegrowth.verify.delivery39${it.suffix}" }
    }

    private data class Snapshot(
        val profile: ProfileId, val balance: GameMoney, val vehicles: List<OwnedVehicle>,
        val settlements: List<Settlement>, val list: List<DeliveryList>, val active: DeliveryInProgress?,
        val held: List<HeldVehicle>, val arrival: ArrivalCommit?, val holdResult: HoldCommit?,
        val checkpoint: HostCommandCheckpoint?, val selectionRows: List<List<String>>,
    )

    private fun snapshot() = read { game ->
        val store = game.store
        val selection = ok(store.read { db ->
            db.rawQuery("SELECT * FROM delivery_requests ORDER BY rowid", null).use { rows ->
                buildList { while (rows.moveToNext()) add((0 until rows.columnCount).map(rows::getString)) }
            }
        })
        Snapshot(game.identity.profileId, ok(store.balance()), ok(store.listVehicleHistory()),
            ok(store.listUnviewedSettlements()), ok(store.delivery.lists(day)), ok(store.delivery.inProgress()),
            ok(store.delivery.held()), ok(store.delivery.arrivalResult(request)), ok(store.delivery.holdResult(request)),
            ok(store.hostCommands.load(game.identity.profileId)), selection)
    }

    private fun awaitReceipt(scenario: Scenario, recovered: Boolean) {
        val root = activity.window.decorView
        awaitUi(scenario.message) {
            if (button("delivery:acknowledge") != null && views(root).filterIsInstance<TextView>()
                    .any { it.isShown && it.text.toString() == scenario.message }) return@awaitUi true
            if (recovered) {
                val pending = storedDeliveryButton(root)
                if (pending != null) {
                    pending.performClick()
                    return@awaitUi false
                }
            }
            val menu = root.mainPage(HostPage.DELIVERY)
                ?.takeIf { it.isShown && it.isEnabled && it.isLaidOut && !it.isLayoutRequested }
                ?: return@awaitUi false
            root.openMainPage(HostPage.DELIVERY)
            false
        }
    }

    private fun storedDeliveryButton(root: View): Button? = views(root).filterIsInstance<Button>().firstOrNull {
        it.isShown && it.isEnabled && it.text.toString() == "보관한 탁송 요청 확인"
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun button(tag: String): Button? = activity.window.decorView.findViewWithTag<Button>(tag)
        ?.takeIf { it.isShown && it.isEnabled }

    private fun awaitUi(label: String, condition: () -> Boolean) = await(label) {
        var result: Result<Boolean>? = null
        instrumentation.runOnMainSync { result = runCatching(condition) }
        checkNotNull(result).getOrThrow()
    }

    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail(label)
    }

    private fun <T> read(block: (GameSession) -> T): T = ok(games.open()).use(block)
    private fun <T> ok(result: ContractResult<T>): T = when (result) {
        is ContractResult.Success -> result.value
        is ContractResult.Failure -> error(result.toString())
    }
}
