package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.drivegrowth.core.contract.ContentVersion
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.DeliveryArrivalEvidence
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.EstimatedRouteDistance
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.RouteId
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.delivery.ActiveDelivery
import io.drivegrowth.core.delivery.DeliveryChange
import io.drivegrowth.core.delivery.DeliveryChangeOffer
import io.drivegrowth.core.delivery.DeliveryChangeRules
import io.drivegrowth.core.delivery.DeliveryInProgress
import io.drivegrowth.core.delivery.DeliveryTerms
import io.drivegrowth.core.delivery.DeliveryList
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.delivery.DeliveryOfferId
import io.drivegrowth.core.delivery.DeliveryOutcome
import io.drivegrowth.core.delivery.DeliveryProposed
import io.drivegrowth.core.delivery.DeliverySelected
import io.drivegrowth.core.delivery.DeliverySelection
import io.drivegrowth.core.delivery.DeliverySettlement
import io.drivegrowth.core.delivery.DestinationKey
import io.drivegrowth.core.delivery.HeldVehicle
import io.drivegrowth.core.delivery.HoldOutcome
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

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

/**
 * 목적지 변경의 확정 결과.
 *
 * @param terms 바뀐 의뢰, 보류 대금, 변경 사용 여부.
 * @param settlement 차액 결제·환급의 공용 정산. 차액이 없으면 null이다.
 */
data class ChangeCommit(
    val terms: DeliveryTerms,
    val settlement: Settlement?,
)

