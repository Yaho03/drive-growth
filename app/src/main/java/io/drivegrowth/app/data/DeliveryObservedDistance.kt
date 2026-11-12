package io.drivegrowth.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.RecordingPolicy
import io.drivegrowth.app.host.driving.RecordingState
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DeliveryInProgress
import org.json.JSONObject
import java.math.RoundingMode

internal data class DeliveryDistanceSession(val receiver: String, val maxAgeMs: Long, val elapsedRealtime: () -> Long) {
    fun fresh(observedElapsed: Long, now: Long = elapsedRealtime()) =
        observedElapsed >= 0 && now >= observedElapsed && now - observedElapsed <= maxAgeMs
}

/** 현재 탁송 경로에는 관측 참조만 저장한다. 거리와 정산은 기존 주행 원장에서 읽는다. */
internal object DeliveryObservedDistance {
    private const val COLUMN = "observed_binding"
    private val missing = Observation.Missing(MissingReason.NO_DATA)

    private data class Route(val request: RequestId, val id: RouteId)
    private data class Binding(val profile: ProfileId, val receiver: String, val revision: Long,
        val anchorUtc: Long, val anchorElapsed: Long, val notBeforeUtc: Long, val notBeforeElapsed: Long,
        val drive: String? = null,
        val departureUtc: Long? = null, val departureElapsed: Long? = null, val sealed: Boolean = false) {
        fun matches(state: RecordingState) = state.identity.profileId == profile && state.receiver == receiver &&
            state.revision >= revision && state.anchor?.receivedAt?.value == anchorUtc &&
            state.anchor?.receivedElapsedMs == anchorElapsed
    }

