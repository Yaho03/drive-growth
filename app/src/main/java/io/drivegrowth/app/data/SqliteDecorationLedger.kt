package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*

/** 같은 helper/지갑의 꾸미기 상태·원결과. 호출은 기존 session worker에서 수행한다. */
class SqliteDecorationLedger internal constructor(private val store:SqliteGameChangeStore) {
    fun definitions():ContractResult<DecorationDefinitions> = store.decorationDefinitions()
    private fun identity(profile:ProfileId,source:InputSource=store.source):ContractResult<Unit> = when(val actual=store.localProfileId()) {
        is ContractResult.Failure->actual
        is ContractResult.Success->if(actual.value==profile&&source==store.source)ContractResult.Success(Unit)else ContractResult.Failure(ContractError.INVALID_INPUT)
    }
    /** helper가 보존한 미도입 marker에서만 실제 초기 상태를 명시 활성화한다. 소실된 marker는 재생성하지 않는다. */
    fun activate(profile:ProfileId):ContractResult<DecorationState> = store.atomic { db->
        when(val r=identity(profile)){is ContractResult.Failure->return@atomic r;else->Unit}
        val old=DecorationStorage.read(store,db,profile)
        if(old!=null)return@atomic ContractResult.Success(current(db,old))
        val initial=DecorationState(profile,store.source,Revision(0),store.readBalance(db),shopLevel(db),emptySet(),null)
        DecorationStorage.write(db,profile,store.source,null,initial)
        if(DecorationStorage.read(store,db,profile)!=initial)throw SQLiteException("꾸미기 활성화 실제 쓰기 누락")
        ContractResult.Success(initial)
    }
    /** 현재 잔액/가게 단계는 공통 원장에서 읽는다. 저장된 과거 잔액을 현재 값으로 내보내지 않는다. */
    fun state(profile:ProfileId):ContractResult<DecorationState> = store.atomic { db->
        when(val r=identity(profile)){is ContractResult.Failure->return@atomic r;else->Unit}
        val s=DecorationStorage.read(store,db,profile)?:return@atomic ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        ContractResult.Success(current(db,s))
    }
    fun settlement(command:DecorationCommand):ContractResult<DecorationSettlement?> = store.atomic {db->originalIn(db,command)}
    internal fun originalIn(db:SQLiteDatabase,c:DecorationCommand):ContractResult<DecorationSettlement?> {
        when(val r=identity(c.profileId,c.source)){is ContractResult.Failure->return r;else->Unit}
        DecorationStorage.read(store,db,c.profileId)
        val original=db.rawQuery("SELECT payload FROM decoration_requests WHERE request_id=?",arrayOf(c.requestId.value)).use { row->
            if(row.moveToFirst())DecorationJson.record(db,row.text(0),c.profileId,c.source).second else null
        }
        if(original==null&&store.requestUnavailable(db,c.requestId))return ContractResult.Failure(ContractError.STATE_CONFLICT)
        return replayDecorationChange(c,original)
    }
    internal fun prepareIn(db:SQLiteDatabase,c:DecorationCommand):ContractResult<DecorationPreparedChange> {
        when(val r=identity(c.profileId,c.source)){is ContractResult.Failure->return r;else->Unit}
        val s=DecorationStorage.read(store,db,c.profileId)?:return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val d=when(val r=definitions()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        return prepareDecorationChange(c,current(db,s),d)
    }
    /** 원요청 재생을 먼저 처리한다. 새 요청만 최신 상태/정의를 같은 거래에서 다시 검사한다. */
    fun commit(command:DecorationCommand):ContractResult<DecorationSettlement> = store.atomic {db->commitIn(db,command,false)}
    /** host가 같은 거래에서 SAVED를 확인하고 STARTED로 전이한 직후에만 호출한다. 저장된 STARTED 복구 입구가 아니다. */
    internal fun commitFromSavedCheckpointIn(db:SQLiteDatabase,command:DecorationCommand):ContractResult<DecorationSettlement> {
        check(db.inTransaction())
        return commitIn(db,command,true)
    }
    private fun commitIn(db:SQLiteDatabase,c:DecorationCommand,fromSavedCheckpoint:Boolean):ContractResult<DecorationSettlement> {
        when(val old=originalIn(db,c)){is ContractResult.Failure->return old;is ContractResult.Success->old.value?.let{return ContractResult.Success(it)}}
        val slot=when(val r=store.hostCommands.load(c.profileId)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        val started=HostCommandCheckpoint(c.profileId,HostCheckpointCommand.Decoration(c),HostCheckpointPhase.STARTED)
        if(fromSavedCheckpoint) {
            if(slot!=started)return ContractResult.Failure(ContractError.STATE_CONFLICT)
        } else if(slot!=null)return ContractResult.Failure(if(slot==started)ContractError.DATA_UNAVAILABLE else ContractError.STATE_CONFLICT)
        val stored=DecorationStorage.read(store,db,c.profileId)?:return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val before=current(db,stored)
        val d=when(val r=definitions()){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        val planned=when(val r=prepareDecorationChange(c,before,d)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        val receipt=planned.money?.let { money->
            val change=GameChange(c.requestId,TransactionKind.FACILITY,c.source,c.ruleVersion,money)
            when(val r=store.record(db,change,LedgerJson.command(change).toString())){is ContractResult.Failure->return r;is ContractResult.Success->r.value}
        }
        val result=DecorationSettlement(c,planned.state,receipt)
        DecorationStorage.write(db,c.profileId,c.source,stored,result.state)
        val raw=DecorationJson.record(c,before,d,result)
        val values=ContentValues().apply {put("request_id",c.requestId.value);put("revision",result.state.revision.value);put("payload",raw);put("digest",ObservedDrivingJson.digest(raw))}
        if(db.insertOrThrow("decoration_requests",null,values)<0)throw SQLiteException("꾸미기 원결과 무삽입")
        if(DecorationStorage.read(store,db,c.profileId)!=result.state||store.readBalance(db)!=result.state.balance||shopLevel(db)!=before.shopLevel)
            throw SQLiteException("꾸미기 소유/선택/revision/재화/가게 최종 실제 상태 불일치")
        when(val actual=originalIn(db,c)){is ContractResult.Failure->throw SQLiteException("꾸미기 실제 원결과 불일치");is ContractResult.Success->if(actual.value!=result)throw SQLiteException("꾸미기 실제 원결과 누락")}
        return ContractResult.Success(result)
    }
    private fun current(db:SQLiteDatabase,s:DecorationState)=s.copy(balance=store.readBalance(db),shopLevel=shopLevel(db))
    private fun shopLevel(db:SQLiteDatabase)=db.rawQuery("SELECT level FROM shop WHERE id=1",null).use {
        if(!it.moveToFirst())throw SQLiteException("꾸미기 공통 가게 소실")
        it.integer(0,1,Int.MAX_VALUE.toLong()).toInt()
    }
}

/** 도입 marker와 실제 원결과의 연속성을 검증한다. 별도 잔액·금융 원장은 없다. */
internal object DecorationStorage {
    fun createTables(db:SQLiteDatabase,source:InputSource) {
        db.execSQL("CREATE TABLE decoration_module(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL,digest TEXT NOT NULL)")
        db.execSQL("CREATE TABLE decoration_requests(request_id TEXT PRIMARY KEY NOT NULL,revision INTEGER UNIQUE NOT NULL CHECK(revision>0),payload TEXT NOT NULL,digest TEXT NOT NULL)")
        val profile=db.rawQuery("SELECT profile_id,source FROM initialization_profile WHERE id=1",null).use {
            if(!it.moveToFirst()||it.text(1)!=source.name)throw SQLiteException("꾸미기 도입의 실제 프로필 소실")
            ProfileId(it.text(0))
        }
        val raw=DecorationJson.module(profile,source,null)
        if(db.insertOrThrow("decoration_module",null,ContentValues().apply{put("id",1);put("payload",raw);put("digest",ObservedDrivingJson.digest(raw))})<0)throw SQLiteException("꾸미기 미도입 marker 무삽입")
        if(readModule(db,profile,source)!=null)throw SQLiteException("꾸미기 미도입 marker 실제 상태 불일치")
    }
    private fun readModule(db:SQLiteDatabase,profile:ProfileId,source:InputSource):DecorationState? = db.rawQuery("SELECT id,payload,digest FROM decoration_module",null).use {
        if(!it.moveToFirst()||it.integer(0,1,1)!=1L)throw SQLiteException("꾸미기 도입 marker 소실")
        val raw=it.text(1)
        if(it.text(2)!=ObservedDrivingJson.digest(raw))throw SQLiteException("꾸미기 도입 marker digest 불일치")
        val s=DecorationJson.module(raw,profile,source)
        if(it.moveToNext())throw SQLiteException("꾸미기 도입 marker 중복")
        s
    }
    fun read(store:SqliteGameChangeStore,db:SQLiteDatabase,profile:ProfileId):DecorationState? {
        db.rawQuery("SELECT source FROM account WHERE id=1",null).use{if(!it.moveToFirst()||it.text(0)!=store.source.name)throw SQLiteException("꾸미기 공통 계정 출처 손상")}
        val state=readModule(db,profile,store.source)
        var next=1L;var owned=emptySet<String>();var selected:String?=null;var last:DecorationState?=null
        db.rawQuery("SELECT request_id,revision,payload,digest FROM decoration_requests ORDER BY revision",null).use { row->while(row.moveToNext()) {
            if(state==null||row.integer(1,1,Int.MAX_VALUE.toLong())!=next)throw SQLiteException("꾸미기 도입/적용 revision 원이력 소실")
            val raw=row.text(2)
            if(row.text(3)!=ObservedDrivingJson.digest(raw))throw SQLiteException("꾸미기 원결과 digest 불일치")
            val(before,result)=DecorationJson.record(db,raw,profile,store.source)
            if(result.command.requestId.value!=row.text(0)||before.revision.value.toLong()!=next-1||result.state.revision.value.toLong()!=next||before.ownedIds!=owned||before.selectedId!=selected)
                throw SQLiteException("꾸미기 소유/선택의 원결과 연속성 손상")
            last=result.state;owned=result.state.ownedIds;selected=result.state.selectedId;next++
        }}
        if(state!=null&&(state.revision.value.toLong()!=next-1||state.ownedIds!=owned||state.selectedId!=selected||(last!=null&&state!=last)))
            throw SQLiteException("꾸미기 현재 상태와 원결과 불일치")
        return state
    }
    fun write(db:SQLiteDatabase,profile:ProfileId,source:InputSource,old:DecorationState?,next:DecorationState) {
        val raw=DecorationJson.module(profile,source,next);val before=DecorationJson.module(profile,source,old)
        val values=ContentValues().apply{put("payload",raw);put("digest",ObservedDrivingJson.digest(raw))}
        if(db.update("decoration_module",values,"id=1 AND payload=? AND digest=?",arrayOf(before,ObservedDrivingJson.digest(before)))!=1)throw SQLiteException("꾸미기 현재 상태 갱신 누락")
    }
}
