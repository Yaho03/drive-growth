package io.drivegrowth.app.host

import android.content.Context
import io.drivegrowth.app.host.gacha.GachaLiveHost
import io.drivegrowth.app.host.gacha.CheckpointGachaSource
import io.drivegrowth.app.host.gacha.SessionGachaJournal
import io.drivegrowth.app.host.gacha.StoredGachaHostSource
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.app.host.onboarding.*
import io.drivegrowth.app.ui.results.ResultsUiState
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 구성 변경 사이 조회만 유지한다. Activity·View·차량 허용 판정은 보관하지 않는다. */
internal class HostSession(context: Context, main: Executor, restoredResults: ResultsUiState = ResultsUiState(), restoredOnboarding: OnboardingHostUiState = OnboardingHostUiState()) {
    private val worker = Executors.newSingleThreadExecutor()
    val games = GameSessionFactory(context)
    private val source = StoredHostSource(context, sessions = games)
    val onboarding = OnboardingHostController(StoredOnboardingHostSource(games), worker, main, restoredOnboarding)
    val queries = HostQueryController(worker, main, source::read, null)
    val gacha = GachaLiveHost(CheckpointGachaSource(StoredGachaHostSource(context, games), SessionGachaJournal(games)), worker, main, queries::refresh)
    val results = ResultsHostController(worker, main, source::markViewed, queries::refresh, restoredResults)

    fun release() {
        queries.release()
        results.release()
        gacha.release()
        onboarding.release()
        worker.shutdownNow()
    }
}
