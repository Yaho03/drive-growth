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

    /** 공용 API가 새로 막는 예약 ID의 과거 오염 행을 시험 DB에 명시 주입한다. */
    private fun injectLegacyChange(change: GameChange) {
        ok(store.commitGameChange(change.copy(requestId = RequestId("legacy-seed"))))
        val id = change.requestId.value
        sql("UPDATE settlements SET request_id='$id', command=json_set(command,'$.request','$id'), result=json_set(result,'$.request','$id') WHERE request_id='legacy-seed'")
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
        ok(ledger.beginRoute(RequestId(id), routeOf(id), start))
    }

    /** 경로 ID는 다시 쓸 수 없어 의뢰마다 다른 경로를 쓴다. */
    private fun routeOf(id: String) = if (id == "r1") route else RouteId("route-$id")

    private fun arrival(at: Long = start.value + 60_000, r: RouteId = route) =
        Observation.Available(DeliveryArrivalEvidence(r, source, EpochMillis(at)))

    private fun settle(
        evidence: Observation<DeliveryArrivalEvidence>,
        now: Long = start.value + 120_000,
        id: String = "r1",
        next: (DeliveryList, DeliveryOfferId) -> ContractResult<DeliveryList> = refill,
    ) = ledger.settleArrival(rules, RequestId(id), evidence, EpochMillis(now), next)

    private val keep: (DeliveryList, DeliveryOfferId) -> ContractResult<DeliveryList> = { l, _ -> ContractResult.Success(l) }

    private fun sql(vararg statements: String) = ok(store.atomic { db -> statements.forEach(db::execSQL); ContractResult.Success(Unit) })

    private val tables = listOf(
        "account", "settlements", "vehicles", "delivery_lists", "delivery_requests", "delivery_active", "delivery_routes",
        "delivery_outcomes", "delivery_held", "delivery_hold_outcomes", "delivery_changes", "delivery_route_history",
        "driving_days", "driving_drives",
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

    private fun assertStorageFailureKeepsRows(vararg calls: () -> ContractResult<*>) {
        val before = dump()
        calls.forEach { assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), it()) }
        reopen()
        calls.forEach { assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), it()) }
        assertEquals(before, dump())
    }

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
        val again = ok(settle(arrival()))
        assertEquals(commit, again)
        assertEquals(commit.settlement?.settlementId, again.settlement?.settlementId)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), settle(arrival(), now = start.value + 120_001))
        assertEquals(10_400, balance())
        assertEquals(stored, ok(ledger.lists(today)).single())
    }

    fun testPurchaseArrivalGivesVehicleWithoutMoneyChange() {
        val ownerships = mutableListOf<FirstCollectionOwnership>()
        store.onFirstCollectionOwnership = { ownerships += it }
        choose(purchase)
        assertTrue(ownerships.isEmpty())
        assertEquals(7_000, balance())
        val commit = ok(settle(arrival()))
        assertTrue(commit.outcome is DeliveryOutcome.VehicleDelivered)
        assertEquals(7_000, balance())
        val car = owned().single()
        assertEquals(OwnedVehicleId("delivery/r1"), car.ownedVehicleId)
        assertEquals(Rarity.RARE, car.rarity)
        assertEquals(GameMoney(3_000), car.acquisitionCost)
        assertEquals(VehicleAcquisitionSource.PURCHASE_DELIVERY, car.acquisitionSource)
        assertEquals(io.drivegrowth.core.garage.CollectionKey(car.vehicleId, car.rarity), ownerships.single().key)
        assertEquals(commit, ok(settle(arrival())))
        assertEquals(1, ownerships.size)
        assertNull(ok(ledger.active()))
    }

    fun testFullGarageHoldsVehicleThenClaimsWhenSlotOpens() {
        val ownerships = mutableListOf<FirstCollectionOwnership>()
        store.onFirstCollectionOwnership = { ownerships += it }
        fillGarage(3)
        choose(purchase)
        val held = (ok(settle(arrival())).outcome as DeliveryOutcome.VehicleHeld).held
        assertEquals(3, owned().size)
        assertEquals(listOf(held), ok(ledger.held()))
        assertNull(ok(ledger.active()))
        assertTrue(ok(ledger.resolveHold(rules, RequestId("r1"), EpochMillis(held.deadline.value - 1))).outcome is HoldOutcome.StillHeld)
        assertTrue(ownerships.isEmpty())
        ok(store.commitGameChange(GameChange(RequestId("sell0"), TransactionKind.GARAGE, source, rule,
            vehicle = VehicleWrite.Update(OwnedVehicleId("car0"), Revision(0), 1, VehicleLifecycle.SOLD, 0))))
        reopen()
        store.onFirstCollectionOwnership = { ownerships += it }
        val claim = ok(ledger.resolveHold(rules, RequestId("r1"), EpochMillis(held.deadline.value - 1)))
        assertTrue(claim.outcome is HoldOutcome.Claimed)
        assertTrue(owned().any { it.ownedVehicleId == OwnedVehicleId("delivery/r1") })
        assertEquals(io.drivegrowth.core.garage.CollectionKey(VehicleId("hyundai_sonata"), Rarity.RARE), ownerships.single().key)
        assertTrue(ok(ledger.held()).isEmpty())
        reopen()
        store.onFirstCollectionOwnership = { fail("보관 수령 재생은 신규 소유가 아니다") }
        assertEquals(claim, ok(ledger.resolveHold(rules, RequestId("r1"), EpochMillis(held.deadline.value - 1))))
        assertEquals(claim, ok(ledger.holdResult(RequestId("r1"))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), ledger.resolveHold(rules, RequestId("r1"), held.deadline))
        assertEquals(7_000, balance())
        assertEquals(3, owned().size)
    }

    fun testExpiredHoldRefundsAfterFee() {
        fillGarage(3)
        choose(purchase)
        val held = (ok(settle(arrival())).outcome as DeliveryOutcome.VehicleHeld).held
        val refund = ok(ledger.resolveHold(rules, RequestId("r1"), held.deadline))
        assertTrue(refund.outcome is HoldOutcome.Refunded)
        assertEquals(7_000 + 2_850, balance())
        assertEquals(3, owned().size)
        assertTrue(ok(ledger.held()).isEmpty())
        reopen()
        assertEquals(refund, ok(ledger.resolveHold(rules, RequestId("r1"), held.deadline)))
        assertEquals(7_000 + 2_850, balance())
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
        val expired = ok(settle(Observation.Missing(MissingReason.NO_DATA), now = start.value + dayMs, id = "r2"))
        assertEquals(DeliveryOutcome.IncomeExpired, expired.outcome)
        assertNull(expired.settlement)
        assertEquals(10_000, balance())
        assertNull(ok(ledger.active()))
    }

    fun testNewRouteKeepsDeadlineStartAndRejectsOldRouteEvidence() {
        choose(purchase)
        val moved = ok(ledger.beginRoute(RequestId("r1"), RouteId("route-2"), EpochMillis(start.value + 30_000), replacing = route))
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

    fun testFeatureOwnedRequestCannotReplayThroughGeneralWriter() {
        val drive = ok(driving.settle(drivingRules, ConfirmedDrive("owned", today, ObservedDistance(1_000, source))))
        val t = checkNotNull(drive.settlement).transaction
        val sameChange = GameChange(t.requestId, t.kind, t.source, t.ruleVersion, t.money)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGameChange(sameChange))
        assertEquals(drive, ok(driving.settle(drivingRules, ConfirmedDrive("owned", today, ObservedDistance(1_000, source)))))
        assertEquals(10_010, balance())
    }

    fun testDriveIdUsedByAnotherLedgerEntryOrSourceIsRejected() {
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            store.commitGameChange(GameChange(RequestId("drive/d1"), TransactionKind.GARAGE, source, rule,
                MoneyChange(MoneyDirection.CREDIT, GameMoney(1)))))
        ok(driving.settle(drivingRules, ConfirmedDrive("d1", today, ObservedDistance(1_000, source))))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),
            driving.settle(drivingRules, ConfirmedDrive("d2", today, ObservedDistance(1_000, InputSource.VEHICLE))))
    }

    fun testEveryFinishedArrivalReplaysAfterLostResponseEvenWithNewActiveRequest() {
        fun lostResponse(offer: DeliveryOffer, id: String, evidence: Observation<DeliveryArrivalEvidence>, now: Long) {
            choose(offer, id)
            val first = ok(settle(evidence, now, id, keep))
            reopen()
            ok(ledger.select(proposed, income.id, RequestId("$id-next")))
            val balance = balance(); val cars = owned(); val lists = ok(ledger.lists(today)); val held = ok(ledger.held())
            assertEquals(first, ok(settle(evidence, now, id, keep)))
            assertEquals(first, ok(ledger.arrivalResult(RequestId(id))))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), settle(evidence, now + 1, id))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), settle(Observation.Missing(MissingReason.STALE), now, id))
            assertEquals(balance, balance()); assertEquals(cars, owned()); assertEquals(lists, ok(ledger.lists(today))); assertEquals(held, ok(ledger.held()))
            assertEquals(RequestId("$id-next"), ok(ledger.active())?.requestId)
            ok(store.atomic { ledger.finishActive(it, RequestId("$id-next")) })
        }
        lostResponse(income, "a", arrival(r = routeOf("a")), start.value + 120_000)
        lostResponse(purchase, "b", arrival(r = routeOf("b")), start.value + 120_000)
        lostResponse(purchase, "c", Observation.Missing(MissingReason.NO_DATA), start.value + dayMs)
        lostResponse(income, "d", Observation.Missing(MissingReason.NO_DATA), start.value + dayMs)
        fillGarage(3)
        lostResponse(purchase, "e", arrival(r = routeOf("e")), start.value + 120_000)
        assertNull(ok(ledger.arrivalResult(RequestId("never"))))
    }

    fun testArrivalForAnotherRequestOrUsedSettleIdIsRejected() {
        choose(purchase)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), settle(arrival(), id = "other"))
        injectLegacyChange(GameChange(RequestId("delivery/r1/settle"), TransactionKind.GARAGE, source, rule,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(1))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), settle(arrival()))
        assertEquals(7_001, balance())
        assertEquals(purchase, ok(ledger.active())?.offer)
        assertTrue(owned().isEmpty())
    }

    fun testArrivalReplayRejectsLossyRawMoneyFromSharedDecoder() {
        choose(income)
        ok(settle(arrival()))
        sql("CREATE TABLE raw_money_backup AS SELECT * FROM settlements WHERE request_id='delivery/r1/settle'")
        for (raw in listOf("400.75", "\"400\"", "400.0", "4e2", "9223372036854775808")) {
            sql("UPDATE settlements SET result=replace(result,'\"amount\":400','\"amount\":$raw') WHERE request_id='delivery/r1/settle'")
            assertStorageFailureKeepsRows({ settle(arrival()) }, { ledger.arrivalResult(RequestId("r1")) })
            sql("DELETE FROM settlements WHERE request_id='delivery/r1/settle'",
                "INSERT INTO settlements SELECT * FROM raw_money_backup")
        }
        assertNotNull(ok(ledger.arrivalResult(RequestId("r1"))))
    }

    fun testStoredArrivalThatDoesNotMatchItsCommandIsStorageFailure() {
        choose(purchase)
        ok(settle(arrival()))
        val call = { settle(arrival()) }
        val read = { ledger.arrivalResult(RequestId("r1")) }
        sql("CREATE TABLE backup AS SELECT * FROM delivery_outcomes")
        val capacity = ok(store.atomic { db -> ContractResult.Success(db.rawQuery("SELECT capacity FROM shop", null).use { it.moveToFirst(); it.getLong(0) }) })
        for (corruption in listOf(
            "UPDATE delivery_outcomes SET result = replace(result, '\"slots\":$capacity', '\"slots\":0')",
            "UPDATE delivery_outcomes SET result = replace(result, 'VEHICLE_DELIVERED', 'PURCHASE_REFUNDED')",
            "UPDATE delivery_outcomes SET result = replace(result, '\"route\":\"route-1\"', '\"route\":\"route-x\"')",
            "UPDATE delivery_outcomes SET command = replace(command, '\"now\":1120000', '\"now\":\"1120000\"')",
            "UPDATE delivery_outcomes SET result = 7",
        )) {
            sql(corruption)
            assertStorageFailureKeepsRows(call, read)
            sql("DELETE FROM delivery_outcomes", "INSERT INTO delivery_outcomes SELECT * FROM backup")
        }
        sql("CREATE TABLE settle_backup AS SELECT * FROM settlements WHERE request_id = 'delivery/r1/settle'")
        sql("UPDATE settlements SET result = replace(result, '\"kind\":\"DELIVERY\"', '\"kind\":\"GARAGE\"') WHERE request_id = 'delivery/r1/settle'")
        assertStorageFailureKeepsRows(call, read)
        sql("DELETE FROM settlements WHERE request_id = 'delivery/r1/settle'")
        assertStorageFailureKeepsRows(call, read)
        sql("INSERT INTO settlements SELECT * FROM settle_backup")
        assertEquals(VehicleId("hyundai_sonata"), ((ok(call()).outcome as DeliveryOutcome.VehicleDelivered).change.vehicle as VehicleWrite.Add).vehicle.vehicleId)
    }

    fun testFinishedArrivalWithoutMoneyButWithSettlementIsStorageFailure() {
        choose(income)
        ok(settle(Observation.Missing(MissingReason.NO_DATA), now = start.value + dayMs))
        injectLegacyChange(GameChange(RequestId("delivery/r1/settle"), TransactionKind.DELIVERY, source, rule,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(400))))
        assertStorageFailureKeepsRows({ settle(Observation.Missing(MissingReason.NO_DATA), now = start.value + dayMs) })
    }

    fun testHeldRowOrHoldResultThatDoesNotMatchArrivalIsStorageFailure() {
        fillGarage(3)
        choose(purchase)
        ok(settle(arrival()))
        sql("UPDATE delivery_held SET payload = replace(payload, '\"price\":3000', '\"price\":9000')")
        assertStorageFailureKeepsRows({ ledger.held() }, { ledger.resolveHold(rules, RequestId("r1"), start) })
        sql("UPDATE delivery_held SET payload = replace(payload, '\"price\":9000', '\"price\":3000')")
        val now = EpochMillis(start.value + dayMs + 60_000 + 1)
        val refund = ok(ledger.resolveHold(rules, RequestId("r1"), now))
        assertTrue(refund.outcome is HoldOutcome.Refunded)
        sql("UPDATE delivery_hold_outcomes SET result = replace(result, 'REFUNDED', 'CLAIMED')")
        assertStorageFailureKeepsRows({ ledger.resolveHold(rules, RequestId("r1"), now) }, { ledger.holdResult(RequestId("r1")) })
    }

    fun testDriveWithZeroPayReplaysWithoutSettlement() {
        ok(driving.settle(drivingRules, ConfirmedDrive("d1", today, ObservedDistance(500_000, source))))
        val zero = ConfirmedDrive("d2", today, ObservedDistance(3_000, source))
        val first = ok(driving.settle(drivingRules, zero))
        assertEquals(GameMoney(0), first.amount)
        assertNull(first.settlement)
        reopen()
        assertEquals(first, ok(driving.settle(drivingRules, zero)))
    }

    fun testStoredDriveThatDoesNotMatchItsCommandOrPaymentIsStorageFailure() {
        val drive = ConfirmedDrive("d1", today, ObservedDistance(30_000, source))
        ok(driving.settle(drivingRules, drive))
        val call = { driving.settle(drivingRules, drive) }
        sql("CREATE TABLE backup AS SELECT * FROM driving_drives", "CREATE TABLE settle_backup AS SELECT * FROM settlements WHERE request_id = 'drive/d1'")
        for (corruption in listOf(
            "UPDATE driving_drives SET amount = 'broken'",
            "UPDATE driving_drives SET amount = 0",
            "UPDATE driving_drives SET rewarded = 29000",
            "UPDATE driving_drives SET recorded = 31000",
            "UPDATE driving_drives SET day_rewarded = 40000",
            "UPDATE driving_drives SET day = '2026-02-30'",
            "UPDATE settlements SET result = replace(result, '\"amount\":300', '\"amount\":0') WHERE request_id = 'drive/d1'",
            "UPDATE settlements SET result = replace(result, '\"kind\":\"DRIVING\"', '\"kind\":\"GARAGE\"') WHERE request_id = 'drive/d1'",
            "DELETE FROM settlements WHERE request_id = 'drive/d1'",
        ) + listOf("300.75", "300.0", "\"300\"", "3e2", "92233720368547758070").map {
            // 공용 금액 해석(#136)이 소수·문자열·지수·범위 초과를 정수로 보정하지 않는지 주행 소비 경로에서 확인한다.
            "UPDATE settlements SET result = replace(result, '\"amount\":300', '\"amount\":$it') WHERE request_id = 'drive/d1'"
        }) {
            val clean = dump()
            sql(corruption)
            assertTrue("손상이 들어가지 않음: $corruption", dump() != clean)
            assertStorageFailureKeepsRows(call)
            sql("DELETE FROM driving_drives", "INSERT INTO driving_drives SELECT * FROM backup",
                "DELETE FROM settlements WHERE request_id = 'drive/d1'", "INSERT INTO settlements SELECT * FROM settle_backup")
        }
        assertEquals(GameMoney(300), ok(call()).amount)
        sql("UPDATE driving_days SET rewarded = 'broken'")
        assertStorageFailureKeepsRows({ driving.day(today) })
    }

    fun testArrivalPaymentWithFractionalOrTextAmountIsStorageFailure() {
        choose(income)
        ok(settle(arrival()))
        val call = { settle(arrival()) }
        sql("CREATE TABLE settle_backup AS SELECT * FROM settlements WHERE request_id = 'delivery/r1/settle'")
        for (raw in listOf("400.5", "400.0", "\"400\"", "4e2")) {
            val clean = dump()
            sql("UPDATE settlements SET result = replace(result, '\"amount\":400', '\"amount\":$raw') WHERE request_id = 'delivery/r1/settle'")
            assertTrue("손상이 들어가지 않음: $raw", dump() != clean)
            assertStorageFailureKeepsRows(call, { ledger.arrivalResult(RequestId("r1")) })
            sql("DELETE FROM settlements WHERE request_id = 'delivery/r1/settle'", "INSERT INTO settlements SELECT * FROM settle_backup")
        }
        assertTrue(ok(call()).outcome is DeliveryOutcome.IncomeCompleted)
        assertEquals(10_400, balance())
    }
}
