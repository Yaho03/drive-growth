package io.drivegrowth.app.host.rental

import android.database.sqlite.SQLiteDatabase
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
import org.json.JSONObject
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
    private var requests = 0
    private val base = Instant.parse("2026-11-10T03:00:00Z").toEpochMilli()
    // 한 시간 대여를 제어 clock으로 진행시키는 시험 정책. 제품의 2500ms 신선도/연속성 정책은 변경하지 않는다.
    private var policy = RecordingPolicy(7_200_000, 7_200_000, 1000, 3000, 0.1, 0.5)
    private fun clock() = RentalClock("explicit-simulation-boot", EpochMillis(base + elapsed), elapsed)
    private fun <T> ok(result: ContractResult<T>): T {
        assertTrue(result.toString(), result is ContractResult.Success)
        return (result as ContractResult.Success).value
    }
    override fun setUp() {
        super.setUp()
        val prefix = "rental-product-binding-${UUID.randomUUID()}"
        names = InputSource.entries.associateWith { "$prefix-${it.name}.db" }
        games = factory()
        definition = ok(AssetTrialDefinitionProvider(context.assets).load())
        val onboarding = StoredOnboardingHostSource(games)
        val initial = ok(onboarding.read()); identity = initial.identity
        ok(onboarding.choose(identity, FirstGrant.CANDIDATES.first()))
        ok(games.open()).use { ok(it.store.activateObservedDriving(identity.profileId)) }
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

    override fun tearDown() { names.values.forEach(context::deleteDatabase); super.tearDown() }
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
        val seed = visitSeed(ok(definition.rentalVisitDefinition()))
        return SessionRentalProductSource(games, { ContractResult.Success(definition) }, policy,
            { ContractResult.Success(clock()) }, { Random(seed) }, { RequestId("explicit-simulation-visit-${++requests}") })
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
