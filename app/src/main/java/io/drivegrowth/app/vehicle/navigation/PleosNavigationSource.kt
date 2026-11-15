package io.drivegrowth.app.vehicle.navigation

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 명시적으로 생성/연결하는 제품 입력 경계. Activity/gate/서비스/원장을 시작하지 않는다. */
class PleosNavigationSource(context: Context, main: Executor, timeoutMillis: Long) : AutoCloseable {
    private val app = context.applicationContext
    private val worker = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "DriveGrowthNavigation") }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    private val clock = object : NavigationClock {
        override fun utcMillis() = System.currentTimeMillis()
        override fun elapsedMillis() = SystemClock.elapsedRealtime()
    }
    private val timer = NavigationTimer { delay, action ->
        val task = worker.schedule(action, delay, TimeUnit.MILLISECONDS)
        NavigationCancellation { task.cancel(false) }
    }
    private val adapter = NavigationAdapter(worker, main, clock, timer,
        { permission(READ_PERMISSION) }, { permission(SEARCH_PERMISSION) },
        { PleosNavigationSdkPort(app, worker) }, timeoutMillis)

    fun connect(listener: (NavigationEvent) -> Unit) = adapter.connect(listener)
    internal fun reconnectForQuote(listener: (NavigationEvent) -> Unit) = adapter.reconnectForQuote(listener)
    fun disconnect() = adapter.disconnect()
    fun refresh() = adapter.refresh()
    fun requestRoute(request: NavigationRouteRequest) = adapter.requestRoute(request)
    fun cancelRoute(requestId: String) = adapter.cancelRoute(requestId)
    fun retry(command: NavigationCommand) = adapter.retry(command)
    override fun close() { adapter.close(); worker.shutdown() }

    private fun permission(name: String): NavigationPermission {
        val declared = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        return when {
            name !in declared -> NavigationPermission.NOT_DECLARED
            app.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED -> NavigationPermission.DENIED
            else -> NavigationPermission.GRANTED
        }
    }
    companion object {
        const val READ_PERMISSION = "pleos.car.permission.NAVI_ROUTE"
        const val SEARCH_PERMISSION = "pleos.car.permission.NAVI_ROUTE_SEARCH"
    }
}
