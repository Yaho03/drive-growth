package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.quest.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 같은 DB의 실제 프로필·단일 버전 정의를 공급한다. 표/프로필/목표/보상 기본값을 만들지 않는다. */
class QuestLedger(private val store:SqliteGameChangeStore,
    private val profile:()->ContractResult<ProfileId>,
    private val definitions:()->ContractResult<QuestDefinitions>) : QuestChangeStore {
    companion object {
        fun createTables(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE quest_tracking (profile TEXT PRIMARY KEY NOT NULL, source TEXT NOT NULL, complete_since INTEGER)")
            db.execSQL("""CREATE TABLE quest_events (id TEXT PRIMARY KEY NOT NULL, profile TEXT NOT NULL, source TEXT NOT NULL,
                action TEXT NOT NULL, cause_request TEXT NOT NULL, payload TEXT NOT NULL,
                UNIQUE(profile,source,action,cause_request))""")
            db.execSQL("""CREATE TABLE quest_claims (request_id TEXT PRIMARY KEY NOT NULL, profile TEXT NOT NULL, source TEXT NOT NULL,
                quest_id TEXT NOT NULL, period_start INTEGER NOT NULL, period_end INTEGER NOT NULL,
                command TEXT NOT NULL, progress TEXT NOT NULL, balance_before INTEGER NOT NULL, event_cutoff INTEGER NOT NULL, complete_since INTEGER NOT NULL, result TEXT NOT NULL,
                UNIQUE(profile,source,quest_id,period_start))""")
        }
        private fun insert(db:SQLiteDatabase,sql:String,args:Array<out Any?>) {
            db.compileStatement(sql).use { statement->
                args.forEachIndexed { index,value->when(value) {
                    null->statement.bindNull(index+1)
                    is String->statement.bindString(index+1,value)
                    is Long->statement.bindLong(index+1,value)
                    is Int->statement.bindLong(index+1,value.toLong())
                    else->error("지원하지 않는 Quest 바인딩")
                } }
                if(statement.executeInsert()<0)throw SQLiteException("Quest 삽입이 반영되지 않음")
            }
        }
        /** E만 실제 신규 생성/모든 hook 활성 거래에서 호출한다. 기존 프로필의 과거 사건을 완전하다고 만들지 않는다. */
        internal fun startTracking(db:SQLiteDatabase,id:ProfileId,source:InputSource,at:EpochMillis?,freshProfile:Boolean):ContractResult<Unit> {
            check(db.inTransaction())
            db.rawQuery("SELECT source FROM account WHERE id=1",null).use {
                if(!it.moveToFirst()||it.text(0)!=source.name)throw SQLiteException("Quest tracking 원장 출처 불일치")
            }
            if(at?.value?.let{it<0}==true)return ContractResult.Failure(ContractError.INVALID_INPUT)
            db.rawQuery("SELECT source,complete_since FROM quest_tracking WHERE profile=?",arrayOf(id.value)).use {
                if(it.moveToFirst()){if(it.text(0)!=source.name)throw SQLiteException("Quest tracking 출처 불일치");if(!it.isNull(1))it.integer(1,0,Long.MAX_VALUE);return ContractResult.Success(Unit)}
            }
            val since=if(freshProfile)0L else at?.value
            insert(db,"INSERT INTO quest_tracking VALUES(?,?,?)",arrayOf<Any?>(id.value,source.name,since))
            db.rawQuery("SELECT source,complete_since FROM quest_tracking WHERE profile=?",arrayOf(id.value)).use {
                if(!it.moveToFirst()||it.text(0)!=source.name||(if(it.isNull(1))null else it.integer(1,0,Long.MAX_VALUE))!=since)throw SQLiteException("Quest tracking 삽입 확인 실패")
            }
            return ContractResult.Success(Unit)
        }
    }
    private fun identity(expected:ProfileId):ContractResult<Unit> = when(val actual=profile()) {
        is ContractResult.Failure->actual
        is ContractResult.Success->if(actual.value==expected) ContractResult.Success(Unit) else ContractResult.Failure(ContractError.INVALID_INPUT)
    }
    /** UI용 독립 이벤트 write가 아니다. 실제 행동 쓰기와 같은 거래에서 실패를 원래 행동에 전파한다. */
    internal fun recordConfirmedEvent(db:SQLiteDatabase,event:QuestEvent):ContractResult<Unit> {
        check(db.inTransaction())
        val checked=identity(event.profileId);if(checked is ContractResult.Failure)return checked
        if(event.source!=store.source)return ContractResult.Failure(ContractError.INVALID_INPUT)
        val encoded=QuestJson.event(event).toString()
        db.rawQuery("SELECT payload FROM quest_events WHERE id=?",arrayOf(event.eventId)).use {
            if(it.moveToFirst()) {
                val original=QuestJson.event(JSONObject(it.text(0)))
                if(QuestJson.event(original).toString()!=it.text(0))throw SQLiteException("Quest 사건 원문 손상")
                return if(original==event)checkCause(db,event) else ContractResult.Failure(ContractError.STATE_CONFLICT)
            }
        }
        db.rawQuery("SELECT 1 FROM quest_events WHERE profile=? AND source=? AND action=? AND cause_request=?",
            arrayOf(event.profileId.value,event.source.name,event.cause.action.name,event.cause.requestId.value)).use {
            if(it.moveToFirst())return ContractResult.Failure(ContractError.STATE_CONFLICT)
        }
        val causeCheck=checkCause(db,event);if(causeCheck is ContractResult.Failure)return causeCheck
        insert(db,"INSERT INTO quest_events VALUES(?,?,?,?,?,?)",arrayOf(event.eventId,event.profileId.value,event.source.name,event.cause.action.name,event.cause.requestId.value,encoded))
        db.rawQuery("SELECT profile,source,action,cause_request,payload FROM quest_events WHERE id=?",arrayOf(event.eventId)).use {
            if(!it.moveToFirst()||it.text(0)!=event.profileId.value||it.text(1)!=event.source.name||it.text(2)!=event.cause.action.name||it.text(3)!=event.cause.requestId.value||it.text(4)!=encoded)throw SQLiteException("Quest 사건 삽입 확인 실패")
        }
        return ContractResult.Success(Unit)
    }
    private fun emit(db:SQLiteDatabase,cause:QuestCause,at:EpochMillis?):ContractResult<Unit> {
        val id=when(val read=profile()){is ContractResult.Failure->return read;is ContractResult.Success->read.value}
        db.rawQuery("SELECT payload FROM quest_events WHERE profile=? AND source=? AND action=? AND cause_request=?",arrayOf(id.value,store.source.name,cause.action.name,cause.requestId.value)).use {
            if(it.moveToFirst()) {
                val previous=QuestJson.event(JSONObject(it.text(0)))
                if(QuestJson.event(previous).toString()!=it.text(0))throw SQLiteException("Quest 기존 사건 원문 손상")
                if(previous.cause!=cause)return ContractResult.Failure(ContractError.STATE_CONFLICT)
                return recordConfirmedEvent(db,previous)
            }
        }
        return recordConfirmedEvent(db,QuestEvent("quest-event/"+cause.action.slug+"/"+cause.requestId.value,id,store.source,cause,at))
    }
    /** 이미 열람한 과거 boolean만으로 오늘 사건을 만들지 않는다. 기록 완전성 기간은 tracking이 보장한다. */
    internal fun onResultViewed(db:SQLiteDatabase,receipt:Settlement,wasAlreadyViewed:Boolean):ContractResult<Unit> {
        if(wasAlreadyViewed) {
            db.rawQuery("SELECT 1 FROM quest_events WHERE action=? AND cause_request=?",arrayOf(QuestAction.RESULT_VIEWED.name,receipt.transaction.requestId.value)).use {
                if(!it.moveToFirst())return ContractResult.Success(Unit)
            }
        }
        return emit(db,QuestCause.ResultViewed(receipt.transaction.requestId,receipt.settlementId),store.now())
    }
    internal fun onVehicleChanged(db:SQLiteDatabase,before:OwnedVehicle?,after:OwnedVehicle?,receipt:Settlement):ContractResult<Unit> {
        if(before==null||after==null||before.lifecycle!=VehicleLifecycle.OWNED||after.lifecycle!=VehicleLifecycle.OWNED||after.level<=before.level)return ContractResult.Success(Unit)
        if(before.ownedVehicleId!=after.ownedVehicleId)return ContractResult.Failure(ContractError.INVALID_INPUT)
        return emit(db,QuestCause.VehicleLevelUp(receipt.transaction.requestId,receipt.settlementId,after.ownedVehicleId,before.level,after.level),receipt.confirmedAt)
    }
    internal fun onRentalCompleted(db:SQLiteDatabase,rentalId:RequestId,receipt:Settlement)=emit(db,QuestCause.RentalCompleted(rentalId,receipt.settlementId),receipt.confirmedAt)
    private fun checkCause(db:SQLiteDatabase,event:QuestEvent):ContractResult<Unit> {
        val receipt=receipt(db,event.cause.requestId)?:return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if(receipt.settlementId!=event.cause.settlementId)return ContractResult.Failure(ContractError.STATE_CONFLICT)
        if(event.cause !is QuestCause.ResultViewed && event.occurredAt!=null && event.occurredAt!=receipt.confirmedAt)return ContractResult.Failure(ContractError.STATE_CONFLICT)
        when(val cause=event.cause) {
            is QuestCause.ResultViewed->db.rawQuery("SELECT viewed FROM settlements WHERE request_id=?",arrayOf(cause.requestId.value)).use {
                if(!it.moveToFirst()||it.integer(0,0,1)!=1L)return ContractResult.Failure(ContractError.STATE_CONFLICT)
            }
            is QuestCause.RentalCompleted->{
                val completed=when(val read=store.rental.completedRental(cause.rentalId)){is ContractResult.Failure->return read;is ContractResult.Success->read.value}
                if(completed?.receipt?.settlement!=receipt)return ContractResult.Failure(ContractError.STATE_CONFLICT)
            }
            is QuestCause.VehicleLevelUp->{
                if(receipt.transaction.kind!=TransactionKind.GARAGE)return ContractResult.Failure(ContractError.STATE_CONFLICT)
                val car=receipt.changedVehicle
                if(car!=null) {
                    if(car.ownedVehicleId!=cause.vehicleId||car.lifecycle!=VehicleLifecycle.OWNED||car.level!=cause.afterLevel||car.revision.value==0)
                        return ContractResult.Failure(ContractError.STATE_CONFLICT)
                    val change=GameChange(cause.requestId,TransactionKind.GARAGE,store.source,receipt.transaction.ruleVersion,receipt.transaction.money,VehicleWrite.Update(car.ownedVehicleId,Revision(car.revision.value-1),car.level,car.lifecycle,car.levelProgressPercent))
                    if(readRecordedSettlement(db,change)!=receipt)return ContractResult.Failure(ContractError.STATE_CONFLICT)
                } else {
                    db.rawQuery("SELECT command,original_state,result FROM material_requests WHERE request_id=?",arrayOf(cause.requestId.value)).use {
                        if(!it.moveToFirst())return ContractResult.Failure(ContractError.STATE_CONFLICT)
                        val c=MaterialJson.command(JSONObject(it.text(0)));val before=MaterialJson.state(JSONObject(it.text(1)))
                        val prepared=when(val plan=prepareMaterialChange(c,before)){is ContractResult.Failure->throw SQLiteException("Quest 재료 원인 손상");is ContractResult.Success->plan.value}
                        val target=LedgerJson.vehicle(JSONObject(it.text(2)).getJSONObject("target"))
                        if(MaterialJson.command(c).toString()!=it.text(0)||MaterialJson.state(before).toString()!=it.text(1)||target!=prepared.target||
                            MaterialJson.receipt(JSONObject(it.text(2)).getJSONObject("receipt"))!=receipt||target.ownedVehicleId!=cause.vehicleId||
                            before.target.vehicle.level!=cause.beforeLevel||target.level!=cause.afterLevel)return ContractResult.Failure(ContractError.STATE_CONFLICT)
                    }
                }
            }
        }
        return ContractResult.Success(Unit)
    }
    override fun read(id:ProfileId,at:Observation<EpochMillis>):ContractResult<List<QuestProgress>> = store.atomic { db->
        val checked=identity(id);if(checked is ContractResult.Failure)return@atomic checked
        val now=(at as? Observation.Available)?.value?:return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if(now.value<0)return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        val catalog=when(val read=definitions()){is ContractResult.Failure->return@atomic read;is ContractResult.Success->read.value}
        val states=ArrayList<QuestProgress>()
        for(d in catalog.definitions)when(val value=progress(db,id,d,questPeriod(d.cadence,d.zone,now),now)) {
            is ContractResult.Failure->return@atomic value
            is ContractResult.Success->states.add(value.value)
        }
        ContractResult.Success(java.util.Collections.unmodifiableList(states))
    }
    private fun progress(db:SQLiteDatabase,id:ProfileId,definition:QuestDefinition,period:QuestPeriod,now:EpochMillis):ContractResult<QuestProgress> {
        claims(db,id).firstOrNull {overlaps(it.command,definition.id,period)}?.let{return ContractResult.Success(it.progress)}
        val since=db.rawQuery("SELECT source,complete_since FROM quest_tracking WHERE profile=?",arrayOf(id.value)).use {
            if(!it.moveToFirst())null else {if(it.text(0)!=store.source.name)throw SQLiteException("Quest 진행 출처 불일치");if(it.isNull(1))null else EpochMillis(it.integer(1,0,Long.MAX_VALUE))}
        }
        return questProgress(definition,period,id,store.source,events(db,id,Long.MAX_VALUE),now,since)
    }
    private fun events(db:SQLiteDatabase,id:ProfileId,cutoff:Long):List<QuestEvent> = db.rawQuery("SELECT id,profile,source,action,cause_request,payload FROM quest_events WHERE rowid<=? ORDER BY rowid",arrayOf(cutoff.toString())).use { rows->buildList {
            while(rows.moveToNext()) {
                val raw=rows.text(5);val event=QuestJson.event(JSONObject(raw))
                if(QuestJson.event(event).toString()!=raw||event.eventId!=rows.text(0)||event.profileId.value!=rows.text(1)||event.source.name!=rows.text(2)||
                    event.cause.action.name!=rows.text(3)||event.cause.requestId.value!=rows.text(4)||event.profileId!=id||event.source!=store.source)throw SQLiteException("Quest 사건 식별·출처 불일치")
                if(checkCause(db,event) is ContractResult.Failure)throw SQLiteException("Quest 사건의 실제 원인 누락·불일치")
                add(event)
            }
        }}
    override fun claim(command:QuestClaimCommand):ContractResult<QuestClaimSettlement> = store.atomic { db->
        recorded(db,command.requestId)?.let {
            if(it.command!=command)return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
            val checked=identity(command.profileId);if(checked is ContractResult.Failure)return@atomic checked
            return@atomic ContractResult.Success(it)
        }
        val checked=identity(command.profileId);if(checked is ContractResult.Failure)return@atomic checked
        if(command.source!=store.source)return@atomic ContractResult.Failure(ContractError.INVALID_INPUT)
        // 같은 목표 기간은 요청ID/정의버전·zone을 바꿔도 두 번 지급하지 않는다.
        if(claims(db,command.profileId).any {overlaps(it.command,command.definition.id,command.period)})return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
        if(store.requestUnavailable(db,command.requestId,questClaim=true))return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
        val catalog=when(val read=definitions()){is ContractResult.Failure->return@atomic read;is ContractResult.Success->read.value}
        if(catalog.definitions.none{it==command.definition})return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
        val now=store.now()
        val state=when(val read=progress(db,command.profileId,command.definition,command.period,now)){is ContractResult.Failure->return@atomic read;is ContractResult.Success->read.value}
        val cutoff=db.rawQuery("SELECT COALESCE(MAX(rowid),0) FROM quest_events",null).use{it.moveToFirst();it.integer(0,0,Long.MAX_VALUE)}
        val since=db.rawQuery("SELECT complete_since FROM quest_tracking WHERE profile=?",arrayOf(command.profileId.value)).use {
            if(!it.moveToFirst()||it.isNull(0))throw SQLiteException("Quest 완전성 근거 누락");it.integer(0,0,Long.MAX_VALUE)
        }
        val before=store.readBalance(db)
        val plan=when(val read=prepareQuestClaim(command,state,before,now)){is ContractResult.Failure->return@atomic read;is ContractResult.Success->read.value}
        val receipt=Settlement(SettlementId(UUID.randomUUID().toString()),Transaction(TransactionId(UUID.randomUUID().toString()),command.requestId,
            TransactionKind.GARAGE,command.source,command.definition.version,plan.money),plan.balance,null,now)
        if(db.update("account",ContentValues().apply{put("balance",plan.balance.units)},"id=1",null)!=1)throw SQLiteException("Quest 계좌 갱신 대상 불일치")
        val encoded=QuestJson.command(command).toString()
        store.insertSettlement(db,command.requestId,encoded,receipt)
        insert(db,"INSERT INTO quest_claims VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf(command.requestId.value,command.profileId.value,command.source.name,
            command.definition.id,command.period.startsAt.value,command.period.endsAt.value,encoded,QuestJson.progress(state).toString(),before.units,cutoff,since,LedgerJson.settlement(receipt).toString()))
        val confirmed=QuestClaimSettlement(command,state.copy(status=QuestStatus.CLAIMED),receipt)
        if(store.readBalance(db)!=plan.balance || recorded(db,command.requestId)!=confirmed)throw SQLiteException("Quest 정산·마커 삽입 확인 실패")
        ContractResult.Success(confirmed)
    }
    override fun claimedRequestIds(id:ProfileId):ContractResult<Set<RequestId>> = store.atomic { db->
        val checked=identity(id);if(checked is ContractResult.Failure)return@atomic checked
        ContractResult.Success(java.util.Collections.unmodifiableSet(claims(db,id).mapTo(linkedSetOf()){it.command.requestId}))
    }
    private fun overlaps(c:QuestClaimCommand,id:String,p:QuestPeriod)=c.definition.id==id && c.period.startsAt.value<p.endsAt.value && p.startsAt.value<c.period.endsAt.value
    private fun claims(db:SQLiteDatabase,id:ProfileId):List<QuestClaimSettlement> {
        val ids=db.rawQuery("SELECT request_id FROM quest_claims",null).use{r->buildList{while(r.moveToNext())add(RequestId(r.text(0)))}}
        val result=ids.map {recorded(db,it)?:throw SQLiteException("Quest 청구 원문 누락")}
        if(result.any{it.command.profileId!=id||it.command.source!=store.source})throw SQLiteException("Quest 청구 프로필 혼합")
        for((index,a)in result.withIndex())if(result.take(index).any{overlaps(it.command,a.command.definition.id,a.command.period)})throw SQLiteException("Quest 기간 중복 청구")
        db.rawQuery("SELECT request_id,command FROM settlements WHERE substr(request_id,1,12)='quest/claim/'",null).use { r->while(r.moveToNext()) {
            val owner=(org.json.JSONTokener(r.text(1)).nextValue() as? JSONObject)?.optString("type")
            if(owner=="quest-claim-v1" && RequestId(r.text(0)) !in ids)throw SQLiteException("Quest 원인 없는 정산")
        } }
        return result
    }
    private fun recorded(db:SQLiteDatabase,id:RequestId):QuestClaimSettlement? {
        db.rawQuery("SELECT profile,source,quest_id,period_start,period_end,command,progress,balance_before,event_cutoff,complete_since,result FROM quest_claims WHERE request_id=?",arrayOf(id.value)).use {
            if(!it.moveToFirst())return null
            val raw=it.text(5);val c=QuestJson.command(JSONObject(raw));val beforeText=it.text(6);val before=QuestJson.progress(JSONObject(beforeText))
            val resultText=it.text(10);val result=LedgerJson.settlement(JSONObject(resultText))
            if(QuestJson.command(c).toString()!=raw||QuestJson.progress(before).toString()!=beforeText||LedgerJson.settlement(result).toString()!=resultText||
                c.requestId!=id||c.profileId.value!=it.text(0)||c.source.name!=it.text(1)||c.source!=store.source||c.definition.id!=it.text(2)||
                c.period.startsAt.value!=it.integer(3)||c.period.endsAt.value!=it.integer(4))throw SQLiteException("Quest 원문·기간·출처 불일치")
            val cutoff=it.integer(8,0,Long.MAX_VALUE);val since=EpochMillis(it.integer(9,0,Long.MAX_VALUE))
            val original=questProgress(c.definition,c.period,c.profileId,c.source,events(db,c.profileId,cutoff),result.confirmedAt,since)
            if((original as? ContractResult.Success)?.value!=before)throw SQLiteException("Quest 원래 사건·진행 snapshot 불일치")
            val balance=GameMoney(it.integer(7,0,Long.MAX_VALUE))
            val plan=when(val read=prepareQuestClaim(c,before,balance,result.confirmedAt)){is ContractResult.Failure->throw SQLiteException("Quest 원래 청구 검증 실패");is ContractResult.Success->read.value}
            if(result.transaction.requestId!=id||result.transaction.source!=c.source||result.transaction.kind!=TransactionKind.GARAGE||result.transaction.ruleVersion!=c.definition.version||
                result.transaction.money!=plan.money||result.balance!=plan.balance||result.changedVehicle!=null||receipt(db,id)!=result)throw SQLiteException("Quest 정산 연결 불일치")
            db.rawQuery("SELECT command FROM settlements WHERE request_id=?",arrayOf(id.value)).use{row->if(!row.moveToFirst()||row.text(0)!=raw)throw SQLiteException("Quest 공용 명령 불일치")}
            return QuestClaimSettlement(c,before.copy(status=QuestStatus.CLAIMED),result)
        }
    }
    private fun receipt(db:SQLiteDatabase,id:RequestId):Settlement? = db.rawQuery("SELECT settlement_id,result FROM settlements WHERE request_id=?",arrayOf(id.value)).use {
        if(!it.moveToFirst())return@use null
        val raw=it.text(1);val j=JSONObject(raw);LedgerJson.integer(j,"balance",0,Long.MAX_VALUE);LedgerJson.integer(j,"at",0,Long.MAX_VALUE)
        val r=LedgerJson.settlement(j)
        if(r.transaction.requestId!=id||r.transaction.source!=store.source||r.settlementId.value!=it.text(0)||LedgerJson.settlement(r).toString()!=raw)throw SQLiteException("Quest 원인 정산 손상")
        r
    }
}

