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
class DeliveryLedgerTest : InstrumentationTestCase() {
    private val source = InputSource.SIMULATION
    private val rule = RuleVersion("delivery-trial-v0")
    private val day = LocalDate.of(2026, 11, 7)
    private val income = DeliveryOffer.Income(DeliveryOfferId("offer-0"), VehicleId("hyundai_casper"), GameMoney(400))
    private val purchase = DeliveryOffer.Purchase(DeliveryOfferId("offer-1"), VehicleId("hyundai_sonata"), Rarity.COMMON, GameMoney(3_000))
    private val samePrice = DeliveryOffer.Purchase(DeliveryOfferId("offer-2"), VehicleId("hyundai_avante"), Rarity.ADVANCED, GameMoney(3_000))
    private val list = DeliveryList(1, source, day, ContentVersion("content-test"), rule, listOf(income, purchase, samePrice), 3)
    private val proposed = DeliveryProposed(DestinationKey("destination-a"), EstimatedRouteDistance(20_000, source), list, list.offers)
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private lateinit var ledger: DeliveryLedger

    private fun open() = SqliteGameChangeStore(context, source, name)

    override fun setUp() {
        super.setUp()
        name = "delivery-test-" + System.nanoTime() + ".db"
        store = open()
        ok(store.atomic { DeliveryLedger.createTables(it); ContractResult.Success(Unit) })
        ok(store.commitGameChange(GameChange(RequestId("seed"), TransactionKind.GARAGE, source, rule,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(10_000)))))
        ledger = DeliveryLedger(store)
        assertEquals(list, ok(ledger.saveList(list)))
    }

    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }

    private fun reopen() { store.close(); store = open(); ledger = DeliveryLedger(store) }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    private fun balance() = ok(store.balance()).units

    private fun select(offer: DeliveryOffer, id: String, p: DeliveryProposed = proposed) =
        ledger.select(p, offer.id, RequestId(id))

    fun testListSurvivesReopenAndIsNotReplacedByAnotherDraw() {
        assertEquals(list, ok(ledger.saveList(list)))
        val redrawn = list.copy(offers = listOf(samePrice), nextOfferNumber = 3)
        assertEquals(list, ok(ledger.saveList(redrawn)))
        reopen()
        assertEquals(listOf(list), ok(ledger.lists(day)))
        assertTrue(ok(ledger.lists(day.plusDays(1))).isEmpty())
    }

    fun testListsOfAnotherContentOrRuleVersionAreKeptSeparately() {
        val newContent = list.copy(contentVersion = ContentVersion("content-next"), offers = listOf(samePrice))
        val newRule = list.copy(ruleVersion = RuleVersion("delivery-trial-v1"), offers = listOf(income))
        assertEquals(newContent, ok(ledger.saveList(newContent)))
        assertEquals(newRule, ok(ledger.saveList(newRule)))
        reopen()
        assertEquals(setOf(list, newContent, newRule), ok(ledger.lists(day)).toSet())
    }

    fun testSelectionFromAnUnsavedOrRedrawnListIsRejected() {
        val unsaved = list.copy(band = 2)
        val fromUnsaved = proposed.copy(list = unsaved)
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), select(purchase, "r1", fromUnsaved))
        val redrawn = list.copy(offers = listOf(purchase), nextOfferNumber = 4)
        val fromRedrawn = proposed.copy(list = redrawn, offers = redrawn.offers)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), select(purchase, "r1", fromRedrawn))
        assertEquals(10_000, balance())
        assertNull(ok(ledger.active()))
        ok(select(purchase, "r1"))
    }

    fun testPurchaseCommitsPaymentHistoryAndActiveTogetherAndReplays() {
        val commit = ok(select(purchase, "r1"))
        val settlement = requireNotNull(commit.settlement)
        assertEquals(7_000, balance())
        assertEquals(MoneyDirection.DEBIT, settlement.transaction.money?.direction)
        assertEquals(purchase, commit.selected.active.offer)
        reopen()
        assertEquals(commit.selected.active, ok(ledger.active()))
        val again = ok(select(purchase, "r1"))
        assertEquals(settlement.settlementId, again.settlement?.settlementId)
        assertEquals(commit.selected, again.selected)
        assertEquals(7_000, balance())
    }

    fun testSameRequestWithAnotherCandidateOrContentConflicts() {
        ok(select(purchase, "r1"))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), select(samePrice, "r1"))
        val elsewhere = proposed.copy(destination = DestinationKey("destination-b"))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), select(purchase, "r1", elsewhere))
        val otherVersion = proposed.copy(list = list.copy(contentVersion = ContentVersion("content-other")))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), select(purchase, "r1", otherVersion))
        assertEquals(7_000, balance())
        assertEquals(purchase, ok(ledger.active())?.offer)
    }

    fun testAnotherRequestWhileActiveIsRejectedWithoutChange() {
        ok(select(income, "r1"))
        assertEquals(ContractResult.Failure(ContractError.CAPACITY_EXCEEDED), select(purchase, "r2"))
        assertEquals(10_000, balance())
        assertEquals(income, ok(ledger.active())?.offer)
    }

    fun testFinishedRequestReplayReturnsOldResultWithoutReactivating() {
        val first = ok(select(purchase, "r1"))
        ok(store.atomic { ledger.finishActive(it, RequestId("r1")) })
        assertNull(ok(ledger.active()))
        val replay = ok(select(purchase, "r1"))
        assertEquals(first.settlement?.settlementId, replay.settlement?.settlementId)
        assertNull(ok(ledger.active()))
        assertEquals(7_000, balance())
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.atomic { ledger.finishActive(it, RequestId("r1")) })
    }

    fun testIncomeSelectionStoresHistoryWithoutMoneyAndReplays() {
        val commit = ok(select(income, "r1"))
        assertNull(commit.settlement)
        assertNull(commit.selected.payment)
        assertEquals(10_000, balance())
        reopen()
        assertEquals(commit, ok(select(income, "r1")))
        assertEquals(10_000, balance())
    }

    fun testRequestIdAlreadyUsedByLedgerIsConflict() {
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), select(income, "seed"))
        assertNull(ok(ledger.active()))
    }

    fun testInsufficientFundsChangesNothing() {
        val expensive = purchase.copy(price = GameMoney(20_000))
        val far = list.copy(band = 2, offers = listOf(expensive), nextOfferNumber = 2)
        ok(ledger.saveList(far))
        val p = proposed.copy(list = far, offers = far.offers)
        assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS), select(expensive, "r1", p))
        assertEquals(10_000, balance())
        assertNull(ok(ledger.active()))
        ok(select(income, "r1"))
    }

    fun testFailureBeforeCommitRollsBackPaymentHistoryAndActive() {
        store.beforeCommit = { throw SQLiteException("주입") }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), select(purchase, "r1"))
        store.beforeCommit = null
        reopen()
        assertEquals(10_000, balance())
        assertNull(ok(ledger.active()))
        assertNotNull(ok(select(purchase, "r1")).settlement)
        assertEquals(7_000, balance())
    }

    fun testSourceMismatchIsRejected() {
        val vehicleSource = proposed.copy(estimated = EstimatedRouteDistance(20_000, InputSource.VEHICLE))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), select(purchase, "r1", vehicleSource))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ledger.saveList(list.copy(source = InputSource.VEHICLE)))
        assertEquals(10_000, balance())
    }

    fun testCorruptStoredListIsStorageFailure() {
        ok(ledger.saveList(list))
        ok(store.atomic { db ->
            db.execSQL("UPDATE delivery_lists SET payload = replace(payload, '\"next\":3', '\"next\":\"3\"')")
            ContractResult.Success(Unit)
        })
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), ledger.lists(day))
    }

    fun testReplaceListRequiresTheStoredList() {
        ok(ledger.saveList(list))
        val updated = list.copy(offers = listOf(purchase, samePrice), nextOfferNumber = 4)
        val stale = list.copy(offers = listOf(income))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.atomic { ledger.replaceList(it, stale, updated) })
        assertEquals(updated, ok(store.atomic { ledger.replaceList(it, list, updated) }))
        assertEquals(listOf(updated), ok(ledger.lists(day)))
    }

    fun testTwoConnectionsSelectingAtOnceLeaveOneActive() {
        val other = open()
        val otherLedger = DeliveryLedger(other)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit(Callable { gate.await(); ledger.select(proposed, purchase.id, RequestId("a")) })
            val b = pool.submit(Callable { gate.await(); otherLedger.select(proposed, samePrice.id, RequestId("b")) })
            gate.countDown()
            val results = listOf(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))
            assertEquals(1, results.count { it is ContractResult.Success })
            assertEquals(1, results.count { it == ContractResult.Failure(ContractError.CAPACITY_EXCEEDED) })
            reopen()
            assertEquals(7_000, balance())
            assertNotNull(ok(ledger.active()))
        } finally { pool.shutdownNow(); other.close() }
    }
}
