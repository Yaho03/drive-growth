package io.drivegrowth.app.simulation

import android.os.Process
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.data.ArrivalCommit
import io.drivegrowth.app.data.AssetTrialDefinitionProvider
import io.drivegrowth.app.data.DeliveryCheckpointOperation
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.HostCheckpointPhase
import io.drivegrowth.app.data.HostCommandCheckpoint
import io.drivegrowth.app.data.questDefinitions
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DeliveryInProgress
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.delivery.DeliveryOutcome
import io.drivegrowth.core.garage.CollectionKey
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Main의 기존 버튼과 실제 SQLite를 사용한다. 주행·도착 입력은 Debug 모의 시연에서만 온다. */
@Suppress("DEPRECATION")
class PurchaseDeliveryMainFlowTest :
    ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {

    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("unified-simulation-v1", 0)
    private val arrivalEvidence get() = context.filesDir.resolve("purchase28-arrival-before.json")

    private data class Purchase(val route: DeliveryInProgress, val offer: DeliveryOffer.Purchase)
    private data class Original(
        val profile: ProfileId,
        val balance: GameMoney,
        val vehicles: List<OwnedVehicle>,
        val settlements: List<Settlement>,
        val arrival: ArrivalCommit?,
        val checkpoint: HostCommandCheckpoint?,
    )

    fun testPurchaseThenAbandonRefundsExactlyOnce() {
        requireFresh("io.drivegrowth.verify.purchase28cancel")
        val purchase = buyFromMain()
        click("delivery:review-abandon")
        click("delivery:abandon")
        awaitReceipt("의뢰 포기가 확정됐어요.")

        val request = purchase.route.active.requestId
        val after = stored(request)
        val refund = purchase.offer.price.units * 9 / 10
        assertEquals(1, after.vehicles.size)
        assertNull(after.arrival)
        assertEquals(GameMoney(11_000 - purchase.offer.price.units + refund), after.balance)
        assertEquals(2, after.settlements.count { it.transaction.kind == TransactionKind.DELIVERY })
        val originalRefund = after.settlements.single { it.transaction.requestId == RequestId("delivery/${request.value}/settle") }
        assertEquals(TransactionKind.DELIVERY, originalRefund.transaction.kind)
        assertEquals(InputSource.SIMULATION, originalRefund.transaction.source)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(refund)), originalRefund.transaction.money)
        assertNull(originalRefund.changedVehicle)
        val pending = requireNotNull(after.checkpoint)
        assertEquals(HostCheckpointPhase.STARTED, pending.phase)
        assertTrue((pending.command as? HostCheckpointCommand.Delivery)?.operation is DeliveryCheckpointOperation.Abandon)
        assertNull(read { ok(it.store.delivery.inProgress()) })

        acknowledge(after.profile)
        assertEquals(after.copy(checkpoint = null), stored(request))
    }

    fun testPurchaseThenSimulatedArrivalPersistsOneVehicle() {
        requireFresh("io.drivegrowth.verify.purchase28arrival")
        val purchase = buyFromMain()
        click("simulation:start")
        await("모의 주행 입력이 마지막 주차 단계에 도달", timeoutMs = 75_000) { prefs.getInt("stage", 0) == 65 }
        awaitReceipt("구매 차량 지급이 확정됐어요.")

        val request = purchase.route.active.requestId
        val after = stored(request)
        assertEquals(purchase.route.route.value, prefs.getString("arrivalRoute", null))
        assertEquals(after.profile.value, prefs.getString("arrivalProfile", null))
        assertEquals(2, after.vehicles.size)
        val delivered = after.vehicles.single { it.acquisitionSource == VehicleAcquisitionSource.PURCHASE_DELIVERY }
        assertEquals(purchase.offer.vehicleId, delivered.vehicleId)
        assertEquals(purchase.offer.rarity, delivered.rarity)
        assertEquals(purchase.offer.price, delivered.acquisitionCost)
        assertNull(read { ok(it.store.delivery.inProgress()) })
        val originalArrival = requireNotNull(after.arrival)
        assertTrue(originalArrival.outcome is DeliveryOutcome.VehicleDelivered)
        val grant = requireNotNull(originalArrival.settlement)
        assertEquals(InputSource.SIMULATION, grant.transaction.source)
        assertEquals(TransactionKind.DELIVERY, grant.transaction.kind)
        assertEquals(RequestId("delivery/${request.value}/settle"), grant.transaction.requestId)
        assertNull(grant.transaction.money)
        assertEquals(delivered, grant.changedVehicle)
        assertEquals(2, after.settlements.count { it.transaction.kind == TransactionKind.DELIVERY })
        assertTrue(after.settlements.contains(grant))
        val pending = requireNotNull(after.checkpoint)
        assertEquals(HostCheckpointPhase.STARTED, pending.phase)
        val operation = (pending.command as? HostCheckpointCommand.Delivery)?.operation
        val arrival = requireNotNull(operation as? DeliveryCheckpointOperation.Arrival)
        assertEquals(request, arrival.requestId)
        val observed = requireNotNull((arrival.evidence as? Observation.Available)?.value)
        assertEquals(purchase.route.route, observed.routeId)
        assertEquals(InputSource.SIMULATION, observed.source)

        // 사용자가 확인하기 전의 상태를 다음 계측 프로세스에서 대조한다.
        arrivalEvidence.writeText(JSONObject().put("pid", Process.myPid()).put("request", request.value)
            .put("original", after.toString()).toString())
    }

    fun testColdProcessReopensSamePurchaseArrivalWithoutGrantingAgain() {
        assertEquals("io.drivegrowth.verify.purchase28arrival", context.packageName)
        val evidence = JSONObject(arrivalEvidence.readText())
        assertTrue("같은 프로세스의 Activity 재생성은 cold 근거가 아니다", Process.myPid() != evidence.getInt("pid"))
        val request = RequestId(evidence.getString("request"))
        val before = stored(request)
        assertEquals(evidence.getString("original"), before.toString())

        activity
        awaitUi("보관한 탁송 요청 확인") {
            views(activity.window.decorView).filterIsInstance<Button>()
                .firstOrNull { it.isShown && it.isEnabled && it.text.toString() == "보관한 탁송 요청 확인" }
                ?.performClick() == true
        }
        awaitReceipt("구매 차량 지급이 확정됐어요.")
        assertEquals(before, stored(request))
        acknowledge(before.profile)
        assertEquals(before.copy(checkpoint = null), stored(request))
        assertNull(read { ok(it.store.delivery.inProgress()) })
        context.filesDir.resolve("purchase28-arrival-after.json")
            .writeText(JSONObject().put("pid", Process.myPid()).put("request", request.value).toString())
    }

    private fun buyFromMain(): Purchase {
        for (tag in listOf(R.string.onboarding_start, VehicleId("hyundai_casper"), R.string.onboarding_review,
            R.string.onboarding_confirm, R.string.onboarding_to_shop)) click(tag)
        read { game ->
            assertEquals(InputSource.SIMULATION, game.identity.source)
            assertEquals(GameMoney(1_000), ok(game.store.balance()))
            assertEquals(1, ok(game.store.listOwnedVehicles()).size)
            val rule = ok(game.content).ruleVersion
            // 기본 36km 구매 후보는 초기 잔액을 넘을 수 있다. 구매 가능성만 준비하는 독립 시험 거래.
            val fixture = GameChange(RequestId("purchase28-fixture-credit"), TransactionKind.GARAGE,
                InputSource.SIMULATION, rule, MoneyChange(MoneyDirection.CREDIT, GameMoney(10_000)))
            assertEquals(GameMoney(11_000), ok(game.store.commitGameChange(fixture)).balance)
        }

        openDelivery()
        click("simulation:destination")
        val offer = firstPurchase()
        await("표시한 구매 후보의 발견 기록") {
            read { CollectionKey(offer.vehicleId, offer.rarity) in ok(it.store.discoveredVehicles()) }
        }
        click("delivery:offer:${offer.id.value}")
        click("delivery:confirm")
        awaitReceipt("의뢰 선택이 확정됐어요.")

        val purchase = read { game ->
            val route = requireNotNull(ok(game.store.delivery.inProgress()))
            assertEquals(InputSource.SIMULATION, route.active.proposed.estimated.source)
            assertEquals(offer, route.active.offer)
            assertEquals(offer.price, ok(game.store.delivery.terms())?.paid)
            val rows = ok(game.store.listUnviewedSettlements()).filter { it.transaction.kind == TransactionKind.DELIVERY }
            val payment = rows.single()
            assertEquals(route.active.requestId, payment.transaction.requestId)
            assertEquals(MoneyChange(MoneyDirection.DEBIT, offer.price), payment.transaction.money)
            assertNull(payment.changedVehicle)
            assertEquals(GameMoney(11_000 - offer.price.units), ok(game.store.balance()))
            assertEquals(1, ok(game.store.listOwnedVehicles()).size)
            Purchase(route, offer)
        }
        val selected = stored(purchase.route.active.requestId)
        acknowledge(selected.profile)
        assertEquals(selected.copy(checkpoint = null), stored(purchase.route.active.requestId))
        return purchase
    }

    private fun firstPurchase(): DeliveryOffer.Purchase {
        val day = Instant.ofEpochMilli(SimulationTimeline().clock.epoch.value)
            .atZone(ZoneId.of("UTC")).toLocalDate()
        var offer: DeliveryOffer.Purchase? = null
        await("현재 Main이 저장한 구매 후보") {
            offer = read { game ->
                ok(game.store.delivery.lists(day)).flatMap { it.offers }.filterIsInstance<DeliveryOffer.Purchase>()
                    .firstOrNull { it.price.units in 10L..11_000L && it.price.units % 10 == 0L }
            }
            offer != null
        }
        return requireNotNull(offer)
    }

    private fun openDelivery() {
        val root = activity.window.decorView
        awaitUi("Main 최초 탁송 조회 완료") {
            if (enabledButton("delivery:destination") != null) return@awaitUi true
            val menu = root.findViewWithTag<Spinner>("host:destination")
                ?.takeIf { it.isShown && it.isEnabled && it.isLaidOut && !it.isLayoutRequested }
                ?: return@awaitUi false
            val delivery = (0 until menu.count).single { menu.getItemAtPosition(it).toString() == "탁송" }
            // 조회가 Spinner를 교체하면 현재 메뉴에서 다시 선택한다.
            if (menu.selectedItemPosition != delivery) menu.setSelection(delivery)
            false
        }
    }

    private fun acknowledge(profile: ProfileId) {
        click("delivery:acknowledge")
        await("실행한 탁송의 확인 대기 슬롯 해제") { read { ok(it.store.hostCommands.load(profile)) == null } }
    }

    private fun stored(request: RequestId): Original = read { game ->
        Original(game.identity.profileId, ok(game.store.balance()), ok(game.store.listOwnedVehicles()),
            ok(game.store.listUnviewedSettlements()), ok(game.store.delivery.arrivalResult(request)),
            ok(game.store.hostCommands.load(game.identity.profileId)))
    }

    private fun requireFresh(packageName: String) {
        assertEquals(packageName, context.packageName)
        assertFalse("DB를 지우지 않고 새 격리 설치로 실행한다", context.getDatabasePath("unified-simulation.db").exists())
        assertFalse(prefs.contains("stage"))
        assertFalse(arrivalEvidence.exists())
    }

    private fun <T> read(block: (GameSession) -> T): T {
        val clock = { SimulationTimeline(prefs.getLong("elapsed", 0), prefs.getInt("stage", 0)).clock.epoch.value }
        val factory = GameSessionFactory(context, mapOf(InputSource.SIMULATION to "unified-simulation.db"),
            InputSource.SIMULATION, {
                when (val definitions = AssetTrialDefinitionProvider(context.assets).load()) {
                    is ContractResult.Failure -> definitions
                    is ContractResult.Success -> definitions.value.questDefinitions(ZoneId.of("UTC"))
                }
            }, clock)
        return ok(factory.open()).use(block)
    }

    private fun awaitReceipt(text: String) {
        activity // InstrumentationTestCase2는 접근 시 실제 Activity를 시작한다.
        awaitUi(text) {
            enabledButton("delivery:acknowledge") != null &&
                views(activity.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == text }
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun enabledButton(tag: Any): Button? =
        activity.window.decorView.findViewWithTag<Button>(tag)?.takeIf { it.isShown && it.isEnabled }

    private fun click(tag: Any) {
        activity
        awaitUi("활성 버튼 $tag") { enabledButton(tag)?.performClick() == true }
    }

    private fun awaitUi(label: String, condition: () -> Boolean) = await(label) {
        var result: Result<Boolean>? = null
        instrumentation.runOnMainSync { result = runCatching(condition) }
        requireNotNull(result).getOrThrow()
    }

    private fun await(label: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        var displayed = emptyList<String>()
        instrumentation.runOnMainSync {
            displayed = views(activity.window.decorView).filterIsInstance<TextView>()
                .filter { it.isShown }.map { it.text.toString() }.toList()
        }
        fail("$label · 화면: $displayed")
    }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
}
