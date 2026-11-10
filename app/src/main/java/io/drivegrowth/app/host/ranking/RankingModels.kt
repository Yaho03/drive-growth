package io.drivegrowth.app.host.ranking

import io.drivegrowth.core.contract.*
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.ZoneId

/** 단위는 게임 값이다. 실차 가격·현재 잔액·옛 XP 점수로 대체하지 않는다. */
enum class RankingMetric(val title: String, val unit: String) {
    SHOP_LEVEL("가게 레벨", "레벨"), COLLECTION("도감 수집", "칸"), OWNED("보유 차량", "대"),
    EARNED("누적 게임머니", "게임머니"), WEEKLY_DISTANCE("이번 주 보상 반영 거리", "m"), HIGHEST_VALUE("가장 비싼 보유 차량", "게임머니")
}
sealed interface RankingValue {
    data class Available(val amount: BigDecimal, val basis: String) : RankingValue { init { require(amount.signum()>=0) } }
    data class Empty(val reason: String) : RankingValue
    data class Missing(val reason: String) : RankingValue
    data class Failure(val error: ContractError) : RankingValue
}
enum class CollectionCountDefinition { OBTAINED, SEEN_OR_OBTAINED }
enum class RankingIncomeOrigin(val title:String) { DRIVING("주행"), DELIVERY_INCOME("수익 탁송"), RENTAL("영업"), DISPOSAL("판매·폐차"), QUEST("퀘스트") }
data class RankingIncomeDefinition(val version: RuleVersion, val included: Set<RankingIncomeOrigin>)
data class RankingWeekDefinition(val version: RuleVersion, val firstDay: DayOfWeek, val zone: ZoneId)
/** 미공급 정책은 null이며 0/현재 잔액/임의 주 경계로 보정하지 않는다. */
data class RankingDefinitions(val collection: CollectionCountDefinition? = null,
    val income: RankingIncomeDefinition? = null, val week: RankingWeekDefinition? = null) {
    companion object {
        /** 2026-11-10 사용자 결정. 표시 집계 정의이며 지급/경제 규칙을 바꾸지 않는다. */
        fun acceptedLocal() = RankingDefinitions(CollectionCountDefinition.OBTAINED,
            RankingIncomeDefinition(RuleVersion("ranking-local-2026-11-10"),RankingIncomeOrigin.entries.toSet()),
            RankingWeekDefinition(RuleVersion("ranking-week-2026-11-10"),DayOfWeek.MONDAY,ZoneId.of("Asia/Seoul")))
    }
}
data class RankingIncome(val requestId: RequestId, val origin: RankingIncomeOrigin, val amount: GameMoney, val source: InputSource)
/** day는 출발 당시 현지 달력 날짜다. UTC instant로 재해석하지 않는다. */
data class RankingDayDistance(val day: java.time.LocalDate, val rewardedMeters: Long, val source: InputSource,
    val departureZone: Observation<ZoneId> = Observation.Missing(MissingReason.NO_DATA))
data class RankingSnapshot(val source: InputSource, val asOf: EpochMillis, val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion, val metrics: Map<RankingMetric, RankingValue>, val definitions: RankingDefinitions? = null)

internal fun RankingDefinitions.frozen()=copy(income=income?.copy(included=java.util.Collections.unmodifiableSet(HashSet(income.included))))
internal fun RankingSnapshot.frozen()=copy(metrics=java.util.Collections.unmodifiableMap(LinkedHashMap(metrics)),definitions=definitions?.frozen())
