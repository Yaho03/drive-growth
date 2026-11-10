package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.progression.VehicleCare
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** 공용 DB의 현재 대여/사고와 명시 콘텐츠 견적을 같은 확정 경계에서 읽는다. 별도 지갑은 없다. */
internal class RepairLedger(private val store: SqliteGameChangeStore) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE repair_requests (request_id TEXT PRIMARY KEY NOT NULL, command TEXT NOT NULL, original_state TEXT NOT NULL, result TEXT NOT NULL)")
        }
    }

    private fun state(db: SQLiteDatabase, target: OwnedVehicleId): ContractResult<RepairState> {
        val content = when (val supplied = store.repairContent?.invoke()) {
            null -> return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            is ContractResult.Failure -> return supplied
            is ContractResult.Success -> supplied.value
        }
        val vehicle = store.findVehicle(db, target) ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (vehicle.ownedVehicleId != target) throw JSONException("수리 개체 키 불일치")
        val price = content.vehicles.find { it.vehicleId == vehicle.vehicleId }?.price
            ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val accidents = when (val read = store.rental.accidentCount(target)) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        val activity = store.rental.activity(db, target)
        if (activity == VehicleActivity.NEEDS_REPAIR) {
            db.rawQuery("SELECT id FROM rentals WHERE owned_id=? AND phase='NEEDS_REPAIR'",arrayOf(target.value)).use { row ->
                if (!row.moveToFirst()) throw SQLiteException("수리 필요 영업 누락")
                val completed = when (val read = store.rental.completedRental(RequestId(row.text(0)))) {
                    is ContractResult.Failure -> return read
                    is ContractResult.Success -> read.value
                }
                if (completed == null || completed.ownedVehicleId != target || !completed.receipt.accident || row.moveToNext())
                    throw SQLiteException("수리 필요 활동과 확정 사고 연결 불일치")
            }
        }
        val cost = try { GameMoney(VehicleCare().repairCost(price.units, vehicle.rarity)) }
            catch (_: ArithmeticException) { return ContractResult.Failure(ContractError.INVALID_INPUT) }
        return ContractResult.Success(RepairState(store.source, content.catalog.contentVersion, content.ruleVersion,
            store.readBalance(db), vehicle, activity, accidents, cost))
    }

    fun readState(target: OwnedVehicleId) = store.atomic { db -> state(db, target) }

    fun settlement(id: RequestId): ContractResult<RepairSettlement?> = store.atomic { db ->
        val original = recorded(db, id)
        if (original == null && store.requestUnavailable(db, id)) ContractResult.Failure(ContractError.STATE_CONFLICT)
        else ContractResult.Success(original)
    }

    fun commit(command: RepairCommand): ContractResult<RepairSettlement> = store.atomic { db ->
        recorded(db, command.requestId)?.let { original ->
            return@atomic when (val replay = replayRepairChange(command, original)) {
                is ContractResult.Failure -> replay
                is ContractResult.Success -> ContractResult.Success(checkNotNull(replay.value))
            }
        }
        if (store.requestUnavailable(db, command.requestId)) return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
        if (command.source != store.source) return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        val current = when (val read = state(db, command.target.ownedVehicleId)) {
            is ContractResult.Failure -> return@atomic read
            is ContractResult.Success -> read.value
        }
        val prepared = when (val read = prepareRepairChange(command, current)) {
            is ContractResult.Failure -> return@atomic read
            is ContractResult.Success -> read.value
        }
        val result = RepairSettlement(command, SettlementId(UUID.randomUUID().toString()),
            Transaction(TransactionId(UUID.randomUUID().toString()), command.requestId, TransactionKind.GARAGE,
                command.source, command.ruleVersion, prepared.money), prepared.balance, prepared.vehicle,
            prepared.activity, prepared.accidentCount, store.now())
        db.execSQL("UPDATE account SET balance=? WHERE id=1", arrayOf(result.balance.units))
        store.writeVehicle(db, result.vehicle)
        // 사고 영업은 지우지 않는다. 현재 NEEDS_REPAIR 한 건의 활동만 회복한다.
        val changed = db.update("rentals", ContentValues().apply { put("phase", VehicleActivity.IDLE.name) },
            "owned_id=? AND phase=?", arrayOf(result.vehicle.ownedVehicleId.value, VehicleActivity.NEEDS_REPAIR.name))
        if (changed != 1) throw SQLiteException("수리 활동 갱신 대상 불일치")
        val encoded = RepairJson.command(command).toString()
        store.insertSettlement(db, command.requestId, encoded, RepairJson.receipt(result))
        db.execSQL("INSERT INTO repair_requests VALUES(?,?,?,?)", arrayOf(command.requestId.value, encoded,
            RepairJson.state(current).toString(), RepairJson.result(result).toString()))
        ContractResult.Success(result)
    }

    private fun recorded(db: SQLiteDatabase, id: RequestId): RepairSettlement? {
        db.rawQuery("SELECT command,original_state,result FROM repair_requests WHERE request_id=?", arrayOf(id.value)).use { row ->
            if (row.moveToFirst()) {
                val encoded = row.text(0)
                val command = RepairJson.command(JSONObject(encoded))
                if (command.requestId != id || command.source != store.source || RepairJson.command(command).toString() != encoded)
                    throw JSONException("수리 명령 형식·키·출처 불일치")
                val beforeText = row.text(1)
                val before = RepairJson.state(JSONObject(beforeText))
                if (RepairJson.state(before).toString() != beforeText) throw JSONException("수리 원상태 형식 오류")
                val prepared = (prepareRepairChange(command, before) as? ContractResult.Success)?.value
                    ?: throw JSONException("수리 원명령 검증 실패")
                val savedText = row.text(2)
                val result = RepairJson.result(command, JSONObject(savedText))
                if (RepairJson.result(result).toString() != savedText || before.source != store.source ||
                    result.balance != prepared.balance || result.vehicle != prepared.vehicle ||
                    result.activity != prepared.activity || result.accidentCount != prepared.accidentCount ||
                    replayRepairChange(command, result) is ContractResult.Failure)
                    throw JSONException("수리 최초 결과 연결 불일치")
                db.rawQuery("SELECT settlement_id,command,result FROM settlements WHERE request_id=?",arrayOf(id.value)).use { receipt ->
                    if (!receipt.moveToFirst() || receipt.text(0) != result.settlementId.value || receipt.text(1) != encoded ||
                        receipt.text(2) != LedgerJson.settlement(RepairJson.receipt(result)).toString())
                        throw JSONException("수리 공용 정산 누락·불일치")
                }
                return result
            }
        }
        // 공용 정산만 남은 수리를 미확정/다른 종류로 처리하면 다시 차감하거나 손상을 숨길 수 있다.
        db.rawQuery("SELECT command FROM settlements WHERE request_id=?",arrayOf(id.value)).use { row ->
            if (row.moveToFirst() && row.text(0).startsWith("{") && JSONObject(row.text(0)).optString("type") == "repair-v1")
                throw JSONException("수리 전체 결과 누락")
        }
        return null
    }
}

