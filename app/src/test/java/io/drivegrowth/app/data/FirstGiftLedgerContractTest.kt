package io.drivegrowth.app.data

import io.drivegrowth.core.contract.FirstRentalGiftStore
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstGiftLedgerContractTest {
    @Test fun ledgerCanBeConsumedThroughTheExistingWholeGiftContract() {
        val type = runCatching { Class.forName("io.drivegrowth.app.data.FirstRentalGiftLedger") }.getOrNull()
        assertTrue("SQLite 선물 모듈은 기존 전체 선물 계약으로 소비해야 한다",
            type != null && FirstRentalGiftStore::class.java.isAssignableFrom(type))
    }
}
