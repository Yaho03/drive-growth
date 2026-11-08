package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.driving.RestBonus
import io.drivegrowth.core.driving.RestBonusStarted
import io.drivegrowth.core.driving.RestBonusState
import io.drivegrowth.core.driving.RestDeparture
import io.drivegrowth.core.driving.RestOutcome
import io.drivegrowth.core.driving.RestPlace
import io.drivegrowth.core.driving.RestRules
import io.drivegrowth.core.driving.RestStop
import org.json.JSONException
import org.json.JSONObject

/**
 * 휴식 버프 상태를 공용 원장과 같은 DB에 저장한다. 돈은 움직이지 않으며 버프 배율의 실제 금액 계산은 기본 주행 수익 쪽이 맡는다.
 *
 * - 상태는 출발 처리와 운전 소모 기록을 순서대로 쌓은 기록(`rest_log`)의 마지막 결과다. 시각이 아니라 남은 시간을 저장해 주차·앱 종료 중에는 줄지 않는다.
 * - 같은 정차의 출발 처리, 같은 ID의 소모는 한 번만 기록한다. 같은 명령을 다시 보내면 당시 결과를 돌려주고, 다른 명령이면 STATE_CONFLICT다.
 * - 읽을 때마다 기록 전체를 처음부터 순서대로 다시 계산해 각 기록의 결과와 대조한다. 한 기록이라도 계산·이어짐·형식이 다르면 STORAGE_FAILURE이고 원본을 바꾸지 않는다.
 *   여러 기록을 맞춰 바꾼 손상도 처음 상태부터 다시 계산하므로 드러난다. 기록 수에 비례해 읽는 비용이 늘지만 하루 정차·운전 구간 수 규모를 가정한다.
 * - 버프가 없을 때의 운전 소모도 기록한다. 같은 소모 ID의 재전송이 언제 와도 같은 결과를 돌려주게 하려는 것이다.
 * - 공용 store 연결, 테이블 생성 호출, DB 버전 이전은 #122에서 통합한다. 휴식 기준 수치는 #24 제안 단계의 시험값을 호출자가 넘긴다.
 */
