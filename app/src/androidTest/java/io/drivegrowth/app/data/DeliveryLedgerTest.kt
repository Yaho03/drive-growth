package io.drivegrowth.app.data

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabase
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
        ok(store.commitGameChange(GameChange(RequestId("seed"), TransactionKind.GARAGE, source, rule,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(10_000)))))
        ledger = store.delivery
        assertEquals(list, ok(ledger.saveList(list)))
    }

    fun testIncomeRequestBlocksOtherEntryPointsAndReservedIdsCannotBePreempted() {
        ok(select(income, "income-visit"))
        val credit = GameChange(RequestId("income-visit"), TransactionKind.GARAGE, source, rule,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(9)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGameChange(credit))
        for (id in listOf("delivery/future/settle", "delivery/future/hold", "delivery/future/change", "drive/future")) {
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
                store.commitGameChange(credit.copy(requestId = RequestId(id))))
        }
        assertEquals(10_000, balance())
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), store.atomic {
            store.record(it, credit.copy(requestId = RequestId("wrong-source"), source = InputSource.VEHICLE), "source")
        })
    }

    private val deliveryTables = listOf("delivery_lists", "delivery_requests", "delivery_active", "delivery_routes",
        "delivery_outcomes", "delivery_held", "delivery_changes", "driving_days", "driving_drives", "delivery_route_history", "delivery_hold_outcomes", "material_requests", "repair_requests", "initialization_requests", "initialization_profile", "first_gift_requests", "first_gift_markers", "host_command_checkpoint","quest_events","quest_claims","quest_tracking")

    fun testV4MigrationPreservesExistingMoneyVehiclesDiscoveryAndProgress() {
        val before = ok(store.listUnviewedSettlements())
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO discoveries VALUES ('hyundai_casper','RARE')")
            db.execSQL("INSERT INTO gacha_progress VALUES (1,2,3,4)")
            deliveryTables.forEach { db.execSQL("DROP TABLE $it") }
            db.version = 4
        }
        store = open()
        ledger = store.delivery
        assertEquals(before, ok(store.listUnviewedSettlements()))
        assertEquals(10_000, balance())
        assertEquals(setOf(io.drivegrowth.core.garage.CollectionKey(VehicleId("hyundai_casper"), Rarity.RARE)),
            ok(store.discoveredVehicles()))
        ok(store.read { db ->
            db.rawQuery("SELECT revision,counter,tickets FROM gacha_progress", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0)); assertEquals(3, it.getInt(1)); assertEquals(4, it.getInt(2))
            }
        })
        ok(ledger.saveList(list))
        val saved = ok(select(purchase, "post-migration"))
        reopen()
        assertEquals(saved, ok(select(purchase, "post-migration")))
    }

    fun testV4LegacyGeneralRequestsReplayBeforeNewReservation() {
        val commands = listOf("drive/legacy-record", "delivery/legacy/settle").map { id ->
            GameChange(RequestId(id), TransactionKind.DRIVING, source, rule,
                MoneyChange(MoneyDirection.CREDIT, GameMoney(10)))
        }
        val saved = commands.mapIndexed { index, c ->
            Settlement(SettlementId("legacy-$index"),
                Transaction(TransactionId("tx-$index"), c.requestId, c.kind, c.source, c.ruleVersion, c.money),
                GameMoney(10010L + index * 10), null, EpochMillis(100))
        }
        store.close()
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            deliveryTables.forEach { db.execSQL("DROP TABLE $it") }
            commands.zip(saved).forEach { (c, result) ->
                db.execSQL("INSERT INTO settlements VALUES (?,?,?,?,0)", arrayOf(c.requestId.value,
                    result.settlementId.value, LedgerJson.command(c).toString(), LedgerJson.settlement(result).toString()))
            }
            db.execSQL("UPDATE account SET balance=10020 WHERE id=1")
            db.version = 4
        }
        store = open()
        assertEquals(10020, balance())
        store.close()
        val migrated = file.readBytes()
        store = open()
        commands.zip(saved).forEach { (c, result) ->
            assertEquals(result, ok(store.commitGameChange(c)))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
                store.commitGameChange(c.copy(money = MoneyChange(MoneyDirection.CREDIT, GameMoney(11)))))
            assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
                store.commitGameChange(c.copy(requestId = RequestId(c.requestId.value + "-new"))))
        }
        assertEquals(10020, balance())
        store.close()
        assertTrue(migrated.contentEquals(file.readBytes()))
        store = open()
        ledger = store.delivery
    }

    fun testMigrationFailurePreservesOriginalFile() {
        store.close()
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db -> db.version = 4 }
        val before = file.readBytes()
        store = open()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.balance())
        store.close()
        assertTrue(before.contentEquals(file.readBytes()))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db -> db.version = 12 }
        store = open()
    }

    fun testTwoConnectionsOnlyActivateOneSelection() {
        val other = open()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            val jobs = listOf(store,other).mapIndexed { i,s -> pool.submit(java.util.concurrent.Callable {
                gate.await()
                s.delivery.select(proposed, purchase.id, RequestId("race-$i"))
            }) }
            gate.countDown()
            val results = jobs.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is ContractResult.Success })
            assertEquals(7_000, balance())
            assertNotNull(ok(store.delivery.active()))
        } finally { pool.shutdownNow(); other.close() }
    }

    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }

    private fun reopen() { store.close(); store = open(); ledger = store.delivery }

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

    /** 처음 순회와 그 뒤 순회 결과가 다른 목록. 입력을 한 번만 읽는지 확인한다. */
    private class Flipping(private val first: List<DeliveryOffer>, private val later: List<DeliveryOffer>) : AbstractList<DeliveryOffer>() {
        var reads = 0
        override val size get() = if (reads == 0) first.size else later.size
        override fun get(index: Int) = (if (reads == 0) first else later)[index]
        override fun iterator(): Iterator<DeliveryOffer> = (if (reads++ == 0) first else later).iterator()
    }

    private fun sql(vararg statements: String) = ok(store.atomic { db -> statements.forEach(db::execSQL); ContractResult.Success(Unit) })

    /** 표의 모든 칸을 형식까지 포함해 읽는다. 실패한 호출이 원본을 바꾸지 않았는지 본다. */
    private fun dump(table: String): String = ok(store.atomic { db ->
        val columns = db.rawQuery("SELECT * FROM $table LIMIT 0", null).use { it.columnNames.toList() }
        val quoted = columns.joinToString(" || '|' || ") { "quote($it)" }
        ContractResult.Success(db.rawQuery("SELECT $quoted FROM $table ORDER BY rowid", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }.joinToString("\n")
        })
    })

    private fun assertStorageFailureKeepsRows(call: () -> ContractResult<*>) {
        val before = listOf(dump("delivery_requests"), dump("delivery_active"), dump("settlements"), dump("account"), dump("delivery_lists"))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), call())
        reopen()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), call())
        assertEquals(before, listOf(dump("delivery_requests"), dump("delivery_active"), dump("settlements"), dump("account"), dump("delivery_lists")))
    }

    fun testInputIsReadOnceForCommandListCheckAndPayment() {
        val flipping = Flipping(listOf(income), listOf(income, purchase))
        val changing = proposed.copy(offers = flipping)
        flipping.reads = 0
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), ledger.select(changing, purchase.id, RequestId("r1")))
        assertEquals(10_000, balance())
        assertNull(ok(ledger.active()))
        val growing = Flipping(listOf(income, purchase), listOf(income))
        val commit = ok(ledger.select(proposed.copy(offers = growing), purchase.id, RequestId("r1")))
        assertEquals(listOf(income, purchase), commit.selected.active.proposed.offers)
        assertEquals(7_000, balance())
        reopen()
        assertEquals(listOf(income, purchase), requireNotNull(ok(ledger.active())).proposed.offers)
        assertEquals(commit, ok(ledger.select(proposed.copy(offers = listOf(income, purchase)), purchase.id, RequestId("r1"))))
    }

    fun testReplayWithMislinkedPaymentSettlementIsStorageFailure() {
        ok(select(purchase, "r1"))
        val corruptions = listOf(
            "UPDATE settlements SET result = replace(result, '\"request\":\"r1\"', '\"request\":\"other-request\"') WHERE request_id = 'r1'",
            "UPDATE settlements SET result = replace(result, '\"kind\":\"DELIVERY\"', '\"kind\":\"GARAGE\"') WHERE request_id = 'r1'",
            "UPDATE settlements SET result = replace(result, '\"amount\":3000', '\"amount\":2000') WHERE request_id = 'r1'",
            "UPDATE settlements SET result = replace(result, '\"rule\":\"delivery-trial-v0\"', '\"rule\":\"other-rule\"') WHERE request_id = 'r1'",
            "UPDATE settlements SET command = replace(command, '3000', '2000') WHERE request_id = 'r1'",
            "UPDATE settlements SET settlement_id = 'forged' WHERE request_id = 'r1'",
        ) + listOf("3000.5", "3000.0", "\"3000\"", "3e3", "92233720368547758070").map {
            // 공용 금액 해석(#136)이 소수·문자열·지수·범위 초과를 정수로 보정하지 않는지 탁송 소비 경로에서 확인한다.
            "UPDATE settlements SET result = replace(result, '\"amount\":3000', '\"amount\":$it') WHERE request_id = 'r1'"
        }
        val original = dump("settlements")
        sql("CREATE TABLE settlement_backup AS SELECT * FROM settlements WHERE request_id = 'r1'")
        for (corruption in corruptions) {
            sql(corruption)
            assertTrue(corruption, dump("settlements") != original)
            assertStorageFailureKeepsRows { select(purchase, "r1") }
            assertEquals(7_000, balance())
            sql("DELETE FROM settlements WHERE request_id = 'r1'", "INSERT INTO settlements SELECT * FROM settlement_backup")
        }
        assertEquals(purchase, ok(select(purchase, "r1")).selected.active.offer)
    }

    fun testMissingPaymentSettlementIsStorageFailure() {
        ok(select(purchase, "r1"))
        sql("DELETE FROM settlements WHERE request_id = 'r1'")
        assertStorageFailureKeepsRows { select(purchase, "r1") }
        // 재전송이 아닌 활성 조회와 다른 요청의 선택도 결제가 사라진 구매를 정상으로 보지 않는다.
        assertStorageFailureKeepsRows { ledger.active() }
        assertStorageFailureKeepsRows { ledger.inProgress() }
        assertStorageFailureKeepsRows { select(income, "r2") }
    }

    fun testActiveIncomeNeedsNoSettlementAndPurchaseActiveIsVerified() {
        ok(select(income, "r1"))
        reopen()
        assertEquals(income, ok(ledger.active())?.offer)
        ok(store.atomic { ledger.finishActive(it, RequestId("r1")) })
        ok(select(purchase, "r2"))
        sql("UPDATE settlements SET result = replace(result, '\"kind\":\"DELIVERY\"', '\"kind\":\"GARAGE\"') WHERE request_id = 'r2'")
        assertStorageFailureKeepsRows { ledger.active() }
    }

    fun testAnotherValidActiveOrPaidFlagIsStorageFailure() {
        ok(select(purchase, "r1"))
        val forged = DeliveryJson.active(ActiveDelivery(RequestId("r1"), proposed, samePrice)).toString().replace("'", "''")
        sql("UPDATE delivery_requests SET active = '$forged' WHERE request_id = 'r1'")
        assertStorageFailureKeepsRows { select(purchase, "r1") }
        assertStorageFailureKeepsRows { ledger.active() }
        assertEquals(7_000, balance())
    }

    fun testIncomeHistoryMarkedPaidIsStorageFailure() {
        ok(select(income, "r1"))
        sql("UPDATE delivery_requests SET paid = 1 WHERE request_id = 'r1'")
        assertStorageFailureKeepsRows { select(income, "r1") }
        assertStorageFailureKeepsRows { ledger.active() }
    }

    fun testPaidOutsideZeroOrOneOrNotIntegerIsStorageFailure() {
        ok(select(purchase, "r1"))
        // CHECK 제약 때문에 정상 API로는 만들 수 없는 손상값을 일부러 만든다.
        // 이런 값은 다시 열 때 저장소 전체 검사(quick_check)에도 걸리므로, 같은 연결에서 이 원장의 대조를 먼저 확인한다.
        val tables = listOf("delivery_requests", "delivery_active", "settlements", "account", "delivery_lists")
        for (raw in listOf("2", "-1", "'broken'", "X'01'")) {
            sql("PRAGMA ignore_check_constraints = ON", "UPDATE delivery_requests SET paid = $raw WHERE request_id = 'r1'",
                "PRAGMA ignore_check_constraints = OFF")
            val before = tables.map(::dump)
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), select(purchase, "r1"))
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), ledger.active())
            assertEquals(before, tables.map(::dump))
            sql("UPDATE delivery_requests SET paid = 1 WHERE request_id = 'r1'")
            assertNotNull(ok(select(purchase, "r1")).settlement)
        }
        sql("PRAGMA ignore_check_constraints = ON", "UPDATE delivery_requests SET paid = 2 WHERE request_id = 'r1'",
            "PRAGMA ignore_check_constraints = OFF")
        reopen()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), select(purchase, "r1"))
        assertEquals(LedgerFailureReason.CORRUPT, store.lastStorageFailure)
    }

    fun testBrokenStoredDateIsStorageFailureNotACrash() {
        sql("UPDATE delivery_lists SET payload = replace(payload, '\"createdOn\":\"2026-11-07\"', '\"createdOn\":\"2026-02-30\"')")
        assertStorageFailureKeepsRows { ledger.lists(day) }
        assertStorageFailureKeepsRows { select(purchase, "r1") }
        assertEquals(10_000, balance())
    }
}
