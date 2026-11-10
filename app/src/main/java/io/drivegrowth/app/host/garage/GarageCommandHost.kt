package io.drivegrowth.app.host.garage

import android.content.Context
import io.drivegrowth.app.host.GameSessionFactory
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** 명령/DB 작업은 전용 worker이며 같은 F adapter의 저장·실행·해소를 함께 사용한다. */
class GarageCommandHost(context: Context, main: Executor, games: GameSessionFactory? = null, onState: (GarageCommandState) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val services = CheckpointGarageCommandAdapter(context, sessions = games)
    val controller = GarageCommandController(services, services, worker, main,
        onState = onState)
    fun release() { controller.release(); worker.shutdown() }
}
