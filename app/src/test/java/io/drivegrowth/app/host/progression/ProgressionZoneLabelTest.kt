package io.drivegrowth.app.host.progression

import io.drivegrowth.core.contract.InputSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class ProgressionZoneLabelTest {
    private val mapper = ProgressionMapper()
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test fun `UTC 기기에서 차량 입력과 모의 시연을 구분한다`() {
        for (zone in listOf(ZoneOffset.UTC, ZoneId.of("UTC"), ZoneId.of("Etc/UTC"))) {
            assertEquals("기기 시간 기준", mapper.zoneLabel(InputSource.VEHICLE, zone, ZoneOffset.UTC))
            assertEquals("모의 시연 시각 기준", mapper.zoneLabel(InputSource.SIMULATION, zone, ZoneOffset.UTC))
        }
    }

    @Test fun `서울 기기에서도 모의 시연은 입력 출처로 구분한다`() {
        assertEquals("모의 시연 시각 기준", mapper.zoneLabel(InputSource.SIMULATION, ZoneOffset.UTC, seoul))
        assertEquals("기기 시간 기준", mapper.zoneLabel(InputSource.VEHICLE, seoul, seoul))
    }

    @Test fun `차량의 저장 기간이 현재 기기 시간대와 다르면 원래 시간대를 표시한다`() {
        assertEquals("UTC 기준", mapper.zoneLabel(InputSource.VEHICLE, ZoneId.of("UTC"), seoul))
        assertEquals("Asia/Seoul 기준", mapper.zoneLabel(InputSource.VEHICLE, seoul, ZoneOffset.UTC))
    }
}
