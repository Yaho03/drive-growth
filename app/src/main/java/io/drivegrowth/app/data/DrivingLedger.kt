package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.RequestId
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
            val settlement = if (stored.commit.amount.units > 0) readSettlement(db, requestId) else null
            return@atomic ContractResult.Success(stored.commit.copy(settlement = settlement))
        }
        if (hasSettlement(db, requestId)) return@atomic failure(ContractError.STATE_CONFLICT)

        val result = when (val settled = DrivingIncome(rules).settle(readDay(db, drive.departedOn), drive)) {
            is ContractResult.Failure -> return@atomic settled
            is ContractResult.Success -> settled.value
        }
        val recorded = result.change?.let {
            when (val r = store.record(db, it, LedgerJson.command(it).toString())) {
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
        ContractResult.Success(
            DriveCommit(drive.driveId, result.recordedMeters, result.rewardedMeters, result.amount, result.day, recorded),
        )
    }

    private fun command(rules: DrivingIncomeRules, drive: ConfirmedDrive) = JSONObject()
        .put("drive", drive.driveId).put("day", drive.departedOn.toString())
        .put("meters", drive.distance.meters).put("source", drive.distance.source.name)
        .put("rule", rules.ruleVersion.value).put("cap", rules.dailyCapMeters).put("perKm", rules.incomePerKm.units)

    private fun readDay(db: SQLiteDatabase, day: LocalDate): DrivingDay? = db.rawQuery(
        "SELECT rewarded FROM driving_days WHERE day = ? AND source = ?", arrayOf(day.toString(), store.source.name),
    ).use { if (it.moveToFirst()) DrivingDay(day, it.getLong(0)) else null }

    /** 저장된 주행 기록. commit의 settlement는 비워 두고 재생할 때 공용 정산에서 읽는다. */
    private class StoredDrive(val command: String, val commit: DriveCommit)

    private fun readDrive(db: SQLiteDatabase, driveId: String): StoredDrive? = db.rawQuery(
        "SELECT command, recorded, rewarded, amount, day, day_rewarded FROM driving_drives WHERE drive_id = ?", arrayOf(driveId),
    ).use {
        if (!it.moveToFirst()) return@use null
        val commit = DriveCommit(
            driveId, it.getLong(1), it.getLong(2), GameMoney(it.getLong(3)),
            DrivingDay(LocalDate.parse(it.getString(4)), it.getLong(5)), settlement = null,
        )
        StoredDrive(it.getString(0), commit)
    }

    private fun hasSettlement(db: SQLiteDatabase, requestId: RequestId): Boolean =
        db.rawQuery("SELECT 1 FROM settlements WHERE request_id = ?", arrayOf(requestId.value)).use { it.moveToFirst() }

    private fun readSettlement(db: SQLiteDatabase, requestId: RequestId): Settlement =
        db.rawQuery("SELECT result FROM settlements WHERE request_id = ?", arrayOf(requestId.value)).use {
            if (!it.moveToFirst()) throw JSONException("주행 수익 정산 누락")
            LedgerJson.settlement(JSONObject(it.getString(0)))
        }
}
