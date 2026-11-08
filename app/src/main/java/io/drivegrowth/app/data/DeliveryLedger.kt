package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.drivegrowth.core.contract.ContentVersion
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.DeliveryArrivalEvidence
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.EstimatedRouteDistance
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.RouteId
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.VehicleWrite
import io.drivegrowth.core.delivery.ActiveDelivery
import io.drivegrowth.core.delivery.DeliveryInProgress
import io.drivegrowth.core.delivery.DeliveryList
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.delivery.DeliveryOfferId
import io.drivegrowth.core.delivery.DeliveryOutcome
import io.drivegrowth.core.delivery.DeliveryProposed
import io.drivegrowth.core.delivery.DeliverySelected
import io.drivegrowth.core.delivery.DeliverySelection
import io.drivegrowth.core.delivery.DeliverySettlement
import io.drivegrowth.core.delivery.DeliverySettlementRules
import io.drivegrowth.core.delivery.DestinationKey
import io.drivegrowth.core.delivery.HeldVehicle
import io.drivegrowth.core.delivery.HoldOutcome
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 탁송 선택의 확정 결과.
 *
 * @param settlement 구매 결제의 공용 정산. 수익 탁송은 돈이 오가지 않아 null이다.
 */
data class DeliveryCommit(
    val selected: DeliverySelected,
    val settlement: Settlement?,
)

/**
 * 도착 정산의 확정 결과.
 *
 * @param settlement 지급·환급의 공용 정산. 확인 대기·임시 보관·수익 만료는 돈과 차량이 바뀌지 않아 null이다.
 */
data class ArrivalCommit(
    val outcome: DeliveryOutcome,
    val settlement: Settlement?,
)

/** 임시 보관 처리의 확정 결과. 계속 보관이면 settlement가 null이다. */
data class HoldCommit(
    val outcome: HoldOutcome,
    val settlement: Settlement?,
)

/**
 * 탁송 하루 목록, 활성 의뢰, 선택 요청 이력, 경로 세대, 도착 정산 결과, 임시 보관 차량을 공용 원장과 같은 DB 트랜잭션으로 저장한다.
 *
 * 선택 요청 이력은 의뢰가 끝난 뒤에도 남는다. 같은 요청을 다시 보내면 당시 결과를 돌려주고 끝난 의뢰를 다시 활성화하지 않는다.
 * 공용 store 연결, 테이블 생성 호출, DB 버전 이전은 #122에서 통합한다.
 * 수치와 규칙은 #24 제안 단계의 시험값을 그대로 쓴다.
 */