    fun addColumn(db: SQLiteDatabase) {
        val existing = db.rawQuery("PRAGMA table_info(delivery_routes)", null).use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == COLUMN) {
                if (rows.getString(2) != "TEXT" || rows.getInt(3) != 0 || !rows.isNull(4) || rows.getInt(5) != 0)
                    throw SQLiteException("탁송 거리 참조 열 불일치")
                found = true
            }
            found
        }
        if (!existing) db.execSQL("ALTER TABLE delivery_routes ADD COLUMN $COLUMN TEXT")
        else db.rawQuery("SELECT 1 FROM delivery_routes WHERE $COLUMN IS NOT NULL LIMIT 1", null).use {
            if (it.moveToFirst()) throw SQLiteException("이전 탁송 경로에 새 거리 참조가 있음")
        }
    }

    fun arm(db: SQLiteDatabase, identity: GameIdentity, progress: DeliveryInProgress, at: EpochMillis,
        session: DeliveryDistanceSession?): ContractResult<Unit> {
        if (identity.source != InputSource.VEHICLE || session == null) return ContractResult.Success(Unit)
        val state = when (val read = SqliteObservedDrivingJournal().read(db, identity)) {
            is ContractResult.Failure -> return if (read.error == ContractError.DATA_UNAVAILABLE) ContractResult.Success(Unit) else read
            is ContractResult.Success -> read.value
        }
        val anchor = state.anchor
        val elapsed = session.elapsedRealtime()
        if (state.trip == null && state.receiver == session.receiver && anchor != null && parked(anchor) &&
            anchor.source == identity.source && anchor.receivedAt.value <= at.value &&
            session.fresh(anchor.receivedElapsedMs, elapsed)) {
            val route = Route(progress.active.requestId, progress.route)
            write(db, route, null, Binding(identity.profileId, session.receiver, state.revision,
                anchor.receivedAt.value, anchor.receivedElapsedMs, at.value, elapsed))
        }
        return ContractResult.Success(Unit)
    }

    fun advance(db: SQLiteDatabase, before: RecordingState, next: RecordingState, policy: RecordingPolicy) {
        if (before.identity.source != InputSource.VEHICLE) return
        val (route, raw) = current(db) ?: return
        val binding = decode(raw)
        val from = before.anchor
        val to = next.anchor
        val updated = if (binding.matches(before) && next.receiver == binding.receiver && from != null && to != null &&
            (from == to || continuous(from, to, policy))) {
            advance(binding, before, next, policy)?.copy(revision = next.revision, anchorUtc = to.receivedAt.value,
                anchorElapsed = to.receivedElapsedMs)
        } else null
        write(db, route, raw, updated)
    }

    private fun advance(binding: Binding, before: RecordingState, next: RecordingState, policy: RecordingPolicy): Binding? {
        if (binding.sealed) return binding.takeIf { next.trip == null && parked(next.anchor) }
        val drive = binding.drive
        if (drive == null) {
            if (before.trip != null) return null
            val trip = next.trip ?: return binding
            val from = before.anchor ?: return null
            if (trip.departure != next.anchor || trip.departedAt.value <= binding.notBeforeUtc ||
                trip.departure.receivedElapsedMs <= binding.notBeforeElapsed || !continuous(from, trip.departure, policy) || trip.gaps.isNotEmpty()) return null
            return binding.copy(drive = trip.id, departureUtc = trip.departedAt.value, departureElapsed = trip.departure.receivedElapsedMs)
        }
        val trip = next.trip ?: next.outbox.singleOrNull { it.id == drive } ?: return null
        if (trip.id != drive || trip.gaps.isNotEmpty()) return null
        return if (next.trip == null && parked(next.anchor)) binding.copy(sealed = true) else binding
    }

    fun verifyChange(db: SQLiteDatabase, identity: GameIdentity, progress: DeliveryInProgress, meters: Long,
        session: DeliveryDistanceSession?): ContractResult<Unit> {
        return when (val distance = read(db, identity, progress, session)) {
            is ContractResult.Failure -> distance
            is ContractResult.Success -> if (distance.value == Observation.Available(ObservedDistance(meters, identity.source)))
                ContractResult.Success(Unit) else ContractResult.Failure(ContractError.STATE_CONFLICT)
        }
    }

    fun read(db: SQLiteDatabase, identity: GameIdentity, progress: DeliveryInProgress?, session: DeliveryDistanceSession?): ContractResult<Observation<ObservedDistance>> {
        if (identity.source != InputSource.VEHICLE || progress == null || session == null)
            return ContractResult.Success(missing)
        val (route, raw) = current(db) ?: return ContractResult.Success(missing)
        if (route != Route(progress.active.requestId, progress.route)) return ContractResult.Success(missing)
        val binding = decode(raw)
        if (binding.profile != identity.profileId || binding.receiver != session.receiver || !binding.sealed)
            return ContractResult.Success(missing)
        val journal = SqliteObservedDrivingJournal()
        val state = when (val read = journal.read(db, identity)) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        // Rest의 봉인 지급 ACK는 revision만 전진시키고 수신자와 마지막 수락 표본을 유지한다.
        if (!binding.matches(state) || state.trip != null || !parked(state.anchor))
            return ContractResult.Success(missing)
        val drive = checkNotNull(binding.drive)
        val trip = state.outbox.singleOrNull { it.id == drive } ?: when (val read = journal.committed(db, identity, drive)) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value?.trip ?: return ContractResult.Failure(ContractError.STORAGE_FAILURE)
        }
        if (trip.source != identity.source || trip.departedAt.value != binding.departureUtc ||
            trip.departure.receivedElapsedMs != binding.departureElapsed || trip.gaps.isNotEmpty() ||
            trip.segments.firstOrNull()?.from != trip.departure) return ContractResult.Success(missing)
        if (!session.fresh(binding.anchorElapsed)) return ContractResult.Success(Observation.Missing(MissingReason.STALE))
        val meters = try { trip.meters.setScale(0, RoundingMode.DOWN).longValueExact() }
        catch (_: ArithmeticException) { return ContractResult.Failure(ContractError.STORAGE_FAILURE) }
        return ContractResult.Success(Observation.Available(ObservedDistance(meters, identity.source)))
    }

    private fun parked(value: VehicleObservation?) =
        value != null && value.gear == Observation.Available(Gear.PARK) && value.uxRestricted == Observation.Available(false)

    private fun continuous(from: VehicleObservation, to: VehicleObservation, policy: RecordingPolicy): Boolean {
        val elapsed = to.receivedElapsedMs - from.receivedElapsedMs
        val utc = to.receivedAt.value - from.receivedAt.value
        return from.source == to.source && elapsed in 1..policy.maxGapMs && utc >= 0 &&
            kotlin.math.abs(utc - elapsed) <= policy.maxUtcSkewMs
    }

    private fun current(db: SQLiteDatabase): Pair<Route, String>? = db.rawQuery(
        "SELECT r.request_id, r.route, r.$COLUMN FROM delivery_routes r JOIN delivery_active a ON r.request_id=a.request_id " +
            "WHERE a.id=1 AND r.$COLUMN IS NOT NULL", null
    ).use { if (it.moveToFirst()) Route(RequestId(it.text(0)), RouteId(it.text(1))) to it.text(2) else null }

    private fun write(db: SQLiteDatabase, route: Route, previous: String?, value: Binding?) {
        val encoded = value?.let(::encode)
        val values = ContentValues().apply { put(COLUMN, encoded) }
        val where = "request_id=? AND route=? AND $COLUMN " + if (previous == null) "IS NULL" else "=?"
        val args = if (previous == null) arrayOf(route.request.value, route.id.value) else arrayOf(route.request.value, route.id.value, previous)
        if (db.update("delivery_routes", values, where, args) != 1) throw SQLiteException("탁송 거리 참조 CAS 무갱신")
        db.rawQuery("SELECT $COLUMN FROM delivery_routes WHERE request_id=? AND route=?",
            arrayOf(route.request.value, route.id.value)).use {
            if (!it.moveToFirst() || (if (it.isNull(0)) null else it.text(0)) != encoded || it.moveToNext())
                throw SQLiteException("탁송 거리 참조 저장 대조 실패")
        }
    }

    private fun encode(value: Binding) = JSONObject().put("profile", value.profile.value).put("receiver", value.receiver)
        .put("revision", value.revision).put("anchorUtc", value.anchorUtc).put("anchorElapsed", value.anchorElapsed)
        .put("notBeforeUtc", value.notBeforeUtc).put("notBeforeElapsed", value.notBeforeElapsed)
        .put("drive", value.drive ?: JSONObject.NULL)
        .put("utc", value.departureUtc ?: JSONObject.NULL).put("elapsed", value.departureElapsed ?: JSONObject.NULL)
        .put("sealed", value.sealed).toString()

    private fun decode(raw: String): Binding {
        val json = JSONObject(raw)
        val drive = if (json.isNull("drive")) null else json.getString("drive")
        val value = Binding(ProfileId(json.getString("profile")), json.getString("receiver"),
            DeliveryJson.long(json, "revision", 0, Long.MAX_VALUE), DeliveryJson.long(json, "anchorUtc", 0, Long.MAX_VALUE),
            DeliveryJson.long(json, "anchorElapsed", 0, Long.MAX_VALUE), DeliveryJson.long(json, "notBeforeUtc", 0, Long.MAX_VALUE),
            DeliveryJson.long(json, "notBeforeElapsed", 0, Long.MAX_VALUE), drive,
            if (drive == null) null else DeliveryJson.long(json, "utc", 0, Long.MAX_VALUE),
            if (drive == null) null else DeliveryJson.long(json, "elapsed", 0, Long.MAX_VALUE), json.getBoolean("sealed"))
        if (value.receiver.isBlank() || drive?.isBlank() == true || (value.sealed && drive == null) || encode(value) != raw)
            throw SQLiteException("탁송 거리 참조 원문 불일치")
        return value
    }
}
