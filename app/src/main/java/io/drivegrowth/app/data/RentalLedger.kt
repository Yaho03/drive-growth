package io.drivegrowth.app.data

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
        val command = JSONArray(listOf(request.desired.value, request.hours, catalog.contentVersion.value,
            terms.rules.value, terms.customer.age, terms.customer.licensedMonths, terms.roll, terms.start.value,
            terms.returnMinutes, terms.fuelBonus.units, terms.rounding?.name, terms.regularCustomer,
            categories.entries.sortedBy { it.key.value }.map { listOf(it.key.value, it.value.name) },
            hourlyRates.entries.sortedBy { it.key.value }.map { listOf(it.key.value, it.value.units) })).toString()
        db.rawQuery("SELECT command,assigned FROM visits WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) return@atomic if (it.getString(0) == command)
                ContractResult.Success(it.getString(1)?.let(::OwnedVehicleId)) else conflict()
        }
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
        db.execSQL("INSERT INTO visits VALUES(?,?,?)", arrayOf(id, command, selected?.value))
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
        db.execSQL("UPDATE vehicles SET payload=? WHERE owned_id=?", arrayOf(
            LedgerJson.vehicle(v.copy(revision = Revision(v.revision.value + 1))).toString(), v.ownedVehicleId.value))
        ContractResult.Success(selected)
    }

    /** 호출자가 확인한 연속 주행/주차 구간. 화면 가시성만으로 주행을 추정하지 않는다. */
    fun account(id: RequestId, from: EpochMillis, to: EpochMillis, parkedSince: EpochMillis?) = store.atomic { db ->
        if (from.value < 0 || to.value < 0 || (parkedSince != null && parkedSince.value !in 0..from.value))
            return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        db.rawQuery("SELECT cursor,end,weighted,parked_since FROM rentals WHERE id=?", arrayOf(id.value)).use {
            if (!it.moveToFirst()) return@atomic missing()
            val cursor = it.getLong(0)
            val end = minOf(it.getLong(1), to.value)
            if (end <= cursor || to.value < from.value) return@atomic ContractResult.Success(EpochMillis(cursor))
            if (from.value > cursor) return@atomic conflict()
            if (parkedSince != null && !it.isNull(3) && it.getLong(3) != parkedSince.value) return@atomic conflict()
            val increment = parkedSince?.let { start -> parkingIncomeWindow(start, EpochMillis(cursor), EpochMillis(end)).equivalentMillis() }
                ?: (end - cursor).toBigDecimal()
            db.execSQL("UPDATE rentals SET cursor=?, weighted=?, parked_since=? WHERE id=?",
                arrayOf<Any?>(end, (it.getString(2).toBigDecimal() + increment).toPlainString(), parkedSince?.value, id.value))
            ContractResult.Success(EpochMillis(end))
        }
    }

    /** 앱 재실행 시 이어갈 대여와 마지막 반영 시각. */
    data class Pending(val id: RequestId, val vehicle: OwnedVehicleId, val phase: VehicleActivity,
                       val cursor: EpochMillis, val end: EpochMillis, val returnEnd: EpochMillis)

    fun pending() = store.atomic { db ->
        db.rawQuery("SELECT id,owned_id,phase,cursor,end,return_end FROM rentals WHERE phase IN ('RENTED','RETURNING') ORDER BY start,id", null).use {
            ContractResult.Success(buildList {
                while (it.moveToNext()) add(Pending(RequestId(it.getString(0)), OwnedVehicleId(it.getString(1)),
                    VehicleActivity.valueOf(it.getString(2)), EpochMillis(it.getLong(3)), EpochMillis(it.getLong(4)), EpochMillis(it.getLong(5))))
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

    private fun countSettledAccidents(db: SQLiteDatabase, ownedVehicleId: OwnedVehicleId): Int =
        db.rawQuery("SELECT count(*) FROM rentals WHERE owned_id=? AND accident=1 AND settlement_request IS NOT NULL",
            arrayOf(ownedVehicleId.value)).use { rows ->
            rows.moveToFirst()
            rows.getInt(0)
        }

    private fun completed(id: RequestId?): ContractResult<CompletedRental?> = store.read { db ->
        val filter = if (id == null) "" else " AND r.id=?"
        db.rawQuery("""SELECT r.id,r.owned_id,s.result,r.accident,r.match_review,r.wash_review,
            r.bonus,r.weighted,r.regular_customer FROM rentals r
            LEFT JOIN settlements s ON s.request_id=r.settlement_request
            WHERE r.settlement_request IS NOT NULL$filter ORDER BY s.rowid LIMIT 1""",
            id?.let { arrayOf(it.value) }).use { row ->
            if (!row.moveToFirst()) return@read null
            if (row.isNull(2)) throw android.database.sqlite.SQLiteException("대여 정산 누락")
            val settlement = LedgerJson.settlement(JSONObject(row.getString(2)))
            val bonus = if (BigDecimal(row.getString(7)).signum() == 0) GameMoney(0) else GameMoney(row.getLong(6))
            CompletedRental(RequestId(row.getString(0)), OwnedVehicleId(row.getString(1)),
                RentalReceipt(settlement, row.getInt(3) == 1, row.getInt(4) == 1, row.getInt(5) == 1,
                    bonus, if (row.isNull(8)) null else row.getInt(8) == 1))
        }
    }

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
            db.execSQL("UPDATE rentals SET phase=? WHERE id=?", arrayOf(next.name, id.value))
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
        offer.preview(state.progress, state.capacity, balance).also {
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
