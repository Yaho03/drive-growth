package io.drivegrowth.core.driving

import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.VehicleInteraction
import io.drivegrowth.core.contract.VehicleObservation
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleInteractionGateTest {
    private val policy = InteractionPolicy(
        maxStationarySpeedMps = 0.1, requiredParkMs = 3000, maxSignalAgeMs = 2500, maxSampleGapMs = 2500,
    )
    private val allowed = Observation.Available(VehicleInteraction.ALLOWED)
    private val restricted = Observation.Available(VehicleInteraction.RESTRICTED)

    private fun observation(
        time: Long,
        gear: Observation<Gear> = Observation.Available(Gear.PARK),
        speed: Observation<Double> = Observation.Available(0.0),
        ux: Observation<Boolean> = Observation.Available(false),
        source: InputSource = InputSource.VEHICLE,
    ) = VehicleObservation(source, time, EpochMillis(time), speed, gear, ux)

    private fun parkedGate(until: Long = 3000): VehicleInteractionGate {
        val gate = VehicleInteractionGate(policy)
        for (time in 0..until step 1000) gate.accept(observation(time))
        return gate
    }

    @Test
    fun `P와 UX 허용이 연속으로 유지되어야 허용한다`() {
        val gate = VehicleInteractionGate(policy)
        for (time in 0L..2000L step 1000) gate.accept(observation(time))
        assertEquals(restricted, gate.state(2000))
        gate.accept(observation(3000))
        assertEquals(allowed, gate.state(3000))
    }

    @Test
    fun `D 정차와 UX 제한은 오래 지속되어도 허용하지 않는다`() {
        val drive = VehicleInteractionGate(policy)
        for (time in 0L..10_000L step 1000) drive.accept(observation(time, gear = Observation.Available(Gear.DRIVE)))
        assertEquals(restricted, drive.state(10_000))

        val uxLimited = VehicleInteractionGate(policy)
        for (time in 0L..10_000L step 1000) uxLimited.accept(observation(time, ux = Observation.Available(true)))
        assertEquals(restricted, uxLimited.state(10_000))
    }

    @Test
    fun `주행 전환 즉시 제한하고 P 복귀 후 다시 기다린다`() {
        val gate = parkedGate()
        gate.accept(observation(4000, gear = Observation.Available(Gear.DRIVE)))
        assertEquals(restricted, gate.state(4000))
        gate.accept(observation(5000))
        assertEquals(restricted, gate.state(5000))
        for (time in 6000L..8000L step 1000) gate.accept(observation(time))
        assertEquals(allowed, gate.state(8000))
    }

    @Test
    fun `표본이 없거나 멈추면 확인 불가다`() {
        assertEquals(Observation.Missing(MissingReason.NO_DATA), VehicleInteractionGate(policy).state(0))
        val gate = parkedGate()
        assertEquals(allowed, gate.state(5500))
        assertEquals(Observation.Missing(MissingReason.STALE), gate.state(5501))
    }

    @Test
    fun `긴 공백 뒤에는 연속 주차를 다시 센다`() {
        val gate = parkedGate()
        gate.accept(observation(6000))
        assertEquals(restricted, gate.state(6000))
        for (time in 7000L..9000L step 1000) gate.accept(observation(time))
        assertEquals(allowed, gate.state(9000))
    }

    @Test
    fun `역순과 중복 표본은 상태를 되돌리지 않는다`() {
        val gate = parkedGate()
        gate.accept(observation(2000, gear = Observation.Available(Gear.DRIVE)))
        gate.accept(observation(3000, gear = Observation.Available(Gear.DRIVE)))
        assertEquals(allowed, gate.state(3000))
        assertEquals(Observation.Missing(MissingReason.STALE), gate.state(6000))
    }

    @Test
    fun `신호별 누락 원인을 전달하고 0으로 간주하지 않는다`() {
        val cases = mapOf(
            MissingReason.PERMISSION_DENIED to observation(4000, speed = Observation.Missing(MissingReason.PERMISSION_DENIED)),
            MissingReason.UNSUPPORTED to observation(4000, gear = Observation.Missing(MissingReason.UNSUPPORTED)),
            MissingReason.STALE to observation(4000, ux = Observation.Missing(MissingReason.STALE)),
            MissingReason.NO_DATA to observation(4000, speed = Observation.Missing(MissingReason.NO_DATA)),
        )
        cases.forEach { (reason, sample) ->
            val gate = parkedGate()
            gate.accept(sample)
            assertEquals(Observation.Missing(reason), gate.state(4000))
        }
    }

    @Test
    fun `여러 신호가 누락되면 권한 거부를 먼저 알린다`() {
        val gate = VehicleInteractionGate(policy)
        gate.accept(observation(0, speed = Observation.Missing(MissingReason.NO_DATA), ux = Observation.Missing(MissingReason.PERMISSION_DENIED)))
        assertEquals(Observation.Missing(MissingReason.PERMISSION_DENIED), gate.state(0))
    }

    @Test
    fun `모의 입력은 게임 조작을 열지 않는다`() {
        val gate = VehicleInteractionGate(policy)
        for (time in 0L..5000L step 1000) gate.accept(observation(time, source = InputSource.SIMULATION))
        assertEquals(Observation.Missing(MissingReason.NO_DATA), gate.state(5000))
    }

    @Test
    fun `초기화 후에는 다시 확인 불가에서 시작한다`() {
        val gate = parkedGate()
        gate.reset()
        assertEquals(Observation.Missing(MissingReason.NO_DATA), gate.state(3000))
        gate.accept(observation(1))
        assertEquals(restricted, gate.state(1))
    }
}
