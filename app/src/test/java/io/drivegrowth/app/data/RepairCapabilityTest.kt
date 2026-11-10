package io.drivegrowth.app.data

import io.drivegrowth.core.contract.RepairChangeStore
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairCapabilityTest {
    @Test fun sqliteStoreExposesTheCommonRepairContract() {
        assertTrue("공용 SQLite 원장은 수리 계약으로 소비할 수 있어야 한다",
            RepairChangeStore::class.java.isAssignableFrom(SqliteGameChangeStore::class.java))
    }
}
