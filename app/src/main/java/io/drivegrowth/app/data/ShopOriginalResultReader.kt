package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.DecorationSettlement
import io.drivegrowth.core.rental.ShopExpansion
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 완료 slot 뒤 남은 UI 요청을 원결과로만 복원한다. 새 명령/활성화/slot 정리를 수행하지 않는다. */
class ShopOriginalResultReader(private val store: SqliteGameChangeStore) {
    fun decoration(profileId: ProfileId, requestId: RequestId): ContractResult<DecorationSettlement?> = store.atomic { db ->
        when (val valid = identity(db, profileId)) { is ContractResult.Failure -> return@atomic valid; else -> Unit }
        DecorationStorage.read(store, db, profileId)
        val original = db.rawQuery("SELECT payload FROM decoration_requests WHERE request_id=?", arrayOf(requestId.value)).use {
            if (!it.moveToFirst()) null else DecorationJson.record(db, it.text(0), profileId, store.source).second
        }
        if (original != null && original.command.requestId != requestId) throw SQLiteException("꾸미기 조회의 원요청 키 손상")
        when (val slot = matchingSlot(profileId, requestId) { command ->
            command is HostCheckpointCommand.Decoration && command.value.profileId == profileId &&
                (original == null || command.value == original.command)
        }) { is ContractResult.Failure -> return@atomic slot; else -> Unit }
        val foreign = if (original == null) store.requestUnavailable(db, requestId)
            else store.requestOwnedElsewhere(db, requestId, SqliteGameChangeStore.OriginalRequestOwner.DECORATION)
        if (foreign) conflict() else ContractResult.Success(original)
    }

    fun expansion(profileId: ProfileId, requestId: RequestId, offer: ShopExpansion): ContractResult<Settlement?> = store.atomic { db ->
        when (val valid = identity(db, profileId)) { is ContractResult.Failure -> return@atomic valid; else -> Unit }
        when (val slot = matchingSlot(profileId, requestId) { command ->
            command is HostCheckpointCommand.Expand && command.offer == offer
        }) { is ContractResult.Failure -> return@atomic slot; else -> Unit }
        db.rawQuery("SELECT settlement_id,command,result FROM settlements WHERE request_id=?", arrayOf(requestId.value)).use { row ->
            if (!row.moveToFirst()) return@atomic if (store.requestUnavailable(db, requestId)) conflict() else ContractResult.Success(null)
            val raw = row.text(2); val json = JSONObject(raw)
            LedgerJson.integer(json, "balance", 0, Long.MAX_VALUE); LedgerJson.integer(json, "at", Long.MIN_VALUE, Long.MAX_VALUE)
            val receipt = LedgerJson.settlement(json)
            if (LedgerJson.settlement(receipt).toString() != raw || receipt.settlementId.value != row.text(0) ||
                receipt.transaction.requestId != requestId || receipt.transaction.source != store.source) throw SQLiteException("확장 조회의 금융 원문/키/출처 손상")
            val commandRaw = row.text(1); val command = JSONTokener(commandRaw).nextValue()
            if (command !is JSONArray && command !is JSONObject || command.toString() != commandRaw) throw SQLiteException("확장 조회의 명령 원문 형식 손상")
            if (command !is JSONArray) return@atomic conflict()
            val kind = command.opt(0) as? String ?: throw SQLiteException("확장 조회의 명령 종류 손상")
            if (kind != "expand") return@atomic conflict()
            val originalOffer = expansionOffer(command)
            if (!matchesExpansionReceipt(requestId, store.source, originalOffer, receipt)) throw SQLiteException("확장 조회의 전체 명령/최초 정산 불일치")
            if (originalOffer != offer || store.requestOwnedElsewhere(db, requestId, SqliteGameChangeStore.OriginalRequestOwner.EXPANSION))
                conflict() else ContractResult.Success(receipt)
        }
    }

    private fun identity(db: SQLiteDatabase, expected: ProfileId): ContractResult<Unit> {
        when (val actual = store.localProfileId()) {
            is ContractResult.Failure -> return actual
            is ContractResult.Success -> if (actual.value != expected) return ContractResult.Failure(ContractError.INVALID_INPUT)
        }
        db.rawQuery("SELECT source FROM account WHERE id=1", null).use {
            if (!it.moveToFirst() || it.text(0) != store.source.name) throw SQLiteException("가게 원결과 조회의 실제 출처 손상")
        }
        return ContractResult.Success(Unit)
    }

    private fun matchingSlot(profile: ProfileId, id: RequestId, accepts: (HostCheckpointCommand) -> Boolean): ContractResult<Unit> =
        when (val loaded = store.hostCommands.load(profile)) {
            is ContractResult.Failure -> loaded
            is ContractResult.Success -> {
                val command = loaded.value?.command
                if (command != null && command.requestId == id && !accepts(command)) conflict() else ContractResult.Success(Unit)
            }
        }
    private fun conflict() = ContractResult.Failure(ContractError.STATE_CONFLICT)

    private fun expansionOffer(j: JSONArray): ShopExpansion {
        if (j.length() != 8) throw SQLiteException("확장 원명령 전체 필드 손상")
        fun number(index: Int, min: Long = 0, max: Long = Int.MAX_VALUE.toLong()) =
            LedgerJson.integer(JSONObject().put("value", j.get(index)), "value", min, max)
        val trial = j.get(5) as? Boolean ?: throw SQLiteException("확장 원명령 시험 여부 손상")
        val rule = j.get(6) as? String ?: throw SQLiteException("확장 원명령 규칙 형식 손상")
        return ShopExpansion(RuleVersion(rule), trial, number(1, 1, Int.MAX_VALUE - 1L).toInt(),
            number(2, 1).toInt(), number(3, 1).toInt(), number(4).toInt(), GameMoney(number(7, 0, Long.MAX_VALUE)))
    }
}

/** 원래 전체 offer의 금융 연결만 대조한다. 현재 가격/잔액/정의를 입력받지 않는다. */
internal fun matchesExpansionReceipt(id: RequestId, source: InputSource, offer: ShopExpansion, receipt: Settlement): Boolean =
    receipt.transaction.requestId == id && receipt.transaction.source == source && receipt.transaction.kind == TransactionKind.FACILITY &&
        receipt.transaction.ruleVersion == offer.ruleVersion && receipt.transaction.money == MoneyChange(MoneyDirection.DEBIT, offer.price) &&
        receipt.changedVehicle == null
