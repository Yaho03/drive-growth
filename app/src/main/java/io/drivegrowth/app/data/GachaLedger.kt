package io.drivegrowth.app.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.core.contract.*
import org.json.JSONObject

/** 가챠 진행과 공용 원장을 같은 SQLite 거래에서 확정한다. */
internal class GachaLedger(private val store: SqliteGameChangeStore) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE gacha_progress (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, counter INTEGER NOT NULL, tickets INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE gacha_requests (request_id TEXT PRIMARY KEY NOT NULL, command TEXT NOT NULL, settlement_id TEXT NOT NULL, revision INTEGER NOT NULL, counter INTEGER NOT NULL, tickets INTEGER NOT NULL)")
        }

        /** 새 DB를 생성할 때 신뢰한 프로필 초기화 입력이 명시된 경우에만 사용한다. */
        fun insertInitialProgress(db: SQLiteDatabase, progress: GachaProgress) {
            db.execSQL("INSERT INTO gacha_progress VALUES (1,?,?,?)",
                arrayOf(progress.revision.value, progress.drawsSinceGuarantee, progress.freeTickets))
        }

        fun capacityIfConfigured(db: SQLiteDatabase): Int? =
            db.rawQuery("SELECT capacity FROM shop WHERE id=1 AND EXISTS(SELECT 1 FROM gacha_progress WHERE id=1)", null).use {
                if (it.moveToFirst()) integer(it, 0, Int.MAX_VALUE.toLong()).toInt() else null
            }

        private fun integer(row: Cursor, column: Int, max: Long): Long {
            if (row.getType(column) != Cursor.FIELD_TYPE_INTEGER) throw SQLiteException("가챠 정수 형식 오류")
            return row.getLong(column).also { if (it !in 0..max) throw SQLiteException("가챠 정수 범위 오류") }
        }

        private fun progress(row: Cursor, start: Int) = GachaProgress(
            Revision(integer(row, start, Int.MAX_VALUE.toLong()).toInt()),
            integer(row, start + 1, Int.MAX_VALUE.toLong()).toInt(), integer(row, start + 2, Long.MAX_VALUE))
    }

    private fun failure(error: ContractError) = ContractResult.Failure(error)

    private fun state(db: SQLiteDatabase): ContractResult<GachaState> {
        val provided = when (val result = store.gachaAuthority?.invoke()) {
            null -> return failure(ContractError.DATA_UNAVAILABLE)
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        if (provided.source != store.source) return failure(ContractError.INVALID_INPUT)
        val saved = db.rawQuery("SELECT revision,counter,tickets FROM gacha_progress WHERE id=1", null).use {
            if (it.moveToFirst()) progress(it, 0) else return failure(ContractError.DATA_UNAVAILABLE)
        }
        val shop = db.rawQuery("SELECT level,capacity FROM shop WHERE id=1", null).use {
            if (!it.moveToFirst()) throw SQLiteException("가게 상태 누락")
            integer(it, 0, Int.MAX_VALUE.toLong()).toInt() to integer(it, 1, Int.MAX_VALUE.toLong()).toInt()
        }
        if (provided.shopLevel != shop.first || provided.garageCapacity != shop.second)
            return failure(ContractError.STATE_CONFLICT)
        if (saved.drawsSinceGuarantee >= provided.pity) throw SQLiteException("가챠 카운터 범위 오류")
        return ContractResult.Success(GachaState(provided, saved))
    }

    fun readState() = store.readResult(::state)

    fun commit(command: GachaCommand): ContractResult<GachaSettlement> = store.atomic { db ->
        val encoded = encode(command)
        db.rawQuery("""SELECT g.command,g.settlement_id,g.revision,g.counter,g.tickets,s.result,s.command,s.settlement_id
            FROM gacha_requests g LEFT JOIN settlements s ON s.request_id=g.request_id WHERE g.request_id=?""",
            arrayOf(command.requestId.value)).use { row ->
            if (row.moveToFirst()) {
                if (row.getString(0) != encoded) return@atomic failure(ContractError.STATE_CONFLICT)
                if (row.isNull(5)) throw SQLiteException("가챠 원장 누락")
                val settlement = LedgerJson.settlement(JSONObject(row.getString(5)))
                if (settlement.settlementId.value != row.getString(1) || settlement.transaction.requestId != command.requestId ||
                    settlement.transaction.source != store.source || settlement.transaction.kind != TransactionKind.GARAGE ||
                    settlement.transaction.ruleVersion != command.ruleVersion || settlement.changedVehicle != command.award.vehicle)
                    throw SQLiteException("가챠 정산 연결 오류")
                val savedProgress = progress(row, 2)
                val expectedMoney = if (command.ticket == GachaTicketUse.PURCHASED)
                    MoneyChange(MoneyDirection.DEBIT, command.award.vehicle.acquisitionCost) else null
                if (row.isNull(6) || row.getString(6) != encoded || row.isNull(7) ||
                    row.getString(7) != settlement.settlementId.value ||
                    savedProgress.revision.value.toLong() != command.expectedRevision.value.toLong() + 1 ||
                    savedProgress.drawsSinceGuarantee != command.award.nextCounter ||
                    settlement.transaction.money != expectedMoney ||
                    (command.ticket == GachaTicketUse.FREE && command.award.vehicle.acquisitionCost.units != 0L))
                    throw SQLiteException("가챠 최초 명령·결제·진행 연결 오류")
                return@atomic ContractResult.Success(GachaSettlement(command, settlement, savedProgress))
            }
        }
        if (store.requestUnavailable(db, command.requestId))
            return@atomic failure(ContractError.STATE_CONFLICT)
        val saved = when (val result = state(db)) {
            is ContractResult.Failure -> return@atomic result
            is ContractResult.Success -> result.value
        }
        val authority = saved.authority
        val p = saved.progress
        if (command.source != store.source) return@atomic failure(ContractError.INVALID_INPUT)
        if (command.contentVersion != authority.contentVersion || command.ruleVersion != authority.ruleVersion ||
            command.expectedRevision != p.revision || command.drawsSinceGuarantee != p.drawsSinceGuarantee ||
            command.shopLevel != authority.shopLevel || command.garageCapacity != authority.garageCapacity)
            return@atomic failure(ContractError.STATE_CONFLICT)
        val vehicles = when (val result = store.listOwnedVehicles()) {
            is ContractResult.Failure -> return@atomic result
            is ContractResult.Success -> result.value
        }
        if (vehicles.size >= authority.garageCapacity) return@atomic failure(ContractError.CAPACITY_EXCEEDED)
        if (p.revision.value == Int.MAX_VALUE) return@atomic failure(ContractError.INVALID_INPUT)
        if (authority.unlockedVehicles.isEmpty()) return@atomic failure(ContractError.DATA_UNAVAILABLE)
        val award = command.award
        val v = award.vehicle
        val price = if (command.ticket == GachaTicketUse.PURCHASED) authority.purchasePrice else GameMoney(0)
        val guaranteed = p.drawsSinceGuarantee + 1 == authority.pity
        val nextCounter = if (v.rarity >= authority.guaranteedFrom) 0 else p.drawsSinceGuarantee + 1
        if (v.vehicleId !in authority.unlockedVehicles || v.rarity !in authority.eligibleRarities ||
            v.revision != Revision(0) || v.lifecycle != VehicleLifecycle.OWNED ||
            v.acquisitionSource != VehicleAcquisitionSource.GACHA || v.level != 1 || v.levelProgressPercent != 0 ||
            v.acquisitionCost != price || award.guaranteed != guaranteed || award.nextCounter != nextCounter ||
            (guaranteed && v.rarity < authority.guaranteedFrom)) return@atomic failure(ContractError.INVALID_INPUT)
        if (command.ticket == GachaTicketUse.FREE && p.freeTickets == 0L) return@atomic failure(ContractError.INSUFFICIENT_FUNDS)
        val next = GachaProgress(Revision(p.revision.value + 1), nextCounter,
            p.freeTickets - if (command.ticket == GachaTicketUse.FREE) 1 else 0)
        val change = GameChange(command.requestId, TransactionKind.GARAGE, command.source, command.ruleVersion,
            if (command.ticket == GachaTicketUse.PURCHASED) MoneyChange(MoneyDirection.DEBIT, price) else null, VehicleWrite.Add(v))
        when (val result = store.record(db, change, encoded, trackAcquisition = true)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> {
                db.execSQL("UPDATE gacha_progress SET revision=?,counter=?,tickets=? WHERE id=1",
                    arrayOf(next.revision.value, next.drawsSinceGuarantee, next.freeTickets))
                db.execSQL("INSERT INTO gacha_requests VALUES (?,?,?,?,?,?)",
                    arrayOf(command.requestId.value, encoded, result.value.settlementId.value,
                        next.revision.value, next.drawsSinceGuarantee, next.freeTickets))
                ContractResult.Success(GachaSettlement(command, result.value, next))
            }
        }
    }

    private fun encode(c: GachaCommand): String = JSONObject().put("type", "gacha-v1")
        .put("request", c.requestId.value).put("source", c.source.name).put("content", c.contentVersion.value)
        .put("rules", c.ruleVersion.value).put("revision", c.expectedRevision.value).put("shopLevel", c.shopLevel)
        .put("capacity", c.garageCapacity).put("counter", c.drawsSinceGuarantee).put("ticket", c.ticket.name)
        .put("vehicle", LedgerJson.vehicle(c.award.vehicle)).put("next", c.award.nextCounter).put("guaranteed", c.award.guaranteed).toString()
}
