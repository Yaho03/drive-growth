package io.drivegrowth.app.host.delivery

import android.content.pm.PackageManager
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
import io.drivegrowth.app.data.DeliveryCheckpointOperation
import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.HostCheckpointPhase
import io.drivegrowth.app.data.HostCommandCheckpoint
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.HostSession
import io.drivegrowth.app.ui.delivery.DeliveryScreen
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.vehicle.ForegroundInteraction
import io.drivegrowth.app.vehicle.TRIAL_INTERACTION_POLICY
import io.drivegrowth.app.vehicle.navigation.NavigationConnection
import io.drivegrowth.app.vehicle.navigation.NavigationField
import io.drivegrowth.app.vehicle.navigation.NavigationSource
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.driving.VehicleInteractionGate
import io.drivegrowth.core.garage.CollectionKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
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

    fun testNativePurchaseForColdRestore() {
        verifyPurchase("buy", affordable = true)
        record("cold_fixture", "pid" to Process.myPid(), "ledgerSha256" to purchaseLedgerSha256())
    }

    fun testColdMainRestoresNativePurchaseWithoutInferringArrival() {
        val fixture = coldFixture()
        openOriginalActive(fixture)
        val sdk = awaitSdkQuote { it.route == fixture.sdkRoute }
        awaitUi("재연결 SDK 안내와 별개로 원의뢰가 도착 확인 대기를 유지함") {
            val state = session.delivery.state ?: return@awaitUi false
            val active = (state.screen as? DeliveryScreen.Active)?.delivery ?: return@awaitUi false
            val navigation = state.navigation ?: return@awaitUi false
            if (active.active.requestId.value != fixture.request) return@awaitUi false
            assertTrue(navigation.arrival is Observation.Missing)
            val reconnected = ok(session.deliveryNavigation.port.read(state.identity))
            assertEquals(sdk.generation, checkNotNull(reconnected.metadata?.connectionGeneration))
            assertNull("현재 SDK 안내를 저장된 선택으로 자동 연결하지 않음", reconnected.destination)
            assertTrue(reconnected.estimated is Observation.Missing)
            assertTrue(reconnected.arrival is Observation.Missing)
            assertNull(active.destinationName)
            context.getString(R.string.delivery_destination_missing) in labels() &&
                "목적지 도착 증거를 확인하고 있어요." in labels()
        }
        assertOriginalPurchase(fixture)
        record("cold_restored", "pid" to Process.myPid(), "sdkRoute" to sdk.route,
            "generation" to sdk.generation, "sdkMeters" to sdk.meters, "request" to fixture.request,
            "appRoute" to fixture.appRoute, "ledgerSha256" to fixture.ledgerSha256, "arrival" to "Missing")
    }

    fun testColdMainRejectsChangedSdkDestinationWithoutObservedTravel() {
        val fixture = coldFixture()
        openOriginalActive(fixture)
        awaitUi("원래 의뢰의 이동 관측은 누락 상태") {
            val navigation = session.delivery.state?.navigation ?: return@awaitUi false
            navigation.observed is Observation.Missing && navigation.arrival is Observation.Missing
        }
        awaitUi("현재 의뢰의 목적지 변경 요청") { button("delivery:destination")?.performClick() == true }
        awaitUi("새 목적지의 SDK 확인 대기") {
            val screen = session.delivery.state?.screen
            (screen is DeliveryScreen.Destination || screen is DeliveryScreen.AwaitingResult) &&
                labelledButton("내비 목적지 다시 조회") != null
        }
        val sdk = awaitSdkQuote {
            it.route != fixture.sdkRoute &&
                (abs(it.latitude - fixture.latitude) > 0.001 || abs(it.longitude - fixture.longitude) > 0.001)
        }
        awaitUi("다른 SDK 목적지의 유효한 견적을 Main에서 확인") {
            val live = readSdkQuote() ?: return@awaitUi false
            if (live.route != sdk.route || live.latitude != sdk.latitude || live.longitude != sdk.longitude)
                return@awaitUi false
            val confirm = labelledButton("이 목적지와 남은 예상거리 확인") ?: return@awaitUi false
            assertTrue(confirm.performClick())
            val identity = checkNotNull(session.delivery.state).identity
            val selected = ok(session.deliveryNavigation.port.read(identity))
            assertNotNull("SDK 목적지 확인 성공", selected.destination)
            assertEquals(EstimatedRouteDistance(live.meters, InputSource.VEHICLE),
                (selected.estimated as? Observation.Available)?.value)
            assertTrue(selected.arrival is Observation.Missing)
            true
        }
        awaitUi("이동 근거가 없는 변경 거절") {
            "탁송에 필요한 정보를 확인할 수 없어요. 원래 요청은 유지돼요." in labels() &&
                button("delivery:change") == null
        }
        assertOriginalPurchase(fixture)
        record("change_rejected", "pid" to Process.myPid(), "sdkRoute" to sdk.route,
            "generation" to sdk.generation, "sdkMeters" to sdk.meters, "request" to fixture.request,
            "appRoute" to fixture.appRoute, "ledgerSha256" to fixture.ledgerSha256, "arrival" to "Missing")
    }

    /** 별도 실행자가 Pleos의 차량 제어로 정지 D와 P를 전환한다. SDK·gate를 주입하지 않는다. */
    fun testActualDriveParkHidesDeliveryReviewAndPreservesSavedRequest() {
        val fixture = coldFixture()
        openOriginalActive(fixture)
        awaitSdkQuote { it.route == fixture.sdkRoute }
        awaitUi("P에서 포기 검토 열기") { button("delivery:review-abandon")?.performClick() == true }
        awaitUi("P의 원의뢰와 검토 버튼") {
            val review = session.delivery.state?.screen as? DeliveryScreen.AbandonReview ?: return@awaitUi false
            review.delivery.active.requestId.value == fixture.request && button("delivery:abandon") != null
        }
        val prior = ledger(includeObservationBinding = false)
        requestVehicleGate("REQUEST_DRIVE_STATIONARY review", Gear.DRIVE) { deliveryRestricted() }
        assertEquals(prior, ledger(includeObservationBinding = false))

        requestVehicleGate("REQUEST_PARK review", Gear.PARK) {
            val current = session.delivery.state?.screen as? DeliveryScreen.Active ?: return@requestVehicleGate false
            current.delivery.active.requestId.value == fixture.request && button("delivery:review-abandon") != null
        }
        assertOriginalPurchase(fixture)

        requestVehicleGate("REQUEST_DRIVE_STATIONARY pending", Gear.DRIVE) { deliveryRestricted() }
        // 실제 D 제한을 확인한 뒤 미실행 원명령의 저장 경계만 fixture로 준비한다.
        val request = RequestId(fixture.request)
        val pending = read { game ->
            val rule = ok(ok(AssetTrialDefinitionProvider(context.assets).load()).deliveryChangeRules())
            val command = HostCheckpointCommand.Delivery(ok(game.content).catalog.contentVersion,
                game.identity.source, DeliveryCheckpointOperation.Abandon(rule, request))
            ok(game.store.hostCommands.save(game.identity.profileId, command))
            HostCommandCheckpoint(game.identity.profileId, command, HostCheckpointPhase.SAVED)
        }
        instrumentation.runOnMainSync { assertTrue(deliveryRestricted()) }
        assertEquals(pending, read { ok(it.store.hostCommands.load(pending.profileId)) })
        assertEquals(prior - "host_command_checkpoint", ledger(includeObservationBinding = false) - "host_command_checkpoint")

        requestVehicleGate("REQUEST_PARK pending", Gear.PARK) {
            val saved = labelledButton("보관한 탁송 요청 확인")
            if (saved != null) saved.performClick()
            button("delivery:acknowledge") != null && "의뢰 포기가 확정됐어요." in labels()
        }
        val after = read { game ->
            assertEquals(pending.copy(phase = HostCheckpointPhase.STARTED), ok(game.store.hostCommands.load(pending.profileId)))
            val rows = ok(game.store.listUnviewedSettlements()).filter { it.transaction.kind == TransactionKind.DELIVERY }
            val refund = rows.single { it.transaction.requestId == RequestId("delivery/${request.value}/settle") }
            assertEquals(2, rows.size)
            assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(fixture.price - fixture.price / 10)), refund.transaction.money)
            assertNull(refund.changedVehicle)
            assertEquals(1, ok(game.store.listOwnedVehicles()).size)
            assertNull(ok(game.store.delivery.inProgress()))
            rows
        }
        awaitUi("원결과 확인") { button("delivery:acknowledge")?.performClick() == true }
        await("확인 뒤 동일 원명령 슬롯 해제") {
            read { ok(it.store.hostCommands.load(pending.profileId)) == null }
        }
        assertEquals(after, read { game -> ok(game.store.listUnviewedSettlements()).filter {
            it.transaction.kind == TransactionKind.DELIVERY
        } })
        record("dp_review_and_saved_request", "pid" to Process.myPid(), "request" to request.value)
    }

    private fun deliveryRestricted(): Boolean {
        val root = activity.window.decorView
        return root.findViewWithTag<Spinner>("host:destination") == null &&
            views(root).none { it.isShown && it is io.drivegrowth.app.ui.delivery.DeliveryView } &&
            listOf("delivery:abandon", "delivery:change", "delivery:acknowledge").all {
                root.findViewWithTag<View>(it)?.isShown != true
            } && "보관한 탁송 요청 확인" !in labels() &&
            "주차 후 조작이 허용되면 이용할 수 있습니다." in labels()
    }

    // Main에 전달된 실제 관측을 읽기만 한다. 새 SDK 연결이나 gate 입력을 만들지 않는다.
    private val vehicleGate: VehicleInteractionGate by lazy {
        val interaction = MainActivity::class.java.getDeclaredField("interaction").apply { isAccessible = true }
            .get(activity) as ForegroundInteraction
        ForegroundInteraction::class.java.getDeclaredField("gate").apply { isAccessible = true }
            .get(interaction) as VehicleInteractionGate
    }
    private val latestVehicleObservation by lazy {
        VehicleInteractionGate::class.java.getDeclaredField("latest").apply { isAccessible = true }
    }

    private fun requestVehicleGate(request: String, gear: Gear, ready: () -> Boolean) {
        val pid = Process.myPid()
        val restricted = gear == Gear.DRIVE
        val expectedGate = if (restricted) VehicleInteraction.RESTRICTED else VehicleInteraction.ALLOWED
        var markerElapsed = -1L
        instrumentation.runOnMainSync {
            Log.i("DeliveryGate53", "$request pid=$pid")
            markerElapsed = SystemClock.elapsedRealtime()
        }
        await(request, timeoutMs = 60_000) {
            var matched = false
            instrumentation.runOnMainSync {
                val sample = latestVehicleObservation.get(vehicleGate) as? VehicleObservation ?: return@runOnMainSync
                val now = SystemClock.elapsedRealtime()
                val speed = (sample.speedMps as? Observation.Available)?.value ?: return@runOnMainSync
                if (sample.source != InputSource.VEHICLE || sample.receivedElapsedMs <= markerElapsed ||
                    now - sample.receivedElapsedMs !in 0L..TRIAL_INTERACTION_POLICY.maxSignalAgeMs ||
                    abs(speed) != 0.0 || sample.gear != Observation.Available(gear) ||
                    sample.uxRestricted != Observation.Available(restricted) ||
                    vehicleGate.state(now) != Observation.Available(expectedGate) || !ready()) return@runOnMainSync
                assertEquals(pid, Process.myPid())
                record("dp_sdk_sample", "marker" to request, "pid" to pid, "markerElapsedMs" to markerElapsed,
                    "sampleElapsedMs" to sample.receivedElapsedMs, "source" to sample.source,
                    "gear" to gear, "speedMps" to speed, "uxRestricted" to restricted, "gate" to expectedGate)
                matched = true
            }
            matched
        }
    }

    private data class ColdFixture(
        val profile: String, val request: String, val settlement: String, val appRoute: String, val price: Long,
        val sdkRoute: String, val latitude: Double, val longitude: Double, val ledgerSha256: String,
    )

    private fun coldFixture(): ColdFixture {
        assertEquals("io.drivegrowth.verify.issue37buy", context.packageName)
        assertTrue(context.getDatabasePath("game-ledger-vehicle.db").isFile)
        val rows = evidence.readLines().map { JSONObject(it) }
        fun event(kind: String) = rows.single { it.getString("kind") == kind }
        val baseline = event("cold_fixture")
        assertFalse("구매를 실행한 프로세스와 다른 PID여야 합니다.", baseline.getString("pid").toInt() == Process.myPid())
        val purchase = event("purchase")
        val sdk = event("sdk_quote")
        return ColdFixture(event("discovery").getString("profile"), purchase.getString("request"),
            purchase.getString("settlement"), purchase.getString("route"), purchase.getString("price").toLong(),
            sdk.getString("route"), sdk.getString("latitude").toDouble(), sdk.getString("longitude").toDouble(),
            baseline.getString("ledgerSha256"))
    }

    private fun openOriginalActive(fixture: ColdFixture) {
        assertOriginalPurchase(fixture)
        activity
        awaitUi("새 PID의 Main에 같은 활성 구매 표시") {
            assertNull(session.simulation)
            val active = (session.delivery.state?.screen as? DeliveryScreen.Active)?.delivery
            if (active?.active?.requestId?.value == fixture.request &&
                context.getString(R.string.delivery_active) in labels()) return@awaitUi true
            val spinner = activity.window.decorView.findViewWithTag<Spinner>("host:destination")
                ?.takeIf { it.isEnabled && it.isShown && it.isLaidOut && !it.isLayoutRequested }
                ?: return@awaitUi false
            val index = (0 until spinner.count).single { spinner.getItemAtPosition(it).toString() == "탁송" }
            if (spinner.selectedItemPosition != index) spinner.setSelection(index)
            false
        }
    }

    private fun assertOriginalPurchase(fixture: ColdFixture) {
        assertEquals("원의뢰·결제·활성 경로·잔액과 비교 원장 행 보존",
            fixture.ledgerSha256, purchaseLedgerSha256())
        read { game ->
            assertEquals(InputSource.VEHICLE, game.identity.source)
            assertEquals(fixture.profile, game.identity.profileId.value)
            val progress = checkNotNull(ok(game.store.delivery.inProgress()))
            val terms = checkNotNull(ok(game.store.delivery.terms()))
            assertEquals(fixture.request, progress.active.requestId.value)
            assertEquals(fixture.appRoute, progress.route.value)
            assertEquals(progress.active, terms.active)
            assertEquals(GameMoney(fixture.price), terms.paid)
            assertFalse("변경 기회 보존", terms.changeUsed)
            val receipt = ok(game.store.listUnviewedSettlements()).single { it.transaction.kind == TransactionKind.DELIVERY }
            assertEquals(fixture.settlement, receipt.settlementId.value)
            assertEquals(progress.active.requestId, receipt.transaction.requestId)
            assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(fixture.price)), receipt.transaction.money)
            assertEquals(GameMoney(11_000 - fixture.price), ok(game.store.balance()))
            assertEquals(1, ok(game.store.listOwnedVehicles()).size)
            assertNull(ok(game.store.delivery.arrivalResult(progress.active.requestId)))
            assertNull(ok(game.store.hostCommands.load(game.identity.profileId)))
        }
    }

    private data class SdkQuote(
        val route: String, val generation: Long, val meters: Long, val latitude: Double, val longitude: Double,
    )

    private fun awaitSdkQuote(matches: (SdkQuote) -> Boolean): SdkQuote {
        awaitUi("새 프로세스의 SDK 연결 후 내비 조회") {
            val raw = session.deliveryNavigation.snapshot ?: return@awaitUi false
            if (raw.connection != NavigationConnection.CONNECTED_OBSERVED || raw.failure != null) return@awaitUi false
            labelledButton("내비 목적지 다시 조회")?.performClick() == true
        }
        return awaitUiValue("현재 SDK 연결 세대의 신선한 안내 견적") { readSdkQuote()?.takeIf(matches) }
    }

    private fun readSdkQuote(): SdkQuote? {
        val raw = session.deliveryNavigation.snapshot ?: return null
        if (raw.connection != NavigationConnection.CONNECTED_OBSERVED || raw.failure != null) return null
        val route = raw.routeId as? NavigationField.Value ?: return null
        val destination = raw.destination as? NavigationField.Value ?: return null
        val distance = raw.remainingEstimatedMeters as? NavigationField.Value ?: return null
        val guidance = raw.guidance as? NavigationField.Value ?: return null
        val generation = raw.stamp.connectionGeneration
        val now = SystemClock.elapsedRealtime()
        if (route.value.isBlank() || guidance.value.mode != "ROUTE_GUIDING" || distance.value <= 0 ||
            distance.stamp != destination.stamp || listOf(route.stamp, distance.stamp, guidance.stamp).any {
                it.connectionGeneration != generation || it.source != NavigationSource.PLEOS_NAVI_SDK
            } || now - distance.stamp.receivedElapsedMillis !in 0L..5_000L ||
            now - guidance.stamp.receivedElapsedMillis !in 0L..5_000L) return null
        return SdkQuote(route.value, generation, distance.value, destination.value.latitude, destination.value.longitude)
    }

    private fun purchaseLedgerSha256(): String {
        val tables = JSONArray()
        for ((table, rows) in ledger(includeObservationBinding = false)) {
            tables.put(JSONArray().put(table).put(JSONArray(rows.map { JSONArray(it) })))
        }
        return MessageDigest.getInstance("SHA-256").digest(tables.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

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
    private fun ledger(includeObservationBinding: Boolean = true) = read { game ->
        ok(game.store.read { db ->
            listOf("account", "vehicles", "settlements", "delivery_requests", "delivery_active",
                "delivery_routes", "delivery_route_history", "delivery_outcomes", "host_command_checkpoint").associateWith { table ->
                // 관측 수신 세션의 참조 열은 새 프로세스에서 바뀔 수 있다. 원의뢰 경로와 시작 시각은 비교한다.
                val columns = if (!includeObservationBinding && table == "delivery_routes") "request_id, route, started_at" else "*"
                db.rawQuery("SELECT $columns FROM $table ORDER BY rowid", null).use { cursor ->
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

    private fun await(label: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
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