class DeliveryLedger internal constructor(private val store: SqliteGameChangeStore) {
    internal companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE delivery_lists (
                created_on TEXT NOT NULL, band INTEGER NOT NULL, source TEXT NOT NULL,
                content_version TEXT NOT NULL, rule_version TEXT NOT NULL, payload TEXT NOT NULL,
                PRIMARY KEY (created_on, band, source, content_version, rule_version))""",
            )
            db.execSQL(
                """CREATE TABLE delivery_requests (
                request_id TEXT PRIMARY KEY NOT NULL, command TEXT NOT NULL, active TEXT NOT NULL,
                paid INTEGER NOT NULL CHECK (paid IN (0, 1)))""",
            )
            db.execSQL(
                "CREATE TABLE delivery_active (id INTEGER PRIMARY KEY CHECK (id = 1), request_id TEXT NOT NULL UNIQUE)",
            )
            db.execSQL(
                "CREATE TABLE delivery_routes (request_id TEXT PRIMARY KEY NOT NULL, route TEXT NOT NULL, started_at INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE delivery_outcomes (request_id TEXT PRIMARY KEY NOT NULL, command TEXT NOT NULL, result TEXT NOT NULL)",
            )
            db.execSQL("CREATE TABLE delivery_held (request_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
            db.execSQL(
                "CREATE TABLE delivery_hold_outcomes (request_id TEXT PRIMARY KEY NOT NULL, command TEXT NOT NULL, result TEXT NOT NULL)",
            )
        }
    }

    private fun failure(error: ContractError) = ContractResult.Failure(error)

    /** 이 저장소 출처의 해당 날짜 목록. 콘텐츠·규칙 버전이 다른 목록도 함께 돌려주며, 맞는 목록은 core의 propose가 고른다. */
    fun lists(day: LocalDate): ContractResult<List<DeliveryList>> = store.atomic { db ->
        ContractResult.Success(readLists(db, day))
    }

    /**
     * 새로 만든 목록을 저장한다. 같은 날짜·구간·출처·콘텐츠 버전·규칙 버전의 목록이 이미 있으면 바꾸지 않고 저장된 목록을 돌려준다.
     * 호출자는 돌려받은 목록을 써야 한다. 동시에 두 번 만들어도 다시 뽑힌 목록으로 바뀌지 않는다.
     */
    fun saveList(list: DeliveryList): ContractResult<DeliveryList> = store.atomic { db ->
        if (list.source != store.source) return@atomic failure(ContractError.INVALID_INPUT)
        readList(db, list)?.let { return@atomic ContractResult.Success(it) }
        db.insertOrThrow("delivery_lists", null, listValues(list))
        ContractResult.Success(readList(db, list) ?: throw JSONException("목록 저장 누락"))
    }

    /**
     * 완료한 자리를 바꾼 목록으로 교체한다. #39의 도착 정산과 같은 트랜잭션에서 호출하는 연결점이다.
     * 저장된 목록이 expected와 다르면 STATE_CONFLICT이고 바꾸지 않는다.
     */
    internal fun replaceList(db: SQLiteDatabase, expected: DeliveryList, updated: DeliveryList): ContractResult<DeliveryList> {
        if (expected.source != store.source || updated.source != expected.source ||
            updated.createdOn != expected.createdOn || updated.band != expected.band ||
            updated.contentVersion != expected.contentVersion || updated.ruleVersion != expected.ruleVersion
        ) {
            return failure(ContractError.INVALID_INPUT)
        }
        val stored = readList(db, expected) ?: return failure(ContractError.DATA_UNAVAILABLE)
        if (!sameList(stored, expected)) return failure(ContractError.STATE_CONFLICT)
        db.update("delivery_lists", listValues(updated), KEY, key(expected))
        return ContractResult.Success(updated)
    }

    /** 현재 활성 의뢰. 없으면 null이다. */
    fun active(): ContractResult<ActiveDelivery?> = store.atomic { db -> ContractResult.Success(readActive(db)) }

    /**
     * 의뢰를 선택한다. 기존 요청 확인 → 저장된 목록·현재 활성 의뢰·잔액·후보 검사 → 확정 순서로 처리한다.
     *
     * - 같은 요청 ID·같은 내용은 당시 결과를 돌려주고, 끝난 의뢰를 다시 활성화하지 않는다.
     * - 같은 요청 ID·다른 내용, 또는 공용 정산에 이미 있는 요청 ID는 STATE_CONFLICT이고 바꾸지 않는다.
     * - 제시 목록이 저장되지 않았으면 DATA_UNAVAILABLE, 저장된 목록과 다르면 STATE_CONFLICT다. 다시 뽑은 목록으로 고르지 못하게 한다.
     * - 구매는 결제 정산, 선택 이력, 활성 의뢰를 한 트랜잭션으로 확정한다.
     * - 입력은 처음에 한 번만 읽어 고정한다. 명령 기록·저장 목록 대조·선택·결제·활성 기록이 모두 같은 값을 쓴다.
     * - 재생·활성 조회 때 저장된 활성 의뢰와 결제 정산이 원래 명령과 맞지 않거나 구매 정산이 없으면 STORAGE_FAILURE이고 원본을 바꾸지 않는다.
     */
    fun select(proposed: DeliveryProposed, offerId: DeliveryOfferId, requestId: RequestId): ContractResult<DeliveryCommit> =
        store.atomic { db ->
            val request = DeliveryJson.proposed(DeliveryJson.proposed(proposed))
            if (request.estimated.source != store.source || request.list.source != store.source) {
                return@atomic failure(ContractError.INVALID_INPUT)
            }
            val command = DeliveryJson.command(requestId, offerId, request).toString()
            readRequest(db, requestId)?.let { previous ->
                if (previous.command != command) return@atomic failure(ContractError.STATE_CONFLICT)
                return@atomic replay(previous)
            }
            if (hasSettlement(db, requestId)) return@atomic failure(ContractError.STATE_CONFLICT)
            val stored = readList(db, request.list) ?: return@atomic failure(ContractError.DATA_UNAVAILABLE)
            if (!sameList(stored, request.list)) return@atomic failure(ContractError.STATE_CONFLICT)

            val current = readActive(db)
            val selected = when (val result = DeliverySelection.select(request, offerId, requestId, store.readBalance(db), current)) {
                is ContractResult.Failure -> return@atomic result
                is ContractResult.Success -> result.value
            }
            val settlement = selected.payment?.let { payment ->
                when (val recorded = store.record(db, payment, LedgerJson.command(payment).toString())) {
                    is ContractResult.Failure -> return@atomic recorded
                    is ContractResult.Success -> recorded.value
                }
            }
            db.insertOrThrow("delivery_requests", null, ContentValues().apply {
                put("request_id", requestId.value)
                put("command", command)
                put("active", DeliveryJson.active(selected.active).toString())
                put("paid", if (settlement != null) 1 else 0)
            })
            db.insertOrThrow("delivery_active", null, ContentValues().apply {
                put("id", 1)
                put("request_id", requestId.value)
            })
            ContractResult.Success(DeliveryCommit(selected, settlement))
        }

    /**
     * 활성 의뢰의 경로를 시작하거나 새 경로 세대로 바꾼다. 미도착 기한의 시작 시각은 처음 값을 유지한다.
     * 활성 의뢰가 없으면 DATA_UNAVAILABLE, 다른 요청이면 STATE_CONFLICT다.
     */
    fun beginRoute(requestId: RequestId, route: RouteId, at: EpochMillis): ContractResult<DeliveryInProgress> = store.atomic { db ->
        val active = readActive(db) ?: return@atomic failure(ContractError.DATA_UNAVAILABLE)
        if (active.requestId != requestId) return@atomic failure(ContractError.STATE_CONFLICT)
        val started = readRoute(db, requestId)?.second ?: at
        db.insertWithOnConflict("delivery_routes", null, ContentValues().apply {
            put("request_id", requestId.value)
            put("route", route.value)
            put("started_at", started.value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        ContractResult.Success(DeliveryInProgress(active, route, started))
    }

    /** 경로까지 시작한 활성 의뢰. 활성 의뢰가 없거나 경로를 아직 시작하지 않았으면 null이다. */
    fun inProgress(): ContractResult<DeliveryInProgress?> = store.atomic { db ->
        val active = readActive(db) ?: return@atomic ContractResult.Success(null)
        val route = readRoute(db, active.requestId) ?: return@atomic ContractResult.Success(null)
        ContractResult.Success(DeliveryInProgress(active, route.first, route.second))
    }

    /**
     * [requestId] 의뢰의 도착을 정산한다. [DeliverySettlement]의 결과와 지급·환급, 활성 의뢰 종료, 완료한 자리 교체, 임시 보관 기록,
     * 이 명령과 당시 전체 결과를 한 트랜잭션으로 확정한다. 빈 칸 수는 같은 트랜잭션 안에서 가게 용량과 보유 차량으로 계산한다.
     *
     * - 이미 확정한 요청은 현재 활성 의뢰보다 먼저 확인한다. 같은 명령(규칙·증거·현재 시각)이면 당시 결과와 정산을 그대로 돌려주고,
     *   다른 명령이면 STATE_CONFLICT다. 당시 결과만 다시 읽으려면 [arrivalResult]를 쓴다.
     * - 저장된 결과는 원래 명령과 당시 경로·빈 칸 수로 다시 계산해 대조한다. 다르면 STORAGE_FAILURE이고 원본을 바꾸지 않는다.
     *
     * @param nextList 완료한 자리를 채운 목록을 만드는 함수. 보통 `DeliveryProposal.replaceCompleted`에 콘텐츠·가게 레벨·난수를 묶어 넘긴다.
     * @return 확인 대기면 아무것도 바꾸지 않는다. 활성 의뢰나 경로가 없으면 DATA_UNAVAILABLE, 다른 의뢰가 활성이면 STATE_CONFLICT다.
     */
    fun settleArrival(
        rules: DeliverySettlementRules,
        requestId: RequestId,
        evidence: Observation<DeliveryArrivalEvidence>,
        now: EpochMillis,
        nextList: (DeliveryList, DeliveryOfferId) -> ContractResult<DeliveryList>,
    ): ContractResult<ArrivalCommit> = store.atomic { db ->
        val command = DeliveryJson.arrivalCommand(requestId, rules, evidence, now).toString()
        readArrival(db, requestId)?.let { stored ->
            if (stored.command != command) return@atomic failure(ContractError.STATE_CONFLICT)
            return@atomic ContractResult.Success(stored.commit)
        }
        val active = readActive(db) ?: return@atomic failure(ContractError.DATA_UNAVAILABLE)
        if (active.requestId != requestId) return@atomic failure(ContractError.STATE_CONFLICT)
        val route = readRoute(db, active.requestId) ?: return@atomic failure(ContractError.DATA_UNAVAILABLE)
        val progress = DeliveryInProgress(active, route.first, route.second)
        val slots = freeSlots(db)
        val outcome = when (val result = DeliverySettlement(rules).resolve(progress, evidence, now, slots)) {
            is ContractResult.Failure -> return@atomic result
            is ContractResult.Success -> result.value
        }
        val change = when (outcome) {
            is DeliveryOutcome.AwaitingEvidence -> return@atomic ContractResult.Success(ArrivalCommit(outcome, null))
            is DeliveryOutcome.IncomeCompleted -> outcome.change
            is DeliveryOutcome.VehicleDelivered -> outcome.change
            is DeliveryOutcome.PurchaseRefunded -> outcome.change
            is DeliveryOutcome.VehicleHeld, DeliveryOutcome.IncomeExpired -> null
        }
        // 이 의뢰의 정산 ID를 다른 기록이 먼저 썼으면 그 정산을 이 도착의 결과로 재사용하지 않는다.
        if (hasSettlement(db, settleIdOf(requestId))) return@atomic failure(ContractError.STATE_CONFLICT)
        val recorded = change?.let {
            when (val result = store.record(db, it, LedgerJson.command(it).toString())) {
                is ContractResult.Failure -> return@atomic result
                is ContractResult.Success -> result.value
            }
        }
        if (outcome is DeliveryOutcome.VehicleHeld) {
            db.insertOrThrow("delivery_held", null, ContentValues().apply {
                put("request_id", active.requestId.value)
                put("payload", DeliveryJson.held(outcome.held).toString())
            })
        }
        val completed = outcome is DeliveryOutcome.IncomeCompleted || outcome is DeliveryOutcome.VehicleDelivered ||
            outcome is DeliveryOutcome.VehicleHeld
        if (completed) replaceCompletedSlot(db, active, nextList)?.let { return@atomic it }
        finishActive(db, active.requestId).let { if (it is ContractResult.Failure) return@atomic it }
        db.delete("delivery_routes", "request_id = ?", arrayOf(active.requestId.value))
        db.insertOrThrow("delivery_outcomes", null, ContentValues().apply {
            put("request_id", active.requestId.value)
            put("command", command)
            put("result", DeliveryJson.arrivalResult(progress.route, progress.startedAt, slots, outcome).toString())
        })
        ContractResult.Success(ArrivalCommit(outcome, recorded))
    }

    /** 확정한 도착 결과. 확정 전이거나 확인 대기만 있었으면 null이다. 저장값이 원래 명령과 맞지 않으면 STORAGE_FAILURE다. */
    fun arrivalResult(requestId: RequestId): ContractResult<ArrivalCommit?> = store.atomic { db ->
        ContractResult.Success(readArrival(db, requestId)?.commit)
    }

    /** 확정한 보관 처리 결과. 수령·환급 전이면 null이다. 저장값이 원래 명령과 맞지 않으면 STORAGE_FAILURE다. */
    fun holdResult(requestId: RequestId): ContractResult<HoldCommit?> = store.atomic { db ->
        ContractResult.Success(readHoldOutcome(db, requestId)?.commit)
    }

    /** 임시 보관 중인 차량. */
    fun held(): ContractResult<List<HeldVehicle>> = store.atomic { db ->
        val ids = db.rawQuery("SELECT request_id FROM delivery_held ORDER BY request_id", null).use {
            buildList { while (it.moveToNext()) add(RequestId(it.text(0))) }
        }
        ContractResult.Success(ids.map { readHeld(db, it) ?: throw JSONException("보관 기록 누락") })
    }

    /**
     * 임시 보관 차량을 처리한다. 수령·환급은 지급 또는 환급, 보관 기록 정리, 이 명령과 당시 결과를 한 트랜잭션으로 확정한다.
     *
     * - 이미 수령·환급한 요청은 보관 기록보다 먼저 확인한다. 같은 명령(규칙·현재 시각)이면 당시 결과를 돌려주고 다른 명령이면 STATE_CONFLICT다.
     * - 보관 기록이 없으면 DATA_UNAVAILABLE이다.
     */
    fun resolveHold(rules: DeliverySettlementRules, requestId: RequestId, now: EpochMillis): ContractResult<HoldCommit> =
        store.atomic { db ->
            val command = DeliveryJson.holdCommand(requestId, rules, now).toString()
            readHoldOutcome(db, requestId)?.let { stored ->
                if (stored.command != command) return@atomic failure(ContractError.STATE_CONFLICT)
                return@atomic ContractResult.Success(stored.commit)
            }
            val held = readHeld(db, requestId) ?: return@atomic failure(ContractError.DATA_UNAVAILABLE)
            val outcome = when (val result = DeliverySettlement(rules).resolveHold(held, now, freeSlots(db))) {
                is ContractResult.Failure -> return@atomic result
                is ContractResult.Success -> result.value
            }
            val change = when (outcome) {
                is HoldOutcome.StillHeld -> return@atomic ContractResult.Success(HoldCommit(outcome, null))
                is HoldOutcome.Claimed -> outcome.change
                is HoldOutcome.Refunded -> outcome.change
            }
            if (hasSettlement(db, change.requestId)) return@atomic failure(ContractError.STATE_CONFLICT)
            val recorded = when (val result = store.record(db, change, LedgerJson.command(change).toString())) {
                is ContractResult.Failure -> return@atomic result
                is ContractResult.Success -> result.value
            }
            db.delete("delivery_held", "request_id = ?", arrayOf(requestId.value))
            db.insertOrThrow("delivery_hold_outcomes", null, ContentValues().apply {
                put("request_id", requestId.value)
                put("command", command)
                put("result", DeliveryJson.holdResult(held, outcome).toString())
            })
            ContractResult.Success(HoldCommit(outcome, recorded))
        }

    private class StoredArrival(val command: String, val commit: ArrivalCommit)

    private class StoredHold(val command: String, val commit: HoldCommit)

    private fun settleIdOf(requestId: RequestId) = RequestId("delivery/${requestId.value}/settle")

    /** 정산 계산에 쓸 그 의뢰의 당시 활성 상태. 이력이 없으면 손상이다. */
    private fun activeOf(db: SQLiteDatabase, requestId: RequestId): ActiveDelivery =
        (readRequest(db, requestId) ?: throw JSONException("선택 이력 누락")).active

    /**
     * 확정한 도착 결과를 읽는다. 원래 명령과 당시 경로·시작 시각·빈 칸 수로 다시 계산한 결과가 저장값과 같아야 하고,
     * 지급·환급 정산은 그 결과의 거래와 맞아야 한다. 돈·차량이 바뀌지 않은 결과에 정산이 있으면 손상이다.
     */
    private fun readArrival(db: SQLiteDatabase, requestId: RequestId): StoredArrival? = db.rawQuery(
        "SELECT command, result FROM delivery_outcomes WHERE request_id = ?", arrayOf(requestId.value),
    ).use {
        if (!it.moveToFirst()) return@use null
        val command = it.text(0)
        val stored = JSONObject(it.text(1))
        val input = DeliveryJson.arrivalInput(JSONObject(command))
        if (input.requestId != requestId ||
            DeliveryJson.arrivalCommand(requestId, input.rules, input.evidence, input.now).toString() != command
        ) {
            throw JSONException("도착 명령 형식 오류")
        }
        val route = RouteId(stored.getString("route"))
        val startedAt = EpochMillis(DeliveryJson.long(stored, "startedAt", Long.MIN_VALUE, Long.MAX_VALUE))
        val slots = DeliveryJson.long(stored, "slots", 0, Int.MAX_VALUE.toLong()).toInt()
        val progress = DeliveryInProgress(activeOf(db, requestId), route, startedAt)
        val outcome = when (val result = DeliverySettlement(input.rules).resolve(progress, input.evidence, input.now, slots)) {
            is ContractResult.Failure -> throw JSONException("저장된 도착 재계산 실패: ${result.error}")
            is ContractResult.Success -> result.value
        }
        if (outcome is DeliveryOutcome.AwaitingEvidence ||
            DeliveryJson.arrivalResult(route, startedAt, slots, outcome).toString() != stored.toString()
        ) {
            throw JSONException("도착 결과가 원래 명령과 다름")
        }
        StoredArrival(command, ArrivalCommit(outcome, settlementOf(db, settleIdOf(requestId), outcome.changeOrNull())))
    }

    /** 확정한 보관 처리 결과를 읽는다. 보관 차량은 도착 결과의 보관 차량과 같고, 결과는 원래 명령으로 다시 계산한 값과 같아야 한다. */
    private fun readHoldOutcome(db: SQLiteDatabase, requestId: RequestId): StoredHold? = db.rawQuery(
        "SELECT command, result FROM delivery_hold_outcomes WHERE request_id = ?", arrayOf(requestId.value),
    ).use {
        if (!it.moveToFirst()) return@use null
        val command = it.text(0)
        val stored = JSONObject(it.text(1))
        val input = DeliveryJson.holdInput(JSONObject(command))
        if (input.requestId != requestId || DeliveryJson.holdCommand(requestId, input.rules, input.now).toString() != command) {
            throw JSONException("보관 명령 형식 오류")
        }
        val held = heldOf(db, requestId)
        // 기한 전이면 칸이 있어야 수령으로 확정되므로 빈 칸 1로 다시 계산한다. 기한이 지났으면 칸 수와 무관하게 환급이다.
        val outcome = when (val result = DeliverySettlement(input.rules).resolveHold(held, input.now, 1)) {
            is ContractResult.Failure -> throw JSONException("저장된 보관 재계산 실패: ${result.error}")
            is ContractResult.Success -> result.value
        }
        val change = when (outcome) {
            is HoldOutcome.StillHeld -> throw JSONException("보관 결과 형식 오류")
            is HoldOutcome.Claimed -> outcome.change
            is HoldOutcome.Refunded -> outcome.change
        }
        if (DeliveryJson.holdResult(held, outcome).toString() != stored.toString()) throw JSONException("보관 결과가 원래 명령과 다름")
        StoredHold(command, HoldCommit(outcome, readRecordedSettlement(db, change) ?: throw JSONException("보관 정산 누락")))
    }

    /** 도착 결과에 기록된 보관 차량. 보관으로 끝난 도착이 없으면 손상이다. */
    private fun heldOf(db: SQLiteDatabase, requestId: RequestId): HeldVehicle =
        ((readArrival(db, requestId) ?: throw JSONException("보관 도착 결과 누락")).commit.outcome as? DeliveryOutcome.VehicleHeld)
            ?.held ?: throw JSONException("보관으로 끝난 도착이 아님")

    /** 보관 중인 차량. 보관 행은 그 의뢰의 도착 결과에 기록된 보관 차량과 같아야 한다. */
    private fun readHeld(db: SQLiteDatabase, requestId: RequestId): HeldVehicle? =
        db.rawQuery("SELECT payload FROM delivery_held WHERE request_id = ?", arrayOf(requestId.value)).use {
            if (!it.moveToFirst()) return@use null
            val payload = it.text(0)
            val held = heldOf(db, requestId)
            if (held.source != store.source || DeliveryJson.held(held).toString() != payload) throw JSONException("보관 기록 불일치")
            held
        }

    /** 결과의 거래로 확정된 정산. 거래가 없는 결과에 같은 정산 ID의 기록이 있으면 손상이다. */
    private fun settlementOf(db: SQLiteDatabase, settleId: RequestId, change: GameChange?): Settlement? {
        if (change == null) {
            if (hasSettlement(db, settleId)) throw JSONException("돈·차량이 바뀌지 않은 결과에 정산이 있음")
            return null
        }
        if (change.requestId != settleId) throw JSONException("정산 ID 불일치")
        return readRecordedSettlement(db, change) ?: throw JSONException("도착 정산 누락")
    }

    private fun DeliveryOutcome.changeOrNull(): GameChange? = when (this) {
        is DeliveryOutcome.IncomeCompleted -> change
        is DeliveryOutcome.VehicleDelivered -> change
        is DeliveryOutcome.PurchaseRefunded -> change
        is DeliveryOutcome.AwaitingEvidence, is DeliveryOutcome.VehicleHeld, DeliveryOutcome.IncomeExpired -> null
    }

    /** 완료한 의뢰가 아직 저장된 목록에 있으면 그 자리만 바꾼다. 날짜가 지나 목록이 없거나 이미 바뀌었으면 건너뛴다. */
    private fun replaceCompletedSlot(
        db: SQLiteDatabase,
        active: ActiveDelivery,
        nextList: (DeliveryList, DeliveryOfferId) -> ContractResult<DeliveryList>,
    ): ContractResult.Failure? {
        val stored = readList(db, active.proposed.list) ?: return null
        if (stored.offers.none { it.id == active.offer.id }) return null
        val updated = when (val result = nextList(stored, active.offer.id)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        if (updated == stored) return null
        return replaceList(db, stored, updated) as? ContractResult.Failure
    }

    /** 같은 트랜잭션 안의 빈 칸 수. 대여 중 차량도 소유 중이라 칸을 차지한다. */
    private fun freeSlots(db: SQLiteDatabase): Int {
        val capacity = db.rawQuery("SELECT capacity FROM shop WHERE id = 1", null).use {
            if (!it.moveToFirst()) throw JSONException("가게 용량 누락")
            it.integer(0, 0, Int.MAX_VALUE.toLong()).toInt()
        }
        val owned = db.rawQuery("SELECT payload FROM vehicles", null).use {
            var count = 0
            while (it.moveToNext()) if (LedgerJson.vehicle(JSONObject(it.text(0))).lifecycle == VehicleLifecycle.OWNED) count++
            count
        }
        return (capacity - owned).coerceAtLeast(0)
    }

    private fun readRoute(db: SQLiteDatabase, requestId: RequestId): Pair<RouteId, EpochMillis>? = db.rawQuery(
        "SELECT route, started_at FROM delivery_routes WHERE request_id = ?", arrayOf(requestId.value),
    ).use { if (it.moveToFirst()) RouteId(it.text(0)) to EpochMillis(it.integer(1)) else null }

    /** 활성 의뢰를 끝낸다. #38 포기·#39 완료와 같은 트랜잭션에서 호출하는 연결점이며 선택 이력은 남긴다. */
    internal fun finishActive(db: SQLiteDatabase, requestId: RequestId): ContractResult<Unit> {
        val removed = db.delete("delivery_active", "id = 1 AND request_id = ?", arrayOf(requestId.value))
        return if (removed == 1) ContractResult.Success(Unit) else failure(ContractError.STATE_CONFLICT)
    }

    /** 원래 명령에서 다시 계산해 저장값·구매 결제 정산과 대조를 마친 선택 이력. */
    private class StoredRequest(val command: String, val selected: DeliverySelected, val settlement: Settlement?) {
        val active: ActiveDelivery get() = selected.active
    }

    private fun replay(previous: StoredRequest): ContractResult<DeliveryCommit> =
        ContractResult.Success(DeliveryCommit(previous.selected, previous.settlement))

    private fun listValues(list: DeliveryList) = ContentValues().apply {
        put("created_on", list.createdOn.toString())
        put("band", list.band)
        put("source", list.source.name)
        put("content_version", list.contentVersion.value)
        put("rule_version", list.ruleVersion.value)
        put("payload", DeliveryJson.list(list).toString())
    }

    private val KEY = "created_on = ? AND band = ? AND source = ? AND content_version = ? AND rule_version = ?"

    private fun key(list: DeliveryList) = arrayOf(
        list.createdOn.toString(), list.band.toString(), list.source.name, list.contentVersion.value, list.ruleVersion.value,
    )

    private fun sameList(a: DeliveryList, b: DeliveryList) = DeliveryJson.list(a).toString() == DeliveryJson.list(b).toString()

    /** keyOf와 같은 키의 저장된 목록. */
    private fun readList(db: SQLiteDatabase, keyOf: DeliveryList): DeliveryList? =
        db.rawQuery("SELECT payload FROM delivery_lists WHERE $KEY", key(keyOf)).use {
            if (!it.moveToFirst()) return@use null
            DeliveryJson.list(JSONObject(it.text(0))).also { stored ->
                if (stored.createdOn != keyOf.createdOn || stored.band != keyOf.band || stored.source != keyOf.source ||
                    stored.contentVersion != keyOf.contentVersion || stored.ruleVersion != keyOf.ruleVersion
                ) {
                    throw JSONException("목록 키 불일치")
                }
            }
        }

    private fun readLists(db: SQLiteDatabase, day: LocalDate): List<DeliveryList> = db.rawQuery(
        "SELECT band, content_version, rule_version, payload FROM delivery_lists WHERE created_on = ? AND source = ? " +
            "ORDER BY band, content_version, rule_version",
        arrayOf(day.toString(), store.source.name),
    ).use {
        val lists = ArrayList<DeliveryList>()
        while (it.moveToNext()) {
            val list = DeliveryJson.list(JSONObject(it.text(3)))
            if (list.createdOn != day || list.source != store.source || list.band.toLong() != it.integer(0) ||
                list.contentVersion.value != it.text(1) || list.ruleVersion.value != it.text(2)
            ) {
                throw JSONException("목록 키 불일치")
            }
            lists += list
        }
        lists
    }

    private fun readRequest(db: SQLiteDatabase, requestId: RequestId): StoredRequest? = db.rawQuery(
        "SELECT command, active, paid FROM delivery_requests WHERE request_id = ?", arrayOf(requestId.value),
    ).use {
        if (!it.moveToFirst()) return@use null
        val command = it.text(0)
        val activeJson = it.text(1)
        val paid = it.flag(2)
        val stored = JSONObject(command)
        if (stored.getString("request") != requestId.value) throw JSONException("선택 이력 요청 ID 불일치")
        val proposed = DeliveryJson.proposed(stored.getJSONObject("proposed"))
        val offerId = DeliveryOfferId(stored.getString("offer"))
        if (DeliveryJson.command(requestId, offerId, proposed).toString() != command) throw JSONException("선택 명령 형식 오류")
        // 저장된 활성 의뢰·결제 여부는 파싱만 믿지 않고 원래 명령에서 다시 계산한 값과 같아야 한다.
        val selected = when (val result = DeliverySelection.select(proposed, offerId, requestId, GameMoney(Long.MAX_VALUE), null)) {
            is ContractResult.Failure -> throw JSONException("저장된 선택 재생 실패: ${result.error}")
            is ContractResult.Success -> result.value
        }
        if (activeJson != DeliveryJson.active(selected.active).toString()) throw JSONException("활성 의뢰가 원래 명령과 다름")
        if (paid != (selected.payment != null)) throw JSONException("결제 기록 불일치")
        // 구매는 재전송뿐 아니라 활성 조회에서도 결제 정산이 있고 원래 결제와 맞아야 한다. 사라진 결제를 정상 구매로 보여 주지 않는다.
        val settlement = selected.payment?.let { readRecordedSettlement(db, it) ?: throw JSONException("구매 결제 정산 누락") }
        StoredRequest(command, selected, settlement)
    }

    private fun readActive(db: SQLiteDatabase): ActiveDelivery? {
        val requestId = db.rawQuery("SELECT request_id FROM delivery_active WHERE id = 1", null).use {
            if (it.moveToFirst()) RequestId(it.text(0)) else null
        } ?: return null
        return (readRequest(db, requestId) ?: throw JSONException("활성 의뢰의 선택 이력 누락")).active
    }

    private fun hasSettlement(db: SQLiteDatabase, requestId: RequestId): Boolean =
        db.rawQuery("SELECT 1 FROM settlements WHERE request_id = ?", arrayOf(requestId.value)).use { it.moveToFirst() }
}

/**
 * [change]로 확정된 공용 정산을 읽는다. 없으면 null이다.
 * 정산 행의 명령이 [change]와 같고, 결과의 정산 ID·요청 ID·종류·출처·규칙 버전·금액·방향·차량이 [change]와 맞아야 한다.
 * 하나라도 다르면 파싱에 성공해도 손상으로 본다.
 */
internal fun readRecordedSettlement(db: SQLiteDatabase, change: GameChange): Settlement? =
    db.rawQuery("SELECT settlement_id, command, result FROM settlements WHERE request_id = ?", arrayOf(change.requestId.value)).use {
        if (!it.moveToFirst()) return@use null
        if (it.text(1) != LedgerJson.command(change).toString()) throw JSONException("정산 명령이 원래 거래와 다름")
        val settlement = LedgerJson.settlement(JSONObject(it.text(2)))
        val t = settlement.transaction
        if (settlement.settlementId.value != it.text(0) || t.requestId != change.requestId || t.kind != change.kind ||
            t.source != change.source || t.ruleVersion != change.ruleVersion || t.money != change.money
        ) {
            throw JSONException("정산 결과가 원래 거래와 다름")
        }
        val sameVehicle = when (val write = change.vehicle) {
            null -> settlement.changedVehicle == null
            // 새로 지급한 차량은 희귀도·취득액·출처까지 원래 거래와 같아야 한다.
            is VehicleWrite.Add -> settlement.changedVehicle == write.vehicle
            is VehicleWrite.Update -> settlement.changedVehicle?.ownedVehicleId == write.ownedVehicleId
        }
        if (!sameVehicle) throw JSONException("정산 차량이 원래 거래와 다름")
        settlement
    }

/** TEXT 칸만 받는다. NULL·정수·실수·BLOB은 손상으로 본다. */
internal fun Cursor.text(index: Int): String {
    if (getType(index) != Cursor.FIELD_TYPE_STRING) throw JSONException("문자열 칸 형식 오류")
    return getString(index)
}

/** INTEGER 칸만 받는다. TEXT·실수·NULL을 0이나 절삭 정수로 바꾸지 않는다. */
internal fun Cursor.integer(index: Int, min: Long = Long.MIN_VALUE, max: Long = Long.MAX_VALUE): Long {
    if (getType(index) != Cursor.FIELD_TYPE_INTEGER) throw JSONException("정수 칸 형식 오류")
    return getLong(index).also { if (it !in min..max) throw JSONException("정수 칸 범위 오류") }
}

/** INTEGER 0 또는 1만 받는다. */
internal fun Cursor.flag(index: Int): Boolean = integer(index, 0, 1) == 1L

/** 필드 순서가 고정된 탁송 표현. 정수는 형식과 범위를 검사하고 자동 변환하지 않는다. */
internal object DeliveryJson {
    fun offer(o: DeliveryOffer): JSONObject = when (o) {
        is DeliveryOffer.Income -> JSONObject().put("type", "INCOME").put("id", o.id.value)
            .put("vehicle", o.vehicleId.value).put("reward", o.reward.units)
        is DeliveryOffer.Purchase -> JSONObject().put("type", "PURCHASE").put("id", o.id.value)
            .put("vehicle", o.vehicleId.value).put("rarity", o.rarity.name).put("price", o.price.units)
    }

    fun offer(j: JSONObject): DeliveryOffer {
        val id = DeliveryOfferId(j.getString("id"))
        val vehicle = VehicleId(j.getString("vehicle"))
        return when (j.getString("type")) {
            "INCOME" -> DeliveryOffer.Income(id, vehicle, GameMoney(long(j, "reward", 0, Long.MAX_VALUE)))
            "PURCHASE" -> DeliveryOffer.Purchase(
                id, vehicle, Rarity.valueOf(j.getString("rarity")), GameMoney(long(j, "price", 0, Long.MAX_VALUE)),
            )
            else -> throw JSONException("의뢰 유형 오류")
        }
    }

    fun list(l: DeliveryList): JSONObject = JSONObject().put("band", l.band).put("source", l.source.name)
        .put("createdOn", l.createdOn.toString()).put("content", l.contentVersion.value).put("rule", l.ruleVersion.value)
        .put("offers", offers(l.offers)).put("next", l.nextOfferNumber)

    fun list(j: JSONObject) = DeliveryList(
        long(j, "band", 0, Int.MAX_VALUE.toLong()).toInt(),
        InputSource.valueOf(j.getString("source")),
        date(j.getString("createdOn")),
        ContentVersion(j.getString("content")),
        RuleVersion(j.getString("rule")),
        offers(j.getJSONArray("offers")),
        long(j, "next", 0, Int.MAX_VALUE.toLong()).toInt(),
    )

    fun proposed(p: DeliveryProposed): JSONObject = JSONObject().put("destination", p.destination.value)
        .put("meters", p.estimated.meters).put("estimateSource", p.estimated.source.name)
        .put("list", list(p.list)).put("offers", offers(p.offers))

    fun proposed(j: JSONObject) = DeliveryProposed(
        DestinationKey(j.getString("destination")),
        EstimatedRouteDistance(long(j, "meters", 0, Long.MAX_VALUE), InputSource.valueOf(j.getString("estimateSource"))),
        list(j.getJSONObject("list")),
        offers(j.getJSONArray("offers")),
    )

    fun active(a: ActiveDelivery): JSONObject = JSONObject().put("request", a.requestId.value)
        .put("proposed", proposed(a.proposed)).put("offer", offer(a.offer))

    fun active(j: JSONObject) = ActiveDelivery(
        RequestId(j.getString("request")), proposed(j.getJSONObject("proposed")), offer(j.getJSONObject("offer")),
    )

    /** 선택 명령. 목록 키·목적지·거리·출처·버전·후보 전체와 고른 의뢰를 함께 비교한다. */
    fun command(requestId: RequestId, offerId: DeliveryOfferId, proposed: DeliveryProposed): JSONObject =
        JSONObject().put("request", requestId.value).put("offer", offerId.value).put("proposed", proposed(proposed))

    fun held(h: HeldVehicle): JSONObject = JSONObject().put("request", h.requestId.value).put("source", h.source.name)
        .put("vehicle", LedgerJson.vehicle(h.vehicle)).put("price", h.price.units)
        .put("heldAt", h.heldAt.value).put("deadline", h.deadline.value)

    fun held(j: JSONObject) = HeldVehicle(
        RequestId(j.getString("request")),
        InputSource.valueOf(j.getString("source")),
        LedgerJson.vehicle(j.getJSONObject("vehicle")),
        GameMoney(long(j, "price", 0, Long.MAX_VALUE)),
        EpochMillis(long(j, "heldAt", Long.MIN_VALUE, Long.MAX_VALUE)),
        EpochMillis(long(j, "deadline", Long.MIN_VALUE, Long.MAX_VALUE)),
    )

    fun settlementRules(r: DeliverySettlementRules): JSONObject = JSONObject().put("rule", r.ruleVersion.value)
        .put("arrivalWindow", r.arrivalWindowMs).put("holdWindow", r.holdWindowMs).put("holdFee", r.holdRefundFeePercent)

    fun settlementRules(j: JSONObject) = DeliverySettlementRules(
        RuleVersion(j.getString("rule")), long(j, "arrivalWindow", 1, Long.MAX_VALUE), long(j, "holdWindow", 1, Long.MAX_VALUE),
        long(j, "holdFee", 0, 100).toInt(),
    )

    fun evidence(e: Observation<DeliveryArrivalEvidence>): JSONObject = when (e) {
        is Observation.Available -> JSONObject().put("type", "AVAILABLE").put("route", e.value.routeId.value)
            .put("source", e.value.source.name).put("at", e.value.receivedAt.value)
        is Observation.Missing -> JSONObject().put("type", "MISSING").put("reason", e.reason.name)
    }

    fun evidence(j: JSONObject): Observation<DeliveryArrivalEvidence> = when (j.getString("type")) {
        "AVAILABLE" -> Observation.Available(
            DeliveryArrivalEvidence(
                RouteId(j.getString("route")), InputSource.valueOf(j.getString("source")),
                EpochMillis(long(j, "at", Long.MIN_VALUE, Long.MAX_VALUE)),
            ),
        )
        "MISSING" -> Observation.Missing(MissingReason.valueOf(j.getString("reason")))
        else -> throw JSONException("증거 형식 오류")
    }

    class ArrivalInput(
        val requestId: RequestId,
        val rules: DeliverySettlementRules,
        val evidence: Observation<DeliveryArrivalEvidence>,
        val now: EpochMillis,
    )

    /** 도착 정산 명령. 같은 요청의 재시도는 규칙·증거·현재 시각이 모두 같아야 같은 명령이다. */
    fun arrivalCommand(requestId: RequestId, rules: DeliverySettlementRules, evidence: Observation<DeliveryArrivalEvidence>, now: EpochMillis) =
        JSONObject().put("request", requestId.value).put("rules", settlementRules(rules)).put("evidence", evidence(evidence))
            .put("now", now.value)

    fun arrivalInput(j: JSONObject) = ArrivalInput(
        RequestId(j.getString("request")), settlementRules(j.getJSONObject("rules")), evidence(j.getJSONObject("evidence")),
        EpochMillis(long(j, "now", Long.MIN_VALUE, Long.MAX_VALUE)),
    )

    class HoldInput(val requestId: RequestId, val rules: DeliverySettlementRules, val now: EpochMillis)

    /** 보관 처리 명령. 같은 요청의 재시도는 규칙·현재 시각이 모두 같아야 같은 명령이다. */
    fun holdCommand(requestId: RequestId, rules: DeliverySettlementRules, now: EpochMillis) =
        JSONObject().put("request", requestId.value).put("rules", settlementRules(rules)).put("now", now.value)

    fun holdInput(j: JSONObject) = HoldInput(
        RequestId(j.getString("request")), settlementRules(j.getJSONObject("rules")), EpochMillis(long(j, "now", Long.MIN_VALUE, Long.MAX_VALUE)),
    )

    fun outcome(o: DeliveryOutcome): JSONObject = when (o) {
        is DeliveryOutcome.AwaitingEvidence -> throw JSONException("확인 대기는 저장하지 않는다")
        is DeliveryOutcome.IncomeCompleted -> JSONObject().put("type", "INCOME_COMPLETED").put("change", LedgerJson.command(o.change))
        is DeliveryOutcome.VehicleDelivered -> JSONObject().put("type", "VEHICLE_DELIVERED").put("change", LedgerJson.command(o.change))
        is DeliveryOutcome.VehicleHeld -> JSONObject().put("type", "VEHICLE_HELD").put("held", held(o.held))
        DeliveryOutcome.IncomeExpired -> JSONObject().put("type", "INCOME_EXPIRED")
        is DeliveryOutcome.PurchaseRefunded -> JSONObject().put("type", "PURCHASE_REFUNDED").put("change", LedgerJson.command(o.change))
    }

    /** 도착 결과. 다시 계산해 대조할 수 있게 당시 경로·시작 시각·빈 칸 수를 함께 둔다. */
    fun arrivalResult(route: RouteId, startedAt: EpochMillis, slots: Int, outcome: DeliveryOutcome): JSONObject =
        JSONObject().put("type", "ARRIVAL").put("route", route.value).put("startedAt", startedAt.value).put("slots", slots)
            .put("outcome", outcome(outcome))

    fun holdResult(held: HeldVehicle, outcome: HoldOutcome): JSONObject = JSONObject().put("held", held(held)).put(
        "outcome",
        when (outcome) {
            is HoldOutcome.StillHeld -> throw JSONException("계속 보관은 저장하지 않는다")
            is HoldOutcome.Claimed -> JSONObject().put("type", "CLAIMED").put("change", LedgerJson.command(outcome.change))
            is HoldOutcome.Refunded -> JSONObject().put("type", "REFUNDED").put("change", LedgerJson.command(outcome.change))
        },
    )

    /** 잘못된 날짜는 기본값으로 바꾸지 않고 저장 손상으로 돌려준다. */
    fun date(raw: String): LocalDate = try {
        LocalDate.parse(raw)
    } catch (error: DateTimeParseException) {
        throw JSONException("날짜 형식 오류: $raw")
    }

    private fun offers(list: List<DeliveryOffer>) = JSONArray().apply { list.forEach { put(offer(it)) } }

    private fun offers(array: JSONArray): List<DeliveryOffer> = List(array.length()) { offer(array.getJSONObject(it)) }

    fun long(j: JSONObject, key: String, min: Long, max: Long): Long {
        val value = when (val raw = j.get(key)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> throw JSONException("$key 정수 형식 오류")
        }
        if (value !in min..max) throw JSONException("$key 정수 범위 오류")
        return value
    }
}
