package io.drivegrowth.app.ui.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.delivery.DeliveryHostRead
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.VehicleId

/**
 * 진행 중인 탁송의 거리 진행도. 가게·차고 등 다른 화면 아래의 한 줄 막대에 쓴다.
 *
 * 마지막으로 읽은 탁송 화면과 같은 화면의 확인된 운전 거리만 사용한다. 진행도를 새로 계산하거나
 * 도착·보상을 만들지 않으며, 확인된 거리가 없으면 막대를 채우지 않는다.
 */
internal data class DriveProgress(
    val key: Key,
    val readVersion: Long,
    val destinationName: String?,
    val vehicleId: VehicleId?,
    val estimatedMeters: Long,
    val observedMeters: Long?,
    val awaitingMessage: String?,
) {
    data class Key(val identity: GameIdentity, val requestId: RequestId)
    val source: InputSource get() = key.identity.source

    /** 0..1. 확인된 거리나 예상거리가 없으면 null이다. */
    val fraction: Float?
        get() = observedMeters?.takeIf { estimatedMeters > 0L }
            ?.let { (it.coerceIn(0L, estimatedMeters).toDouble() / estimatedMeters).toFloat() }

    companion object {
        fun from(frame: DeliveryFrame, read: DeliveryHostRead?, identity: GameIdentity): DriveProgress? {
            val current = read?.takeIf { it.screen == frame.screen && it.identity == identity && it.pending == null }
                ?: return null
            val screen = frame.screen
            val delivery = when (screen) {
                is DeliveryScreen.Active -> screen.delivery
                is DeliveryScreen.AwaitingResult -> screen.delivery
                else -> return null
            }
            val estimated = delivery.active.proposed.estimated
            if (estimated.source != identity.source) return null
            val message = (screen as? DeliveryScreen.AwaitingResult)?.message
            val observed = if (message == null) (current.navigation?.observed as? Observation.Available)?.value
                ?.takeIf { it.source == identity.source } else null
            return DriveProgress(Key(identity, delivery.active.requestId), frame.version, delivery.destinationName, delivery.active.offer.vehicleId,
                estimated.meters, observed?.meters, message)
        }

        /** 1000m → "1.0". 소수 첫째 자리까지. */
        fun km(meters: Long): String {
            val nonnegative = meters.coerceAtLeast(0L)
            val tenths = nonnegative / 100L + if (nonnegative % 100L >= 50L) 1L else 0L
            return "${tenths / 10}.${tenths % 10}"
        }
    }
}
