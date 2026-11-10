package io.drivegrowth.app.host.delivery

import org.junit.Assert.*
import org.junit.Test

class DeliveryNavigationMetadataTest {
    @Test fun `필드별 수신 근거는 공급자 map 변경 뒤에도 보존한다`() {
        val receipt=DeliveryFieldReceipt(1000,50,7,4,"PLEOS_NAVI_SDK")
        val supplied=mutableMapOf("destination" to receipt)
        val metadata=DeliveryNavigationMetadata(7,supplied)
        supplied.clear()
        assertEquals(mapOf("destination" to receipt),metadata.fields)
    }
}
