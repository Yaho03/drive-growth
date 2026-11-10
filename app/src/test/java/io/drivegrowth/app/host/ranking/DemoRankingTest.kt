package io.drivegrowth.app.host.ranking

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class DemoRankingTest {
    @Test fun fixedSyntheticDataUsesCompetitionTiesAndStableDisplayOrder(){
        val rows=DemoRankingProvider().rows(RankingMetric.EARNED)
        assertEquals(listOf(1,2,2,4),rows.map{it.rank});assertEquals(listOf("demo-0","demo-1","demo-2","demo-3"),rows.map{it.entry.id})
        assertEquals(BigDecimal(12500),rows.first().value)
        assertEquals(listOf(1,2,3,4),DemoRankingProvider().rows(RankingMetric.OWNED).map{it.rank})
    }
    @Test fun exampleValuesAreCopiedAndRealIdsCannotBeInjected(){
        val map=RankingMetric.entries.associateWith{BigDecimal.ONE}.toMutableMap();val row=DemoRankingEntry("demo-copy","합성",map);map.clear();assertEquals(6,row.values.size)
        assertThrows(IllegalArgumentException::class.java){DemoRankingEntry("real-profile","실제 이름",RankingMetric.entries.associateWith{BigDecimal.ONE})}
    }
}
