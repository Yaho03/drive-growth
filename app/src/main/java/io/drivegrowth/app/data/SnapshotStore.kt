package io.drivegrowth.app.data

import android.util.AtomicFile
import io.drivegrowth.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 정산과 경험치 잔액을 하나의 원자적 파일로 확정한다. */
class SnapshotStore(private val file: File) {
    private val atomic = AtomicFile(file)

    @Synchronized
    fun load(): Snapshot {
        if (!file.exists() && !File(file.path + ".bak").exists()) return Snapshot()
        val json = atomic.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        require(json.getInt("schemaVersion") == 1) { "지원하지 않는 저장 버전입니다" }
        val trips = json.getJSONArray("trips")
        return Snapshot(
            state = DriveState.valueOf(json.getString("state")),
            totalXp = json.getInt("totalXp"),
            trips = List(trips.length()) { readTrip(trips.getJSONObject(it)) },
            active = json.objectOrNull("active")?.let(::readActive),
            lastSample = json.objectOrNull("lastSample")?.let(::readSample),
            parkSinceMs = json.longOrNull("parkSinceMs"),
            lastEventBand = json.getInt("lastEventBand")
        )
    }

    @Synchronized
    fun save(snapshot: Snapshot) {
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("state", snapshot.state.name)
            put("totalXp", snapshot.totalXp)
            put("trips", JSONArray().apply { snapshot.trips.forEach { put(writeTrip(it)) } })
            put("active", snapshot.active?.let(::writeActive) ?: JSONObject.NULL)
            put("lastSample", snapshot.lastSample?.let(::writeSample) ?: JSONObject.NULL)
            put("parkSinceMs", snapshot.parkSinceMs ?: JSONObject.NULL)
            put("lastEventBand", snapshot.lastEventBand)
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun writeSample(s: Sample) = JSONObject().apply {
        put("source", s.source.name); put("elapsedMs", s.elapsedMs); put("epochMs", s.epochMs)
        put("speedMps", s.speedMps?.let(::jsonDouble) ?: JSONObject.NULL); put("gear", s.gear.name)
        put("uxRestricted", s.uxRestricted ?: JSONObject.NULL); put("valid", s.valid)
    }
    private fun readSample(j: JSONObject) = Sample(
        InputSource.valueOf(j.getString("source")), j.getLong("elapsedMs"), j.getLong("epochMs"),
        if (j.get("speedMps") == JSONObject.NULL) null else readDouble(j, "speedMps"),
        Gear.valueOf(j.getString("gear")),
        if (j.get("uxRestricted") == JSONObject.NULL) null else j.getBoolean("uxRestricted"), j.getBoolean("valid")
    )
    private fun writeActive(t: ActiveTrip) = JSONObject().apply {
        put("id", t.id); put("source", t.source.name); put("startedEpochMs", t.startedEpochMs)
        put("validDurationMs", t.validDurationMs); put("distanceMeters", jsonDouble(t.distanceMeters)); put("harshEvents", t.harshEvents)
    }
    private fun readActive(j: JSONObject) = ActiveTrip(
        j.getString("id"), InputSource.valueOf(j.getString("source")), j.getLong("startedEpochMs"),
        j.getLong("validDurationMs"), readDouble(j, "distanceMeters"), j.getInt("harshEvents")
    )
    private fun writeTrip(t: TripResult) = JSONObject().apply {
        put("id", t.id); put("source", t.source.name); put("startedEpochMs", t.startedEpochMs)
        put("endedEpochMs", t.endedEpochMs); put("validDurationMs", t.validDurationMs)
        put("distanceMeters", jsonDouble(t.distanceMeters)); put("harshEvents", t.harshEvents)
        put("score", t.score ?: JSONObject.NULL); put("xp", t.xp); put("interrupted", t.interrupted)
    }
    private fun readTrip(j: JSONObject) = TripResult(
        j.getString("id"), InputSource.valueOf(j.getString("source")), j.getLong("startedEpochMs"),
        j.getLong("endedEpochMs"), j.getLong("validDurationMs"), readDouble(j, "distanceMeters"),
        j.getInt("harshEvents"), if (j.get("score") == JSONObject.NULL) null else j.getInt("score"),
        j.getInt("xp"), j.getBoolean("interrupted")
    )
    // 무효 입력의 NaN/Infinity도 진단용 마지막 샘플에서 손실 없이 보존한다.
    private fun jsonDouble(value: Double): Any = if (value.isFinite()) value else value.toString()
    private fun readDouble(json: JSONObject, key: String): Double {
        val value = json.get(key)
        return if (value is String && value in setOf("NaN", "Infinity", "-Infinity")) value.toDouble()
        else json.getDouble(key)
    }
    private fun JSONObject.objectOrNull(key: String): JSONObject? =
        if (get(key) == JSONObject.NULL) null else getJSONObject(key)
    private fun JSONObject.longOrNull(key: String): Long? =
        if (get(key) == JSONObject.NULL) null else getLong(key)
}
