package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.app.host.*
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity
import org.json.JSONObject

enum class RentalInactiveBasis { DRIVING_END, APPLICATION_EXIT_DRIVING }
enum class RentalInactivePhase { RECORDED, PREPARED, COMPLETED }
/** 복구 조회 결과다. 원점 조회 실패를 저장 손상과 구분하고, 실제 P/새 시각으로 보충하지 않는다. */
data class RentalInactiveRecovery(val identity:GameIdentity,val marker:RequestId,val since:RentalClock,
    val basis:RentalInactiveBasis,val policyOrigin:ContractResult<Observation<RentalClock>>,
    val phase:RentalInactivePhase,val interval:RentalInactiveInterval?) {
    init { require((phase==RentalInactivePhase.RECORDED)==(interval==null)) }
}

/** B port 그대로. 현재 시각과 마지막 주행 종료 권위는 공급자가 명시한다. */
class SqliteRentalProductJournal(private val games:GameSessionFactory,
    private val definitions:()->ContractResult<RentalVisitDefinition>,
    private val clock:()->ContractResult<RentalClock>,
    private val policyOrigin:(GameIdentity)->ContractResult<Observation<RentalClock>>):RentalProductJournal {
    private fun conflict()=ContractResult.Failure(ContractError.STATE_CONFLICT)
    private fun data()=ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    private fun <T> withDb(id:GameIdentity,body:(GameSession,SQLiteDatabase)->ContractResult<T>):ContractResult<T> {
        val session=when(val r=games.open()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        return session.use {if(it.identity!=id)ContractResult.Failure(ContractError.INVALID_INPUT) else it.store.atomic{db->body(it,db)}}
    }
    private fun state(db:SQLiteDatabase,id:GameIdentity)=RentalProductStorage.read(db,id)
    private fun free(s:GameSession):ContractResult<Unit> = when(val r=s.store.hostCommands.load(s.identity.profileId)){is ContractResult.Failure->r;is ContractResult.Success->if(r.value==null)ContractResult.Success(Unit) else conflict()}
    private fun valid(c:RentalClock)=c.bootId.isNotBlank()&&c.epoch.value>=0&&c.elapsedMs>=0
    private fun continuous(a:RentalClock,b:RentalClock)=valid(a)&&valid(b)&&a.bootId==b.bootId&&b.epoch.value>=a.epoch.value&&b.elapsedMs>=a.elapsedMs&&b.epoch.value-a.epoch.value==b.elapsedMs-a.elapsedMs
    /** 명시 활성화. 과거방문을만들지않고 실제 clock 한 번을 첫 schedule로 고정한다. */
    fun activate(identity:GameIdentity):ContractResult<RentalVisitCursor> = withDb(identity){_,db->
        when(val old=state(db,identity)){is ContractResult.Success->return@withDb ContractResult.Success(old.value.cursor);is ContractResult.Failure->if(old.error!=ContractError.DATA_UNAVAILABLE)return@withDb old}
        val at=when(val r=clock()){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value};if(!valid(at))return@withDb data()
        val d=when(val r=definitions()){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
        RentalProductStorage.activate(db,identity,RentalVisitCursor(Revision(0),0,at),d.version,d.trial)
    }
    override fun pending(identity:GameIdentity):ContractResult<PreparedRentalVisit?> = withDb(identity){s,db->
        val st=when(val r=state(db,identity)){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
        if(st.pending==null)when(val r=free(s)){is ContractResult.Failure->return@withDb r;else->Unit}
        ContractResult.Success(st.pending)
    }
    override fun cursor(identity:GameIdentity):ContractResult<RentalVisitCursor> = withDb(identity){s,db->
        when(val r=free(s)){is ContractResult.Failure->return@withDb r;else->Unit}
        when(val r=state(db,identity)){is ContractResult.Failure->r;is ContractResult.Success->ContractResult.Success(r.value.cursor)}
    }
    private fun original(db:SQLiteDatabase,p:PreparedRentalVisit):ContractResult<Pair<Boolean,OwnedVehicleId?>> {
        db.rawQuery("SELECT host_command FROM visits WHERE id=?",arrayOf(p.visit.request.id.value)).use {
            if(it.moveToFirst() && (it.isNull(0)||it.text(0)!=RentalProductJson.prepared(p).toString()))return conflict()
        }
        return ContractResult.Success(RentalProductStorage.original(db,p))
    }
    override fun save(input:PreparedRentalVisit):ContractResult<PreparedRentalVisit> = withDb(input.identity){s,db->
        when(val old=original(db,input)){is ContractResult.Failure->return@withDb old;is ContractResult.Success->if(old.value.first)return@withDb ContractResult.Success(input)}
        val st=when(val r=state(db,input.identity)){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
        st.pending?.let{return@withDb if(it==input)ContractResult.Success(it) else conflict()}
        when(val r=free(s)){is ContractResult.Failure->return@withDb r;else->Unit}
        if(st.cursor!=input.expected||s.store.requestUnavailable(db,input.visit.request.id))return@withDb conflict()
        val d=when(val r=definitions()){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
        val now=when(val r=clock()){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
        val catalog=when(val r=s.content){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value.catalog}
        val v=input.visit;val t=v.terms;val a=input.expected;val b=input.next
        if(!continuous(a.nextAt,now))return@withDb data()
        if(input.definitionVersion!=d.version||input.trial!=d.trial||v.source!=input.identity.source||v.catalog!=catalog||v.categories!=d.categories||v.hourlyRates!=d.rates||t.rules!=d.rules||t.fuelBonus!=d.fuelBonus||t.rounding!=d.rounding||t.start!=a.nextAt.epoch||d.demand[v.request.desired]?.let{it>0}!=true||d.durations[v.request.hours]?.let{it>0}!=true||d.returns[v.request.hours]!=t.returnMinutes||d.customers.none{it.weight>0&&it.customer==t.customer&&it.regularCustomer==t.regularCustomer})return@withDb conflict()
        if(a.revision.value==Int.MAX_VALUE||a.sequence==Long.MAX_VALUE||b.revision.value!=a.revision.value+1||b.sequence!=a.sequence+1||!continuous(a.nextAt,b.nextAt)||b.nextAt.epoch.value-a.nextAt.epoch.value!=d.intervalMillis)return@withDb conflict()
        val encoded=RentalProductJson.prepared(input).toString();val frozen=RentalProductJson.prepared(JSONObject(encoded));if(RentalProductJson.prepared(frozen).toString()!=encoded)return@withDb ContractResult.Failure(ContractError.INVALID_INPUT)
        RentalProductStorage.update(db,st,st.copy(pending=frozen,definition=input.definitionVersion,trial=input.trial));ContractResult.Success(frozen)
    }
    override fun execute(input:PreparedRentalVisit):ContractResult<OwnedVehicleId?> = withDb(input.identity){s,db->
        when(val old=original(db,input)){is ContractResult.Failure->return@withDb old;is ContractResult.Success->if(old.value.first)return@withDb ContractResult.Success(old.value.second)}
        when(val r=free(s)){is ContractResult.Failure->return@withDb r;else->Unit}
        RentalProductStorage.commit(s.store,db,input)
    }
    override fun finish(input:PreparedRentalVisit):ContractResult<Unit> = withDb(input.identity){_,db->
        when(val old=original(db,input)){is ContractResult.Failure->return@withDb old;is ContractResult.Success->if(!old.value.first)return@withDb data()}
        val st=when(val r=state(db,input.identity)){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
        if(st.pending==input)RentalProductStorage.update(db,st,st.copy(pending=null))
        ContractResult.Success(Unit)
    }
    private fun inactive(db:SQLiteDatabase,id:GameIdentity,marker:RequestId):JSONObject? = db.rawQuery("SELECT profile,source,payload,digest FROM rental_product_inactive WHERE marker=?",arrayOf(marker.value)).use {
        if(!it.moveToFirst())return@use null
        val raw=it.text(2);val j=JSONObject(raw)
        if(it.text(0)!=id.profileId.value||it.text(1)!=id.source.name||j.getString("marker")!=marker.value||it.text(3)!=ObservedDrivingJson.digest(raw))throw SQLiteException("비활성 원문 프로필/출처 손상")
        val since=RentalProductJson.clock(j.getJSONObject("since"));val done=j.get("done") as? Boolean?:throw org.json.JSONException("비활성 done 형식")
        if(done&&j.isNull("command"))throw SQLiteException("비활성 확정 원문 누락")
        val basis=if(j.has("basis"))j.getString("basis") else "DRIVING_END"
        if(basis !in setOf("DRIVING_END","APPLICATION_EXIT_DRIVING"))throw SQLiteException("비활성 경제 근거 종류 손상")
        val origin=j.getJSONObject("origin")
        if(origin.length()!=1)throw SQLiteException("비활성 원점 종류 손상")
        val authority=when {
            origin.has("clock")->RentalProductJson.clock(origin.getJSONObject("clock")).let{at->
                if(basis!="DRIVING_END")throw SQLiteException("물리 종료 원점/앱 이탈 근거 혼합")
                if(!continuous(at,since))throw SQLiteException("비활성 원점 시계 불일치")
                JSONObject().put("clock",RentalProductJson.clock(at))
            }
            origin.has("applicationExit")->RentalProductJson.clock(origin.getJSONObject("applicationExit")).let{at->
                if(basis!="APPLICATION_EXIT_DRIVING"||!continuous(at,since))throw SQLiteException("앱 이탈 경제 원점 불일치")
                JSONObject().put("applicationExit",RentalProductJson.clock(at))
            }
            origin.has("missing")->JSONObject().put("missing",MissingReason.valueOf(origin.getString("missing")).name)
            origin.has("error")->JSONObject().put("error",ContractError.valueOf(origin.getString("error")).name)
            else->throw SQLiteException("비활성 원점 종류 누락")
        }
        val command=if(j.isNull("command"))null else RentalProductJson.interval(j.getJSONObject("command")).also {
            if(it.marker!=marker||it.originalSince!=since||it.from!=since||!continuous(it.from,it.to))throw SQLiteException("비활성 준비 구간 불일치")
        }
        val canonical=JSONObject().put("marker",marker.value).put("since",RentalProductJson.clock(since)).put("origin",authority)
            .put("command",command?.let(RentalProductJson::interval)?:JSONObject.NULL).put("done",done)
        if(j.has("basis"))canonical.put("basis",basis)
        if(canonical.toString()!=raw)throw SQLiteException("비활성 전체 원문 손상")
        j
    }
    private fun writeInactive(db:SQLiteDatabase,id:GameIdentity,j:JSONObject,insert:Boolean) {
        val raw=j.toString();val values=ContentValues().apply{put("marker",j.getString("marker"));put("profile",id.profileId.value);put("source",id.source.name);put("payload",raw);put("digest",ObservedDrivingJson.digest(raw))}
        if(insert){if(db.insertOrThrow("rental_product_inactive",null,values)<0)throw SQLiteException("비활성 marker 무삽입")}
        else if(db.update("rental_product_inactive",values,"marker=?",arrayOf(j.getString("marker")))!=1)throw SQLiteException("비활성 원문 무갱신")
        if(inactive(db,id,RequestId(j.getString("marker")))?.toString()!=raw)throw SQLiteException("비활성 실제 쓰기 불일치")
    }
    override fun beginInactive(identity:GameIdentity,marker:RequestId,since:RentalClock)=begin(identity,marker,since,false)
    /** 실제 onStop에 주행 중임을 확인한 B 공급자만 호출한다. P·물리 주행 종료·휴식 근거가 아니다. */
    fun beginInactiveWhileDriving(identity:GameIdentity,marker:RequestId,since:RentalClock)=begin(identity,marker,since,true)
    private fun begin(identity:GameIdentity,marker:RequestId,since:RentalClock,drivingExit:Boolean):ContractResult<Unit> = withDb(identity){_,db->
        val st=when(val r=state(db,identity)){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
        val basis=if(drivingExit)"APPLICATION_EXIT_DRIVING" else "DRIVING_END"
        inactive(db,identity,marker)?.let{return@withDb if(RentalProductJson.clock(it.getJSONObject("since"))==since&&(if(it.has("basis"))it.getString("basis") else "DRIVING_END")==basis)ContractResult.Success(Unit) else conflict()}
        var previousExit:RentalClock?=null
        st.inactive?.let{
            val old=inactive(db,identity,RequestId(it))?:throw SQLiteException("비활성 현재 marker 누락")
            if(!old.getBoolean("done"))return@withDb conflict()
            if(!continuous(RentalProductJson.interval(old.getJSONObject("command")).to,since))return@withDb conflict()
            old.getJSONObject("origin").takeIf{it.has("applicationExit")}?.let{origin->previousExit=RentalProductJson.clock(origin.getJSONObject("applicationExit"))}
        }
        val now=when(val r=clock()){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value};if(!continuous(since,now))return@withDb data()
        val prior=previousExit
        val origin=if(drivingExit&&prior==null)JSONObject().put("applicationExit",RentalProductJson.clock(since))
        else when(val r=policyOrigin(identity)) {
            is ContractResult.Failure->JSONObject().put("error",r.error.name)
            is ContractResult.Success->when(val o=r.value) {
                is Observation.Missing->if(drivingExit&&prior!=null)JSONObject().put("applicationExit",RentalProductJson.clock(prior)) else JSONObject().put("missing",o.reason.name)
                is Observation.Available->{
                    if(!continuous(o.value,since))return@withDb data()
                    if(drivingExit) {
                        // 같은 주행 중 재접속은 원점을 유지한다. 실제 새 물리 종료 근거가 있어야 다음 주기로 바뀐다.
                        val at=if(prior!=null&&o.value.epoch.value<=prior.epoch.value)prior else since
                        JSONObject().put("applicationExit",RentalProductJson.clock(at))
                    } else JSONObject().put("clock",RentalProductJson.clock(o.value))
                }
            }
        }
        val j=JSONObject().put("marker",marker.value).put("since",RentalProductJson.clock(since)).put("origin",origin).put("command",JSONObject.NULL).put("done",false).put("basis",basis)
        writeInactive(db,identity,j,true);RentalProductStorage.update(db,st,st.copy(inactive=marker.value));ContractResult.Success(Unit)
    }
    /** 응답 유실/재실행 때 새 to를 만들기 전에 이 원래 준비 구간부터 복구한다. */
    fun pendingInactive(identity:GameIdentity):ContractResult<RentalInactiveInterval?> = withDb(identity){_,db->
        val st=when(val r=state(db,identity)){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value};val key=st.inactive?:return@withDb ContractResult.Success(null)
        val j=inactive(db,identity,RequestId(key))?:throw SQLiteException("비활성 marker 소실")
        if(j.getBoolean("done")||j.isNull("command"))return@withDb ContractResult.Success(null)
        ContractResult.Success(RentalProductJson.interval(j.getJSONObject("command")))
    }
    /** 읽기만 한다. RECORDED도 반환하므로 준비 전 실제 onStop을 재실행 뒤 복구할 수 있다. */
    fun currentInactive(identity:GameIdentity):ContractResult<RentalInactiveRecovery?> = withDb(identity){_,db->
        val st=when(val r=state(db,identity)){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
        val key=st.inactive
        if(key==null) {
            if(db.rawQuery("SELECT 1 FROM rental_product_inactive",null).use{it.moveToFirst()})throw SQLiteException("비활성 이력의 현재 marker 참조 소실")
            return@withDb ContractResult.Success(null)
        }
        val marker=RequestId(key);val j=inactive(db,identity,marker)?:throw SQLiteException("비활성 현재 marker 원문 소실")
        val origin=j.getJSONObject("origin")
        val authority:ContractResult<Observation<RentalClock>> = when {
            origin.has("clock")->ContractResult.Success(Observation.Available(RentalProductJson.clock(origin.getJSONObject("clock"))))
            origin.has("applicationExit")->ContractResult.Success(Observation.Available(RentalProductJson.clock(origin.getJSONObject("applicationExit"))))
            origin.has("missing")->ContractResult.Success(Observation.Missing(MissingReason.valueOf(origin.getString("missing"))))
            else->ContractResult.Failure(ContractError.valueOf(origin.getString("error")))
        }
        val interval=if(j.isNull("command"))null else RentalProductJson.interval(j.getJSONObject("command"))
        val phase=if(j.getBoolean("done"))RentalInactivePhase.COMPLETED else if(interval==null)RentalInactivePhase.RECORDED else RentalInactivePhase.PREPARED
        // basis 없는 구형 원문은 기존 strict decoder가 검증한 DRIVING_END 형식뿐이다.
        val basis=if(j.has("basis"))RentalInactiveBasis.valueOf(j.getString("basis")) else RentalInactiveBasis.DRIVING_END
        ContractResult.Success(RentalInactiveRecovery(identity,marker,RentalProductJson.clock(j.getJSONObject("since")),basis,authority,phase,interval))
    }
    private fun snapshot(s:SqliteGameChangeStore):ContractResult<RentalRuntimeSnapshot> {
        val pending=when(val r=s.rental.pending()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        return when(val r=s.rental.firstCompletedRental()){is ContractResult.Failure->r;is ContractResult.Success->ContractResult.Success(RentalRuntimeSnapshot(pending,r.value))}
    }
    override fun advanceInactive(identity:GameIdentity,interval:RentalInactiveInterval):ContractResult<RentalRuntimeSnapshot> {
        // 준비는 금융 실행과 분리한다. 다른 금융slot이 막아도 원래 복귀시각을 잃지 않는다.
        val prepared=withDb(identity){_,db->
            val j=inactive(db,identity,interval.marker)?:return@withDb data();val encoded=RentalProductJson.interval(interval)
            if(!j.isNull("command"))return@withDb if(j.getJSONObject("command").toString()==encoded.toString())ContractResult.Success(Unit) else conflict()
            val st=when(val r=state(db,identity)){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
            if(st.inactive!=interval.marker.value||RentalProductJson.clock(j.getJSONObject("since"))!=interval.originalSince||interval.from!=interval.originalSince)return@withDb conflict()
            val now=when(val r=clock()){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
            if(!continuous(interval.from,interval.to)||!continuous(interval.to,now))return@withDb data()
            j.put("command",encoded);writeInactive(db,identity,j,false);ContractResult.Success(Unit)
        }
        if(prepared is ContractResult.Failure)return prepared
        return withDb(identity){s,db->
            val j=inactive(db,identity,interval.marker)?:return@withDb data()
            if(j.getBoolean("done"))return@withDb snapshot(s.store)
            when(val r=free(s)){is ContractResult.Failure->return@withDb r;else->Unit}
            val origin=j.getJSONObject("origin");val key=when{origin.has("clock")->"clock";origin.has("applicationExit")->"applicationExit";else->null}
            if(key==null)return@withDb if(origin.has("error"))ContractResult.Failure(ContractError.valueOf(origin.getString("error"))) else data()
            val policy=RentalProductJson.clock(origin.getJSONObject(key));if(!continuous(policy,interval.originalSince))return@withDb data()
            val active=when(val r=s.store.rental.pending()){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
            for(rental in active) {
                if(rental.phase==VehicleActivity.RENTED) {
                    val cursor=when(val r=s.store.rental.accountInactive(rental.id,interval.from.epoch,interval.to.epoch,policy.epoch)){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
                    if(cursor.value>=rental.end.value)when(val r=s.store.rental.settle(rental.id)){is ContractResult.Failure->return@withDb r;else->Unit}
                }
                if(interval.to.epoch.value>=rental.returnEnd.value)when(val r=s.store.rental.finishReturn(rental.id,interval.to.epoch)){is ContractResult.Failure->return@withDb r;else->Unit}
            }
            j.put("done",true);writeInactive(db,identity,j,false);snapshot(s.store)
        }
    }
}
