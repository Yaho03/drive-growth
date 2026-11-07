package io.drivegrowth.app.host

import android.content.Context
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 구성 변경 사이 조회만 유지한다. Activity·View·차량 허용 판정은 보관하지 않는다. */
internal class HostSession(context: Context, main: Executor) {
    private val worker = Executors.newSingleThreadExecutor()
    private val source = StoredHostSource(context)
    val queries = HostQueryController(worker, main, source::read, null)

    fun release() {
        queries.release()
        worker.shutdownNow()
    }
}
