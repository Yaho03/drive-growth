package io.drivegrowth.app

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.core.content.ContentLoad

@Suppress("DEPRECATION")
class PackagedContentTest : InstrumentationTestCase() {
    fun testPackagedTrialContentCanBeReadAgainWithSameVersion() {
        val assets = instrumentation.targetContext.assets
        val first = (AssetGameContent(assets).load() as ContentLoad.Loaded).snapshot
        val next = (AssetGameContent(assets).load() as ContentLoad.Loaded).snapshot
        assertTrue(first.trial)
        assertEquals("fixture-content-v0", first.catalog.contentVersion.value)
        assertEquals(5, first.vehicles.size)
        assertEquals(first, next)
    }
}
