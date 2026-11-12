package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.core.contract.*
import org.json.JSONObject

/** D 포트 그대로 같은 세션의 db에서 동작한다. 별도 연결·지갑·driver를 만들지 않는다. */
class SqliteObservedDrivingJournal:ObservedDrivingJournal {
    companion object {
        internal fun createTables(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE observed_driving_marker(profile TEXT PRIMARY KEY NOT NULL,source TEXT NOT NULL)")
            db.execSQL("CREATE TABLE observed_driving_state(profile TEXT PRIMARY KEY NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL,digest TEXT NOT NULL)")
            db.execSQL("CREATE TABLE observed_driving_receipts(drive_id TEXT PRIMARY KEY NOT NULL,profile TEXT NOT NULL,source TEXT NOT NULL,payload TEXT NOT NULL,digest TEXT NOT NULL)")
        }
    }
    private fun invalid()=ContractResult.Failure(ContractError.INVALID_INPUT)
    private fun conflict()=ContractResult.Failure(ContractError.STATE_CONFLICT)
    private fun identity(db:SQLiteDatabase,id:GameIdentity):Boolean {
        check(db.inTransaction())
        val profile=db.rawQuery("SELECT profile_id,source FROM initialization_profile WHERE id=1",null).use {if(!it.moveToFirst())throw SQLiteException("관측 실제 프로필 누락");it.text(0) to it.text(1)}
        val source=db.rawQuery("SELECT source FROM account WHERE id=1",null).use{if(!it.moveToFirst())throw SQLiteException("관측 실제 계정 누락");it.text(0)}
        return profile.first==id.profileId.value && profile.second==id.source.name && source==id.source.name
    }
    /** UI조회가 아닌 기능 활성화 시 명시 호출. marker가 있는데 state가 없으면 재초기화하지 않는다. */
    fun activate(db:SQLiteDatabase,id:GameIdentity):ContractResult<RecordingState> {
        if(!identity(db,id))return invalid()
        val active=marker(db,id)
        if(active)return read(db,id)
        val orphan=db.rawQuery("SELECT 1 FROM observed_driving_state UNION ALL SELECT 1 FROM observed_driving_receipts",null).use{it.moveToFirst()}
        if(orphan)throw SQLiteException("관측 marker 없는 진행/원정산")
        val initial=RecordingState(id,0,null,null,null,null,null,emptyList());val encoded=ObservedDrivingJson.state(initial).toString()
        insert(db,"observed_driving_marker",ContentValues().apply{put("profile",id.profileId.value);put("source",id.source.name)})
        insert(db,"observed_driving_state",values(initial,encoded))
        if(!marker(db,id) || (read(db,id) as? ContractResult.Success)?.value!=initial)throw SQLiteException("관측 활성화 실제 쓰기 누락")
        return ContractResult.Success(initial)
    }
    private fun marker(db:SQLiteDatabase,id:GameIdentity):Boolean = db.rawQuery("SELECT profile,source FROM observed_driving_marker",null).use {
        if(!it.moveToFirst())return@use false
        if(it.text(0)!=id.profileId.value||it.text(1)!=id.source.name||it.moveToNext())throw SQLiteException("관측 marker 출처/프로필 손상")
        true
    }
    override fun read(db:SQLiteDatabase,identity:GameIdentity):ContractResult<RecordingState> {
        if(!identity(db,identity))return invalid()
        if(!marker(db,identity)) {
            if(db.rawQuery("SELECT 1 FROM observed_driving_state UNION ALL SELECT 1 FROM observed_driving_receipts",null).use{it.moveToFirst()})throw SQLiteException("관측 marker 없는 저장 이력")
            return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        }
        val state=db.rawQuery("SELECT profile,revision,payload,digest FROM observed_driving_state",null).use {
            if(!it.moveToFirst())throw SQLiteException("관측 marker 이후 state 소실")
            val raw=it.text(2);val decoded=ObservedDrivingJson.state(JSONObject(raw))
            if(decoded.identity!=identity||it.text(0)!=identity.profileId.value||it.integer(1,0,Long.MAX_VALUE)!=decoded.revision||it.text(3)!=ObservedDrivingJson.digest(raw)||ObservedDrivingJson.state(decoded).toString()!=raw||it.moveToNext())throw SQLiteException("관측 전체 진행 원문 손상")
            validate(decoded);decoded
        }
        return ContractResult.Success(state)
    }
    private fun validate(s:RecordingState) {
        val trips=s.outbox+listOfNotNull(s.trip)
        if(trips.map{it.id}.toSet().size!=trips.size)throw SQLiteException("관측 주행 ID 중복")
        for(t in trips) {
            if(t.source!=s.identity.source || t.segments.any{it.from.source!=s.identity.source||it.to.source!=s.identity.source} || t.gaps.any{it.observation?.source?.let{v->v!=s.identity.source}==true})throw SQLiteException("관측 출처 혼합")
            val total=t.segments.fold(java.math.BigDecimal.ZERO){a,b->a+b.meters}
            if(total.compareTo(t.meters)!=0)throw SQLiteException("관측 known 구간 합 불일치")
            for(segment in t.segments) {
                val a=(segment.from.speedMps as? Observation.Available)?.value;val b=(segment.to.speedMps as? Observation.Available)?.value
                if(a==null||b==null||!a.isFinite()||!b.isFinite()||kotlin.math.abs(a)>100||kotlin.math.abs(b)>100||segment.to.receivedElapsedMs<=segment.from.receivedElapsedMs)throw SQLiteException("관측 적산 구간 손상")
                val distance=java.math.BigDecimal.valueOf(kotlin.math.abs(a)).add(java.math.BigDecimal.valueOf(kotlin.math.abs(b))).multiply(java.math.BigDecimal.valueOf(segment.to.receivedElapsedMs-segment.from.receivedElapsedMs)).divide(java.math.BigDecimal(2000))
                if(distance!=segment.meters)throw SQLiteException("관측 구간 거리 불일치")
            }
        }
        if(s.anchor?.source?.let{it!=s.identity.source}==true)throw SQLiteException("관측 anchor 출처 불일치")
    }
    override fun save(db:SQLiteDatabase,identity:GameIdentity,expectedRevision:Long,next:RecordingState):ContractResult<Unit> {
        val before=when(val loaded=read(db,identity)){is ContractResult.Failure->return loaded;is ContractResult.Success->loaded.value}
        if(next.identity!=identity)return invalid()
        if(before.revision!=expectedRevision||expectedRevision==Long.MAX_VALUE||next.revision!=expectedRevision+1)return conflict()
        validate(next)
        for(trip in next.outbox) if(before.outbox.none{it.id==trip.id} && DrivingRecordReader(identity.source).readDrive(db,trip.id)!=null)
            return conflict() // 기존 LocalDate-only 정산에 현재 출발 metadata를 붙이지 않는다.
        if(next.trip!=null && before.trip?.id!=next.trip.id && DrivingRecordReader(identity.source).readDrive(db,next.trip.id)!=null)return conflict()
        val removed=before.outbox.filter{old->next.outbox.none{it.id==old.id}}
        for(trip in removed)when(val original=committed(db,identity,trip.id)) {
            is ContractResult.Failure->return original
            is ContractResult.Success->{val receipt=original.value;if(receipt==null||!sameTrip(receipt.trip,trip))return conflict()}
        }
        for(old in before.outbox)next.outbox.firstOrNull{it.id==old.id}?.let{if(!sameTrip(it,old))return conflict()}
        val raw=ObservedDrivingJson.state(next).toString()
        if(db.update("observed_driving_state",values(next,raw),"profile=? AND revision=?",arrayOf(identity.profileId.value,expectedRevision.toString()))!=1)throw SQLiteException("관측 CAS 무갱신")
        val confirmed=(read(db,identity) as? ContractResult.Success)?.value
        if(confirmed==null || ObservedDrivingJson.state(confirmed).toString()!=raw)throw SQLiteException("관측 CAS 실제 쓰기 불일치")
        return ContractResult.Success(Unit)
    }
    override fun recordCommit(db:SQLiteDatabase,identity:GameIdentity,receipt:ObservedDrivingReceipt):ContractResult<Unit> {
        val original=committed(db,identity,receipt.trip.id)
        if(original is ContractResult.Failure)return original
        (original as ContractResult.Success).value?.let{return if(ObservedDrivingJson.receipt(it).toString()==ObservedDrivingJson.receipt(receipt).toString())ContractResult.Success(Unit) else conflict()}
        when(val state=read(db,identity)){is ContractResult.Failure->return state;is ContractResult.Success->if(state.value.outbox.none{sameTrip(it,receipt.trip)})return conflict()}
        validateReceipt(db,identity,receipt)
        val raw=ObservedDrivingJson.receipt(receipt).toString()
        insert(db,"observed_driving_receipts",ContentValues().apply{put("drive_id",receipt.trip.id);put("profile",identity.profileId.value);put("source",identity.source.name);put("payload",raw);put("digest",ObservedDrivingJson.digest(raw))})
        when(val confirmed=committed(db,identity,receipt.trip.id)) {
            is ContractResult.Failure->return confirmed
            is ContractResult.Success->{val actual=confirmed.value;if(actual==null||ObservedDrivingJson.receipt(actual).toString()!=raw)throw SQLiteException("관측 원출발 provenance 실제 원문 불일치")}
        }
        return ContractResult.Success(Unit)
    }
    override fun committed(db:SQLiteDatabase,identity:GameIdentity,driveId:String):ContractResult<ObservedDrivingReceipt?> {
        if(!identity(db,identity))return invalid()
        if(!marker(db,identity)) {
            if(db.rawQuery("SELECT 1 FROM observed_driving_state UNION ALL SELECT 1 FROM observed_driving_receipts",null).use{it.moveToFirst()})throw SQLiteException("관측 marker 없는 저장 이력")
            return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        }
        val result=db.rawQuery("SELECT profile,source,payload,digest FROM observed_driving_receipts WHERE drive_id=?",arrayOf(driveId)).use {
            if(!it.moveToFirst())return@use null
            val raw=it.text(2);val receipt=ObservedDrivingJson.receipt(JSONObject(raw))
            if(it.text(0)!=identity.profileId.value||it.text(1)!=identity.source.name||receipt.trip.id!=driveId||it.text(3)!=ObservedDrivingJson.digest(raw)||ObservedDrivingJson.receipt(receipt).toString()!=raw)throw SQLiteException("관측 원출발 원문 손상")
            validateReceipt(db,identity,receipt);receipt
        }
        return ContractResult.Success(result)
    }
    /** 종료 입력으로 끝난 유일한 영수증을 기존 원거래 검증 경로로 다시 읽는다. */
    internal fun committedEndingAt(
        db: SQLiteDatabase, identity: GameIdentity, frame: DrivingFrame,
    ): ContractResult<ObservedDrivingReceipt?> {
        val driveId = db.rawQuery("SELECT drive_id,payload FROM observed_driving_receipts WHERE profile=? AND source=?",
            arrayOf(identity.profileId.value, identity.source.name)).use { rows ->
            var match: String? = null
            while (rows.moveToNext()) {
                val original = ObservedDrivingJson.receipt(JSONObject(rows.getString(1)))
                if (original.trip.segments.lastOrNull()?.to != frame.observation || original.trip.zone != frame.zone)
                    continue
                if (match != null) return@use null
                match = rows.getString(0)
            }
            match
        } ?: return ContractResult.Success(null)
        return committed(db, identity, driveId)
    }

