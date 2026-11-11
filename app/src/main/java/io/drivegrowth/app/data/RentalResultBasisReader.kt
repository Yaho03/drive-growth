package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.content.ContentValues
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.CompletedRental
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** 실제 최초 정산의 시간 원본만 조회한다. legacy를 현재 정책/금액으로 역산하지 않는다. */
class RentalResultBasisReader(private val store: SqliteGameChangeStore) {
    fun read(rentalId: RequestId): ContractResult<Observation<RentalResultBasis>> = store.readResult { db ->
        RentalBasisStorage.load(db, store, rentalId) // 존재하는 orphan/손상도 없는 대여로 숨기지 않는다.
        when (val completed = store.rental.completedRental(rentalId)) {
            is ContractResult.Failure -> completed
            is ContractResult.Success -> completed.value?.let { ContractResult.Success(readIn(db, it)) }
                ?: ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        }
    }

    internal fun readIn(db: SQLiteDatabase, completed: CompletedRental): Observation<RentalResultBasis> {
        val original = RentalBasisStorage.load(db, store, completed.rentalId)
            ?: return Observation.Missing(MissingReason.NO_DATA)
        if (original.settlementId != completed.receipt.settlement.settlementId) throw SQLiteException("대여 시간 근거의 최초 정산 연결 손상")
        return Observation.Available(RentalResultBasis(original.rentalId, original.settlementId,
            original.profileId, original.source, original.ruleVersion, original.start, original.end, original.periods))
    }
}

/** 선택적인 같은 DB 메타데이터. 존재하는 원본의 손상을 누락으로 바꾸지 않는다. */
internal object RentalBasisStorage {
    fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE rental_result_basis (rental_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
    }

    internal data class Record(val rentalId: RequestId, val ownedId: OwnedVehicleId, val profileId: ProfileId,
        val source: InputSource, val ruleVersion: RuleVersion, val start: EpochMillis, val end: EpochMillis,
        val periods: List<RentalIncomePeriod>, val settlementId: SettlementId? = null) {
        val weighted: BigDecimal get() = periods.fold(BigDecimal.ZERO) { sum, p -> sum + p.weightedMillis }
    }

    fun begin(db: SQLiteDatabase, store: SqliteGameChangeStore, id: RequestId) {
        val profile = profile(store)
        val record = db.rawQuery("SELECT owned_id,version,start,end FROM rentals WHERE id=?", arrayOf(id.value)).use { row ->
            if (!row.moveToFirst()) throw SQLiteException("새 대여 시간 근거의 대여 누락")
            Record(id, OwnedVehicleId(row.text(0)), profile, store.source, RuleVersion(row.text(1)),
                EpochMillis(row.integer(2, 0, Long.MAX_VALUE)), EpochMillis(row.integer(3, 0, Long.MAX_VALUE)), emptyList())
        }
        db.execSQL("INSERT INTO rental_result_basis(rental_id,payload) VALUES(?,?)", arrayOf(id.value, encode(record)))
        if (load(db, store, id) != record) throw SQLiteException("새 대여 시간 근거 삽입 누락")
    }

    fun append(db: SQLiteDatabase, store: SqliteGameChangeStore, previous: Record?, period: RentalIncomePeriod) {
        if (previous == null) return // legacy를 중간부터 채우지 않는다.
        val last = previous.periods.lastOrNull()
        val periods = if (last != null && last.through == period.from && last.kind == period.kind &&
            last.origin == period.origin && last.policy == period.policy) {
            previous.periods.dropLast(1) + last.copy(through = period.through,
                normalMillis = last.normalMillis + period.normalMillis, reducedMillis = last.reducedMillis + period.reducedMillis,
                cappedMillis = last.cappedMillis + period.cappedMillis, weightedMillis = last.weightedMillis + period.weightedMillis)
        } else previous.periods + period
        save(db, store, previous, previous.copy(periods = periods))
    }

    fun finish(db: SQLiteDatabase, store: SqliteGameChangeStore, previous: Record?, settlement: Settlement) {
        if (previous != null) save(db, store, previous, previous.copy(settlementId = settlement.settlementId))
    }

    private fun save(db: SQLiteDatabase, store: SqliteGameChangeStore, previous: Record, next: Record) {
        check(db.inTransaction())
        if (db.update("rental_result_basis", ContentValues().apply { put("payload", encode(next)) },
                "rental_id=? AND payload=?", arrayOf(previous.rentalId.value, encode(previous))) != 1 ||
            load(db, store, next.rentalId) != next) throw SQLiteException("대여 시간 근거 실제 쓰기 불일치")
    }

