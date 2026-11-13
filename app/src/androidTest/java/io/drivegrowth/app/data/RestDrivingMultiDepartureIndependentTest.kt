package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.rest.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.json.JSONObject
import java.math.BigDecimal
import java.time.ZoneId

/** 실제 SQLite 시험. 시간·단가는 명시적인 축소 SIMULATION 입력이며 제품 규칙 변경이 아니다. */
@Suppress("DEPRECATION")
class RestDrivingMultiDepartureIndependentTest : InstrumentationTestCase() {
    private lateinit var name: String
    private lateinit var store: SqliteGameChangeStore
    private lateinit var identity: GameIdentity
    private lateinit var definition: TrialDefinition
    private var definitionsAvailable = true
    private var definitionReads = 0
    private val context get() = instrumentation.targetContext
    private val clock = RestClockDomain("multi-receiver", "multi-boot")
    private val policy = RestEvidencePolicy(4_000_000, 4_000_000, 100, .5, .1)
    private val missing = Observation.Missing(MissingReason.NO_DATA)

    private fun <T> ok(value: ContractResult<T>): T {
        assertTrue(value.toString(), value is ContractResult.Success)
        return (value as ContractResult.Success).value
    }
    private fun open() = SqliteGameChangeStore(context, InputSource.SIMULATION, name).apply {
        trialDefinitions = TrialDefinitionSource {
            definitionReads++
            if (definitionsAvailable) ContractResult.Success(definition)
            else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        }
    }
    override fun setUp() {
        super.setUp()
        name = "rest-multi-f-${System.nanoTime()}.db"
        val base = ok(AssetTrialDefinitionProvider(context.assets).load())
        definition = TrialDefinition(base.expansions, base.rentalRates, base.facilityOffers,
            base.fuelBonusPerRental, base.questRewards, base.provenance, base.delivery,
            DrivingIncomeRules(base.rules, 500_000, GameMoney(1_000)), base.rental,
            RestRules(base.rules, 1_000, 1_000, 2_000, 150), base.restPricing)
        store = open()
        identity = GameIdentity(ok(store.localProfileId()), store.source)
        ok(store.activateObservedDriving(identity.profileId))
        ok(store.restDriving.activate(identity.profileId))
    }
    override fun tearDown() {
        store.close()
        context.deleteDatabase(name) // 이 시험만 생성한 고유 DB.
        super.tearDown()
    }
    private fun raw(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(
        context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun rows(): List<String> = buildList {
        raw { db ->
            for (table in listOf("account", "settlements", "driving_days", "driving_drives",
                "observed_driving_state", "observed_driving_receipts", "rest_module", "rest_contexts",
                "rest_departures", "rest_driving_receipts", "rest_log", "rest_head")) {
                db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { c ->
                    while (c.moveToNext()) add(table + ":" + (0 until c.columnCount).joinToString("|") {
                        if (c.isNull(it)) "<null>" else c.getString(it)
                    })
                }
            }
        }
    }
    private fun observation(t: Long, gear: Gear = Gear.DRIVE) = VehicleObservation(
        identity.source, t, EpochMillis(10_000_000 + t),
        Observation.Available(if (gear == Gear.PARK) 0.0 else 10.0),
        Observation.Available(gear), Observation.Available(gear != Gear.PARK))
    private fun area(t: Long, inside: Boolean) = Observation.Available(RestAreaProof(
        "multi-fixture-area", RestPlace.SERVICE_AREA, ContentVersion("multi-fixture"),
        "https://example.test/rest", "fixture-licence", "2026-11-10", identity.source,
        inside, t, EpochMillis(10_000_000 + t)))
    private fun span(a: Long, b: Long, fromGear: Gear = Gear.DRIVE) = RestObservedSpan(
        clock.bootId, observation(a, fromGear), observation(b), missing, missing)
    private fun trip(id: String, spans: List<RestObservedSpan>): RestDriveContext {
        val segments = spans.map { s ->
            val a = (s.from.speedMps as Observation.Available).value
            val b = (s.to.speedMps as Observation.Available).value
            val meters = BigDecimal.valueOf(a).add(BigDecimal.valueOf(b))
                .multiply(BigDecimal.valueOf(s.to.receivedElapsedMs - s.from.receivedElapsedMs))
                .divide(BigDecimal(2_000))
            DrivingSegment(s.from, s.to, meters)
        }
        val original = ObservedTrip(id, spans.first().from, Observation.Available(ZoneId.of("Asia/Seoul")),
            ContractResult.Success(definition.driving), segments.fold(BigDecimal.ZERO) { a, b -> a + b.meters }, segments)
        return RestDriveContext(identity, original, definition.rest, definition.restPricing,
            spans.mapIndexed { i, s -> RestSegmentEvidence(i, clock.domain, s, policy) }, clock)
    }
    private fun capture(value: RestDriveContext) {
        val revision = ok(store.restDriving.state(identity.profileId)).revision
        ok(store.atomic { db ->
            val before = ok(store.observedDriving.read(db, identity))
            val after = before.copy(revision = before.revision + 1, receiver = clock.domain,
                outbox = before.outbox + value.trip)
            ok(store.observedDriving.save(db, identity, before.revision, after))
            store.restDriving.captureIn(db, identity, revision, after.revision, value)
        })
    }
    private fun departure(driveId: String, request: String, a: Long, b: Long,
        parkedFrom: Long, parkedTo: Long, exitAt: Long): RestDepartureInput {
        val parked = RestObservedSpan(clock.bootId, observation(parkedFrom, Gear.PARK),
            observation(parkedTo, Gear.PARK), area(parkedFrom, true), area(parkedTo, true))
        val exit = RestObservedSpan(clock.bootId, parked.to, observation(exitAt),
            area(parkedTo, true), area(exitAt, false))
        return RestDepartureInput(RequestId(request), identity, driveId, listOf(span(a, b)),
            listOf(parked), exit, definition.rest, policy, clock)
    }
    private fun prepare(input: RestDepartureInput): RestAnchoredDeparture {
        val revision = ok(store.restDriving.state(identity.profileId)).revision
        return ok(store.atomic { store.restDriving.prepareDepartureIn(it, identity, revision, input) })
    }

    fun testTwoDeparturesRefreshCarriedGrantInOnePaymentAndReplayWithoutMutation() {
        val seed = trip("multi-seed", listOf(span(3_500, 4_000)))
        capture(seed)
        prepare(departure(seed.trip.id, "multi-seed-depart", 0, 1_000, 1_500, 2_500, 3_500))
        val seedCommit = ok(store.restDriving.settleAndAck(identity.profileId, seed.trip.id))
        assertEquals(RestBonusState(1_500, 0), seedCommit.remaining)
        assertEquals(GameMoney(7), seedCommit.observed.commit.amount)

        val main = trip("multi-one-original", listOf(span(4_000, 5_000),
            span(6_001, 7_001, Gear.PARK), span(7_001, 8_001),
            span(9_002, 10_002, Gear.PARK), span(10_002, 11_502)))
        capture(main)
        val firstInput = departure(main.trip.id, "multi-first", 4_000, 5_000, 5_001, 6_001, 7_001)
        val secondInput = departure(main.trip.id, "multi-second", 7_001, 8_001, 8_002, 9_002, 10_002)
        val first = prepare(firstInput); val second = prepare(secondInput)
        assertEquals(listOf(1, 3), listOf(first.segment, second.segment))
        assertEquals(listOf(1L, 2L), listOf(first.packet.proof.stopNumber, second.packet.proof.stopNumber))
        val pending = ok(store.restDriving.state(identity.profileId))
        assertEquals(RestBonusState(1_500, 0), pending.confirmed)
        assertEquals(RestBonusState(500, 2), pending.effective)
        assertEquals(1, pending.pendingDrives); assertEquals(GameMoney(7), ok(store.balance()))

        // 두 번째 departure 저장 실패는 앞선 carry 소모·첫 갱신까지 함께 되돌려야 한다.
        raw { it.execSQL("CREATE TRIGGER multi_second_depart BEFORE INSERT ON rest_log " +
            "WHEN NEW.kind='DEPART' AND NEW.key='2' BEGIN SELECT RAISE(IGNORE); END") }
        val beforeFailure = rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),
            store.restDriving.settleAndAck(identity.profileId, main.trip.id))
        assertEquals(beforeFailure, rows())
        raw { it.execSQL("DROP TRIGGER multi_second_depart") }

        val result = ok(store.restDriving.settleAndAck(identity.profileId, main.trip.id))
        assertEquals(main, result.context); assertEquals(listOf(first, second), result.departures)
        assertEquals(RestBonusState(500, 2), result.remaining)
        assertEquals(3_500L, result.price.boostedMs)
        assertEquals(listOf(0, 2, 4), result.price.slices.map { it.window.segmentIndex })
        assertEquals(listOf(1_000L, 1_000L, 1_500L), result.price.slices.map { it.window.untilOffsetMs })
        // M=45m, B=35m: floor(45 + 35*0.5)=62. 앞선 seed 5m/7원은 별도 원주행이다.
        assertEquals(45L, result.observed.commit.recordedMeters)
        assertEquals(45L, result.observed.commit.rewardedMeters)
        assertEquals(50L, result.observed.commit.day.rewardedMeters)
        assertEquals(GameMoney(62), result.observed.commit.amount)
        assertEquals(RequestId("drive/multi-one-original"), result.observed.commit.settlement!!.transaction.requestId)
        assertEquals(GameMoney(69), ok(store.balance()))
        assertEquals(result.remaining, ok(RestLedger(store).state()))
        assertTrue(ok(store.atomic { store.observedDriving.read(it, identity) }).outbox.isEmpty())
        raw { db ->
            db.rawQuery("SELECT COUNT(*) FROM settlements", null).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM settlements WHERE request_id='drive/multi-one-original'", null)
                .use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.rawQuery("SELECT result FROM rest_log WHERE kind='DEPART' AND key IN ('1','2') ORDER BY seq", null).use {
                val before = mutableListOf<Long>(); val after = mutableListOf<Long>()
                while (it.moveToNext()) {
                    val json = JSONObject(it.getString(0))
                    before += json.getJSONObject("before").getLong("remaining")
                    after += json.getJSONObject("after").getLong("remaining")
                    assertTrue(json.getJSONObject("started").getBoolean("refreshed"))
                }
                assertEquals(listOf(500L, 1_000L), before); assertEquals(listOf(2_000L, 2_000L), after)
            }
        }
        val state = ok(store.restDriving.state(identity.profileId))
        assertEquals(result.remaining, state.confirmed); assertEquals(state.confirmed, state.effective)
        assertEquals(2L, state.confirmedDrives); assertEquals(0, state.pendingDrives)
        val frozenRows = rows(); store.close(); definitionsAvailable = false
        val readsBeforeReplay = definitionReads; store = open()
        assertEquals(result, ok(store.restDriving.settleAndAck(identity.profileId, main.trip.id)))
        assertEquals(seedCommit, ok(store.restDriving.settleAndAck(identity.profileId, seed.trip.id)))
        assertEquals(first, ok(store.atomic { store.restDriving.prepareDepartureIn(it, identity, 0, firstInput) }))
        assertEquals(second, ok(store.atomic { store.restDriving.prepareDepartureIn(it, identity, 0, secondInput) }))
        assertEquals(result.observed, ok(store.observedDrivingReceipt(main.trip.id)))
        assertEquals(readsBeforeReplay, definitionReads)
        assertEquals(state, ok(store.restDriving.state(identity.profileId)))
        assertEquals(frozenRows, rows())
    }
}
