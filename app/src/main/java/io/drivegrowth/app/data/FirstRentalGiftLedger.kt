package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.CompletedRental
import io.drivegrowth.core.rental.RentalReceipt
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * 같은 DB의 영속 프로필 ID와 새 청구 자격은 초기화 경계가 공급한다. 여기서 프로필/NEW를 생성하지 않는다.
 * profileId는 현재 콘텐츠/진행을 읽지 않는다. eligible/content는 새 지급에만 사용하며 누락은 명시 미지원이다.
 * callback은 같은 store/출처에 묶이고 조회 중 거래·상태 변경을 하지 않는다.
 */
internal class FirstRentalGiftLedger(
    private val store: SqliteGameChangeStore,
    private val profileId: (() -> ContractResult<ProfileId>)? = null,
    private val eligible: ((ProfileId) -> ContractResult<Unit>)? = null,
    private val content: (() -> ContractResult<GameContentSnapshot>)? = null,
) : FirstRentalGiftStore, GachaChangeStore by store {
    companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE first_gift_markers (profile_id TEXT PRIMARY KEY NOT NULL, request_id TEXT UNIQUE NOT NULL, rental_id TEXT NOT NULL, settlement_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE first_gift_requests (request_id TEXT PRIMARY KEY NOT NULL, profile_id TEXT UNIQUE NOT NULL, command TEXT NOT NULL, original_state TEXT NOT NULL, result TEXT NOT NULL)")
        }
    }
    private fun profile() = profileId?.invoke() ?: ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    private fun current(db: SQLiteDatabase, id: ProfileId, original: FirstRentalGiftSettlement?): ContractResult<FirstRentalGiftState> {
        if (original == null) when (val permission = eligible?.invoke(id)) {
            null -> return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            is ContractResult.Failure -> return permission
            is ContractResult.Success -> Unit
        }
        val supplied = when (val read = content?.invoke()) {
            null -> return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        val progress = db.rawQuery("SELECT revision,counter,tickets FROM gacha_progress WHERE id=1",null).use { row ->
            if (!row.moveToFirst()) return if (original == null) ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
                else throw SQLiteException("지급 뒤 현재 가챠 진행 누락")
            GachaProgress(Revision(row.integer(0,0,Int.MAX_VALUE.toLong()).toInt()),
                row.integer(1,0,Int.MAX_VALUE.toLong()).toInt(),row.integer(2,0,Long.MAX_VALUE))
        }
        if (progress.drawsSinceGuarantee >= supplied.gacha.pity) throw SQLiteException("선물 조회의 가챠 카운터 범위 오류")
        val rental = when (val read = store.rental.firstCompletedRental()) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        rental?.let(::validateRental)
        return ContractResult.Success(FirstRentalGiftState(id,store.source,supplied.catalog.contentVersion,supplied.ruleVersion,
            store.readBalance(db),progress,rental,original))
    }
    private fun validateRental(rental: CompletedRental) {
        val receipt=rental.receipt.settlement
        if(receipt.transaction.kind!=TransactionKind.RENTAL || receipt.transaction.source!=store.source ||
            receipt.transaction.requestId.value!="rental/settle/"+rental.rentalId.value ||
            receipt.transaction.money?.direction!=MoneyDirection.CREDIT || receipt.changedVehicle!=null)
            throw JSONException("최초 완료 영업 정산 연결 오류")
    }
    override fun readFirstRentalGift(profileId: ProfileId): ContractResult<FirstRentalGiftState> = store.atomic { db ->
        val id = when (val read = profile()) { is ContractResult.Failure -> return@atomic read; is ContractResult.Success -> read.value }
        if (profileId != id) return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        current(db,id,recorded(db,id))
    }
    override fun commitFirstRentalGift(command: FirstRentalGiftCommand): ContractResult<FirstRentalGiftSettlement> = store.atomic { db ->
        val id = when (val read = profile()) { is ContractResult.Failure -> return@atomic read; is ContractResult.Success -> read.value }
        if (command.profileId != id || command.source != store.source) return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        recorded(db,id)?.let { original ->
            return@atomic if (original.command == command) ContractResult.Success(original)
                else ContractResult.Failure(ContractError.STATE_CONFLICT)
        }
        if (store.requestUnavailable(db,command.requestId)) return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
        val before = when (val read = current(db,id,null)) { is ContractResult.Failure -> return@atomic read; is ContractResult.Success -> read.value }
        val ready = when (val checked = prepareFirstRentalGift(command,before)) {
            is ContractResult.Failure -> return@atomic checked
            is ContractResult.Success -> checked.value as FirstRentalGiftPreparation.Ready
        }
        val result = FirstRentalGiftSettlement(command,SettlementId(UUID.randomUUID().toString()),
            Transaction(TransactionId(UUID.randomUUID().toString()),command.requestId,TransactionKind.GARAGE,store.source,command.ruleVersion,null),
            ready.balance,ready.progress,ready.marker,store.now())
        val changed = db.update("gacha_progress",ContentValues().apply {
            put("revision",result.progress.revision.value);put("tickets",result.progress.freeTickets)
        },"id=1 AND revision=?",arrayOf(command.expectedProgressRevision.value.toString()))
        if (changed != 1) throw SQLiteException("선물 진행 갱신 대상 불일치")
        val encoded=FirstGiftJson.command(command).toString()
        store.insertSettlement(db,command.requestId,encoded,FirstGiftJson.receipt(result))
        db.execSQL("INSERT INTO first_gift_markers VALUES(?,?,?,?)",arrayOf(id.value,command.requestId.value,command.cause.rentalId.value,command.cause.settlementId.value))
        db.execSQL("INSERT INTO first_gift_requests VALUES(?,?,?,?,?)",arrayOf(command.requestId.value,id.value,encoded,
            FirstGiftJson.state(before).toString(),FirstGiftJson.result(result).toString()))
        ContractResult.Success(result)
    }

    /** 현재 진행/완료 조회에 의존하지 않고, 당시 원명령·상태·마커·공용 정산의 연결만 검증한다. */
    private fun recorded(db: SQLiteDatabase,id: ProfileId): FirstRentalGiftSettlement? {
        val marker=db.rawQuery("SELECT profile_id,request_id,rental_id,settlement_id FROM first_gift_markers",null).use { row ->
            if(!row.moveToFirst()) null else {
                val value=FirstRentalGiftMarker(ProfileId(row.text(0)),RequestId(row.text(1)),FirstRentalGiftCause(RequestId(row.text(2)),SettlementId(row.text(3))))
                if(value.profileId!=id || row.moveToNext())throw JSONException("선물 마커 프로필/중복 오류")
                value
            }
        }
        db.rawQuery("SELECT request_id,profile_id,command,original_state,result FROM first_gift_requests",null).use { row ->
            if(row.moveToFirst()) {
                val encoded=row.text(2);val command=FirstGiftJson.command(JSONObject(encoded))
                if(marker==null || row.text(0)!=command.requestId.value || row.text(1)!=id.value || command.profileId!=id ||
                    command.source!=store.source || FirstGiftJson.command(command).toString()!=encoded)
                    throw JSONException("선물 명령/프로필/마커 연결 오류")
                val beforeText=row.text(3);val before=FirstGiftJson.state(JSONObject(beforeText))
                before.firstCompletedRental?.let(::validateRental)
                val ready=(prepareFirstRentalGift(command,before) as? ContractResult.Success)?.value as? FirstRentalGiftPreparation.Ready
                    ?: throw JSONException("선물 당시 원명령 오류")
                val saved=row.text(4);val result=FirstGiftJson.result(command,JSONObject(saved))
                if(FirstGiftJson.state(before).toString()!=beforeText || FirstGiftJson.result(result).toString()!=saved ||
                    before.profileId!=id || before.source!=store.source || result.balance!=ready.balance || result.progress!=ready.progress ||
                    result.marker!=ready.marker || marker!=ready.marker || result.transaction.requestId!=command.requestId ||
                    result.transaction.kind!=TransactionKind.GARAGE || result.transaction.source!=store.source ||
                    result.transaction.ruleVersion!=command.ruleVersion || result.transaction.money!=null)
                    throw JSONException("선물 최초 전체 결과 연결 오류")
                db.rawQuery("SELECT settlement_id,command,result FROM settlements WHERE request_id=?",arrayOf(command.requestId.value)).use { receipt ->
                    if(!receipt.moveToFirst() || receipt.text(0)!=result.settlementId.value || receipt.text(1)!=encoded ||
                        receipt.text(2)!=LedgerJson.settlement(FirstGiftJson.receipt(result)).toString())
                        throw JSONException("선물 공용 정산 누락/연결 오류")
                }
                if(row.moveToNext())throw JSONException("선물 최초 결과 중복")
                return result
            }
        }
        if(marker!=null)throw JSONException("선물 마커의 전체 결과 누락")
        db.rawQuery("SELECT command FROM settlements",null).use { row ->
            while(row.moveToNext()) {
                val encoded=row.text(0)
                if(encoded.startsWith("{") && JSONObject(encoded).optString("type")=="first-gift-v1")
                    throw JSONException("선물 공용 정산만 남음")
            }
        }
        return null
    }
}

