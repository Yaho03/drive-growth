package io.drivegrowth.app.host.ranking

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.CollectionEntry
import io.drivegrowth.core.garage.CollectionState
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.TemporalAdjusters

internal object RankingAggregation {
    private fun invalid() = RankingValue.Failure(ContractError.STORAGE_FAILURE)
    fun owned(history: List<OwnedVehicle>): RankingValue {
        if (history.map { it.ownedVehicleId }.toSet().size != history.size) return invalid()
        return RankingValue.Available(history.count { it.lifecycle == VehicleLifecycle.OWNED }.toBigDecimal(), "현재 OWNED 개체 · 중복 차종 각각 포함")
    }
    fun collection(entries: List<CollectionEntry>, definition: CollectionCountDefinition?): RankingValue {
        if (definition == null) return RankingValue.Missing("도감 수집 집계 기준이 아직 공급되지 않았습니다.")
        if (entries.map { it.key }.toSet().size != entries.size) return invalid()
        val count = entries.count { it.state == CollectionState.OWNED || definition == CollectionCountDefinition.SEEN_OR_OBTAINED && it.state == CollectionState.DISCOVERED }
        return RankingValue.Available(count.toBigDecimal(), if (definition == CollectionCountDefinition.OBTAINED) "획득 이력의 차종×희귀도 칸 · 처분 후 유지" else "발견 또는 획득한 차종×희귀도 칸")
    }
    fun highest(history: List<OwnedVehicle>, prices: Map<VehicleId, GameMoney>): RankingValue {
        if (history.map { it.ownedVehicleId }.toSet().size != history.size) return invalid()
        val owned = history.filter { it.lifecycle == VehicleLifecycle.OWNED }
        if (owned.isEmpty()) return RankingValue.Empty("현재 보유 차량이 없습니다.")
        val values = owned.map { car ->
            val price = prices[car.vehicleId] ?: return RankingValue.Failure(ContractError.DATA_UNAVAILABLE)
            val multiplier = when (car.rarity) { Rarity.COMMON -> 10; Rarity.ADVANCED -> 11; Rarity.RARE -> 12; Rarity.LEGEND -> 13 }
            price.units.toBigDecimal().multiply(multiplier.toBigDecimal()).divide(BigDecimal.TEN)
        }
        return RankingValue.Available(values.maxOrNull()!!, "현재 보유 차종 게임가격 × 개별 희귀도 수익 배율 · 취득액/매각가 아님")
    }
    fun income(rows: List<RankingIncome>, definition: RankingIncomeDefinition?, source: InputSource): RankingValue {
        if (definition == null) return RankingValue.Missing("누적 수익에 포함할 거래 원인 기준이 아직 공급되지 않았습니다.")
        val unique = linkedMapOf<RequestId, RankingIncome>()
        for (row in rows) {
            if (row.source != source || unique[row.requestId]?.let { it != row } == true) return invalid()
            unique[row.requestId] = row
        }
        val included = unique.values.filter { it.origin in definition.included }
        if (included.isEmpty()) return RankingValue.Empty("선택한 정의로 집계할 확정 수익 기록이 없습니다.")
        return RankingValue.Available(included.fold(BigDecimal.ZERO) { sum, row -> sum + row.amount.units.toBigDecimal() },
            "확정 수익 누적 · ${definition.included.joinToString { it.title }}")
    }
    fun week(rows: List<RankingDayDistance>, definition: RankingWeekDefinition?, source: InputSource, asOf: EpochMillis): RankingValue {
        if (definition == null) return RankingValue.Missing("주 시작 요일·시간대 기준이 아직 공급되지 않았습니다.")
        if (asOf.value < 0) return invalid()
        val today = Instant.ofEpochMilli(asOf.value).atZone(definition.zone).toLocalDate()
        val start = today.with(TemporalAdjusters.previousOrSame(definition.firstDay)); val end = start.plusDays(7)
        val days = linkedMapOf<java.time.LocalDate, RankingDayDistance>()
        for (row in rows) {
            if (row.source != source || row.rewardedMeters !in 0..500000 || days.put(row.day, row) != null) return invalid()
        }
        if(days.values.any{(it.departureZone as? Observation.Available)?.value != definition.zone})
            return RankingValue.Missing("출발 당시 날짜의 시간대 근거가 없거나 집계 시간대와 다릅니다. 날짜 키를 임의 변환하지 않습니다.")
        val included = days.values.filter { it.day >= start && it.day < end }
        if (included.any { it.day > today }) return invalid()
        if (included.isEmpty()) return RankingValue.Empty("$start ~ $end 미만 · ${definition.zone.id} · 확정 주행 기록 없음")
        return RankingValue.Available(included.sumOf { it.rewardedMeters }.toBigDecimal(),
            "$start ~ $end 미만 · ${definition.zone.id} · 출발일/일 500km 상한")
    }
}
