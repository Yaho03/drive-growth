package io.drivegrowth.app.simulation

import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import io.drivegrowth.app.data.LedgerJson
import io.drivegrowth.app.data.ObservedDrivingJson
import io.drivegrowth.app.data.RentalProductJson
import io.drivegrowth.app.data.RentalProductState
import io.drivegrowth.app.data.RentalProductStorage
import io.drivegrowth.app.data.SqliteObservedDrivingJournal
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.driving.DrivingFrame
import io.drivegrowth.app.host.driving.DrivingRecordingResult
import io.drivegrowth.app.host.driving.ObservedDrivingReceipt
import io.drivegrowth.app.host.driving.RecordingClock
import io.drivegrowth.app.runtime.rental.RentalRuntimeSnapshot
import io.drivegrowth.app.runtime.rental.product.PreparedRentalVisit
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.RequestId
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 실제 소비 완료를 기록하고 새 owner에서는 같은 모의 입력과 SQLite 원결과를 대조한다. */
internal class SimulationTerminalProof(
    private val prefs: SharedPreferences,
    private val games: GameSessionFactory,
) {
    private val driving = SqliteObservedDrivingJournal()

    fun remember(timeline: SimulationTimeline, recorded: DrivingRecordingResult, rental: RentalRuntimeSnapshot) {
        if (recorded.waiting != null || rental.deferred.isNotEmpty()) return
        val frame = timeline.frame()
        if (recorded.state.anchor != frame.observation) return
        val committed = recorded.committed.filter { endsWith(it, frame) }
        if (committed.size > 1) return
        val evidence = evidence(timeline, committed.singleOrNull()?.trip?.id, recorded, rental) ?: return
        if (!prefs.edit().putString(KEY, evidence).commit()) {
            Log.w("DriveGrowthSimulation", "simulation_terminal_receipt_write_failed")
        }
    }

    fun matches(timeline: SimulationTimeline): Boolean = try {
        val stored = prefs.getString(KEY, null)
        if (stored == null) false else {
            val receipt = JSONObject(stored)
            // 같은 원입력을 가리킬 때만 DB를 열어 원거래를 대조한다.
            receipt.getJSONObject("clock").toString() == RentalProductJson.clock(timeline.clock).toString() &&
                evidence(timeline, receipt.getString("drive")) == stored
        }
    } catch (_: RuntimeException) {
        false
    } catch (_: JSONException) {
        false
    }

    private fun evidence(
        timeline: SimulationTimeline,
        driveId: String?,
        recorded: DrivingRecordingResult? = null,
        rental: RentalRuntimeSnapshot? = null,
    ): String? {
        val game = (games.open() as? ContractResult.Success)?.value ?: return null
        return game.use {
            val identity = game.identity
            if (identity.source != InputSource.SIMULATION) return@use null
            val frame = timeline.frame()
            val checked = game.store.atomic { db ->
                val state = (driving.read(db, identity) as? ContractResult.Success)?.value
                val schedule = (RentalProductStorage.read(db, identity) as? ContractResult.Success)?.value
                // 정상 pause는 anchor를 비운다. 같은 시각만으로 완료를 판단하지 않는다.
                if (state == null || state.trip != null || state.outbox.isNotEmpty() ||
                    state.boundary != RecordingClock(timeline.elapsedMs, timeline.clock.epoch) ||
                    (state.anchor != null && state.anchor != frame.observation) ||
                    (recorded != null && state != recorded.state) || schedule == null || schedule.pending != null
                ) return@atomic ContractResult.Success(null)
                val id = driveId ?: originalDriveId(db, identity, frame) ?: return@atomic ContractResult.Success(null)
                val original = (driving.committed(db, identity, id) as? ContractResult.Success)?.value
                if (original == null || !endsWith(original, frame)) return@atomic ContractResult.Success(null)
                // 같은 DB 연결·스레드의 거래 안에서 영업 schedule, 방문 원문, 배정 및 정산을 읽는다.
                val first = (game.store.rental.firstCompletedRental() as? ContractResult.Success)
                    ?: return@atomic ContractResult.Success(null)
                if (rental != null && rental.firstCompleted != first.value) return@atomic ContractResult.Success(null)
                val visits = originalVisits(db, game, schedule, first.value?.rentalId)
                    ?: return@atomic ContractResult.Success(null)
                ContractResult.Success(JSONObject()
                    .put("type", KEY)
                    .put("profile", identity.profileId.value)
                    .put("clock", RentalProductJson.clock(timeline.clock))
                    .put("observation", ObservedDrivingJson.observation(frame.observation))
                    .put("drive", id)
                    .put("original", ObservedDrivingJson.digest(ObservedDrivingJson.receipt(original).toString()))
                    .put("rentalCursor", RentalProductJson.cursor(schedule.cursor))
                    .put("rentalDefinition", schedule.definition.value)
                    .put("rentalTrial", schedule.trial)
                    .put("rentalVisits", visits)
                    .put("firstRental", first.value?.rentalId?.value ?: JSONObject.NULL)
                    .toString())
            }
            (checked as? ContractResult.Success)?.value
        }
    }

    private fun endsWith(receipt: ObservedDrivingReceipt, frame: DrivingFrame): Boolean =
        receipt.trip.segments.lastOrNull()?.to == frame.observation && receipt.trip.zone == frame.zone

    // 주행만 먼저 확정된 재시도의 commit 목록은 비어 있다. 같은 원 입력으로 끝난 영수증 하나만 찾는다.
    // 호출자는 식별한 ID를 기존 journal의 정산 검증 경로로 다시 조회한다.
    private fun originalDriveId(db: SQLiteDatabase, identity: GameIdentity, frame: DrivingFrame): String? =
        db.rawQuery("SELECT drive_id,payload FROM observed_driving_receipts WHERE profile=? AND source=?",
            arrayOf(identity.profileId.value, identity.source.name)).use { rows ->
            var id: String? = null
            while (rows.moveToNext()) {
                val original = ObservedDrivingJson.receipt(JSONObject(rows.getString(1)))
                if (!endsWith(original, frame)) continue
                if (id != null) return@use null
                id = rows.getString(0)
            }
            id
        }

    private fun originalVisits(db: SQLiteDatabase, game: GameSession, schedule: RentalProductState, first: RequestId?): String? =
        db.rawQuery("SELECT id,host_command FROM visits WHERE host_command IS NOT NULL ORDER BY rowid", null).use { rows ->
            val evidence = JSONArray()
            var previous: PreparedRentalVisit? = null
            var sequence = 0L
            var foundFirst = first == null
            while (rows.moveToNext()) {
                if (sequence >= schedule.cursor.sequence) return@use null
                val raw = rows.getString(1)
                val visit = RentalProductJson.prepared(JSONObject(raw))
                val id = visit.visit.request.id
                if (id.value != rows.getString(0) || visit.identity != game.identity ||
                    visit.expected.sequence != sequence || visit.expected.revision.value.toLong() != sequence ||
                    visit.next.sequence != sequence + 1 || visit.next.revision.value.toLong() != sequence + 1 ||
                    (previous != null && previous.next != visit.expected)
                ) return@use null
                val (exists, assigned) = RentalProductStorage.original(db, visit)
                if (!exists) return@use null
                val completed = (game.store.rental.completedRental(id) as? ContractResult.Success)
                    ?: return@use null
                val settlement = completed.value?.receipt?.settlement
                if (completed.value != null && completed.value?.ownedVehicleId != assigned) return@use null
                val originalSettlement = db.rawQuery("SELECT command,result FROM settlements WHERE request_id=?",
                    arrayOf("rental/settle/${id.value}")).use { row ->
                    if (!row.moveToFirst()) null else row.getString(0) to row.getString(1)
                }
                val expectedSettlement = settlement?.let {
                    JSONArray(listOf("settle", id.value)).toString() to LedgerJson.settlement(it).toString()
                }
                if (originalSettlement != expectedSettlement) return@use null
                if (id == first && settlement != null) foundFirst = true
                evidence.put(JSONArray(listOf(id.value, raw, assigned?.value, expectedSettlement?.second)))
                previous = visit
                sequence++
            }
            if (!foundFirst || sequence != schedule.cursor.sequence || sequence != schedule.cursor.revision.value.toLong() ||
                (previous != null && (previous.next != schedule.cursor ||
                    previous.definitionVersion != schedule.definition || previous.trial != schedule.trial))
            ) return@use null
            ObservedDrivingJson.digest(evidence.toString())
        }

    private companion object {
        const val KEY = "terminal-consumed-v2"
    }
}
