package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.garage.CollectionState
import io.drivegrowth.core.garage.Garage
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class RentalLedgerTest : InstrumentationTestCase() {
    private val source = InputSource.SIMULATION
    private val version = RuleVersion("trial-test")
    private val car = OwnedVehicle(OwnedVehicleId("one"), VehicleId("model"), 1, Revision(0), VehicleLifecycle.OWNED,
        Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
    private val catalog = VehicleCatalogSnapshot(ContentVersion("test"), listOf(VehicleDefinition(car.vehicleId,"시험차")))
    private val request = RentalRequest(RequestId("r1"),car.vehicleId,1)
    private val terms = RentalTerms(version, SimulatedCustomer(30,36), 99, EpochMillis(0), 15, GameMoney(5), RoundingMode.DOWN)
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private fun open() = SqliteGameChangeStore(context, source, name)
    override fun setUp() {
        super.setUp()
        name = "rental-test-" + System.nanoTime() + ".db"
        store = open()
        add(car, 100)
    }
    private fun add(v: OwnedVehicle, credit: Long) = ok(store.commitGameChange(GameChange(
        RequestId("add/"+v.ownedVehicleId.value), TransactionKind.GARAGE, source, version,
        MoneyChange(MoneyDirection.CREDIT,GameMoney(credit)), VehicleWrite.Add(v))))
    private fun reopen() { store.close(); store = open() }
    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    private fun assign(s: SqliteGameChangeStore = store, r: RentalRequest = request, t: RentalTerms = terms) =
        s.rental.assign(r, catalog, mapOf(car.vehicleId to RentalCategory.SEDAN), mapOf(car.vehicleId to GameMoney(100)),t)
    private fun sell(s: SqliteGameChangeStore, revision: Int) = s.commitGameChange(GameChange(RequestId("sell"),
        TransactionKind.GARAGE,source,version,vehicle=VehicleWrite.Update(car.ownedVehicleId,Revision(revision),1,VehicleLifecycle.SOLD,0)))

    fun testAssignmentRacesSaleAndTwoConnectionsRestoreOneWinner() {
        val other = open()
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit(Callable { gate.await(); assign() })
            val b = pool.submit(Callable { gate.await(); sell(other,0) })
            gate.countDown()
            val assigned = ok(a.get(10,TimeUnit.SECONDS))
            val sold = b.get(10,TimeUnit.SECONDS)
            reopen()
            assertEquals(assigned,ok(assign()))
            if (assigned != null) {
                assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),sold)
                assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),sell(other,1))
                assertNull(ok(assign(other,request.copy(id=RequestId("r2")))))
            } else {
                ok(sold)
                assertTrue(ok(store.listOwnedVehicles()).isEmpty())
            }
        } finally { pool.shutdownNow(); other.close() }
    }

    fun testTwoVisitorsCompeteAndRevisitIsReplayable() {
        open().use { other ->
            val pool = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(1)
            try {
                val jobs = listOf(store,other).mapIndexed { i,s -> pool.submit(Callable {
                    ready.await()
                    ok(assign(s,request.copy(id=RequestId("r"+i))))
                }) }
                ready.countDown()
                assertEquals(1,jobs.count { it.get(10,TimeUnit.SECONDS) != null })
                assertEquals(1,ok(store.rental.pending()).size)
            } finally { pool.shutdownNow() }
        }
        reopen()
        assertEquals(VehicleActivity.RENTED,ok(store.rental.activity(car.ownedVehicleId)))
    }

    fun testParkingRollbackReturnReviewAndFacilitySurviveReopen() {
        for (i in 2..5) add(car.copy(ownedVehicleId=OwnedVehicleId("v"+i)),0)
        val offer = FacilityOffer(Facility.FUEL_STATION,version,true,GameMoney(10))
        val purchase = ok(store.rental.buy(RequestId("buy"),offer))
        assertEquals(GameMoney(90),purchase.balance)
        assertEquals(car.ownedVehicleId,ok(assign()))
        val id = request.id
        val start = terms.start
        ok(store.rental.account(id,start,EpochMillis(1800000),start))
        reopen()
        assertEquals(EpochMillis(1800000),ok(store.rental.pending()).single().cursor)
        assertEquals(EpochMillis(1800000),ok(store.rental.account(id,start,EpochMillis(1500000),start)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            store.rental.account(id,EpochMillis(1800000),EpochMillis(3600000),EpochMillis(1800000)))
        ok(store.rental.account(id,start,EpochMillis(3600000),start))
        store.beforeCommit = { throw SQLiteException("주입") }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.rental.settle(id))
        reopen()
        assertEquals(GameMoney(90),ok(store.balance()))
        val receipt = ok(store.rental.settle(id))
        assertEquals(GameMoney(173),receipt.settlement.balance) // 120 × (30+9)/60 + 5
        assertTrue(receipt.matchReview)
        assertFalse(receipt.accident)
        reopen()
        assertEquals(receipt,ok(store.rental.settle(id)))
        assertEquals(purchase,ok(store.rental.buy(RequestId("buy"),offer)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.rental.finishReturn(id,EpochMillis(3600000)))
        assertEquals(VehicleActivity.IDLE,ok(store.rental.finishReturn(id,EpochMillis(4500000))))
        val expand = ShopExpansion(version,true,1,3,6,1,GameMoney(20))
        val expanded = ok(store.rental.expand(RequestId("expand"),expand))
        reopen()
        assertEquals(expanded,ok(store.rental.expand(RequestId("expand"),expand)))
        assertEquals(ShopState(ShopProgress(5,1,2),6,setOf(Facility.FUEL_STATION)),ok(store.rental.shop()))
        assertEquals(GameMoney(153),ok(store.balance()))
    }

    fun testRestAndAccidentSurviveReopenWithoutRedraw() {
        ok(store.rental.setResting(car.ownedVehicleId,true))
        assertNull(ok(assign()))
        ok(store.rental.setResting(car.ownedVehicleId,false))
        assertNull(ok(assign()))
        val second = request.copy(id=RequestId("second"))
        val accident = terms.copy(roll=0, regularCustomer=true)
        assertEquals(car.ownedVehicleId,ok(assign(r=second,t=accident)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),assign(r=second,t=terms))
        ok(store.rental.account(second.id,EpochMillis(0),EpochMillis(3600000),null))
        val receipt = ok(store.rental.settle(second.id))
        assertTrue(receipt.accident)
        assertEquals(true,receipt.regularCustomer)
        assertEquals(GameMoney(160),receipt.settlement.balance)
        reopen()
        assertEquals(receipt,ok(store.rental.settle(second.id)))
        assertEquals(VehicleActivity.NEEDS_REPAIR,ok(store.rental.finishReturn(second.id,EpochMillis(4500000))))
        assertNull(ok(assign(r=request.copy(id=RequestId("third")))))
    }

    fun testUnresolvedRoundingLeavesBalanceAndRequestRecoverable() {
        val t = terms.copy(rounding=null)
        val start = t.start
        ok(assign(t=t))
        ok(store.rental.account(request.id,start,EpochMillis(1),null))
        // 1ms 주행 후 주차하면 정산액에 소수가 남는다.
        ok(store.rental.account(request.id,EpochMillis(1),EpochMillis(3600000),EpochMillis(1)))
        val before = ok(store.rental.pending())
        val settlements = ok(store.listUnviewedSettlements())
        assertEquals(ContractResult.Failure(ContractError.POLICY_UNRESOLVED),store.rental.settle(request.id))
        reopen()
        assertEquals(GameMoney(100),ok(store.balance()))
        assertEquals(before, ok(store.rental.pending()))
        assertEquals(settlements, ok(store.listUnviewedSettlements()))
        val resolved = ok(store.rental.settle(request.id,RoundingMode.DOWN))
        assertEquals(GameMoney(178),resolved.settlement.balance)
        assertEquals(resolved,ok(store.rental.settle(request.id)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.rental.settle(request.id,RoundingMode.UP))
    }

    fun testDamagedCarRejectsMaterialButAllowsSale() = assertDamagedDisposition(VehicleLifecycle.SOLD)

    fun testDamagedCarRejectsMaterialButAllowsScrap() = assertDamagedDisposition(VehicleLifecycle.SCRAPPED)

    private fun assertDamagedDisposition(disposition: VehicleLifecycle) {
        ok(assign(t = terms.copy(roll = 0)))
        val assigned = ok(store.listOwnedVehicles()).single()
        fun write(target: VehicleLifecycle) = store.commitGameChange(GameChange(
            RequestId(target.name), TransactionKind.GARAGE, source, version,
            money = MoneyChange(MoneyDirection.CREDIT, GameMoney(5)),
            vehicle = VehicleWrite.Update(assigned.ownedVehicleId, assigned.revision,
                assigned.level, target, assigned.levelProgressPercent),
        ))
        val conflict = ContractResult.Failure(ContractError.STATE_CONFLICT)
        assertEquals(conflict, write(disposition))
        ok(store.rental.account(request.id, terms.start, EpochMillis(3600000), null))
        ok(store.rental.settle(request.id))
        assertEquals(conflict, write(disposition))
        assertEquals(VehicleActivity.NEEDS_REPAIR, ok(store.rental.finishReturn(request.id, EpochMillis(4500000))))
        val balance = ok(store.balance())
        val settlements = ok(store.listUnviewedSettlements())
        assertEquals(conflict, write(VehicleLifecycle.CONSUMED))
        reopen()
        assertEquals(listOf(assigned), ok(store.listOwnedVehicles()))
        assertEquals(balance, ok(store.balance()))
        assertEquals(settlements, ok(store.listUnviewedSettlements()))
        assertEquals(disposition, ok(write(disposition)).changedVehicle?.lifecycle)
    }

    fun testIntegralOverflowRejectsEveryRoundingPolicyWithoutWrites() {
        ok(store.rental.assign(request, catalog, mapOf(car.vehicleId to RentalCategory.SEDAN),
            mapOf(car.vehicleId to GameMoney(Long.MAX_VALUE - 2)), terms.copy(rounding = null)))
        ok(store.rental.account(request.id, terms.start, EpochMillis(3600000), null))
        val pending = ok(store.rental.pending())
        val settlements = ok(store.listUnviewedSettlements())
        for (policy in listOf(null, RoundingMode.UNNECESSARY, RoundingMode.DOWN)) {
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), store.rental.settle(request.id, policy))
            reopen()
            assertEquals(pending, ok(store.rental.pending()))
            assertEquals(settlements, ok(store.listUnviewedSettlements()))
            assertEquals(GameMoney(100), ok(store.balance()))
        }
    }

    fun testDisposedHistoryAndDiscoverySurviveReopen() {
        val seen = CollectionKey(car.vehicleId, Rarity.RARE)
        val owned = CollectionKey(car.vehicleId, Rarity.COMMON)
        val expected = mutableListOf<OwnedVehicle>()
        for (state in listOf(VehicleLifecycle.SOLD, VehicleLifecycle.SCRAPPED, VehicleLifecycle.CONSUMED)) {
            val vehicle = if (state == VehicleLifecycle.SOLD) car else car.copy(
                ownedVehicleId = OwnedVehicleId(state.name), acquisitionSource = VehicleAcquisitionSource.GACHA)
            if (vehicle != car) add(vehicle, 0)
            val updated = ok(store.commitGameChange(GameChange(RequestId("dispose/" + state.name),
                TransactionKind.GARAGE, source, version, vehicle = VehicleWrite.Update(vehicle.ownedVehicleId,
                    vehicle.revision, vehicle.level, state, vehicle.levelProgressPercent))))
            expected.add(checkNotNull(updated.changedVehicle))
        }
        repeat(2) { ok(store.recordDiscoveries(catalog, setOf(seen))) }
        val lastBalance = ok(store.balance())
        val transactions = ok(store.listUnviewedSettlements())
        val unseen = CollectionKey(car.vehicleId, Rarity.LEGEND)
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), store.recordDiscoveries(catalog,
            setOf(unseen, CollectionKey(VehicleId("unknown"), Rarity.COMMON))))
        store.beforeCommit = { throw SQLiteException("발견 저장 실패") }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.recordDiscoveries(catalog, setOf(unseen)))
        reopen()
        assertTrue(ok(store.listOwnedVehicles()).isEmpty())
        val history = ok(store.listVehicleHistory())
        assertEquals(expected.toSet(), history.toSet())
        assertEquals(car.ownedVehicleId, history.single { it.acquisitionSource == VehicleAcquisitionSource.INITIAL_GRANT }.ownedVehicleId)
        assertEquals(setOf(seen), ok(store.discoveredVehicles()))
        val keys = history.map { CollectionKey(it.vehicleId, it.rarity) }.toSet()
        val states = ok(Garage(catalog).collection(keys, ok(store.discoveredVehicles()))).associate { it.key to it.state }
        assertEquals(CollectionState.OWNED, states[owned])
        assertEquals(CollectionState.DISCOVERED, states[seen])
        assertEquals(CollectionState.UNDISCOVERED, states[unseen])
        assertEquals(lastBalance, ok(store.balance()))
        assertEquals(transactions, ok(store.listUnviewedSettlements()))
    }

    fun testAccidentCountUsesOnlyCommittedAccidentsOfOneOwnedVehicle() {
        val id = car.ownedVehicleId
        val other = OwnedVehicleId("same-model")
        add(car.copy(ownedVehicleId = other), 0)
        ok(store.rental.setResting(other, true))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            store.rental.accidentCount(OwnedVehicleId("unknown")))
        assertEquals(0, ok(store.rental.accidentCount(id)))

        for (n in 0..3) {
            val start = n * 4500000L
            val r = request.copy(id = RequestId("visit-$n"))
            // 첫 대여는 정상, 이어지는 세 번은 사고다.
            val t = terms.copy(start = EpochMillis(start), roll = if (n == 0) 99 else 0)
            assertEquals(id, ok(assign(r = r, t = t)))
            val before = (n - 1).coerceAtLeast(0)
            assertEquals(before, ok(store.rental.accidentCount(id)))
            ok(store.rental.account(r.id, t.start, EpochMillis(start + 3600000), null))
            if (n == 1) {
                store.beforeCommit = { throw SQLiteException("정산 실패") }
                assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.rental.settle(r.id))
                reopen()
                assertEquals(before, ok(store.rental.accidentCount(id)))
            }
            val receipt = ok(store.rental.settle(r.id))
            assertEquals(n != 0, receipt.accident)
            assertEquals(receipt, ok(store.rental.settle(r.id)))
            ok(store.rental.finishReturn(r.id, EpochMillis(start + 4500000)))
            reopen()
            assertEquals(n, ok(store.rental.accidentCount(id)))
            assertEquals(0, ok(store.rental.accidentCount(other)))
            if (n in 1..2) {
                // #49 수리 명령 전의 fixture: 수리 완료 활동만 주입하고 대여·사고 이력은 그대로 둔다.
                store.close()
                SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("UPDATE rentals SET phase='IDLE' WHERE id=? AND phase='NEEDS_REPAIR'", arrayOf(r.id.value))
                }
                store = open()
            }
        }
        val current = ok(store.listOwnedVehicles()).single { it.ownedVehicleId == id }
        ok(store.commitGameChange(GameChange(RequestId("scrap"), TransactionKind.GARAGE, source, version,
            vehicle = VehicleWrite.Update(id, current.revision, current.level, VehicleLifecycle.SCRAPPED, current.levelProgressPercent))))
        reopen()
        assertEquals(3, ok(store.rental.accidentCount(id)))
    }

    fun testFirstCompletedRentalUsesActualCommitOrderAcrossV2Upgrade() {
        val second = request.copy(id = RequestId("second"))
        add(car.copy(ownedVehicleId = OwnedVehicleId("two")), 0)
        val firstCar = ok(assign())
        val secondCar = ok(assign(r = second, t = terms.copy(roll = 0)))
        assertNull(ok(store.rental.firstCompletedRental()))
        for (r in listOf(request, second)) ok(store.rental.account(r.id, terms.start, EpochMillis(3600000), null))
        store.beforeCommit = { throw SQLiteException("정산 실패") }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.rental.settle(request.id))
        reopen()
        assertNull(ok(store.rental.firstCompletedRental()))
        val earliest = ok(store.rental.settle(second.id))
        ok(store.markViewed(earliest.settlement.settlementId))
        val later = ok(store.rental.settle(request.id))
        val balance = ok(store.balance())
        val unviewed = ok(store.listUnviewedSettlements())
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            for (table in deliveryTables) it.execSQL("DROP TABLE $table")
            it.execSQL("DROP TABLE discoveries")
            it.execSQL("DROP TABLE gacha_progress")
            it.execSQL("DROP TABLE gacha_requests")
            it.version = 2
        }
        store = open()
        val first = checkNotNull(ok(store.rental.firstCompletedRental()))
        assertEquals(second.id, first.rentalId)
        assertEquals(secondCar, first.ownedVehicleId)
        assertEquals(earliest, first.receipt)
        assertTrue(first.receipt.accident)
        assertEquals(first, ok(store.rental.completedRental(second.id)))
        val last = checkNotNull(ok(store.rental.completedRental(request.id)))
        assertEquals(firstCar, last.ownedVehicleId)
        assertEquals(later, last.receipt)
        assertNull(ok(store.rental.completedRental(RequestId("unknown"))))
        assertTrue(ok(store.discoveredVehicles()).isEmpty())
        assertEquals(balance, ok(store.balance()))
        assertEquals(unviewed, ok(store.listUnviewedSettlements()))
        assertEquals(earliest, ok(store.rental.settle(second.id)))
    }

    fun testDiscoveryUsesOneSnapshotOfChangingInput() {
        val valid = CollectionKey(car.vehicleId, Rarity.RARE)
        val invalid = CollectionKey(VehicleId("unknown"), Rarity.LEGEND)
        var reads = 0
        val changing = object : AbstractSet<CollectionKey>() {
            override val size = 2
            override fun iterator(): Iterator<CollectionKey> {
                reads++
                return if (reads == 1) setOf(valid, CollectionKey(car.vehicleId, Rarity.COMMON)).iterator()
                else setOf(valid, invalid).iterator()
            }
        }
        ok(store.recordDiscoveries(catalog, changing))
        assertFalse(invalid in ok(store.discoveredVehicles()))
        assertEquals(2, ok(store.discoveredVehicles()).size)
    }

    fun testCompletedRentalRejectsBrokenLinksAndRawValuesWithoutWrites() {
        ok(assign())
        ok(store.rental.account(request.id, terms.start, EpochMillis(3600000), null))
        ok(store.rental.settle(request.id))
        val balance = ok(store.balance())
        val cases = listOf(
            "UPDATE rentals SET settlement_request='add/one'" ,
            "UPDATE settlements SET settlement_id='wrong' WHERE request_id='rental/settle/r1'",
            "UPDATE settlements SET result=json_set(result,'$.request','wrong') WHERE request_id='rental/settle/r1'",
            "UPDATE settlements SET result=json_set(result,'$.kind','GARAGE') WHERE request_id='rental/settle/r1'",
            "UPDATE settlements SET result=json_set(result,'$.source','VEHICLE') WHERE request_id='rental/settle/r1'",
            "UPDATE rentals SET accident=2",
            "UPDATE rentals SET match_review=0.5",
            "UPDATE rentals SET wash_review='invalid'",
            "UPDATE rentals SET regular_customer=-1",
            "UPDATE rentals SET bonus=1.5",
            "UPDATE rentals SET bonus=-1",
        )
        store.close()
        val file = context.getDatabasePath(name)
        val original = file.readBytes()
        for (sql in cases) {
            file.writeBytes(original)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(sql) }
            val broken = file.readBytes()
            store = open()
            assertEquals(sql, ContractResult.Failure(ContractError.STORAGE_FAILURE), store.rental.firstCompletedRental())
            assertEquals(sql, ContractResult.Failure(ContractError.STORAGE_FAILURE), store.rental.completedRental(request.id))
            assertEquals(balance, ok(store.balance()))
            store.close()
            assertTrue(sql, broken.contentEquals(file.readBytes()))
        }
        file.writeBytes(original)
        store = open()
        assertNotNull(ok(store.rental.firstCompletedRental()))
    }

    private val deliveryTables = listOf("delivery_lists", "delivery_requests", "delivery_active", "delivery_routes",
        "delivery_outcomes", "delivery_held", "delivery_changes", "driving_days", "driving_drives", "delivery_route_history", "delivery_hold_outcomes")

    fun testUpgradeFromV1KeepsAccountAndOriginalVehicle() {
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            listOf("visits","rentals","shop","facilities","rental_rest","discoveries","gacha_progress","gacha_requests").plus(deliveryTables).forEach { db.execSQL("DROP TABLE "+it) }
            db.version=1
        }
        store=open()
        assertEquals(GameMoney(100),ok(store.balance()))
        assertEquals(listOf(car),ok(store.listOwnedVehicles()))
        assertEquals(1,ok(store.listUnviewedSettlements()).size)
        assertEquals(car.ownedVehicleId,ok(assign()))
    }
}
