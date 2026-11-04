package io.drivegrowth.app.data

import android.test.InstrumentationTestCase
import io.drivegrowth.core.*
import java.io.File

@Suppress("DEPRECATION")
class SnapshotStoreTest : InstrumentationTestCase() {
    private lateinit var file: File
    private lateinit var store: SnapshotStore
    override fun setUp() {
        super.setUp()
        file = File(instrumentation.targetContext.cacheDir, "snapshot-test-${System.nanoTime()}.json")
        store = SnapshotStore(file)
    }
    override fun tearDown() {
        file.delete()
        File(file.path + ".bak").delete()
        File(file.path + ".new").delete()
        super.tearDown()
    }
    private fun sample(t: Long, speed: Double? = 10.0, gear: Gear = Gear.DRIVE, ux: Boolean? = true) =
        Sample(InputSource.SIMULATION, t, 1793656800000L + t, speed, gear, ux)

    fun testRoundTripPreservesEveryField() {
        val active = ActiveTrip("active", InputSource.VEHICLE, 123L, 4200L, 41.75, 2)
        val trip = TripResult("done", InputSource.VEHICLE, 12L, 34L, 22L, 2.5, 1, 95, 20)
        val original = Snapshot(DriveState.WAITING_FOR_PARK, 20, listOf(trip), active,
            Sample(InputSource.VEHICLE, 300L, 456L, 0.0, Gear.PARK, false, false), 290L, -1)
        store.save(original)
        assertEquals(original, SnapshotStore(file).load())
    }
    fun testNullableFieldsAndEmptyStore() {
        assertEquals(Snapshot(), store.load())
        val snapshot = Snapshot(lastSample = sample(1, null, Gear.UNKNOWN, null))
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }
    fun testSettledReloadDoesNotPayAgain() {
        val engine = DriveEngine()
        for (t in 0L..60_000 step 1000) engine.ingest(sample(t))
        for (t in 61_000L..64_000 step 1000) engine.ingest(sample(t, 0.0, Gear.PARK, false))
        store.save(engine.snapshot)
        val restored = DriveEngine(SnapshotStore(file).load())
        restored.recoverInterrupted()
        restored.ingest(sample(1, 0.0, Gear.PARK, false))
        store.save(restored.snapshot)
        assertEquals(20, store.load().totalXp)
        assertEquals(1, store.load().trips.size)
    }
    fun testTruncatedFileFailsWithoutReset() {
        file.writeText("{\"schemaVersion\":1,")
        try {
            store.load()
            fail("손상된 저장 파일을 새 진행 상태로 취급하면 안 됩니다")
        } catch (_: org.json.JSONException) {
            assertEquals("{\"schemaVersion\":1,", file.readText())
        }
    }
    fun testUnsupportedSchemaFails() {
        file.writeText("{\"schemaVersion\":99}")
        try {
            store.load()
            fail("지원하지 않는 버전을 조용히 초기화하면 안 됩니다")
        } catch (_: IllegalArgumentException) {
            assertTrue(file.exists())
        }
    }
    fun testInterruptedRecoveryPersistsWithoutReward() {
        val engine = DriveEngine()
        engine.ingest(sample(0))
        engine.ingest(sample(1000))
        store.save(engine.snapshot)
        val restored = DriveEngine(store.load())
        store.save(restored.recoverInterrupted())
        val result = SnapshotStore(file).load()
        assertNull(result.active)
        assertEquals(0, result.totalXp)
        assertTrue(result.trips.single().interrupted)
        assertEquals(0, result.trips.single().xp)
        store.save(DriveEngine(result).recoverInterrupted())
        assertEquals(1, store.load().trips.size)
    }
    fun testReplacementCommitsWholeSnapshot() {
        store.save(Snapshot(totalXp = 20))
        store.save(Snapshot(totalXp = 40, parkSinceMs = 123L))
        assertEquals(Snapshot(totalXp = 40, parkSinceMs = 123L), store.load())
    }
    fun testInvalidNonFiniteSampleRemainsAvailableForDiagnosis() {
        val snapshot = Snapshot(state = DriveState.DATA_UNAVAILABLE,
            lastSample = sample(1000, Double.NaN).copy(valid = false))
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }

}
