package io.drivegrowth.app.simulation

import io.drivegrowth.app.data.SqliteObservedDrivingJournal
import io.drivegrowth.app.data.RestClockDomain
import io.drivegrowth.app.data.TrialDefinition
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.rest.ForegroundRestObservationProducer
import io.drivegrowth.app.host.rest.RestAreaProof
import io.drivegrowth.app.host.rest.RestEvidencePolicy
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.RestPlace
import io.drivegrowth.core.driving.RestRules
import java.time.ZoneId
import java.util.UUID

/** 명시 실행용 입력. 호출자는 초기화된 격리 SIMULATION factory와 단일 worker를 제공한다. */
internal object RestSimulationScenario {
    const val label = "SIMULATION · 휴식 시험 장소 · 실제 차량 입력 아님"
    private const val sampleMs = 60_000L
    private const val epochBaseMs = 1_794_279_600_000L
    private const val receiver = "rest-simulation"
    internal val clockDomain = RestClockDomain("rest-simulation-elapsed", "rest-simulation-boot")
    internal val recordingPolicy = RecordingPolicy(sampleMs, sampleMs, 1_000, 3_000, 0.1, 0.5)
    internal val evidencePolicy = RestEvidencePolicy(sampleMs, sampleMs, 1_000, 0.5, 0.1)

    fun run(games: GameSessionFactory): ContractResult<List<ObservedDrivingReceipt>> =
        when (val prepared = prepareRun(games)) {
            is ContractResult.Failure -> prepared
            is ContractResult.Success -> when (val result = prepared.value.run()) {
                is ContractResult.Failure -> result
                is ContractResult.Success -> ContractResult.Success(checkNotNull(result.value))
            }
        }

    fun prepareRun(games: GameSessionFactory): ContractResult<Run> {
        val (identity, definition) = when (val setup = prepare(games)) {
            is ContractResult.Failure -> return setup
            is ContractResult.Success -> setup.value
        }
        val producer = ForegroundRestObservationProducer(identity, definition::restRules, evidencePolicy, definition.restPricing)
        val source = SessionObservedDrivingSource(games, SqliteObservedDrivingJournal(),
            ObservedDrivingReducer(recordingPolicy) { UUID.randomUUID().toString() }, definition::drivingIncomeRules)
        source.restObservationProvider = producer
        return ContractResult.Success(Run(producer, source, samples(definition.rest).iterator()))
    }

    internal data class Sample(val frame: DrivingFrame, val place: Observation<RestAreaProof>, val last: Boolean)

    /** 실패한 표본과 같은 source/producer를 worker에 남겨 명시 재시도에서 그대로 사용한다. */
    class Run internal constructor(
        private val producer: ForegroundRestObservationProducer,
        private val source: SessionObservedDrivingSource,
        private val inputs: Iterator<Sample>,
    ) {
        private val receipts = mutableListOf<ObservedDrivingReceipt>()
        private var pending: Sample? = null
        private var phase: Phase? = null
        private var completed = false

        /** null은 현재 거래를 마치고 다음 원표본 생성 전에 중지한 상태다. */
        fun run(
            onPhase: (Phase) -> Unit = {},
            canContinue: () -> Boolean = { true },
        ): ContractResult<List<ObservedDrivingReceipt>?> {
            while (true) {
                if (completed) return ContractResult.Success(receipts.toList())
                if (!canContinue()) return ContractResult.Success(null)
                val (frame, place, last) = pending ?: inputs.next().also { pending = it }
                val raw = frame.observation
                val next = phaseFor(phase, place)
                if (next != phase) { phase = next; onPhase(next) }
                when (val staged = producer.observe(receiver, clockDomain, raw, place)) {
                    is ContractResult.Failure -> return staged
                    is ContractResult.Success -> Unit
                }
                when (val recorded = source.observe(receiver, frame, RecordingClock(raw.receivedElapsedMs, raw.receivedAt))) {
                    is ContractResult.Failure -> return recorded
                    is ContractResult.Success -> {
                        receipts += recorded.value.committed
                        recorded.value.waiting?.let { return ContractResult.Failure(it) }
                        pending = null
                        completed = last
                    }
                }
            }
        }
    }

    enum class Phase { DRIVE, REST, DEPARTURE }

