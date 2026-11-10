package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.CompletedRental
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections

data class SettlementHistoryEntry(val settlement:Settlement,val viewed:Boolean,
    val rental:CompletedRental?=null,val rentalVehicleId:VehicleId?=null)
data class SettlementHistorySnapshot(val identity:GameIdentity,val entries:List<SettlementHistoryEntry>,
    val vehicleNames:Map<VehicleId,String> = emptyMap())

/** 실제 전체 정산의 읽기 전용 조회. commit/settle/markViewed를 조회 대신 호출하지 않는다. */
class SettlementHistoryReader(private val store:SqliteGameChangeStore) {
    companion object {
        /** 기존 session worker에서 호출한다. 현재 콘텐츠 부재는 이름 미제공이며 과거 정산을 없애지 않는다. */
        fun read(games:GameSessionFactory):ContractResult<SettlementHistorySnapshot> = when(val opened=games.open()) {
            is ContractResult.Failure->opened
            is ContractResult.Success->opened.value.use { game ->when(val history=SettlementHistoryReader(game.store).read()) {
                is ContractResult.Failure->history
                is ContractResult.Success->{
                    val names=(game.content as? ContractResult.Success)?.value?.catalog?.vehicles?.associate{it.vehicleId to it.displayName}.orEmpty()
                    if(history.value.identity!=game.identity)ContractResult.Failure(ContractError.STATE_CONFLICT)
                    else ContractResult.Success(history.value.copy(vehicleNames=Collections.unmodifiableMap(names)))
                }
            } }
        }
    }
    fun read():ContractResult<SettlementHistorySnapshot> = store.readResult { db ->
        try {
            db.rawQuery("SELECT source FROM account WHERE id=1",null).use {
                if(!it.moveToFirst() || it.text(0)!=store.source.name)bad("원장 출처 불일치")
            }
            val profile=required(store.localProfileId())
            val ids=HashSet<SettlementId>();val requests=HashSet<RequestId>()
            val entries=db.rawQuery("SELECT request_id,settlement_id,command,result,viewed FROM settlements ORDER BY rowid",null).use { rows->buildList {
                while(rows.moveToNext()) {
                    val raw=rows.text(3);val receipt=decode(raw)
                    if(receipt.transaction.requestId.value!=rows.text(0) || receipt.settlementId.value!=rows.text(1) ||
                        receipt.transaction.source!=store.source || !ids.add(receipt.settlementId) || !requests.add(receipt.transaction.requestId))bad("정산 키·출처 불일치")
                    val rental=checkCommand(db,profile,rows.text(2),receipt)
                    val car=rental?.let{store.findVehicle(db,it.ownedVehicleId)}
                    if(car!=null && car.ownedVehicleId!=rental.ownedVehicleId)bad("대여 차량 키 불일치")
                    add(SettlementHistoryEntry(receipt,rows.flag(4),rental,car?.vehicleId))
                }
            } }
            ContractResult.Success(SettlementHistorySnapshot(GameIdentity(profile,store.source),Collections.unmodifiableList(entries)))
        } catch(error:ReadFailure) {ContractResult.Failure(error.error)}
    }
    private fun decode(raw:String):Settlement {
        val json=JSONObject(raw)
        LedgerJson.integer(json,"balance",0,Long.MAX_VALUE)
        LedgerJson.integer(json,"at",Long.MIN_VALUE,Long.MAX_VALUE)
        val receipt=LedgerJson.settlement(json)
        if(LedgerJson.settlement(receipt).toString()!=raw)bad("정산 원문 형식 불일치")
        return receipt
    }
    private fun checkCommand(db:SQLiteDatabase,profile:ProfileId,raw:String,s:Settlement):CompletedRental? {
        return when(val json=JSONTokener(raw).nextValue()) {
            is JSONArray->checkArray(db,json,raw,s)
            is JSONObject->{
                when(json.optString("type")) {
                    ""->{
                        val write=when(val w=json.get("write")) {
                            JSONObject.NULL->null
                            is JSONObject->when(w.getString("type")) {
                                "add"->VehicleWrite.Add(LedgerJson.vehicle(w.getJSONObject("vehicle")))
                                "update"->VehicleWrite.Update(OwnedVehicleId(w.getString("id")),Revision(LedgerJson.integer(w,"revision",0,Int.MAX_VALUE.toLong()).toInt()),
                                    LedgerJson.integer(w,"level",1,20).toInt(),VehicleLifecycle.valueOf(w.getString("lifecycle")),LedgerJson.integer(w,"levelProgressPercent",0,99).toInt())
                                else->bad("차량 명령 종류 오류")
                            }
                            else->bad("차량 명령 형식 오류")
                        }
                        val t=s.transaction
                        val command=GameChange(t.requestId,t.kind,t.source,t.ruleVersion,t.money,write)
                        if(LedgerJson.command(command).toString()!=raw || readRecordedSettlement(db,command)!=s)bad("원래 명령과 정산 불일치")
                        if(write is VehicleWrite.Update) {
                            val vehicle=s.changedVehicle?:bad("변경 차량 누락")
                            if(vehicle.revision.value.toLong()!=write.expectedRevision.value.toLong()+1 || vehicle.level!=write.level ||
                                vehicle.lifecycle!=write.lifecycle || vehicle.levelProgressPercent!=write.levelProgressPercent)bad("변경 결과 snapshot 불일치")
                        }
                    }
                    "gacha-v1"->{
                        typed(json,s,"rules")
                        val vehicle=LedgerJson.vehicle(json.getJSONObject("vehicle"))
                        for(key in listOf("revision","shopLevel","capacity","counter","next"))LedgerJson.integer(json,key,0,Int.MAX_VALUE.toLong())
                        if(json.get("guaranteed") !is Boolean)bad("가챠 불리언 오류")
                        val ticket=GachaTicketUse.valueOf(json.getString("ticket"))
                        val command=GachaCommand(s.transaction.requestId,store.source,ContentVersion(json.getString("content")),s.transaction.ruleVersion,
                            Revision(LedgerJson.integer(json,"revision",0,Int.MAX_VALUE.toLong()).toInt()),
                            LedgerJson.integer(json,"shopLevel",1,Int.MAX_VALUE.toLong()).toInt(),LedgerJson.integer(json,"capacity",0,Int.MAX_VALUE.toLong()).toInt(),
                            LedgerJson.integer(json,"counter",0,Int.MAX_VALUE.toLong()).toInt(),ticket,
                            GachaAward(vehicle,LedgerJson.integer(json,"next",0,Int.MAX_VALUE.toLong()).toInt(),json.getBoolean("guaranteed")))
                        if(JSONObject(HostCheckpointJson.encode(profile,HostCheckpointCommand.Gacha(command))).getJSONObject("command").toString()!=raw)bad("가챠 원명령 형식 불일치")
                        val money=if(ticket==GachaTicketUse.PURCHASED)MoneyChange(MoneyDirection.DEBIT,vehicle.acquisitionCost) else null
                        if(s.changedVehicle!=vehicle || s.transaction.money!=money)bad("가챠 지급 snapshot 불일치")
                        db.rawQuery("SELECT command,settlement_id,revision,counter,tickets FROM gacha_requests WHERE request_id=?",arrayOf(s.transaction.requestId.value)).use {
                            if(!it.moveToFirst() || it.text(0)!=raw || it.text(1)!=s.settlementId.value ||
                                it.integer(2,0,Int.MAX_VALUE.toLong())!=LedgerJson.integer(json,"revision",0,Int.MAX_VALUE.toLong())+1 ||
                                it.integer(3,0,Int.MAX_VALUE.toLong())!=LedgerJson.integer(json,"next",0,Int.MAX_VALUE.toLong()))bad("가챠 원래 행 연결 불일치")
                            it.integer(4,0,Long.MAX_VALUE)
                        }
                    }
                    "material-v1"->{
                        val command=MaterialJson.command(json);typed(json,s,"rule")
                        if(MaterialJson.command(command).toString()!=raw)bad("재료 명령 원문 불일치")
                        val result=JSONObject(linkedResult(db,"material_requests",s.transaction.requestId,raw))
                        if(MaterialJson.receipt(result.getJSONObject("receipt"))!=s)bad("재료 원결과 연결 불일치")
                        val target=LedgerJson.vehicle(result.getJSONObject("target"));val material=LedgerJson.vehicle(result.getJSONObject("material"))
                        if(target.ownedVehicleId!=command.target.ownedVehicleId || material.ownedVehicleId!=command.material.ownedVehicleId ||
                            target.level!=command.target.level || target.levelProgressPercent!=command.target.levelProgressPercent || target.lifecycle!=command.target.lifecycle ||
                            target.revision.value.toLong()!=command.target.expectedRevision.value.toLong()+1 ||
                            material.revision.value.toLong()!=command.material.expectedRevision.value.toLong()+1 ||
                            material.level!=command.material.level || material.levelProgressPercent!=command.material.levelProgressPercent ||
                            material.lifecycle!=command.material.lifecycle)bad("재료 원결과 개체 연결 불일치")
                    }
                    "repair-v1"->{
                        val command=RepairJson.command(json);typed(json,s,"rule")
                        if(RepairJson.command(command).toString()!=raw)bad("수리 명령 원문 불일치")
                        val original=required(store.repairSettlement(command.requestId))?:bad("수리 원결과 누락")
                        if(original.command!=command || RepairJson.receipt(original)!=s)bad("수리 원결과 연결 불일치")
                    }
                    "first-gift-v1"->{
                        val command=FirstGiftJson.command(json);typed(json,s,"rule")
                        if(command.profileId!=profile || FirstGiftJson.command(command).toString()!=raw)bad("선물 명령 원문 불일치")
                        val result=FirstGiftJson.result(command,JSONObject(linkedResult(db,"first_gift_requests",command.requestId,raw)))
                        if(FirstGiftJson.receipt(result)!=s || result.marker.profileId!=profile || result.marker.requestId!=command.requestId ||
                            result.marker.cause!=command.cause)bad("선물 원결과 연결 불일치")
                        val rental=required(store.rental.completedRental(command.cause.rentalId))?:bad("선물 원인 대여 누락")
                        if(rental.receipt.settlement.settlementId!=command.cause.settlementId)bad("선물 원인 정산 불일치")
                    }
                    "quest-claim-v1"->{
                        val command=QuestJson.command(json)
                        if(command.profileId!=profile || command.source!=store.source)bad("Quest 식별 불일치")
                        val original=required(store.quests.settlement(command.requestId))?:bad("Quest 원결과 누락")
                        if(original.command!=command || original.settlement!=s || QuestJson.command(command).toString()!=raw)bad("Quest 원결과 연결 불일치")
                    }
                    else->bad("미지원 정산 명령 원문")
                };null
            }
            else->bad("정산 명령 형식 오류")
        }
    }
    private fun typed(j:JSONObject,s:Settlement,rule:String) {
        if(j.getString("request")!=s.transaction.requestId.value || j.getString("source")!=store.source.name ||
            j.getString(rule)!=s.transaction.ruleVersion.value || s.transaction.kind!=TransactionKind.GARAGE)bad("정산 명령 메타데이터 불일치")
    }
    private fun linkedResult(db:SQLiteDatabase,table:String,id:RequestId,command:String):String =
        db.rawQuery("SELECT command,result FROM $table WHERE request_id=?",arrayOf(id.value)).use {
            if(!it.moveToFirst() || it.text(0)!=command)bad("기능 원래 행 누락·불일치")
            it.text(1)
        }
    private fun checkArray(db:SQLiteDatabase,j:JSONArray,raw:String,s:Settlement):CompletedRental? {
        when(j.getString(0)) {
            "settle"->{
                if(j.length()!=2 || j.toString()!=raw || s.transaction.kind!=TransactionKind.RENTAL)bad("영업 정산 명령 오류")
                val id=RequestId(j.getString(1));val rental=required(store.rental.completedRental(id))?:bad("완료 대여 누락")
                if(rental.receipt.settlement!=s || s.transaction.money?.direction!=MoneyDirection.CREDIT)bad("대여 원래 정산 불일치")
                db.rawQuery("SELECT r.version,r.settlement_request,v.assigned FROM rentals r LEFT JOIN visits v ON v.id=r.id WHERE r.id=?",arrayOf(id.value)).use {
                    if(!it.moveToFirst() || it.text(0)!=s.transaction.ruleVersion.value || it.text(1)!=s.transaction.requestId.value ||
                        it.text(2)!=rental.ownedVehicleId.value)bad("대여 규칙·정산·배정 연결 불일치")
                }
                return rental
            }
            "facility","expand"->{
                val count=if(j.getString(0)=="facility")5 else 8
                if(j.length()!=count || j.toString()!=raw || s.transaction.kind!=TransactionKind.FACILITY || s.changedVehicle!=null)bad("시설 정산 명령 오류")
                val rule=j.get(count-2) as? String?:bad("시설 규칙 형식 오류")
                val amount=LedgerJson.integer(JSONObject().put("price",j.get(count-1)),"price",0,Long.MAX_VALUE)
                if (j.getString(0)=="facility") io.drivegrowth.core.rental.Facility.valueOf(j.getString(1))
                else for(index in 1..4) LedgerJson.integer(JSONObject().put("value",j.get(index)),"value",if(index==4)0 else 1,Int.MAX_VALUE.toLong())
                if(j.get(count-3) !is Boolean || s.transaction.ruleVersion.value!=rule ||
                    s.transaction.money!=MoneyChange(MoneyDirection.DEBIT,GameMoney(amount)))bad("시설 원래 정산 불일치")
            }
            else->bad("미지원 배열 명령")
        };return null
    }
    private class ReadFailure(val error:ContractError):RuntimeException()
    private fun <T> required(r:ContractResult<T>):T=when(r){is ContractResult.Failure->throw ReadFailure(r.error);is ContractResult.Success->r.value}
    private fun bad(message:String):Nothing=throw SQLiteException(message)
}
