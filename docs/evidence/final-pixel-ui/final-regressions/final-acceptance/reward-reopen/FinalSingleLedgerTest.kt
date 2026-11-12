package io.drivegrowth.app.simulation

import io.drivegrowth.app.mainNavigation
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.showSimulationControls
import io.drivegrowth.app.host.HostPage
import android.os.SystemClock
import android.os.Process
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import io.drivegrowth.app.R
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DeliveryOffer
import android.util.Log
import io.drivegrowth.app.host.progression.progressKey
import io.drivegrowth.core.quest.QuestStatus
import io.drivegrowth.core.quest.questPeriod
import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.rental.*
import java.time.Instant
import java.time.ZoneId

/** 시연 런처의 실제 기존 UI/공개 명령/SQLite. 입력·도착은 명시 SIMULATION, SDK 실행 시험이 아니다. */
@Suppress("DEPRECATION")
class FinalSingleLedgerTest : ActivityInstrumentationTestCase2<UnifiedSimulationActivity>(UnifiedSimulationActivity::class.java) {
    private val context get() = instrumentation.targetContext
    private fun <T> ok(result: ContractResult<T>): T { assertTrue(result.toString(), result is ContractResult.Success); return (result as ContractResult.Success).value }
    private fun tree(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { tree(v.getChildAt(it)) } else emptyList()
    private fun awaitUi(a: UnifiedSimulationActivity, label: String, check: () -> Boolean) {
        val end=SystemClock.uptimeMillis()+90_000;var passed=false
        while (!passed && SystemClock.uptimeMillis()<end) {
            instrumentation.runOnMainSync { passed=check() }
            if (!passed) SystemClock.sleep(50)
        }
        assertTrue(label,passed)
    }
    private fun click(a: UnifiedSimulationActivity, tag: Any) {
        awaitUi(a, "active button $tag") {
            a.window.decorView.findViewWithTag<Button>(tag)
                ?.takeIf { it.isShown && it.isEnabled && it.isAttachedToWindow }?.performClick() == true
        }
    }
    private fun confirmOffer(a: UnifiedSimulationActivity, id: String) {
        val tag = "delivery:offer:$id"
        awaitUi(a, "active delivery confirmation for $tag") {
            val root = a.window.decorView
            val confirm = root.findViewWithTag<Button>("delivery:confirm")
            if (confirm?.isEnabled == true && confirm.isShown) return@awaitUi confirm.performClick()
            root.findViewWithTag<Button>(tag)?.takeIf { it.isShown && it.isEnabled }?.performClick()
            false
        }
    }
    private fun factory(): GameSessionFactory {
        val clock={ val p=context.getSharedPreferences("unified-simulation-v1",0);SimulationTimeline(p.getLong("elapsed",0),p.getInt("stage",0),p.getBoolean("restRun",false)).clock.epoch.value }
        return GameSessionFactory(context,mapOf(InputSource.SIMULATION to "unified-simulation.db"),InputSource.SIMULATION,{
            when (val r=AssetTrialDefinitionProvider(context.assets).load()) {
                is ContractResult.Failure->r
                is ContractResult.Success->r.value.questDefinitions(ZoneId.of("UTC"))
            }
        },clock)
    }
    private fun <T> read(action:(GameSession)->T):T = ok(factory().open()).use(action)
    private fun awaitStored(label:String,check:()->Boolean) {
        val end=SystemClock.uptimeMillis()+90_000
        while (SystemClock.uptimeMillis()<end) { if(check())return;SystemClock.sleep(50) }
        fail(label)
    }
    private val prefs get() = context.getSharedPreferences("unified-simulation-v1",0)
    private fun timeline() = SimulationTimeline(prefs.getLong("elapsed",0), prefs.getInt("stage",0), prefs.getBoolean("restRun",false))
    private fun page(a: UnifiedSimulationActivity, page: HostPage) {
        awaitUi(a, "page $page") {
            val button = a.window.decorView.findViewWithTag<Button>("host:page:${page.name}") ?: return@awaitUi false
            if (!button.isShown || !button.isEnabled) return@awaitUi false
            if (button.isSelected) return@awaitUi true
            button.performClick()
            false
        }
    }
    private fun drive(a: UnifiedSimulationActivity) {
        val before = timeline().elapsedMs
        instrumentation.runOnMainSync { a.window.decorView.showSimulationControls() }
        click(a, "simulation:start")
        awaitUi(a, "simulated drive ends") {
            timeline().elapsedMs > before && timeline().terminal && a.window.decorView.mainNavigation()?.isEnabled == true
        }
    }
    private fun slot() = read { g -> ok(g.store.hostCommands.load(g.identity.profileId)) }
    private fun awaitClear() = awaitStored("common slot clears") { slot() == null }
    private fun rows() = read { g -> ok(g.store.listUnviewedSettlements()) }
    private fun balance() = read { g -> ok(g.store.balance()) }
    private fun owned() = read { g -> ok(g.store.listOwnedVehicles()) }
    private fun note(stage: String, detail: String) {
        Log.i("FinalSingleLedger", "$stage profile=${read { it.identity.profileId.value }} $detail")
    }

    private val evidence get() = context.filesDir.resolve("single-ledger-before.json")
    private fun originalRows() = rows().map {
        "${it.settlementId.value}|${it.transaction.requestId.value}|${it.transaction.kind}|${it.transaction.source}|${it.transaction.money}|${it.balance.units}"
    }.sorted().joinToString("\n")
    fun testInitialGrantIncomeDeliveryAndFirstRentalBeforeStop() {
        assertEquals("io.drivegrowth.verify.singleledger28", context.packageName)
        for (name in listOf("unified-simulation.db", "game-ledger-vehicle.db", "game-ledger-simulation.db"))
            assertFalse("fresh isolated database: $name", context.getDatabasePath(name).exists())
        assertFalse(prefs.contains("stage"))
        val a = activity
        for (tag in listOf(R.string.onboarding_start, VehicleId("hyundai_casper"), R.string.onboarding_review,
            R.string.onboarding_confirm, R.string.onboarding_to_shop)) click(a, tag)
        val identity = read { it.identity }
        assertEquals(InputSource.SIMULATION, identity.source)
        assertEquals(GameMoney(1_000), balance())
        page(a, HostPage.DELIVERY)
        click(a, "simulation:destination")
        val day = Instant.ofEpochMilli(timeline().clock.epoch.value).atZone(ZoneId.of("UTC")).toLocalDate()
        var offer: DeliveryOffer.Income? = null
        awaitStored("income candidate") {
            offer = read { ok(it.store.delivery.lists(day)).flatMap { list -> list.offers }
                .filterIsInstance<DeliveryOffer.Income>().firstOrNull() }
            offer != null
        }
        confirmOffer(a, checkNotNull(offer).id.value)
        click(a, "delivery:acknowledge")
        awaitClear()
        val route = read { checkNotNull(ok(it.store.delivery.inProgress())) }
        drive(a)
        awaitStored("first rental ticket") {
            read { ok(it.store.readFirstRentalGift(identity.profileId)).progress.freeTickets == 1L }
        }
        awaitStored("original driving and income delivery receipts") {
            rows().any { it.transaction.kind == TransactionKind.DRIVING } &&
                read { ok(it.store.delivery.arrivalResult(route.active.requestId))?.settlement != null }
        }
        val driving = rows().single { it.transaction.kind == TransactionKind.DRIVING }
        val delivery = read { checkNotNull(ok(it.store.delivery.arrivalResult(route.active.requestId))?.settlement) }
        assertEquals(TransactionKind.DELIVERY, delivery.transaction.kind)
        assertEquals(InputSource.SIMULATION, driving.transaction.source)
        assertEquals(InputSource.SIMULATION, delivery.transaction.source)
        assertTrue(driving.settlementId != delivery.settlementId)
        assertEquals(route.route.value, prefs.getString("arrivalRoute", null))
        assertEquals(1, owned().size)
        assertFalse(evidence.exists())
        evidence.writeText(JSONObject().put("pid", Process.myPid()).put("profile", identity.profileId.value)
            .put("balance", balance().units).put("rows", originalRows())
            .put("driving", driving.settlementId.value).put("delivery", delivery.settlementId.value)
            .put("route", route.route.value).put("request", route.active.requestId.value)
            .put("vehicle", owned().single().ownedVehicleId.value).toString())
        note("BEFORE_STOP", "pid=${Process.myPid()} driving=${driving.settlementId.value} delivery=${delivery.settlementId.value} balance=${balance().units}")
    }

    fun testReopenSameLedgerThenUseTicketBuyExpandInstallAndClaim() {
        assertEquals("io.drivegrowth.verify.singleledger28", context.packageName)
        val previous = JSONObject(evidence.readText())
        assertTrue(previous.getInt("pid") != Process.myPid())
        val a = activity
        val identity = read { it.identity }
        assertEquals(previous.getString("profile"), identity.profileId.value)
        assertEquals(InputSource.SIMULATION, identity.source)
        assertEquals(previous.getLong("balance"), balance().units)
        assertEquals(previous.getString("rows"), originalRows())
        assertEquals(previous.getString("vehicle"), owned().single().ownedVehicleId.value)
        page(a, HostPage.DELIVERY)
        click(a, "delivery:acknowledge")
        awaitClear()
        assertEquals(previous.getString("rows"), originalRows())
        val originalId = previous.getString("delivery")
        page(a, HostPage.RESULTS)
        click(a, "results:open:$originalId")
        click(a, "results:mark:$originalId")
        awaitStored("only original delivery result was viewed") {
            rows().none { it.settlementId.value == originalId }
        }
        assertEquals(previous.getString("rows").lines().filterNot { it.startsWith("$originalId|") }, originalRows().lines())
        assertEquals(previous.getLong("balance"), balance().units)
        note("AFTER_STOP", "pid=${Process.myPid()} original=$originalId request=${previous.getString("request")} balance=${balance().units}")
        val beforeFree = balance()
        page(a, HostPage.GACHA)
        click(a, "gacha:option:free")
        assertEquals(beforeFree, balance())
        click(a, "gacha:confirm")
        click(a, "gacha:garage")
        awaitClear()
        val free = rows().single { it.changedVehicle?.acquisitionSource == VehicleAcquisitionSource.GACHA }
        assertEquals(InputSource.SIMULATION, free.transaction.source)
        assertNull(free.transaction.money)
        assertEquals(beforeFree, free.balance)
        assertEquals(GameMoney(0), free.changedVehicle?.acquisitionCost)
        assertEquals(2, owned().size)
        assertEquals(0L, read { ok(it.store.readFirstRentalGift(identity.profileId)).progress.freeTickets })
        note("FREE", "request=${free.transaction.requestId.value} balance=${free.balance.units} owned=2")

        read { g ->
            val rule = ok(g.content).ruleVersion
            ok(g.store.commitGameChange(GameChange(RequestId("e2e28-fixture-credit"), TransactionKind.GARAGE,
                identity.source, rule, MoneyChange(MoneyDirection.CREDIT, GameMoney(10_000)))))
        }
        val purchaseBefore = balance()
        page(a, HostPage.DELIVERY)
        click(a, "simulation:destination")
        val day = Instant.ofEpochMilli(timeline().clock.epoch.value).atZone(ZoneId.of("UTC")).toLocalDate()
        var offer: DeliveryOffer.Purchase? = null
        awaitStored("stored affordable purchase") {
            offer = read { g -> ok(g.store.delivery.lists(day)).flatMap { it.offers }.filterIsInstance<DeliveryOffer.Purchase>()
                .firstOrNull { it.price.units <= purchaseBefore.units - 500 } }
            offer != null
        }
        val purchase = checkNotNull(offer)
        awaitStored("discovery persisted") { read { CollectionKey(purchase.vehicleId,purchase.rarity) in ok(it.store.discoveredVehicles()) } }
        confirmOffer(a, purchase.id.value)
        awaitStored("purchase payment committed") { read { ok(it.store.delivery.inProgress()) != null } }
        val route = read { checkNotNull(ok(it.store.delivery.inProgress())) }
        val payment = rows().single { it.transaction.requestId == route.active.requestId }
        assertEquals(MoneyChange(MoneyDirection.DEBIT, purchase.price), payment.transaction.money)
        assertEquals(GameMoney(purchaseBefore.units - purchase.price.units), payment.balance)
        assertEquals(2, owned().size)
        click(a, "delivery:acknowledge")
        awaitClear()
        drive(a)
        awaitStored("matching purchase arrival") { read { ok(it.store.delivery.arrivalResult(route.active.requestId)) != null } }
        val arrival = read { checkNotNull(ok(it.store.delivery.arrivalResult(route.active.requestId))) }
        val delivered = checkNotNull(arrival.settlement?.changedVehicle)
        assertNull(arrival.settlement?.transaction?.money)
        assertEquals(purchase.vehicleId, delivered.vehicleId)
        assertEquals(purchase.rarity, delivered.rarity)
        assertEquals(purchase.price, delivered.acquisitionCost)
        assertEquals(VehicleAcquisitionSource.PURCHASE_DELIVERY, delivered.acquisitionSource)
        assertEquals(3, owned().size)
        assertEquals(route.route.value, prefs.getString("arrivalRoute", null))
        click(a, "delivery:acknowledge")
        awaitClear()
        note("DELIVERED", "request=${route.active.requestId.value} price=${purchase.price.units} owned=3")

        // 독립 시험 준비. 구매 탁송은 같은 프로필에 보존하고, 시설 해금에 필요한 조건만 마련한다.
        read { g ->
            val store = g.store
            val definition = ok(store.trialDefinitions.load())
            val rental = ok(definition.rentalVisitDefinition())
            val catalog = ok(g.content).catalog
            val first = ok(store.listOwnedVehicles()).first()
            repeat(3) { index ->
                val start = SimulationTimeline().clock.epoch.value - (3 - index) * 4_500_000L
                val id = RequestId("e2e28-fixture-rental-$index")
                val terms = RentalTerms(rental.rules, SimulatedCustomer(30, 48), 99, EpochMillis(start),
                    15, rental.fuelBonus, rental.rounding, false)
                ok(store.rental.assign(RentalRequest(id, first.vehicleId, 1), catalog, rental.categories, rental.rates, terms))
                ok(store.rental.account(id, terms.start, EpochMillis(start + 3_600_000L), null))
                ok(store.rental.settle(id))
                ok(store.rental.finishReturn(id, EpochMillis(start + 4_500_000L)))
            }
        }
        val beforeExpand = balance()
        page(a, HostPage.HOME)
        click(a, "host:refresh")
        click(a, "shop:preview:expand")
        click(a, "shop:confirm")
        awaitStored("HOME expansion level 2 and capacity 5") {
            read { ok(it.store.rental.shop()).let { shop -> shop.progress.shopLevel == 2 && shop.capacity == 5 } } && slot() == null
        }
        assertEquals(GameMoney(beforeExpand.units - 500), balance())
        note("EXPAND", "HOME debit=500 capacity=5")
        read { g ->
            val store = g.store
            val definition = ok(store.trialDefinitions.load())
            val first = ok(store.listOwnedVehicles()).first()
            repeat(2) { index ->
                val filler = first.copy(ownedVehicleId = OwnedVehicleId("e2e28-fixture-car-$index"), revision = Revision(0),
                    acquisitionSource = VehicleAcquisitionSource.GACHA)
                ok(store.commitGameChange(GameChange(RequestId("e2e28-fixture-add-$index"), TransactionKind.GARAGE,
                    identity.source, definition.rules, vehicle = VehicleWrite.Add(filler))))
            }
            assertEquals(5, ok(store.listOwnedVehicles()).size)
            assertTrue(ok(store.rental.shop()).facilities.isEmpty())
        }
        val beforeFacility = balance()
        page(a, HostPage.HOME)
        click(a, "host:refresh")
        awaitUi(a, "confirmed current fuel preview") {
            val root = a.window.decorView
            val shop = tree(root).filterIsInstance<io.drivegrowth.app.ui.shop.ShopView>().singleOrNull()
                ?: return@awaitUi false
            val selected = shop.uiState.selection?.option?.command
                as? io.drivegrowth.app.ui.shop.ShopCommand.BuyFacility
            val confirm = root.findViewWithTag<Button>("shop:confirm")
            if (selected?.offer?.facility == Facility.FUEL_STATION && confirm?.isEnabled == true && confirm.isShown)
                return@awaitUi confirm.performClick()
            root.findViewWithTag<Button>("shop:preview:facility-FUEL_STATION")
                ?.takeIf { it.isShown && it.isEnabled && it.isAttachedToWindow }?.performClick()
            false
        }
        awaitStored("facility committed") { read { Facility.FUEL_STATION in ok(it.store.rental.shop()).facilities } }
        val facility = checkNotNull(slot())
        val facilityCommand = facility.command as HostCheckpointCommand.FacilityPurchase
        assertEquals(HostCheckpointPhase.STARTED, facility.phase)
        assertEquals(GameMoney(500), facilityCommand.offer.price)
        assertEquals(GameMoney(beforeFacility.units - 500), balance())
        assertEquals(1, rows().count { it.transaction.requestId == facilityCommand.requestId })
        click(a, "facility:ack")
        awaitClear()
        note("FACILITY", "request=${facilityCommand.requestId.value} debit=500")

        val priorRentals = rows().filter { it.transaction.kind == TransactionKind.RENTAL }.map { it.settlementId }.toSet()
        drive(a)
        var completed: CompletedRental? = null
        repeat(2) { attempt ->
            if (completed == null) {
                completed = read { game ->
                    ok(game.store.listUnviewedSettlements()).filter {
                        it.transaction.kind == TransactionKind.RENTAL && it.settlementId !in priorRentals
                    }.mapNotNull { receipt ->
                        ok(game.store.rental.completedRental(RequestId(receipt.transaction.requestId.value.removePrefix("rental/settle/"))))
                    }.firstOrNull { it.receipt.facilityBonus == GameMoney(10) }
                }
                if (completed == null && attempt == 0) drive(a)
            }
        }
        assertNotNull("product rental with installed fuel bonus", completed)
        val boosted = checkNotNull(completed).receipt.settlement
        page(a, HostPage.RESULTS)
        awaitUi(a, "open exact fuel settlement from the results list") {
            val root = a.window.decorView
            val receipt = root.findViewWithTag<Button>("results:open:${boosted.settlementId.value}")
            if (receipt?.isShown == true && receipt.isEnabled) return@awaitUi receipt.performClick()
            val close = root.findViewWithTag<Button>("results:close")
            if (close?.isShown == true && close.isEnabled) close.performClick()
            false
        }
        awaitUi(a, "stored rental shows fuel bonus") { tree(a.window.decorView).filterIsInstance<TextView>()
            .any { it.isShown && "매출에 포함된 시설 보너스" in it.text && "10" in it.text } }
        click(a, "results:mark:${boosted.settlementId.value}")
        awaitStored("rental receipt viewed") { rows().none { it.settlementId == boosted.settlementId } }
        note("RENTAL", "request=${boosted.transaction.requestId.value} settlement=${boosted.settlementId.value} bonus=10")
        instrumentation.runOnMainSync { a.window.decorView.showSimulationControls() }
        click(a, "simulation:rest-start")
        awaitUi(a, "rest scenario completes with navigation available") {
            timeline().restRun && timeline().terminal && a.window.decorView.mainNavigation()?.isEnabled == true
        }
        val rest = read { ok(it.store.restDriving.state(it.identity.profileId)) }
        assertTrue(rest.confirmedDrives >= 2)
        assertEquals(0, rest.pendingDrives)
        assertTrue(rest.confirmed.isActive)
        assertEquals(identity, read { it.identity })
        page(a, HostPage.PROGRESSION)
        awaitUi(a, "rest status active in progress") { tree(a.window.decorView).filterIsInstance<TextView>()
            .any { it.isShown && it.text.toString() == context.getString(R.string.progress_buff_active) } }
        note("REST", "confirmed drives=${rest.confirmedDrives} active")
        val definitions = ok(ok(AssetTrialDefinitionProvider(context.assets).load()).questDefinitions(ZoneId.of("UTC")))
        val quest = definitions.definitions.single { it.id == "daily-result-viewed" }
        val period = questPeriod(quest.cadence, quest.zone, timeline().clock.epoch)
        val beforeQuest = balance()
        page(a, HostPage.PROGRESSION)
        click(a, progressKey(quest, period))
        click(a, "progress-claim")
        awaitStored("quest credit") { balance() == GameMoney(beforeQuest.units + quest.reward.units) }
        awaitClear()
        val claimed = rows().single { it.transaction.requestId.value.startsWith("quest/claim/") }
        assertEquals(InputSource.SIMULATION, claimed.transaction.source)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, quest.reward), claimed.transaction.money)
        assertEquals(identity, read { it.identity })
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        assertFalse(context.getDatabasePath("game-ledger-simulation.db").exists())
        note("QUEST", "request=${claimed.transaction.requestId.value} credit=${quest.reward.units} balance=${balance().units}")
        assertFalse(finalEvidence.exists())
        val verified = finalSnapshot(claimed.transaction.requestId)
        assertEquals(beforeQuest.units + quest.reward.units, verified.getLong("balance"))
        finalEvidence.writeText(JSONObject().put("pid", Process.myPid()).put("ledger", verified).toString())

    }

    private val finalEvidence get() = context.filesDir.resolve("single-ledger-after-claim.json")

    private fun finalSnapshot(claimRequest: RequestId): JSONObject = read { game ->
        val store = game.store
        val profile = game.identity.profileId
        assertEquals(InputSource.SIMULATION, game.identity.source)
        assertNull(ok(store.hostCommands.load(profile)))
        val history = ok(SettlementHistoryReader(store).read())
        assertEquals(game.identity, history.identity)
        val claim = checkNotNull(ok(store.quests.settlement(claimRequest)))
        assertEquals(profile, claim.command.profileId)
        assertEquals(InputSource.SIMULATION, claim.command.source)
        assertEquals("daily-result-viewed", claim.command.definition.id)
        assertEquals(QuestStatus.CLAIMED, claim.progress.status)
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(100)), claim.settlement.transaction.money)
        assertEquals(setOf(claimRequest), ok(store.quests.claimedRequestIds(profile)))
        assertEquals(claim.settlement, history.entries.single { it.settlement.transaction.requestId == claimRequest }.settlement)
        val rest = ok(store.restDriving.state(profile))
        assertTrue(rest.confirmedDrives >= 2)
        assertEquals(0, rest.pendingDrives)
        assertTrue(rest.confirmed.isActive)
        val vehicles = ok(store.listVehicleHistory()).sortedBy { it.ownedVehicleId.value }
        val settlements = history.entries.sortedBy { it.settlement.settlementId.value }
        JSONObject().put("profile", profile.value).put("source", game.identity.source.name)
            .put("balance", ok(store.balance()).units).put("claimRequest", claimRequest.value)
            .put("vehicles", JSONArray(vehicles.map(LedgerJson::vehicle)))
            .put("settlements", JSONArray(settlements.map { row ->
                JSONObject().put("settlement", LedgerJson.settlement(row.settlement)).put("viewed", row.viewed)
            }))
            .put("confirmedDrives", rest.confirmedDrives).put("pendingDrives", rest.pendingDrives)
            .put("restRevision", rest.revision).put("remainingMs", rest.confirmed.remainingMs)
            .put("lastStopNumber", rest.confirmed.lastStopNumber)
    }

    fun testReopenAfterRestAndQuestKeepsConfirmedResultsWithoutAnotherCredit() {
        assertEquals("io.drivegrowth.verify.singleledger28", context.packageName)
        val prior = JSONObject(finalEvidence.readText())
        val previous = JSONObject(evidence.readText())
        assertTrue("새 프로세스에서 실행해야 합니다", Process.myPid() != prior.getInt("pid"))
        assertTrue(Process.myPid() != previous.getInt("pid"))
        val saved = prior.getJSONObject("ledger")
        assertEquals(previous.getString("profile"), saved.getString("profile"))
        val claimRequest = RequestId(saved.getString("claimRequest"))
        assertEquals(saved.toString(), finalSnapshot(claimRequest).toString())

        val a = activity
        page(a, HostPage.PROGRESSION)
        awaitUi(a, "복원된 휴식 잔여 시간과 활성 표시") {
            val labels = tree(a.window.decorView).filterIsInstance<TextView>().filter { it.isShown }
            val seconds = java.math.BigDecimal.valueOf(saved.getLong("remainingMs"), 3).stripTrailingZeros().toPlainString()
            labels.any { it.text.toString() == context.getString(R.string.progress_buff_active) } &&
                labels.any { it.text.toString() == "원장 잔여 ${seconds}초" }
        }
        val claim = read { checkNotNull(ok(it.store.quests.settlement(claimRequest))) }
        click(a, progressKey(claim.command.definition, claim.command.period))
        awaitUi(a, "이미 수령한 퀘스트의 지급 버튼 비활성") {
            val root = a.window.decorView
            val button = root.findViewWithTag<Button>("progress-claim")
            button?.isShown == true && !button.isEnabled && tree(root).filterIsInstance<TextView>()
                .any { it.isShown && it.text.toString() == context.getString(R.string.progress_claimed) }
        }
        assertEquals(saved.toString(), finalSnapshot(claimRequest).toString())
        assertFalse(context.getDatabasePath("game-ledger-vehicle.db").exists())
        assertFalse(context.getDatabasePath("game-ledger-simulation.db").exists())
        note("REOPEN_AFTER_QUEST", "priorPid=${prior.getInt("pid")} pid=${Process.myPid()} balance=${saved.getLong("balance")} " +
            "remainingMs=${saved.getLong("remainingMs")} originalSettlements=${saved.getJSONArray("settlements").length()} duplicateCredit=0")
    }
}
