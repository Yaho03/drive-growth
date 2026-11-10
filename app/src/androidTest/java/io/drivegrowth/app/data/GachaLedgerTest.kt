package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class GachaLedgerTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.SIMULATION
    private val version = RuleVersion("test")
    private val carId = VehicleId("model")
    private val authority = GachaAuthority(source, ContentVersion("test"), version, 1, 3,
        GameMoney(500), 30, Rarity.RARE, setOf(carId), Rarity.entries.toSet())
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private fun open() = SqliteGameChangeStore(context, source, name,
        gachaAuthority = { ContractResult.Success(authority) },
        initialGachaProgress = GachaProgress(Revision(0), 0, 1))
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    override fun setUp() {
        super.setUp()
        name = "gacha-" + System.nanoTime() + ".db"
        store = open()
        ok(store.commitGameChange(GameChange(RequestId("fund"), TransactionKind.GARAGE, source, version,
            MoneyChange(MoneyDirection.CREDIT, GameMoney(2000)))))
    }
    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }
    private fun reopen() { store.close(); store = open() }
    private fun vehicle(id: String, cost: Long = 0) = OwnedVehicle(OwnedVehicleId(id), carId, 1, Revision(0),
        VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(cost), VehicleAcquisitionSource.GACHA)
    private fun command(id: String = "draw", free: Boolean = false) = GachaCommand(RequestId(id), source,
        authority.contentVersion, version, Revision(0), 1, 3, 0,
        if (free) GachaTicketUse.FREE else GachaTicketUse.PURCHASED,
        GachaAward(vehicle(id, if (free) 0 else 500), 1, false))

    fun testPaidReplaySurvivesGrowthAndReopenWithOriginalSnapshot() {
        val c = command()
        val first = ok(store.commitGacha(c))
        assertEquals(GameMoney(1500), first.settlement.balance)
        assertEquals(GachaProgress(Revision(1), 1, 1), first.progress)
        ok(store.commitGameChange(GameChange(RequestId("grow"), TransactionKind.GARAGE, source, version,
            vehicle = VehicleWrite.Update(c.award.vehicle.ownedVehicleId, Revision(0), 2, VehicleLifecycle.OWNED, 0))))
        reopen()
        assertEquals(first, ok(store.commitGacha(c)))
        assertEquals(2, ok(store.listOwnedVehicles()).single().level)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGacha(c.copy(ticket = GachaTicketUse.FREE)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGameChange(GameChange(c.requestId,
            TransactionKind.GARAGE, source, version, MoneyChange(MoneyDirection.CREDIT, GameMoney(1)))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGacha(command("fund")))
    }

    fun testFreeTicketFailureAndRetryAreAtomic() {
        val c = command(free = true)
        store.beforeCommit = { throw SQLiteException("failure") }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.commitGacha(c))
        reopen()
        assertEquals(GachaProgress(Revision(0), 0, 1), ok(store.readGachaState()).progress)
        assertTrue(ok(store.listOwnedVehicles()).isEmpty())
        assertEquals(GameMoney(2000), ok(store.balance()))
        val result = ok(store.commitGacha(c))
        assertNull(result.settlement.transaction.money)
        assertEquals(0L, result.progress.freeTickets)
        assertEquals(result, ok(store.commitGacha(c)))
        val next = command("next", true).copy(expectedRevision = Revision(1), drawsSinceGuarantee = 1,
            award = GachaAward(vehicle("next"), 2, false))
        assertEquals(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS), store.commitGacha(next))
    }

    fun testTwoConnectionsCompeteForLastSlotAndLegacyAddSharesCapacity() {
        for (id in listOf("one","two")) ok(store.commitGameChange(GameChange(RequestId(id), TransactionKind.GARAGE,
            source, version, vehicle = VehicleWrite.Add(vehicle(id)))))
        open().use { other ->
            val pool = Executors.newFixedThreadPool(2)
            val gate = CountDownLatch(1)
            try {
                val jobs = listOf(store, other).mapIndexed { i, s -> pool.submit(Callable {
                    gate.await()
                    s.commitGacha(command("draw-$i"))
                }) }
                gate.countDown()
                val results = jobs.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it is ContractResult.Success })
                assertEquals(3, ok(store.listOwnedVehicles()).size)
                assertEquals(GameMoney(1500), ok(store.balance()))
            } finally { pool.shutdownNow() }
        }
        val add = GameChange(RequestId("extra"), TransactionKind.GARAGE, source, version,
            vehicle = VehicleWrite.Add(vehicle("extra")))
        assertEquals(ContractResult.Failure(ContractError.CAPACITY_EXCEEDED), store.commitGameChange(add))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),
            store.commitGameChange(add.copy(source = InputSource.VEHICLE)))
    }

    fun testFreeTicketCompetitionAndChangedVersionsDoNotDoubleSpend() {
        open().use { other ->
            val pool = Executors.newFixedThreadPool(2)
            try {
                val jobs = listOf(store,other).mapIndexed { i,s -> pool.submit(Callable { s.commitGacha(command("free-$i", true)) }) }
                assertEquals(1, jobs.count { it.get(20, TimeUnit.SECONDS) is ContractResult.Success })
            } finally { pool.shutdownNow() }
        }
        assertEquals(0L, ok(store.readGachaState()).progress.freeTickets)
        assertEquals(1, ok(store.listOwnedVehicles()).size)
        assertEquals(GameMoney(2000), ok(store.balance()))
    }

    fun testPityAndRentalRequestNamespaceAreShared() {
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE gacha_progress SET counter=29")
        }
        store = open()
        val c = command().copy(drawsSinceGuarantee = 29,
            award = GachaAward(vehicle("draw", 500).copy(rarity = Rarity.RARE), 0, true))
        val result = ok(store.commitGacha(c))
        assertEquals(0, result.progress.drawsSinceGuarantee)
        assertTrue(result.command.award.guaranteed)
        val catalog = VehicleCatalogSnapshot(authority.contentVersion, listOf(VehicleDefinition(carId, "차")))
        val terms = RentalTerms(version, io.drivegrowth.core.rental.SimulatedCustomer(30, 36), 99,
            EpochMillis(0), 15, GameMoney(0), null)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.rental.assign(
            io.drivegrowth.core.rental.RentalRequest(c.requestId, carId, 1), catalog,
            mapOf(carId to io.drivegrowth.core.rental.RentalCategory.SEDAN), mapOf(carId to GameMoney(100)), terms))
        ok(store.rental.assign(io.drivegrowth.core.rental.RentalRequest(RequestId("visitor"), carId, 1), catalog,
            mapOf(carId to io.drivegrowth.core.rental.RentalCategory.SEDAN), mapOf(carId to GameMoney(100)), terms))
        val next = command("visitor").copy(expectedRevision = Revision(1), drawsSinceGuarantee = 0)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.commitGacha(next))
        assertEquals(result.progress, ok(store.readGachaState()).progress)
    }

    fun testV3MigrationPreservesLedgerAndDoesNotInventProgress() {
        val original = ok(store.listUnviewedSettlements())
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            for (table in listOf("delivery_lists", "delivery_requests", "delivery_active", "delivery_routes",
                "delivery_outcomes", "delivery_held", "delivery_changes", "driving_days", "driving_drives", "delivery_route_history", "delivery_hold_outcomes", "material_requests", "repair_requests"))
                it.execSQL("DROP TABLE $table")
            it.execSQL("DROP TABLE gacha_progress")
            it.execSQL("DROP TABLE gacha_requests")
            it.version = 3
        }
        store = open()
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), store.readGachaState())
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), store.commitGacha(command()))
        assertEquals(original, ok(store.listUnviewedSettlements()))
        assertEquals(GameMoney(2000), ok(store.balance()))
    }

    fun testPastResultCorruptionIsRejectedWithoutChangingFileOrCurrentState() {
        val c = command()
        val first = ok(store.commitGacha(c))
        val balance = ok(store.balance())
        val vehicles = ok(store.listOwnedVehicles())
        val cases = listOf(
            "UPDATE gacha_requests SET counter=28",
            "UPDATE gacha_requests SET revision=99",
            "UPDATE settlements SET result=json_set(result,'$.money.amount',350) WHERE request_id='draw'",
            "UPDATE settlements SET result=json_set(result,'$.money.direction','CREDIT') WHERE request_id='draw'",
            "UPDATE settlements SET result=json_set(result,'$.request','wrong') WHERE request_id='draw'",
            "UPDATE settlements SET result=json_set(result,'$.source','VEHICLE') WHERE request_id='draw'",
            "UPDATE settlements SET result=json_set(result,'$.rule','wrong') WHERE request_id='draw'",
            "UPDATE settlements SET settlement_id='wrong' WHERE request_id='draw'",
            "UPDATE settlements SET command='wrong' WHERE request_id='draw'",
        )
        store.close()
        val file = context.getDatabasePath(name)
        val original = file.readBytes()
        for (sql in cases) {
            file.writeBytes(original)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(sql) }
            val corrupted = file.readBytes()
            store = open()
            assertEquals(sql, ContractResult.Failure(ContractError.STORAGE_FAILURE), store.commitGacha(c))
            assertEquals(balance, ok(store.balance()))
            assertEquals(vehicles, ok(store.listOwnedVehicles()))
            store.close()
            assertTrue(sql, corrupted.contentEquals(file.readBytes()))
        }
        file.writeBytes(original)
        store = open()
        assertEquals(first, ok(store.commitGacha(c)))
    }

    fun testFreeReplayRejectsFabricatedPaymentAndSurvivesDisposal() {
        val c = command(free = true)
        val receipt = ok(store.commitGacha(c))
        ok(store.commitGameChange(GameChange(RequestId("sell"), TransactionKind.GARAGE, source, version,
            vehicle = VehicleWrite.Update(c.award.vehicle.ownedVehicleId, Revision(0), 1, VehicleLifecycle.SOLD, 0))))
        reopen()
        assertEquals(receipt, ok(store.commitGacha(c)))
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE settlements SET result=json_set(result,'$.money',json_object('direction','DEBIT','amount',0)) WHERE request_id='draw'")
        }
        store = open()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.commitGacha(c))
        assertTrue(ok(store.listOwnedVehicles()).isEmpty())
    }

    fun testRawPaymentTypesAreRejectedBeforeConversionAndPreserveDatabase() {
        val c = command()
        val receipt = ok(store.commitGacha(c))
        val balance = ok(store.balance())
        val vehicles = ok(store.listOwnedVehicles())
        store.close()
        val file = context.getDatabasePath(name)
        val original = file.readBytes()
        val resultJson = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT result FROM settlements WHERE request_id='draw'", null).use {
                assertTrue(it.moveToFirst())
                it.getString(0)
            }
        }
        val literals = listOf("500.75", "\"500\"", "500.0", "5e2", "9223372036854775808",
            "-9223372036854775809", "-1", "true", "null", "[]", "{}")
        for (literal in literals) {
            file.writeBytes(original)
            val malformed = Regex("\"amount\":500").replace(resultJson, "\"amount\":$literal")
            assertTrue(literal, malformed != resultJson)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE settlements SET result=? WHERE request_id='draw'", arrayOf(malformed))
            }
            val corrupted = file.readBytes()
            store = open()
            assertEquals(literal, ContractResult.Failure(ContractError.STORAGE_FAILURE), store.commitGacha(c))
            assertEquals(balance, ok(store.balance()))
            assertEquals(vehicles, ok(store.listOwnedVehicles()))
            store.close()
            assertTrue(literal, corrupted.contentEquals(file.readBytes()))
        }
        file.writeBytes(original)
        store = open()
        assertEquals(receipt, ok(store.commitGacha(c)))
    }

    fun testPaymentDecoderKeepsIntegerBoundariesAndFreeNull() {
        val receipt = ok(store.commitGacha(command()))
        val json = LedgerJson.settlement(receipt.settlement)
        for (amount in listOf(0L, Long.MAX_VALUE)) {
            json.getJSONObject("money").put("amount", amount)
            val decoded = LedgerJson.settlement(org.json.JSONObject(json.toString()))
            assertEquals(GameMoney(amount), decoded.transaction.money?.amount)
        }
        json.put("money", org.json.JSONObject.NULL)
        assertNull(LedgerJson.settlement(org.json.JSONObject(json.toString())).transaction.money)
    }

    fun testMalformedProgressAndWrongAwardFailWithoutWrites() {
        val c = command()
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),
            store.commitGacha(c.copy(award = c.award.copy(nextCounter = 10))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            store.commitGacha(c.copy(contentVersion = ContentVersion("wrong"))))
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE gacha_progress SET tickets=0.5")
        }
        val file = context.getDatabasePath(name)
        val bytes = file.readBytes()
        store = open()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.readGachaState())
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.commitGacha(c))
        store.close()
        assertTrue(bytes.contentEquals(file.readBytes()))
        store = open()
    }
}
