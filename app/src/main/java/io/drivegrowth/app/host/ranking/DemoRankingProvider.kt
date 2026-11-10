package io.drivegrowth.app.host.ranking

import java.math.BigDecimal
import java.util.Collections

/** 합성 데이터만 받는 시연 전용 타입. 실제 RankingSnapshot을 선수 목록으로 받지 않는다. */
class DemoRankingEntry(val id: String, val label: String, values: Map<RankingMetric, BigDecimal>) {
    val values: Map<RankingMetric, BigDecimal> = Collections.unmodifiableMap(LinkedHashMap(values))
    init { require(id.startsWith("demo-")); require(values.keys == RankingMetric.entries.toSet()); require(values.values.all { it.signum() >= 0 }) }
}
data class DemoRankingRow(val rank: Int, val entry: DemoRankingEntry, val value: BigDecimal)
class DemoRankingProvider(entries: List<DemoRankingEntry> = examples()) {
    private val entries = Collections.unmodifiableList(ArrayList(entries))
    init { require(this.entries.map { it.id }.toSet().size == this.entries.size) }
    /** 시연 규칙: 큰 값 우선·동률 공동순위(1,2,2,4), 표시순서만 합성 ID 오름차순. */
    fun rows(metric: RankingMetric): List<DemoRankingRow> {
        val ordered = entries.sortedWith(compareByDescending<DemoRankingEntry> { it.values.getValue(metric) }.thenBy { it.id })
        var rank = 0; var previous: BigDecimal? = null
        return Collections.unmodifiableList(ordered.mapIndexed { index, entry ->
            val value = entry.values.getValue(metric)
            if (previous?.compareTo(value) != 0) rank = index + 1
            previous = value; DemoRankingRow(rank, entry, value)
        })
    }
    companion object {
        private fun examples(): List<DemoRankingEntry> = listOf(
            listOf(4,50,10,12500,120000,3900),listOf(3,32,7,8500,80000,3300),listOf(3,32,5,8500,60000,3000),listOf(2,16,3,4000,20000,2000)
        ).mapIndexed { index, values -> DemoRankingEntry("demo-$index", "예시 가게 ${('A'.code + index).toChar()}",
            RankingMetric.entries.zip(values.map { it.toBigDecimal() }).toMap()) }
    }
}
