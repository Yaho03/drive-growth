package io.drivegrowth.app.host.delivery

import android.content.Context
import android.os.SystemClock
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.HostDeliveryNavigation
import io.drivegrowth.app.vehicle.navigation.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import java.util.UUID
import java.util.concurrent.Executor

/** F의 원 snapshot을 그대로 보관한다. gate/stop 세대 무효화 후 disconnect하며 경로를 취소하지 않는다. */
class SessionDeliveryNavigation(context: Context, private val main: Executor) : HostDeliveryNavigation {
    private val fresh: (NavigationStamp) -> Boolean = { stamp ->
        deliveryQuoteFresh(stamp, SystemClock.elapsedRealtime())
    }

    // 앱의 명시 신선도 제한이다. SDK의 유효 시간이나 실제 주행 증명이 아니다.
    private val confirmation = NavigationDestinationConfirmation(fresh, { DestinationKey("navi-confirmed-" + UUID.randomUUID()) })
    private val source = PleosNavigationSource(context, main, 10_000L)
    private var enabled = false
    private var epoch = 0L
    override var changed: (() -> Unit)? = null
    override val snapshot get() = confirmation.snapshot
    override val port: DeliveryNavigationPort = FNavigationDeliveryPort(
        { identity -> confirmation.bind(identity); confirmation.snapshot },
        { identity, _ -> confirmation.selected(identity) },
        { identity, raw -> deliveryEstimateSource(identity, raw, fresh) },
        { Observation.Missing(MissingReason.NO_DATA) },
        fresh,
        { identity, _ ->
            main.execute { if (enabled) { confirmation.begin(identity); refresh() } }
            ContractResult.Success(Unit)
        })
    override fun setControlsEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        ++epoch
        confirmation.setEnabled(value)
        if (!value) { source.disconnect(); return }
        connectForQuote()
    }

    private fun connectForQuote() {
        val token = ++epoch
        var queried = false
        val receive: (NavigationEvent) -> Unit = { event ->
            if (enabled && token == epoch) {
                if (event is NavigationEvent.State) {
                    confirmation.accept(event.snapshot)
                    if (!queried && event.snapshot.connection == NavigationConnection.CONNECTED_OBSERVED) {
                        queried = true
                        source.refresh()
                    }
                }
                changed?.invoke()
            }
        }
        source.connect(receive)
    }

    override fun refresh() {
        if (!enabled) return
        confirmation.invalidateQuote()
        connectForQuote()
        changed?.invoke()
    }
    override fun confirm(displayedStamp: NavigationStamp?): ContractResult<Unit> = when (val result = confirmation.confirm(displayedStamp) { identity, raw -> deliveryEstimateSource(identity, raw, fresh) }) {
        is ContractResult.Failure -> result
        is ContractResult.Success -> ContractResult.Success(Unit)
    }
    override fun close() { enabled = false; epoch++; confirmation.setEnabled(false); changed = null; source.close() }
}

internal fun deliveryQuoteFresh(stamp: NavigationStamp, nowElapsedMillis: Long): Boolean =
    nowElapsedMillis - stamp.receivedElapsedMillis in 0..5_000L

/** 신선한 SDK 안내 모드로 예정거리 입력 경로를 분류한다. 실차 시험·도착 인증이 아니다. */
internal fun deliveryEstimateSource(identity: GameIdentity, raw: NavigationSnapshot,
    fresh: (NavigationStamp) -> Boolean): Observation<InputSource> {
    val guidance = raw.guidance as? NavigationField.Value ?: return Observation.Missing(MissingReason.NO_DATA)
    if (guidance.stamp.source != NavigationSource.PLEOS_NAVI_SDK ||
        guidance.stamp.connectionGeneration != raw.stamp.connectionGeneration ||
        raw.connection != NavigationConnection.CONNECTED_OBSERVED) return Observation.Missing(MissingReason.NO_DATA)
    if (!fresh(guidance.stamp)) return Observation.Missing(MissingReason.STALE)
    val source = when (guidance.value.mode) {
        "ROUTE_GUIDING" -> InputSource.VEHICLE
        "SIMULATION_GUIDING" -> InputSource.SIMULATION
        else -> return Observation.Missing(MissingReason.NO_DATA)
    }
    return if (source == identity.source) Observation.Available(source) else Observation.Missing(MissingReason.NO_DATA)
}
