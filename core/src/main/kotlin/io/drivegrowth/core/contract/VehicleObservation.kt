package io.drivegrowth.core.contract

import io.drivegrowth.core.Gear
import kotlin.math.abs

/** 차량 속도는 m/s이며 receivedElapsedMs는 같은 부팅 내 수신 시각이다. */
data class VehicleObservation(
    val source: InputSource,
    val receivedElapsedMs: Long,
    val receivedAt: EpochMillis,
    val speedMps: Observation<Double>,
    val gear: Observation<Gear>,
    val uxRestricted: Observation<Boolean>,
) {
    init {
        require(receivedElapsedMs >= 0)
    }

    /** 주차 후보만 확인한다. 신호 유효 시간과 연속 주차는 호출자가 확인한다. */
    fun isParkCandidate(maxStationarySpeedMps: Double): Boolean {
        require(maxStationarySpeedMps.isFinite() && maxStationarySpeedMps >= 0)
        val speed = (speedMps as? Observation.Available)?.value ?: return false
        return source == InputSource.VEHICLE &&
            gear == Observation.Available(Gear.PARK) &&
            uxRestricted == Observation.Available(false) &&
            speed.isFinite() && abs(speed) <= maxStationarySpeedMps
    }
}

/** 화면은 Allowed일 때만 조작을 연다. 확인 불가 원인은 Observation.Missing으로 전달한다. */
enum class VehicleInteraction { ALLOWED, RESTRICTED }

/** 선택·재탐색으로 생긴 경로 세대를 식별하는 앱 내부 ID다. */
@JvmInline
value class RouteId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/** 내비게이션의 도착 이벤트를 활성 경로에 연결할 수 있을 때 생성한다. */
data class DeliveryArrivalEvidence(
    val routeId: RouteId,
    val source: InputSource,
    val receivedAt: EpochMillis,
) {
    fun matches(activeRoute: RouteId, expectedSource: InputSource): Boolean =
        routeId == activeRoute && source == expectedSource
}
