package io.drivegrowth.app.vehicle.navigation

import android.content.Context
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** NaviHelper3.0.5의 공개 API만 사용한다. initialize 성공 뒤 같은 worker에서 리스너를 등록한다. */
internal class PleosNavigationSdkPort(context: Context, private val worker: Executor) : NavigationSdkPort {
    private val helper = NaviHelper(context)
    private val closed = AtomicBoolean()
    private var registered = false
    private var listener: NaviHelperEventListener? = null
    override fun initialize(connected: () -> Unit, failed: (Throwable) -> Unit, events: (NavigationSdkEvent) -> Unit) {
        val callbacks = object : NaviHelperEventListener {
            override fun onDestinationInfo(value: DestinationInfo) = events(NavigationSdkEvent.Destination(
                NavigationDestination(value.latitude, value.longitude, value.name, value.address, value.poiId, value.poiSubId, value.poiProvider), value.remainDistance.toLong()))
            override fun onCurrentLocationInfo(value: CurrentLocationInfo) = events(NavigationSdkEvent.RouteId(value.routeId))
            override fun onNavigationStatus(status: NavigationStatus) = events(NavigationSdkEvent.Guidance(status = status.name))
            override fun onRouteStateInfo(value: RouteStateInfo) = events(NavigationSdkEvent.Guidance(mode = value.naviMode.name, isRouting = value.isRouting))
            override fun onRouteStarted(value: RouteStartInfo) = events(NavigationSdkEvent.RouteStarted(value.distance.toLong()))
            override fun onDestinationArrived(value: DestinationArrivedInfo) = events(NavigationSdkEvent.Arrived(value.name, value.address, value.distance.toLong(), value.duration.toLong()))
            override fun onDestinationChanged() = events(NavigationSdkEvent.DestinationChanged)
            override fun onRouteEnded() = events(NavigationSdkEvent.RouteEnded)
            override fun onRouteCancelled() = events(NavigationSdkEvent.RouteCancelled)
            override fun onError(errorCode: NaviErrorCode) = events(NavigationSdkEvent.Error(errorCode.name))
        }
        listener = callbacks
        helper.initialize(onConnected = {
            if (!closed.get()) try {
                worker.execute {
                    if (!closed.get() && !registered) {
                        try { helper.addListener(callbacks); registered = true; connected() }
                        catch (error: RuntimeException) { failed(error) }
                        catch (error: LinkageError) { failed(error) }
                    }
                }
            } catch (error: RejectedExecutionException) { failed(error) }
        }, onFailed = failed)
    }
    override fun query() { helper.getDestinationInfo(); helper.getRouteStateInfo(); helper.getCurrentLocationInfo() }
    override fun requestRoute(request: NavigationRouteRequest) { helper.requestRoute(RouteInfo(
        latitude = request.latitude, longitude = request.longitude, poiName = request.name,
        poiId = request.poiId, poiSubId = request.poiSubId.orEmpty(), poiProvider = request.poiProvider,
        address = request.address.orEmpty(), routeOption = RouteOption.valueOf(request.option.name),
    )) }
    override fun cancelRoute() = helper.cancelRoute()
    override fun close() {
        if (closed.getAndSet(true)) return
        try { if (registered) listener?.let { helper.removeListener(it) } }
        finally { registered = false; listener = null; helper.release() }
    }
}
