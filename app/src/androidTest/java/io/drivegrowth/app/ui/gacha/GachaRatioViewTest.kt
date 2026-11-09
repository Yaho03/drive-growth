package io.drivegrowth.app.ui.gacha

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.core.contract.Rarity

/** 정확한 비율 표시·확인·새 View 복원. 기기 슬롯 미배정으로 컴파일만 검증한다. */
@Suppress("DEPRECATION")
class GachaRatioViewTest : InstrumentationTestCase() {
    private fun nodes(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { nodes(v.getChildAt(it)) } else emptyList()
    private fun text(v:View)=nodes(v).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    fun testExactGuaranteedRatioShowsFractionsAndRestoresPending() {
        var failure:Throwable?=null
        instrumentation.runOnMainSync {
            try {
                val original=GachaPreviewStates.guaranteed()
                val offers=(original.screen as GachaScreen.Offers).value.copy(appliedPercent=null,
                    appliedRatio=GachaRatio(mapOf(Rarity.COMMON to 0L,Rarity.ADVANCED to 0L,Rarity.RARE to 50L,Rarity.LEGEND to 3L),53))
                val frame=original.copy(screen=GachaScreen.Offers(offers))
                val events=mutableListOf<GachaEvent>()
                val view=GachaView(instrumentation.targetContext,events::add)
                assertTrue(view.render(frame,true))
                assertTrue(text(view).contains("희귀 · 50/53"));assertTrue(text(view).contains("전설 · 3/53"))
                assertFalse(text(view).contains("94.3396%"))
                view.findViewWithTag<Button>("gacha:option:paid").performClick()
                view.findViewWithTag<Button>("gacha:confirm").performClick()
                assertEquals(1,events.size)
                val restored=GachaView(instrumentation.targetContext,events::add,view.uiState)
                restored.render(frame,true)
                assertEquals(view.uiState.pending,restored.uiState.pending)
                assertTrue(text(restored).contains("희귀 · 50/53"))
            } catch(t:Throwable) { failure=t }
        }
        failure?.let { throw it }
    }
}
