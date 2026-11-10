package io.drivegrowth.app.vehicle.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation

/** 전경 호스트가 main thread에서 start/close한다. 권한 요청·서비스·제품 장소 판정은 시작하지 않는다. */
class ForegroundPleosLocation internal constructor(
    private val createClient: () -> LocationSdkClient,
    private val hasPermission: () -> Boolean,
    private val post: (() -> Unit) -> Unit,
    private val checkMainThread: () -> Unit,
    private val elapsedMillis: () -> Long,
    private val utcMillis: () -> Long,
    private val deliver: (Observation<PleosLocationFix>) -> Unit,
) : AutoCloseable {
    constructor(context: Context, deliver: (Observation<PleosLocationFix>) -> Unit) : this(
        createClient = context.applicationContext.let { app -> { PleosLocationSdkClient(app) } },
        hasPermission = context.applicationContext.let { app -> { app.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED } },
        post = Handler(Looper.getMainLooper()).let { handler -> { action -> handler.post(action); Unit } },
        checkMainThread = { check(Looper.myLooper() == Looper.getMainLooper()) },
        elapsedMillis = SystemClock::elapsedRealtime,
        utcMillis = System::currentTimeMillis,
        deliver = deliver,
    )

    private var generation = Any()
    private var sdk: LocationSdkClient? = null
    private var listener: FusedLocationListener? = null
    private var area: PleosAreaSubscription? = null

    fun start(area: PleosAreaDefinition, onArea: (PleosAreaObservation) -> Unit) {
        start(PleosAreaSubscription(area, onArea))
    }

    fun start() = start(null)

    private fun start(subscription: PleosAreaSubscription?) {
        checkMainThread()
        close()
        area = subscription
        val token = Any().also { generation = it }
        if (!hasPermission()) {
            emit(token, Observation.Missing(MissingReason.PERMISSION_DENIED))
            subscription?.let { emitArea(token, it, it.fail(MissingReason.PERMISSION_DENIED, receipt())) }
            return
        }
        // 새 연결에서 이전 표본을 유지하지 않는다. 첫 실제 callback 전에는 위치를 모른다.
        emit(token, Observation.Missing(MissingReason.NO_DATA))
        subscription?.let { emitArea(token, it, it.missing(MissingReason.NO_DATA, receipt())) }
        val callback = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                emit(token, pleosLocationFix(location, elapsedMillis(), EpochMillis(utcMillis())))
            }
            override fun onError(error: Exception) {
                emit(token, Observation.Missing(if (error is SecurityException) MissingReason.PERMISSION_DENIED else MissingReason.NO_DATA))
            }
        }
        var failure: MissingReason? = null
        try {
            val client = createClient().also { sdk = it }
            client.initialize()
            if (!client.isSupported()) {
                failure = MissingReason.UNSUPPORTED
            } else {
                listener = callback
                client.register(callback)
                if (subscription != null) {
                    val proximity = client as? LocationAreaSdkClient
                    if (proximity == null) {
                        failure = MissingReason.UNSUPPORTED
                    } else proximity.addArea(ProximityAlertRequest(subscription.definition.latitude, subscription.definition.longitude,
                        subscription.definition.radiusMeters, setOf(ProximityTransition.ENTER, ProximityTransition.EXIT)),
                        object : ProximityAlertListener {
                            override fun onRegistered(alertId: String) {
                                val at = receipt()
                                dispatch(token) { subscription.registered(alertId, hasPermission(), at)?.let(subscription.deliver) }
                            }
                            override fun onTransition(event: ProximityAlertEvent) {
                                val at = receipt()
                                dispatch(token) { subscription.transition(event, hasPermission(), at)?.let(subscription.deliver) }
                            }
                            override fun onError(exception: Exception) {
                                val at = receipt()
                                dispatch(token) { subscription.deliver(subscription.fail(
                                    if (exception is SecurityException) MissingReason.PERMISSION_DENIED else MissingReason.NO_DATA, at)) }
                            }
                        })
                }
            }
        } catch (_: SecurityException) {
            failure = MissingReason.PERMISSION_DENIED
        } catch (_: IllegalStateException) {
            failure = MissingReason.NO_DATA
        } catch (error: RuntimeException) {
            // 예상하지 못한 SDK 오류를 정상 Missing으로 숨기지 않되 자원 정리는 시도한다.
            generation = Any()
            try { releaseClient() } catch (cleanup: RuntimeException) { error.addSuppressed(cleanup) }
            throw error
        }
        if (failure != null) {
            // register가 callback을 남긴 뒤 실패해도 그 세대의 결과를 전달하지 않는다.
            val failedToken = Any().also { generation = it }
            releaseClient()
            emit(failedToken, Observation.Missing(failure))
            subscription?.let { emitArea(failedToken, it, it.fail(failure, receipt())) }
        }
    }

    private fun receipt(): Observation<PleosAreaReceipt> {
        val elapsed = elapsedMillis()
        val utc = utcMillis()
        return if (elapsed < 0 || utc < 0) Observation.Missing(MissingReason.NO_DATA)
        else Observation.Available(PleosAreaReceipt(elapsed, EpochMillis(utc)))
    }

    private fun dispatch(token: Any, action: () -> Unit) {
        post { if (generation === token) action() }
    }

    private fun emitArea(token: Any, subscription: PleosAreaSubscription, value: PleosAreaObservation) {
        dispatch(token) { subscription.deliver(if (!hasPermission())
            subscription.fail(MissingReason.PERMISSION_DENIED, value.receipt) else value) }
    }

    private fun emit(token: Any, value: Observation<PleosLocationFix>) {
        post {
            if (generation === token) {
                deliver(if (value is Observation.Available && !hasPermission())
                    Observation.Missing(MissingReason.PERMISSION_DENIED) else value)
            }
        }
    }

    override fun close() {
        checkMainThread()
        generation = Any()
        releaseClient()
    }

    private fun releaseClient() {
        val client = sdk ?: run { area = null; return }
        val callback = listener
        try {
            try {
                if (callback != null && hasPermission()) client.unregister(callback)
            } catch (_: SecurityException) {
                // 권한 회수 뒤에도 영역 정리와 release를 시도한다.
            }
            if (hasPermission()) for (id in area?.registeredIds.orEmpty()) try {
                (client as? LocationAreaSdkClient)?.removeArea(id)
            } catch (_: SecurityException) {
                // SDK release가 알려진/미완료 등록도 정리한다. 원격 완료 ACK를 의미하지 않는다.
            }
        } finally {
            client.release()
            // release 실패 시 소유권을 보존해 다음 start가 새 연결을 만들지 못하게 한다.
            sdk = null
            listener = null
            area = null
        }
    }

    companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}

