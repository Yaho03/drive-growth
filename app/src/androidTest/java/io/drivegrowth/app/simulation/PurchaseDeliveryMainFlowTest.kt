package io.drivegrowth.app.simulation

import io.drivegrowth.app.mainPage
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.showSimulationControls
import io.drivegrowth.app.host.HostPage
import android.os.Process
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.MainActivity
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
import io.drivegrowth.app.host.HostSession
import io.drivegrowth.app.ui.delivery.DeliveryScreen
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DeliveryChangeOffer
import io.drivegrowth.core.delivery.DeliveryInProgress
import io.drivegrowth.core.delivery.DeliveryList
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.delivery.DeliveryOfferId
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
        val request = purchase.route.active.requestId
        val before = stored(request)
        val fee = purchase.offer.price.units / 10
        val refund = purchase.offer.price.units - fee
        val feeLabel = context.getString(R.string.delivery_money, context.getString(R.string.delivery_abandon_fee), fee)
        val refundLabel = context.getString(R.string.delivery_money, context.getString(R.string.delivery_refund_planned), refund)

        repeat(2) { visit ->
            click("delivery:review-abandon")
            awaitUi("수수료·환급 미리보기") {
                val labels = views(activity.window.decorView).filterIsInstance<TextView>()
                    .filter { it.isShown }.map { it.text.toString() }.toList()
                enabledButton("delivery:abandon") != null && labels.containsAll(listOf(feeLabel, refundLabel))
            }
            assertEquals(before, stored(request))
            assertEquals(purchase.route, read { ok(it.store.delivery.inProgress()) })
            if (visit == 0) {
                instrumentation.runOnMainSync { activity.onBackPressed() }
                awaitUi("Back 뒤 원의뢰") {
                    enabledButton("delivery:abandon") == null && enabledButton("delivery:review-abandon") != null
                }
                assertEquals(before, stored(request))
            }
        }
        awaitUi("포기 확정 버튼 연속 입력") {
            val confirm = enabledButton("delivery:abandon") ?: return@awaitUi false
            assertTrue(confirm.performClick())
            confirm.performClick()
            true
        }
        awaitReceipt("의뢰 포기가 확정됐어요.")

        val after = stored(request)
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
        instrumentation.runOnMainSync { activity.window.decorView.showSimulationControls() }
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

    fun testChangeComparisonRejectsMissingTravelThenCommitsOnce() {
        requireFresh("io.drivegrowth.verify.issue53change")
        val purchase = buyFromMain(changeFixture = true)
        val request = purchase.route.active.requestId
        val original = stored(request)

        chooseChangeDestination()
        awaitUi("원의뢰 이동 관측이 없으면 변경 거절") {
            enabledButton("delivery:refresh") != null &&
                visibleLabels().contains("탁송에 필요한 정보를 확인할 수 없어요. 원래 요청은 유지돼요.")
        }
        assertEquals(original, stored(request))
        assertEquals(purchase.route, read { ok(it.store.delivery.inProgress()) })

        addObservedDistance(purchase, original.profile)
        click("delivery:refresh")
        val review = openChangeReview(purchase)
        instrumentation.runOnMainSync { activity.onBackPressed() }
        awaitUi("변경 검토에서 Back 뒤 원의뢰") {
            enabledButton("delivery:change") == null && enabledButton("delivery:review-abandon") != null
        }
        assertEquals(original, stored(request))
        assertEquals(purchase.route, read { ok(it.store.delivery.inProgress()) })

        chooseChangeDestination()
        val next = openChangeReview(purchase)
        assertEquals(review.before.active, next.before.active)
        awaitUi("변경 확정 버튼 연속 입력") {
            val confirm = enabledButton("delivery:change") ?: return@awaitUi false
            assertTrue(confirm.performClick())
            confirm.performClick()
            true
        }
        assertChangeResult(original, request, next.after.proposed.offers.single { it.id == next.offerId } as DeliveryOffer.Purchase,
            purchase.offer.price)
    }

    fun testSaveReviewedChangeForColdRecovery() {
        requireFresh("io.drivegrowth.verify.issue53recover")
        val purchase = buyFromMain(changeFixture = true)
        val request = purchase.route.active.requestId
        val original = stored(request)
        addObservedDistance(purchase, original.profile)
        chooseChangeDestination()
        val review = openChangeReview(purchase)
        assertEquals(original, stored(request))

        // 종료 직전 아직 실행되지 않은 원명령 경계를 typed SAVED fixture로 준비한다.
        // 이 단계에서는 제품 UI가 명령을 저장했다거나 변경을 확정했다고 가정하지 않는다.
        val rule = ok(ok(AssetTrialDefinitionProvider(context.assets).load()).deliveryChangeRules())
        val proposed = review.after.proposed
        val offer = DeliveryChangeOffer(request, OBSERVED_METERS, OBSERVED_METERS + proposed.estimated.meters, proposed)
        val operation = DeliveryCheckpointOperation.Change(rule, offer, review.offerId, RouteId("issue53-saved-route"), null)
        val command = HostCheckpointCommand.Delivery(proposed.list.contentVersion, InputSource.SIMULATION, operation)
        read { game -> ok(game.store.hostCommands.save(game.identity.profileId, command)) }
        val pending = stored(request)
        assertEquals(original, pending.copy(checkpoint = null))
        assertEquals(HostCommandCheckpoint(original.profile, command, HostCheckpointPhase.SAVED), pending.checkpoint)
        context.filesDir.resolve("issue53-change-saved.json").writeText(JSONObject()
            .put("pid", Process.myPid()).put("request", request.value)
            .put("paid", purchase.offer.price.units).put("original", pending.toString()).toString())
    }

    fun testColdMainExecutesOriginalSavedChange() {
        assertEquals("io.drivegrowth.verify.issue53recover", context.packageName)
        val evidence = JSONObject(context.filesDir.resolve("issue53-change-saved.json").readText())
        assertTrue(Process.myPid() != evidence.getInt("pid"))
        val request = RequestId(evidence.getString("request"))
        val before = stored(request)
        assertEquals(evidence.getString("original"), before.toString())
        val pending = requireNotNull(before.checkpoint)
        val change = (pending.command as HostCheckpointCommand.Delivery).operation as DeliveryCheckpointOperation.Change
        assertEquals(request, change.offer.requestId)
        assertEquals(HostCheckpointPhase.SAVED, pending.phase)
        activity
        awaitUi("새 PID의 원명령 복구 진입") {
            views(activity.window.decorView).filterIsInstance<Button>().firstOrNull {
                it.isShown && it.isEnabled && it.text.toString() == "보관한 탁송 요청 확인"
            }?.performClick() == true
        }
        val selected = change.offer.proposed.offers.single { it.id == change.offerId } as DeliveryOffer.Purchase
        assertChangeResult(before, request, selected, GameMoney(evidence.getLong("paid")), pending)
    }

    private fun addObservedDistance(purchase: Purchase, profile: ProfileId) {
        assertTrue(prefs.edit().putLong("routeMoved", OBSERVED_METERS)
            .putString("runRoute", purchase.route.route.value)
            .putString("runRequest", purchase.route.active.requestId.value)
            .putString("runProfile", profile.value).commit())
        assertFalse(prefs.contains("arrivalRoute"))
    }

    private val session: HostSession by lazy {
        MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
            .get(activity) as HostSession
    }

    private fun chooseChangeDestination() {
        click("delivery:destination")
        awaitUi("변경 목적지 준비 뒤 모의 견적 확인") {
            val screen = session.delivery.state?.screen
            (screen is DeliveryScreen.Destination || screen is DeliveryScreen.AwaitingResult) &&
                enabledButton("simulation:destination")?.performClick() == true
        }
    }

    private fun openChangeReview(purchase: Purchase): DeliveryScreen.ChangeReview {
        var selected: DeliveryOffer.Purchase? = null
        awaitUi("가격과 관측 거리를 공급한 변경 후보") {
            val current = session.delivery.state ?: return@awaitUi false
            if (current.displayedProposal != null) return@awaitUi false
            val offers = (current.screen as? DeliveryScreen.Offers)?.proposal ?: return@awaitUi false
            assertEquals("관측 30km + 새 예상 36km는 50km 초과 구간", OVER_50_KM_BAND, offers.proposed.list.band)
            val offer = offers.proposed.offers.filterIsInstance<DeliveryOffer.Purchase>()
                .filter { offers.availability[it.id]?.allowed == true && it.price.units > purchase.offer.price.units }
                .maxByOrNull { it.price.units } ?: return@awaitUi false
            val button = enabledButton("delivery:offer:${offer.id.value}") ?: return@awaitUi false
            selected = offer
            button.performClick()
        }
        click("delivery:confirm")
        val offer = requireNotNull(selected)
        val delta = offer.price.units - purchase.offer.price.units
        assertTrue("고정 구매가보다 비싼 변경 후보", delta > 0)
        val expectedMoney = context.getString(R.string.delivery_money, context.getString(R.string.delivery_additional_payment), delta)
        val names = read { game -> ok(game.content).catalog.vehicles.associate { it.vehicleId to it.displayName } }
        val vehicleLabels = names.values.toSet()
        val style = GarageStyle(context)
        val rarityLabels = Rarity.entries.map(style::rarity).toSet()
        val beforeHeading = context.getString(R.string.delivery_before)
        val afterHeading = context.getString(R.string.delivery_after)
        val beforeDistance = context.getString(R.string.delivery_estimated, purchase.route.active.proposed.estimated.meters)
        val afterDistance = context.getString(R.string.delivery_estimated, 36_000L)
        val observed = context.getString(R.string.delivery_observed, "실제 관측 이동 거리 $OBSERVED_METERS m")
        var review: DeliveryScreen.ChangeReview? = null
        awaitUi("변경 전후 거리·차량·희귀도·차액 확인") {
            val frame = session.delivery.state?.screen as? DeliveryScreen.ChangeReview ?: return@awaitUi false
            val headings = views(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.toList()
            val before = headings.singleOrNull { it.text.toString() == beforeHeading }?.parent as? ViewGroup
                ?: return@awaitUi false
            val after = headings.singleOrNull { it.text.toString() == afterHeading }?.parent as? ViewGroup
                ?: return@awaitUi false
            if (before === after || before.parent == null || before.parent !== after.parent) return@awaitUi false
            val beforeLabels = visibleLabels(before)
            val afterLabels = visibleLabels(after)
            if (enabledButton("delivery:change") == null || beforeLabels.count { it == beforeDistance } != 1 ||
                afterLabels.count { it == afterDistance } != 1 || observed !in beforeLabels || observed in afterLabels ||
                beforeLabels.filter { it in vehicleLabels } != listOf(names.getValue(purchase.offer.vehicleId)) ||
                afterLabels.filter { it in vehicleLabels } != listOf(names.getValue(offer.vehicleId)) ||
                beforeLabels.filter { it in rarityLabels } != listOf(style.rarity(purchase.offer.rarity)) ||
                afterLabels.filter { it in rarityLabels } != listOf(style.rarity(offer.rarity))) return@awaitUi false
            val labels = visibleLabels()
            if (expectedMoney !in labels || context.getString(R.string.delivery_difference_note) !in labels)
                return@awaitUi false
            review = frame
            true
        }
        return requireNotNull(review)
    }

    private fun assertChangeResult(before: Original, request: RequestId, offer: DeliveryOffer.Purchase,
        paid: GameMoney, originalCheckpoint: HostCommandCheckpoint? = null) {
        awaitReceipt("목적지 변경이 확정됐어요.")
        val after = stored(request)
        val checkpoint = requireNotNull(after.checkpoint)
        assertEquals(HostCheckpointPhase.STARTED, checkpoint.phase)
        if (originalCheckpoint != null) assertEquals(originalCheckpoint.command, checkpoint.command)
        val operation = (checkpoint.command as HostCheckpointCommand.Delivery).operation as DeliveryCheckpointOperation.Change
        assertEquals(request, operation.offer.requestId)
        assertEquals(OBSERVED_METERS, operation.offer.traveledMeters)
        assertEquals(66_000L, operation.offer.combinedMeters)
        assertEquals("50km 초과 구간이 원명령에도 저장됨", OVER_50_KM_BAND, operation.offer.proposed.list.band)
        assertEquals(before.vehicles, after.vehicles)
        assertNull(after.arrival)
        val delta = offer.price.units - paid.units
        assertTrue("변경 차액은 양수", delta > 0)
        assertEquals(GameMoney(before.balance.units - delta), after.balance)
        val changeId = RequestId("delivery/${request.value}/change")
        assertTrue(before.settlements.none { it.transaction.requestId == changeId })
        val change = after.settlements.single { it.transaction.requestId == changeId }
        assertEquals(TransactionKind.DELIVERY, change.transaction.kind)
        assertEquals(InputSource.SIMULATION, change.transaction.source)
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(delta)), change.transaction.money)
        assertNull(change.changedVehicle)
        assertEquals(before.settlements.toSet() + change, after.settlements.toSet())
        read { game ->
            val count = ok(game.store.read { db ->
                db.rawQuery("SELECT count(*) FROM settlements WHERE request_id = ?", arrayOf(changeId.value)).use {
                    check(it.moveToFirst())
                    it.getInt(0)
                }
            })
            assertEquals("원 변경 요청의 전체 정산 행", 1, count)
            val progress = requireNotNull(ok(game.store.delivery.inProgress()))
            assertEquals(request, progress.active.requestId)
            assertEquals(operation.route, progress.route)
            assertEquals(offer, progress.active.offer)
            assertTrue(requireNotNull(ok(game.store.delivery.terms())).changeUsed)
        }
        acknowledge(after.profile)
        assertEquals(after.copy(checkpoint = null), stored(request))
    }

    private fun visibleLabels(root: View = activity.window.decorView) = views(root).filterIsInstance<TextView>()
        .filter { it.isShown }.map { it.text.toString() }.toList()

    private companion object {
        const val OBSERVED_METERS = 30_000L
        const val OVER_50_KM_BAND = 2
    }

    private fun buyFromMain(changeFixture: Boolean = false): Purchase {
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
            if (changeFixture) seedOriginalChangeOffer(game)
        }

        openDelivery()
        click("simulation:destination")
        val offer = firstPurchase()
        await("표시한 구매 후보의 발견 기록") {
            read { CollectionKey(offer.vehicleId, offer.rarity) in ok(it.store.discoveredVehicles()) }
        }
        awaitUi("발견 저장을 마친 구매 목록의 상세 열기") {
            val current = session.delivery.state ?: return@awaitUi false
            val offers = (current.screen as? DeliveryScreen.Offers)?.proposal ?: return@awaitUi false
            current.displayedProposal == null && offer in offers.proposed.offers &&
                enabledButton("delivery:offer:${offer.id.value}")?.performClick() == true
        }
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

    private fun seedOriginalChangeOffer(game: GameSession) {
        val rules = ok(ok(AssetTrialDefinitionProvider(context.assets).load()).deliveryRules())
        val content = ok(game.content)
        val shopLevel = ok(game.store.rental.shop()).progress.shopLevel
        val unlocked = content.unlockedAt(shopLevel)
        val casper = unlocked.single { it.vehicleId == VehicleId("hyundai_casper") }
        val price = requireNotNull(rules.purchasePrice(casper.price, Rarity.COMMON))
        assertEquals(GameMoney(1_000), price)
        // 후보 차종은 중복 없이 뽑고, 일반보다 낮은 가격 배율은 없다.
        // 일반 가격이 기준가 이하인 차종이 후보 수보다 적어야 항상 양수 차액이 나온다.
        val commonPrices = unlocked.map { requireNotNull(rules.purchasePrice(it.price, Rarity.COMMON)).units }
        assertTrue(rules.rarityPricePercent.values.all { it >= rules.rarityPricePercent.getValue(Rarity.COMMON) })
        assertTrue(commonPrices.count { it <= price.units } < minOf(rules.purchaseOfferCount, unlocked.size))
        assertEquals(50_000L, rules.bands[OVER_50_KM_BAND - 1].maxMetersInclusive)
        assertEquals(OVER_50_KM_BAND, rules.bandIndexOf(66_000L))
        val purchase = DeliveryOffer.Purchase(DeliveryOfferId("offer-0"), casper.vehicleId, Rarity.COMMON, price)
        val list = DeliveryList(rules.bandIndexOf(36_000L), InputSource.SIMULATION, simulationDay(),
            content.catalog.contentVersion, rules.ruleVersion, listOf(purchase), 1)
        assertEquals(list, ok(game.store.delivery.saveList(list)))
    }

    private fun simulationDay() = Instant.ofEpochMilli(SimulationTimeline().clock.epoch.value)
        .atZone(ZoneId.of("UTC")).toLocalDate()

    private fun firstPurchase(): DeliveryOffer.Purchase {
        val day = simulationDay()
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
            val menu = root.mainPage(HostPage.DELIVERY)
                ?.takeIf { it.isShown && it.isEnabled && it.isLaidOut && !it.isLayoutRequested }
                ?: return@awaitUi false
            root.openMainPage(HostPage.DELIVERY)
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
