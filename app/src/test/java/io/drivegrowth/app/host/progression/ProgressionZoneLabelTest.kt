package io.drivegrowth.app.host.progression

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class ProgressionZoneLabelTest {
    private val mapper = ProgressionMapper()

    @Test fun `모의 시연의 UTC 기간은 시간대 ID 대신 모의 시연 시각 기준으로 표시한다`() {
        assertEquals("모의 시연 시각 기준", mapper.zoneLabel(ZoneOffset.UTC))
        assertEquals("모의 시연 시각 기준", mapper.zoneLabel(ZoneId.of("UTC")))
    }

    @Test fun `기기 기본 시간대 기간은 기기 시간 기준으로 표시한다`() {
        val device = ZoneId.systemDefault()
        // 시험 기기의 기본 시간대가 UTC면 위 시험과 같은 분기라 이 경우는 건너뛴다.
        if (device.normalized() == ZoneOffset.UTC) return
        assertEquals("기기 시간 기준", mapper.zoneLabel(device))
    }

    @Test fun `그 밖의 시간대는 ID를 그대로 밝힌다`() {
        val other = listOf("Asia/Seoul", "Europe/Berlin").map(ZoneId::of)
            .first { it.normalized() != ZoneId.systemDefault().normalized() }
        assertEquals("${other.id} 기준", mapper.zoneLabel(other))
    }
}
