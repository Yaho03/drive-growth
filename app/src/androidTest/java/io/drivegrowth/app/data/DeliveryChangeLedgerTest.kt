package io.drivegrowth.app.data

import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class DeliveryChangeLedgerTest : InstrumentationTestCase() {
    private val source = InputSource.SIMULATION
    private val rule = RuleVersion("delivery-trial-v0")
    private val dayMs = 24 * 60 * 60 * 1000L
    private val today = LocalDate.of(2026, 11, 7)
    private val changeRules = DeliveryChangeRules(RuleVersion("delivery-change-trial-v0"), 10)
    private val settleRules = DeliverySettlementRules(RuleVersion("delivery-settle-trial-v0"), dayMs, dayMs, 5)
    private val income = DeliveryOffer.Income(DeliveryOfferId("offer-0"), VehicleId("hyundai_casper"), GameMoney(400))
    private val purchase = DeliveryOffer.Purchase(DeliveryOfferId("offer-1"), VehicleId("hyundai_sonata"), Rarity.RARE, GameMoney(3_000))
    private val list = DeliveryList(1, source, today, ContentVersion("content-test"), rule, listOf(income, purchase), 2)
    private val proposed = DeliveryProposed(DestinationKey("destination-a"), EstimatedRouteDistance(20_000, source), list, list.offers)
    private val route = RouteId("route-1")
    private val newRoute = RouteId("route-2")
    private val start = EpochMillis(1_000_000)
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private lateinit var ledger: DeliveryLedger

    private val pricier = DeliveryOffer.Purchase(DeliveryOfferId("change-0"), VehicleId("hyundai_grandeur"), Rarity.ADVANCED, GameMoney(3_500))
    private val cheaper = DeliveryOffer.Purchase(DeliveryOfferId("change-1"), VehicleId("hyundai_avante"), Rarity.COMMON, GameMoney(1_200))
    private val newIncome = DeliveryOffer.Income(DeliveryOfferId("change-0"), VehicleId("hyundai_avante"), GameMoney(700))

    private fun changeOffer(vararg offers: DeliveryOffer, id: String = "r1"): DeliveryChangeOffer {
        val changeList = DeliveryList(1, source, today, ContentVersion("content-test"), rule, offers.toList(), offers.size)
        return DeliveryChangeOffer(RequestId(id), 30_000, 50_000,
            DeliveryProposed(DestinationKey("destination-b"), EstimatedRouteDistance(20_000, source), changeList, changeList.offers))
    }

    private fun open() = SqliteGameChangeStore(context, source, name)

    override fun setUp() {
        super.setUp()
        name = "change-test-" + System.nanoTime() + ".db"
        store = open()
        ok(store.commitGameChange(GameChange(RequestId("seed"), TransactionKind.GARAGE, source, rule,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(10_000)))))
        ledger = store.delivery
        ok(ledger.saveList(list))
    }

    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }

    private fun reopen() { store.close(); store = open(); ledger = store.delivery }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    private fun balance() = ok(store.balance()).units

    private fun choose(offer: DeliveryOffer, id: String = "r1") {
        ok(ledger.select(proposed, offer.id, RequestId(id)))
        ok(ledger.beginRoute(RequestId(id), route, start))
    }

    private fun arrival(r: RouteId) = Observation.Available(DeliveryArrivalEvidence(r, source, EpochMillis(start.value + 60_000)))

    fun testPricierChangePaysDifferenceAndMovesActiveToNewRoute() {
        choose(purchase)
        val commit = ok(ledger.changeDestination(changeRules, changeOffer(pricier, cheaper), pricier.id, newRoute))
        assertEquals(MoneyChange(MoneyDirection.DEBIT, GameMoney(500)), commit.settlement?.transaction?.money)
        assertEquals(10_000 - 3_000 - 500, balance())
        assertEquals(GameMoney(3_500), commit.terms.paid)
        assertTrue(commit.terms.changeUsed)
        reopen()
        val progress = requireNotNull(ok(ledger.inProgress()))
        assertEquals(pricier, progress.active.offer)
        assertEquals(newRoute, progress.route)
        assertEquals(start, progress.startedAt)
        assertEquals(list, ok(ledger.lists(today)).single())
    }

    fun testCheaperChangeRefundsDifference() {
        choose(purchase)
        val commit = ok(ledger.changeDestination(changeRules, changeOffer(pricier, cheaper), cheaper.id, newRoute))
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(1_800)), commit.settlement?.transaction?.money)
        assertEquals(10_000 - 1_200, balance())
    }

    fun testSameChangeReplaysAndAnotherChangeIsRejected() {
        choose(purchase)
        val offer = changeOffer(pricier, cheaper)
        val first = ok(ledger.changeDestination(changeRules, offer, pricier.id, newRoute))
        reopen()
        val again = ok(ledger.changeDestination(changeRules, offer, pricier.id, newRoute))
        assertEquals(first.settlement?.settlementId, again.settlement?.settlementId)
        assertEquals(first.terms, again.terms)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            ledger.changeDestination(changeRules, offer, cheaper.id, newRoute))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            ledger.changeDestination(changeRules, offer, pricier.id, RouteId("route-3")))
        assertEquals(6_500, balance())
    }

    fun testInsufficientFundsKeepsActiveMoneyAndRoute() {
        choose(purchase)
        val expensive = pricier.copy(price = GameMoney(20_000))
        assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS),
            ledger.changeDestination(changeRules, changeOffer(expensive), expensive.id, newRoute))
        assertEquals(7_000, balance())
        val progress = requireNotNull(ok(ledger.inProgress()))
        assertEquals(purchase, progress.active.offer)
        assertEquals(route, progress.route)
        ok(ledger.changeDestination(changeRules, changeOffer(cheaper), cheaper.id, newRoute))
    }

    fun testIncomeChangeHasNoMoneyAndNewReward() {
        choose(income)
        val commit = ok(ledger.changeDestination(changeRules, changeOffer(newIncome), newIncome.id, newRoute))
        assertNull(commit.settlement)
        assertEquals(10_000, balance())
        assertEquals(newIncome, requireNotNull(ok(ledger.inProgress())).active.offer)
    }

    fun testChangeNeedsActiveRequestAndRoute() {
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            ledger.changeDestination(changeRules, changeOffer(pricier), pricier.id, newRoute))
        ok(ledger.select(proposed, purchase.id, RequestId("r1")))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            ledger.changeDestination(changeRules, changeOffer(pricier), pricier.id, newRoute))
        ok(ledger.beginRoute(RequestId("r1"), route, start))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            ledger.changeDestination(changeRules, changeOffer(pricier, id = "r2"), pricier.id, newRoute))
        assertEquals(7_000, balance())
    }

    fun testFailureBeforeCommitRollsBackChange() {
        choose(purchase)
        store.beforeCommit = { throw SQLiteException("주입") }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),
            ledger.changeDestination(changeRules, changeOffer(pricier), pricier.id, newRoute))
        store.beforeCommit = null
        reopen()
        assertEquals(7_000, balance())
        val progress = requireNotNull(ok(ledger.inProgress()))
        assertEquals(purchase, progress.active.offer)
        assertEquals(route, progress.route)
        ok(ledger.changeDestination(changeRules, changeOffer(pricier), pricier.id, newRoute))
    }

    fun testArrivalAfterChangeUsesNewRouteAndNewOffer() {
        choose(purchase)
        ok(ledger.changeDestination(changeRules, changeOffer(pricier), pricier.id, newRoute))
        val nextList: (DeliveryList, DeliveryOfferId) -> ContractResult<DeliveryList> = { l, _ -> ContractResult.Success(l) }
        assertTrue(ok(ledger.settleArrival(settleRules, RequestId("r1"), arrival(route), EpochMillis(start.value + 120_000), nextList)).outcome
            is DeliveryOutcome.AwaitingEvidence)
        val done = ok(ledger.settleArrival(settleRules, RequestId("r1"), arrival(newRoute), EpochMillis(start.value + 120_000), nextList))
        val car = ((done.outcome as DeliveryOutcome.VehicleDelivered).change.vehicle as VehicleWrite.Add).vehicle
        assertEquals(VehicleId("hyundai_grandeur"), car.vehicleId)
        assertEquals(GameMoney(3_500), car.acquisitionCost)
        assertEquals(list, ok(ledger.lists(today)).single())
    }

    fun testPurchaseAbandonRefundsAfterFeeOnceAndKeepsList() {
        choose(purchase)
        val abandoned = ok(ledger.abandon(changeRules, RequestId("r1")))
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(2_700)), abandoned.settlement?.transaction?.money)
        assertEquals(7_000 + 2_700, balance())
        assertNull(ok(ledger.active()))
        assertEquals(list, ok(ledger.lists(today)).single())
        reopen()
        val again = ok(ledger.abandon(changeRules, RequestId("r1")))
        assertEquals(abandoned.settlement?.settlementId, again.settlement?.settlementId)
        assertEquals(9_700, balance())
        assertTrue(owned().isEmpty())
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            ledger.settleArrival(settleRules, RequestId("r1"), arrival(route), EpochMillis(start.value + 120_000)) { l, _ -> ContractResult.Success(l) })
    }

    fun testAbandonAfterChangeRefundsFromChangedPrice() {
        choose(purchase)
        ok(ledger.changeDestination(changeRules, changeOffer(pricier), pricier.id, newRoute))
        val abandoned = ok(ledger.abandon(changeRules, RequestId("r1")))
        assertEquals(MoneyChange(MoneyDirection.CREDIT, GameMoney(3_150)), abandoned.settlement?.transaction?.money)
        assertEquals(6_500 + 3_150, balance())
    }

    fun testIncomeAbandonHasNoPenalty() {
        choose(income)
        val abandoned = ok(ledger.abandon(changeRules, RequestId("r1")))
        assertNull(abandoned.settlement)
        assertEquals(10_000, balance())
        assertNull(ok(ledger.active()))
        assertEquals(income, ok(ledger.select(proposed, income.id, RequestId("r2"))).selected.active.offer)
    }

    fun testAbandonAfterArrivalOrOfAnotherRequestIsRejected() {
        choose(income)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.abandon(changeRules, RequestId("other")))
        ok(ledger.settleArrival(settleRules, RequestId("r1"), arrival(route), EpochMillis(start.value + 120_000)) { l, _ -> ContractResult.Success(l) })
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.abandon(changeRules, RequestId("r1")))
        assertEquals(10_400, balance())
    }

    private fun owned() = ok(store.listOwnedVehicles())

    private val keep: (DeliveryList, DeliveryOfferId) -> ContractResult<DeliveryList> = { l, _ -> ContractResult.Success(l) }

    private fun sql(vararg statements: String) = ok(store.atomic { db -> statements.forEach(db::execSQL); ContractResult.Success(Unit) })

    private val tables = listOf(
        "account", "settlements", "vehicles", "delivery_lists", "delivery_requests", "delivery_active", "delivery_routes",
        "delivery_outcomes", "delivery_changes", "delivery_route_history",
    )

    /** 표의 모든 칸을 형식까지 포함해 읽는다. 실패한 호출이 원본을 바꾸지 않았는지 본다. */
    private fun dump(): List<String> = ok(store.atomic { db ->
        ContractResult.Success(tables.map { table ->
            val columns = db.rawQuery("SELECT * FROM $table LIMIT 0", null).use { it.columnNames.toList() }
            val quoted = columns.joinToString(" || '|' || ") { "quote($it)" }
            db.rawQuery("SELECT $quoted FROM $table ORDER BY rowid", null).use {
                buildList { while (it.moveToNext()) add(it.getString(0)) }.joinToString("\n")
            }
        })
    })

    private fun assertStorageFailureKeepsRows(vararg calls: () -> ContractResult<*>, label: String = "") {
        val before = dump()
        calls.forEach { assertEquals(label, ContractResult.Failure(ContractError.STORAGE_FAILURE), it()) }
        reopen()
        calls.forEach { assertEquals(label, ContractResult.Failure(ContractError.STORAGE_FAILURE), it()) }
        assertEquals(label, before, dump())
    }

    /** 처음 순회와 그 뒤 순회 결과가 다른 목록. 입력을 한 번만 읽는지 확인한다. */
    private class Flipping(private val first: List<DeliveryOffer>, private val later: List<DeliveryOffer>) : AbstractList<DeliveryOffer>() {
        var reads = 0
        override val size get() = if (reads == 0) first.size else later.size
        override fun get(index: Int) = (if (reads == 0) first else later)[index]
        override fun iterator(): Iterator<DeliveryOffer> = (if (reads++ == 0) first else later).iterator()
    }

    fun testAbandonReplayComparesRuleVersionAndFeeForPurchaseAndIncome() {
        for ((offer, id) in listOf(purchase to "p", income to "i")) {
            choose(offer, id, RouteId("route-$id"))
            val balanceBefore = balance()
            val first = ok(ledger.abandon(changeRules, RequestId(id)))
            val after = balance()
            reopen()
            assertEquals(first, ok(ledger.abandon(changeRules, RequestId(id))))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.abandon(changeRules.copy(cancelFeePercent = 20), RequestId(id)))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
                ledger.abandon(changeRules.copy(ruleVersion = RuleVersion("delivery-change-trial-v1")), RequestId(id)))
            assertEquals(after, balance())
            assertEquals(if (offer is DeliveryOffer.Purchase) balanceBefore + 2_700 else balanceBefore, after)
            assertNull(ok(ledger.active()))
        }
    }

    private fun choose(offer: DeliveryOffer, id: String, r: RouteId) {
        ok(ledger.select(proposed, offer.id, RequestId(id)))
        ok(ledger.beginRoute(RequestId(id), r, start))
    }

    fun testStoredChangeThatDoesNotMatchItsCommandOrPaymentIsStorageFailure() {
        choose(purchase)
        val offer = changeOffer(pricier, cheaper)
        val first = ok(ledger.changeDestination(changeRules, offer, pricier.id, newRoute))
        val calls = arrayOf<() -> ContractResult<*>>(
            { ledger.changeDestination(changeRules, offer, pricier.id, newRoute) }, { ledger.inProgress() }, { ledger.active() },
        )
        sql("CREATE TABLE backup AS SELECT * FROM delivery_changes", "CREATE TABLE settle_backup AS SELECT * FROM settlements WHERE request_id = 'delivery/r1/change'")
        val cheaperActive = DeliveryJson.active(ActiveDelivery(RequestId("r1"), offer.proposed, cheaper)).toString().replace("'", "''")
        for (corruption in listOf(
            "UPDATE delivery_changes SET paid = 'broken'",
            "UPDATE delivery_changes SET paid = 3500.5",
            "UPDATE delivery_changes SET paid = 3400",
            "UPDATE delivery_changes SET charged = 0",
            "UPDATE delivery_changes SET active = '$cheaperActive'",
            "UPDATE delivery_changes SET command = replace(command, '\"fee\":10', '\"fee\":\"10\"')",
            "UPDATE settlements SET result = replace(result, '\"amount\":500', '\"amount\":400') WHERE request_id = 'delivery/r1/change'",
            "UPDATE settlements SET result = replace(result, '\"request\":\"delivery\\/r1\\/change\"', '\"request\":\"other\"') WHERE request_id = 'delivery/r1/change'",
            "DELETE FROM settlements WHERE request_id = 'delivery/r1/change'",
        )) {
            val clean = dump()
            sql("PRAGMA ignore_check_constraints = ON", corruption, "PRAGMA ignore_check_constraints = OFF")
            assertTrue("손상이 들어가지 않음: $corruption", dump() != clean)
            assertStorageFailureKeepsRows(*calls, label = corruption)
            sql("DELETE FROM delivery_changes", "INSERT INTO delivery_changes SELECT * FROM backup",
                "DELETE FROM settlements WHERE request_id = 'delivery/r1/change'", "INSERT INTO settlements SELECT * FROM settle_backup")
        }
        assertEquals(first.settlement?.settlementId, ok(ledger.changeDestination(changeRules, offer, pricier.id, newRoute)).settlement?.settlementId)
        assertEquals(6_500, balance())
        // CHECK를 우회한 값은 같은 연결에서 이 원장이 막고, 다시 열면 저장소 전체 검사(quick_check)가 손상으로 막는다.
        sql("PRAGMA ignore_check_constraints = ON", "UPDATE delivery_changes SET charged = 'yes'", "PRAGMA ignore_check_constraints = OFF")
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), ledger.inProgress())
        reopen()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), ledger.inProgress())
        assertEquals(LedgerFailureReason.CORRUPT, store.lastStorageFailure)
    }

    fun testSamePriceChangeHasNoSettlementAndInjectedOneIsStorageFailure() {
        choose(purchase)
        val same = pricier.copy(price = GameMoney(3_000))
        val commit = ok(ledger.changeDestination(changeRules, changeOffer(same), same.id, newRoute))
        assertNull(commit.settlement)
        reopen()
        assertEquals(commit, ok(ledger.changeDestination(changeRules, changeOffer(same), same.id, newRoute)))
        ok(store.commitGameChange(GameChange(RequestId("delivery/r1/change"), TransactionKind.DELIVERY, source, changeRules.ruleVersion,
            MoneyChange(MoneyDirection.DEBIT, GameMoney(1)))))
        assertStorageFailureKeepsRows({ ledger.inProgress() })
    }

    fun testStoredAbandonThatDoesNotMatchIsStorageFailure() {
        choose(purchase)
        ok(ledger.changeDestination(changeRules, changeOffer(pricier), pricier.id, newRoute))
        ok(ledger.abandon(changeRules, RequestId("r1")))
        val call = { ledger.abandon(changeRules, RequestId("r1")) }
        sql("CREATE TABLE backup AS SELECT * FROM delivery_outcomes")
        for (corruption in listOf(
            "UPDATE delivery_outcomes SET result = replace(result, '\"amount\":3150', '\"amount\":3500')",
            "UPDATE delivery_outcomes SET result = replace(result, 'ABANDON', 'ARRIVAL')",
            "UPDATE delivery_outcomes SET command = replace(command, '\"fee\":10', '\"fee\":0')",
        )) {
            sql(corruption)
            assertStorageFailureKeepsRows(call)
            sql("DELETE FROM delivery_outcomes", "INSERT INTO delivery_outcomes SELECT * FROM backup")
        }
        sql("UPDATE settlements SET result = replace(result, '\"amount\":3150', '\"amount\":3500') WHERE request_id = 'delivery/r1/settle'")
        assertStorageFailureKeepsRows(call)
    }

    fun testChangeReadsItsInputOnce() {
        choose(purchase)
        val flipping = Flipping(listOf(cheaper), listOf(pricier, cheaper))
        val base = changeOffer(pricier, cheaper)
        val changing = base.copy(proposed = base.proposed.copy(offers = flipping))
        flipping.reads = 0
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ledger.changeDestination(changeRules, changing, pricier.id, newRoute))
        assertEquals(7_000, balance())
        assertEquals(purchase, requireNotNull(ok(ledger.inProgress())).active.offer)
    }

    fun testLateOrReusedRouteCannotAttachToChangedDestination() {
        choose(purchase)
        ok(ledger.changeDestination(changeRules, changeOffer(pricier), pricier.id, newRoute))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.beginRoute(RequestId("r1"), route, start))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.beginRoute(RequestId("r1"), RouteId("late"), start, replacing = route))
        assertEquals(newRoute, ok(ledger.beginRoute(RequestId("r1"), newRoute, start)).route)
        assertEquals(RouteId("route-3"), ok(ledger.beginRoute(RequestId("r1"), RouteId("route-3"), start, replacing = newRoute)).route)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.beginRoute(RequestId("r1"), newRoute, start, replacing = RouteId("route-3")))
        assertEquals(RouteId("route-3"), requireNotNull(ok(ledger.inProgress())).route)
        assertEquals(start, requireNotNull(ok(ledger.inProgress())).startedAt)
        ok(ledger.abandon(changeRules, RequestId("r1")))
        ok(ledger.select(proposed, income.id, RequestId("r2")))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.beginRoute(RequestId("r2"), route, start))
        ok(ledger.beginRoute(RequestId("r2"), RouteId("route-r2"), start))
        val next = DeliveryChangeOffer(RequestId("r2"), 30_000, 50_000, changeOffer(newIncome).proposed)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.changeDestination(changeRules, next, newIncome.id, newRoute))
        assertEquals(income, requireNotNull(ok(ledger.inProgress())).active.offer)
    }

    fun testArrivalAndAbandonAtOnceFinishTheRequestOnce() {
        ok(store.commitGameChange(GameChange(RequestId("seed2"), TransactionKind.GARAGE, source, rule,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(20_000)))))
        repeat(3) { round ->
            val id = "race$round"
            choose(purchase, id, RouteId("route-$id"))
            val other = open()
            val otherLedger = DeliveryLedger(other)
            val gate = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val evidence = Observation.Available(DeliveryArrivalEvidence(RouteId("route-$id"), source, EpochMillis(start.value + 60_000)))
                val a = pool.submit(Callable { gate.await(); ledger.settleArrival(settleRules, RequestId(id), evidence, EpochMillis(start.value + 120_000), keep) })
                val b = pool.submit(Callable { gate.await(); otherLedger.abandon(changeRules, RequestId(id)) })
                gate.countDown()
                val arrived = a.get(10, TimeUnit.SECONDS)
                val abandoned = b.get(10, TimeUnit.SECONDS)
                assertEquals(1, listOf(arrived, abandoned).count { it is ContractResult.Success })
                assertTrue(listOf(arrived, abandoned).any { it == ContractResult.Failure(ContractError.STATE_CONFLICT) })
                reopen()
                assertNull(ok(ledger.active()))
                if (arrived is ContractResult.Success) assertTrue(owned().any { it.ownedVehicleId == OwnedVehicleId("delivery/$id") })
                else assertTrue(owned().none { it.ownedVehicleId == OwnedVehicleId("delivery/$id") })
            } finally { pool.shutdownNow(); other.close() }
        }
        val cars = owned().size
        assertEquals((30_000 - 3_000 * 3 + 2_700 * (3 - cars)).toLong(), balance())
    }
}
