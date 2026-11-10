package io.drivegrowth.app.host.garage

import android.content.Context
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 명령/DB 작업은 전용 worker. F의 같은 DB adapter 공급 전에는 preview만 가능하다. */
class GarageCommandHost(context: Context, main: Executor, onState: (GarageCommandState) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    val controller = GarageCommandController(StoredGarageCommandSource(context), UnavailableGarageCommandJournal, worker, main,
        onState = onState)
    fun release() { controller.release(); worker.shutdown() }
}
