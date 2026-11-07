package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.drivegrowth.core.contract.ContentVersion
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.EstimatedRouteDistance
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleWrite
import io.drivegrowth.core.delivery.ActiveDelivery
import io.drivegrowth.core.delivery.DeliveryList
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.delivery.DeliveryOfferId
import io.drivegrowth.core.delivery.DeliveryProposed
import io.drivegrowth.core.delivery.DeliverySelected
import io.drivegrowth.core.delivery.DeliverySelection
import io.drivegrowth.core.delivery.DestinationKey
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
 * 탁송 하루 목록, 활성 의뢰, 선택 요청 이력을 공용 원장과 같은 DB 트랜잭션으로 저장한다.
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
     * - 재생할 때 저장된 활성 의뢰와 결제 정산이 원래 명령과 맞지 않으면 STORAGE_FAILURE이고 원본을 바꾸지 않는다.
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
                return@atomic replay(db, previous)
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

    /** 활성 의뢰를 끝낸다. #38 포기·#39 완료와 같은 트랜잭션에서 호출하는 연결점이며 선택 이력은 남긴다. */
    internal fun finishActive(db: SQLiteDatabase, requestId: RequestId): ContractResult<Unit> {
        val removed = db.delete("delivery_active", "id = 1 AND request_id = ?", arrayOf(requestId.value))
        return if (removed == 1) ContractResult.Success(Unit) else failure(ContractError.STATE_CONFLICT)
    }

    /** 원래 명령에서 다시 계산해 저장값과 대조를 마친 선택 이력. */
    private class StoredRequest(val command: String, val selected: DeliverySelected) {
        val active: ActiveDelivery get() = selected.active
    }

    /** 대조를 마친 선택 이력과 공용 결제 정산을 돌려준다. 정산이 원래 결제와 맞지 않으면 손상이다. */
    private fun replay(db: SQLiteDatabase, previous: StoredRequest): ContractResult<DeliveryCommit> {
        val settlement = previous.selected.payment?.let { readRecordedSettlement(db, it) ?: throw JSONException("구매 결제 정산 누락") }
        return ContractResult.Success(DeliveryCommit(previous.selected, settlement))
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
        StoredRequest(command, selected)
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
 * 정산 행의 명령이 [change]와 같고, 결과의 정산 ID·요청 ID·종류·출처·규칙 버전·금액·방향·차량 ID가 [change]와 맞아야 한다.
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
        val vehicleId = when (val write = change.vehicle) {
            null -> null
            is VehicleWrite.Add -> write.vehicle.ownedVehicleId
            is VehicleWrite.Update -> write.ownedVehicleId
        }
        if (settlement.changedVehicle?.ownedVehicleId != vehicleId) throw JSONException("정산 차량이 원래 거래와 다름")
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

    /** 잘못된 날짜는 기본값으로 바꾸지 않고 저장 손상으로 돌려준다. */
    fun date(raw: String): LocalDate = try {
        LocalDate.parse(raw)
    } catch (error: DateTimeParseException) {
        throw JSONException("날짜 형식 오류: $raw")
    }

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
