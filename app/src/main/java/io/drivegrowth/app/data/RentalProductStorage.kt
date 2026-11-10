package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.contract.*
import org.json.JSONObject

internal data class RentalProductState(val identity:GameIdentity,val cursor:RentalVisitCursor,val pending:PreparedRentalVisit?,val inactive:String?,val definition:ContentVersion,val trial:Boolean)
/** schedule/pending는 metadata이고 실제 배정/재방문은 기존 visits 원장만 권위다. */
internal object RentalProductStorage {
    fun createTables(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE rental_product_marker(profile TEXT PRIMARY KEY NOT NULL,source TEXT NOT NULL)")
        db.execSQL("CREATE TABLE rental_product_state(profile TEXT PRIMARY KEY NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL,digest TEXT NOT NULL)")
        db.execSQL("CREATE TABLE rental_product_inactive(marker TEXT PRIMARY KEY NOT NULL,profile TEXT NOT NULL,source TEXT NOT NULL,payload TEXT NOT NULL,digest TEXT NOT NULL)")
        db.execSQL("ALTER TABLE visits ADD COLUMN host_command TEXT")
    }
    fun identity(store:SqliteGameChangeStore,id:GameIdentity):ContractResult<Unit> = when(val p=store.localProfileId()) {is ContractResult.Failure->p;is ContractResult.Success->if(p.value==id.profileId&&store.source==id.source)ContractResult.Success(Unit) else ContractResult.Failure(ContractError.INVALID_INPUT)}
    private fun encoded(s:RentalProductState)=JSONObject().put("type","rental-product-v1").put("profile",s.identity.profileId.value).put("source",s.identity.source.name).put("definition",s.definition.value).put("trial",s.trial).put("cursor",RentalProductJson.cursor(s.cursor)).put("pending",s.pending?.let(RentalProductJson::prepared)?:JSONObject.NULL).put("inactive",s.inactive?:JSONObject.NULL).toString()
    fun read(db:SQLiteDatabase,id:GameIdentity):ContractResult<RentalProductState> {
        val marker=db.rawQuery("SELECT profile,source FROM rental_product_marker",null).use{if(!it.moveToFirst())false else {if(it.text(0)!=id.profileId.value||it.text(1)!=id.source.name||it.moveToNext())throw SQLiteException("자동영업 marker 출처/프로필 손상");true}}
        if(!marker) {
            if(db.rawQuery("SELECT 1 FROM rental_product_state UNION ALL SELECT 1 FROM rental_product_inactive",null).use{it.moveToFirst()})throw SQLiteException("자동영업 marker 없는 이력")
            return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        }
        return db.rawQuery("SELECT profile,revision,payload,digest FROM rental_product_state",null).use {
            if(!it.moveToFirst())throw SQLiteException("자동영업 marker 뒤 state 누락")
            val raw=it.text(2);val j=JSONObject(raw);require(j.getString("type")=="rental-product-v1")
            val s=RentalProductState(GameIdentity(ProfileId(j.getString("profile")),InputSource.valueOf(j.getString("source"))),RentalProductJson.cursor(j.getJSONObject("cursor")),if(j.isNull("pending"))null else RentalProductJson.prepared(j.getJSONObject("pending")),if(j.isNull("inactive"))null else j.getString("inactive"),ContentVersion(j.getString("definition")),j.get("trial") as? Boolean ?: throw org.json.JSONException("영업 trial 형식"))
            if(s.identity!=id||it.text(0)!=id.profileId.value||it.integer(1,0,Int.MAX_VALUE.toLong()).toInt()!=s.cursor.revision.value||it.text(3)!=ObservedDrivingJson.digest(raw)||encoded(s)!=raw||it.moveToNext())throw SQLiteException("자동영업 state 전체 원문 손상")
            if(s.pending?.identity?.let{p->p!=id}==true)throw SQLiteException("자동영업 pending 프로필 불일치")
            s.pending?.let{p->if(p.definitionVersion!=s.definition||p.trial!=s.trial||s.cursor !in listOf(p.expected,p.next))throw SQLiteException("자동영업 pending/커서 정의 불일치")}
            ContractResult.Success(s)
        }
    }
    fun activate(db:SQLiteDatabase,id:GameIdentity,cursor:RentalVisitCursor,version:ContentVersion,trial:Boolean):ContractResult<RentalVisitCursor> {
        when(val old=read(db,id)){is ContractResult.Success->return ContractResult.Success(old.value.cursor);is ContractResult.Failure->if(old.error!=ContractError.DATA_UNAVAILABLE)return old}
        val s=RentalProductState(id,cursor,null,null,version,trial);val raw=encoded(s)
        if(db.insertOrThrow("rental_product_marker",null,ContentValues().apply{put("profile",id.profileId.value);put("source",id.source.name)})<0)throw SQLiteException("자동영업 marker 삽입 무시")
        if(db.insertOrThrow("rental_product_state",null,values(s,raw))<0)throw SQLiteException("자동영업 state 삽입 무시")
        if((read(db,id) as? ContractResult.Success)?.value?.let{encoded(it)}!=raw)throw SQLiteException("자동영업 초기 실제 쓰기 누락")
        return ContractResult.Success(cursor)
    }
    private fun values(s:RentalProductState,raw:String)=ContentValues().apply{put("profile",s.identity.profileId.value);put("revision",s.cursor.revision.value);put("payload",raw);put("digest",ObservedDrivingJson.digest(raw))}
    fun update(db:SQLiteDatabase,before:RentalProductState,next:RentalProductState) {
        val raw=encoded(next)
        if(db.update("rental_product_state",values(next,raw),"profile=? AND revision=?",arrayOf(before.identity.profileId.value,before.cursor.revision.value.toString()))!=1)throw SQLiteException("자동영업 state CAS 무갱신")
        if((read(db,next.identity) as? ContractResult.Success)?.value?.let{encoded(it)}!=raw)throw SQLiteException("자동영업 state CAS 실제 쓰기 불일치")
    }
    fun original(db:SQLiteDatabase,p:PreparedRentalVisit):Pair<Boolean,OwnedVehicleId?> = db.rawQuery("SELECT command,assigned,host_command FROM visits WHERE id=?",arrayOf(p.visit.request.id.value)).use {
        if(!it.moveToFirst())return@use false to null
        if(it.isNull(2)||it.text(2)!=RentalProductJson.prepared(p).toString())throw SQLiteException("자동영업 최초 whole 방문 원문 누락/불일치")
        val expected=RentalVisitCommand.encode(p.visit)
        if(it.text(0)!=expected)throw SQLiteException("자동영업 원래 방문 명령 불일치")
        val assigned=if(it.isNull(1))null else OwnedVehicleId(it.text(1))
        db.rawQuery("SELECT owned_id,version,start,end,return_end FROM rentals WHERE id=?",arrayOf(p.visit.request.id.value)).use { row->
            val exists=row.moveToFirst()
            if(assigned==null && exists)throw SQLiteException("재방문 결과에 대여 이력 있음")
            if(assigned!=null && (!exists||row.text(0)!=assigned.value||row.text(1)!=p.visit.terms.rules.value||row.integer(2,0,Long.MAX_VALUE)!=p.visit.terms.start.value||row.integer(3,0,Long.MAX_VALUE)!=p.visit.terms.start.value+p.visit.request.hours*3600000L||row.integer(4,0,Long.MAX_VALUE)!=p.visit.terms.start.value+p.visit.request.hours*3600000L+p.visit.terms.returnMinutes*60000L))throw SQLiteException("방문 원래 대여 연결 불일치")
        }
        true to assigned
    }
    fun commit(store:SqliteGameChangeStore,db:SQLiteDatabase,p:PreparedRentalVisit):ContractResult<OwnedVehicleId?> {
        val old=original(db,p);if(old.first)return ContractResult.Success(old.second)
        val s=when(val r=read(db,p.identity)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        if(s.pending!=p||s.cursor!=p.expected)return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val v=p.visit;val assigned=when(val r=store.rental.assign(v.request,v.catalog,v.categories,v.hourlyRates,v.terms)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        val raw=RentalProductJson.prepared(p).toString()
        if(db.update("visits",ContentValues().apply{put("host_command",raw)},"id=? AND host_command IS NULL",arrayOf(v.request.id.value))!=1)throw SQLiteException("자동영업 whole 방문 저장 누락")
        val actual=original(db,p);if(!actual.first||actual.second!=assigned)throw SQLiteException("자동영업 실제 방문 결과 불일치")
        update(db,s,s.copy(cursor=p.next))
        return ContractResult.Success(assigned)
    }
}
