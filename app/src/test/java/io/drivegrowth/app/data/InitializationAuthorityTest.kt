package io.drivegrowth.app.data

import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentLoader
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InitializationAuthorityTest {
    private fun content() = File("src/main/assets/content").let { path ->
        (GameContentLoader.load(File(path,"vehicle-facts.csv").readText(),File(path,"game-content.csv").readText()) as ContentLoad.Loaded).snapshot
    }
    @Test fun candidatesAreFixedAtSupplyAndCannotBeChangedThroughJavaSet() {
        val candidates=linkedSetOf(VehicleId("hyundai_casper"))
        val a=InitializationAuthority(InputSource.SIMULATION,content(),candidates)
        candidates.clear()
        assertEquals(setOf(VehicleId("hyundai_casper")),a.allowedCandidates)
        assertThrows(UnsupportedOperationException::class.java) { (a.allowedCandidates as MutableSet).clear() }
    }
    @Test fun contentListsAreFixedAtSupply() {
        val original=content();val vehicles=original.vehicles.toMutableList();val definitions=original.catalog.vehicles.toMutableList()
        val a=InitializationAuthority(InputSource.SIMULATION,original.copy(vehicles=vehicles,catalog=original.catalog.copy(vehicles=definitions)),emptySet())
        val count=vehicles.size;vehicles.clear();definitions.clear()
        assertEquals(count,a.content.vehicles.size);assertEquals(count,a.content.catalog.vehicles.size)
        assertThrows(UnsupportedOperationException::class.java) { (a.content.vehicles as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (a.content.catalog.vehicles as MutableList).clear() }
    }
}