/** 숫자 절삭/기본값 없이 공통 정수·차량 decoder를 재사용한다. 과거 견적은 당시 상태에서 검증한다. */
internal object RepairJson {
    fun command(c: RepairCommand) = JSONObject().put("type","repair-v1").put("request",c.requestId.value)
        .put("source",c.source.name).put("content",c.contentVersion.value).put("rule",c.ruleVersion.value)
        .put("target",c.target.ownedVehicleId.value).put("revision",c.target.revision.value)
        .put("accidents",c.expectedAccidentCount).put("cost",c.cost.units)
    fun command(j: JSONObject): RepairCommand {
        if (j.getString("type") != "repair-v1") throw JSONException("수리 명령 종류 오류")
        return RepairCommand(RequestId(j.getString("request")),InputSource.valueOf(j.getString("source")),
            ContentVersion(j.getString("content")),RuleVersion(j.getString("rule")),VehicleRef(OwnedVehicleId(j.getString("target")),
                Revision(LedgerJson.integer(j,"revision",0,Int.MAX_VALUE.toLong()).toInt())),
            LedgerJson.integer(j,"accidents",0,Int.MAX_VALUE.toLong()).toInt(),GameMoney(LedgerJson.integer(j,"cost",0,Long.MAX_VALUE)))
    }
    fun state(s: RepairState) = JSONObject().put("source",s.source.name).put("content",s.contentVersion.value)
        .put("rule",s.ruleVersion.value).put("balance",s.balance.units).put("vehicle",LedgerJson.vehicle(s.vehicle))
        .put("activity",s.activity.name).put("accidents",s.accidentCount).put("cost",s.repairCost.units)
    fun state(j: JSONObject) = RepairState(InputSource.valueOf(j.getString("source")),ContentVersion(j.getString("content")),
        RuleVersion(j.getString("rule")),GameMoney(LedgerJson.integer(j,"balance",0,Long.MAX_VALUE)),
        LedgerJson.vehicle(j.getJSONObject("vehicle")),VehicleActivity.valueOf(j.getString("activity")),
        LedgerJson.integer(j,"accidents",0,Int.MAX_VALUE.toLong()).toInt(),GameMoney(LedgerJson.integer(j,"cost",0,Long.MAX_VALUE)))
    fun receipt(s: RepairSettlement) = Settlement(s.settlementId,s.transaction,s.balance,s.vehicle,s.confirmedAt)
    fun result(s: RepairSettlement) = JSONObject().put("receipt",LedgerJson.settlement(receipt(s)))
        .put("activity",s.activity.name).put("accidents",s.accidentCount)
    fun result(c: RepairCommand,j: JSONObject): RepairSettlement {
        val r = j.getJSONObject("receipt")
        LedgerJson.integer(r,"balance",0,Long.MAX_VALUE);LedgerJson.integer(r,"at",Long.MIN_VALUE,Long.MAX_VALUE)
        val receipt = LedgerJson.settlement(r)
        return RepairSettlement(c,receipt.settlementId,receipt.transaction,receipt.balance,
            receipt.changedVehicle ?: throw JSONException("수리 개체 결과 누락"),VehicleActivity.valueOf(j.getString("activity")),
            LedgerJson.integer(j,"accidents",0,Int.MAX_VALUE.toLong()).toInt(),receipt.confirmedAt)
    }
}
