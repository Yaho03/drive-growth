package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** 저장 측이 명시적으로 공급하는 버전. 호출자가 보낸 명령의 버전을 그대로 권위로 삼지 않는다. */
data class MaterialVersions(val contentVersion: ContentVersion, val ruleVersion: RuleVersion)

/** 기존 지갑·차량·정산을 사용하며 두 개체와 최초 결과를 한 SQLite 거래에서 확정한다. */
internal class MaterialLedger(private val store: SqliteGameChangeStore) {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE material_requests (request_id TEXT PRIMARY KEY NOT NULL, command TEXT NOT NULL, original_state TEXT NOT NULL, result TEXT NOT NULL)")
        }
    }

    private fun state(db: SQLiteDatabase, target: OwnedVehicleId, material: OwnedVehicleId): ContractResult<MaterialState> {
        val versions = when (val supplied = store.materialVersions?.invoke()) {
            null -> return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            is ContractResult.Failure -> return supplied
            is ContractResult.Success -> supplied.value
        }
        fun vehicle(id: OwnedVehicleId): OwnedVehicle? = store.findVehicle(db, id)?.also {
            if (it.ownedVehicleId != id) throw JSONException("재료 개체 키 불일치")
        }
        val a = vehicle(target) ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val b = vehicle(material) ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        // RentalLedger의 현재 활동을 같은 DB 거래 안에서 조회한다. 영업 쉬기는 활동 IDLE을 대신하지 않는다.
        return ContractResult.Success(MaterialState(store.source, versions.contentVersion, versions.ruleVersion,
            store.readBalance(db), MaterialVehicleState(a, store.rental.activity(db, target)),
            MaterialVehicleState(b, store.rental.activity(db, material))))
    }

    fun readState(target: OwnedVehicleId, material: OwnedVehicleId) = store.atomic { db -> state(db, target, material) }

    fun commit(command: MaterialCommand): ContractResult<MaterialSettlement> = store.atomic { db ->
        val encoded = MaterialJson.command(command).toString()
        db.rawQuery("SELECT command,original_state,result FROM material_requests WHERE request_id=?",
            arrayOf(command.requestId.value)).use { row ->
            if (row.moveToFirst()) {
                val originalCommand = MaterialJson.command(JSONObject(row.text(0)))
                if (MaterialJson.command(originalCommand).toString() != row.text(0) || originalCommand.requestId != command.requestId)
                    throw JSONException("재료 명령 형식·키 불일치")
                val originalResult = replay(db, originalCommand, row.text(1), row.text(2))
                if (row.text(0) != encoded) return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
                return@atomic ContractResult.Success(originalResult)
            }
        }
        // 자기 공용 정산만 남았다면 최초 두 snapshot 이력이 손실된 상태다. 현재 개체로 복구하지 않는다.
        db.rawQuery("SELECT command FROM settlements WHERE request_id=?", arrayOf(command.requestId.value)).use { row ->
            if (row.moveToFirst() && JSONObject(row.text(0)).optString("type") == "material-v1")
                throw JSONException("재료 최초 이력 누락")
        }
        if (store.requestUnavailable(db, command.requestId)) return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
        val current = when (val read = state(db, command.target.ownedVehicleId, command.material.ownedVehicleId)) {
            is ContractResult.Failure -> return@atomic read
            is ContractResult.Success -> read.value
        }
        val prepared = when (val result = prepareMaterialChange(command, current)) {
            is ContractResult.Failure -> return@atomic result
            is ContractResult.Success -> result.value
        }
        val result = MaterialSettlement(command, SettlementId(UUID.randomUUID().toString()),
            Transaction(TransactionId(UUID.randomUUID().toString()), command.requestId, TransactionKind.GARAGE,
                command.source, command.ruleVersion, prepared.money), prepared.balance, prepared.target, prepared.material, store.now())
        db.execSQL("UPDATE account SET balance=? WHERE id=1", arrayOf(result.balance.units))
        store.writeVehicle(db, result.target)
        store.writeVehicle(db, result.material)
        // 공용 정산에는 거래 메타데이터를, 역할별 두 snapshot은 material_requests의 전체 결과에 보존한다.
        store.insertSettlement(db, command.requestId, encoded, MaterialJson.receipt(result))
        db.execSQL("INSERT INTO material_requests VALUES (?,?,?,?)", arrayOf(command.requestId.value, encoded,
            MaterialJson.state(current).toString(), MaterialJson.result(result).toString()))
        ContractResult.Success(result)
    }

    private fun replay(db: SQLiteDatabase, command: MaterialCommand, original: String, saved: String): MaterialSettlement {
        val before = MaterialJson.state(JSONObject(original))
        if (MaterialJson.state(before).toString() != original || before.source != store.source)
            throw JSONException("재료 원상태 형식·출처 불일치")
        val prepared = when (val value = prepareMaterialChange(command, before)) {
            is ContractResult.Failure -> throw JSONException("재료 원명령 검증 실패")
            is ContractResult.Success -> value.value
        }
        val json = JSONObject(saved)
        val receipt = MaterialJson.receipt(json.getJSONObject("receipt"))
        val a = LedgerJson.vehicle(json.getJSONObject("target"))
        val b = LedgerJson.vehicle(json.getJSONObject("material"))
        val result = MaterialSettlement(command, receipt.settlementId, receipt.transaction, receipt.balance, a, b, receipt.confirmedAt)
        if (MaterialJson.result(result).toString() != saved || receipt.changedVehicle != null ||
            receipt.transaction.requestId != command.requestId || receipt.transaction.source != store.source ||
            receipt.transaction.kind != TransactionKind.GARAGE || receipt.transaction.ruleVersion != command.ruleVersion ||
            receipt.transaction.money != prepared.money || receipt.balance != prepared.balance ||
            a != prepared.target || b != prepared.material) throw JSONException("재료 최초 결과 연결 불일치")
        db.rawQuery("SELECT settlement_id,command,result FROM settlements WHERE request_id=?",
            arrayOf(command.requestId.value)).use { row ->
            if (!row.moveToFirst() || row.text(0) != receipt.settlementId.value ||
                row.text(1) != MaterialJson.command(command).toString() ||
                MaterialJson.receipt(JSONObject(row.text(2))) != receipt ||
                row.text(2) != LedgerJson.settlement(receipt).toString()) throw JSONException("재료 공용 정산 누락·불일치")
        }
        return result
    }
}