class RestLedger internal constructor(private val store: SqliteGameChangeStore) {
    internal companion object {
        private const val KIND_DEPART = "DEPART"
        private const val KIND_CONSUME = "CONSUME"

        fun createTables(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE rest_log (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL CHECK (kind IN ('DEPART', 'CONSUME')),
                key TEXT NOT NULL, command TEXT NOT NULL, result TEXT NOT NULL,
                UNIQUE (kind, key))""",
            )
        }
    }

    private fun failure(error: ContractError) = ContractResult.Failure(error)

    /** 현재 버프 상태. 기록이 없으면 [RestBonusState.NONE]이다. */
    fun state(): ContractResult<RestBonusState> = store.atomic { db -> ContractResult.Success(stateIn(db)) }

    /**
     * 정차에서 출발할 때 호출한다. 인정된 휴식이면 버프를 규칙 길이로 채우고(중첩 없음), 아니면 남은 시간을 그대로 둔다.
     *
     * - 이미 처리한 정차 번호는 현재 상태보다 먼저 확인한다. 같은 명령(규칙·정차 입력)이면 당시 결과, 다르면 STATE_CONFLICT다.
     * - 마지막으로 처리한 정차보다 앞선 번호인데 기록이 없으면 core의 ALREADY_HANDLED를 돌려주고 아무것도 저장하지 않는다.
     * - 정차 출처가 이 저장소 출처와 다르면 INVALID_INPUT이다.
     */
    fun depart(rules: RestRules, stop: RestStop): ContractResult<RestDeparture> = store.atomic { db -> departIn(db, rules, stop) }

    /**
     * 운전한 시간만큼 버프를 줄인다. 휴식·주차 시간은 넘기지 않는다.
     *
     * @param consumeId 운전 구간의 고유 ID. 같은 ID·같은 입력의 재전송은 한 번만 줄인다.
     * @param afterStop 이 운전 구간이 시작될 때 마지막으로 처리한 정차 번호(없으면 null). 현재 상태와 다르면 그 사이에 새 출발이
     *   처리된 늦은 구간이라 STATE_CONFLICT다. 휴식 전 운전이 새 버프를 깎지 못하게 한다.
     */
    fun consume(rules: RestRules, consumeId: String, afterStop: Long?, elapsedMs: Long): ContractResult<RestBonusState> =
        store.atomic { db -> consumeIn(db, rules, consumeId, afterStop, elapsedMs) }

    /** 기본 주행 수익 정산과 같은 트랜잭션에서 읽을 때 쓰는 연결점. */
    internal fun stateIn(db: SQLiteDatabase): RestBonusState = chain(db).lastOrNull()?.after ?: RestBonusState.NONE

    internal fun departIn(db: SQLiteDatabase, rules: RestRules, stop: RestStop): ContractResult<RestDeparture> {
        if (stop.source != store.source) return failure(ContractError.INVALID_INPUT)
        val command = RestJson.departCommand(rules, stop).toString()
        val entries = chain(db)
        entries.firstOrNull { it.kind == KIND_DEPART && it.key == stop.number.toString() }?.let { stored ->
            if (stored.command != command) return failure(ContractError.STATE_CONFLICT)
            return ContractResult.Success(requireNotNull(stored.departure))
        }
        val before = entries.lastOrNull()?.after ?: RestBonusState.NONE
        val departure = RestBonus(rules).depart(before, stop)
        if (departure.outcome == RestOutcome.ALREADY_HANDLED) return ContractResult.Success(departure)
        insert(db, KIND_DEPART, stop.number.toString(), command, RestJson.departResult(before, departure))
        return ContractResult.Success(departure)
    }

    internal fun consumeIn(
        db: SQLiteDatabase,
        rules: RestRules,
        consumeId: String,
        afterStop: Long?,
        elapsedMs: Long,
    ): ContractResult<RestBonusState> {
        if (consumeId.isBlank() || elapsedMs < 0 || (afterStop != null && afterStop < 0)) return failure(ContractError.INVALID_INPUT)
        val command = RestJson.consumeCommand(rules, consumeId, afterStop, elapsedMs).toString()
        val entries = chain(db)
        entries.firstOrNull { it.kind == KIND_CONSUME && it.key == consumeId }?.let { stored ->
            if (stored.command != command) return failure(ContractError.STATE_CONFLICT)
            return ContractResult.Success(stored.after)
        }
        val before = entries.lastOrNull()?.after ?: RestBonusState.NONE
        if (before.lastStopNumber != afterStop) return failure(ContractError.STATE_CONFLICT)
        val after = when (val result = RestBonus(rules).consume(before, elapsedMs)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        insert(db, KIND_CONSUME, consumeId, command, RestJson.consumeResult(before, after))
        return ContractResult.Success(after)
    }

    private class Entry(val kind: String, val key: String, val command: String, val after: RestBonusState, val departure: RestDeparture?)

    private fun insert(db: SQLiteDatabase, kind: String, key: String, command: String, result: JSONObject) {
        db.insertOrThrow("rest_log", null, ContentValues().apply {
            put("kind", kind)
            put("key", key)
            put("command", command)
            put("result", result.toString())
        })
    }

    /** 기록 전체를 처음 상태부터 순서대로 다시 계산해 대조한 목록. */
    private fun chain(db: SQLiteDatabase): List<Entry> =
        db.rawQuery("SELECT seq, kind, key, command, result FROM rest_log ORDER BY seq", null).use {
            val entries = ArrayList<Entry>()
            var previous = RestBonusState.NONE
            while (it.moveToNext()) {
                it.integer(0, 1, Long.MAX_VALUE)
                val entry = verified(previous, it.text(1), it.text(2), it.text(3), it.text(4))
                entries += entry
                previous = entry.after
            }
            entries
        }

    /**
     * 기록 한 건을 원래 명령과 [previous](바로 앞 기록까지 다시 계산한 상태)로 다시 계산해 대조한다.
     * 기록에 적힌 직전 상태가 [previous]와 다르거나, 파싱에 성공해도 계산 결과·키가 다르면 손상이다.
     */
    private fun verified(previous: RestBonusState, kind: String, key: String, command: String, raw: String): Entry {
        val result = JSONObject(raw)
        val before = RestJson.state(result.getJSONObject("before"))
        if (before != previous) throw JSONException("휴식 기록이 직전 기록과 이어지지 않음")
        return when (kind) {
            KIND_DEPART -> {
                val (rules, stop) = RestJson.departInput(JSONObject(command))
                if (stop.number.toString() != key || stop.source != store.source ||
                    RestJson.departCommand(rules, stop).toString() != command
                ) {
                    throw JSONException("출발 명령 형식 오류")
                }
                val departure = RestBonus(rules).depart(before, stop)
                if (departure.outcome == RestOutcome.ALREADY_HANDLED ||
                    RestJson.departResult(before, departure).toString() != result.toString()
                ) {
                    throw JSONException("출발 결과가 원래 명령과 다름")
                }
                Entry(kind, key, command, departure.state, departure)
            }
            KIND_CONSUME -> {
                val input = RestJson.consumeInput(JSONObject(command))
                if (input.consumeId != key ||
                    RestJson.consumeCommand(input.rules, input.consumeId, input.afterStop, input.elapsedMs).toString() != command ||
                    before.lastStopNumber != input.afterStop
                ) {
                    throw JSONException("소모 명령 형식 오류")
                }
                val after = (RestBonus(input.rules).consume(before, input.elapsedMs) as? ContractResult.Success)?.value
                    ?: throw JSONException("저장된 소모 재계산 실패")
                if (RestJson.consumeResult(before, after).toString() != result.toString()) throw JSONException("소모 결과가 원래 명령과 다름")
                Entry(kind, key, command, after, null)
            }
            else -> throw JSONException("휴식 기록 종류 오류")
        }
    }
}

/** 필드 순서가 고정된 휴식 표현. 정수는 형식과 범위를 검사하고 자동 변환하지 않는다. */
internal object RestJson {
    fun rules(r: RestRules): JSONObject = JSONObject().put("rule", r.ruleVersion.value).put("minDriving", r.minDrivingMs)
        .put("minParked", r.minParkedMs).put("duration", r.bonusDurationMs).put("percent", r.bonusPercent)

    fun rules(j: JSONObject) = RestRules(
        RuleVersion(j.getString("rule")), long(j, "minDriving", 1, Long.MAX_VALUE), long(j, "minParked", 1, Long.MAX_VALUE),
        long(j, "duration", 1, Long.MAX_VALUE), long(j, "percent", 1, Int.MAX_VALUE.toLong()).toInt(),
    )

    fun state(s: RestBonusState): JSONObject =
        JSONObject().put("remaining", s.remainingMs).put("last", s.lastStopNumber ?: JSONObject.NULL)

    fun state(j: JSONObject) = RestBonusState(
        long(j, "remaining", 0, Long.MAX_VALUE),
        if (j.get("last") == JSONObject.NULL) null else long(j, "last", 0, Long.MAX_VALUE),
    )

    private fun <T> observation(o: Observation<T>, value: (T) -> Any): JSONObject = when (o) {
        is Observation.Available -> JSONObject().put("type", "AVAILABLE").put("value", value(o.value))
        is Observation.Missing -> JSONObject().put("type", "MISSING").put("reason", o.reason.name)
    }

    private fun <T> observation(j: JSONObject, value: (JSONObject) -> T): Observation<T> = when (j.getString("type")) {
        "AVAILABLE" -> Observation.Available(value(j))
        "MISSING" -> Observation.Missing(MissingReason.valueOf(j.getString("reason")))
        else -> throw JSONException("관측 형식 오류")
    }

    fun stop(s: RestStop): JSONObject = JSONObject().put("number", s.number).put("source", s.source.name)
        .put("place", observation(s.place) { it.name })
        .put("driven", observation(s.drivenMs) { it })
        .put("parked", observation(s.parkedMs) { it })

    fun stop(j: JSONObject) = RestStop(
        long(j, "number", 0, Long.MAX_VALUE),
        InputSource.valueOf(j.getString("source")),
        observation(j.getJSONObject("place")) { RestPlace.valueOf(it.getString("value")) },
        observation(j.getJSONObject("driven")) { long(it, "value", Long.MIN_VALUE, Long.MAX_VALUE) },
        observation(j.getJSONObject("parked")) { long(it, "value", Long.MIN_VALUE, Long.MAX_VALUE) },
    )

    /** 출발 처리 명령. 같은 정차의 재전송은 규칙과 정차 입력이 모두 같아야 같은 명령이다. */
    fun departCommand(rules: RestRules, stop: RestStop): JSONObject = JSONObject().put("rules", rules(rules)).put("stop", stop(stop))

    fun departInput(j: JSONObject): Pair<RestRules, RestStop> = rules(j.getJSONObject("rules")) to stop(j.getJSONObject("stop"))

    fun departResult(before: RestBonusState, d: RestDeparture): JSONObject = JSONObject().put("before", state(before))
        .put("after", state(d.state)).put("outcome", d.outcome.name).put("started", d.started?.let(::started) ?: JSONObject.NULL)

    private fun started(s: RestBonusStarted): JSONObject = JSONObject().put("stop", s.stopNumber).put("source", s.source.name)
        .put("rule", s.ruleVersion.value).put("duration", s.durationMs).put("percent", s.bonusPercent).put("refreshed", s.refreshed)

    class ConsumeInput(val rules: RestRules, val consumeId: String, val afterStop: Long?, val elapsedMs: Long)

    /** 운전 소모 명령. 같은 ID의 재전송은 규칙·이전 정차·소모 시간이 모두 같아야 같은 명령이다. */
    fun consumeCommand(rules: RestRules, consumeId: String, afterStop: Long?, elapsedMs: Long): JSONObject =
        JSONObject().put("id", consumeId).put("rules", rules(rules)).put("after", afterStop ?: JSONObject.NULL).put("elapsed", elapsedMs)

    fun consumeInput(j: JSONObject) = ConsumeInput(
        rules(j.getJSONObject("rules")), j.getString("id"),
        if (j.get("after") == JSONObject.NULL) null else long(j, "after", 0, Long.MAX_VALUE),
        long(j, "elapsed", 0, Long.MAX_VALUE),
    )

    fun consumeResult(before: RestBonusState, after: RestBonusState): JSONObject =
        JSONObject().put("before", state(before)).put("after", state(after))

    private fun long(j: JSONObject, key: String, min: Long, max: Long): Long {
        val value = when (val raw = j.get(key)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> throw JSONException("$key 정수 형식 오류")
        }
        if (value !in min..max) throw JSONException("$key 정수 범위 오류")
        return value
    }
}
