package io.drivegrowth.core.driving

import io.drivegrowth.core.DriveEngine
import io.drivegrowth.core.DriveState
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleSignalsTest {
    private val noData = Observation.Missing(MissingReason.NO_DATA)

    private fun observation(
        time: Long,
        speed: Observation<Double> = Observation.Available(10.0),
        gear: Observation<Gear> = Observation.Available(Gear.DRIVE),
        ux: Observation<Boolean> = Observation.Available(true),
        source: InputSource = InputSource.VEHICLE,
    ) = VehicleObservation(source, time, EpochMillis(time), speed, gear, ux)

    @Test
    fun `속도는 유효한 값만 전달한다`() {
        assertEquals(Observation.Available(0.0), VehicleSignals.speed(true, 0f))
        assertEquals(noData, VehicleSignals.speed(false, 10f))
        assertEquals(noData, VehicleSignals.speed(true, null))
        assertEquals(noData, VehicleSignals.speed(true, Float.NaN))
        assertEquals(noData, VehicleSignals.speed(true, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `기어 원값을 변환하고 모르는 값은 누락으로 둔다`() {
        assertEquals(Observation.Available(Gear.PARK), VehicleSignals.gear(true, 4))
        assertEquals(Observation.Available(Gear.DRIVE), VehicleSignals.gear(true, 8))
        assertEquals(Observation.Available(Gear.REVERSE), VehicleSignals.gear(true, 2))
        assertEquals(Observation.Available(Gear.NEUTRAL), VehicleSignals.gear(true, 1))
        assertEquals(noData, VehicleSignals.gear(true, 0))
        assertEquals(noData, VehicleSignals.gear(true, 16))
        assertEquals(noData, VehicleSignals.gear(false, 4))
    }

    @Test
    fun `UX는 DO 요구나 활성 제한이 있으면 제한이다`() {
        assertEquals(Observation.Available(false), VehicleSignals.uxRestricted(false, 0))
        assertEquals(Observation.Available(true), VehicleSignals.uxRestricted(true, 0))
        assertEquals(Observation.Available(true), VehicleSignals.uxRestricted(false, 80))
    }

    @Test
    fun `표본 변환은 누락을 0이나 허용으로 바꾸지 않는다`() {
        val sample = observation(1, speed = Observation.Missing(MissingReason.PERMISSION_DENIED),
            gear = noData, ux = noData).toSample()
        assertNull(sample.speedMps)
        assertEquals(Gear.UNKNOWN, sample.gear)
        assertNull(sample.uxRestricted)
        assertFalse(sample.valid)
        assertTrue(observation(1).toSample().valid)
    }

    @Test
    fun `기록은 유효 구간만 더하고 누락과 긴 공백은 연결하지 않는다`() {
        val engine = DriveEngine()
        for (time in 0L..2000L step 1000) engine.ingest(observation(time).toSample())
        assertEquals(20.0, engine.snapshot.active!!.distanceMeters, 0.001)
        engine.ingest(observation(3000, speed = noData).toSample())
        assertEquals(DriveState.DATA_UNAVAILABLE, engine.snapshot.state)
        engine.ingest(observation(4000).toSample())
        assertEquals(20.0, engine.snapshot.active!!.distanceMeters, 0.001)
        engine.ingest(observation(5000).toSample())
        assertEquals(30.0, engine.snapshot.active!!.distanceMeters, 0.001)
        engine.ingest(observation(60_000).toSample())
        assertEquals(30.0, engine.snapshot.active!!.distanceMeters, 0.001)
    }

    @Test
    fun `역순과 중복은 거리를 더하지 않고 출처 혼합은 거절한다`() {
        val engine = DriveEngine()
        for (time in 0L..1000L step 1000) engine.ingest(observation(time).toSample())
        val distance = engine.snapshot.active!!.distanceMeters
        engine.ingest(observation(1000).toSample())
        engine.ingest(observation(500).toSample())
        assertEquals(distance, engine.snapshot.active!!.distanceMeters, 0.001)
        engine.ingest(observation(2000, source = InputSource.SIMULATION).toSample())
        assertEquals(DriveState.DATA_UNAVAILABLE, engine.snapshot.state)
        assertEquals(distance, engine.snapshot.active!!.distanceMeters, 0.001)
        assertEquals(InputSource.VEHICLE, engine.snapshot.active!!.source)
    }
}
