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
        { identity, raw ->
            // 명시 SDK 모의 안내만 모의로 분류한다. VEHICLE 프로필이나 ROUTE_GUIDING만으로 실차 증거를 만들지 않는다.
            if (identity.source == InputSource.SIMULATION &&
                (raw.guidance as? NavigationField.Value)?.value?.mode == "SIMULATION_GUIDING")
                Observation.Available(InputSource.SIMULATION) else Observation.Missing(MissingReason.NO_DATA)
        },
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