/** 당시 완료 영업과 진행은 최초 결과의 검증 snapshot이며 현재 원장의 별도 권위가 아니다. */
internal object FirstGiftJson {
    private fun str(j:JSONObject,k:String)=j.get(k) as? String ?: throw JSONException("$k 문자열 오류")
    private fun integer(j:JSONObject,k:String,max:Long=Long.MAX_VALUE)=LedgerJson.integer(j,k,0,max)
    private fun bool(j:JSONObject,k:String)=j.get(k) as? Boolean ?: throw JSONException("$k 불리언 오류")
    fun command(c:FirstRentalGiftCommand)=JSONObject().put("type","first-gift-v1").put("request",c.requestId.value).put("profile",c.profileId.value)
        .put("source",c.source.name).put("content",c.contentVersion.value).put("rule",c.ruleVersion.value)
        .put("rental",c.cause.rentalId.value).put("settlement",c.cause.settlementId.value).put("revision",c.expectedProgressRevision.value)
    fun command(j:JSONObject):FirstRentalGiftCommand {
        if(str(j,"type")!="first-gift-v1")throw JSONException("선물 명령 종류 오류")
        return FirstRentalGiftCommand(RequestId(str(j,"request")),ProfileId(str(j,"profile")),InputSource.valueOf(str(j,"source")),
            ContentVersion(str(j,"content")),RuleVersion(str(j,"rule")),FirstRentalGiftCause(RequestId(str(j,"rental")),SettlementId(str(j,"settlement"))),
            Revision(integer(j,"revision",Int.MAX_VALUE.toLong()).toInt()))
    }
    private fun progress(p:GachaProgress)=JSONObject().put("revision",p.revision.value).put("counter",p.drawsSinceGuarantee).put("tickets",p.freeTickets)
    private fun progress(j:JSONObject)=GachaProgress(Revision(integer(j,"revision",Int.MAX_VALUE.toLong()).toInt()),integer(j,"counter",Int.MAX_VALUE.toLong()).toInt(),integer(j,"tickets"))
    private fun marker(m:FirstRentalGiftMarker)=JSONObject().put("profile",m.profileId.value).put("request",m.requestId.value)
        .put("rental",m.cause.rentalId.value).put("settlement",m.cause.settlementId.value)
    private fun marker(j:JSONObject)=FirstRentalGiftMarker(ProfileId(str(j,"profile")),RequestId(str(j,"request")),FirstRentalGiftCause(RequestId(str(j,"rental")),SettlementId(str(j,"settlement"))))
    private fun receipt(j:JSONObject):Settlement {
        integer(j,"balance");LedgerJson.integer(j,"at",Long.MIN_VALUE,Long.MAX_VALUE)
        return LedgerJson.settlement(j)
    }
    private fun rental(r:CompletedRental):JSONObject {
        val v=r.receipt
        return JSONObject().put("rental",r.rentalId.value).put("vehicle",r.ownedVehicleId.value).put("receipt",LedgerJson.settlement(v.settlement))
            .put("accident",v.accident).put("match",v.matchReview).put("wash",v.washReview).put("bonus",v.facilityBonus.units)
            .put("regular",v.regularCustomer ?: JSONObject.NULL)
    }
    private fun rental(j:JSONObject):CompletedRental {
        val regular=when(val raw=j.get("regular")){JSONObject.NULL->null;is Boolean->raw;else->throw JSONException("단골 불리언 오류")}
        return CompletedRental(RequestId(str(j,"rental")),OwnedVehicleId(str(j,"vehicle")),RentalReceipt(receipt(j.getJSONObject("receipt")),
            bool(j,"accident"),bool(j,"match"),bool(j,"wash"),GameMoney(integer(j,"bonus")),regular))
    }
    fun state(s:FirstRentalGiftState):JSONObject {
        if(s.granted!=null)throw JSONException("선물 원상태는 미지급 상태")
        return JSONObject().put("profile",s.profileId.value).put("source",s.source.name).put("content",s.contentVersion.value).put("rule",s.ruleVersion.value)
            .put("balance",s.balance.units).put("progress",progress(s.progress)).put("rental",s.firstCompletedRental?.let(::rental) ?: JSONObject.NULL)
            .put("granted",JSONObject.NULL)
    }
    fun state(j:JSONObject):FirstRentalGiftState {
        if(j.get("granted")!=JSONObject.NULL)throw JSONException("선물 원상태에 확정 결과 존재")
        val rental=when(val raw=j.get("rental")){JSONObject.NULL->null;is JSONObject->rental(raw);else->throw JSONException("완료 영업 형식 오류")}
        return FirstRentalGiftState(ProfileId(str(j,"profile")),InputSource.valueOf(str(j,"source")),ContentVersion(str(j,"content")),RuleVersion(str(j,"rule")),
            GameMoney(integer(j,"balance")),progress(j.getJSONObject("progress")),rental,null)
    }
    fun receipt(s:FirstRentalGiftSettlement)=Settlement(s.settlementId,s.transaction,s.balance,null,s.confirmedAt)
    fun result(s:FirstRentalGiftSettlement)=JSONObject().put("receipt",LedgerJson.settlement(receipt(s))).put("progress",progress(s.progress)).put("marker",marker(s.marker))
    fun result(c:FirstRentalGiftCommand,j:JSONObject):FirstRentalGiftSettlement {
        val r=j.getJSONObject("receipt")
        if(r.get("vehicle")!=JSONObject.NULL)throw JSONException("선물 정산에 개체 변경 존재")
        val v=receipt(r)
        return FirstRentalGiftSettlement(c,v.settlementId,v.transaction,v.balance,progress(j.getJSONObject("progress")),marker(j.getJSONObject("marker")),v.confirmedAt)
    }
}
