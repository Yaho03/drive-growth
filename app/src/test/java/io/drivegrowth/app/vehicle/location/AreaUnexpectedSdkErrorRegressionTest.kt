package io.drivegrowth.app.vehicle.location

import ai.pleos.playground.location.fused.listener.FusedLocationListener
import io.drivegrowth.core.contract.Observation
import org.junit.Assert.*
import org.junit.Test

/** 영역을 쓰지 않는 기존 start()의 원예외 계약. SDK 대체 fixture이며 장치 시험이 아니다. */
class AreaUnexpectedSdkErrorRegressionTest {
    private class Client(val original: RuntimeException, val cleanup: RuntimeException? = null) : LocationSdkClient {
        var released = 0
        override fun initialize() = Unit
        override fun isSupported() = true
        override fun register(listener: FusedLocationListener) { throw original }
        override fun unregister(listener: FusedLocationListener) = Unit
        override fun release() { released++; cleanup?.let { throw it } }
    }
    @Test fun originalUnsupportedRegisterErrorIsPropagatedAfterCleanup() {
        val original = UnsupportedOperationException("explicit unexpected register fixture")
        val client = Client(original); val jobs = ArrayDeque<() -> Unit>();val delivered=mutableListOf<Observation<PleosLocationFix>>()
        val owner=ForegroundPleosLocation({client},{true},{jobs+=it},{},{1},{2},delivered::add)
        val actual=assertThrows(UnsupportedOperationException::class.java){owner.start()}
        assertSame(original,actual);assertEquals(1,client.released)
        while(jobs.isNotEmpty())jobs.removeFirst().invoke()
        assertTrue(delivered.isEmpty())
    }
    @Test fun originalUnsupportedRegisterErrorKeepsCleanupFailureSuppressed() {
        val original=UnsupportedOperationException("explicit unexpected register fixture")
        val cleanup=IllegalStateException("explicit release fixture")
        val client=Client(original,cleanup);val jobs=ArrayDeque<() -> Unit>()
        val owner=ForegroundPleosLocation({client},{true},{jobs+=it},{},{1},{2},{})
        val actual=assertThrows(UnsupportedOperationException::class.java){owner.start()}
        assertSame(original,actual);assertEquals(listOf(cleanup),actual.suppressed.toList());assertEquals(1,client.released)
    }
}
