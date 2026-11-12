package io.drivegrowth.app.host

import android.content.Context
import android.os.Bundle
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.app.host.progression.readStoredRestProgression
import io.drivegrowth.app.data.RestAtomicStorage
import io.drivegrowth.app.data.RestClockDomain
import io.drivegrowth.app.host.rest.ForegroundRestObservationProducer
import io.drivegrowth.app.host.rest.RestEvidencePolicy
import io.drivegrowth.app.data.questDefinitions
import io.drivegrowth.app.host.delivery.*
import io.drivegrowth.app.ui.delivery.DeliveryInteractionState
import io.drivegrowth.app.host.gacha.GachaLiveHost
import io.drivegrowth.app.host.gacha.CheckpointGachaSource
import io.drivegrowth.app.host.gacha.SessionGachaJournal
import io.drivegrowth.app.host.gacha.StoredGachaHostSource
import io.drivegrowth.core.contract.*
import io.drivegrowth.app.host.onboarding.*
import io.drivegrowth.app.ui.results.ResultsUiState
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 구성 변경 사이 조회만 유지한다. Activity·View·차량 허용 판정은 보관하지 않는다. */
internal class HostSession(
    context: Context,
    private val main: Executor,
    restoredResults: ResultsUiState = ResultsUiState(),
    restoredOnboarding: OnboardingHostUiState = OnboardingHostUiState(),
    restoredShop: io.drivegrowth.app.ui.shop.ShopUiState = io.drivegrowth.app.ui.shop.ShopUiState(),
    restoredFacility: io.drivegrowth.app.host.facility.FacilityCommand.Buy? = null,
    var failedShopRestore: Bundle? = null,
    val simulation: HostSimulation? = null,
) {
    private val worker = Executors.newSingleThreadExecutor()
    private fun epochNow(): EpochMillis = if (simulation == null) EpochMillis(System.currentTimeMillis()) else
        when (val result = simulation.clock()) { is ContractResult.Failure -> error("모의 논리 시각 미제공"); is ContractResult.Success -> result.value.epoch }
    private val questDefinitions = {
        when (val loaded = io.drivegrowth.app.data.AssetTrialDefinitionProvider(context.assets).load()) {
            is ContractResult.Failure -> loaded
            is ContractResult.Success -> try {
                loaded.value.questDefinitions(if (simulation == null) java.time.ZoneId.systemDefault() else java.time.ZoneId.of("UTC"))
            } catch (_: java.time.DateTimeException) { ContractResult.Failure(ContractError.POLICY_UNRESOLVED) }
        }
    }
    val games = (if (simulation == null) GameSessionFactory(context, questDefinitions = questDefinitions) else
        GameSessionFactory(context, mapOf(InputSource.SIMULATION to "unified-simulation.db"), InputSource.SIMULATION,
            questDefinitions, { epochNow().value })).also { it.enableAchievements() }
    val drivingCalendar = ObservationCalendar(64) // 원관측-zone 상관용 기술 버퍼. 게임 수치가 아니다.
    private val recordingPolicy = simulation?.policy ?: io.drivegrowth.app.vehicle.TRIAL_INTERACTION_POLICY.let { policy ->
        RecordingPolicy(policy.maxSignalAgeMs, policy.maxSampleGapMs, 1000, policy.requiredParkMs,
            policy.maxStationarySpeedMps, 0.5) // skew/출발속도는 D의 명시 기술 시험값.
    }
    val rentalClock: RentalClockReader = if (simulation == null) RentalClockReader(io.drivegrowth.app.host.rental.DeviceRentalClock(context)::read)
        else RentalClockReader(simulation::clock)
    private val rentalSource = io.drivegrowth.app.host.rental.SessionRentalProductSource(games, {
        io.drivegrowth.app.data.AssetTrialDefinitionProvider(context.assets).load()
    }, recordingPolicy, rentalClock::read, random = { simulation?.rentalRandom(games, it) ?: kotlin.random.Random.Default })
    private var ordinaryRentalReplayClock: RestClockDomain? = null // 직렬 worker의 새 시연 세션 복원 경계.
    val rental = io.drivegrowth.app.host.rental.RentalProductHost(worker, main, { frame, recorded ->
        val replayClock = ordinaryRentalReplayClock
        if (replayClock == null) rentalSource.observe(frame, recorded)
        else rentalSource.observeOrdinarySimulationReplay(frame, recorded, replayClock,
            ordinaryTerminal = simulation?.restSample(frame)?.ordinaryTerminal == true).also { result ->
            if (result is ContractResult.Success && result.value.deferred.isEmpty()) ordinaryRentalReplayClock = null
        }
    },
        rentalSource::reconnect, rentalSource::pauseObservations, rentalSource::inactive)
    private val drivingSource = SessionObservedDrivingSource(games, io.drivegrowth.app.data.SqliteObservedDrivingJournal(),
        ObservedDrivingReducer(recordingPolicy) { java.util.UUID.randomUUID().toString() }, {
            when (val loaded = io.drivegrowth.app.data.AssetTrialDefinitionProvider(context.assets).load()) {
                is ContractResult.Failure -> loaded
                is ContractResult.Success -> loaded.value.drivingIncomeRules()
            }
        })
    private var restProducer: ForegroundRestObservationProducer? = null
    private var drivingActivated = false // worker 전용. 매 관측의 실제 초기화/출처는 별도로 재확인한다.
    val driving = ObservedDrivingHost(worker, main,
        { if (simulation == null) RecordingClock(android.os.SystemClock.elapsedRealtime(), epochNow()) else
            when (val result = simulation.clock()) { is ContractResult.Failure -> null
                is ContractResult.Success -> RecordingClock(result.value.elapsedMs, result.value.epoch) } },
        { receiver, frame, at ->
            val recorded = when (val activated = activateDriving(frame.observation.source)) {
                is ContractResult.Failure -> activated
                is ContractResult.Success -> if (simulation?.restRecording == true) {
                    val sample = simulation.restSample(frame)
                    if (sample == null) ContractResult.Failure(ContractError.STATE_CONFLICT)
                    else when (val staged = stageSimulationRest(frame, sample)) {
                        is ContractResult.Failure -> staged
                        is ContractResult.Success -> drivingSource.observe(SIMULATION_REST_RECEIVER, frame, at)
                    }
                } else drivingSource.observe(receiver, frame, at)
            }
            val simulated = simulation?.observed(frame, recorded)
            if (simulated !is ContractResult.Failure) {
                val rentalResult = rental.observed(receiver, frame, recorded)
                simulation?.rentalResult(rentalResult)
                if (simulation != null && recorded is ContractResult.Success &&
                    rentalResult is ContractResult.Success && rentalSource.issue == null
                ) simulation.inputConsumed(frame, recorded.value, rentalResult.value)
            }
            recorded
        }, { receiver, at ->
            // 명시 시연은 화면이 멈춘 동안 표본과 논리 시간이 모두 멈춘다.
            if (drivingActivated && simulation?.restRecording != true) drivingSource.pause(receiver, at)
            else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        })

    private fun stageSimulationRest(frame: DrivingFrame, sample: SimulationRestSample): ContractResult<Unit> {
        if (frame.observation.source != InputSource.SIMULATION) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val producer = restProducer ?: when (val prepared = openSimulationRestProducer(sample)) {
            is ContractResult.Failure -> return prepared
            is ContractResult.Success -> {
                val (restored, replayClock) = prepared.value
                ordinaryRentalReplayClock = replayClock
                restProducer = restored
                drivingSource.restObservationProvider = restored
                restored
            }
        }
        return producer.observe(SIMULATION_REST_RECEIVER, sample.clock, frame.observation, sample.area)
    }

    private fun openSimulationRestProducer(
        sample: SimulationRestSample,
    ): ContractResult<Pair<ForegroundRestObservationProducer, RestClockDomain?>> {
        val game = when (val opened = games.open()) {
            is ContractResult.Failure -> return opened
            is ContractResult.Success -> opened.value
        }
        return game.use {
            if (game.identity.source != InputSource.SIMULATION)
                return@use ContractResult.Failure(ContractError.INVALID_INPUT)
            val definition = when (val loaded = game.store.trialDefinitions.load()) {
                is ContractResult.Failure -> return@use loaded
                is ContractResult.Success -> loaded.value
            }
            val producer = ForegroundRestObservationProducer(game.identity, definition::restRules,
                RestEvidencePolicy(recordingPolicy.maxGapMs, recordingPolicy.maxAgeMs, recordingPolicy.maxUtcSkewMs,
                    recordingPolicy.movingMps, recordingPolicy.stationaryMps),
                definition.restPricing)
            game.store.atomic { db ->
                when (val activated = game.store.restDriving.activate(game.identity.profileId)) {
                    is ContractResult.Failure -> activated
                    is ContractResult.Success -> {
                        val contexts = RestAtomicStorage.contexts(db, game.identity).map { it.value }
                        when (val restored = producer.restore(contexts)) {
                            is ContractResult.Failure -> return@atomic restored
                            is ContractResult.Success -> Unit
                        }
                        var replayClock = sample.clock.takeIf { sample.ordinaryTerminal }
                        if (!sample.restRun) {
                            val observed = when (val saved = game.store.observedDriving.read(db, game.identity)) {
                                is ContractResult.Failure -> return@atomic saved
                                is ContractResult.Success -> saved.value
                            }
                            val trip = observed.trip
                            val anchor = observed.anchor
                            if (trip != null && anchor != null) {
                                val context = contexts.singleOrNull { it.trip == trip }
                                    ?: return@atomic ContractResult.Failure(ContractError.STORAGE_FAILURE)
                                val last = context.segments.lastOrNull()
                                val clock = last?.let { RestClockDomain(it.clockDomain, it.span.bootId) }
                                    ?: context.departureClock
                                if ((last?.span?.to ?: trip.departure) != anchor || clock != sample.clock ||
                                    observed.receiver != SIMULATION_REST_RECEIVER)
                                    return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
                                val area = last?.span?.toPlace ?: Observation.Missing(MissingReason.NO_DATA)
                                when (val staged = producer.observe(SIMULATION_REST_RECEIVER, clock, anchor, area)) {
                                    is ContractResult.Failure -> return@atomic staged
                                    is ContractResult.Success -> replayClock = clock
                                }
                            }
                        }
                        ContractResult.Success(producer to replayClock)
                    }
                }
            }
        }
    }

    private fun activateDriving(source: InputSource): ContractResult<Unit> = when (val opened = games.open()) {
        is ContractResult.Failure -> opened
        is ContractResult.Success -> opened.value.use { game ->
            if (game.identity.source != source) ContractResult.Failure(ContractError.STATE_CONFLICT)
            else when (val initialized = game.store.readInitialization(game.identity.profileId)) {
                is ContractResult.Failure -> initialized
                is ContractResult.Success -> if (initialized.value.status !is ProfileInitializationStatus.Existing)
                    ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
                else if (drivingActivated) ContractResult.Success(Unit)
                else when (val activated = game.store.activateObservedDriving(game.identity.profileId)) {
                    is ContractResult.Failure -> activated
                    is ContractResult.Success -> { drivingActivated = true; ContractResult.Success(Unit) }
                }
            }
        }
    }
    var progressionUi = io.drivegrowth.app.ui.progress.ProgressUiState()
    val progression = io.drivegrowth.app.host.progression.ProgressionController(worker, main,
        io.drivegrowth.app.host.progression.FactoryProgressionSource(
            games = games,
            now = {
                val time = epochNow().value
                if (time >= 0) Observation.Available(EpochMillis(time)) else Observation.Missing(MissingReason.NO_DATA)
            },
            readRest = if (simulation != null) ::readStoredRestProgression else null,
        ))
    var deliveryUi = DeliveryInteractionState()
    val deliveryNavigation: HostDeliveryNavigation = simulation?.navigation(games, main) ?: SessionDeliveryNavigation(context, main)
    val delivery = DeliveryHostController(StoredDeliveryHostSource(
        FactoryDeliveryHostQueries(games) { it.terms() },
        { when (val loaded = io.drivegrowth.app.data.AssetTrialDefinitionProvider(context.assets).load()) {
            is ContractResult.Failure -> loaded
            is ContractResult.Success -> {
                val definition = loaded.value
                val proposal = definition.deliveryRules()
                val change = definition.deliveryChangeRules()
                val settlement = definition.deliverySettlementRules()
                when {
                    proposal is ContractResult.Failure -> proposal
                    change is ContractResult.Failure -> change
                    settlement is ContractResult.Failure -> settlement
                    else -> ContractResult.Success(DeliveryHostRules((proposal as ContractResult.Success).value,
                        (change as ContractResult.Success).value, (settlement as ContractResult.Success).value))
                }
            }
        } }, deliveryNavigation.port, SessionDeliveryJournal(games),
        { java.time.Instant.ofEpochMilli(epochNow().value).atZone(if (simulation == null) java.time.ZoneId.of("Asia/Seoul") else java.time.ZoneId.of("UTC")).toLocalDate() },
        { epochNow() }, { kotlin.random.Random.Default },
        { RequestId("delivery-choice-" + java.util.UUID.randomUUID()) },
        { RouteId("delivery-route-" + java.util.UUID.randomUUID()) }), worker, main)
    private val source = StoredHostSource(context, sessions = games)
    val onboarding = OnboardingHostController(StoredOnboardingHostSource(games), worker, main, restoredOnboarding)
    val queries = HostQueryController(worker, main, source::read, null)
    val gacha = GachaLiveHost(CheckpointGachaSource(StoredGachaHostSource(context, games), SessionGachaJournal(games)), worker, main, queries::refresh)
    val ranking = io.drivegrowth.app.host.ranking.RankingController(worker, main, { source.readRanking(epochNow()) })
    val results = ResultsHostController(worker, main, source::markViewed, queries::refresh, restoredResults,
        readHistory = { io.drivegrowth.app.data.SettlementHistoryReader.read(games) })

    val facility = io.drivegrowth.app.host.facility.FacilityHostController(
        io.drivegrowth.app.host.facility.FactoryFacilityHostSource(context, games,
            io.drivegrowth.app.host.facility.CheckpointFacilityDurablePort()), worker, main)

    val home = io.drivegrowth.app.host.shop.HomeShopHost(
        io.drivegrowth.app.host.shop.FactoryHomeShopPort(context, games), facility, worker, main, restoredShop, restoredFacility, restoredOnboarding.identity)

    fun attachSimulation(accept: (DrivingFrame, () -> Unit) -> Unit) {
        simulation?.attach(worker, accept)
    }

    fun prepareSimulation(rest: Boolean = false, done: (ContractResult<Unit>) -> Unit) {
        worker.execute {
            val result = when (val opened = games.open()) {
                is ContractResult.Failure -> opened
                is ContractResult.Success -> opened.value.use { game ->
                    when (val initialized = game.store.readInitialization(game.identity.profileId)) {
                        is ContractResult.Failure -> initialized
                        is ContractResult.Success -> if (initialized.value.status !is ProfileInitializationStatus.Existing)
                            ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
                        else when (val saved = game.store.hostCommands.load(game.identity.profileId)) {
                            is ContractResult.Failure -> saved
                            is ContractResult.Success -> if (saved.value != null)
                                ContractResult.Failure(ContractError.STATE_CONFLICT)
                                else if (rest) checkRestSimulation(game) else ContractResult.Success(Unit)
                        }
                    }
                }
            }
            main.execute { done(result) }
        }
    }

    private fun checkRestSimulation(game: GameSession): ContractResult<Unit> {
        if (simulation == null || game.identity.source != InputSource.SIMULATION)
            return ContractResult.Failure(ContractError.INVALID_INPUT)
        when (val delivery = game.store.delivery.inProgress()) {
            is ContractResult.Failure -> return delivery
            is ContractResult.Success -> if (delivery.value != null) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        }
        return when (val loaded = game.store.trialDefinitions.load()) {
            is ContractResult.Failure -> loaded
            is ContractResult.Success -> if (loaded.value.rest.minDrivingMs == 3_600_000L &&
                loaded.value.rest.minParkedMs == 300_000L) ContractResult.Success(Unit)
            else ContractResult.Failure(ContractError.POLICY_UNRESOLVED)
        }
    }

    fun expansionWorker(action: () -> Unit) { worker.execute(action) }
    fun pendingRoute(done: (ContractResult<HostPage?>) -> Unit) {
        worker.execute {
            val result = when (val opened = games.open()) {
                is ContractResult.Failure -> opened
                is ContractResult.Success -> opened.value.use { game ->
                    when (val saved = game.store.hostCommands.load(game.identity.profileId)) {
                        is ContractResult.Failure -> saved
                        is ContractResult.Success -> pendingHostPage(saved.value?.command, facilityPageAvailable = true, decorationPageAvailable = true)
                    }
                }
            }
            main.execute { done(result) }
        }
    }

    fun release() {
        simulation?.stop()
        simulation?.changed = null
        driving.stop()
        rental.pause()
        progression.release()
        home.release()
        facility.release()
        delivery.release()
        deliveryNavigation.close()
        queries.release()
        results.release()
        ranking.release()
        gacha.release()
        onboarding.release()
        worker.shutdown() // 이미 받은 관측/pause의 영속 쓰기는 폐기하지 않는다.
    }

    private companion object { const val SIMULATION_REST_RECEIVER = "unified-simulation-rest" }

}
