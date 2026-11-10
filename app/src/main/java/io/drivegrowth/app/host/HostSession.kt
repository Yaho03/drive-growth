package io.drivegrowth.app.host

import android.content.Context
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
        progression.release()
        delivery.release()
        deliveryNavigation.close()
        queries.release()
        results.release()
        ranking.release()
        gacha.release()
        onboarding.release()
        worker.shutdownNow()
    }
}
