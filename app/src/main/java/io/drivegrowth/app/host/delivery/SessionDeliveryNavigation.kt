package io.drivegrowth.app.host.delivery

import android.content.Context
import android.os.SystemClock
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.vehicle.navigation.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import java.util.UUID
import java.util.concurrent.Executor

/** F의 원 snapshot을 그대로 보관한다. gate/stop 세대 무효화 후 disconnect하며 경로를 취소하지 않는다. */
class SessionDeliveryNavigation(context: Context, private val main: Executor) : AutoCloseable {
    // 앱의 명시 신선도 제한이다. SDK의 유효 시간이나 실제 주행 증명이 아니다.
    private val confirmation = NavigationDestinationConfirmation({ stamp ->
        val age = SystemClock.elapsedRealtime() - stamp.receivedElapsedMillis
        age in 0..5_000L
    }, { DestinationKey("navi-confirmed-" + UUID.randomUUID()) })
    private val source = PleosNavigationSource(context, main, 10_000L)
    private var enabled = false
    private var epoch = 0L
    var changed: (() -> Unit)? = null
    val snapshot get() = confirmation.snapshot
    val port: DeliveryNavigationPort = FNavigationDeliveryPort(
        { identity -> confirmation.bind(identity); confirmation.snapshot },
        { identity, _ -> confirmation.selected(identity) },
        { identity, raw -> deliveryEstimateSource(identity, raw) { stamp ->
            val age = SystemClock.elapsedRealtime() - stamp.receivedElapsedMillis
            age in 0..5_000L
        } },
        { Observation.Missing(MissingReason.NO_DATA) },
        { stamp -> val age = SystemClock.elapsedRealtime() - stamp.receivedElapsedMillis; age in 0..5_000L },
        { identity, _ ->
            main.execute { if (enabled) { confirmation.begin(identity); source.refresh(); changed?.invoke() } }
            ContractResult.Success(Unit)
        })
    fun setControlsEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        val token = ++epoch
        confirmation.setEnabled(value)
        if (!value) { source.disconnect(); return }
        source.connect { event ->
            if (enabled && token == epoch) {
                if (event is NavigationEvent.State) confirmation.accept(event.snapshot)
                changed?.invoke()
            }
        }
    }
    fun refresh() { if (enabled) source.refresh() }
    fun confirm(): ContractResult<DeliveryDestinationSelection> = confirmation.confirm()
    override fun close() { enabled = false; epoch++; confirmation.setEnabled(false); changed = null; source.close() }
}

/** 명시 모의 안내의 신선한 SDK 근거만 분류한다. VEHICLE 프로필은 실제 주행 분류 근거가 아니다. */
internal fun deliveryEstimateSource(identity: GameIdentity, raw: NavigationSnapshot,
    fresh: (NavigationStamp) -> Boolean): Observation<InputSource> {
    val guidance = raw.guidance as? NavigationField.Value ?: return Observation.Missing(MissingReason.NO_DATA)
    if (guidance.stamp.source != NavigationSource.PLEOS_NAVI_SDK ||
        guidance.stamp.connectionGeneration != raw.stamp.connectionGeneration ||
        raw.connection != NavigationConnection.CONNECTED_OBSERVED) return Observation.Missing(MissingReason.NO_DATA)
    if (!fresh(guidance.stamp)) return Observation.Missing(MissingReason.STALE)
    return if (identity.source == InputSource.SIMULATION && guidance.value.mode == "SIMULATION_GUIDING")
        Observation.Available(InputSource.SIMULATION) else Observation.Missing(MissingReason.NO_DATA)
}
