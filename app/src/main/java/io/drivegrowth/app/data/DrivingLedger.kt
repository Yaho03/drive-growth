package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.ObservedDistance
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.driving.ConfirmedDrive
import io.drivegrowth.core.driving.DrivingDay
import io.drivegrowth.core.driving.DrivingIncome
import io.drivegrowth.core.driving.DrivingIncomeRules
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

/**
 * 기본 주행 수익 한 건의 확정 결과.
 *
 * @param recordedMeters 기록 원본 거리.
 * @param rewardedMeters 하루 상한을 적용해 보상에 반영한 거리.
 * @param day 이 주행을 반영한 뒤의 하루 상태.
 * @param settlement 지급의 공용 정산. 0원이면 null이다.
 */
data class DriveCommit(
    val driveId: String,
    val recordedMeters: Long,
    val rewardedMeters: Long,
    val amount: GameMoney,
    val day: DrivingDay,
    val settlement: Settlement?,
)

/**
 * 기본 주행 수익과 출발일 기준 하루 반영 거리를 공용 원장과 같은 DB 트랜잭션으로 저장한다.
 * 같은 주행 ID·같은 명령은 당시 결과를 돌려주고 하루 반영 거리를 두 번 더하지 않는다.
 * 저장된 주행 기록은 원래 명령과 당시 하루 상태로 다시 계산한 값, 그리고 그 주행의 원래 지급 정산과 대조한다. 다르면 STORAGE_FAILURE다.
 * 공용 store 연결, 테이블 생성 호출, DB 버전 이전은 #122에서 통합한다.
 */