/** 공용 숫자/차량 decoder와 canonical 원문 검사를 사용한다. */
private object QuestJson {
    private fun zone(value:String)=try {ZoneId.of(value)}catch(e:java.time.DateTimeException){throw org.json.JSONException("Quest 시간대 형식 오류").apply{initCause(e)}}
    private fun date(value:String)=try {LocalDate.parse(value)}catch(e:java.time.DateTimeException){throw org.json.JSONException("Quest 기간 날짜 형식 오류").apply{initCause(e)}}
    private fun JSONObject.n(key:String)=LedgerJson.integer(this,key,0,Long.MAX_VALUE)
    private fun JSONObject.i(key:String)=LedgerJson.integer(this,key,0,Int.MAX_VALUE.toLong()).toInt()
    fun definition(d:QuestDefinition)=JSONObject().put("id",d.id).put("version",d.version.value).put("cadence",d.cadence.name).put("action",d.action.name)
        .put("goal",d.goal).put("reward",d.reward.units).put("zone",d.zone.id).put("trial",d.trial)
    fun definition(j:JSONObject)=QuestDefinition(j.getString("id"),RuleVersion(j.getString("version")),QuestCadence.valueOf(j.getString("cadence")),QuestAction.valueOf(j.getString("action")),j.i("goal"),GameMoney(j.n("reward")),zone(j.getString("zone")),j.getBoolean("trial"))
    fun period(p:QuestPeriod)=JSONObject().put("cadence",p.cadence.name).put("start",p.start.toString()).put("zone",p.zone.id)
    fun period(j:JSONObject)=QuestPeriod(QuestCadence.valueOf(j.getString("cadence")),date(j.getString("start")),zone(j.getString("zone")))
    fun command(c:QuestClaimCommand)=JSONObject().put("type","quest-claim-v1").put("request",c.requestId.value).put("profile",c.profileId.value).put("source",c.source.name)
        .put("definition",definition(c.definition)).put("period",period(c.period)).put("revision",c.expectedRevision.value)
    fun command(j:JSONObject):QuestClaimCommand {require(j.getString("type")=="quest-claim-v1");return QuestClaimCommand(RequestId(j.getString("request")),ProfileId(j.getString("profile")),InputSource.valueOf(j.getString("source")),definition(j.getJSONObject("definition")),period(j.getJSONObject("period")),Revision(j.i("revision")))}
    fun progress(s:QuestProgress)=JSONObject().put("profile",s.profileId.value).put("source",s.source.name).put("definition",definition(s.definition)).put("period",period(s.period))
        .put("count",s.count?:JSONObject.NULL).put("revision",s.revision?.value?:JSONObject.NULL).put("status",s.status.name)
    fun progress(j:JSONObject)=QuestProgress(ProfileId(j.getString("profile")),InputSource.valueOf(j.getString("source")),definition(j.getJSONObject("definition")),period(j.getJSONObject("period")),
        if(j.isNull("count"))null else j.i("count"),if(j.isNull("revision"))null else Revision(j.i("revision")),QuestStatus.valueOf(j.getString("status")))
    fun event(e:QuestEvent):JSONObject {
        val c=e.cause;val cause=JSONObject().put("action",c.action.name).put("request",c.requestId.value).put("settlement",c.settlementId.value)
        when(c){is QuestCause.ResultViewed->Unit;is QuestCause.RentalCompleted->cause.put("rental",c.rentalId.value);is QuestCause.VehicleLevelUp->cause.put("vehicle",c.vehicleId.value).put("before",c.beforeLevel).put("after",c.afterLevel)}
        return JSONObject().put("id",e.eventId).put("profile",e.profileId.value).put("source",e.source.name).put("cause",cause).put("at",e.occurredAt?.value?:JSONObject.NULL)
    }
    fun event(j:JSONObject):QuestEvent {
        val c=j.getJSONObject("cause");val id=RequestId(c.getString("request"));val settlement=SettlementId(c.getString("settlement"))
        val cause=when(QuestAction.valueOf(c.getString("action"))){QuestAction.RESULT_VIEWED->QuestCause.ResultViewed(id,settlement);QuestAction.VEHICLE_LEVEL_UP->QuestCause.VehicleLevelUp(id,settlement,OwnedVehicleId(c.getString("vehicle")),c.i("before"),c.i("after"));QuestAction.RENTAL_COMPLETED->QuestCause.RentalCompleted(RequestId(c.getString("rental")),settlement)}
        require(cause.requestId==id)
        return QuestEvent(j.getString("id"),ProfileId(j.getString("profile")),InputSource.valueOf(j.getString("source")),cause,if(j.isNull("at"))null else EpochMillis(j.n("at")))
    }
}
