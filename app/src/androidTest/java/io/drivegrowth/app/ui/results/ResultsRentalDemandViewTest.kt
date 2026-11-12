package io.drivegrowth.app.ui.results

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.ResultsHostMapper
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.app.runtime.rental.product.RentalVisitPlanner
import io.drivegrowth.core.contract.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 격리 원장과 실제 결과 View를 함께 검증한다. 시간과 고객은 시험 입력이다. */
@Suppress("DEPRECATION")
class ResultsRentalDemandViewTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val source = InputSource.SIMULATION
    private lateinit var name: String
    private lateinit var games: GameSessionFactory

    override fun setUp() {
        super.setUp()
        name = "results-rental-demand-${System.nanoTime()}.db"
        games = GameSessionFactory(context, mapOf(source to name), source)
    }

    override fun tearDown() {
        context.deleteDatabase(name)
        super.tearDown()
    }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }

    private fun createRental(modern: Boolean): RequestId {
        val (identity, content) = ok(games.open()).use { it.identity to ok(it.content) }
        val definition = ok(AssetTrialDefinitionProvider(context.assets).load())
        val inputs = ok(if (modern) definition.rentalVisitDefinition(content, 1) else definition.rentalVisitDefinition())
        val clock = RentalClock("demand-view-fixture", EpochMillis(100_000_000L), 0)
        val journal = SqliteRentalProductJournal(games, { ContractResult.Success(inputs) },
            { ContractResult.Success(clock) }, { ContractResult.Success(Observation.Available(clock)) })
        val cursor = ok(journal.activate(identity))
        val id = RequestId("demand-view-visit")
        val plan = checkNotNull(ok(RentalVisitPlanner().prepare(identity, content.catalog, inputs, cursor, clock,
            object : Random() { override fun nextBits(bitCount: Int) = 0 }) { id }))
        assertEquals(VehicleId("hyundai_casper"), plan.visit.request.desired)
        val assigned = OwnedVehicle(OwnedVehicleId("demand-view-assigned"), VehicleId("hyundai_venue"),
            1, Revision(0), VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
        ok(games.open()).use { game ->
            ok(game.store.commitGameChange(GameChange(RequestId("demand-view-add"), TransactionKind.GARAGE, source,
                inputs.rules, vehicle = VehicleWrite.Add(assigned))))
        }
        ok(journal.save(plan))
        assertEquals(assigned.ownedVehicleId, ok(journal.execute(plan)))
        ok(journal.finish(plan))
        ok(games.open()).use { game ->
            ok(game.store.rental.account(id, clock.epoch, EpochMillis(clock.epoch.value + 3_600_000L), null))
            ok(game.store.rental.settle(id))
        }
        return id
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun detail(): List<String> {
        val mapped = ResultsHostMapper().history(SettlementHistoryReader.read(games)) as ResultsScreenState.Content
        val rental = mapped.results.single { it.rental != null }
        var result = emptyList<String>()
        instrumentation.runOnMainSync {
            val view = ResultsView(context) {}
            view.render(mapped, true)
            view.restoreSelection(rental.settlement.settlementId)
            result = texts(view)
        }
        return result
    }

    fun testV2ShowsOriginalCityPurposeAndDesiredSeparatelyFromAssignedCar() {
        createRental(modern = true)
        val before = context.getDatabasePath(name).readBytes()
        val rows = detail()
        assertTrue(rows.toString(), rows.contains("목적 도시 특성: 도심"))
        assertTrue(rows.contains("용도: 근거리 이동"))
        assertTrue(rows.any { it.startsWith("희망 차종: ") && it.contains("캐스퍼") })
        assertTrue(rows.any { it.startsWith("대여 차량: ") && it.contains("베뉴") })
        assertTrue(before.contentEquals(context.getDatabasePath(name).readBytes()))
    }

    fun testV1ShowsKnownRequestAndAnUnrecordedCity() {
        createRental(modern = false)
        val rows = detail()
        assertTrue(rows.toString(), rows.contains("목적 도시 특성: 기록 없음"))
        assertTrue(rows.contains("용도: 근거리 이동"))
        assertTrue(rows.any { it.startsWith("희망 차종: ") && it.contains("캐스퍼") })
    }

    fun testLegacyVisitWithoutHostCommandDoesNotInventDemand() {
        val id = createRental(modern = false)
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(1, db.update("visits", ContentValues().apply { putNull("host_command") }, "id=?", arrayOf(id.value)))
        }
        val before = file.readBytes()
        val rows = detail()
        assertTrue(rows.toString(), rows.contains("가상 고객 요청: 기록 없음"))
        assertFalse(rows.any { it.startsWith("희망 차종: ") })
        assertTrue(before.contentEquals(file.readBytes()))
    }

    fun testResultsReadCommittedVisitWhileAnotherConnectionHoldsWriteTransaction() {
        val id = createRental(modern = true)
        val committed = ok(SettlementHistoryReader.read(games))
        fun snapshotFacts(snapshot: SettlementHistorySnapshot) =
            snapshot.copy(entries = snapshot.entries.map { it.copy(rentalBasis = Observation.Missing(MissingReason.NO_DATA)) })
        fun assertCommitted(snapshot: SettlementHistorySnapshot) {
            assertEquals(snapshotFacts(committed), snapshotFacts(snapshot))
            fun periods(history: SettlementHistorySnapshot) =
                (history.entries.single { it.rental != null }.rentalBasis as Observation.Available).value.periods
            assertEquals(periods(committed), periods(snapshot))
        }
        val reader = Executors.newSingleThreadExecutor()
        try {
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
                .use { writer ->
                    writer.beginTransactionNonExclusive()
                    try {
                        assertEquals(1, writer.update("visits", ContentValues().apply { putNull("host_command") },
                            "id=?", arrayOf(id.value)))
                        val result = reader.submit<ContractResult<SettlementHistorySnapshot>> {
                            SettlementHistoryReader.read(games)
                        }.get(10, TimeUnit.SECONDS)
                        assertCommitted(ok(result))
                    } finally {
                        writer.endTransaction()
                    }
                }
            assertCommitted(ok(SettlementHistoryReader.read(games)))
        } finally {
            reader.shutdownNow()
        }
    }

    fun testMalformedCityOrForeignIdentityIsAnErrorWithoutRepairingRows() {
        val id = createRental(modern = true)
        val dbFile = context.getDatabasePath(name)
        fun setHostCommand(raw: String) {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                assertEquals(1, db.update("visits", ContentValues().apply { put("host_command", raw) },
                    "id=?", arrayOf(id.value)))
            }
        }
        val original = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT host_command FROM visits WHERE id=?", arrayOf(id.value)).use {
                assertTrue(it.moveToFirst())
                it.getString(0)
            }
        }
        fun rejectsWithoutRepair(label: String) {
            val damaged = dbFile.readBytes()
            assertEquals(label, ContractResult.Failure(ContractError.STORAGE_FAILURE), SettlementHistoryReader.read(games))
            assertTrue(label, damaged.contentEquals(dbFile.readBytes()))
        }
        for ((key, wrong) in listOf("city" to "UNKNOWN", "profile" to "foreign", "source" to "VEHICLE",
            "request" to "foreign", "desired" to "hyundai_venue")) {
            setHostCommand(JSONObject(original).put(key, wrong).toString())
            rejectsWithoutRepair(key)
        }
        setHostCommand(JSONObject(original).apply { remove("city") }.toString())
        rejectsWithoutRepair("v2 도시 누락")
        setHostCommand(original)
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(1, db.update("visits", ContentValues().apply { put("assigned", "foreign") },
                "id=?", arrayOf(id.value)))
        }
        rejectsWithoutRepair("배정 불일치")
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(1, db.delete("visits", "id=?", arrayOf(id.value)))
        }
        rejectsWithoutRepair("방문 행 누락")
    }
}
