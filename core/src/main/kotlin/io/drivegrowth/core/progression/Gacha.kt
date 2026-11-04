package io.drivegrowth.core.progression

import kotlin.random.Random

// 희귀도 순서가 곧 비교 순서다(COMMON < ... < LEGEND).
enum class Rarity { COMMON, ADVANCED, RARE, LEGEND }

// 확률은 천분율 가중치(74.7% → 747). 수치는 PRD "가챠"의 시험값.
data class GachaRules(
    val weights: Map<Rarity, Int> = mapOf(Rarity.COMMON to 747, Rarity.ADVANCED to 200, Rarity.RARE to 50, Rarity.LEGEND to 3),
    val pity: Int = 30,
    val guaranteedFrom: Rarity = Rarity.RARE,
) {
    init {
        require(Rarity.entries.all { (weights[it] ?: 0) >= 0 } && weights.values.sum() > 0) { "가중치는 0 이상, 합은 양수" }
        require(pity >= 1) { "천장은 1회 이상" }
        require(Rarity.entries.filter { it >= guaranteedFrom }.sumOf { weights[it] ?: 0 } > 0) { "보장 대상 희귀도의 가중치가 필요하다" }
    }
}

// drawsSinceGuarantee: 마지막 보장 대상 획득 이후 뽑은 횟수. 결과와 함께 저장해 재실행으로 재추첨하지 않는다.
data class GachaDraw(val rarity: Rarity, val drawsSinceGuarantee: Int, val guaranteed: Boolean)

class Gacha(private val rules: GachaRules = GachaRules()) {
    fun drawRarity(drawsSinceGuarantee: Int, random: Random): GachaDraw {
        require(drawsSinceGuarantee in 0 until rules.pity) { "천장 카운터는 0..${rules.pity - 1}" }
        val guaranteed = drawsSinceGuarantee + 1 == rules.pity
        val pool = Rarity.entries.filter { !guaranteed || it >= rules.guaranteedFrom }
        val rarity = pick(pool, random)
        val next = if (rarity >= rules.guaranteedFrom) 0 else drawsSinceGuarantee + 1
        return GachaDraw(rarity, next, guaranteed)
    }

    private fun pick(pool: List<Rarity>, random: Random): Rarity {
        var roll = random.nextInt(pool.sumOf { rules.weights[it] ?: 0 })
        for (rarity in pool) {
            roll -= rules.weights[rarity] ?: 0
            if (roll < 0) return rarity
        }
        error("가중치 합 계산 오류")
    }
}
