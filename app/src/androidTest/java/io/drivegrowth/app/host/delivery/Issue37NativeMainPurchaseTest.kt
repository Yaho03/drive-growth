package io.drivegrowth.app.host.delivery

import android.content.pm.PackageManager
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.R
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostSession
import io.drivegrowth.app.ui.delivery.DeliveryScreen
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.vehicle.navigation.NavigationConnection
import io.drivegrowth.app.vehicle.navigation.NavigationField
import io.drivegrowth.app.vehicle.navigation.NavigationSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.garage.CollectionKey
import org.json.JSONObject
import kotlin.math.abs

/** 진단 앱이 준비하고 해제한 안내 A를 기존 Main의 실제 SDK 어댑터로 읽는다. */
@Suppress("DEPRECATION")
class Issue37NativeMainPurchaseTest :
    ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private val games by lazy { GameSessionFactory(context) }
    private val session: HostSession by lazy {
        MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
            .get(activity) as HostSession
    }
    private val evidence by lazy { context.filesDir.resolve("issue37-native-main.jsonl") }

    fun testNativeQuoteDisplaysAndConfirmsOnePurchase() = verifyPurchase("buy", affordable = true)

    fun testNativeQuoteShowsInsufficientFundsWithoutChanges() = verifyPurchase("poor", affordable = false)

    private fun verifyPurchase(scenario: String, affordable: Boolean) {
        assertEquals("io.drivegrowth.verify.issue37$scenario", context.packageName)
        for (permission in listOf("pleos.car.permission.NAVI_ROUTE", "android.car.permission.CAR_SPEED")) {
            assertEquals(permission, PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(permission))
        }
        for (database in listOf("game-ledger-vehicle.db", "game-ledger-simulation.db", "unified-simulation.db")) {
            assertFalse(database, context.getDatabasePath(database).exists())
        }
        assertFalse(evidence.exists())
        activity
        instrumentation.runOnMainSync { assertNull(session.simulation) }
        for (tag in listOf(R.string.onboarding_start, VehicleId("hyundai_casper"), R.string.onboarding_review,
            R.string.onboarding_confirm, R.string.onboarding_to_shop)) {
            awaitUi("첫 차량 입력 $tag") { button(tag)?.performClick() == true }
        }
        val identity = read { game ->
            assertEquals(InputSource.VEHICLE, game.identity.source)
            assertEquals(GameMoney(1_000), ok(game.store.balance()))
            assertEquals(1, ok(game.store.listOwnedVehicles()).size)
            assertTrue(ok(game.store.discoveredVehicles()).isEmpty())
            val content = ok(game.content)
            val change = if (affordable) MoneyChange(MoneyDirection.CREDIT, GameMoney(10_000))
                else MoneyChange(MoneyDirection.DEBIT, GameMoney(1_000))
            val fixture = GameChange(RequestId("issue37-fixture-$scenario"), TransactionKind.GARAGE,
                InputSource.VEHICLE, content.ruleVersion, change)
            assertEquals(GameMoney(if (affordable) 11_000 else 0), ok(game.store.commitGameChange(fixture)).balance)
            game.identity
        }
        val before = ledger()
        awaitUi("Main의 탁송 화면") {
            if (button("delivery:destination") != null) return@awaitUi true
            val spinner = activity.window.decorView.findViewWithTag<Spinner>("host:destination")
                ?.takeIf { it.isEnabled && it.isShown && it.isLaidOut && !it.isLayoutRequested }
                ?: return@awaitUi false
            val index = (0 until spinner.count).single { spinner.getItemAtPosition(it).toString() == "탁송" }
            if (spinner.selectedItemPosition != index) spinner.setSelection(index)
            false
        }
        awaitUi("SDK 연결 후 내비 조회") {
            val snapshot = session.deliveryNavigation.snapshot ?: return@awaitUi false
            if (snapshot.connection != NavigationConnection.CONNECTED_OBSERVED || snapshot.failure != null) return@awaitUi false
            labelledButton("내비 목적지 다시 조회")?.performClick() == true
        }
        var sdkMeters = -1L
        awaitUi("실제 SDK 안내 A의 신선한 최초 견적") {
            val current = session.deliveryNavigation.snapshot ?: return@awaitUi false
            val destination = current.destination as? NavigationField.Value ?: return@awaitUi false
            val distance = current.remainingEstimatedMeters as? NavigationField.Value ?: return@awaitUi false
            val route = current.routeId as? NavigationField.Value ?: return@awaitUi false
            val guidance = current.guidance as? NavigationField.Value ?: return@awaitUi false
            val generation = current.stamp.connectionGeneration
            val age = SystemClock.elapsedRealtime() - distance.stamp.receivedElapsedMillis
            if (current.connection != NavigationConnection.CONNECTED_OBSERVED || current.failure != null ||
                route.value.isBlank() || guidance.value.mode != "ROUTE_GUIDING" ||
                listOf(route.stamp, distance.stamp, guidance.stamp).any {
                    it.connectionGeneration != generation || it.source != NavigationSource.PLEOS_NAVI_SDK
                } || distance.stamp != destination.stamp || age !in 0L..5_000L ||
                (SystemClock.elapsedRealtime() - guidance.stamp.receivedElapsedMillis) !in 0L..5_000L ||
                distance.value <= 0L ||
                abs(destination.value.latitude - 37.41289678) > 0.001 ||
                abs(destination.value.longitude - 127.09383464) > 0.001) return@awaitUi false
            val confirm = labelledButton("이 목적지와 남은 예상거리 확인") ?: return@awaitUi false
            sdkMeters = distance.value
            val acted = confirm.performClick()
            if (acted) record("sdk_quote", "route" to route.value, "generation" to generation,
                "sequence" to distance.stamp.sequence, "receivedUtc" to distance.stamp.receivedAtMillis,
                "ageMs" to age, "meters" to sdkMeters, "mode" to guidance.value.mode,
                "latitude" to destination.value.latitude, "longitude" to destination.value.longitude)
            acted
        }

        val shown = awaitUiValue("Main에 실제 표시한 구매 목록") {
            val state = session.delivery.state ?: return@awaitUiValue null
            val offers = (state.screen as? DeliveryScreen.Offers)?.proposal ?: return@awaitUiValue null
            val purchases = offers.proposed.offers.filterIsInstance<DeliveryOffer.Purchase>()
            if (purchases.isEmpty() || purchases.any { button("delivery:offer:${it.id.value}") == null }) {
                return@awaitUiValue null
            }
            assertEquals(identity, state.identity)
            assertEquals(EstimatedRouteDistance(sdkMeters, InputSource.VEHICLE), offers.proposed.estimated)
            assertEquals(offers.proposed.destination, state.navigation?.destination)
            assertEquals(Observation.Missing(MissingReason.NO_DATA), state.navigation?.arrival)
            val visible = labels()
            assertTrue(context.getString(R.string.delivery_estimated, sdkMeters) in visible)
            for (offer in purchases) {
                assertTrue(offers.vehicleNames.getValue(offer.vehicleId) in visible)
                assertTrue(GarageStyle(activity).rarity(offer.rarity) in visible)
                assertTrue(context.getString(R.string.delivery_money,
                    context.getString(R.string.delivery_purchase_payment), offer.price.units) in visible)
            }
            offers
        }
        val proposed = shown.proposed
        val purchases = proposed.offers.filterIsInstance<DeliveryOffer.Purchase>()
        val expectedKeys = purchases.map { CollectionKey(it.vehicleId, it.rarity) }.toSet()
        await("실제 표시 구매 후보만 발견") {
            read { game -> ok(game.store.discoveredVehicles()) == expectedKeys }
        }
        read { game ->
            assertEquals(listOf(proposed.list), ok(game.store.delivery.lists(proposed.list.createdOn)))
            assertEquals(InputSource.VEHICLE, proposed.list.source)
        }
        assertEquals(before, ledger())
        record("discovery", "profile" to identity.profileId.value, "contentVersion" to proposed.list.contentVersion.value,
            "ruleVersion" to proposed.list.ruleVersion.value, "source" to proposed.list.source.name,
            "day" to proposed.list.createdOn, "band" to proposed.list.band, "destination" to proposed.destination.value,
            "offerIds" to purchases.map { it.id.value }, "keys" to expectedKeys)
        val offer = purchases.first()
        awaitUi("구매 상세") { button("delivery:offer:${offer.id.value}")?.performClick() == true }
        awaitUi("구매 상세의 차종·희귀도·가격") {
            val detail = labels()
            val payment = context.getString(R.string.delivery_money,
                context.getString(R.string.delivery_purchase_payment), offer.price.units)
            if (!detail.containsAll(listOf(shown.vehicleNames.getValue(offer.vehicleId),
                    GarageStyle(activity).rarity(offer.rarity), payment))) return@awaitUi false
            val confirm = activity.window.decorView.findViewWithTag<Button>("delivery:confirm")
                ?.takeIf { it.isShown } ?: return@awaitUi false
            if (affordable) {
                if (!confirm.isEnabled) return@awaitUi false
                val current = session.deliveryNavigation.snapshot
                val distance = current?.remainingEstimatedMeters as? NavigationField.Value
                record("purchase_click", "offer" to offer.id.value, "navigation" to current.toString(),
                    "distanceAgeMs" to (distance?.stamp?.let { SystemClock.elapsedRealtime() - it.receivedElapsedMillis } ?: -1L))
                confirm.performClick()
            } else {
                assertFalse(confirm.isEnabled)
                assertTrue("게임머니가 부족해요." in detail)
                true
            }
        }
        if (!affordable) {
            assertEquals(before, ledger())
            record("insufficient_funds", "price" to offer.price.units, "balance" to 0)
            return
        }
        awaitUi("구매 원결과 표시") {
            "의뢰 선택이 확정됐어요." in labels() && button("delivery:acknowledge") != null
        }
        val request = read { game ->
            val route = checkNotNull(ok(game.store.delivery.inProgress()))
            val active = route.active
            assertEquals(proposed, active.proposed)
            assertEquals(offer, active.offer)
            assertEquals(offer.price, ok(game.store.delivery.terms())?.paid)
            val payment = ok(game.store.listUnviewedSettlements()).single { it.transaction.kind == TransactionKind.DELIVERY }
            assertEquals(InputSource.VEHICLE, payment.transaction.source)
            assertEquals(active.requestId, payment.transaction.requestId)
            assertEquals(MoneyChange(MoneyDirection.DEBIT, offer.price), payment.transaction.money)
            assertEquals(GameMoney(11_000 - offer.price.units), ok(game.store.balance()))
            assertNull(payment.changedVehicle)
            assertNull(ok(game.store.delivery.arrivalResult(active.requestId)))
            assertEquals(1, ok(game.store.listOwnedVehicles()).size)
            record("purchase", "request" to active.requestId.value, "settlement" to payment.settlementId.value,
                "offer" to offer.id.value, "price" to offer.price.units, "route" to route.route.value)
            active.requestId
        }
        awaitUi("저장된 구매 결과 확인") { button("delivery:acknowledge")?.performClick() == true }
        await("확인 뒤 슬롯 해제") { read { ok(it.store.hostCommands.load(identity.profileId)) == null } }
        read { game ->
            assertEquals(request, ok(game.store.delivery.inProgress())?.active?.requestId)
            assertEquals(1, ok(game.store.listUnviewedSettlements()).count { it.transaction.kind == TransactionKind.DELIVERY })
            assertEquals(1, ok(game.store.listOwnedVehicles()).size)
        }
    }

    // 후보/발견 및 차량 관측은 정당하게 바뀌므로 거래와 의뢰 표만 비교한다.
    private fun ledger() = read { game ->
        ok(game.store.read { db ->
            listOf("account", "vehicles", "settlements", "delivery_requests", "delivery_active",
                "delivery_routes", "delivery_route_history", "delivery_outcomes", "host_command_checkpoint").associateWith { table ->
                db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map(cursor::getString)) }
                }
            }
        })
    }

    private fun record(kind: String, vararg values: Pair<String, Any>) {
        val json = JSONObject().put("kind", kind).put("elapsedMs", SystemClock.elapsedRealtime())
        values.forEach { (key, value) -> json.put(key, value.toString()) }
        evidence.appendText(json.toString() + "\n")
    }

    private fun button(tag: Any) = activity.window.decorView.findViewWithTag<Button>(tag)
        ?.takeIf { it.isEnabled && it.isShown }

    private fun labelledButton(label: String) = views(activity.window.decorView).filterIsInstance<Button>()
        .firstOrNull { it.isEnabled && it.isShown && it.text.toString() == label }

    private fun labels() = views(activity.window.decorView).filterIsInstance<TextView>()
        .filter { it.isShown }.map { it.text.toString() }.toList()

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    private fun awaitUi(label: String, condition: () -> Boolean) =
        awaitUiValue(label) { true.takeIf { condition() } }

    private fun <T : Any> awaitUiValue(label: String, condition: () -> T?): T {
        var value: Result<T?>? = null
        await(label) {
            instrumentation.runOnMainSync { value = runCatching(condition) }
            requireNotNull(value).getOrThrow() != null
        }
        return requireNotNull(requireNotNull(value).getOrThrow())
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        var displayed = emptyList<String>()
        instrumentation.runOnMainSync {
            displayed = labels()
            record("wait_failed", "condition" to label, "screen" to displayed,
                "navigation" to session.deliveryNavigation.snapshot.toString())
        }
        fail("$label · 화면: $displayed")
    }

    private fun <T> read(action: (GameSession) -> T): T = ok(games.open()).use(action)

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
}