    private fun validateReceipt(db:SQLiteDatabase,id:GameIdentity,r:ObservedDrivingReceipt) {
        validate(RecordingState(id,0,null,null,null,null,null,listOf(r.trip)))
        val rules=(r.trip.rules as? ContractResult.Success)?.value?:throw SQLiteException("관측 출발 규칙 누락")
        val drive=try{r.trip.confirmed(id.source)}catch(e:ArithmeticException){throw SQLiteException("관측 원거리 범위 손상",e)}?:throw SQLiteException("관측 출발 달력 누락")
        val reader=DrivingRecordReader(id.source);val original=reader.readDrive(db,r.trip.id)?:throw SQLiteException("관측 실제 주행 정산 누락")
        val matches=original.rest?.let{ObservedDrivingJson.trip(it.command.context.trip).toString()==ObservedDrivingJson.trip(r.trip).toString()}
            ?: (original.command==reader.command(rules,drive).toString())
        if(!matches||original.commit!=r.commit)throw SQLiteException("관측 원래 주행 정산 불일치")
    }
    private fun sameTrip(a:ObservedTrip,b:ObservedTrip)=ObservedDrivingJson.trip(a).toString()==ObservedDrivingJson.trip(b).toString()
    private fun values(s:RecordingState,raw:String)=ContentValues().apply{put("profile",s.identity.profileId.value);put("revision",s.revision);put("payload",raw);put("digest",ObservedDrivingJson.digest(raw))}
    private fun insert(db:SQLiteDatabase,table:String,values:ContentValues){if(db.insertOrThrow(table,null,values)<0)throw SQLiteException("관측 필수 삽입 누락")}
}
