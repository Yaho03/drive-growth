package io.drivegrowth.core

import org.junit.Assert.*
import org.junit.Test

class DriveEngineTest {
    private val epoch = 1793656800000L
    private fun sample(t: Long, speed: Double? = 10.0, gear: Gear = Gear.DRIVE, ux: Boolean? = true, source: InputSource = InputSource.SIMULATION) = Sample(source, t, epoch + t, speed, gear, ux)
    private fun complete(engine: DriveEngine, start: Long = 0): Snapshot {
        for (t in start..start + 60_000 step 1000) engine.ingest(sample(t))
        for (t in start + 61_000..start + 64_000 step 1000) engine.ingest(sample(t, 0.0, Gear.PARK, false))
        return engine.snapshot
    }
    @Test fun `D 정지는 주차나 정산이 아니다`() {
        val e = DriveEngine()
        e.ingest(sample(0))
        e.ingest(sample(1000, 0.0))
        assertNotNull(e.snapshot.active)
        assertTrue(e.snapshot.trips.isEmpty())
    }
    @Test fun `주차 기어와 제한 해제 3초 후 한 번 정산한다`() {
        val e = DriveEngine()
        val s = complete(e)
        assertEquals(DriveState.SETTLED, s.state)
        assertEquals(20, s.totalXp)
        assertEquals(1, s.trips.size)
        assertEquals(605.0, s.trips.single().distanceMeters, 0.01)
        assertEquals(2, s.level)
        assertTrue(s.firstBadgeUnlocked)
        for (t in 65_000L..69_000L step 1000) e.ingest(sample(t, 0.0, Gear.PARK, false))
        assertEquals(1, e.snapshot.trips.size)
        assertEquals(20, e.snapshot.totalXp)
    }
    @Test fun `속도 누락과 제한 미확인은 정산하지 않는다`() {
        val e = DriveEngine()
        e.ingest(sample(0))
        e.ingest(sample(1000, null, Gear.PARK, false))
        assertEquals(DriveState.DATA_UNAVAILABLE, e.snapshot.state)
        for (t in 2000L..6000L step 1000) e.ingest(sample(t, 0.0, Gear.PARK, null))
        assertTrue(e.snapshot.trips.isEmpty())
        assertEquals(0, e.snapshot.totalXp)
    }
    @Test fun `중복과 역순 시각은 거리를 더하지 않는다`() {
        val e = DriveEngine()
        e.ingest(sample(1000))
        e.ingest(sample(2000))
        e.ingest(sample(2000, 50.0))
        e.ingest(sample(1500, 50.0))
        assertEquals(10.0, e.snapshot.active!!.distanceMeters, 0.001)
    }
    @Test fun `누락 구간과 긴 간격을 연결해 적분하지 않는다`() {
        val e = DriveEngine()
        e.ingest(sample(0))
        e.ingest(sample(1000))
        e.ingest(sample(2000, null))
        e.ingest(sample(3000))
        e.ingest(sample(20_000))
        assertEquals(10.0, e.snapshot.active!!.distanceMeters, 0.001)
        assertEquals(1000L, e.snapshot.active!!.validDurationMs)
    }
    @Test fun `같은 날짜 추가 주행은 일일 경험치 상한을 넘지 않는다`() {
        val e = DriveEngine()
        complete(e)
        complete(e, 70_000)
        assertEquals(2, e.snapshot.trips.size)
        assertEquals(20, e.snapshot.totalXp)
        assertEquals(0, e.snapshot.trips.last().xp)
    }
    @Test fun `복원한 정산 기록으로 경험치를 다시 주지 않는다`() {
        val first = complete(DriveEngine())
        val restored = DriveEngine(first)
        restored.ingest(sample(70_000, 0.0, Gear.PARK, false))
        assertEquals(20, restored.snapshot.totalXp)
        assertEquals(1, restored.snapshot.trips.size)
    }
    @Test fun `중단된 주행 복원은 보상 없이 기록한다`() {
        val e = DriveEngine()
        e.ingest(sample(0))
        e.ingest(sample(1000))
        val restored = DriveEngine(e.snapshot)
        val s = restored.recoverInterrupted()
        assertNull(s.active)
        assertEquals(0, s.totalXp)
        assertTrue(s.trips.single().interrupted)
        restored.recoverInterrupted()
        assertEquals(1, restored.snapshot.trips.size)
    }
    @Test fun `모의와 차량 입력을 한 기록으로 섞지 않는다`() {
        val e = DriveEngine()
        e.ingest(sample(0))
        e.ingest(sample(1000, source = InputSource.VEHICLE))
        assertEquals(DriveState.DATA_UNAVAILABLE, e.snapshot.state)
        assertEquals(0.0, e.snapshot.active!!.distanceMeters, 0.0)
    }
    @Test fun `같은 급감속 구간은 이벤트 하나이며 경험치와 무관하다`() {
        val e = DriveEngine()
        e.ingest(sample(0, 20.0))
        e.ingest(sample(1000, 16.0))
        e.ingest(sample(2000, 12.0))
        assertEquals(1, e.snapshot.active!!.harshEvents)
    }
    @Test fun `짧은 기록은 평가 점수와 경험치를 만들지 않는다`() {
        val e = DriveEngine()
        e.ingest(sample(0))
        for (t in 1000L..4000L step 1000) e.ingest(sample(t, 0.0, Gear.PARK, false))
        assertNull(e.snapshot.trips.single().score)
        assertEquals(0, e.snapshot.totalXp)
    }
    @Test fun `주차 신호 관측이 끊기면 연속 주차 시간을 다시 센다`() {
        val e = DriveEngine()
        e.ingest(sample(0))
        e.ingest(sample(1000, 0.0, Gear.PARK, false))
        e.ingest(sample(20_000, 0.0, Gear.PARK, false))
        assertTrue(e.snapshot.trips.isEmpty())
        assertEquals(DriveState.WAITING_FOR_PARK, e.snapshot.state)
    }
    @Test fun `정산 후 재부팅은 새로운 단조 시각을 허용한다`() {
        val restored = DriveEngine(complete(DriveEngine()))
        restored.recoverInterrupted()
        restored.ingest(sample(1))
        assertNotNull(restored.snapshot.active)
        assertEquals(20, restored.snapshot.totalXp)
    }

}
