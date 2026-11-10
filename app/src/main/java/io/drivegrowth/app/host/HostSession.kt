package io.drivegrowth.app.host

import android.content.Context
import io.drivegrowth.app.host.driving.*
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
internal class HostSession(context: Context, private val main: Executor, restoredResults: ResultsUiState = ResultsUiState(), restoredOnboarding: OnboardingHostUiState = OnboardingHostUiState()) {
    private val worker = Executors.newSingleThreadExecutor()
    val games = GameSessionFactory(context, questDefinitions = {
        when (val loaded = io.drivegrowth.app.data.AssetTrialDefinitionProvider(context.assets).load()) {
            is ContractResult.Failure -> loaded
            is ContractResult.Success -> try {
                loaded.value.questDefinitions(java.time.ZoneId.systemDefault())
            } catch (_: java.time.DateTimeException) { ContractResult.Failure(ContractError.POLICY_UNRESOLVED) }
        }
    })
    val drivingCalendar = ObservationCalendar(64) // 원관측-zone 상관용 기술 버퍼. 게임 수치가 아니다.
    private val recordingPolicy = io.drivegrowth.app.vehicle.TRIAL_INTERACTION_POLICY.let { policy ->
        RecordingPolicy(policy.maxSignalAgeMs, policy.maxSampleGapMs, 1000, policy.requiredParkMs,
            policy.maxStationarySpeedMps, 0.5) // skew/출발속도는 D의 명시 기술 시험값.
    }
    private val drivingSource = SessionObservedDrivingSource(games, io.drivegrowth.app.data.SqliteObservedDrivingJournal(),
        ObservedDrivingReducer(recordingPolicy) { java.util.UUID.randomUUID().toString() }, {
            when (val loaded = io.drivegrowth.app.data.AssetTrialDefinitionProvider(context.assets).load()) {
                is ContractResult.Failure -> loaded
                is ContractResult.Success -> loaded.value.drivingIncomeRules()
            }
        })
    private var drivingActivated = false // worker 전용. 매 관측의 실제 초기화/출처는 별도로 재확인한다.
    val driving = ObservedDrivingHost(worker, main,
        { RecordingClock(android.os.SystemClock.elapsedRealtime(), EpochMillis(System.currentTimeMillis())) },
        { receiver, frame, at ->
            when (val activated = activateDriving(frame.observation.source)) {
                is ContractResult.Failure -> activated
                is ContractResult.Success -> drivingSource.observe(receiver, frame, at)
            }
        }, { receiver, at ->
            if (drivingActivated) drivingSource.pause(receiver, at) else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        })
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
        io.drivegrowth.app.host.progression.FactoryProgressionSource(games, {
            val time = System.currentTimeMillis()
            if (time >= 0) Observation.Available(EpochMillis(time)) else Observation.Missing(MissingReason.NO_DATA)
        }))
    var deliveryUi = DeliveryInteractionState()
    val deliveryNavigation = SessionDeliveryNavigation(context, main)
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
        { java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")) },
        { EpochMillis(System.currentTimeMillis()) }, { kotlin.random.Random.Default },
        { RequestId("delivery-choice-" + java.util.UUID.randomUUID()) },
        { RouteId("delivery-route-" + java.util.UUID.randomUUID()) }), worker, main)
    private val source = StoredHostSource(context, sessions = games)
    val onboarding = OnboardingHostController(StoredOnboardingHostSource(games), worker, main, restoredOnboarding)
    val queries = HostQueryController(worker, main, source::read, null)
    val gacha = GachaLiveHost(CheckpointGachaSource(StoredGachaHostSource(context, games), SessionGachaJournal(games)), worker, main, queries::refresh)
    val ranking = io.drivegrowth.app.host.ranking.RankingController(worker, main, source::readRanking)
    val results = ResultsHostController(worker, main, source::markViewed, queries::refresh, restoredResults)

    fun expansionWorker(action: () -> Unit) { worker.execute(action) }
    fun pendingRoute(done: (ContractResult<HostPage?>) -> Unit) {
        worker.execute {
            val result = when (val opened = games.open()) {
                is ContractResult.Failure -> opened
                is ContractResult.Success -> opened.value.use { game ->
                    when (val saved = game.store.hostCommands.load(game.identity.profileId)) {
                        is ContractResult.Failure -> saved
                        is ContractResult.Success -> ContractResult.Success(pendingHostPage(saved.value?.command))
                    }
                }
            }
            main.execute { done(result) }
        }
    }

    fun release() {
        driving.stop()
        progression.release()
        delivery.release()
        deliveryNavigation.close()
        queries.release()
        results.release()
        ranking.release()
        gacha.release()
        onboarding.release()
        worker.shutdown() // 이미 받은 관측/pause의 영속 쓰기는 폐기하지 않는다.
    }
}
