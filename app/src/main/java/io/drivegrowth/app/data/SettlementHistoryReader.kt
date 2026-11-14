package io.drivegrowth.app.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.runtime.rental.product.RentalCity
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.CompletedRental
import io.drivegrowth.core.rental.RentalCategory
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections

enum class SettlementHistoryCause { INITIAL_GRANT, GACHA }

data class SettlementHistoryEntry(val settlement:Settlement,val viewed:Boolean,
    val rental:CompletedRental?=null,val rentalVehicleId:VehicleId?=null,
    val rentalBasis:Observation<RentalResultBasis> = Observation.Missing(MissingReason.NO_DATA),
    val rentalDemand: Observation<RentalDemandHistory> = Observation.Missing(MissingReason.NO_DATA),
    val cause: SettlementHistoryCause? = null)
data class RentalDemandHistory(
    val identity: GameIdentity,
    val rental: CompletedRental,
    val desired: VehicleDefinition,
    val purpose: RentalCategory,
    val city: Observation<RentalCity>,
)
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
    fun read():ContractResult<SettlementHistorySnapshot> = store.readResult(::readIn)

    private fun readIn(db: SQLiteDatabase): ContractResult<SettlementHistorySnapshot> =
        try {
            db.rawQuery("SELECT source FROM account WHERE id=1",null).use {
                if(!it.moveToFirst() || it.text(0)!=store.source.name)bad("원장 출처 불일치")
            }
            val profile=required(store.localProfileId())
            val identity = GameIdentity(profile, store.source)
            val ids=HashSet<SettlementId>();val requests=HashSet<RequestId>()
            val entries=db.rawQuery(historySelect,null).use { rows->buildList {
                while(rows.moveToNext()) {
                    val receipt=decode(rows.text(2))
                    if(receipt.transaction.requestId.value!=rows.text(10) || receipt.settlementId.value!=rows.text(11) ||
                        receipt.transaction.source!=store.source || !ids.add(receipt.settlementId) || !requests.add(receipt.transaction.requestId))
                        bad("정산 키·출처 불일치")
                    if (rows.field("history_profile") != profile.value ||
                        rows.field("history_profile_source") != store.source.name ||
                        rows.field("history_account_source") != store.source.name) bad("정산 원장 프로필·출처 불일치")
                    val checked = checkCommand(db,profile,rows.field("history_command"),receipt,rows)
                    val rental = checked.rental
                    val car=rental?.let {
                        val col = rows.getColumnIndexOrThrow("history_car")
                        if (rows.isNull(col)) null else LedgerJson.vehicle(JSONObject(rows.text(col)))
                    }
                    if(car!=null && car.ownedVehicleId!=rental.ownedVehicleId)bad("대여 차량 키 불일치")
                    val basis = rental?.let { RentalResultBasisReader(store).readIn(db, it) }
                        ?: Observation.Missing(MissingReason.NO_DATA)
                    val demand = rental?.let { readRentalDemand(rows, identity, it) }
                        ?: Observation.Missing(MissingReason.NO_DATA)
                    add(SettlementHistoryEntry(receipt,rows.flag(rows.getColumnIndexOrThrow("history_viewed")),
                        rental,car?.vehicleId,basis,demand,checked.cause))
                }
            } }
            ContractResult.Success(SettlementHistorySnapshot(identity,Collections.unmodifiableList(entries)))
        } catch(error:ReadFailure) {ContractResult.Failure(error.error)}

    // RentalLedger의 완료 대여 열 순서를 재사용한다. 원방문과 배정 차량도 같은 행에서 읽는다.
    private val historySelect = """SELECT ${RentalLedger.completedColumns},
        s.command AS history_command, s.viewed AS history_viewed, p.profile_id AS history_profile,
        p.source AS history_profile_source, a.source AS history_account_source,
        car.payload AS history_car, ${RentalProductStorage.originalColumns}
        FROM settlements s
        LEFT JOIN rentals r ON substr(s.request_id,1,14)='rental/settle/' AND r.id=substr(s.request_id,15)
        LEFT JOIN visits v ON v.id=r.id
        LEFT JOIN vehicles car ON car.owned_id=r.owned_id
        LEFT JOIN initialization_profile p ON p.id=1
        LEFT JOIN account a ON a.id=1
        ORDER BY s.rowid"""

    private fun Cursor.field(name: String): String = text(getColumnIndexOrThrow(name))

    private fun readRentalDemand(
        row: Cursor,
        identity: GameIdentity,
        rental: CompletedRental,
    ): Observation<RentalDemandHistory> {
        val command = row.getColumnIndexOrThrow("visit_host_command")
        if (row.isNull(command)) return Observation.Missing(MissingReason.NO_DATA)
        val prepared = RentalProductJson.prepared(JSONObject(row.text(command)))
        val visit = prepared.visit
        if (prepared.identity != identity || visit.source != identity.source || visit.request.id != rental.rentalId)
            bad("원래 방문의 프로필·출처·요청 불일치")
        if (RentalProductStorage.original(row, prepared) != rental.ownedVehicleId)
            bad("원래 방문 배정 불일치")
        val desired = visit.catalog.vehicles.singleOrNull { it.vehicleId == visit.request.desired }
            ?: bad("방문에 희망 차종이 없음")
        val purpose = visit.categories[desired.vehicleId] ?: bad("방문에 희망 차종의 유형이 없음")
        val city = prepared.city?.let { Observation.Available(it) } ?: Observation.Missing(MissingReason.NO_DATA)
        return Observation.Available(RentalDemandHistory(identity, rental, desired, purpose, city))
    }

    private fun decode(raw:String):Settlement {
        val json=JSONObject(raw)
        LedgerJson.integer(json,"balance",0,Long.MAX_VALUE)
        LedgerJson.integer(json,"at",Long.MIN_VALUE,Long.MAX_VALUE)
        val receipt=LedgerJson.settlement(json)
        if(LedgerJson.settlement(receipt).toString()!=raw)bad("정산 원문 형식 불일치")
        return receipt
    }
    private data class CheckedCommand(val rental: CompletedRental? = null, val cause: SettlementHistoryCause? = null)

    private fun checkCommand(db:SQLiteDatabase,profile:ProfileId,raw:String,s:Settlement,row: Cursor): CheckedCommand {
        return when(val json=JSONTokener(raw).nextValue()) {
            is JSONArray->CheckedCommand(rental = checkArray(json,raw,s,row))
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
                    "initialization-v1"->{
                        val command=InitializationJson.command(json)
                        if(command.profileId!=profile || command.evidence.profileId!=profile || command.evidence.source!=store.source ||
                            command.grant.source!=store.source || InitializationJson.command(command).toString()!=raw)bad("초기 지급 명령 원문·식별 불일치")
                        val state=required(store.readInitialization(profile))
                        val original=(state.status as? ProfileInitializationStatus.Existing)?.original?:bad("초기 지급 원결과 누락")
                        if(state.profileId!=profile || state.source!=store.source || original.command!=command || original.grant!=s ||
                            original.marker.profileId!=profile || original.marker.requestId!=s.transaction.requestId ||
                            original.marker.evidence!=command.evidence)bad("초기 지급 원결과·마커 연결 불일치")
                        return CheckedCommand(cause = SettlementHistoryCause.INITIAL_GRANT)
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
                        return CheckedCommand(cause = SettlementHistoryCause.GACHA)
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
                    AchievementJson.TYPE -> {
                        val original = store.achievements.confirmed(db, s.transaction.requestId) ?: bad("업적 원결과 누락")
                        if (original.command.profileId != profile || original.settlement != s ||
                            AchievementJson.command(original.command).toString() != raw) bad("업적 원결과 연결 불일치")
                    }
                    else->bad("미지원 정산 명령 원문")
                }
                CheckedCommand()
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
    private fun checkArray(j:JSONArray,raw:String,s:Settlement,row: Cursor):CompletedRental? {
        when(j.getString(0)) {
            "settle"->{
                if(j.length()!=2 || j.toString()!=raw || s.transaction.kind!=TransactionKind.RENTAL)bad("영업 정산 명령 오류")
                val id = RequestId(j.getString(1))
                if (row.getString(0) != id.value || row.field("original_rules") != s.transaction.ruleVersion.value)
                    bad("완료 대여 또는 규칙 연결 불일치")
                val rental = store.rental.decodeCompleted(row)
                if (rental.receipt.settlement != s || s.transaction.money?.direction != MoneyDirection.CREDIT ||
                    row.field("visit_id") != id.value || row.field("visit_assigned") != rental.ownedVehicleId.value)
                    bad("대여 원래 정산·배정 연결 불일치")
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
