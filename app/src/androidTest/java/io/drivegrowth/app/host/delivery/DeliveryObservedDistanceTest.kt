package io.drivegrowth.app.host.delivery

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.HostCheckpointPhase
import io.drivegrowth.app.data.SqliteObservedDrivingJournal
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import io.drivegrowth.core.driving.DrivingIncomeRules
import io.drivegrowth.core.garage.FirstGrant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** VEHICLE 원장의 시험 표본으로 거래와 관측 연결을 검증한다. */
@Suppress("DEPRECATION")
class DeliveryObservedDistanceTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private val database = "delivery-observed-${UUID.randomUUID()}.db"
    private val source = InputSource.VEHICLE
    private val version = RuleVersion("distance-test")
    private val receiver = "distance-receiver"
    private val missing = Observation.Missing(MissingReason.NO_DATA)
    private lateinit var games: GameSessionFactory
    private lateinit var identity: GameIdentity
    private lateinit var recorder: SessionObservedDrivingSource
    private lateinit var proposed: DeliveryProposed
    private var storeElapsed = 0L
    private var storeUtc = at(0)

    private fun factory() = GameSessionFactory(context, mapOf(source to database), source, null) { storeUtc.value }
        .also { it.deliveryDistanceElapsedRealtime = { storeElapsed } }

    override fun setUp() {
        super.setUp()
        games = factory()
        val onboarding = StoredOnboardingHostSource(games)
        identity = ok(onboarding.read()).identity
        ok(onboarding.choose(identity, FirstGrant.CANDIDATES.first()))
        ok(games.open()).use { game ->
            ok(game.store.activateObservedDriving(identity.profileId))
            val catalog = ok(game.content).catalog
            val offer = DeliveryOffer.Income(DeliveryOfferId("offer-0"), catalog.vehicles.first().vehicleId, GameMoney(10))
            val list = DeliveryList(0, source, LocalDate.of(2026, 11, 12), catalog.contentVersion, version, listOf(offer), 1)
            ok(game.store.delivery.saveList(list))
            proposed = DeliveryProposed(DestinationKey("distance-fixture"), EstimatedRouteDistance(10_000, source), list, list.offers)
        }
        recorder = SessionObservedDrivingSource(games, SqliteObservedDrivingJournal(),
            ObservedDrivingReducer(RecordingPolicy(5_000, 5_000, 1_000, 3_000, 0.1, 0.5)) { UUID.randomUUID().toString() }) {
            ContractResult.Success(DrivingIncomeRules(version, 500_000, GameMoney(10)))
        }
    }

    override fun tearDown() {
        context.deleteDatabase(database)
        super.tearDown()
    }

    private fun at(elapsed: Long) = EpochMillis(1_794_441_600_000L + elapsed)

    private fun observe(elapsed: Long, gear: Gear, speed: Double? = if (gear == Gear.PARK) 0.0 else 10.0,
        receivedNow: Long = elapsed, nowUtc: EpochMillis = at(receivedNow)) = ok(record(elapsed, gear, speed, receivedNow, nowUtc))

    private fun record(elapsed: Long, gear: Gear, speed: Double?, receivedNow: Long,
        nowUtc: EpochMillis = at(receivedNow)): ContractResult<DrivingRecordingResult> {
        storeElapsed = receivedNow
        storeUtc = nowUtc
        val frame = DrivingFrame(VehicleObservation(source, elapsed, at(elapsed),
            speed?.let { Observation.Available(it) } ?: missing, Observation.Available(gear),
            Observation.Available(gear != Gear.PARK)), Observation.Available(ZoneOffset.UTC))
        return recorder.observe(receiver, frame, RecordingClock(receivedNow, nowUtc))
    }

    private fun observedState() = ok(games.open()).use { game ->
        ok(game.store.atomic { db -> game.store.observedDriving.read(db, identity) })
    }

    private fun select(name: String, elapsed: Long, utc: EpochMillis = at(elapsed)): Pair<SessionDeliveryJournal, DeliveryHostOperation.Select> {
        storeElapsed = elapsed
        storeUtc = utc
        val journal = SessionDeliveryJournal(games)
        val command = DeliveryHostOperation.Select(proposed, proposed.offers.single().id, RequestId(name), RouteId("$name-route"), utc)
        ok(journal.save(identity, command))
        ok(journal.execute(identity, command))
        return journal to command
    }

    private fun distance(factory: GameSessionFactory = games) =
        ok(FactoryDeliveryHostQueries(factory) { it.terms() }.read(identity)).vehicleObserved

    fun testOnlyParkSealedDistanceOfBoundRouteSurvivesOriginalCommandAndSampleReplay() {
        observe(1_000, Gear.PARK)
        val (journal, command) = select("first", 1_000)
        assertEquals(missing, distance())
        observe(2_000, Gear.DRIVE)
        ok(journal.execute(identity, command))
        observe(3_000, Gear.DRIVE)
        assertEquals(missing, distance())
        observe(4_000, Gear.PARK)
        val terminal = observe(7_000, Gear.PARK)
        assertEquals(1, terminal.committed.size)
        val expected = Observation.Available(ObservedDistance(15L, source))
        assertEquals(expected, distance())
        observe(7_000, Gear.PARK)
        ok(journal.execute(identity, command))
        assertEquals(expected, distance())
        assertEquals(missing, distance(factory()))

        ok(journal.finish(identity, command))
        ok(games.open()).use { game ->
            ok(game.store.delivery.beginRoute(command.requestId, RouteId("replacement"), at(7_000), command.route))
        }
        assertEquals(missing, distance())
    }

    fun testGapAndSecondDepartureInvalidateFirstTripWithoutErasingItsReceipt() {
        observe(1_000, Gear.PARK)
        select("first", 1_000)
        observe(2_000, Gear.DRIVE)
        observe(3_000, Gear.DRIVE)
        observe(4_000, Gear.PARK)
        val terminal = observe(7_000, Gear.PARK)
        val receipt = terminal.committed.single()
        assertEquals(Observation.Available(ObservedDistance(15L, source)), distance())
        observe(8_000, Gear.DRIVE, null)
        assertEquals(missing, distance())
        observe(9_000, Gear.DRIVE)
        assertEquals(missing, distance())
        ok(games.open()).use { assertEquals(receipt, ok(it.store.observedDrivingReceipt(receipt.trip.id))) }
    }

    fun testTripStartedBeforeSelectionAndLaterRouteCannotSupplyPreviousMeters() {
        observe(1_000, Gear.DRIVE)
        val (journal, command) = select("first", 1_000)
        observe(2_000, Gear.DRIVE)
        observe(3_000, Gear.PARK)
        observe(6_000, Gear.PARK)
        assertEquals(missing, distance())
        ok(journal.finish(identity, command))
        ok(games.open()).use {
            ok(it.store.delivery.abandon(rules = DeliveryChangeRules(version, 10), requestId = command.requestId))
        }
        select("second", 6_000)
        assertEquals(missing, distance())
    }

    fun testDepartureReceivedBeforeSelectionCannotBindWhenProcessedAfterIt() {
        observe(1_000, Gear.PARK)
        select("late", 2_200, utc = at(1_700))
        assertNotNull(observe(2_000, Gear.DRIVE, receivedNow = 2_400, nowUtc = at(2_200)).state.trip)
        observe(3_000, Gear.DRIVE)
        observe(4_000, Gear.PARK)
        assertEquals(1, observe(7_000, Gear.PARK).committed.size)
        assertEquals(missing, distance())
    }

    fun testSecondTripOnTheSameRouteDoesNotSupplyPartialDistance() {
        observe(1_000, Gear.PARK)
        select("two-trips", 1_000)
        observe(2_000, Gear.DRIVE)
        observe(3_000, Gear.DRIVE)
        observe(4_000, Gear.PARK)
        observe(7_000, Gear.PARK)
        assertEquals(Observation.Available(ObservedDistance(15L, source)), distance())
        observe(8_000, Gear.DRIVE)
        observe(9_000, Gear.PARK)
        assertEquals(1, observe(12_000, Gear.PARK).committed.size)
        assertEquals(missing, distance())
    }

    fun testBindingWriteFailureRollsBackRecordedFrameAndSameInputCanBeRetried() {
        observe(1_000, Gear.PARK)
        select("retry", 1_000)
        val before = observedState()
        fun exec(sql: String) = ok(games.open()).use { game ->
            ok(game.store.atomic { db -> db.execSQL(sql); ContractResult.Success(Unit) })
        }
        exec("CREATE TRIGGER reject_distance BEFORE UPDATE OF observed_binding ON delivery_routes BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), record(2_000, Gear.DRIVE, 10.0, 2_000))
        assertEquals(before, observedState())
        assertEquals(missing, distance())
        exec("DROP TRIGGER reject_distance")
        exec("CREATE TRIGGER rewrite_distance AFTER UPDATE OF observed_binding ON delivery_routes WHEN NEW.observed_binding IS NOT NULL " +
            "BEGIN UPDATE delivery_routes SET observed_binding=NULL WHERE request_id=NEW.request_id; END")
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), record(2_000, Gear.DRIVE, 10.0, 2_000))
        assertEquals(before, observedState())
        exec("DROP TRIGGER rewrite_distance")
        observe(2_000, Gear.DRIVE)
        observe(3_000, Gear.DRIVE)
        observe(4_000, Gear.PARK)
        observe(7_000, Gear.PARK)
        assertEquals(Observation.Available(ObservedDistance(15L, source)), distance())
    }

    fun testSavedChangeRejectsReplacedRouteWithoutChangingTermsOrCheckpoint() {
        observe(1_000, Gear.PARK)
        val (journal, selected) = select("change", 1_000)
        observe(2_000, Gear.DRIVE)
        observe(3_000, Gear.DRIVE)
        observe(4_000, Gear.PARK)
        observe(7_000, Gear.PARK)
        val meters = (distance() as Observation.Available).value.meters
        ok(journal.finish(identity, selected))
        val command = change(selected, meters)
        ok(journal.save(identity, command))
        val terms = ok(games.open()).use { game ->
            ok(game.store.delivery.beginRoute(selected.requestId, RouteId("external-replacement"), at(7_000), selected.route))
            ok(game.store.delivery.terms())
        }
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), journal.execute(identity, command))
        ok(games.open()).use { game ->
            assertEquals(terms, ok(game.store.delivery.terms()))
            assertEquals(HostCheckpointPhase.SAVED, ok(game.store.hostCommands.load(identity.profileId))?.phase)
            assertEquals(RouteId("external-replacement"), ok(game.store.delivery.inProgress())?.route)
        }
    }

    fun testValidChangeReplaysOriginalReceiptWithoutRequiringTheOldRouteAgain() {
        observe(1_000, Gear.PARK)
        val (journal, selected) = select("valid-change", 1_000)
        observe(2_000, Gear.DRIVE)
        observe(3_000, Gear.DRIVE)
        observe(4_000, Gear.PARK)
        observe(7_000, Gear.PARK)
        val meters = (distance() as Observation.Available).value.meters
        ok(journal.finish(identity, selected))
        val command = change(selected, meters)
        ok(journal.save(identity, command))
        val receipt = ok(journal.execute(identity, command))
        assertTrue(receipt is DeliveryHostReceipt.Changed)
        assertEquals(missing, distance())
        val reopened = SessionDeliveryJournal(factory())
        assertEquals(command, ok(reopened.pending(identity)))
        assertEquals(receipt, ok(reopened.execute(identity, command)))
        ok(games.open()).use { assertEquals(command.route, ok(it.store.delivery.inProgress())?.route) }
    }

    private fun change(selected: DeliveryHostOperation.Select, meters: Long): DeliveryHostOperation.Change {
        val offer = (proposed.offers.single() as DeliveryOffer.Income).copy(id = DeliveryOfferId("change-0"))
        val next = proposed.copy(destination = DestinationKey("new-fixture"), list = proposed.list.copy(offers = listOf(offer)),
            offers = listOf(offer))
        return DeliveryHostOperation.Change(DeliveryChangeRules(version, 10),
            DeliveryChangeOffer(selected.requestId, meters, meters + next.estimated.meters, next), offer.id,
            RouteId("new"), selected.route)
    }

    fun testVersion19UpgradePreservesExistingRouteAndDoesNotBackfillDistance() {
        observe(1_000, Gear.PARK)
        select("legacy", 1_000)
        observe(2_000, Gear.DRIVE)
        observe(3_000, Gear.DRIVE)
        observe(4_000, Gear.PARK)
        val receipt = observe(7_000, Gear.PARK).committed.single()
        val balance = ok(games.open()).use { ok(it.store.balance()) }
        val progress = ok(games.open()).use { ok(it.store.delivery.inProgress()) }
        makeVersion19()
        val reopened = factory()
        ok(reopened.open()).use {
            assertEquals(progress, ok(it.store.delivery.inProgress()))
            assertEquals(balance, ok(it.store.balance()))
            assertEquals(receipt, ok(it.store.observedDrivingReceipt(receipt.trip.id)))
        }
        raw().use { db ->
            assertEquals(20, db.version)
            db.rawQuery("SELECT observed_binding FROM delivery_routes WHERE route=?", arrayOf(progress!!.route.value)).use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertFalse(it.moveToNext())
            }
        }
        assertEquals(missing, distance(reopened))
    }

    fun testStaleParkedDistanceCannotBeReusedOrRenewedByDuplicateFrame() {
        observe(1_000, Gear.PARK)
        val (journal, selected) = select("freshness", 1_000)
        observe(2_000, Gear.DRIVE)
        observe(3_000, Gear.DRIVE)
        observe(4_000, Gear.PARK)
        observe(7_000, Gear.PARK)
        assertEquals(Observation.Available(ObservedDistance(15L, source)), distance())
        ok(journal.finish(identity, selected))
        val pending = change(selected, 15)
        ok(journal.save(identity, pending))
        val before = observedState()

        storeElapsed = 12_000
        assertEquals(Observation.Available(ObservedDistance(15L, source)), distance())
        storeElapsed = 12_001
        val stale = Observation.Missing(MissingReason.STALE)
        assertEquals(stale, distance())
        observe(7_000, Gear.PARK, receivedNow = 12_001)
        assertEquals(before, observedState())
        assertEquals(stale, distance())
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), journal.execute(identity, pending))
        ok(games.open()).use {
            assertEquals(selected.route, ok(it.store.delivery.inProgress())?.route)
            assertEquals(HostCheckpointPhase.SAVED, ok(it.store.hostCommands.load(identity.profileId))?.phase)
        }
    }

    fun testVersion19SavedChangeWithoutProofRemainsCancellableAndDoesNotExecute() {
        observe(1_000, Gear.PARK)
        val (journal, selected) = select("legacy-saved", 1_000)
        ok(journal.finish(identity, selected))
        val command = change(selected, 15).copy(observedRoute = null)
        ok(journal.save(identity, command))
        val before = ok(games.open()).use { ok(it.store.balance()) to ok(it.store.delivery.inProgress()) }
        makeVersion19()

        val reopenedFactory = factory()
        val reopened = SessionDeliveryJournal(reopenedFactory)
        assertEquals(command, ok(reopened.pending(identity)))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), reopened.execute(identity, command))
        ok(reopenedFactory.open()).use {
            assertEquals(before, ok(it.store.balance()) to ok(it.store.delivery.inProgress()))
            assertEquals(HostCheckpointPhase.SAVED, ok(it.store.hostCommands.load(identity.profileId))?.phase)
        }
        ok(reopened.cancelUnstarted(identity, command))
        assertNull(ok(reopened.pending(identity)))
    }

    private fun raw() = SQLiteDatabase.openDatabase(context.getDatabasePath(database).path, null, SQLiteDatabase.OPEN_READWRITE)

    private fun makeVersion19() {
        raw().use { db ->
            db.execSQL("ALTER TABLE delivery_routes RENAME TO delivery_routes_v20")
            db.execSQL("CREATE TABLE delivery_routes (request_id TEXT PRIMARY KEY NOT NULL, route TEXT NOT NULL, started_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO delivery_routes SELECT request_id, route, started_at FROM delivery_routes_v20")
            db.execSQL("DROP TABLE delivery_routes_v20")
            db.version = 19
        }
    }

    fun testFailedUpgradeLeavesVersion19AndOriginalRows() {
        observe(1_000, Gear.PARK)
        val (_, command) = select("migration-failure", 1_000)
        val before = raw().use { db ->
            // 구버전이 새 필드에 이미 값을 가진 비정상 상태는 복원하지 않는다.
            db.version = 19
            db.rawQuery("SELECT request_id, route, started_at, observed_binding FROM delivery_routes", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(command.route.value, rows.getString(1))
                (0 until rows.columnCount).map(rows::getString)
            }
        }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), factory().open())
        raw().use { db ->
            assertEquals(19, db.version)
            db.rawQuery("SELECT request_id, route, started_at, observed_binding FROM delivery_routes", null).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(before, (0 until rows.columnCount).map(rows::getString))
            }
        }
    }

    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
}
