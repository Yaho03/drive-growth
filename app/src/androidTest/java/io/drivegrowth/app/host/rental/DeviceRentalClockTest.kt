package io.drivegrowth.app.host.rental

import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.ContractResult

/** 실제 OS clock 읽기만 검증한다. 차량 SDK·주행·영업·도착 성공 시험이 아니다. */
@Suppress("DEPRECATION")
class DeviceRentalClockTest : InstrumentationTestCase() {
    fun testActualOsBootCounterAndClocksAreReadableWithoutSyntheticFallback() {
        val source = DeviceRentalClock(instrumentation.targetContext)
        val first = source.read()
        assertTrue("실제 OS clock을 읽지 못하면 임의 boot/시각으로 대체하지 않는다: $first", first is ContractResult.Success)
        val a = (first as ContractResult.Success).value
        val second = source.read()
        assertTrue(second.toString(), second is ContractResult.Success)
        val b = (second as ContractResult.Success).value
        assertTrue(a.bootId.startsWith("android-boot-count:"))
        assertTrue(a.bootId.removePrefix("android-boot-count:").toLong() >= 0)
        assertEquals(a.bootId, b.bootId)
        assertTrue(a.epoch.value > 0); assertTrue(a.elapsedMs >= 0)
        assertTrue(b.elapsedMs >= a.elapsedMs)
    }
}
