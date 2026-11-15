package io.drivegrowth.app.host.rental

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.host.rest.ForegroundRestObservationProducer
import io.drivegrowth.app.host.rest.RestEvidencePolicy
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.RentalRequest
import io.drivegrowth.core.rental.SimulatedCustomer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/** 실제 제품 source/원장 연결의 명시 SIMULATION 시험. 가상 clock/customer를 SDK·실차 결과로 표시하지 않는다. */
@Suppress("DEPRECATION")
class RentalProductSourceBindingTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext
    private lateinit var names: Map<InputSource, String>
    private lateinit var games: GameSessionFactory
    private lateinit var definition: TrialDefinition
    private lateinit var driving: SessionObservedDrivingSource
    private lateinit var producer: SessionRentalProductSource
    private lateinit var identity: GameIdentity
    private lateinit var readJournal: SqliteRentalProductJournal
    private var elapsed = 0L
    private var wallOffset = 0L
    private var rawClockSample: (() -> RentalClock)? = null
    private var requests = 0
    private var coldProof: JSONObject? = null
    private var retainColdDatabase = false
    private val coldRun get() = File(context.cacheDir, "rental-forward-cold-enabled")
    private val coldEvidence get() = File(context.cacheDir, "rental-forward-cold-ready.json")
    private val base = Instant.parse("2026-11-10T03:00:00Z").toEpochMilli()
    // 한 시간 대여를 제어 clock으로 진행시키는 시험 정책. 제품의 2500ms 신선도/연속성 정책은 변경하지 않는다.
    private var policy = RecordingPolicy(7_200_000, 7_200_000, 1000, 3000, 0.1, 0.5)
    private fun clock() = RentalClock("explicit-simulation-boot", EpochMillis(base + elapsed + wallOffset), elapsed)
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    override fun setUp() {
        super.setUp()
        if (name == "testNormalPreparedForwardRepairConnectsAnUnfinishedRentalToLiveDrivingAndPark" &&
            coldRun.exists()) {
            assertTrue(context.packageName.startsWith("io.drivegrowth.verify."))
            if (coldEvidence.exists()) coldProof = JSONObject(coldEvidence.readText())
        }
        val prefix = coldProof?.getString("database") ?: "rental-product-binding-${UUID.randomUUID()}"
        names = InputSource.entries.associateWith { "$prefix-${it.name}.db" }
        games = factory()
        definition = ok(AssetTrialDefinitionProvider(context.assets).load())
        val onboarding = StoredOnboardingHostSource(games)
        val initial = ok(onboarding.read()); identity = initial.identity
        if (coldProof == null) {
            ok(onboarding.choose(identity, FirstGrant.CANDIDATES.first()))
            ok(games.open()).use { ok(it.store.activateObservedDriving(identity.profileId)) }
        }
        bindRecorder()
        readJournal = SqliteRentalProductJournal(games, { error("조회는 현재 정의를 읽지 않는다") },
            { error("조회는 현재 clock을 읽지 않는다") }, { error("조회는 현재 원점을 읽지 않는다") })
    }
    private fun bindRecorder() {
        var trips = 0
        driving = SessionObservedDrivingSource(games, SqliteObservedDrivingJournal(),
            ObservedDrivingReducer(policy) { "explicit-simulation-drive-${++trips}" }, { definition.drivingIncomeRules() })
        producer = source()
    }

    override fun tearDown() {
        if (!retainColdDatabase && ::names.isInitialized) names.values.forEach(context::deleteDatabase)
        if (coldProof != null) {
            coldEvidence.delete()
            coldRun.delete()
        }
        super.tearDown()
    }
    private fun factory() = GameSessionFactory(context, names, InputSource.SIMULATION) {
        when (val loaded = AssetTrialDefinitionProvider(context.assets).load()) {
            is ContractResult.Failure -> loaded
            is ContractResult.Success -> loaded.value.questDefinitions(ZoneId.of("UTC"))
        }
    }
    private fun visitSeed(visit: RentalVisitDefinition): Int {
        val desired = FirstGrant.CANDIDATES.first()
        val entries = visit.demand.entries.toList()
        val offset = entries.takeWhile { it.key != desired }.sumOf { it.value.toLong() }
        val weight = visit.demand.getValue(desired).toLong()
        // 쓰기 이전에 중앙 분포에서 재현 seed를 고른다. 저장 후 고객/사고를 다시 뽑지 않는다.
        return (0..100_000).first { value ->
            val rng = Random(value)
            val car = rng.nextLong(entries.sumOf { it.value.toLong() })
            val duration = rng.nextLong(visit.durations.values.sumOf { it.toLong() })
            rng.nextLong(visit.customers.sumOf { it.weight.toLong() })
            car in offset until offset + weight && duration < visit.durations.getValue(1) && rng.nextInt(100) >= 13
        }
    }
    private fun source(): SessionRentalProductSource {
        return SessionRentalProductSource(games, { ContractResult.Success(definition) }, policy,
            { ContractResult.Success(rawClockSample?.invoke() ?: clock()) }, { Random(visitSeed(it.definition)) }, { RequestId("explicit-simulation-visit-${++requests}") })
    }
    private fun feed(at: Long, receiver: String = "simulation-receiver-1",
        gear: Gear = Gear.DRIVE): ContractResult<RentalRuntimeSnapshot> {
        elapsed = at
        val parked = gear == Gear.PARK
        val input = VehicleObservation(identity.source, elapsed, clock().epoch, Observation.Available(if (parked) 0.0 else 1.0),
            Observation.Available(gear), Observation.Available(!parked))
        val frame = DrivingFrame(input, Observation.Available(ZoneId.of("UTC")))
        val original = driving.observe(receiver, frame, RecordingClock(elapsed, input.receivedAt))
        ok(original)
        return producer.observe(frame, original)
    }
    private val restClock get() = RestClockDomain("explicit-simulation-rest", clock().bootId)

    private fun restProducer() = ForegroundRestObservationProducer(identity, definition::restRules,
        RestEvidencePolicy(policy.maxGapMs, policy.maxAgeMs, policy.maxUtcSkewMs, policy.movingMps, policy.stationaryMps),
        definition.restPricing)

    private fun restRecorder(rest: ForegroundRestObservationProducer) =
        SessionObservedDrivingSource(games, SqliteObservedDrivingJournal(),
            ObservedDrivingReducer(policy) { "explicit-simulation-drive" }, definition::drivingIncomeRules)
            .also { it.restObservationProvider = rest }

    private fun record(
        at: Long, rest: ForegroundRestObservationProducer?, receiver: String = "simulation-rest", parked: Boolean = false,
    ): Pair<DrivingFrame, ContractResult<DrivingRecordingResult>> {
        elapsed = at
        val input = VehicleObservation(identity.source, at, clock().epoch, Observation.Available(if (parked) 0.0 else 10.0),
            Observation.Available(if (parked) Gear.PARK else Gear.DRIVE), Observation.Available(!parked))
        val frame = DrivingFrame(input, Observation.Available(ZoneId.of("UTC")))
        if (rest != null) ok(rest.observe(receiver, restClock, input, Observation.Missing(MissingReason.NO_DATA)))
        val recorded = driving.observe(receiver, frame, RecordingClock(at, input.receivedAt))
        ok(recorded)
        return frame to recorded
    }

    private fun activeRentalAtStage13(rest: ForegroundRestObservationProducer?): RentalLedger.Pending {
        for (at in listOf(0L, 1000L)) {
            val (frame, result) = record(at, rest)
            ok(producer.observe(frame, result))
        }
        val (frame, result) = record(720_000, rest)
        return ok(producer.observe(frame, result)).pending.single().also {
            assertEquals(EpochMillis(base + 720_000), it.cursor)
        }
    }

    private fun reopenRental() {
        games = factory()
        producer = source()
        producer.reconnect()
    }

    private fun reopenRestRecorder(): ForegroundRestObservationProducer {
        val rest = restProducer()
        ok(games.open()).use { game ->
            val (state, contexts) = ok(game.store.atomic { db ->
                val state = ok(game.store.observedDriving.read(db, identity))
                ContractResult.Success(state to RestAtomicStorage.contexts(db, identity).map { it.value })
            })
            ok(rest.restore(contexts))
            if (state.trip != null) {
                val anchor = checkNotNull(state.anchor)
                ok(rest.observe(checkNotNull(state.receiver), restClock, anchor, Observation.Missing(MissingReason.NO_DATA)))
            }
        }
        driving = restRecorder(rest)
        return rest
    }

    private fun rentalRows(): List<String> = ok(games.open()).use { game ->
        ok(game.store.read { db ->
            buildList {
                for (table in listOf("rentals", "rental_result_basis", "rental_product_state",
                    "rental_product_inactive", "visits", "settlements", "account")) {
                    db.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { rows ->
                        while (rows.moveToNext()) add(table + ":" +
                            (0 until rows.columnCount).joinToString("|") { rows.getString(it) ?: "<null>" })
                    }
                }
            }
        })
    }

    private fun assertRentalThrough(id: RequestId, at: Long) = ok(games.open()).use { game ->
        val pending = ok(game.store.rental.pending()).single()
        assertEquals(id, pending.id)
        assertEquals(EpochMillis(base + at), pending.cursor)
        ok(game.store.read { db ->
            val basis = checkNotNull(RentalBasisStorage.load(db, game.store, id))
            val period = basis.periods.single()
            assertEquals(EpochMillis(base), period.from)
            assertEquals(pending.cursor, period.through)
            assertEquals(at, period.normalMillis)
            assertNull(basis.settlementId)
        })
    }

    private fun enableRestRecorder(): ForegroundRestObservationProducer {
        ok(games.open()).use { ok(it.store.restDriving.activate(identity.profileId)) }
        return restProducer().also { driving = restRecorder(it) }
    }

    fun testReopenedSimulationRentalContinuesAfterOneSecondDepartureGap() {
        val rental = activeRentalAtStage13(null)
        val firstPark = 721_000L
        val terminal = firstPark + policy.parkMs + 1_000L
        for (at in listOf(firstPark, firstPark + policy.parkMs, terminal)) {
            val (frame, result) = record(at, null, parked = true)
            assertTrue(ok(producer.observe(frame, result)).deferred.isEmpty())
        }
        val origin = clock()
        reopenRental()
        val startAt = terminal + 1_000L
        val (start, started) = record(startAt, null)
        assertTrue(ok(producer.observeSimulationDeparture(start, started, null, origin)).deferred.isEmpty())
        val nextAt = startAt + 60_000L
        val (next, advanced) = record(nextAt, null)
        val result = ok(producer.observe(next, advanced))
        assertTrue(result.deferred.toString(), result.deferred.isEmpty())
        assertEquals(EpochMillis(base + nextAt), result.pending.single { it.id == rental.id }.cursor)
        ok(games.open()).use { game -> ok(game.store.read { db ->
            val basis = checkNotNull(RentalBasisStorage.load(db, game.store, rental.id))
            val gap = basis.periods.single { it.from == origin.epoch }
            assertEquals(EpochMillis(base + startAt), gap.through)
            assertEquals(0, gap.weightedMillis.signum())
        }) }
    }

    fun testActiveRentalCrossesFromOrdinaryTerminalIntoRestAndBackWithoutLosingTime() {
        val active = activeRentalAtStage13(null)
        val firstPark = 721_000L
        val parkConfirmed = firstPark + policy.parkMs
        val terminal = parkConfirmed + 1_000
        // 주행 종료를 확인한 표본은 다음 구간의 주차 원점만 공급한다.
        for (at in listOf(firstPark, parkConfirmed, terminal)) {
            val (frame, result) = record(at, null, receiver = "simulation-rest", parked = true)
            assertTrue(ok(producer.observe(frame, result)).deferred.isEmpty())
        }
        val before = ok(games.open()).use { ok(it.store.rental.pending()).single() }
        assertEquals(active.id, before.id)
        assertEquals(clock().epoch, before.cursor)
        assertEquals(EpochMillis(base + firstPark), before.parkedSince)

        ok(games.open()).use { ok(it.store.restDriving.activate(identity.profileId)) }
        val rest = restProducer().also { driving.restObservationProvider = it }
        val restStart = terminal + 1_000
        val (start, recorded) = record(restStart, rest, receiver = SIMULATION_REST_RECEIVER)
        val crossing = ok(producer.observeSimulationDeparture(start, recorded, restClock, null))
        assertTrue(crossing.deferred.isEmpty())
        assertEquals(EpochMillis(base + restStart), crossing.pending.single().cursor)
        val crossingRows = rentalRows()
        assertTrue(ok(producer.observeSimulationDeparture(start, recorded, restClock, null)).deferred.isEmpty())
        assertEquals(crossingRows, rentalRows())

        val restPark = restStart + 61_000
        for (at in listOf(restStart + 60_000, restPark, restPark + 3_000)) {
            val (frame, result) = record(at, rest, receiver = SIMULATION_REST_RECEIVER, parked = at >= restPark)
            assertTrue(ok(producer.observe(frame, result)).deferred.isEmpty())
        }
        val nextOrdinary = restPark + 4_000
        val (ordinaryFrame, ordinaryRecord) = record(nextOrdinary, rest, receiver = SIMULATION_REST_RECEIVER)
        val continued = ok(producer.observe(ordinaryFrame, ordinaryRecord))
        assertTrue(continued.deferred.isEmpty())
        assertEquals(active.id, continued.pending.single().id)
        assertEquals(EpochMillis(base + nextOrdinary), continued.pending.single().cursor)
        ok(games.open()).use { game ->
            ok(game.store.read { db ->
                val basis = checkNotNull(RentalBasisStorage.load(db, game.store, active.id))
                val transition = basis.periods.single { it.from == EpochMillis(base + parkConfirmed) }
                assertEquals(EpochMillis(base + firstPark), transition.origin)
                assertEquals(EpochMillis(base + restStart), transition.through)
                assertEquals(RentalIncomeKind.PARKED, transition.kind)
                assertEquals(2_000L, transition.normalMillis)
                assertEquals(0L, transition.reducedMillis)
                assertEquals(0L, transition.cappedMillis)
                assertEquals(0, transition.weightedMillis.compareTo(2_000.toBigDecimal()))
                assertTrue(basis.periods.none { it.kind == RentalIncomeKind.UNOBSERVED })
                ContractResult.Success(Unit)
            })
        }
    }

    fun testOrdinarySimulationReplayAdvancesRentalFromStage13Through14And15Once() {
        val originalRest = enableRestRecorder()
        val rental = activeRentalAtStage13(originalRest)
        assertRentalThrough(rental.id, 720_000)
        val originalRequests = requests
        // 새 세션의 첫 재생 프레임은 저장된 13단계 주행 anchor에 이어진다.
        reopenRental()
        val restored = reopenRestRecorder()
        val (fourteen, savedFourteen) = record(780_000, restored)
        val beforeFourteen = rentalRows()
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            producer.observeOrdinarySimulationReplay(fourteen, savedFourteen, restClock.copy(domain = "foreign-domain")))
        assertEquals(beforeFourteen, rentalRows())
        assertTrue(ok(producer.observeOrdinarySimulationReplay(fourteen, savedFourteen, restClock)).deferred.isEmpty())
        assertRentalThrough(rental.id, 780_000)
        val afterFourteen = rentalRows()
        assertTrue(ok(producer.observeOrdinarySimulationReplay(fourteen, savedFourteen, restClock)).deferred.isEmpty())
        assertEquals(afterFourteen, rentalRows())

        val (fifteen, savedFifteen) = record(840_000, restored)
        assertTrue(ok(producer.observe(fifteen, savedFifteen)).deferred.isEmpty())
        assertRentalThrough(rental.id, 840_000)
        val afterFifteen = rentalRows()
        assertTrue(ok(producer.observe(fifteen, savedFifteen)).deferred.isEmpty())
        assertEquals(afterFifteen, rentalRows())
        assertEquals(originalRequests, requests)
    }

    fun testOrdinarySimulationReplayCoversRentalRecoveredFromOriginalPendingVisit() {
        val rest = enableRestRecorder()
        val (initial, initialRecorded) = record(0, rest)
        assertTrue(ok(producer.observe(initial, initialRecorded)).pending.isEmpty())
        record(1000, rest)

        val visitDefinition = ok(definition.rentalVisitDefinition())
        val journal = SqliteRentalProductJournal(games, { ContractResult.Success(visitDefinition) },
            { ContractResult.Success(clock()) }, { error("배정 저장에서는 이탈 원점을 읽지 않는다") })
        val cursor = ok(journal.cursor(identity))
        val catalog = ok(games.open()).use { game ->
            assertTrue(ok(game.store.rental.pending()).isEmpty())
            ok(game.content).catalog
        }
        val id = RequestId("original-unexecuted-rental-visit")
        val original = checkNotNull(ok(RentalVisitPlanner().prepare(
            identity, catalog, visitDefinition, cursor, clock(), Random(visitSeed(visitDefinition)), { id },
        )))
        assertEquals(original, ok(journal.save(original)))
        assertEquals(original, ok(journal.pending(identity)))

        reopenRental()
        val restored = reopenRestRecorder()
        val (first, recordedFirst) = record(1000, restored)
        val resumed = ok(producer.observeOrdinarySimulationReplay(first, recordedFirst, restClock))
        assertTrue(resumed.deferred.isEmpty())
        assertRentalThrough(id, 1000)
        assertNull(ok(readJournal.pending(identity)))
        val beforeRetry = rentalRows()
        assertTrue(ok(producer.observeOrdinarySimulationReplay(first, recordedFirst, restClock)).deferred.isEmpty())
        assertEquals(beforeRetry, rentalRows())

        val (second, recordedSecond) = record(2000, restored)
        assertTrue(ok(producer.observe(second, recordedSecond)).deferred.isEmpty())
        assertRentalThrough(id, 2000)
        val visits = rentalRows().filter { it.startsWith("visits:") }
        assertEquals(1, visits.size)
        assertTrue(visits.single().startsWith("visits:${id.value}|"))
        assertEquals(0, requests)
    }

    fun testOrdinaryReplayRecoversOriginalVisitAndPreExitSpanBeforePreparedSettlement() =
        assertOrdinaryReplayExitRecovery(prepareFirst = true)

    fun testOrdinaryReplayPreparesRecordedExitAtFirstNewReceiverSample() =
        assertOrdinaryReplayExitRecovery(prepareFirst = false)

    fun testOrdinaryReplayRetainsRecordedExitFirstSampleAfterReplayWriteFailure() =
        assertOrdinaryReplayExitRecovery(prepareFirst = false, failFirstReplay = true)

    private fun assertOrdinaryReplayExitRecovery(prepareFirst: Boolean, failFirstReplay: Boolean = false) {
        val rest = enableRestRecorder()
        val (initial, original) = record(0, rest)
        assertTrue(ok(producer.observe(initial, original)).pending.isEmpty())
        record(1000, rest)
        val preExitThrough = clock()
        val visitDefinition = ok(definition.rentalVisitDefinition())
        val journal = SqliteRentalProductJournal(games, { ContractResult.Success(visitDefinition) },
            { ContractResult.Success(clock()) }, { error("저장된 이탈 원점을 재계산하지 않는다") })
        val catalog = ok(games.open()).use { ok(it.content).catalog }
        val id = RequestId("original-visit-before-prepared-exit")
        val visit = checkNotNull(ok(RentalVisitPlanner().prepare(identity, catalog, visitDefinition,
            ok(journal.cursor(identity)), preExitThrough, Random(visitSeed(visitDefinition)), { id })))
        ok(journal.save(visit))

        elapsed = 1500
        val since = clock()
        val markerId = RequestId("prepared-exit-before-ordinary-replay")
        ok(journal.beginVerifiedInactive(identity, markerId, since, drivingExit = true, observedThrough = preExitThrough))
        val receiver = "ordinary-recovered-receiver"
        val (firstFrame, firstRecorded) = record(120_000, rest, receiver)
        val command = RentalInactiveInterval(markerId, since, since, clock())
        if (prepareFirst) {
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),
                journal.advanceInactiveAfterDrain(identity, command) { ContractResult.Failure(ContractError.STORAGE_FAILURE) })
        }
        val originalBoundary = marker()
        assertEquals(if (prepareFirst) RentalInactivePhase.PREPARED else RentalInactivePhase.RECORDED, originalBoundary.phase)
        assertEquals(if (prepareFirst) command else null, originalBoundary.interval)
        assertEquals(visit, ok(journal.pending(identity)))

        reopenRental()
        val (frame, recorded) = if (failFirstReplay) {
            raw().use { db ->
                db.execSQL("CREATE TRIGGER reject_pre_exit_replay BEFORE UPDATE OF cursor ON rentals " +
                    "WHEN NEW.cursor=${preExitThrough.epoch.value} BEGIN SELECT RAISE(IGNORE); END")
            }
            try {
                assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),
                    producer.observeOrdinarySimulationReplay(firstFrame, firstRecorded, restClock))
                assertEquals(originalBoundary.copy(phase = RentalInactivePhase.PREPARED, interval = command), marker())
                assertEquals(0, BigDecimal.ZERO.compareTo(weighted(id)))
            } finally {
                raw().use { it.execSQL("DROP TRIGGER reject_pre_exit_replay") }
            }
            record(121_000, rest, receiver)
        } else firstFrame to firstRecorded
        val recordingBefore = ok(recorded).state
        val result = ok(producer.observeOrdinarySimulationReplay(frame, recorded, restClock))
        assertTrue(result.deferred.isEmpty())
        assertNull(ok(journal.pending(identity)))
        assertEquals(originalBoundary.copy(phase = RentalInactivePhase.COMPLETED, interval = command), marker())
        assertEquals(frame.observation.receivedAt, result.pending.single { it.id == id }.cursor)
        val expectedWeight = if (failFirstReplay) "120500" else "119500"
        assertEquals(0, BigDecimal(expectedWeight).compareTo(weighted(id)))
        ok(games.open()).use { game ->
            ok(game.store.atomic { db ->
                assertEquals(recordingBefore, ok(game.store.observedDriving.read(db, identity)))
                val periods = checkNotNull(RentalBasisStorage.load(db, game.store, id)).periods
                val expectedKinds = listOf(RentalIncomeKind.DRIVING, RentalIncomeKind.UNOBSERVED, RentalIncomeKind.INACTIVE)
                assertEquals(if (failFirstReplay) expectedKinds + RentalIncomeKind.DRIVING else expectedKinds,
                    periods.map { it.kind })
                assertEquals(preExitThrough.epoch, periods[0].through)
                assertEquals(preExitThrough.epoch, periods[1].from)
                assertEquals(since.epoch, periods[1].through)
                assertEquals(since.epoch, periods[2].from)
                assertEquals(command.to.epoch, periods[2].through)
                ContractResult.Success(Unit)
            })
        }
        val rows = rentalRows()
        assertEquals(1, rows.count { it.startsWith("visits:${id.value}|") })
        assertTrue(ok(producer.observeOrdinarySimulationReplay(frame, recorded, restClock)).deferred.isEmpty())
        assertEquals(rows, rentalRows())
    }

    fun testOrdinarySimulationReplayRetriesSettlementAfterCoverageCommitted() {
        val rental = activeRentalAtStage13(enableRestRecorder())
        val end = rental.end.value - base
        val originalRequests = requests
        reopenRental()
        val rest = reopenRestRecorder()
        val (frame, recorded) = record(end, rest)
        rentalSettlementFault(true)
        try {
            val failed = ok(producer.observeOrdinarySimulationReplay(frame, recorded, restClock))
            assertEquals(mapOf(rental.id to ContractError.STORAGE_FAILURE), failed.deferred)
            assertEquals(VehicleActivity.RENTED, failed.pending.single().phase)
            assertNull(failed.firstCompleted)
            assertRentalThrough(rental.id, end)
            val covered = rentalRows()

            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
                producer.observeOrdinarySimulationReplay(frame, recorded, restClock.copy(domain = "foreign-domain")))
            val stillFailed = ok(producer.observeOrdinarySimulationReplay(frame, recorded, restClock))
            assertEquals(failed.deferred, stillFailed.deferred)
            assertEquals(covered, rentalRows())
        } finally {
            rentalSettlementFault(false)
        }

        // 대여 소비자가 다시 생성돼도 저장된 종료 커서에서 정산을 재시도한다.
        reopenRental()
        val retried = ok(producer.observeOrdinarySimulationReplay(frame, recorded, restClock))
        val completed = checkNotNull(retried.firstCompleted)
        assertTrue(retried.deferred.isEmpty())
        assertEquals(rental.id, completed.rentalId)
        assertEquals(VehicleActivity.RETURNING, retried.pending.single().phase)
        ok(games.open()).use { game ->
            val settlement = completed.receipt.settlement
            val basis = (ok(RentalResultBasisReader(game.store).read(rental.id)) as Observation.Available).value
            assertEquals(settlement.settlementId, basis.settlementId)
            assertEquals(end, basis.periods.single().normalMillis)
            assertEquals(GameMoney(1000 + settlement.transaction.money!!.amount.units), ok(game.store.balance()))
            assertEquals(1, ok(game.store.listUnviewedSettlements()).count { it.transaction.kind == TransactionKind.RENTAL })
        }
        val paid = rentalRows()
        assertEquals(completed, ok(producer.observeOrdinarySimulationReplay(frame, recorded, restClock)).firstCompleted)
        assertEquals(paid, rentalRows())

        val (returnFrame, returnRecorded) = record(rental.returnEnd.value - base, rest)
        reopenRental()
        val returned = ok(producer.observeOrdinarySimulationReplay(returnFrame, returnRecorded, restClock))
        assertTrue(returned.deferred.isEmpty())
        assertTrue(returned.pending.isEmpty())
        assertEquals(completed, returned.firstCompleted)
        ok(games.open()).use { game ->
            assertEquals(GameMoney(1000 + completed.receipt.settlement.transaction.money!!.amount.units),
                ok(game.store.balance()))
            assertEquals(1, ok(game.store.listUnviewedSettlements()).count { it.transaction.kind == TransactionKind.RENTAL })
        }
        assertEquals(originalRequests, requests)
    }

    private fun rentalSettlementFault(enabled: Boolean) = ok(games.open()).use { game ->
        ok(game.store.atomic { db ->
            if (enabled) db.execSQL("""CREATE TRIGGER rental_replay_settlement_fault BEFORE INSERT ON settlements
                WHEN substr(NEW.request_id,1,14)='rental/settle/'
                BEGIN SELECT RAISE(ABORT, 'injected rental settlement failure'); END""")
            else db.execSQL("DROP TRIGGER rental_replay_settlement_fault")
            ContractResult.Success(Unit)
        })
    }

    fun testOrdinarySimulationTerminalReplayRetriesDueSettlementFromStoredDriveReceipt() {
        val originalRest = enableRestRecorder()
        val rental = activeRentalAtStage13(originalRest)
        val end = rental.end.value - base
        val terminal = end + 5_000
        val originalRequests = requests
        val originalDrive = try {
            rentalSettlementFault(true)
            reopenRental()
            val rest = reopenRestRecorder()
            val (lastDriving, drivingResult) = record(end + 1_000, rest)
            val coverage = ok(producer.observeOrdinarySimulationReplay(lastDriving, drivingResult, restClock))
            assertEquals(mapOf(rental.id to ContractError.STORAGE_FAILURE), coverage.deferred)
            assertRentalThrough(rental.id, end)

            // 64단계까지 원주행이 열려 있고 새 세션의 첫 65단계가 주행을 확정한다.
            for (offset in 2_000L..4_000L step 1_000) {
                assertNotNull(ok(record(end + offset, rest, parked = true).second).state.trip)
            }
            reopenRental()
            val restored = reopenRestRecorder()
            val (frame, first) = record(terminal, restored, parked = true)
            val recorded = ok(first)
            assertNull(recorded.state.trip)
            val receipt = recorded.committed.single()
            val beforeSettlement = rentalRows()
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
                producer.observeOrdinarySimulationReplay(frame, first, restClock))
            val unmatched = frame.copy(zone = Observation.Available(ZoneId.of("Asia/Seoul")))
            assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
                producer.observeOrdinarySimulationReplay(unmatched, first, restClock, ordinaryTerminal = true))
            assertEquals(beforeSettlement, rentalRows())

            // 실행 결과에 영수증이 있어도 저장 원문이 없으면 대여 정산을 진행하지 않는다.
            ok(games.open()).use { game ->
                val originalRow = ok(game.store.atomic { db ->
                    val row = db.rawQuery(
                        "SELECT drive_id,profile,source,payload,digest FROM observed_driving_receipts WHERE drive_id=?",
                        arrayOf(receipt.trip.id),
                    ).use { rows ->
                        assertTrue(rows.moveToFirst())
                        Array(rows.columnCount) { rows.getString(it) }
                    }
                    assertEquals(1, db.delete("observed_driving_receipts", "drive_id=?", arrayOf(receipt.trip.id)))
                    ContractResult.Success(row)
                })
                try {
                    assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
                        producer.observeOrdinarySimulationReplay(frame, first, restClock, ordinaryTerminal = true))
                    assertEquals(beforeSettlement, rentalRows())
                } finally {
                    ok(game.store.atomic { db ->
                        db.execSQL("INSERT INTO observed_driving_receipts(drive_id,profile,source,payload,digest) VALUES(?,?,?,?,?)",
                            originalRow)
                        ContractResult.Success(Unit)
                    })
                }
            }

            val failed = ok(producer.observeOrdinarySimulationReplay(frame, first, restClock, ordinaryTerminal = true))
            assertEquals(mapOf(rental.id to ContractError.STORAGE_FAILURE), failed.deferred)
            val (same, repeated) = record(terminal, restored, parked = true)
            assertEquals(frame, same)
            assertTrue(ok(repeated).committed.isEmpty())
            val failedAgain = ok(producer.observeOrdinarySimulationReplay(same, repeated, restClock, ordinaryTerminal = true))
            assertEquals(failed.deferred, failedAgain.deferred)
            assertEquals(beforeSettlement, rentalRows())
            receipt
        } finally {
            rentalSettlementFault(false)
        }

        reopenRental()
        val restored = reopenRestRecorder()
        val (frame, repeated) = record(terminal, restored, parked = true)
        assertTrue(ok(repeated).committed.isEmpty())
        val settled = ok(producer.observeOrdinarySimulationReplay(frame, repeated, restClock, ordinaryTerminal = true))
        val result = checkNotNull(settled.firstCompleted)
        assertEquals(rental.id, result.rentalId)
        assertTrue(settled.deferred.isEmpty())
        assertEquals(VehicleActivity.RETURNING, settled.pending.single().phase)
        ok(games.open()).use { game ->
            assertEquals(originalDrive, ok(game.store.observedDrivingReceipt(originalDrive.trip.id)))
            val basis = (ok(RentalResultBasisReader(game.store).read(rental.id)) as Observation.Available).value
            assertEquals(result.receipt.settlement.settlementId, basis.settlementId)
            assertEquals(end, basis.periods.single().normalMillis)
            val settlements = ok(game.store.listUnviewedSettlements())
            assertEquals(1, settlements.count { it.transaction.kind == TransactionKind.RENTAL })
            assertEquals(1, settlements.count { it.transaction.kind == TransactionKind.DRIVING })
        }
        val after = rentalRows()
        assertEquals(result,
            ok(producer.observeOrdinarySimulationReplay(frame, repeated, restClock, ordinaryTerminal = true)).firstCompleted)
        assertEquals(after, rentalRows())
        assertEquals(originalRequests, requests)
    }

    fun testOrdinarySimulationReplayWithoutRestContextPreservesRentalRows() {
        val rental = activeRentalAtStage13(null)
        val (frame, recorded) = record(780_000, null)
        reopenRental()
        val before = rentalRows()
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            producer.observeOrdinarySimulationReplay(frame, recorded, restClock))
        assertRentalThrough(rental.id, 720_000)
        assertEquals(before, rentalRows())
    }

    fun testOrdinarySimulationReplayWithActualGapPreservesRentalRows() {
        val rest = enableRestRecorder()
        val rental = activeRentalAtStage13(rest)
        record(780_000, rest, "discontinuous-rest")
        val (frame, recorded) = record(840_000, rest, "discontinuous-rest")
        val trip = checkNotNull(ok(recorded).state.trip)
        assertEquals(DrivingGapReason.RECOVERY, trip.gaps.single().reason)
        reopenRental()
        val before = rentalRows()
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            producer.observeOrdinarySimulationReplay(frame, recorded, restClock))
        assertRentalThrough(rental.id, 720_000)
        assertEquals(before, rentalRows())
    }

    fun testActualStopReconnectSettlesOriginalRentalAndFirstGiftOnlyOnce() {
        ok(feed(0)); val assigned = ok(feed(1000)).pending.single()
        assertEquals(base + 3_600_000, assigned.end.value)
        ok(feed(60_000))
        ok(driving.pause("simulation-receiver-1", RecordingClock(elapsed, clock().epoch)))
        producer.pauseObservations(); ok(producer.inactive(false, ContractResult.Success(clock())))
        val stopped = checkNotNull(ok(readJournal.currentInactive(identity)))
        assertEquals(RentalInactiveBasis.APPLICATION_EXIT_DRIVING, stopped.basis)
        assertEquals(RentalInactivePhase.RECORDED, stopped.phase)
        assertEquals(clock(), (ok(stopped.policyOrigin) as Observation.Available).value)
        producer.reconnect()
        val resumed = ok(feed(3_600_001, "simulation-receiver-2"))
        val completed = checkNotNull(resumed.firstCompleted)
        assertEquals(assigned.id, completed.rentalId)
        assertEquals(assigned.vehicle, completed.ownedVehicleId)
        assertFalse(completed.receipt.accident)
        assertEquals(VehicleActivity.RETURNING, resumed.pending.single().phase)
        assertNull(resumed.pending.single().parkedSince)
        val boundary = checkNotNull(ok(readJournal.currentInactive(identity)))
        assertEquals(RentalInactivePhase.COMPLETED, boundary.phase)
        assertEquals(clock(), checkNotNull(boundary.interval).to)
        val first = ok(games.open()).use { game ->
            val state = ok(game.store.readFirstRentalGift(identity.profileId))
            assertEquals(1L, state.progress.freeTickets)
            assertNotNull(state.granted)
            val amount = completed.receipt.settlement.transaction.money!!.amount.units
            assertEquals(GameMoney(1000 + amount), ok(game.store.balance()))
            state
        }
        // 새 factory/source는 저장된 완료·지급 결과를 읽는다. 재초기화나 과거 고객 생성은 하지 않는다.
        games = factory(); producer = source(); producer.reconnect()
        val afterReopen = ok(feed(3_600_002, "simulation-receiver-3"))
        assertEquals(completed, afterReopen.firstCompleted)
        ok(games.open()).use { game ->
            val state = ok(game.store.readFirstRentalGift(identity.profileId))
            assertEquals(first.granted, state.granted)
            assertEquals(first.progress, state.progress)
            assertEquals(first.balance, state.balance)
            assertEquals(1, ok(game.store.listUnviewedSettlements()).count { it.transaction.kind == TransactionKind.RENTAL })
        }
        assertFalse(context.getDatabasePath(names.getValue(InputSource.VEHICLE)).exists())
    }
    fun testForwardWallJumpSettlesOnlyExistingExitAndReplaysItsFirstWindow() {
        ok(feed(0))
        val assigned = ok(feed(1_000)).pending.single()
        ok(feed(60_000))
        ok(stop(60_500, "simulation-receiver-1"))
        val exit = marker()
        assertEquals(RentalInactivePhase.RECORDED, exit.phase)
        producer.reconnect()

        wallOffset = -20_000
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            feed(61_000, "simulation-receiver-2"))
        assertEquals(exit, marker())

        wallOffset = 9 * 3_600_000L
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
            feed(62_000, "simulation-receiver-2"))
        assertEquals(exit, marker())
        rentalSettlementFault(true)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),
            feed(63_000, "simulation-receiver-2"))
        val prepared = marker()
        assertEquals(RentalInactivePhase.PREPARED, prepared.phase)
        assertEquals(clock(), checkNotNull(prepared.interval).to)
        assertEquals(exit.policyOrigin, prepared.policyOrigin)

        rentalSettlementFault(false)
        val completed = ok(feed(64_000, "simulation-receiver-2")).firstCompleted
        assertEquals(assigned.id, checkNotNull(completed).rentalId)
        val recovered = marker()
        assertEquals(RentalInactivePhase.COMPLETED, recovered.phase)
        assertEquals(prepared.interval, recovered.interval)
        assertEquals(1, requests)
        val originalRows = rentalRows()
        val cursor = ok(readJournal.cursor(identity))
        assertTrue(cursor.nextAt.elapsedMs > elapsed)
        assertEquals(base + wallOffset + cursor.nextAt.elapsedMs, cursor.nextAt.epoch.value)
        reopenRental()
        ok(feed(cursor.nextAt.elapsedMs - 1_000, "simulation-receiver-3"))
        assertEquals(originalRows, rentalRows())
        assertEquals(recovered, marker())
        assertEquals(1, requests)
        ok(feed(cursor.nextAt.elapsedMs, "simulation-receiver-3"))
        assertEquals(2, requests)
    }

    fun testForwardWallJumpWhileParkedUsesExistingOriginWithoutCreatingExit() {
        ok(feed(0))
        val assigned = ok(feed(1_000)).pending.single()
        for (at in 2_000L..6_000L step 1_000L) ok(feed(at, gear = Gear.PARK))
        val original = ok(feed(7_000, gear = Gear.PARK)).pending.single().parkedSince
        assertNotNull(original)
        wallOffset = 40 * 60_000L
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), feed(8_000, gear = Gear.PARK))
        assertEquals(assigned.id, ok(feed(9_000, gear = Gear.PARK)).pending.single().id)
        assertEquals(original, ok(feed(10_000, gear = Gear.PARK)).pending.single().parkedSince)
        val firstSchedule = ok(readJournal.cursor(identity))

        wallOffset = 9 * 3_600_000L
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), feed(11_000, gear = Gear.PARK))
        val completed = checkNotNull(ok(feed(12_000, gear = Gear.PARK)).firstCompleted)
        assertEquals(completed, ok(feed(13_000, gear = Gear.PARK)).firstCompleted)
        assertEquals(firstSchedule.sequence, ok(readJournal.cursor(identity)).sequence)
        assertEquals(assigned.id, completed.rentalId)
        assertNull(ok(readJournal.currentInactive(identity)))
        ok(games.open()).use { game ->
            val basis = ok(RentalResultBasisReader(game.store).read(assigned.id)) as Observation.Available
            val economic = basis.value.periods.filter { it.kind == RentalIncomeKind.INACTIVE }
            assertTrue(economic.size >= 2)
            assertEquals(setOf(original), economic.map { it.origin }.toSet())
        }
        assertEquals(1, requests)

        ok(stop(13_500, "simulation-receiver-1"))
        val parkedExit = marker()
        assertEquals(RentalInactivePhase.RECORDED, parkedExit.phase)
        assertEquals(RentalInactiveBasis.DRIVING_END, parkedExit.basis)
        assertEquals(original, (ok(parkedExit.policyOrigin) as Observation.Available).value.epoch)
        producer.reconnect()
        ok(feed(14_000, "simulation-receiver-2", gear = Gear.PARK))
        assertEquals(parkedExit.marker, marker().marker)
        assertEquals(RentalInactivePhase.COMPLETED, marker().phase)
    }

    fun testLevelChangeKeepsStoredVisitTimeThenUsesNewDemandInterval() {
        val minute = 60_000L
        ok(feed(0))
        val assigned = ok(feed(1_000)).pending.single()
        val stored = ok(readJournal.cursor(identity))
        assertEquals(30 * minute, stored.nextAt.elapsedMs)
        assertEquals(1, requests)

        raw().use { it.execSQL("UPDATE shop SET level=2,capacity=5") }
        assertEquals(stored, ok(readJournal.cursor(identity)))
        ok(feed(stored.nextAt.elapsedMs - 1_000))
        assertEquals(stored, ok(readJournal.cursor(identity)))
        assertEquals(1, requests)
        val accepted = ok(feed(stored.nextAt.elapsedMs))
        assertTrue(accepted.deferred.isEmpty())
        assertEquals(assigned.id, accepted.pending.single().id)
        assertEquals(2, requests)

        val persistedNext = raw().use { db ->
            db.rawQuery("SELECT host_command FROM visits WHERE host_command IS NOT NULL ORDER BY rowid", null).use {
                assertTrue(it.moveToFirst())
                val first = RentalProductJson.prepared(JSONObject(it.getString(0)))
                assertEquals(assigned.id, first.visit.request.id)
                assertEquals(stored, first.next)
                assertNotNull(first.city)
                assertTrue(it.moveToNext())
                val second = RentalProductJson.prepared(JSONObject(it.getString(0)))
                assertEquals(stored, second.expected)
                assertEquals(stored.nextAt.epoch, second.visit.terms.start)
                assertEquals(25 * minute, second.next.nextAt.elapsedMs - second.expected.nextAt.elapsedMs)
                assertEquals(FirstGrant.CANDIDATES.first(), second.visit.request.desired)
                assertNotNull(second.city)
                assertFalse(it.moveToNext())
                second.next
            }
        }
        assertEquals(persistedNext, ok(readJournal.cursor(identity)))
    }

    fun testOrdinaryThirtyFiveMinuteGapResumesNextVisitWithoutBackfilling() {
        ok(feed(0))
        val assigned = ok(feed(1_000)).pending.single()
        val original = ok(readJournal.cursor(identity))
        assertEquals(30 * 60_000L, original.nextAt.elapsedMs)
        ok(feed(10 * 60_000L))
        ok(stop(10 * 60_000L + 1, "simulation-receiver-1"))
        reopenRental()
        val returned = ok(feed(35 * 60_000L, "simulation-receiver-2"))
        assertEquals(assigned.id, returned.pending.single().id)
        val exit = marker()
        ok(feed(35 * 60_000L + 1_000, "simulation-receiver-2"))
        val next = ok(readJournal.cursor(identity))
        assertEquals(60 * 60_000L, next.nextAt.elapsedMs)
        assertEquals(original.sequence, next.sequence)
        assertEquals(exit, marker())
        assertEquals(1, requests)

        // 같은 복귀 세션의 관측을 이어서, 다음 미래 슬롯에서만 새 방문을 확인한다.
        assertTrue(ok(feed(next.nextAt.elapsedMs - 1_000, "simulation-receiver-2")).deferred.isEmpty())
        assertEquals(1, requests)
        assertTrue(ok(feed(next.nextAt.elapsedMs, "simulation-receiver-2")).deferred.isEmpty())
        assertEquals(2, requests)
        assertEquals(next.sequence + 1, ok(readJournal.cursor(identity)).sequence)
        assertEquals(exit, marker())
    }

    fun testPreparedExitDrainsRetainedSpanBeforeMovingMissedVisitSlot() {
        val minute = 60_000L
        policy = policy.copy(maxGapMs = 10 * minute)
        bindRecorder()
        ok(feed(0))
        val assigned = ok(feed(1_000)).pending.single()
        for (at in listOf(5L, 10L, 15L, 20L, 24L)) ok(feed(at * minute))
        val overdue = ok(readJournal.cursor(identity))
        assertEquals(30 * minute, overdue.nextAt.elapsedMs)
        ok(feed(35 * minute))
        raw().use {
            it.execSQL("CREATE TRIGGER reject_visit_schedule BEFORE UPDATE OF revision ON rental_product_state " +
                "WHEN NEW.revision > OLD.revision BEGIN SELECT RAISE(ABORT, 'injected visit schedule failure'); END")
        }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), feed(36 * minute))
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),
            stop(36 * minute + 1, "simulation-receiver-1"))
        val exit = marker()
        assertEquals(RentalInactivePhase.RECORDED, exit.phase)
        assertEquals(36 * minute, checkNotNull(exit.observedThrough).elapsedMs)
        assertEquals(overdue, ok(readJournal.cursor(identity)))
        raw().use { it.execSQL("DROP TRIGGER reject_visit_schedule") }

        producer.reconnect()
        val receiver = "simulation-receiver-2"
        val recovered = ok(feed(37 * minute, receiver)).pending.single()
        assertEquals(assigned.id, recovered.id)
        assertEquals(base + 37 * minute, recovered.cursor.value)
        val complete = marker()
        assertEquals(exit.marker, complete.marker)
        assertEquals(RentalInactivePhase.COMPLETED, complete.phase)
        assertEquals(overdue, ok(readJournal.cursor(identity)))
        assertEquals(1, requests)

        ok(feed(38 * minute, receiver))
        val next = ok(readJournal.cursor(identity))
        assertEquals(60 * minute, next.nextAt.elapsedMs)
        assertEquals(overdue.sequence, next.sequence)
        assertEquals(complete, marker())
        assertEquals(1, requests)
    }

    fun testUnsavedVisitKeepsOriginalRequestAcrossOrdinaryGap() = retainedVisitAcrossGap(0)
    fun testUnsavedVisitKeepsOriginalRequestAcrossForwardRebase() = retainedVisitAcrossGap(9 * 3_600_000L)

    private fun retainedVisitAcrossGap(offset: Long) {
        val minute = 60_000L
        val receiver = "simulation-receiver-2"
        ok(feed(0))
        ok(feed(1_000))
        ok(feed(29 * minute))
        raw().use {
            it.execSQL("CREATE TRIGGER reject_visit_draft BEFORE UPDATE ON rental_product_state " +
                "WHEN instr(NEW.payload,'rental-prepared-v2')>0 AND instr(OLD.payload,'rental-prepared-v2')=0 " +
                "BEGIN SELECT RAISE(ABORT, 'injected visit draft failure'); END")
        }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), feed(30 * minute))
        assertEquals(2, requests)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),
            stop(30 * minute + 1, "simulation-receiver-1"))
        assertEquals(RentalInactivePhase.RECORDED, marker().phase)
        raw().use { it.execSQL("DROP TRIGGER reject_visit_draft") }

        producer.reconnect()
        wallOffset = offset
        ok(feed(65 * minute, receiver))
        assertEquals(RentalInactivePhase.COMPLETED, marker().phase)
        raw().use { db ->
            db.rawQuery("SELECT host_command FROM visits WHERE id=?", arrayOf("explicit-simulation-visit-2")).use {
                assertTrue(it.moveToFirst())
                val original = RentalProductJson.prepared(JSONObject(it.getString(0)))
                assertEquals(base + 30 * minute, original.visit.terms.start.value)
                assertEquals(1L, original.expected.sequence)
            }
        }
        assertEquals(2, requests)
        ok(feed(66 * minute, receiver))
        val next = ok(readJournal.cursor(identity))
        assertEquals(90 * minute, next.nextAt.elapsedMs)
        assertEquals(base + offset + 90 * minute, next.nextAt.epoch.value)
        assertEquals(2L, next.sequence)
        assertEquals(2, requests)
    }

    fun testRawRejectedForwardCandidateDoesNotBlockCorrectedClockAfterReconnect() {
        ok(feed(0))
        val assigned = ok(feed(1_000)).pending.single()
        ok(feed(60_000))
        ok(stop(60_500, "simulation-receiver-1"))
        val exit = marker()
        producer.reconnect()

        wallOffset = 9 * 3_600_000L
        var reads = 0
        rawClockSample = {
            val at = clock()
            if (++reads == 2) at.copy(epoch = EpochMillis(at.epoch.value - wallOffset)) else at
        }
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), feed(62_000, "simulation-receiver-2"))
        assertEquals(2, reads)
        assertEquals(exit, marker())

        rawClockSample = null
        wallOffset = 0
        producer.reconnect()
        assertEquals(assigned.id, ok(feed(63_000, "simulation-receiver-3")).pending.single().id)
        val complete = marker()
        assertEquals(exit.marker, complete.marker)
        assertEquals(RentalInactivePhase.COMPLETED, complete.phase)
        assertEquals(clock(), checkNotNull(complete.interval).to)
        assertFalse(checkNotNull(complete.interval).forwardWallJump)
        assertEquals(1, requests)
    }

    fun testNormalPreparedSettlementAndForwardScheduleRecoverTogetherAfterWriteFailure() {
        val minute = 60_000L
        val receiver = "simulation-receiver-2"
        ok(feed(0))
        val assigned = ok(feed(1_000)).pending.single()
        ok(feed(minute))
        ok(stop(minute + 500, "simulation-receiver-1"))
        val exit = marker()
        producer.reconnect()

        rentalSettlementFault(true)
        val normalResume = 60 * minute + 1
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), feed(normalResume, receiver))
        val prepared = marker()
        val original = checkNotNull(prepared.interval)
        assertEquals(exit.marker, prepared.marker)
        assertEquals(RentalInactivePhase.PREPARED, prepared.phase)
        assertFalse(original.forwardWallJump)
        assertEquals(clock(), original.to)
        rentalSettlementFault(false)

        fun financeRows() = rentalRows().filter { it.startsWith("settlements:") ||
            it.startsWith("account:") || it.startsWith("rental_result_basis:") }
        val beforeFinance = financeRows()
        val beforeSchedule = ok(readJournal.cursor(identity))
        wallOffset = 9 * 3_600_000L
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), feed(normalResume + 1_000, receiver))
        raw().use {
            it.execSQL("CREATE TRIGGER reject_forward_schedule BEFORE UPDATE OF revision ON rental_product_state " +
                "WHEN NEW.revision > OLD.revision BEGIN SELECT RAISE(ABORT, 'injected schedule write failure'); END")
        }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), feed(normalResume + 2_000, receiver))
        assertEquals(prepared, marker())
        assertEquals(beforeSchedule, ok(readJournal.cursor(identity)))
        assertEquals(beforeFinance, financeRows())
        raw().use { it.execSQL("DROP TRIGGER reject_forward_schedule") }

        val completed = checkNotNull(ok(feed(normalResume + 3_000, receiver)).firstCompleted)
        assertEquals(assigned.id, completed.rentalId)
        val finished = marker()
        assertEquals(prepared.copy(phase = RentalInactivePhase.COMPLETED), finished)
        val schedule = ok(readJournal.cursor(identity))
        assertEquals(beforeSchedule.sequence, schedule.sequence)
        assertTrue(schedule.nextAt.elapsedMs > elapsed)
        assertEquals(base + wallOffset + schedule.nextAt.elapsedMs, schedule.nextAt.epoch.value)
        ok(games.open()).use { game ->
            val read = ok(RentalResultBasisReader(game.store).read(assigned.id)) as Observation.Available
            val inactive = read.value.periods.filter { it.kind == RentalIncomeKind.INACTIVE }
            assertTrue(inactive.isNotEmpty())
            assertTrue(inactive.all { it.origin == exit.since.epoch && it.policy?.capMillis == 8 * 60 * minute })
            assertEquals(1, ok(game.store.listUnviewedSettlements()).count { it.transaction.kind == TransactionKind.RENTAL })
        }
        val originalBasis = financeRows()
        assertEquals(1, requests)

        reopenRental()
        assertEquals(completed, ok(feed(normalResume + 4_000, "simulation-receiver-3")).firstCompleted)
        assertEquals(finished, marker())
        assertEquals(schedule, ok(readJournal.cursor(identity)))
        assertEquals(originalBasis, financeRows())
        assertEquals(1, requests)
    }

    private fun prepareUnfinishedRentalForForwardRepair(resumed: String): RentalLedger.Pending {
        val first = "simulation-receiver-1"
        ok(feed(0))
        val assigned = ok(feed(1_000)).pending.single()
        ok(feed(60_000))
        ok(stop(60_500, first))
        producer.reconnect()

        raw().use {
            it.execSQL("CREATE TRIGGER reject_completion BEFORE UPDATE OF payload ON rental_product_inactive " +
                "WHEN instr(NEW.payload, '\"done\":true') > 0 " +
                "BEGIN SELECT RAISE(ABORT, 'injected completion write failure'); END")
        }
        val originalResume = 120_000L
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), feed(originalResume, resumed))
        val prepared = marker()
        assertEquals(RentalInactivePhase.PREPARED, prepared.phase)
        val interval = checkNotNull(prepared.interval)
        assertFalse(interval.forwardWallJump)
        assertEquals(clock(), interval.to)
        assertTrue(interval.to.epoch.value < assigned.end.value)
        raw().use { it.execSQL("DROP TRIGGER reject_completion") }
        return assigned
    }

    fun testNormalPreparedForwardRepairConnectsAnUnfinishedRentalToLiveDrivingAndPark() {
        coldProof?.let { return verifyForwardRepairFromNewProcess(it) }
        val resumed = "simulation-receiver-2"
        val assigned = prepareUnfinishedRentalForForwardRepair(resumed)
        val prepared = marker()
        val interval = checkNotNull(prepared.interval)
        val originalResume = interval.to.elapsedMs
        wallOffset = 600_000L
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), feed(originalResume + 1_000, resumed))
        val oldRows = rentalRows()
        val originalSchedule = ok(readJournal.cursor(identity))
        raw().use {
            it.execSQL("CREATE TRIGGER reject_forward_schedule BEFORE UPDATE OF revision ON rental_product_state " +
                "WHEN NEW.revision > OLD.revision BEGIN SELECT RAISE(ABORT, 'injected schedule write failure'); END")
        }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), feed(originalResume + 2_000, resumed))
        assertEquals(oldRows, rentalRows())
        assertEquals(prepared, marker())
        raw().use { it.execSQL("DROP TRIGGER reject_forward_schedule") }

        val firstLive = originalResume + 3_000
        val repaired = ok(feed(firstLive, resumed)).pending.single()
        val rebasedAt = clock().epoch
        assertEquals(prepared.copy(phase = RentalInactivePhase.COMPLETED), marker())
        assertEquals(assigned.id, repaired.id)
        assertEquals(VehicleActivity.RENTED, repaired.phase)
        assertEquals(rebasedAt, repaired.cursor)
        assertTrue(repaired.cursor.value < repaired.end.value)
        assertEquals(0, BigDecimal("119500").compareTo(weighted(assigned.id)))
        ok(games.open()).use { game ->
            ok(game.store.read { db ->
                val basis = checkNotNull(RentalBasisStorage.load(db, game.store, assigned.id))
                val tail = basis.periods.last()
                assertEquals(interval.to.epoch, tail.from)
                assertEquals(rebasedAt, tail.through)
                assertEquals(RentalIncomeKind.UNOBSERVED, tail.kind)
                assertEquals(0, tail.weightedMillis.signum())
                assertNull(tail.origin)
                assertNull(tail.policy)
                val credited = basis.periods.last { it.kind == RentalIncomeKind.INACTIVE }
                assertEquals(interval.from.epoch, credited.from)
                assertEquals(interval.to.epoch, credited.through)
                assertEquals(interval.originalSince.epoch, credited.origin)
                assertNull(basis.settlementId)
            })
        }

        val live = ok(feed(firstLive + 1_000, resumed))
        assertTrue(live.deferred.isEmpty())
        assertEquals(clock().epoch, live.pending.single().cursor)
        assertEquals(0, BigDecimal("120500").compareTo(weighted(assigned.id)))
        for (at in firstLive + 2_000..firstLive + 8_000 step 1_000L) {
            val parked = ok(feed(at, resumed, Gear.PARK))
            assertTrue(parked.deferred.isEmpty())
            assertEquals(clock().epoch, parked.pending.single().cursor)
        }
        val schedule = ok(readJournal.cursor(identity))
        assertEquals(originalSchedule.sequence, schedule.sequence)
        assertEquals(base + wallOffset + schedule.nextAt.elapsedMs, schedule.nextAt.epoch.value)
        assertEquals(1, requests)
        assertEquals(prepared.copy(phase = RentalInactivePhase.COMPLETED), marker())
        ok(games.open()).use { game ->
            assertEquals(GameMoney(1000), ok(game.store.balance()))
            assertTrue(ok(game.store.listUnviewedSettlements()).none { it.transaction.kind == TransactionKind.RENTAL })
        }
        if (coldRun.exists()) {
            ok(stop(elapsed + 500, resumed))
            val nextExit = marker()
            assertEquals(RentalInactivePhase.RECORDED, nextExit.phase)
            assertEquals(RentalInactiveBasis.DRIVING_END, nextExit.basis)
            coldEvidence.writeText(JSONObject().put("pid", Process.myPid())
                .put("database", names.getValue(InputSource.SIMULATION).removeSuffix("-SIMULATION.db"))
                .put("elapsed", elapsed).put("wallOffset", wallOffset)
                .put("request", assigned.id.value).put("requests", requests)
                .put("rows", JSONArray(rentalRows())).toString())
            retainColdDatabase = true
        }
    }

    fun testNormalPreparedForwardRepairSettlesAnExpiredRentalWithZeroIncomeForTheTail() {
        val resumed = "simulation-receiver-2"
        val assigned = prepareUnfinishedRentalForForwardRepair(resumed)
        val prepared = marker()
        val interval = checkNotNull(prepared.interval)
        val originalResume = interval.to.elapsedMs
        val before = rentalRows()
        wallOffset = 9 * 3_600_000L
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), feed(originalResume + 1_000, resumed))
        rentalSettlementFault(true)
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), feed(originalResume + 2_000, resumed))
        assertEquals(before, rentalRows())
        assertEquals(prepared, marker())
        rentalSettlementFault(false)

        val completed = ok(feed(originalResume + 3_000, resumed))
        assertEquals(assigned.id, completed.firstCompleted?.rentalId)
        assertTrue(completed.pending.isEmpty())
        assertEquals(prepared.copy(phase = RentalInactivePhase.COMPLETED), marker())
        assertEquals(0, BigDecimal("119500").compareTo(weighted(assigned.id)))
        ok(games.open()).use { game ->
            val read = ok(RentalResultBasisReader(game.store).read(assigned.id)) as Observation.Available
            val tail = read.value.periods.last()
            assertEquals(interval.to.epoch, tail.from)
            assertEquals(assigned.end, tail.through)
            assertEquals(RentalIncomeKind.UNOBSERVED, tail.kind)
            assertEquals(0, tail.weightedMillis.signum())
            assertEquals(1, ok(game.store.listUnviewedSettlements()).count { it.transaction.kind == TransactionKind.RENTAL })
        }
        assertEquals(1, requests)
    }

    private fun verifyForwardRepairFromNewProcess(proof: JSONObject) {
        assertTrue("새 OS 프로세스에서 같은 DB를 열어야 한다", Process.myPid() != proof.getInt("pid"))
        assertEquals(proof.getJSONArray("rows").toString(), JSONArray(rentalRows()).toString())
        elapsed = proof.getLong("elapsed")
        wallOffset = proof.getLong("wallOffset")
        requests = proof.getInt("requests")
        val savedRequests = requests
        val schedule = ok(readJournal.cursor(identity))
        val exit = marker()
        val receiver = "simulation-receiver-3"
        val firstPark = elapsed + 1_000
        val resumed = ok(feed(firstPark, receiver, Gear.PARK))
        val pending = resumed.pending.single()
        assertEquals(proof.getString("request"), pending.id.value)
        assertEquals(clock().epoch, pending.cursor)
        assertEquals(exit.marker, marker().marker)
        assertEquals(exit.policyOrigin, marker().policyOrigin)
        assertEquals(RentalInactivePhase.COMPLETED, marker().phase)
        val continued = ok(feed(firstPark + 1_000, receiver, Gear.PARK))
        assertTrue(continued.deferred.isEmpty())
        assertEquals(clock().epoch, continued.pending.single().cursor)
        assertEquals(schedule, ok(readJournal.cursor(identity)))
        assertEquals(savedRequests, requests)
        assertNull(continued.firstCompleted)
        File(context.cacheDir, "rental-forward-cold-verified.json").writeText(JSONObject()
            .put("firstPid", proof.getInt("pid")).put("newPid", Process.myPid())
            .put("request", pending.id.value).put("cursor", continued.pending.single().cursor.value).toString())
    }

    fun testUnwrittenForwardPreparationCanRetryAfterClockCorrection() {
        ok(feed(0))
        val assigned = ok(feed(1_000)).pending.single()
        ok(feed(60_000))
        ok(stop(60_500, "simulation-receiver-1"))
        val exit = marker()
        producer.reconnect()

        raw().use {
            it.execSQL("CREATE TRIGGER reject_forward_prepare BEFORE UPDATE OF payload ON rental_product_inactive " +
                "WHEN instr(NEW.payload,'forwardWallJump')>0 " +
                "BEGIN SELECT RAISE(ABORT, 'injected prepare failure'); END")
        }
        wallOffset = 9 * 3_600_000L
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), feed(62_000, "simulation-receiver-2"))
        assertEquals(exit, marker())
        raw().use { it.execSQL("DROP TRIGGER reject_forward_prepare") }

        wallOffset = 0
        producer.reconnect()
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), feed(63_000, "simulation-receiver-3"))
        assertEquals(exit, marker())
        assertEquals(assigned.id, ok(feed(64_000, "simulation-receiver-3")).pending.single().id)
        val complete = marker()
        assertEquals(exit.marker, complete.marker)
        assertEquals(RentalInactivePhase.COMPLETED, complete.phase)
        assertEquals(base + 63_000, checkNotNull(complete.interval).to.epoch.value)
        assertFalse(checkNotNull(complete.interval).forwardWallJump)
        assertEquals(1, requests)
    }

    fun testCompletedForwardParkedExitRestoresOriginAfterSourceRecreation() {
        ok(feed(0))
        ok(feed(1_000))
        for (at in 2_000L..7_000L step 1_000L) ok(feed(at, gear = Gear.PARK))
        ok(stop(7_500, "simulation-receiver-1"))
        val original = marker()
        assertEquals(RentalInactiveBasis.DRIVING_END, original.basis)
        producer.reconnect()
        wallOffset = 9 * 3_600_000L
        ok(feed(8_000, "simulation-receiver-2", Gear.PARK))
        val completed = marker()
        assertEquals(RentalInactivePhase.COMPLETED, completed.phase)
        assertTrue(checkNotNull(completed.interval).forwardWallJump)
        assertFalse(completed.rebasedClock)

        reopenRental()
        ok(feed(9_000, "simulation-receiver-3", Gear.PARK))
        ok(stop(9_500, "simulation-receiver-3"))
        val subsequent = marker()
        assertEquals(RentalInactivePhase.RECORDED, subsequent.phase)
        assertEquals(RentalInactiveBasis.DRIVING_END, subsequent.basis)
        assertEquals(original.policyOrigin, subsequent.policyOrigin)
        assertTrue(subsequent.rebasedClock)
    }

    fun testConfigurationChangeCreatesNoInactiveMarkerAndDoesNotInventCoverage() {
        ok(feed(0)); val original = ok(feed(1000)).pending.single()
        ok(producer.inactive(true, ContractResult.Success(clock())))
        assertNull(ok(readJournal.currentInactive(identity)))
        ok(driving.pause("simulation-receiver-1", RecordingClock(elapsed, clock().epoch)))
        producer.reconnect()
        ok(feed(3000, "simulation-receiver-2"))
        val resumed = ok(feed(4000, "simulation-receiver-2"))
        assertNull(ok(readJournal.currentInactive(identity)))
        assertEquals(original.cursor, resumed.pending.single().cursor)
        assertEquals(ContractError.DATA_UNAVAILABLE, resumed.deferred[original.id])
        assertNull(resumed.firstCompleted)
        ok(games.open()).use { assertEquals(GameMoney(1000), ok(it.store.balance())) }
    }

    fun testExitBetweenAcceptedFramesResumesActiveRentalWithoutCreditingTheGap() {
        ok(feed(0))
        val assigned = ok(feed(1000)).pending.single()
        val beforeExit = ok(feed(60_000)).pending.single()
        assertEquals(base + 60_000, beforeExit.cursor.value)
        elapsed = 60_500
        val exitedAt = clock()
        ok(driving.pause("simulation-receiver-1", RecordingClock(elapsed, exitedAt.epoch)))
        producer.pauseObservations()
        ok(producer.inactive(false, ContractResult.Success(exitedAt)))

        producer.reconnect()
        val resumed = ok(feed(120_000, "simulation-receiver-2")).pending.single()
        assertEquals(assigned.id, resumed.id)
        assertEquals(base + 120_000, resumed.cursor.value)
        assertNull(resumed.parkedSince)
        val recovered = checkNotNull(ok(readJournal.currentInactive(identity)))
        assertEquals(RentalInactivePhase.COMPLETED, recovered.phase)
        assertEquals(exitedAt, recovered.since)
        assertEquals(clock(), checkNotNull(recovered.interval).to)
    }

    private fun stop(at: Long, receiver: String): ContractResult<Unit> {
        elapsed = at
        ok(driving.pause(receiver, RecordingClock(elapsed, clock().epoch)))
        producer.pauseObservations()
        return producer.inactive(false, ContractResult.Success(clock()))
    }

    private fun marker() = checkNotNull(ok(readJournal.currentInactive(identity)))
    private fun raw() = SQLiteDatabase.openDatabase(
        context.getDatabasePath(names.getValue(InputSource.SIMULATION)).path, null, SQLiteDatabase.OPEN_READWRITE)

    private fun weighted(id: RequestId): BigDecimal = raw().use { db ->
        db.rawQuery("SELECT weighted FROM rentals WHERE id=?", arrayOf(id.value)).use {
            assertTrue(it.moveToFirst())
            it.getString(0).toBigDecimal()
        }
    }

    fun testFailedAcceptedSpanIsRetriedBeforePreparedInactiveWindowCanSkipItsTail() {
        val first = "simulation-receiver-1"
        val second = "simulation-receiver-2"
        ok(feed(0))
        val assigned = ok(feed(1000)).pending.single()
        raw().use {
            it.execSQL("CREATE TRIGGER reject_accepted_span BEFORE UPDATE OF cursor ON rentals " +
                "WHEN NEW.cursor=${base + 60_000} BEGIN SELECT RAISE(IGNORE); END")
        }
        assertEquals(ContractError.STORAGE_FAILURE, ok(feed(60_000)).deferred[assigned.id])
        stop(60_500, first)
        val exit = marker()
        assertEquals(60_500L, exit.since.elapsedMs)
        assertEquals(RentalInactivePhase.RECORDED, exit.phase)
        producer.reconnect()

        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), feed(120_000, second))
        val blocked = marker()
        assertEquals(exit.marker, blocked.marker)
        assertEquals(RentalInactivePhase.PREPARED, blocked.phase)
        val command = checkNotNull(blocked.interval)
        assertEquals(exit.since, command.from)
        assertEquals(clock(), command.to)
        assertEquals(0, BigDecimal("1000").compareTo(weighted(assigned.id)))
        ok(games.open()).use { game ->
            assertEquals(assigned.cursor, ok(game.store.rental.pending()).single().cursor)
            assertNull(ok(game.store.rental.firstCompletedRental()))
            assertEquals(GameMoney(1000), ok(game.store.balance()))
        }

        raw().use { it.execSQL("DROP TRIGGER reject_accepted_span") }
        // 앞선 관측을 재생하지 않는 복구 호출도 저장된 경계를 건너뛸 수 없다.
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), readJournal.advanceInactive(identity, command))
        assertEquals(blocked, marker())
        assertEquals(base + 121_000, ok(feed(121_000, second)).pending.single().cursor.value)
        assertEquals(blocked.copy(phase = RentalInactivePhase.COMPLETED), marker())
        assertEquals(0, BigDecimal("120500").compareTo(weighted(assigned.id)))
        raw().use { db ->
            db.rawQuery("SELECT payload FROM rental_result_basis WHERE rental_id=?", arrayOf(assigned.id.value)).use {
                assertTrue(it.moveToFirst())
                val periods = JSONObject(it.getString(0)).getJSONArray("periods")
                assertEquals(4, periods.length())
                assertEquals("DRIVING", periods.getJSONObject(0).getString("kind"))
                assertEquals(base + 60_000, periods.getJSONObject(0).getLong("through"))
                val gap = periods.getJSONObject(1)
                assertEquals("UNOBSERVED", gap.getString("kind"))
                assertEquals(base + 60_000, gap.getLong("from"))
                assertEquals(base + 60_500, gap.getLong("through"))
                assertEquals("0", gap.getString("weighted"))
                assertEquals(base + 120_000, periods.getJSONObject(2).getLong("through"))
            }
        }
    }

    fun testObservedSpansAfterMaxGapKeepTheirDrivingAndParkingRatesOnResume() {
        policy = policy.copy(maxGapMs = 3_000)
        bindRecorder()
        ok(feed(0))
        val assigned = ok(feed(1000)).pending.single()
        ok(feed(2000))
        ok(feed(7000))
        assertEquals(base + 8000, ok(feed(8000)).pending.single().cursor.value)
        ok(feed(9000, gear = Gear.PARK))
        ok(feed(12000, gear = Gear.PARK))
        ok(feed(13000, gear = Gear.PARK))
        ok(feed(1_820_000, gear = Gear.PARK))
        val lastParked = ok(feed(1_821_000, gear = Gear.PARK))
        assertTrue(lastParked.deferred.isEmpty())
        assertEquals(base + 1_821_000, lastParked.pending.single().cursor.value)
        assertEquals(0, BigDecimal("8300").compareTo(weighted(assigned.id)))
        raw().use { db ->
            db.rawQuery("SELECT payload FROM rental_result_basis WHERE rental_id=?", arrayOf(assigned.id.value)).use {
                assertTrue(it.moveToFirst())
                val periods = JSONObject(it.getString(0)).getJSONArray("periods")
                assertEquals(listOf("DRIVING", "UNOBSERVED", "DRIVING", "PARKED", "UNOBSERVED", "PARKED"),
                    (0 until periods.length()).map { i -> periods.getJSONObject(i).getString("kind") })
                assertEquals(base + 2000, periods.getJSONObject(1).getLong("from"))
                assertEquals(base + 7000, periods.getJSONObject(1).getLong("through"))
                assertEquals(base + 13000, periods.getJSONObject(4).getLong("from"))
                assertEquals(base + 1_820_000, periods.getJSONObject(4).getLong("through"))
                assertEquals(1_000L, periods.getJSONObject(5).getLong("reduced"))
            }
        }

        ok(stop(1_821_500, "simulation-receiver-1"))
        producer.reconnect()
        val resumed = ok(feed(1_830_000, "simulation-receiver-2"))
        assertEquals(base + 1_830_000, resumed.pending.single().cursor.value)
        assertEquals(RentalInactivePhase.COMPLETED, marker().phase)
        assertEquals(EpochMillis(base + 9000), resumed.pending.single().parkedSince)
        assertEquals(0, BigDecimal("10850").compareTo(weighted(assigned.id)))
    }

    fun testConfirmedParkThenNewDriveStartsAnotherNormalThirtyMinutes() {
        val first = "simulation-receiver-1"
        val second = "simulation-receiver-2"
        val third = "simulation-receiver-3"
        val fourth = "simulation-receiver-4"
        val rental = RequestId("six-hour-rental-before-first-observation")
        val terms = ok(definition.rentalVisitDefinition())
        ok(games.open()).use { game ->
            val car = ok(game.store.listOwnedVehicles()).single()
            ok(game.store.rental.assign(RentalRequest(rental, car.vehicleId, 6), ok(game.content).catalog,
                terms.categories, terms.rates, RentalTerms(terms.rules, SimulatedCustomer(30, 48), 99,
                    clock().epoch, 60, GameMoney(0), RoundingMode.DOWN, false)))
        }
        ok(feed(0)); ok(feed(1000))
        ok(stop(1500, first))
        val firstOrigin = clock()
        producer.reconnect()
        ok(feed(1_900_000, second))
        ok(feed(1_901_000, second)); ok(stop(1_901_500, second))
        assertEquals(firstOrigin, (ok(marker().policyOrigin) as Observation.Available).value)
        producer.reconnect()
        ok(feed(1_902_000, third)); ok(feed(1_903_000, third))
        ok(feed(1_904_000, third, Gear.PARK))
        ok(feed(1_907_000, third, Gear.PARK))
        ok(feed(1_908_000, third)); ok(feed(1_909_000, third))

        val creditedBeforeExit = weighted(rental)
        ok(stop(1_909_500, third))
        val newOrigin = clock()
        assertEquals(newOrigin, (ok(marker().policyOrigin) as Observation.Available).value)
        producer.reconnect()
        ok(feed(3_709_501, fourth))
        assertEquals(RentalInactivePhase.COMPLETED, marker().phase)
        assertEquals(newOrigin, checkNotNull(marker().interval).from)
        assertEquals(0, BigDecimal("1800000.30").compareTo(weighted(rental) - creditedBeforeExit))
    }
}
