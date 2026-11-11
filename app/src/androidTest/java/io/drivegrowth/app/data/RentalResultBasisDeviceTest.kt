package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/** 같은 원장의 실제 SQLite 기록/재개방 시험. 금액은 기존 정산의 독립 기대값이다. */
@Suppress("DEPRECATION")
class RentalResultBasisDeviceTest : InstrumentationTestCase() {
    private val source = InputSource.SIMULATION
    private val car = OwnedVehicle(OwnedVehicleId("basis-car"), VehicleId("model"), 1, Revision(0),
        VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
    private val catalog = VehicleCatalogSnapshot(ContentVersion("basis-content"), listOf(VehicleDefinition(car.vehicleId, "시험차")))
    private val id = RequestId("basis-rental")
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private fun hour(n: Double) = EpochMillis((n * 3_600_000).toLong())
    private fun <T> ok(r: ContractResult<T>): T {
        assertTrue(r.toString(), r is ContractResult.Success)
        return (r as ContractResult.Success).value
    }
    private fun raw(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(
        context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun reopen() { store.close(); store = SqliteGameChangeStore(context, source, name) }
    override fun setUp() {
        super.setUp(); name = "rental-basis-${System.nanoTime()}.db"
        store = SqliteGameChangeStore(context, source, name)
        ok(store.commitGameChange(GameChange(RequestId("basis-add"), TransactionKind.GARAGE, source,
            RuleVersion("grant"), MoneyChange(MoneyDirection.CREDIT, GameMoney(100)), VehicleWrite.Add(car))))
    }
    override fun tearDown() { store.close(); context.deleteDatabase(name); super.tearDown() }
    private fun assign(start: EpochMillis = hour(5.0), hours: Int = 6) = store.rental.assign(
        RentalRequest(id, car.vehicleId, hours), catalog, mapOf(car.vehicleId to RentalCategory.SEDAN),
        mapOf(car.vehicleId to GameMoney(100)), RentalTerms(RuleVersion("original-rental"),
            SimulatedCustomer(30, 36), 99, start, 15, GameMoney(0), RoundingMode.DOWN))
    private fun mixed() {
        assertEquals(car.ownedVehicleId, ok(assign()))
        ok(store.rental.account(id, hour(5.0), hour(5.5), null))
        ok(store.rental.accountInactive(id, hour(5.5), hour(9.0), hour(0.0)))
        ok(store.rental.account(id, hour(9.0), hour(10.0), null))
        ok(store.rental.account(id, hour(10.0), hour(11.0), hour(10.0)))
    }
    private fun available(): RentalResultBasis {
        val observation = ok(RentalResultBasisReader(store).read(id))
        assertTrue(observation.toString(), observation is Observation.Available)
        return (observation as Observation.Available).value
    }
    private fun rows(): List<String> = buildList {
        raw { db -> db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'android_%' ORDER BY name", null).use { tables ->
            while (tables.moveToNext()) {
                val table = tables.getString(0)
                db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { r ->
                    while (r.moveToNext()) add(table + ":" + (0 until r.columnCount).joinToString("|") { if (r.isNull(it)) "NULL" else r.getString(it) })
                }
            }
        } }
    }
    private fun mutate(block: (JSONObject) -> Unit) = raw { db ->
        val j = db.rawQuery("SELECT payload FROM rental_result_basis WHERE rental_id=?", arrayOf(id.value)).use {
            assertTrue(it.moveToFirst()); JSONObject(it.getString(0))
        }
        block(j)
        db.execSQL("UPDATE rental_result_basis SET payload=? WHERE rental_id=?", arrayOf(j.toString(), id.value))
    }

    fun testMixedOriginalPeriodsAndExactSettlementSurviveReopenAndReplay() {
        mixed(); val receipt = ok(store.rental.settle(id))
        assertEquals(GameMoney(448), receipt.settlement.balance) // 원래 720 × (2h + 3h×30%) / 6h = 348
        reopen(); val b = available()
        assertEquals(receipt.settlement.settlementId, b.settlementId)
        assertEquals(ok(store.localProfileId()), b.profileId); assertEquals(source, b.source)
        assertEquals(RuleVersion("original-rental"), b.ruleVersion)
        assertEquals(7_200_000L, b.normalMillis); assertEquals(10_800_000L, b.reducedMillis)
        assertEquals(3_600_000L, b.cappedMillis); assertEquals(0, b.weightedMillis.compareTo(BigDecimal("10440000")))
        assertEquals(listOf(RentalIncomeKind.DRIVING, RentalIncomeKind.INACTIVE, RentalIncomeKind.DRIVING, RentalIncomeKind.PARKED), b.periods.map { it.kind })
        assertEquals(hour(0.0), b.periods[1].origin); assertEquals(hour(10.0), b.periods[3].origin)
        assertEquals(1_800_000L, b.periods[1].policy!!.normalMillis)
        assertEquals(28_800_000L, b.periods[1].policy!!.capMillis)
        assertEquals(BigDecimal("0.30"), b.periods[1].policy!!.reducedMultiplier)
        val before = rows(); assertEquals(receipt, ok(store.rental.settle(id))); available(); assertEquals(before, rows())
        val entry = ok(SettlementHistoryReader(store).read()).entries.single { it.rental != null }
        assertEquals(b.settlementId, (entry.rentalBasis as Observation.Available).value.settlementId)
    }

    fun testRepeatIntervalsAndReconnectionDoNotRestartOriginOrDuplicateEvidence() {
        ok(assign(hour(0.0), 1))
        ok(store.rental.account(id, hour(0.0), hour(0.5), hour(0.0))); reopen()
        ok(store.rental.account(id, hour(0.0), hour(0.5), hour(0.0)))
        ok(store.rental.accountInactive(id, hour(0.5), hour(1.0), hour(0.0)))
        ok(store.rental.settle(id)); val b = available()
        assertEquals(1_800_000L, b.normalMillis); assertEquals(1_800_000L, b.reducedMillis)
        assertEquals(2, b.periods.size); assertEquals(GameMoney(178), ok(store.balance()))
        assertTrue(b.periods.all { it.origin == hour(0.0) })
    }

    fun testAdjacentDrivingIntervalsAreCoalescedWithoutNewRounding() {
        ok(assign(hour(0.0), 1))
        ok(store.rental.account(id, hour(0.0), EpochMillis(1), null))
        ok(store.rental.account(id, EpochMillis(1), hour(1.0), null))
        ok(store.rental.settle(id)); val b = available()
        assertEquals(1, b.periods.size); assertEquals(3_600_000L, b.normalMillis)
        assertEquals(GameMoney(220), ok(store.balance()))
    }

    fun testLegacyCompletedAndActiveRentalsStayMissingAfterSchema17Upgrade() {
        mixed(); val receipt = ok(store.rental.settle(id)); val before = rows().filterNot { it.startsWith("rental_result_basis:") }
        store.close(); raw { it.execSQL("DROP TABLE rental_result_basis"); it.version = 17 }; reopen()
        assertEquals(Observation.Missing(MissingReason.NO_DATA), ok(RentalResultBasisReader(store).read(id)))
        assertEquals(receipt, ok(store.rental.settle(id)))
        assertEquals(before, rows().filterNot { it.startsWith("rental_result_basis:") }); raw { assertEquals(18, it.version) }
    }

    fun testLegacyActiveRentalDoesNotAcquirePartialBasisMidway() {
        ok(assign(hour(0.0), 1)); ok(store.rental.account(id, hour(0.0), hour(0.5), hour(0.0)))
        store.close(); raw { it.execSQL("DROP TABLE rental_result_basis"); it.version = 17 }; reopen()
        ok(store.rental.accountInactive(id, hour(0.5), hour(1.0), hour(0.0))); ok(store.rental.settle(id))
        assertEquals(Observation.Missing(MissingReason.NO_DATA), ok(RentalResultBasisReader(store).read(id)))
        assertEquals(GameMoney(178), ok(store.balance()))
    }

    fun testFractionalDurationFailsInsteadOfBecomingMissing() {
        mixed(); ok(store.rental.settle(id))
        mutate { it.getJSONArray("periods").getJSONObject(0).put("normal", 1800000.5) }
        val before = rows(); val failure = ContractResult.Failure(ContractError.STORAGE_FAILURE)
        assertEquals(failure, RentalResultBasisReader(store).read(id)); assertEquals(failure, SettlementHistoryReader(store).read())
        assertEquals(failure, store.rental.settle(id)); assertEquals(before, rows())
    }

    fun testDefinitionFailureDoesNotHideOrRecomputeOriginalPolicy() {
        mixed(); val receipt = ok(store.rental.settle(id)); reopen()
        ok(store.commitGameChange(GameChange(RequestId("later-credit"), TransactionKind.GARAGE, source,
            RuleVersion("later"), MoneyChange(MoneyDirection.CREDIT, GameMoney(50)))))
        store.trialDefinitions = TrialDefinitionSource { ContractResult.Failure(ContractError.DATA_UNAVAILABLE) }
        val b = available(); assertEquals(0, b.weightedMillis.compareTo(BigDecimal("10440000")))
        assertEquals(receipt, ok(store.rental.settle(id)))
        assertEquals(RuleVersion("parking-income-v1"), b.periods[1].policy!!.version)
        assertEquals(GameMoney(498), ok(store.balance()))
    }

    fun testMissingRentalAndUnfinishedResultAreExplicitlyUnavailable() {
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), RentalResultBasisReader(store).read(id))
        ok(assign()); assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), RentalResultBasisReader(store).read(id))
    }

    fun testIgnoredInitialEvidenceInsertRollsBackVisitVehicleAndRental() {
        raw { it.execSQL("CREATE TRIGGER omit_basis BEFORE INSERT ON rental_result_basis BEGIN SELECT RAISE(IGNORE); END") }
        val before = rows(); assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), assign()); assertEquals(before, rows())
        raw { it.execSQL("DROP TRIGGER omit_basis") }; assertEquals(car.ownedVehicleId, ok(assign()))
    }

    fun testIgnoredAccountEvidenceUpdateRollsBackCursorAndDelta() {
        ok(assign()); val before = rows()
        raw { it.execSQL("CREATE TRIGGER omit_basis BEFORE UPDATE ON rental_result_basis BEGIN SELECT RAISE(IGNORE); END") }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.rental.account(id, hour(5.0), hour(6.0), null))
        assertEquals(before, rows()); raw { it.execSQL("DROP TRIGGER omit_basis") }; reopen()
        assertEquals(hour(5.0), ok(store.rental.pending()).single().cursor)
    }

    fun testIgnoredFinalEvidenceUpdateRollsBackCreditOriginalAndQuest() {
        mixed(); val before = rows()
        raw { it.execSQL("CREATE TRIGGER omit_basis BEFORE UPDATE ON rental_result_basis BEGIN SELECT RAISE(IGNORE); END") }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.rental.settle(id)); assertEquals(before, rows())
        raw { it.execSQL("DROP TRIGGER omit_basis") }; reopen()
        assertEquals(GameMoney(448), ok(store.rental.settle(id)).settlement.balance)
    }

    fun testLateCommitFailureRollsBackAllRowsAndCanRetryAfterReopen() {
        mixed(); val before = rows(); store.beforeCommit = { throw SQLiteException("basis late fault") }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.rental.settle(id))
        store.beforeCommit = null; assertEquals(before, rows()); reopen()
        assertEquals(GameMoney(448), ok(store.rental.settle(id)).settlement.balance); available()
    }

    fun testForeignIdentityAndOriginalSettlementLinkFailWithoutWrites() {
        mixed(); ok(store.rental.settle(id))
        var original = ""
        raw { db -> db.rawQuery("SELECT payload FROM rental_result_basis", null).use { it.moveToFirst(); original = it.getString(0) } }
        for ((key, value) in listOf("rental" to "other", "owned" to "other", "profile" to "other", "source" to "VEHICLE", "rule" to "other", "settlement" to "other")) {
            raw { it.execSQL("UPDATE rental_result_basis SET payload=?", arrayOf(JSONObject(original).put(key, value).toString())) }
            val before = rows(); assertEquals(key, ContractResult.Failure(ContractError.STORAGE_FAILURE), RentalResultBasisReader(store).read(id))
            assertEquals(key, before, rows())
        }
        raw { it.execSQL("UPDATE rental_result_basis SET payload=?", arrayOf(original)) }; available()
    }

    fun testGapAndDifferentOriginLeaveAcceptedBasisUnchanged() {
        ok(assign(hour(0.0), 1)); ok(store.rental.account(id, hour(0.0), hour(0.5), hour(0.0)))
        val before = rows(); val conflict = ContractResult.Failure(ContractError.STATE_CONFLICT)
        assertEquals(conflict, store.rental.account(id, hour(0.75), hour(1.0), hour(0.0)))
        assertEquals(conflict, store.rental.accountInactive(id, hour(0.5), hour(1.0), hour(0.5)))
        assertEquals(before, rows())
    }

    fun testEntireAcceptedIntervalBeyondCapKeepsZeroWeightAndOriginalSettlement() {
        ok(assign(hour(10.0), 1)); ok(store.rental.accountInactive(id, hour(10.0), hour(11.0), hour(0.0)))
        val receipt = ok(store.rental.settle(id)); val b = available()
        assertEquals(0L, b.normalMillis); assertEquals(0L, b.reducedMillis); assertEquals(3_600_000L, b.cappedMillis)
        assertEquals(0, b.weightedMillis.signum()); assertEquals(GameMoney(0), receipt.settlement.transaction.money!!.amount)
        assertEquals(GameMoney(100), receipt.settlement.balance)
    }

    fun testMissingMetadataTableAtSchema18IsNotRecreatedOrTreatedAsLegacy() {
        mixed(); ok(store.rental.settle(id)); store.close()
        raw { it.execSQL("DROP TABLE rental_result_basis") }; reopen(); val before = rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), RentalResultBasisReader(store).read(id))
        assertEquals(before, rows()); raw { db ->
            db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='rental_result_basis'", null).use { assertFalse(it.moveToFirst()) }
        }
    }
}
