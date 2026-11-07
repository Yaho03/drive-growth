package io.drivegrowth.app.host

import android.content.Context
import io.drivegrowth.app.ui.results.ResultsUiState
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 구성 변경 사이 조회만 유지한다. Activity·View·차량 허용 판정은 보관하지 않는다. */
internal class HostSession(context: Context, main: Executor, restoredResults: ResultsUiState = ResultsUiState()) {
    private val worker = Executors.newSingleThreadExecutor()
    private val source = StoredHostSource(context)
    val queries = HostQueryController(worker, main, source::read, null)
    val results = ResultsHostController(worker, main, source::markViewed, queries::refresh, restoredResults)

    fun release() {
        queries.release()
        results.release()
        worker.shutdownNow()
    }
}
