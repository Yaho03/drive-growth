package io.drivegrowth.navidiagnostic

import org.junit.Assert.*
import org.junit.Test

class LogChunksTest {
    @Test fun `긴 한국어 payload를 UTF8 한도 안에서 손실 없이 분할한다`() {
        val payload = "실제 SDK 위치🚗 37.48544722,127.03636666 ".repeat(200)
        val chunks = logChunks(payload)
        assertEquals(payload, chunks.joinToString(""))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 2400 })
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == it })
    }
}
