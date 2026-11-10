package io.drivegrowth.app.data

import io.drivegrowth.core.contract.InitializationChangeStore
import org.junit.Assert.assertTrue
import org.junit.Test

class InitializationCapabilityTest {
    @Test fun publicStoreExposesInitializationAlongsideExistingWallet() {
        assertTrue("같은 SQLite 원장을 초기화 계약으로 소비해야 한다",
            InitializationChangeStore::class.java.isAssignableFrom(SqliteGameChangeStore::class.java))
    }
}