    internal fun phaseFor(previous: Phase?, place: Observation<RestAreaProof>): Phase = when {
        (place as? Observation.Available)?.value?.inside == true -> Phase.REST
        previous == Phase.REST || previous == Phase.DEPARTURE -> Phase.DEPARTURE
        else -> Phase.DRIVE
    }

    private fun prepare(games: GameSessionFactory): ContractResult<Pair<GameIdentity, TrialDefinition>> {
        val session = when (val opened = games.open()) {
            is ContractResult.Failure -> return opened
            is ContractResult.Success -> opened.value
        }
        session.use { game ->
            val identity = game.identity
            if (identity.source != InputSource.SIMULATION) return ContractResult.Failure(ContractError.INVALID_INPUT)
            val profile = identity.profileId
            when (val initialized = game.store.readInitialization(profile)) {
                is ContractResult.Failure -> return initialized
                is ContractResult.Success -> if (initialized.value.status !is ProfileInitializationStatus.Existing)
                    return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            }
            val definition = when (val loaded = game.store.trialDefinitions.load()) {
                is ContractResult.Failure -> return loaded
                is ContractResult.Success -> loaded.value
            }
            // 미완료·완료 모듈에 시연 표본을 다시 주입하지 않는다. 재개방은 기존 읽기 포트를 사용한다.
            when (val existing = game.store.restDriving.state(profile)) {
                is ContractResult.Success -> return ContractResult.Failure(ContractError.STATE_CONFLICT)
                is ContractResult.Failure -> if (existing.error != ContractError.DATA_UNAVAILABLE) return existing
            }
            when (val observed = game.store.activateObservedDriving(profile)) {
                is ContractResult.Failure -> return observed
                is ContractResult.Success -> if (observed.value.revision != 0L)
                    return ContractResult.Failure(ContractError.STATE_CONFLICT)
            }
            return when (val activated = game.store.restDriving.activate(profile)) {
                is ContractResult.Failure -> activated
                is ContractResult.Success -> ContractResult.Success(identity to definition)
            }
        }
    }

    internal fun frames(rules: RestRules): Sequence<Pair<DrivingFrame, Observation<RestAreaProof>>> =
        samples(rules).map { it.frame to it.place }

    private fun samples(rules: RestRules): Sequence<Sample> = sequence {
        val firstPark = rules.minDrivingMs + 1_000
        val departure = firstPark + rules.minParkedMs + 1_000
        val nextDriveMs = 100_000L
        for (elapsed in sampleTimes(0, rules.minDrivingMs)) yield(sample(elapsed, parked = false, inside = false))
        for (elapsed in sampleTimes(firstPark, rules.minParkedMs)) yield(sample(elapsed, parked = true, inside = true))
        for (elapsed in sampleTimes(departure, nextDriveMs)) yield(sample(elapsed, parked = false, inside = false))
        val lastPark = departure + nextDriveMs + 1_000
        yield(sample(lastPark, parked = true, inside = false))
        yield(sample(lastPark + recordingPolicy.parkMs, parked = true, inside = false, last = true))
    }

    private fun sampleTimes(start: Long, duration: Long): Sequence<Long> = sequence {
        yield(start)
        var remaining = duration
        var elapsed = start
        while (remaining > 0) {
            val step = minOf(remaining, sampleMs)
            remaining -= step
            elapsed += step
            yield(elapsed)
        }
    }

    private fun sample(elapsed: Long, parked: Boolean, inside: Boolean, last: Boolean = false): Sample {
        val utc = EpochMillis(epochBaseMs + elapsed)
        val raw = VehicleObservation(InputSource.SIMULATION, elapsed, utc,
            Observation.Available(if (parked) 0.0 else 10.0),
            Observation.Available(if (parked) Gear.PARK else Gear.DRIVE), Observation.Available(!parked))
        val place = RestAreaProof("rest-simulation-area", RestPlace.SERVICE_AREA, ContentVersion("rest-simulation-v1"),
            "https://example.test/rest-simulation", "fixture-only", "2026-11-11",
            InputSource.SIMULATION, inside, elapsed, utc)
        return Sample(DrivingFrame(raw, Observation.Available(ZoneId.of("UTC"))), Observation.Available(place), last)
    }
}
