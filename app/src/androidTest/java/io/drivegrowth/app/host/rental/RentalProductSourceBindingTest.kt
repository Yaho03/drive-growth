package io.drivegrowth.app.host.rental

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.onboarding.StoredOnboardingHostSource
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.progression.VehicleActivity
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
    private val policy = RecordingPolicy(7_200_000, 7_200_000, 1000, 3000, 0.1, 0.5)
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
        driving = SessionObservedDrivingSource(games, SqliteObservedDrivingJournal(),
            ObservedDrivingReducer(policy) { "explicit-simulation-drive" }, { definition.drivingIncomeRules() })
        producer = source()
        readJournal = SqliteRentalProductJournal(games, { error("조회는 현재 정의를 읽지 않는다") },
            { error("조회는 현재 clock을 읽지 않는다") }, { error("조회는 현재 원점을 읽지 않는다") })
    }
    override fun tearDown() { names.values.forEach(context::deleteDatabase); super.tearDown() }
    private fun factory() = GameSessionFactory(context, names, InputSource.SIMULATION) {
        when (val loaded = AssetTrialDefinitionProvider(context.assets).load()) {
            is ContractResult.Failure -> loaded
            is ContractResult.Success -> loaded.value.questDefinitions(ZoneId.of("UTC"))
        }
    }
    private fun source(): SessionRentalProductSource {
        val visit = ok(definition.rentalVisitDefinition())
        val desired = FirstGrant.CANDIDATES.first()
        val entries = visit.demand.entries.toList()
        val offset = entries.takeWhile { it.key != desired }.sumOf { it.value.toLong() }
        val weight = visit.demand.getValue(desired).toLong()
        // 쓰기 이전에 중앙 분포에서 재현 seed를 고른다. 저장 후 고객/사고를 다시 뽑지 않는다.
        val seed = (0..100_000).first { value ->
            val rng = Random(value)
            val car = rng.nextLong(entries.sumOf { it.value.toLong() })
            val duration = rng.nextLong(visit.durations.values.sumOf { it.toLong() })
            rng.nextLong(visit.customers.sumOf { it.weight.toLong() })
            car in offset until offset + weight && duration < visit.durations.getValue(1) && rng.nextInt(100) >= 13
        }
        return SessionRentalProductSource(games, { ContractResult.Success(definition) }, policy,
            { ContractResult.Success(clock()) }, { Random(seed) }, { RequestId("explicit-simulation-visit-${++requests}") })
    }
    private fun feed(at: Long, receiver: String = "simulation-receiver-1"): ContractResult<RentalRuntimeSnapshot> {
        elapsed = at
        val input = VehicleObservation(identity.source, elapsed, clock().epoch, Observation.Available(1.0),
            Observation.Available(Gear.DRIVE), Observation.Available(true))
        val frame = DrivingFrame(input, Observation.Available(ZoneId.of("UTC")))
        val original = driving.observe(receiver, frame, RecordingClock(elapsed, input.receivedAt))
        ok(original)
        return producer.observe(frame, original)
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
}
