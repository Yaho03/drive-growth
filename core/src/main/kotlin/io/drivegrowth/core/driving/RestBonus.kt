package io.drivegrowth.core.driving

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.RuleVersion

/** 정차 장소. 판정할 수 없으면 Observation.Missing으로 전달한다. */
enum class RestPlace { SERVICE_AREA, DROWSY_SHELTER, OTHER }

/**
 * 휴식 버프 규칙. 확정 전 수치를 제품 기본값으로 굳히지 않도록 기본값을 두지 않는다.
 *
 * @param bonusPercent 기본 주행 수익 배율(150 = 1.5배). 실제 금액 계산은 호출자가 맡는다.
 */
data class RestRules(
    val ruleVersion: RuleVersion,
    val minDrivingMs: Long,
    val minParkedMs: Long,
    val bonusDurationMs: Long,
    val bonusPercent: Int,
) {
    init {
        require(minDrivingMs > 0 && minParkedMs > 0 && bonusDurationMs > 0) { "시간은 양수" }
        require(bonusPercent > 0) { "배율은 양수" }
    }
}

/**
 * 출발할 때 확인한 직전 정차. 시간을 얻지 못했거나 음수면 확인할 수 없는 정차로 분류한다.
 *
 * @param number 정차 순번. 커질수록 나중이며 재실행에서도 같은 정차는 같은 값이다.
 * @param drivenMs 정차 직전의 운전 시간. 짧은 정차를 어떻게 합산할지는 호출자가 맡는다.
 */
data class RestStop(
    val number: Long,
    val source: InputSource,
    val place: Observation<RestPlace>,
    val drivenMs: Observation<Long>,
    val parkedMs: Observation<Long>,
) {
    init { require(number >= 0) { "정차 순번은 0 이상" } }
}

enum class RestOutcome {
    ACCEPTED,
    NOT_REST_PLACE,
    DRIVING_TOO_SHORT,
    PARKING_TOO_SHORT,

    /** 장소나 시간을 확인할 수 없다. 혜택을 확정하지 않는다. */
    UNCONFIRMED,

    /** 이미 처리한 정차다. 상태를 바꾸지 않고 이벤트도 다시 내지 않는다. */
    ALREADY_HANDLED,
}

/** 저장하는 버프 상태. 시각이 아니라 남은 시간을 보관해 멈춘 동안 줄어들지 않는다. */
data class RestBonusState(
    val remainingMs: Long,
    val lastStopNumber: Long?,
) {
    init { require(remainingMs >= 0) { "남은 시간은 0 이상" } }

    val isActive: Boolean get() = remainingMs > 0

    companion object {
        val NONE = RestBonusState(remainingMs = 0, lastStopNumber = null)
    }
}

/**
 * 버프 시작 이벤트. 같은 정차로는 한 번만 나온다.
 *
 * @param refreshed 남아 있던 버프를 다시 채웠으면 true다.
 */
data class RestBonusStarted(
    val stopNumber: Long,
    val source: InputSource,
    val ruleVersion: RuleVersion,
    val durationMs: Long,
    val bonusPercent: Int,
    val refreshed: Boolean,
)

data class RestDeparture(
    val state: RestBonusState,
    val outcome: RestOutcome,
    val started: RestBonusStarted?,
)

/**
 * 휴식 인정과 버프 남은 시간을 계산한다. 음성, 위치 판정, 화면, 저장은 담당하지 않는다.
 * 만료 알림을 내지 않아 버프가 끝나기 전 운전을 재촉하지 않는다.
 */
class RestBonus(private val rules: RestRules) {
    fun classify(stop: RestStop): RestOutcome {
        val place = (stop.place as? Observation.Available)?.value ?: return RestOutcome.UNCONFIRMED
        if (place == RestPlace.OTHER) return RestOutcome.NOT_REST_PLACE
        val driven = stop.drivenMs.knownDuration() ?: return RestOutcome.UNCONFIRMED
        val parked = stop.parkedMs.knownDuration() ?: return RestOutcome.UNCONFIRMED
        return when {
            driven < rules.minDrivingMs -> RestOutcome.DRIVING_TOO_SHORT
            parked < rules.minParkedMs -> RestOutcome.PARKING_TOO_SHORT
            else -> RestOutcome.ACCEPTED
        }
    }

    /**
     * 정차에서 출발할 때 호출한다. 인정된 휴식이면 남은 시간을 규칙 길이로 채운다(중첩 없음).
     * 인정되지 않은 정차는 남은 시간을 바꾸지 않는다. 호출자는 결과 상태와 이벤트를 한 번에 저장한다.
     */
    fun depart(state: RestBonusState, stop: RestStop): RestDeparture {
        val last = state.lastStopNumber
        if (last != null && stop.number <= last) return RestDeparture(state, RestOutcome.ALREADY_HANDLED, null)
        val outcome = classify(stop)
        if (outcome != RestOutcome.ACCEPTED) {
            return RestDeparture(state.copy(lastStopNumber = stop.number), outcome, null)
        }
        return RestDeparture(
            RestBonusState(rules.bonusDurationMs, stop.number),
            outcome,
            RestBonusStarted(
                stop.number, stop.source, rules.ruleVersion, rules.bonusDurationMs, rules.bonusPercent, state.isActive,
            ),
        )
    }

    /** 버프를 소모한 시간만큼 줄인다. 어떤 시간을 넘길지는 호출자가 정하며, 휴식 시간은 넘기지 않는다. */
    fun consume(state: RestBonusState, elapsedMs: Long): ContractResult<RestBonusState> {
        if (elapsedMs < 0) return ContractResult.Failure(ContractError.INVALID_INPUT)
        return ContractResult.Success(state.copy(remainingMs = (state.remainingMs - elapsedMs).coerceAtLeast(0)))
    }

    private fun Observation<Long>.knownDuration(): Long? =
        (this as? Observation.Available)?.value?.takeIf { it >= 0 }
}
