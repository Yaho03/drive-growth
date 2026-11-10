package io.drivegrowth.app.data

import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.Garage
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/** 배정 시 고정한 규칙과 가상 고객 입력. 재실행이나 재정산에서 다시 추첨하지 않는다. */
data class RentalTerms(
    val rules: RuleVersion, val customer: SimulatedCustomer,
    val roll: Int, val start: EpochMillis, val returnMinutes: Int, val fuelBonus: GameMoney,
    val rounding: RoundingMode?, val regularCustomer: Boolean? = null,
) {
    init {
        require(roll in 0..99 && returnMinutes in setOf(15, 30, 60))
        require(start.value in 0..Long.MAX_VALUE - 7 * 3_600_000L)
    }
}

typealias RentalReceipt = io.drivegrowth.core.rental.RentalReceipt
data class ShopState(val progress: ShopProgress, val capacity: Int, val facilities: Set<Facility>)

class RentalLedger internal constructor(private val store: SqliteGameChangeStore) : CompletedRentalSource {
    internal companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE rentals (
                id TEXT PRIMARY KEY NOT NULL, owned_id TEXT NOT NULL, version TEXT NOT NULL,
                start INTEGER NOT NULL, end INTEGER NOT NULL, return_end INTEGER NOT NULL, cursor INTEGER NOT NULL,
                parked_since INTEGER, weighted TEXT NOT NULL, fare TEXT NOT NULL, bonus INTEGER NOT NULL, rounding TEXT,
                accident INTEGER NOT NULL, match_review INTEGER NOT NULL, wash_review INTEGER NOT NULL,
                phase TEXT NOT NULL, settlement_request TEXT, regular_customer INTEGER)""")
            db.execSQL("CREATE TABLE visits (id TEXT PRIMARY KEY NOT NULL, command TEXT NOT NULL, assigned TEXT)")
            db.execSQL("CREATE UNIQUE INDEX single_active_rental ON rentals(owned_id) WHERE phase!='IDLE'")
            db.execSQL("CREATE INDEX rental_activity ON rentals(owned_id, phase)")
            db.execSQL("CREATE TABLE shop (id INTEGER PRIMARY KEY CHECK(id=1), level INTEGER NOT NULL, capacity INTEGER NOT NULL)")
            db.execSQL("INSERT INTO shop VALUES (1,1,3)")
            db.execSQL("CREATE TABLE facilities (facility TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("CREATE TABLE rental_rest (owned_id TEXT PRIMARY KEY NOT NULL)")
        }
    }

    private fun conflict() = ContractResult.Failure(ContractError.STATE_CONFLICT)
    private fun missing() = ContractResult.Failure(ContractError.DATA_UNAVAILABLE)

    internal fun activity(db: SQLiteDatabase, id: OwnedVehicleId): VehicleActivity =
        db.rawQuery("SELECT phase FROM rentals WHERE owned_id=? AND phase!='IDLE'", arrayOf(id.value)).use {
            if (it.moveToFirst()) VehicleActivity.valueOf(it.getString(0)) else VehicleActivity.IDLE
        }

    fun activity(id: OwnedVehicleId) = store.atomic { db ->
        if (store.findVehicle(db, id) == null) missing() else ContractResult.Success(activity(db, id))
    }

    fun setResting(id: OwnedVehicleId, resting: Boolean) = store.atomic { db ->
        if (store.findVehicle(db, id)?.lifecycle != VehicleLifecycle.OWNED) return@atomic missing()
        if (resting) db.execSQL("INSERT OR IGNORE INTO rental_rest VALUES (?)", arrayOf(id.value))
        else db.execSQL("DELETE FROM rental_rest WHERE owned_id=?", arrayOf(id.value))
        ContractResult.Success(Unit)
    }

    fun restingVehicles() = store.atomic { db ->
        ContractResult.Success(db.rawQuery("SELECT owned_id FROM rental_rest", null).use {
            buildSet { while (it.moveToNext()) add(OwnedVehicleId(it.getString(0))) }
        })
    }

    /** 빈 배정도 방문 결과로 저장하므로 원래 요청을 다시 보내도 다른 차를 배정하지 않는다. */
    fun assign(request: RentalRequest, catalog: VehicleCatalogSnapshot, categories: Map<VehicleId, RentalCategory>,
               hourlyRates: Map<VehicleId, GameMoney>, terms: RentalTerms): ContractResult<OwnedVehicleId?> = store.atomic { db ->
        val id = request.id.value
        val command = RentalVisitCommand.encode(request,catalog,categories,hourlyRates,terms)
        db.rawQuery("SELECT command,assigned FROM visits WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) return@atomic if (it.getString(0) == command)
                ContractResult.Success(it.getString(1)?.let(::OwnedVehicleId)) else conflict()
        }
        if (store.requestUnavailable(db, request.id))
            return@atomic conflict()
        if (catalog.vehicles.none { it.vehicleId == request.desired }) return@atomic missing()
        val owned = when (val result = store.listOwnedVehicles()) {
            is ContractResult.Success -> result.value
            is ContractResult.Failure -> return@atomic result
        }
        val resting = db.rawQuery("SELECT owned_id FROM rental_rest", null).use {
            buildSet { while (it.moveToNext()) add(OwnedVehicleId(it.getString(0))) }
        }
        val plan = when (val result = Garage(catalog).planRental(request, owned, categories, resting) {
            ContractResult.Success(activity(db, it))
        }) {
            is ContractResult.Success -> result.value
            is ContractResult.Failure -> return@atomic result
        }
        val selected = (plan as? AssignmentPlan.Candidate)?.vehicle?.ownedVehicleId
        db.execSQL("INSERT INTO visits(id,command,assigned) VALUES(?,?,?)", arrayOf(id, command, selected?.value))
        db.rawQuery("SELECT command,assigned FROM visits WHERE id=?",arrayOf(id)).use {
            if(!it.moveToFirst()||it.text(0)!=command||(if(it.isNull(1))null else it.text(1))!=selected?.value)throw SQLiteException("방문 실제 원문/결과 삽입 누락")
        }
        if (plan !is AssignmentPlan.Candidate) return@atomic ContractResult.Success(null)
        val v = plan.vehicle
        if (v.revision.value == Int.MAX_VALUE) return@atomic conflict()
        val lastReturn = db.rawQuery("SELECT max(return_end) FROM rentals WHERE owned_id=?", arrayOf(v.ownedVehicleId.value)).use {
            it.moveToFirst()
            if (it.isNull(0)) null else it.getLong(0)
        }
        if (lastReturn != null && terms.start.value < lastReturn) return@atomic conflict()
        val hourlyFare = hourlyRates[v.vehicleId] ?: return@atomic missing()
        val pastAccidents = countSettledAccidents(db, v.ownedVehicleId)
        val quote = plan.quote(hourlyFare, pastAccidents, terms.customer, terms.roll,
            rentalFacilities(installed(db), terms.fuelBonus))
        val end = terms.start.value + plan.request.hours * 3_600_000L
        db.execSQL("""INSERT INTO rentals(id,owned_id,version,start,end,return_end,cursor,weighted,fare,bonus,
            rounding,accident,match_review,wash_review,phase,regular_customer) VALUES(?,?,?,?,?,?,?,'0',?,?,?,?,?,?,'RENTED',?)""",
            arrayOf<Any?>(id, v.ownedVehicleId.value, terms.rules.value, terms.start.value, end,
                end + terms.returnMinutes * 60_000L, terms.start.value, quote.fare.toPlainString(),
                quote.facilityBonus.units, terms.rounding?.name, quote.accident.bit, quote.exactMatchReviewBoost.bit,
                quote.carWashReviewBoost.bit, terms.regularCustomer?.bit))
        val bumped=v.copy(revision=Revision(v.revision.value+1));store.writeVehicle(db,bumped)
        if(store.findVehicle(db,v.ownedVehicleId)!=bumped)throw SQLiteException("배정 실제 개체 revision 쓰기 불일치")
        db.rawQuery("SELECT owned_id,version,start,end,return_end,cursor,weighted,fare,bonus,phase,rounding,accident,match_review,wash_review,regular_customer FROM rentals WHERE id=?",arrayOf(id)).use {
            if(!it.moveToFirst()||it.text(0)!=selected?.value||it.text(1)!=terms.rules.value||it.integer(2,0,Long.MAX_VALUE)!=terms.start.value||it.integer(3,0,Long.MAX_VALUE)!=end||it.integer(4,0,Long.MAX_VALUE)!=end+terms.returnMinutes*60000L||it.integer(5,0,Long.MAX_VALUE)!=terms.start.value||it.text(6)!="0"||it.text(7)!=quote.fare.toPlainString()||it.integer(8,0,Long.MAX_VALUE)!=quote.facilityBonus.units||it.text(9)!="RENTED")throw SQLiteException("배정 실제 대여 입력 쓰기 불일치")
            if((if(it.isNull(10))null else it.text(10))!=terms.rounding?.name||it.strictBoolean(11)!=quote.accident||it.strictBoolean(12)!=quote.exactMatchReviewBoost||it.strictBoolean(13)!=quote.carWashReviewBoost||(if(it.isNull(14))null else it.strictBoolean(14))!=terms.regularCustomer)throw SQLiteException("배정 실제 고객/사고/반올림 쓰기 불일치")
        }
        ContractResult.Success(selected)
    }

    /** 호출자가 확인한 연속 주행/주차 구간. 화면 가시성만으로 주행을 추정하지 않는다. */
    fun account(id:RequestId,from:EpochMillis,to:EpochMillis,parkedSince:EpochMillis?)=accountBasis(id,from,to,parkedSince,true)

    /** 원점은 마지막 주행 종료다. 앱 onStop을 물리 주차로 저장하지 않는다. */
    fun accountInactive(id:RequestId,from:EpochMillis,to:EpochMillis,policyOrigin:EpochMillis)=accountBasis(id,from,to,policyOrigin,false)

    private fun accountBasis(id: RequestId, from: EpochMillis, to: EpochMillis, parkedSince: EpochMillis?, physical:Boolean) = store.atomic { db ->
        if (from.value < 0 || to.value < 0 || (parkedSince != null && parkedSince.value !in 0..from.value))
            return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        db.rawQuery("SELECT cursor,end,weighted,parked_since,start FROM rentals WHERE id=?", arrayOf(id.value)).use {
            if (!it.moveToFirst()) return@atomic missing()
            val cursor=it.integer(0,0,Long.MAX_VALUE);val end=minOf(it.integer(1,0,Long.MAX_VALUE),to.value)
            val oldPark=if(it.isNull(3))null else it.integer(3,0,Long.MAX_VALUE)
            val start=it.integer(4,0,Long.MAX_VALUE);val previous=it.text(2).toBigDecimal()
            if(cursor !in start..it.integer(1,start,Long.MAX_VALUE)||previous.signum()<0||previous>(cursor-start).toBigDecimal()||oldPark?.let{p->p>cursor}==true)throw SQLiteException("대여 누적/커서 범위 손상")
            if (end <= cursor || to.value < from.value) return@atomic ContractResult.Success(EpochMillis(cursor))
            if (from.value > cursor) return@atomic conflict()
            if (parkedSince != null && oldPark!=null && oldPark != parkedSince.value) return@atomic conflict()
            val increment = parkedSince?.let { start -> parkingIncomeWindow(start, EpochMillis(cursor), EpochMillis(end)).equivalentMillis() } ?: (end-cursor).toBigDecimal()
            val amount=(previous+increment).toPlainString();val persistedPark=if(physical)parkedSince?.value else oldPark
            if(db.update("rentals",android.content.ContentValues().apply{put("cursor",end);put("weighted",amount);if(persistedPark==null)putNull("parked_since")else put("parked_since",persistedPark)},"id=?",arrayOf(id.value))!=1)throw SQLiteException("대여 누적 무갱신")
            db.rawQuery("SELECT cursor,weighted,parked_since FROM rentals WHERE id=?",arrayOf(id.value)).use{r->
                if(!r.moveToFirst()||r.integer(0,0,Long.MAX_VALUE)!=end||r.text(1)!=amount||(if(r.isNull(2))null else r.integer(2,0,Long.MAX_VALUE))!=persistedPark)throw SQLiteException("대여 실제 누적 쓰기 불일치")
            }
            ContractResult.Success(EpochMillis(end))
        }
    }

    /** 앱 재실행 시 이어갈 대여와 마지막 반영 시각. */
    data class Pending(val id: RequestId, val vehicle: OwnedVehicleId, val phase: VehicleActivity,
                       val cursor: EpochMillis, val end: EpochMillis, val returnEnd: EpochMillis,
                       val parkedSince: EpochMillis? = null)

    fun pending() = store.atomic { db ->
        db.rawQuery("SELECT id,owned_id,phase,cursor,end,return_end,parked_since FROM rentals WHERE phase IN ('RENTED','RETURNING') ORDER BY start,id", null).use {
            ContractResult.Success(buildList {
                while (it.moveToNext()) add(Pending(RequestId(it.getString(0)), OwnedVehicleId(it.getString(1)),
                    VehicleActivity.valueOf(it.getString(2)), EpochMillis(it.strictInteger(3, 0, Long.MAX_VALUE)),
                    EpochMillis(it.strictInteger(4, 0, Long.MAX_VALUE)), EpochMillis(it.strictInteger(5, 0, Long.MAX_VALUE)),
                    if (it.isNull(6)) null else EpochMillis(it.strictInteger(6, 0, Long.MAX_VALUE))))
            })
        }
    }

    /** 정산 쓰기 순서로 최초 영업을 찾는다. 같은 시각·이미 열람한 정산도 구분한다. */
    override fun firstCompletedRental(): ContractResult<CompletedRental?> = completed(null)

    /** #49가 지정 대여의 확정 결과만 읽는다. 미완료/없는 대여는 null이다. */
    override fun completedRental(id: RequestId): ContractResult<CompletedRental?> = completed(id)

    override fun accidentCount(ownedVehicleId: OwnedVehicleId): ContractResult<Int> =
        store.readResult { db ->
            if (store.findVehicle(db, ownedVehicleId) == null) missing()
            else ContractResult.Success(countSettledAccidents(db, ownedVehicleId))
        }

    private val completedSelect = """SELECT r.id,r.owned_id,s.result,r.accident,r.match_review,r.wash_review,
        r.bonus,r.weighted,r.regular_customer,r.settlement_request,s.request_id,s.settlement_id FROM rentals r
        LEFT JOIN settlements s ON s.request_id=r.settlement_request
        WHERE r.settlement_request IS NOT NULL"""

    /** 방문/보험 기준과 구분한 확정 대여 건수. 조회는 지급/열람/활동을 바꾸지 않는다. */
    fun completedRentalCount(): ContractResult<Int> = store.atomic { db ->
        ContractResult.Success(completedRentalCount(db))
    }

    private fun completedRentalCount(db: SQLiteDatabase): Int {
        val rentals = HashSet<RequestId>()
        val receipts = HashSet<SettlementId>()
        db.rawQuery(completedSelect, null).use { rows ->
            while (rows.moveToNext()) {
                val completed = decodeCompleted(rows)
                if (!rentals.add(completed.rentalId) || !receipts.add(completed.receipt.settlement.settlementId))
                    throw SQLiteException("완료 대여 정산 중복")
            }
        }
        // 공용 정산만 남은 대여를 정상 완료나 단순 미완료로 보정하지 않는다.
        db.rawQuery("""SELECT 1 FROM settlements s LEFT JOIN rentals r ON r.settlement_request=s.request_id
            WHERE substr(s.request_id,1,14)='rental/settle/' AND r.id IS NULL LIMIT 1""", null).use {
            if (it.moveToFirst()) throw SQLiteException("대여 없는 완료 정산")
        }
        return rentals.size
    }

    private fun countSettledAccidents(db: SQLiteDatabase, ownedVehicleId: OwnedVehicleId): Int =
        db.rawQuery("$completedSelect AND r.owned_id=?", arrayOf(ownedVehicleId.value)).use { rows ->
            var count = 0
            while (rows.moveToNext()) if (decodeCompleted(rows).receipt.accident) count++
            count
        }

    private fun completed(id: RequestId?): ContractResult<CompletedRental?> = store.read { db ->
        val filter = if (id == null) "" else " AND r.id=?"
        db.rawQuery("$completedSelect$filter ORDER BY s.rowid LIMIT 1", id?.let { arrayOf(it.value) }).use { row ->
            if (!row.moveToFirst()) return@read null
            decodeCompleted(row)
        }
    }

    private fun decodeCompleted(row: Cursor): CompletedRental {
        if (row.isNull(2) || row.isNull(10) || row.isNull(11)) throw SQLiteException("대여 정산 누락")
        val rentalId = RequestId(row.getString(0))
        val settlement = LedgerJson.settlement(JSONObject(row.getString(2)))
        val expected = "rental/settle/" + rentalId.value
        if (row.getString(9) != expected || row.getString(10) != expected ||
            settlement.transaction.requestId.value != expected ||
            settlement.settlementId.value != row.getString(11) ||
            settlement.transaction.kind != TransactionKind.RENTAL ||
            settlement.transaction.source != store.source)
            throw SQLiteException("대여 정산 연결 불일치")
        val bonusUnits = row.strictInteger(6, 0, Long.MAX_VALUE)
        val weighted = if (row.getType(7) == Cursor.FIELD_TYPE_STRING) BigDecimal(row.getString(7))
            else throw SQLiteException("시간 가중치 형식 오류")
        if (weighted.signum() < 0) throw SQLiteException("시간 가중치 범위 오류")
        val bonus = GameMoney(if (weighted.signum() == 0) 0 else bonusUnits)
        return CompletedRental(rentalId, OwnedVehicleId(row.getString(1)),
            RentalReceipt(settlement, row.strictBoolean(3), row.strictBoolean(4), row.strictBoolean(5),
                bonus, if (row.isNull(8)) null else row.strictBoolean(8)))
    }

    private fun Cursor.strictInteger(column: Int, min: Long, max: Long): Long {
        if (getType(column) != Cursor.FIELD_TYPE_INTEGER) throw SQLiteException("정수 저장 형식 오류")
        val value = getLong(column)
        if (value !in min..max) throw SQLiteException("정수 저장 범위 오류")
        return value
    }

    private fun Cursor.strictBoolean(column: Int) = strictInteger(column, 0, 1) == 1L

    fun settle(id: RequestId, resolvedRounding: RoundingMode? = null) = store.atomic { db ->
        db.rawQuery("""SELECT version,start,end,cursor,weighted,fare,bonus,rounding,accident,match_review,wash_review,
            settlement_request,regular_customer FROM rentals WHERE id=?""", arrayOf(id.value)).use { row ->
            if (!row.moveToFirst()) return@atomic missing()
            val weightedMillis = BigDecimal(row.getString(4))
            val bonus = GameMoney(if (weightedMillis.signum() == 0) 0 else row.getLong(6))
            fun receipt(s: Settlement) = RentalReceipt(s, row.getInt(8)==1, row.getInt(9)==1, row.getInt(10)==1, bonus,
                if (row.isNull(12)) null else row.getInt(12) == 1)
            val request = RequestId("rental/settle/" + id.value)
            val configured = row.getString(7)?.let(RoundingMode::valueOf)
            if (configured != null && resolvedRounding != null && resolvedRounding != configured) return@atomic conflict()
            if (!row.isNull(11)) return@atomic ContractResult.Success(receipt(readResult(db, row.getString(11))))
            if (row.getLong(3) != row.getLong(2)) return@atomic conflict()
            val numerator = BigDecimal(row.getString(5)) * weightedMillis
            val rounding = configured ?: resolvedRounding ?: RoundingMode.UNNECESSARY
            val duration = (row.getLong(2) - row.getLong(1)).toBigDecimal()
            if (duration.signum() <= 0) return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
            val fare = try {
                numerator.divide(duration, 0, rounding)
            } catch (_: ArithmeticException) {
                return@atomic ContractResult.Failure(ContractError.POLICY_UNRESOLVED)
            }
            val amount = try {
                GameMoney((fare + bonus.units.toBigDecimal()).longValueExact())
            } catch (_: ArithmeticException) {
                return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
            }
            val change = GameChange(request, TransactionKind.RENTAL, store.source, RuleVersion(row.getString(0)),
                MoneyChange(MoneyDirection.CREDIT, amount))
            when (val result = store.record(db, change, JSONArray(listOf("settle", id.value)).toString())) {
                is ContractResult.Failure -> result
                is ContractResult.Success -> {
                    db.execSQL("UPDATE rentals SET phase='RETURNING',settlement_request=?,rounding=? WHERE id=?", arrayOf(request.value,rounding.name,id.value))
                    db.rawQuery("SELECT phase,settlement_request,rounding FROM rentals WHERE id=?",arrayOf(id.value)).use{r->
                        if(!r.moveToFirst()||r.text(0)!="RETURNING"||r.text(1)!=request.value||r.text(2)!=rounding.name)throw SQLiteException("대여 정산 실제 상태 쓰기 불일치")
                    }
                    val event = store.quests.onRentalCompleted(db, id, result.value)
                    if (event is ContractResult.Failure) return@atomic event
                    ContractResult.Success(receipt(result.value))
                }
            }
        }
    }

    fun finishReturn(id: RequestId, now: EpochMillis) = store.atomic { db ->
        db.rawQuery("SELECT return_end,accident,phase FROM rentals WHERE id=?", arrayOf(id.value)).use {
            if (!it.moveToFirst()) return@atomic missing()
            val phase = VehicleActivity.valueOf(it.getString(2))
            if (phase == VehicleActivity.RENTED || now.value < it.getLong(0)) return@atomic conflict()
            if (phase != VehicleActivity.RETURNING) return@atomic ContractResult.Success(phase)
            val next = if (it.getInt(1) == 1) VehicleActivity.NEEDS_REPAIR else VehicleActivity.IDLE
            if(db.update("rentals",android.content.ContentValues().apply{put("phase",next.name)},"id=? AND phase='RETURNING'",arrayOf(id.value))!=1)throw SQLiteException("대여 반납 무갱신")
            db.rawQuery("SELECT phase FROM rentals WHERE id=?",arrayOf(id.value)).use{r->if(!r.moveToFirst()||r.text(0)!=next.name)throw SQLiteException("대여 반납 실제 상태 쓰기 불일치")}
            ContractResult.Success(next)
        }
    }

    private fun installed(db: SQLiteDatabase) = db.rawQuery("SELECT facility FROM facilities", null).use {
        buildSet { while (it.moveToNext()) add(Facility.valueOf(it.getString(0))) }
    }

    private fun shop(db: SQLiteDatabase): ShopState {
        val owned = db.rawQuery("SELECT payload FROM vehicles", null).use {
            var count = 0
            while (it.moveToNext()) if (LedgerJson.vehicle(JSONObject(it.getString(0))).lifecycle == VehicleLifecycle.OWNED) count++
            count
        }
        val served = db.rawQuery("SELECT count(*) FROM visits", null).use { it.moveToFirst(); it.getInt(0) }
        return db.rawQuery("SELECT level,capacity FROM shop WHERE id=1",null).use {
            if (!it.moveToFirst()) throw android.database.sqlite.SQLiteException("가게 상태 누락")
            ShopState(ShopProgress(owned, served, it.getInt(0)), it.getInt(1), installed(db))
        }
    }

    fun shop() = store.atomic { ContractResult.Success(shop(it)) }

    fun buy(id: RequestId, offer: FacilityOffer) = purchase(id, offer.ruleVersion, offer.price,
        listOf("facility", offer.facility.name, offer.trial)) { db, state, balance ->
        offer.planPurchase(state.progress, state.facilities, balance).also {
            if (it is ContractResult.Success) db.execSQL("INSERT INTO facilities VALUES (?)", arrayOf(offer.facility.name))
        }
    }

    fun expand(id: RequestId, offer: ShopExpansion) = purchase(id, offer.ruleVersion, offer.price,
        listOf("expand", offer.fromLevel, offer.currentCapacity, offer.nextCapacity, offer.requiredCustomers, offer.trial)) { db, state, balance ->
        val completed = completedRentalCount(db)
        offer.preview(state.progress.copy(servedCustomers = completed), state.capacity, balance).also {
            if (it is ContractResult.Success) db.execSQL("UPDATE shop SET level=?,capacity=? WHERE id=1",
                arrayOf(offer.fromLevel + 1, offer.nextCapacity))
        }
    }

    private fun purchase(id: RequestId, rules: RuleVersion, price: GameMoney, terms: List<Any>,
                         apply: (SQLiteDatabase, ShopState, GameMoney) -> ContractResult<*>) = store.atomic { db ->
        val command = JSONArray(terms + listOf(rules.value, price.units)).toString()
        db.rawQuery("SELECT command,result FROM settlements WHERE request_id=?", arrayOf(id.value)).use {
            if (it.moveToFirst()) return@atomic if (it.getString(0)==command)
                ContractResult.Success(LedgerJson.settlement(JSONObject(it.getString(1)))) else conflict()
        }
        val result = apply(db, shop(db), store.readBalance(db))
        if (result is ContractResult.Failure) return@atomic result
        store.record(db, GameChange(id, TransactionKind.FACILITY, store.source, rules,
            MoneyChange(MoneyDirection.DEBIT, price)), command)
    }

    private fun readResult(db: SQLiteDatabase, id: String) =
        db.rawQuery("SELECT result FROM settlements WHERE request_id=?",arrayOf(id)).use {
            if (!it.moveToFirst()) throw android.database.sqlite.SQLiteException("대여 정산 누락")
            LedgerJson.settlement(JSONObject(it.getString(0)))
        }

    private val Boolean.bit get() = if (this) 1 else 0
}

/** 기존 assign의 원문 형식을 그대로 공유한다. 별도 거래/차량 모델이 아니다. */
internal object RentalVisitCommand {
    fun encode(v:io.drivegrowth.app.runtime.rental.RentalVisit)=encode(v.request,v.catalog,v.categories,v.hourlyRates,v.terms)
    fun encode(request:RentalRequest,catalog:VehicleCatalogSnapshot,categories:Map<VehicleId,RentalCategory>,hourlyRates:Map<VehicleId,GameMoney>,terms:RentalTerms):String {
        return JSONArray(listOf(request.desired.value, request.hours, catalog.contentVersion.value,
            terms.rules.value, terms.customer.age, terms.customer.licensedMonths, terms.roll, terms.start.value,
            terms.returnMinutes, terms.fuelBonus.units, terms.rounding?.name, terms.regularCustomer,
            categories.entries.sortedBy { it.key.value }.map { listOf(it.key.value, it.value.name) },
            hourlyRates.entries.sortedBy { it.key.value }.map { listOf(it.key.value, it.value.units) })).toString()
    }
}
