package io.drivegrowth.app.ui.delivery

import io.drivegrowth.app.host.delivery.DeliveryHostRead
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleId

/**
 * 진행 중인 탁송의 거리 진행도. 가게·차고 등 다른 화면 아래의 한 줄 막대에 쓴다.
 *
 * 마지막으로 읽은 탁송 화면과 같은 화면의 확인된 운전 거리만 사용한다. 진행도를 새로 계산하거나
 * 도착·보상을 만들지 않으며, 확인된 거리가 없으면 막대를 채우지 않는다.
 */
internal data class DriveProgress(
    val destinationName: String?,
    val vehicleId: VehicleId?,
    val estimatedMeters: Long,
    val observedMeters: Long?,
    val source: InputSource,
    val awaitingResult: Boolean,
) {
    /** 0..1. 확인된 거리나 예상거리가 없으면 null이다. */
    val fraction: Float?
        get() = observedMeters?.takeIf { estimatedMeters > 0L }
            ?.let { (it.coerceIn(0L, estimatedMeters).toDouble() / estimatedMeters).toFloat() }

    companion object {
        fun from(frame: DeliveryFrame, read: DeliveryHostRead?): DriveProgress? {
            val (delivery, awaiting) = when (val screen = frame.screen) {
                is DeliveryScreen.Active -> screen.delivery to false
                is DeliveryScreen.AwaitingResult -> screen.delivery to true
                else -> return null
            }
            val estimated = delivery.active.proposed.estimated
            // 탁송 화면(DeliveryPresentation)과 같은 기준: 같은 화면의 읽기이고 입력 출처가 같은 거리만 쓴다.
            val observed = (read?.takeIf { it.screen == frame.screen }?.navigation?.observed as? Observation.Available)
                ?.value?.takeIf { it.source == estimated.source }
            return DriveProgress(delivery.destinationName, delivery.active.offer.vehicleId, estimated.meters,
                observed?.meters, estimated.source, awaiting)
        }

        /** 1000m → "1.0". 소수 첫째 자리까지. */
        fun km(meters: Long): String {
            val tenths = (meters.coerceAtLeast(0L) + 50L) / 100L
            return "${tenths / 10}.${tenths % 10}"
        }
    }
}