/** 포기의 확정 결과. 수익 탁송 포기는 돈이 오가지 않아 settlement가 null이다. */
data class AbandonCommit(
    val requestId: RequestId,
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
        /** delivery_outcomes.kind의 포기 값. 도착 정산 결과는 결과 클래스 이름을 쓴다. */
        const val ABANDONED = "Abandoned"

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
                "CREATE TABLE delivery_outcomes (request_id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, settle_request TEXT)",
            )
            db.execSQL("CREATE TABLE delivery_held (request_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
            db.execSQL(
                """CREATE TABLE delivery_changes (
                request_id TEXT PRIMARY KEY NOT NULL, command TEXT NOT NULL, active TEXT NOT NULL,
                paid INTEGER NOT NULL CHECK (paid >= 0), charged INTEGER NOT NULL CHECK (charged IN (0, 1)))""",
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
     */
    fun select(proposed: DeliveryProposed, offerId: DeliveryOfferId, requestId: RequestId): ContractResult<DeliveryCommit> =
        store.atomic { db ->
            if (proposed.estimated.source != store.source || proposed.list.source != store.source) {
                return@atomic failure(ContractError.INVALID_INPUT)
            }
            val command = DeliveryJson.command(requestId, offerId, proposed).toString()
            readRequest(db, requestId)?.let { previous ->
                if (previous.command != command) return@atomic failure(ContractError.STATE_CONFLICT)
                return@atomic replay(db, previous, offerId)
            }
            if (store.requestUnavailable(db, requestId)) return@atomic failure(ContractError.STATE_CONFLICT)
            val stored = readList(db, proposed.list) ?: return@atomic failure(ContractError.DATA_UNAVAILABLE)
            if (!sameList(stored, proposed.list)) return@atomic failure(ContractError.STATE_CONFLICT)

            val current = readActive(db)
            val selected = when (val result = DeliverySelection.select(proposed, offerId, requestId, store.readBalance(db), current)) {
                is ContractResult.Failure -> return@atomic result
                is ContractResult.Success -> result.value
            }
            val settlement = selected.payment?.let { payment ->
                when (val recorded = store.record(db, payment, LedgerJson.command(payment).toString(), SqliteGameChangeStore.RequestDomain.DELIVERY)) {
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
     * 활성 의뢰의 도착을 정산한다. [DeliverySettlement]의 결과와 지급·환급, 활성 의뢰 종료, 완료한 자리 교체, 임시 보관 기록을 한 트랜잭션으로 확정한다.
     * 빈 칸 수는 같은 트랜잭션 안에서 가게 용량과 보유 차량으로 계산한다.
     *
     * @param nextList 완료한 자리를 채운 목록을 만드는 함수. 보통 `DeliveryProposal.replaceCompleted`에 콘텐츠·가게 레벨·난수를 묶어 넘긴다.
     * @return 확인 대기면 아무것도 바꾸지 않는다. 활성 의뢰나 경로가 없으면 DATA_UNAVAILABLE이다.
     */
    fun settleArrival(
        settlement: DeliverySettlement,
        evidence: Observation<DeliveryArrivalEvidence>,
        now: EpochMillis,
        nextList: (DeliveryList, DeliveryOfferId) -> ContractResult<DeliveryList>,
    ): ContractResult<ArrivalCommit> = store.atomic { db ->
        val active = readActive(db) ?: return@atomic failure(ContractError.DATA_UNAVAILABLE)
        val route = readRoute(db, active.requestId) ?: return@atomic failure(ContractError.DATA_UNAVAILABLE)
        if (hasSettlement(db, RequestId("delivery/${active.requestId.value}/settle")))
            return@atomic failure(ContractError.STATE_CONFLICT)
        val progress = DeliveryInProgress(active, route.first, route.second)
        val outcome = when (val result = settlement.resolve(progress, evidence, now, freeSlots(db))) {
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
        val recorded = change?.let {
            when (val result = store.record(db, it, LedgerJson.command(it).toString(), SqliteGameChangeStore.RequestDomain.DELIVERY)) {
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
            put("kind", outcome::class.simpleName)
            put("settle_request", recorded?.transaction?.requestId?.value)
        })
        ContractResult.Success(ArrivalCommit(outcome, recorded))
    }

    /**
     * 진행 중 의뢰의 목적지를 바꾼다. 기존 변경 확인 → 활성 의뢰·잔액·후보 검사 → 확정 순서로 처리한다.
     * 차액 결제·환급, 바뀐 의뢰와 보류 대금, 새 경로 세대를 한 트랜잭션으로 확정한다. 미도착 기한의 시작 시각은 처음 값을 유지한다.
     *
     * - 같은 의뢰·같은 변경 명령은 당시 결과를 돌려준다. 다른 변경 명령은 STATE_CONFLICT다(변경은 1회).
     * - 활성 의뢰나 경로가 없으면 DATA_UNAVAILABLE이다. 실패하면 아무것도 바꾸지 않는다.
     */
    fun changeDestination(
        rules: DeliveryChangeRules,
        offer: DeliveryChangeOffer,
        offerId: DeliveryOfferId,
        route: RouteId,
    ): ContractResult<ChangeCommit> = store.atomic { db ->
        val command = DeliveryJson.changeCommand(rules, offer, offerId, route).toString()
        val changeId = RequestId("delivery/${offer.requestId.value}/change")
        readChange(db, offer.requestId)?.let { stored ->
            if (stored.command != command) return@atomic failure(ContractError.STATE_CONFLICT)
            return@atomic ContractResult.Success(ChangeCommit(stored.terms, if (stored.charged) readSettlement(db, changeId) else null))
        }
        val terms = readTerms(db) ?: return@atomic failure(ContractError.DATA_UNAVAILABLE)
        if (terms.active.requestId != offer.requestId) return@atomic failure(ContractError.STATE_CONFLICT)
        if (offer.proposed.list.source != store.source) return@atomic failure(ContractError.INVALID_INPUT)
        val started = readRoute(db, offer.requestId)?.second ?: return@atomic failure(ContractError.DATA_UNAVAILABLE)
        if (hasSettlement(db, changeId)) return@atomic failure(ContractError.STATE_CONFLICT)
        val changed = when (val result = DeliveryChange(rules).change(terms, offer, offerId, store.readBalance(db))) {
            is ContractResult.Failure -> return@atomic result
            is ContractResult.Success -> result.value
        }
        val recorded = changed.change?.let {
            when (val result = store.record(db, it, LedgerJson.command(it).toString(), SqliteGameChangeStore.RequestDomain.DELIVERY)) {
                is ContractResult.Failure -> return@atomic result
                is ContractResult.Success -> result.value
            }
        }
        db.insertOrThrow("delivery_changes", null, ContentValues().apply {
            put("request_id", offer.requestId.value)
            put("command", command)
            put("active", DeliveryJson.active(changed.terms.active).toString())
            put("paid", changed.terms.paid.units)
            put("charged", if (recorded != null) 1 else 0)
        })
        db.update("delivery_routes", ContentValues().apply {
            put("route", route.value)
            put("started_at", started.value)
        }, "request_id = ?", arrayOf(offer.requestId.value))
        ContractResult.Success(ChangeCommit(changed.terms, recorded))
    }

    /**
     * 진행 중 의뢰를 포기한다. 환급, 활성 의뢰 종료, 결과 기록을 한 트랜잭션으로 확정하고 하루 목록은 바꾸지 않는다.
     * 같은 의뢰의 포기 재전송은 당시 결과를 돌려준다. 이미 도착 정산 등으로 끝난 의뢰는 STATE_CONFLICT다.
     */
    fun abandon(rules: DeliveryChangeRules, requestId: RequestId): ContractResult<AbandonCommit> = store.atomic { db ->
        val settleId = RequestId("delivery/${requestId.value}/settle")
        db.rawQuery("SELECT kind FROM delivery_outcomes WHERE request_id = ?", arrayOf(requestId.value)).use {
            if (it.moveToFirst()) {
                if (it.getString(0) != ABANDONED) return@atomic failure(ContractError.STATE_CONFLICT)
                val settlement = if (hasSettlement(db, settleId)) readSettlement(db, settleId) else null
                return@atomic ContractResult.Success(AbandonCommit(requestId, settlement))
            }
        }
        if (hasSettlement(db, settleId)) return@atomic failure(ContractError.STATE_CONFLICT)
        val terms = readTerms(db) ?: return@atomic failure(ContractError.DATA_UNAVAILABLE)
        if (terms.active.requestId != requestId) return@atomic failure(ContractError.STATE_CONFLICT)
        val abandoned = when (val result = DeliveryChange(rules).abandon(terms)) {
            is ContractResult.Failure -> return@atomic result
            is ContractResult.Success -> result.value
        }
        val recorded = abandoned.change?.let {
            when (val result = store.record(db, it, LedgerJson.command(it).toString(), SqliteGameChangeStore.RequestDomain.DELIVERY)) {
                is ContractResult.Failure -> return@atomic result
                is ContractResult.Success -> result.value
            }
        }
        finishActive(db, requestId).let { if (it is ContractResult.Failure) return@atomic it }
        db.delete("delivery_routes", "request_id = ?", arrayOf(requestId.value))
        db.insertOrThrow("delivery_outcomes", null, ContentValues().apply {
            put("request_id", requestId.value)
            put("kind", ABANDONED)
            put("settle_request", recorded?.transaction?.requestId?.value)
        })
        ContractResult.Success(AbandonCommit(requestId, recorded))
    }

    /** 임시 보관 중인 차량. */
    fun held(): ContractResult<List<HeldVehicle>> = store.atomic { db ->
        db.rawQuery("SELECT request_id, payload FROM delivery_held ORDER BY request_id", null).use {
            val list = ArrayList<HeldVehicle>()
            while (it.moveToNext()) {
                val held = DeliveryJson.held(JSONObject(it.getString(1)))
                if (held.requestId.value != it.getString(0)) throw JSONException("보관 요청 ID 불일치")
                list += held
            }
            ContractResult.Success(list)
        }
    }

    /**
     * 임시 보관 차량을 처리한다. 수령·환급은 지급 또는 환급과 보관 기록 정리를 한 트랜잭션으로 확정한다.
     * 보관 기록이 없으면 DATA_UNAVAILABLE이다.
     */
    fun resolveHold(settlement: DeliverySettlement, requestId: RequestId, now: EpochMillis): ContractResult<HoldCommit> =
        store.atomic { db ->
            val held = db.rawQuery("SELECT payload FROM delivery_held WHERE request_id = ?", arrayOf(requestId.value)).use {
                if (it.moveToFirst()) DeliveryJson.held(JSONObject(it.getString(0))) else null
            } ?: return@atomic failure(ContractError.DATA_UNAVAILABLE)
            if (held.requestId != requestId || held.source != store.source) throw JSONException("보관 기록 불일치")
            val outcome = when (val result = settlement.resolveHold(held, now, freeSlots(db))) {
                is ContractResult.Failure -> return@atomic result
                is ContractResult.Success -> result.value
            }
            val change = when (outcome) {
                is HoldOutcome.StillHeld -> return@atomic ContractResult.Success(HoldCommit(outcome, null))
                is HoldOutcome.Claimed -> outcome.change
                is HoldOutcome.Refunded -> outcome.change
            }
            val recorded = when (val result = store.record(db, change, LedgerJson.command(change).toString(), SqliteGameChangeStore.RequestDomain.DELIVERY)) {
                is ContractResult.Failure -> return@atomic result
                is ContractResult.Success -> result.value
            }
            db.delete("delivery_held", "request_id = ?", arrayOf(requestId.value))
            ContractResult.Success(HoldCommit(outcome, recorded))
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
            it.getInt(0)
        }
        val owned = db.rawQuery("SELECT payload FROM vehicles", null).use {
            var count = 0
            while (it.moveToNext()) if (LedgerJson.vehicle(JSONObject(it.getString(0))).lifecycle == VehicleLifecycle.OWNED) count++
            count
        }
        return (capacity - owned).coerceAtLeast(0)
    }

    private fun readRoute(db: SQLiteDatabase, requestId: RequestId): Pair<RouteId, EpochMillis>? = db.rawQuery(
        "SELECT route, started_at FROM delivery_routes WHERE request_id = ?", arrayOf(requestId.value),
    ).use { if (it.moveToFirst()) RouteId(it.getString(0)) to EpochMillis(it.getLong(1)) else null }

    /** 활성 의뢰를 끝낸다. #38 포기·#39 완료와 같은 트랜잭션에서 호출하는 연결점이며 선택 이력은 남긴다. */
    internal fun finishActive(db: SQLiteDatabase, requestId: RequestId): ContractResult<Unit> {
        val removed = db.delete("delivery_active", "id = 1 AND request_id = ?", arrayOf(requestId.value))
        return if (removed == 1) ContractResult.Success(Unit) else failure(ContractError.STATE_CONFLICT)
    }

    private class StoredRequest(val command: String, val active: ActiveDelivery, val paid: Boolean)

    private fun replay(db: SQLiteDatabase, previous: StoredRequest, offerId: DeliveryOfferId): ContractResult<DeliveryCommit> {
        val active = previous.active
        val selected = when (val result = DeliverySelection.select(active.proposed, offerId, active.requestId, GameMoney(0), active)) {
            is ContractResult.Failure -> throw JSONException("저장된 선택 재생 실패: ${result.error}")
            is ContractResult.Success -> result.value
        }
        val settlement = if (previous.paid) readSettlement(db, active.requestId) else null
        if (previous.paid != (selected.payment != null)) throw JSONException("결제 기록 불일치")
        return ContractResult.Success(DeliveryCommit(selected, settlement))
    }

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
            DeliveryJson.list(JSONObject(it.getString(0))).also { stored ->
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
            val list = DeliveryJson.list(JSONObject(it.getString(3)))
            if (list.createdOn != day || list.source != store.source || list.band != it.getInt(0) ||
                list.contentVersion.value != it.getString(1) || list.ruleVersion.value != it.getString(2)
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
        val active = DeliveryJson.active(JSONObject(it.getString(1)))
        if (active.requestId != requestId) throw JSONException("선택 이력 요청 ID 불일치")
        StoredRequest(it.getString(0), active, it.getInt(2) == 1)
    }

    /** 현재 활성 의뢰. 목적지를 바꿨으면 바뀐 의뢰다. */
    private fun readActive(db: SQLiteDatabase): ActiveDelivery? = readTerms(db)?.active

    /** 현재 활성 의뢰의 보류 대금과 변경 사용 여부. 바꾼 적이 없으면 처음 선택한 의뢰와 결제액이다. */
    private fun readTerms(db: SQLiteDatabase): DeliveryTerms? {
        val requestId = db.rawQuery("SELECT request_id FROM delivery_active WHERE id = 1", null).use {
            if (it.moveToFirst()) RequestId(it.getString(0)) else null
        } ?: return null
        readChange(db, requestId)?.let { return it.terms }
        val selected = (readRequest(db, requestId) ?: throw JSONException("활성 의뢰의 선택 이력 누락")).active
        return DeliveryTerms(selected, (selected.offer as? DeliveryOffer.Purchase)?.price ?: GameMoney(0), changeUsed = false)
    }

    private class StoredChange(val command: String, val terms: DeliveryTerms, val charged: Boolean)

    private fun readChange(db: SQLiteDatabase, requestId: RequestId): StoredChange? = db.rawQuery(
        "SELECT command, active, paid, charged FROM delivery_changes WHERE request_id = ?", arrayOf(requestId.value),
    ).use {
        if (!it.moveToFirst()) return@use null
        val active = DeliveryJson.active(JSONObject(it.getString(1)))
        if (active.requestId != requestId) throw JSONException("변경 기록 요청 ID 불일치")
        StoredChange(it.getString(0), DeliveryTerms(active, GameMoney(it.getLong(2)), changeUsed = true), it.getInt(3) == 1)
    }

    private fun hasSettlement(db: SQLiteDatabase, requestId: RequestId): Boolean =
        db.rawQuery("SELECT 1 FROM settlements WHERE request_id = ?", arrayOf(requestId.value)).use { it.moveToFirst() }

    private fun readSettlement(db: SQLiteDatabase, requestId: RequestId): Settlement =
        db.rawQuery("SELECT result FROM settlements WHERE request_id = ?", arrayOf(requestId.value)).use {
            if (!it.moveToFirst()) throw JSONException("구매 결제 정산 누락")
            LedgerJson.settlement(JSONObject(it.getString(0)))
        }
}

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
        LocalDate.parse(j.getString("createdOn")),
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

    /** 변경 명령. 변경 목록 전체, 고른 의뢰, 실제·합산 거리, 새 경로, 규칙을 함께 비교한다. */
    fun changeCommand(rules: DeliveryChangeRules, offer: DeliveryChangeOffer, offerId: DeliveryOfferId, route: RouteId): JSONObject =
        JSONObject().put("request", offer.requestId.value).put("offer", offerId.value)
            .put("traveled", offer.traveledMeters).put("combined", offer.combinedMeters).put("route", route.value)
            .put("rule", rules.ruleVersion.value).put("fee", rules.cancelFeePercent).put("proposed", proposed(offer.proposed))

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

    private fun offers(list: List<DeliveryOffer>) = JSONArray().apply { list.forEach { put(offer(it)) } }

    private fun offers(array: JSONArray): List<DeliveryOffer> = List(array.length()) { offer(array.getJSONObject(it)) }

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