    fun load(db: SQLiteDatabase, store: SqliteGameChangeStore, id: RequestId): Record? {
        val raw = db.rawQuery("SELECT payload FROM rental_result_basis WHERE rental_id=?", arrayOf(id.value)).use {
            if (!it.moveToFirst()) return null
            it.text(0)
        }
        val record = decode(raw)
        if (record.rentalId != id || record.source != store.source || record.profileId != profile(store) || encode(record) != raw)
            throw SQLiteException("대여 시간 근거 키/출처/전체 원문 손상")
        db.rawQuery("SELECT owned_id,version,start,end,cursor,weighted,settlement_request,phase FROM rentals WHERE id=?", arrayOf(id.value)).use { row ->
            if (!row.moveToFirst() || row.text(0) != record.ownedId.value || row.text(1) != record.ruleVersion.value ||
                row.integer(2, 0, Long.MAX_VALUE) != record.start.value || row.integer(3, 0, Long.MAX_VALUE) != record.end.value)
                throw SQLiteException("대여 시간 근거의 원 대여 연결 손상")
            val cursor = row.integer(4, record.start.value, record.end.value)
            if (record.end.value <= record.start.value || record.weighted.compareTo(row.text(5).toBigDecimal()) != 0 ||
                (record.periods.isEmpty() && cursor != record.start.value) ||
                (record.periods.isNotEmpty() && (record.periods.first().from != record.start || record.periods.last().through.value != cursor)) ||
                !record.periods.zipWithNext().all { (a, b) -> a.through == b.from }) throw SQLiteException("대여 시간 근거의 연속 구간/누적 손상")
            val request = if (row.isNull(6)) null else row.text(6)
            if (record.settlementId == null) {
                if (request != null || row.text(7) != "RENTED") throw SQLiteException("미완료 대여의 시간 근거 상태 손상")
            } else {
                if (cursor != record.end.value || request != "rental/settle/${id.value}" ||
                    row.text(7) !in setOf("RETURNING", "IDLE", "NEEDS_REPAIR")) throw SQLiteException("대여 시간 근거 완료 범위 손상")
                db.rawQuery("SELECT settlement_id,command,result FROM settlements WHERE request_id=?", arrayOf(request)).use { money ->
                    if (!money.moveToFirst()) throw SQLiteException("대여 시간 근거의 금융 원본 소실")
                    val json = JSONObject(money.text(2))
                    LedgerJson.integer(json, "balance", 0, Long.MAX_VALUE); LedgerJson.integer(json, "at", Long.MIN_VALUE, Long.MAX_VALUE)
                    val s = LedgerJson.settlement(json)
                    if (LedgerJson.settlement(s).toString() != money.text(2) || money.text(0) != record.settlementId.value ||
                        s.settlementId != record.settlementId || s.transaction.requestId.value != request ||
                        s.transaction.source != record.source || s.transaction.ruleVersion != record.ruleVersion ||
                        s.transaction.kind != TransactionKind.RENTAL || s.transaction.money?.direction != MoneyDirection.CREDIT || s.changedVehicle != null ||
                        money.text(1) != JSONArray(listOf("settle", id.value)).toString()) throw SQLiteException("대여 시간 근거의 최초 정산 원문 손상")
                }
            }
        }
        return record
    }

    private fun profile(store: SqliteGameChangeStore): ProfileId = when (val r = store.localProfileId()) {
        is ContractResult.Success -> r.value
        is ContractResult.Failure -> throw SQLiteException("대여 시간 근거의 프로필 조회 실패: ${r.error}")
    }

    private fun policy(p: RentalIncomePolicy) = JSONObject().put("version", p.version.value).put("normal", p.normalMillis)
        .put("cap", p.capMillis).put("multiplier", p.reducedMultiplier.toPlainString())
    private fun period(p: RentalIncomePeriod) = JSONObject().put("from", p.from.value).put("through", p.through.value)
        .put("kind", p.kind.name).put("origin", p.origin?.value ?: JSONObject.NULL).put("normal", p.normalMillis)
        .put("reduced", p.reducedMillis).put("capped", p.cappedMillis).put("weighted", p.weightedMillis.toPlainString())
        .put("policy", p.policy?.let(::policy) ?: JSONObject.NULL)
    private fun encode(r: Record) = JSONObject().put("type", "rental-result-basis-v1").put("rental", r.rentalId.value)
        .put("owned", r.ownedId.value).put("profile", r.profileId.value).put("source", r.source.name).put("rule", r.ruleVersion.value)
        .put("start", r.start.value).put("end", r.end.value).put("periods", JSONArray(r.periods.map(::period)))
        .put("settlement", r.settlementId?.value ?: JSONObject.NULL).toString()
    private fun number(j: JSONObject, key: String) = LedgerJson.integer(j, key, 0, Long.MAX_VALUE)
    private fun text(j: JSONObject, key: String) = j.get(key) as? String ?: throw SQLiteException("대여 시간 근거 문자열 형식")
    private fun decode(raw: String): Record {
        val j = JSONObject(raw)
        if (text(j, "type") != "rental-result-basis-v1") throw SQLiteException("대여 시간 근거 형식 버전")
        val values = j.getJSONArray("periods")
        val periods = (0 until values.length()).map { i ->
            val p = values.getJSONObject(i)
            val policy = when (val value = p.get("policy")) {
                JSONObject.NULL -> null
                is JSONObject -> RentalIncomePolicy(RuleVersion(text(value, "version")), number(value, "normal"), number(value, "cap"), text(value, "multiplier").toBigDecimal())
                else -> throw SQLiteException("대여 시간 근거 정책 형식")
            }
            val origin = if (p.get("origin") == JSONObject.NULL) null else EpochMillis(number(p, "origin"))
            RentalIncomePeriod(EpochMillis(number(p, "from")), EpochMillis(number(p, "through")), RentalIncomeKind.valueOf(text(p, "kind")),
                origin, number(p, "normal"), number(p, "reduced"), number(p, "capped"), text(p, "weighted").toBigDecimal(), policy)
        }
        val settlement = if (j.get("settlement") == JSONObject.NULL) null else SettlementId(text(j, "settlement"))
        return Record(RequestId(text(j, "rental")), OwnedVehicleId(text(j, "owned")), ProfileId(text(j, "profile")),
            InputSource.valueOf(text(j, "source")), RuleVersion(text(j, "rule")), EpochMillis(number(j, "start")), EpochMillis(number(j, "end")), periods, settlement)
    }
}
