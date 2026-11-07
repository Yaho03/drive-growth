package io.drivegrowth.core.driving

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import org.junit.Assert.*
import org.junit.Test

class RestBonusTest {
    private val rules = RestFixtures.trialRules
    private val rest = RestBonus(rules)
    private val hour = 60 * 60 * 1000L
    private val minute = 60 * 1000L

    private fun stop(
        number: Long = 1,
        place: Observation<RestPlace> = Observation.Available(RestPlace.SERVICE_AREA),
        driven: Observation<Long> = Observation.Available(hour),
        parked: Observation<Long> = Observation.Available(5 * minute),
    ) = RestStop(number, InputSource.SIMULATION, place, driven, parked)

    private fun <T> success(result: ContractResult<T>): T = (result as ContractResult.Success).value

    @Test fun `휴게소와 졸음쉼터에서 조건을 채우면 인정한다`() {
        assertEquals(RestOutcome.ACCEPTED, rest.classify(stop(place = Observation.Available(RestPlace.SERVICE_AREA))))
        assertEquals(RestOutcome.ACCEPTED, rest.classify(stop(place = Observation.Available(RestPlace.DROWSY_SHELTER))))
    }

    @Test fun `일반 주차는 조건을 채워도 인정하지 않는다`() {
        assertEquals(RestOutcome.NOT_REST_PLACE, rest.classify(stop(place = Observation.Available(RestPlace.OTHER))))
    }

    @Test fun `운전 시간과 주차 시간 경계를 구분한다`() {
        assertEquals(RestOutcome.DRIVING_TOO_SHORT, rest.classify(stop(driven = Observation.Available(hour - 1))))
        assertEquals(RestOutcome.ACCEPTED, rest.classify(stop(driven = Observation.Available(hour))))
        assertEquals(RestOutcome.PARKING_TOO_SHORT, rest.classify(stop(parked = Observation.Available(5 * minute - 1))))
        assertEquals(RestOutcome.ACCEPTED, rest.classify(stop(parked = Observation.Available(5 * minute))))
    }

    @Test fun `장소나 시간을 확인할 수 없으면 혜택을 확정하지 않는다`() {
        val missing = Observation.Missing(MissingReason.NO_DATA)
        val denied = Observation.Missing(MissingReason.PERMISSION_DENIED)
        listOf(
            stop(place = missing),
            stop(place = denied),
            stop(driven = missing),
            stop(parked = missing),
            stop(driven = Observation.Available(-1)),
            stop(parked = Observation.Available(-1)),
        ).forEach {
            val departed = rest.depart(RestBonusState.NONE, it)
            assertEquals(RestOutcome.UNCONFIRMED, departed.outcome)
            assertNull(departed.started)
            assertFalse(departed.state.isActive)
        }
    }

    @Test fun `인정된 휴식에서 출발하면 버프가 시작된다`() {
        val departed = rest.depart(RestBonusState.NONE, stop())
        assertEquals(RestOutcome.ACCEPTED, departed.outcome)
        assertEquals(20 * minute, departed.state.remainingMs)
        val started = requireNotNull(departed.started)
        assertEquals(1L, started.stopNumber)
        assertEquals(InputSource.SIMULATION, started.source)
        assertEquals(rules.ruleVersion, started.ruleVersion)
        assertEquals(20 * minute, started.durationMs)
        assertEquals(150, started.bonusPercent)
        assertFalse(started.refreshed)
    }

    @Test fun `저장 상태를 다시 읽어 같은 정차를 반복해도 이벤트와 상태가 그대로다`() {
        val first = rest.depart(RestBonusState.NONE, stop())
        val restored = first.state.copy()
        val used = success(rest.consume(restored, 3 * minute))
        val again = rest.depart(used, stop())
        assertEquals(RestOutcome.ALREADY_HANDLED, again.outcome)
        assertNull(again.started)
        assertEquals(used, again.state)
    }

    @Test fun `이전 순번의 정차가 늦게 와도 처리하지 않는다`() {
        val state = rest.depart(RestBonusState.NONE, stop(number = 5)).state
        val late = rest.depart(state, stop(number = 4))
        assertEquals(RestOutcome.ALREADY_HANDLED, late.outcome)
        assertEquals(state, late.state)
    }

    @Test fun `출발 전에는 남은 시간이 줄지 않아 쉬는 시간이 아무리 길어도 그대로다`() {
        val before = success(rest.consume(rest.depart(RestBonusState.NONE, stop()).state, 15 * minute))
        val parkedLong = stop(number = 2, place = Observation.Available(RestPlace.OTHER), parked = Observation.Available(1_000 * hour))
        assertEquals(5 * minute, rest.depart(before, parkedLong).state.remainingMs)
    }

    @Test fun `다시 휴식하면 중첩 없이 전체 길이로 갱신한다`() {
        val running = success(rest.consume(rest.depart(RestBonusState.NONE, stop()).state, 15 * minute))
        val departed = rest.depart(running, stop(number = 2))
        assertEquals(20 * minute, departed.state.remainingMs)
        assertTrue(requireNotNull(departed.started).refreshed)
    }

    @Test fun `인정되지 않은 정차는 남은 시간을 바꾸지 않고 순번만 기록한다`() {
        val running = rest.depart(RestBonusState.NONE, stop()).state
        val shortStop = stop(number = 2, parked = Observation.Available(minute))
        val departed = rest.depart(running, shortStop)
        assertEquals(RestOutcome.PARKING_TOO_SHORT, departed.outcome)
        assertNull(departed.started)
        assertEquals(20 * minute, departed.state.remainingMs)
        assertEquals(2L, departed.state.lastStopNumber)
    }

    @Test fun `소모 경계에서 0 아래로 내려가지 않고 끝나면 비활성이다`() {
        val state = rest.depart(RestBonusState.NONE, stop()).state
        assertTrue(success(rest.consume(state, 20 * minute - 1)).isActive)
        assertFalse(success(rest.consume(state, 20 * minute)).isActive)
        assertEquals(0L, success(rest.consume(state, Long.MAX_VALUE)).remainingMs)
    }

    @Test fun `음수 소모는 거절하고 0 소모는 그대로다`() {
        val state = rest.depart(RestBonusState.NONE, stop()).state
        listOf(-1L, Long.MIN_VALUE).forEach {
            assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT), rest.consume(state, it))
        }
        assertEquals(state, success(rest.consume(state, 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `0 이하 규칙 값은 거절한다`() {
        rules.copy(bonusDurationMs = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `음수 남은 시간은 거절한다`() {
        RestBonusState(remainingMs = -1, lastStopNumber = null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `음수 정차 순번은 거절한다`() {
        stop(number = -1)
    }
}
