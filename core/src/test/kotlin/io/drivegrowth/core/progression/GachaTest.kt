package io.drivegrowth.core.progression

import io.drivegrowth.core.contract.Rarity

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GachaTest {
    private val gacha = Gacha()

    // 항상 0을 내놓는 난수: 일반 풀에서는 COMMON, 보장 풀에서는 RARE가 나온다.
    private object AlwaysLowest : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    @Test fun `같은 seed는 같은 결과를 낸다`() {
        val a = (0 until 50).map { gacha.drawRarity(0, Random(7 + it)) }
        val b = (0 until 50).map { gacha.drawRarity(0, Random(7 + it)) }
        assertEquals(a, b)
    }

    @Test fun `29번 연속 희귀 미만이면 30번째는 희귀 이상이 확정된다`() {
        var counter = 0
        repeat(29) {
            val draw = gacha.drawRarity(counter, AlwaysLowest)
            assertEquals(Rarity.COMMON, draw.rarity)
            assertFalse(draw.guaranteed)
            counter = draw.drawsSinceGuarantee
        }
        assertEquals(29, counter)
        val last = gacha.drawRarity(counter, AlwaysLowest)
        assertTrue(last.guaranteed)
        assertEquals(Rarity.RARE, last.rarity)
        assertEquals(0, last.drawsSinceGuarantee)
    }

    @Test fun `보장 뽑기는 어떤 난수에서도 희귀 미만이 나오지 않는다`() {
        repeat(2_000) { seed ->
            assertTrue(gacha.drawRarity(29, Random(seed)).rarity >= Rarity.RARE)
        }
    }

    @Test fun `희귀 이상이 나오면 카운터는 0이 되고 아니면 1 오른다`() {
        val random = Random(1)
        repeat(5_000) {
            val before = it % 29
            val draw = gacha.drawRarity(before, random)
            val expected = if (draw.rarity >= Rarity.RARE) 0 else before + 1
            assertEquals(expected, draw.drawsSinceGuarantee)
        }
    }

    @Test fun `보장 없는 뽑기의 분포는 공개 확률과 맞는다`() {
        val random = Random(42)
        val n = 200_000
        val counts = Rarity.entries.associateWith { 0 }.toMutableMap()
        repeat(n) { counts.merge(gacha.drawRarity(0, random).rarity, 1, Int::plus) }
        fun share(r: Rarity) = counts.getValue(r) * 1000.0 / n
        assertEquals(747.0, share(Rarity.COMMON), 5.0)
        assertEquals(200.0, share(Rarity.ADVANCED), 5.0)
        assertEquals(50.0, share(Rarity.RARE), 2.0)
        assertEquals(3.0, share(Rarity.LEGEND), 0.6)
    }

    @Test fun `범위 밖 카운터와 잘못된 규칙은 거절한다`() {
        assertThrows(IllegalArgumentException::class.java) { gacha.drawRarity(30, Random(0)) }
        assertThrows(IllegalArgumentException::class.java) { gacha.drawRarity(-1, Random(0)) }
        assertThrows(IllegalArgumentException::class.java) {
            GachaRules(weights = mapOf(Rarity.COMMON to 1000, Rarity.ADVANCED to 0, Rarity.RARE to 0, Rarity.LEGEND to 0))
        }
    }
}
