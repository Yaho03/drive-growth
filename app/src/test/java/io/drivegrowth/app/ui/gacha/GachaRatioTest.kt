package io.drivegrowth.app.ui.gacha

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class GachaRatioTest {
    private fun base() = (GachaPreviewStates.ready().screen as GachaScreen.Offers).value
    private fun weights() = linkedMapOf(Rarity.COMMON to 0L,Rarity.ADVANCED to 0L,Rarity.RARE to 50L,Rarity.LEGEND to 3L)
    private fun ratio(weights: Map<Rarity,Long> = weights(), denominator: Long = 53) = base().copy(appliedPercent = null,appliedRatio = GachaRatio(weights,denominator))

    @Test fun `정확한 비율은 유한소수 없이 기존 선택 확인과 복원을 허용한다`() {
        val ui=GachaInteraction({});ui.render(GachaFrame(1,GachaScreen.Offers(ratio())),true)
        assertTrue(ui.select("paid"));assertTrue(ui.confirm())
        val saved=ui.uiState
        val restored=GachaInteraction({},saved)
        assertEquals(53L,restored.uiState.pending!!.input.offers.appliedRatio!!.denominator)
        assertEquals(saved,restored.uiState)
    }
    @Test fun `가중치 키 부호 분모 합과 overflow 손상은 표시를 차단한다`() {
        val variants=listOf(ratio(denominator=0),ratio(denominator=-1),ratio(denominator=54),
            ratio(weights().apply { remove(Rarity.COMMON) }),ratio(weights().apply { put(Rarity.COMMON,-1) }),
            ratio(Rarity.entries.associateWith { Long.MAX_VALUE },Long.MAX_VALUE))
        for(value in variants) assertEquals(GachaAvailability.INVALID_DISPLAY,value.displayProblem())
    }
    @Test fun `퍼센트와 비율을 함께 공급하면 정확히 일치해야 한다`() {
        val normal=GachaRatio(mapOf(Rarity.COMMON to 747L,Rarity.ADVANCED to 200L,Rarity.RARE to 50L,Rarity.LEGEND to 3L),1000)
        assertTrue(base().copy(appliedRatio=normal).ratesValid())
        assertFalse(base().copy(appliedRatio=GachaRatio(weights(),53)).ratesValid())
        assertFalse(base().copy(appliedRatio=normal,appliedPercent=base().appliedPercent!!.toMutableMap().apply { put(Rarity.COMMON,BigDecimal("74.70000000001")) }).ratesValid())
        assertEquals(GachaAvailability.MISSING,base().copy(appliedPercent=null).displayProblem())
        assertTrue(base().ratesValid())
    }
    @Test fun `입력 map과 결과 map의 외부 변경은 비율과 보존 요청을 바꾸지 못한다`() {
        val input=weights();val exact=GachaRatio(input,53);val value=base().copy(appliedPercent=null,appliedRatio=exact)
        input[Rarity.RARE]=1
        assertEquals(50L,exact.numerators.getValue(Rarity.RARE))
        try { (exact.numerators as MutableMap)[Rarity.RARE]=2;fail("비율 map 변경 허용") } catch (_: UnsupportedOperationException) {}
        assertTrue(value.ratesValid())
        assertEquals(GachaRatio(weights(),53),exact)
    }
}
