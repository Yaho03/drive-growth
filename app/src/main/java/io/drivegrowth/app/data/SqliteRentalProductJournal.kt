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
        val origin=j.getJSONObject("origin")
        if(origin.length()!=1)throw SQLiteException("비활성 원점 종류 손상")
        val authority=when {
            origin.has("clock")->RentalProductJson.clock(origin.getJSONObject("clock")).let{at->
                if(!continuous(at,since))throw SQLiteException("비활성 원점 시계 불일치")
                JSONObject().put("clock",RentalProductJson.clock(at))
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
        if(canonical.toString()!=raw)throw SQLiteException("비활성 전체 원문 손상")
        j
    }
    private fun writeInactive(db:SQLiteDatabase,id:GameIdentity,j:JSONObject,insert:Boolean) {
        val raw=j.toString();val values=ContentValues().apply{put("marker",j.getString("marker"));put("profile",id.profileId.value);put("source",id.source.name);put("payload",raw);put("digest",ObservedDrivingJson.digest(raw))}
        if(insert){if(db.insertOrThrow("rental_product_inactive",null,values)<0)throw SQLiteException("비활성 marker 무삽입")}
        else if(db.update("rental_product_inactive",values,"marker=?",arrayOf(j.getString("marker")))!=1)throw SQLiteException("비활성 원문 무갱신")
        if(inactive(db,id,RequestId(j.getString("marker")))?.toString()!=raw)throw SQLiteException("비활성 실제 쓰기 불일치")
    }
    override fun beginInactive(identity:GameIdentity,marker:RequestId,since:RentalClock):ContractResult<Unit> = withDb(identity){_,db->
        val st=when(val r=state(db,identity)){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value}
        inactive(db,identity,marker)?.let{return@withDb if(RentalProductJson.clock(it.getJSONObject("since"))==since)ContractResult.Success(Unit) else conflict()}
        st.inactive?.let{
            val old=inactive(db,identity,RequestId(it))?:throw SQLiteException("비활성 현재 marker 누락")
            if(!old.getBoolean("done"))return@withDb conflict()
            if(!continuous(RentalProductJson.interval(old.getJSONObject("command")).to,since))return@withDb conflict()
        }
        val now=when(val r=clock()){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value};if(!continuous(since,now))return@withDb data()
        val origin=when(val r=policyOrigin(identity)){is ContractResult.Failure->JSONObject().put("error",r.error.name);is ContractResult.Success->when(val o=r.value){is Observation.Missing->JSONObject().put("missing",o.reason.name);is Observation.Available->{if(!continuous(o.value,since))return@withDb data();JSONObject().put("clock",RentalProductJson.clock(o.value))}}}
        val j=JSONObject().put("marker",marker.value).put("since",RentalProductJson.clock(since)).put("origin",origin).put("command",JSONObject.NULL).put("done",false)
        writeInactive(db,identity,j,true);RentalProductStorage.update(db,st,st.copy(inactive=marker.value));ContractResult.Success(Unit)
    }
    /** 응답 유실/재실행 때 새 to를 만들기 전에 이 원래 준비 구간부터 복구한다. */
    fun pendingInactive(identity:GameIdentity):ContractResult<RentalInactiveInterval?> = withDb(identity){_,db->
        val st=when(val r=state(db,identity)){is ContractResult.Failure->return@withDb r;is ContractResult.Success->r.value};val key=st.inactive?:return@withDb ContractResult.Success(null)
        val j=inactive(db,identity,RequestId(key))?:throw SQLiteException("비활성 marker 소실")
        if(j.getBoolean("done")||j.isNull("command"))return@withDb ContractResult.Success(null)
        ContractResult.Success(RentalProductJson.interval(j.getJSONObject("command")))
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
            val origin=j.getJSONObject("origin");if(!origin.has("clock"))return@withDb if(origin.has("error"))ContractResult.Failure(ContractError.valueOf(origin.getString("error"))) else data()
            val policy=RentalProductJson.clock(origin.getJSONObject("clock"));if(!continuous(policy,interval.originalSince))return@withDb data()
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
