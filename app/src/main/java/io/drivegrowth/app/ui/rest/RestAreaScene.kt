package io.drivegrowth.app.ui.rest

import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.driving.RestOutcome
import io.drivegrowth.core.driving.RestPlace

/** 같은 전경 정차의 확인된 표시 입력. 원장 쓰기와 판정은 호스트가 맡는다. */
internal sealed interface RestAreaScene {
    val source: InputSource

    data class Unconfirmed(override val source: InputSource) : RestAreaScene

    data class Parked(
        override val source: InputSource,
        val stopId: String,
        val place: RestPlace,
        val vehicleId: VehicleId?,
        val progress: ObservedParking? = null,
        val outcome: RestOutcome? = null,
    ) : RestAreaScene {
        init {
            require(stopId.isNotBlank())
            require(place == RestPlace.SERVICE_AREA || place == RestPlace.DROWSY_SHELTER)
        }

        val checkpointHighlighted get() = true
        val phase get() = if (progress?.elapsedMs == 0L && outcome == null) Phase.ARRIVED else Phase.RESTING
        val ready get() = outcome == RestOutcome.ACCEPTED
    }

    /** 연속성이 확인된 주차 표본과 그 정차에 적용된 규칙의 요구 시간이다. */
    data class ObservedParking(val elapsedMs: Long, val requiredMs: Long) {
        init { require(elapsedMs >= 0 && requiredMs > 0) }
        val fraction get() = (minOf(elapsedMs, requiredMs).toDouble() / requiredMs).toFloat()
    }

    enum class Phase { ARRIVED, RESTING }
}