/** JVM 시험은 이 호출 경계만 대체하며 실제 SDK callback 타입과 변환기는 그대로 사용한다. */
internal interface LocationSdkClient {
    fun initialize()
    fun isSupported(): Boolean
    fun register(listener: FusedLocationListener)
    fun unregister(listener: FusedLocationListener)
    fun release()
}

internal interface LocationAreaSdkClient {
    fun addArea(request: ai.pleos.playground.location.fused.model.ProximityAlertRequest,
        listener: ai.pleos.playground.location.fused.listener.ProximityAlertListener)
    fun removeArea(alertId: String)
}

private class PleosLocationSdkClient(context: Context) : LocationSdkClient, LocationAreaSdkClient {
    private val sdk = FusedLocation(context)
    override fun initialize() = sdk.initialize()
    override fun isSupported() = sdk.isSupported()
    override fun register(listener: FusedLocationListener) = sdk.registerFusedLocationCallback(listener)
    override fun unregister(listener: FusedLocationListener) = sdk.unregisterFusedLocationCallback(listener)
    override fun release() = sdk.release()
    override fun addArea(request: ProximityAlertRequest, listener: ProximityAlertListener) = sdk.addProximityAlert(request, listener)
    override fun removeArea(alertId: String) = sdk.removeProximityAlert(alertId)
}
