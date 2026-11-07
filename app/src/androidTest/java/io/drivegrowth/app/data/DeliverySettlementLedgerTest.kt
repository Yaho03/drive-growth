package io.drivegrowth.app.data

import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import io.drivegrowth.core.driving.ConfirmedDrive
import io.drivegrowth.core.driving.DrivingDay
import io.drivegrowth.core.driving.DrivingIncomeRules
import java.time.LocalDate

@Suppress("DEPRECATION")
class DeliverySettlementLedgerTest : InstrumentationTestCase() {
    private val source = InputSource.SIMULATION
    private val rule = RuleVersion("delivery-trial-v0")
    private val dayMs = 24 * 60 * 60 * 1000L
    private val today = LocalDate.of(2026, 11, 7)
    private val rules = DeliverySettlementRules(RuleVersion("delivery-settle-trial-v0"), dayMs, dayMs, 5)
    private val settlement = DeliverySettlement(rules)
    private val drivingRules = DrivingIncomeRules(RuleVersion("driving-trial-v0"), 500_000, GameMoney(10))
    private val income = DeliveryOffer.Income(DeliveryOfferId("offer-0"), VehicleId("hyundai_casper"), GameMoney(400))
    private val purchase = DeliveryOffer.Purchase(DeliveryOfferId("offer-1"), VehicleId("hyundai_sonata"), Rarity.RARE, GameMoney(3_000))
    private val list = DeliveryList(1, source, today, ContentVersion("content-test"), rule, listOf(income, purchase), 2)
    private val proposed = DeliveryProposed(DestinationKey("destination-a"), EstimatedRouteDistance(20_000, source), list, list.offers)
    private val route = RouteId("route-1")
    private val start = EpochMillis(1_000_000)
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private lateinit var ledger: DeliveryLedger
    private lateinit var driving: DrivingLedger

    /** 완료한 자리에 새 수익 의뢰를 채우는 시험용 교체. */
    private val refill: (DeliveryList, DeliveryOfferId) -> ContractResult<DeliveryList> = { l, id ->
        val replacement = DeliveryOffer.Income(DeliveryOfferId("offer-${l.nextOfferNumber}"), VehicleId("hyundai_avante"), GameMoney(500))
        ContractResult.Success(l.copy(offers = l.offers.map { if (it.id == id) replacement else it }, nextOfferNumber = l.nextOfferNumber + 1))
    }

    private fun open() = SqliteGameChangeStore(context, source, name)

