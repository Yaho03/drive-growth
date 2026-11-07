package io.drivegrowth.app.data

import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import java.time.LocalDate

@Suppress("DEPRECATION")
class DeliveryChangeLedgerTest : InstrumentationTestCase() {
    private val source = InputSource.SIMULATION
    private val rule = RuleVersion("delivery-trial-v0")
    private val dayMs = 24 * 60 * 60 * 1000L
    private val today = LocalDate.of(2026, 11, 7)
    private val changeRules = DeliveryChangeRules(RuleVersion("delivery-change-trial-v0"), 10)
    private val settlement = DeliverySettlement(DeliverySettlementRules(RuleVersion("delivery-settle-trial-v0"), dayMs, dayMs, 5))
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
        ok(store.atomic { DeliveryLedger.createTables(it); ContractResult.Success(Unit) })
        ok(store.commitGameChange(GameChange(RequestId("seed"), TransactionKind.GARAGE, source, rule,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(10_000)))))
        ledger = DeliveryLedger(store)
        ok(ledger.saveList(list))
    }

    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }

    private fun reopen() { store.close(); store = open(); ledger = DeliveryLedger(store) }

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
        assertTrue(ok(ledger.settleArrival(settlement, arrival(route), EpochMillis(start.value + 120_000), nextList)).outcome
            is DeliveryOutcome.AwaitingEvidence)
        val done = ok(ledger.settleArrival(settlement, arrival(newRoute), EpochMillis(start.value + 120_000), nextList))
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
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            ledger.settleArrival(settlement, arrival(route), EpochMillis(start.value + 120_000)) { l, _ -> ContractResult.Success(l) })
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
        ok(ledger.settleArrival(settlement, arrival(route), EpochMillis(start.value + 120_000)) { l, _ -> ContractResult.Success(l) })
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.abandon(changeRules, RequestId("r1")))
        assertEquals(10_400, balance())
    }

    private fun owned() = ok(store.listOwnedVehicles())
}
