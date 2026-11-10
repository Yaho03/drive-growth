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
    class StoredDrive(val command: String, val commit: DriveCommit)

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
        val (rules, drive) = commandInput(JSONObject(command))
        if (drive.driveId != driveId || command(rules, drive).toString() != command) throw JSONException("주행 명령 형식 오류")
        if (rewarded > day.rewardedMeters) throw JSONException("하루 반영 거리 불일치")
        // 하루 반영 거리는 줄지 않는다. 지금 하루 상태가 이 주행 직후 값보다 작으면 둘 중 하나가 손상이다.
        val current = readDay(db, day.day) ?: throw JSONException("하루 상태 누락")
        if (current.rewardedMeters < day.rewardedMeters) throw JSONException("하루 반영 거리 불일치")
        val before = DrivingDay(day.day, day.rewardedMeters - rewarded)
        val expected = when (val result = DrivingIncome(rules).settle(before, drive)) {
            is ContractResult.Failure -> throw JSONException("저장된 주행 재계산 실패: ${result.error}")
            is ContractResult.Success -> result.value
        }
        if (expected.recordedMeters != recorded || expected.rewardedMeters != rewarded || expected.amount != amount || expected.day != day) {
            throw JSONException("주행 결과가 원래 명령과 다름")
        }
        val requestId = RequestId("drive/$driveId")
        val settlement = when (val change = expected.change) {
            null -> if (hasSettlement(db, requestId)) throw JSONException("0원 주행에 정산이 있음") else null
            else -> readRecordedSettlement(db, change) ?: throw JSONException("주행 수익 정산 누락")
        }
        StoredDrive(command, DriveCommit(driveId, recorded, rewarded, amount, day, settlement))
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