    override fun setUp() {
        super.setUp()
        name = "settle-test-" + System.nanoTime() + ".db"
        store = open()
        ok(store.commitGameChange(GameChange(RequestId("seed"), TransactionKind.GARAGE, source, rule,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(10_000)))))
        bind()
        ok(ledger.saveList(list))
    }

    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }

    private fun bind() { ledger = store.delivery; driving = store.driving }

    private fun reopen() { store.close(); store = open(); bind() }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    private fun balance() = ok(store.balance()).units

    private fun owned() = ok(store.listOwnedVehicles())

    private fun choose(offer: DeliveryOffer, id: String = "r1") {
        ok(ledger.select(proposed, offer.id, RequestId(id)))
        ok(ledger.beginRoute(RequestId(id), route, start))
    }

    private fun arrival(at: Long = start.value + 60_000, r: RouteId = route) =
        Observation.Available(DeliveryArrivalEvidence(r, source, EpochMillis(at)))

    private fun settle(evidence: Observation<DeliveryArrivalEvidence>, now: Long = start.value + 120_000) =
        ledger.settleArrival(settlement, evidence, EpochMillis(now), refill)

    private fun fillGarage(count: Int) = repeat(count) { i ->
        ok(store.commitGameChange(GameChange(RequestId("add$i"), TransactionKind.GARAGE, source, rule, vehicle = VehicleWrite.Add(
            OwnedVehicle(OwnedVehicleId("car$i"), VehicleId("hyundai_casper"), 1, Revision(0), VehicleLifecycle.OWNED,
                Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.GACHA)))))
    }

    fun testIncomeArrivalPaysRewardEndsActiveAndRefillsOnlyThatSlot() {
        choose(income)
        val commit = ok(settle(arrival()))
        assertTrue(commit.outcome is DeliveryOutcome.IncomeCompleted)
        assertEquals(10_400, balance())
        assertNull(ok(ledger.active()))
        assertNull(ok(ledger.inProgress()))
        val stored = ok(ledger.lists(today)).single()
        assertEquals(listOf(DeliveryOfferId("offer-2"), purchase.id), stored.offers.map { it.id })
        assertEquals(3, stored.nextOfferNumber)
        reopen()
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), settle(arrival()))
        assertEquals(10_400, balance())
    }

    fun testPurchaseArrivalGivesVehicleWithoutMoneyChange() {
        choose(purchase)
        assertEquals(7_000, balance())
        val commit = ok(settle(arrival()))
        assertTrue(commit.outcome is DeliveryOutcome.VehicleDelivered)
        assertEquals(7_000, balance())
        val car = owned().single()
        assertEquals(OwnedVehicleId("delivery/r1"), car.ownedVehicleId)
        assertEquals(Rarity.RARE, car.rarity)
        assertEquals(GameMoney(3_000), car.acquisitionCost)
        assertEquals(VehicleAcquisitionSource.PURCHASE_DELIVERY, car.acquisitionSource)
        assertNull(ok(ledger.active()))
    }

    fun testFullGarageHoldsVehicleThenClaimsWhenSlotOpens() {
        fillGarage(3)
        choose(purchase)
        val held = (ok(settle(arrival())).outcome as DeliveryOutcome.VehicleHeld).held
        assertEquals(3, owned().size)
        assertEquals(listOf(held), ok(ledger.held()))
        assertNull(ok(ledger.active()))
        assertTrue(ok(ledger.resolveHold(settlement, RequestId("r1"), EpochMillis(held.deadline.value - 1))).outcome is HoldOutcome.StillHeld)
        ok(store.commitGameChange(GameChange(RequestId("sell0"), TransactionKind.GARAGE, source, rule,
            vehicle = VehicleWrite.Update(OwnedVehicleId("car0"), Revision(0), 1, VehicleLifecycle.SOLD, 0))))
        reopen()
        val claim = ok(ledger.resolveHold(settlement, RequestId("r1"), EpochMillis(held.deadline.value - 1)))
        assertTrue(claim.outcome is HoldOutcome.Claimed)
        assertTrue(owned().any { it.ownedVehicleId == OwnedVehicleId("delivery/r1") })
        assertTrue(ok(ledger.held()).isEmpty())
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            ledger.resolveHold(settlement, RequestId("r1"), EpochMillis(held.deadline.value - 1)))
        assertEquals(7_000, balance())
    }

    fun testExpiredHoldRefundsAfterFee() {
        fillGarage(3)
        choose(purchase)
        val held = (ok(settle(arrival())).outcome as DeliveryOutcome.VehicleHeld).held
        val refund = ok(ledger.resolveHold(settlement, RequestId("r1"), held.deadline))
        assertTrue(refund.outcome is HoldOutcome.Refunded)
        assertEquals(7_000 + 2_850, balance())
        assertEquals(3, owned().size)
        assertTrue(ok(ledger.held()).isEmpty())
    }

    fun testMissingOrOldRouteEvidenceWaitsWithoutChange() {
        choose(purchase)
        listOf(Observation.Missing(MissingReason.NO_DATA), arrival(r = RouteId("route-old"))).forEach {
            assertTrue(ok(settle(it)).outcome is DeliveryOutcome.AwaitingEvidence)
        }
        assertEquals(7_000, balance())
        assertEquals(purchase, ok(ledger.active())?.offer)
        assertTrue(owned().isEmpty())
        assertEquals(list, ok(ledger.lists(today)).single())
    }

    fun testNoArrivalByDeadlineRefundsPurchaseAndEndsIncome() {
        choose(purchase)
        val refund = ok(settle(Observation.Missing(MissingReason.NO_DATA), now = start.value + dayMs))
        assertTrue(refund.outcome is DeliveryOutcome.PurchaseRefunded)
        assertEquals(10_000, balance())
        assertNull(ok(ledger.active()))
        assertEquals(list, ok(ledger.lists(today)).single())
        choose(income, "r2")
        val expired = ok(settle(Observation.Missing(MissingReason.NO_DATA), now = start.value + dayMs))
        assertEquals(DeliveryOutcome.IncomeExpired, expired.outcome)
        assertNull(expired.settlement)
        assertEquals(10_000, balance())
        assertNull(ok(ledger.active()))
    }

    fun testNewRouteKeepsDeadlineStartAndRejectsOldRouteEvidence() {
        choose(purchase)
        val moved = ok(ledger.beginRoute(RequestId("r1"), RouteId("route-2"), EpochMillis(start.value + 30_000)))
        assertEquals(start, moved.startedAt)
        assertTrue(ok(settle(arrival(r = route))).outcome is DeliveryOutcome.AwaitingEvidence)
        assertTrue(ok(settle(arrival(r = RouteId("route-2")))).outcome is DeliveryOutcome.VehicleDelivered)
    }

    fun testRouteOnlyForTheActiveRequest() {
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), ledger.beginRoute(RequestId("r1"), route, start))
        ok(ledger.select(proposed, income.id, RequestId("r1")))
        assertNull(ok(ledger.inProgress()))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), settle(arrival()))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.beginRoute(RequestId("other"), route, start))
    }

    fun testFailureBeforeCommitKeepsActiveMoneyAndList() {
        choose(income)
        store.beforeCommit = { throw SQLiteException("주입") }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), settle(arrival()))
        store.beforeCommit = null
        reopen()
        assertEquals(10_000, balance())
        assertEquals(income, ok(ledger.active())?.offer)
        assertEquals(list, ok(ledger.lists(today)).single())
        assertTrue(ok(settle(arrival())).outcome is DeliveryOutcome.IncomeCompleted)
    }

    fun testDriveIsPaidOnceAndDayStateIsNotAddedTwice() {
        val drive = ConfirmedDrive("d1", today, ObservedDistance(30_000, source))
        val first = ok(driving.settle(drivingRules, drive))
        assertEquals(GameMoney(300), first.amount)
        assertEquals(10_300, balance())
        reopen()
        val again = ok(driving.settle(drivingRules, drive))
        assertEquals(first.settlement?.settlementId, again.settlement?.settlementId)
        assertEquals(10_300, balance())
        assertEquals(DrivingDay(today, 30_000), ok(driving.day(today)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            driving.settle(drivingRules, drive.copy(distance = ObservedDistance(31_000, source))))
    }

    fun testDailyCapAcrossDrivesAndDepartureDay() {
        ok(driving.settle(drivingRules, ConfirmedDrive("d1", today, ObservedDistance(499_000, source))))
        val second = ok(driving.settle(drivingRules, ConfirmedDrive("d2", today, ObservedDistance(2_000, source))))
        assertEquals(2_000L, second.recordedMeters)
        assertEquals(1_000L, second.rewardedMeters)
        val capped = ok(driving.settle(drivingRules, ConfirmedDrive("d3", today, ObservedDistance(5_000, source))))
        assertNull(capped.settlement)
        assertEquals(DrivingDay(today, 500_000), ok(driving.day(today)))
        val next = ok(driving.settle(drivingRules, ConfirmedDrive("d4", today.plusDays(1), ObservedDistance(1_000, source))))
        assertEquals(1_000L, next.rewardedMeters)
        assertEquals(10_000 + 4_990 + 10 + 10, balance())
    }

    fun testDriveIdUsedByAnotherLedgerEntryOrSourceIsRejected() {
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            store.commitGameChange(GameChange(RequestId("drive/d1"), TransactionKind.GARAGE, source, rule,
                MoneyChange(MoneyDirection.CREDIT, GameMoney(1)))))
        ok(driving.settle(drivingRules, ConfirmedDrive("d1", today, ObservedDistance(1_000, source))))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),
            driving.settle(drivingRules, ConfirmedDrive("d2", today, ObservedDistance(1_000, InputSource.VEHICLE))))
    }
}