class DrivingLedger internal constructor(private val store: SqliteGameChangeStore) {
    internal companion object {
        fun createTables(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE driving_days (
                day TEXT NOT NULL, source TEXT NOT NULL, rewarded INTEGER NOT NULL CHECK (rewarded >= 0),
                PRIMARY KEY (day, source))""",
            )
            db.execSQL(
                """CREATE TABLE driving_drives (
                drive_id TEXT PRIMARY KEY NOT NULL, command TEXT NOT NULL, recorded INTEGER NOT NULL,
                rewarded INTEGER NOT NULL, amount INTEGER NOT NULL, day TEXT NOT NULL, day_rewarded INTEGER NOT NULL)""",
            )
        }
    }

    private fun failure(error: ContractError) = ContractResult.Failure(error)

    /** 출발일의 하루 반영 거리. 기록이 없으면 null이다. */
    fun day(day: LocalDate): ContractResult<DrivingDay?> = store.atomic { db -> ContractResult.Success(readDay(db, day)) }

    /**
     * 주행 한 건을 정산한다. 기존 주행 확인 → 하루 상태로 계산 → 지급·하루 상태·주행 기록을 한 트랜잭션으로 확정한다.
     * 같은 주행 ID에 다른 거리·날짜·규칙이 오거나, 공용 정산에 이미 같은 요청 ID가 있으면 STATE_CONFLICT다.
     */
    fun settle(rules: DrivingIncomeRules, drive: ConfirmedDrive): ContractResult<DriveCommit> = store.atomic { db ->
        if (drive.distance.source != store.source) return@atomic failure(ContractError.INVALID_INPUT)
        val command = command(rules, drive).toString()
        val requestId = RequestId("drive/${drive.driveId}")
        readDrive(db, drive.driveId)?.let { stored ->
            if (stored.command != command) return@atomic failure(ContractError.STATE_CONFLICT)
            return@atomic ContractResult.Success(stored.commit)
        }
        val restActive=db.rawQuery("SELECT 1 FROM rest_module",null).use{it.moveToFirst()}
        if(!restActive&&db.rawQuery("SELECT 1 FROM rest_contexts UNION ALL SELECT 1 FROM rest_departures UNION ALL SELECT 1 FROM rest_driving_receipts UNION ALL SELECT 1 FROM rest_log UNION ALL SELECT 1 FROM rest_head",null).use{it.moveToFirst()})throw android.database.sqlite.SQLiteException("휴식 marker 없는 이력에 기본 지급 불가")
        if(restActive)return@atomic failure(ContractError.STATE_CONFLICT)
        if (hasSettlement(db, requestId)) return@atomic failure(ContractError.STATE_CONFLICT)

        val result = when (val settled = DrivingIncome(rules).settle(readDay(db, drive.departedOn), drive)) {
            is ContractResult.Failure -> return@atomic settled
            is ContractResult.Success -> settled.value
        }
        val recorded = result.change?.let {
            when (val r = store.record(db, it, LedgerJson.command(it).toString(), SqliteGameChangeStore.RequestDomain.DRIVING)) {
                is ContractResult.Failure -> return@atomic r
                is ContractResult.Success -> r.value
            }
        }
        db.insertWithOnConflict("driving_days", null, ContentValues().apply {
            put("day", result.day.day.toString())
            put("source", store.source.name)
            put("rewarded", result.day.rewardedMeters)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        db.insertOrThrow("driving_drives", null, ContentValues().apply {
            put("drive_id", drive.driveId)
            put("command", command)
            put("recorded", result.recordedMeters)
            put("rewarded", result.rewardedMeters)
            put("amount", result.amount.units)
            put("day", result.day.day.toString())
            put("day_rewarded", result.day.rewardedMeters)
        })
        val committed=DriveCommit(drive.driveId,result.recordedMeters,result.rewardedMeters,result.amount,result.day,recorded)
        if(readDrive(db,drive.driveId)?.commit!=committed || (recorded!=null && store.readBalance(db)!=recorded.balance))
            throw android.database.sqlite.SQLiteException("주행 실제 이력/일누계/재화 쓰기 불일치")
        ContractResult.Success(committed)
    }

    /** 같은 Rest 외부 atomic 전용이다. composite 원결과/커서/ack 쓰기 뒤 shared reader가 재검증한다. */
    internal fun settleRestIn(db:SQLiteDatabase,c:RestPricedCommand):ContractResult<DriveCommit> {
        if(c.context.identity.source!=store.source)return failure(ContractError.INVALID_INPUT)
        val priced=when(val r=RestDrivingRecords.calculate(c)){is ContractResult.Failure->return r;is ContractResult.Success->r.value.price.settlement}
        if(readDay(db,priced.drive.departedOn)!=c.dayBefore||db.rawQuery("SELECT 1 FROM driving_drives WHERE drive_id=?",arrayOf(priced.drive.driveId)).use{it.moveToFirst()}||hasSettlement(db,RequestId("drive/${priced.drive.driveId}")))return failure(ContractError.STATE_CONFLICT)
        val settlement=priced.change?.let{change->when(val r=store.record(db,change,LedgerJson.command(change).toString(),SqliteGameChangeStore.RequestDomain.DRIVING)){is ContractResult.Failure->return r;is ContractResult.Success->r.value}}
        if(db.insertWithOnConflict("driving_days",null,ContentValues().apply{put("day",priced.day.day.toString());put("source",store.source.name);put("rewarded",priced.day.rewardedMeters)},SQLiteDatabase.CONFLICT_REPLACE)<0||readDay(db,priced.day.day)!=priced.day)throw android.database.sqlite.SQLiteException("휴식 주행 일누계 실제 저장 불일치")
        if(db.insertOrThrow("driving_drives",null,ContentValues().apply{put("drive_id",priced.drive.driveId);put("command",RestDrivingJson.priced(c).toString());put("recorded",priced.recordedMeters);put("rewarded",priced.rewardedMeters);put("amount",priced.amount.units);put("day",priced.day.day.toString());put("day_rewarded",priced.day.rewardedMeters)})<0)throw android.database.sqlite.SQLiteException("휴식 주행 원명령 무삽입")
        return ContractResult.Success(DriveCommit(priced.drive.driveId,priced.recordedMeters,priced.rewardedMeters,priced.amount,priced.day,settlement))
    }

    private val records=DrivingRecordReader(store.source)
    private fun command(rules:DrivingIncomeRules,drive:ConfirmedDrive)=records.command(rules,drive)
    private fun readDay(db:SQLiteDatabase,day:LocalDate)=records.readDay(db,day)
    private fun readDrive(db:SQLiteDatabase,id:String)=records.readDrive(db,id)
    private fun hasSettlement(db:SQLiteDatabase,id:RequestId)=records.hasSettlement(db,id)
}

/** 같은 DB를 받는 recorder도 기존 원장의 원문/결과 검증을 재사용한다. 지급 API가 아니다. */
internal class DrivingRecordReader(private val source:InputSource) {
    fun command(rules: DrivingIncomeRules, drive: ConfirmedDrive) = JSONObject()
        .put("drive", drive.driveId).put("day", drive.departedOn.toString())
        .put("meters", drive.distance.meters).put("source", drive.distance.source.name)
        .put("rule", rules.ruleVersion.value).put("cap", rules.dailyCapMeters).put("perKm", rules.incomePerKm.units)

    fun readDay(db: SQLiteDatabase, day: LocalDate): DrivingDay? = db.rawQuery(
        "SELECT rewarded FROM driving_days WHERE day = ? AND source = ?", arrayOf(day.toString(), source.name),
    ).use { if (it.moveToFirst()) DrivingDay(day, it.integer(0, 0, Long.MAX_VALUE)) else null }

    /** 대조를 마친 주행 기록과 당시 결과. */
    class StoredDrive(val command: String, val commit: DriveCommit,val rest:VerifiedRestDrive?=null)

    /**
     * 저장된 주행 기록을 읽는다. 원래 명령의 규칙·주행과 당시 하루 상태(반영 후 누적 - 이번 반영)로 다시 계산한 결과가
     * 저장된 거리·반영 거리·금액·하루 상태와 같아야 한다. 지급이 있으면 원래 DRIVING 지급과 맞는 정산이 있어야 하고, 0원이면 정산이 없어야 한다.
     */
    fun readDrive(db: SQLiteDatabase, driveId: String): StoredDrive? = db.rawQuery(
        "SELECT command, recorded, rewarded, amount, day, day_rewarded FROM driving_drives WHERE drive_id = ?", arrayOf(driveId),
    ).use {
        if (!it.moveToFirst()) return@use null
        val command = it.text(0)
        val recorded = it.integer(1, 0, Long.MAX_VALUE)
        val rewarded = it.integer(2, 0, Long.MAX_VALUE)
        val amount = GameMoney(it.integer(3, 0, Long.MAX_VALUE))
        val day = DrivingDay(DeliveryJson.date(it.text(4)), it.integer(5, 0, Long.MAX_VALUE))
        val json=JSONObject(command)
        val rest=if(json.has("type")) {
            val c=RestDrivingJson.priced(json)
            if(RestDrivingJson.priced(c).toString()!=command)throw JSONException("휴식 주행 명령 형식 오류")
            RestDrivingRecords.verify(db,source,c)
        }else null
        val base=if(rest==null)commandInput(json) else null
        val drive=rest?.calculation?.price?.settlement?.drive?:checkNotNull(base).second
        if(drive.distance.source!=source||drive.driveId!=driveId||rest==null&&command(checkNotNull(base).first,drive).toString()!=command)throw JSONException("주행 명령 형식 오류")
        if (rewarded > day.rewardedMeters) throw JSONException("하루 반영 거리 불일치")
        // 하루 반영 거리는 줄지 않는다. 지금 하루 상태가 이 주행 직후 값보다 작으면 둘 중 하나가 손상이다.
        val current = readDay(db, day.day) ?: throw JSONException("하루 상태 누락")
        if (current.rewardedMeters < day.rewardedMeters) throw JSONException("하루 반영 거리 불일치")
        val before = DrivingDay(day.day, day.rewardedMeters - rewarded)
        val expected = rest?.calculation?.price?.settlement?:when (val result = DrivingIncome(checkNotNull(base).first).settle(before, drive)) {
            is ContractResult.Failure -> throw JSONException("저장된 주행 재계산 실패: ${result.error}")
            is ContractResult.Success -> result.value
        }
        if(rest!=null&&(rest.command.dayBefore?.rewardedMeters?:0L)!=before.rewardedMeters)throw JSONException("휴식 주행 원 일누계 불일치")
        if (expected.recordedMeters != recorded || expected.rewardedMeters != rewarded || expected.amount != amount || expected.day != day) {
            throw JSONException("주행 결과가 원래 명령과 다름")
        }
        val requestId = RequestId("drive/$driveId")
        val settlement = when (val change = expected.change) {
            null -> if (hasSettlement(db, requestId)) throw JSONException("0원 주행에 정산이 있음") else null
            else -> readRecordedSettlement(db, change) ?: throw JSONException("주행 수익 정산 누락")
        }
        val commit=DriveCommit(driveId, recorded, rewarded, amount, day, settlement)
        if(rest!=null)RestDrivingRecords.verifyReceipt(db,rest,commit)
        StoredDrive(command,commit,rest)
    }

    private fun commandInput(j: JSONObject): Pair<DrivingIncomeRules, ConfirmedDrive> {
        val rules = DrivingIncomeRules(
            RuleVersion(j.getString("rule")), DeliveryJson.long(j, "cap", 1, Long.MAX_VALUE), GameMoney(DeliveryJson.long(j, "perKm", 0, Long.MAX_VALUE)),
        )
        val drive = ConfirmedDrive(
            j.getString("drive"), DeliveryJson.date(j.getString("day")),
            ObservedDistance(DeliveryJson.long(j, "meters", 0, Long.MAX_VALUE), InputSource.valueOf(j.getString("source"))),
        )
        return rules to drive
    }

    fun hasSettlement(db: SQLiteDatabase, requestId: RequestId): Boolean =
        db.rawQuery("SELECT 1 FROM settlements WHERE request_id = ?", arrayOf(requestId.value)).use { it.moveToFirst() }
}