/** 공용 차량·정수·금액 decoder를 재사용한다. 문자열/소수/지수의 정수 보정은 허용하지 않는다. */
internal object MaterialJson {
    private fun update(v: VehicleWrite.Update) = JSONObject().put("id", v.ownedVehicleId.value)
        .put("revision", v.expectedRevision.value).put("level", v.level).put("lifecycle", v.lifecycle.name)
        .put("progress", v.levelProgressPercent)
    private fun update(j: JSONObject) = VehicleWrite.Update(OwnedVehicleId(j.getString("id")),
        Revision(LedgerJson.integer(j, "revision", 0, Int.MAX_VALUE.toLong()).toInt()),
        LedgerJson.integer(j, "level", 1, MAX_VEHICLE_LEVEL.toLong()).toInt(), VehicleLifecycle.valueOf(j.getString("lifecycle")),
        LedgerJson.integer(j, "progress", 0, 99).toInt())
    fun command(j: JSONObject): MaterialCommand {
        if (j.getString("type") != "material-v1") throw JSONException("재료 명령 종류 오류")
        return MaterialCommand(RequestId(j.getString("request")), InputSource.valueOf(j.getString("source")),
            ContentVersion(j.getString("content")), RuleVersion(j.getString("rule")), update(j.getJSONObject("target")),
            update(j.getJSONObject("material")), GameMoney(LedgerJson.integer(j, "cost", 0, Long.MAX_VALUE)))
    }
    fun command(c: MaterialCommand) = JSONObject().put("type", "material-v1").put("request", c.requestId.value)
        .put("source", c.source.name).put("content", c.contentVersion.value).put("rule", c.ruleVersion.value)
        .put("target", update(c.target)).put("material", update(c.material)).put("cost", c.cost.units)
    private fun participant(s: MaterialVehicleState) = JSONObject().put("vehicle", LedgerJson.vehicle(s.vehicle)).put("activity", s.activity.name)
    fun state(s: MaterialState) = JSONObject().put("source", s.source.name).put("content", s.contentVersion.value)
        .put("rule", s.ruleVersion.value).put("balance", s.balance.units)
        .put("target", participant(s.target)).put("material", participant(s.material))
    private fun participant(j: JSONObject) = MaterialVehicleState(LedgerJson.vehicle(j.getJSONObject("vehicle")),
        VehicleActivity.valueOf(j.getString("activity")))
    fun state(j: JSONObject) = MaterialState(InputSource.valueOf(j.getString("source")), ContentVersion(j.getString("content")),
        RuleVersion(j.getString("rule")), GameMoney(LedgerJson.integer(j, "balance", 0, Long.MAX_VALUE)),
        participant(j.getJSONObject("target")), participant(j.getJSONObject("material")))
    fun receipt(s: MaterialSettlement) = Settlement(s.settlementId, s.transaction, s.balance, null, s.confirmedAt)
    fun receipt(j: JSONObject): Settlement {
        // LedgerJson의 공통 금액/차량 검증에 더해 이 경로의 잔액·시간도 변환 전에 검증한다.
        LedgerJson.integer(j, "balance", 0, Long.MAX_VALUE)
        LedgerJson.integer(j, "at", Long.MIN_VALUE, Long.MAX_VALUE)
        if (j.get("vehicle") != JSONObject.NULL) throw JSONException("재료 공용 정산에 단일 개체 결과가 있음")
        return LedgerJson.settlement(j)
    }
    fun result(s: MaterialSettlement) = JSONObject().put("receipt", LedgerJson.settlement(receipt(s)))
        .put("target", LedgerJson.vehicle(s.target)).put("material", LedgerJson.vehicle(s.material))
}
