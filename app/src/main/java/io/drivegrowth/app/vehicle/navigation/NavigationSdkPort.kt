package io.drivegrowth.app.vehicle.navigation

/** 한 SDK worker에서만 생성/호출/정리한다. 콜백은 임의 스레드에서 올 수 있다. */
interface NavigationSdkPort {
    fun initialize(connected: () -> Unit, failed: (Throwable) -> Unit, events: (NavigationSdkEvent) -> Unit)
    fun query()
    fun requestRoute(request: NavigationRouteRequest)
    fun cancelRoute()
    fun close()
}
sealed interface NavigationSdkEvent {
    data class Destination(val destination: NavigationDestination, val remainingMeters: Long) : NavigationSdkEvent
    data class RouteId(val value: String?) : NavigationSdkEvent
    data class Guidance(val status: String? = null, val mode: String? = null, val isRouting: Boolean? = null) : NavigationSdkEvent
    data class RouteStarted(val estimatedMeters: Long) : NavigationSdkEvent
    data class Arrived(val name: String?, val address: String?, val distanceMeters: Long, val durationSeconds: Long) : NavigationSdkEvent
    data class Error(val code: String) : NavigationSdkEvent
    data object RouteEnded : NavigationSdkEvent
    data object RouteCancelled : NavigationSdkEvent
    data object DestinationChanged : NavigationSdkEvent
}
enum class NavigationPermission { GRANTED, NOT_DECLARED, DENIED }
interface NavigationClock { fun utcMillis(): Long; fun elapsedMillis(): Long }
fun interface NavigationCancellation { fun cancel() }
fun interface NavigationTimer { fun after(delayMillis: Long, action: () -> Unit): NavigationCancellation }
