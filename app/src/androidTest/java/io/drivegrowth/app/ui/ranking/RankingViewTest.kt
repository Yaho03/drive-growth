package io.drivegrowth.app.ui.ranking

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.drivegrowth.app.host.ranking.*
import io.drivegrowth.core.contract.*
import java.math.BigDecimal

@Suppress("DEPRECATION")
class RankingViewTest:InstrumentationTestCase(){
    private fun texts(view:View):List<String> = if(view is ViewGroup)(0 until view.childCount).flatMap{texts(view.getChildAt(it))}else if(view is TextView)listOf(view.text.toString())else emptyList()
    private fun button(view:View,label:String):View? {if(view is TextView&&view.text.toString()==label&&view.isClickable)return view;if(view is ViewGroup)for(i in 0 until view.childCount)button(view.getChildAt(i),label)?.let{return it};return null}
    private fun content()=RankingState.Content(RankingSnapshot(InputSource.SIMULATION,EpochMillis(1),ContentVersion("c"),RuleVersion("r"),RankingMetric.entries.associateWith{RankingValue.Available(BigDecimal(7),"확정 입력")}))
    fun testRealRecordNeverHasRankOrSyntheticPlayersAndRestrictionHidesBoth(){instrumentation.runOnMainSync{
        val view=RankingView(instrumentation.targetContext){};view.render(content(),true)
        assertTrue(texts(view).any{it.contains("모의 입력 기록")});assertFalse(texts(view).any{it.contains("예시 가게")||it.contains("1위")})
        button(view,"시연용 랭킹 (예시 데이터)")!!.performClick();assertTrue(texts(view).any{it.contains("예시 가게")})
        view.render(content(),false);assertFalse(texts(view).any{it.contains("예시 가게")||it.contains("가게 레벨")});assertEquals(RankingMode.DEMO,view.uiState.mode)
        view.render(content(),true);assertTrue(texts(view).any{it.contains("예시 가게")});assertTrue(view.handleBack());assertEquals(RankingMode.MY_RECORD,view.uiState.mode)
    }}
    fun testMetricSelectionAndUiRestorationAreNotDataRestoration(){instrumentation.runOnMainSync{
        val view=RankingView(instrumentation.targetContext){};view.render(content(),true);button(view,"시연용 랭킹 (예시 데이터)")!!.performClick();button(view,RankingMetric.OWNED.title)!!.performClick()
        val saved=view.uiState;val next=RankingView(instrumentation.targetContext){};next.restoreUiState(saved);next.render(RankingState.Loading,true)
        assertEquals(RankingMetric.OWNED,next.uiState.metric);assertTrue(texts(next).any{it.contains("예시 가게")});assertFalse(texts(next).any{it.contains("확정 입력")})
    }}
    fun testRetryOnlyOnceUntilFreshStateAndMissingNeverLooksLikeZero(){instrumentation.runOnMainSync{
        var retries=0;val view=RankingView(instrumentation.targetContext){retries++}
        view.render(RankingState.Content(content().snapshot.copy(metrics=RankingMetric.entries.associateWith{RankingValue.Missing("증거 없음")})),true)
        assertTrue(texts(view).any{it.contains("확인 불가")});button(view,"다시 조회")!!.performClick();assertEquals(1,retries);assertTrue(view.uiState.retryPending)
        view.render(RankingState.Failure(ContractError.DATA_UNAVAILABLE),true);assertFalse(view.uiState.retryPending)
    }}
    fun testNarrowMeasuredWindowWrapsLongMissingReasonWithoutChangingSelection(){instrumentation.runOnMainSync{
        val view=RankingView(instrumentation.targetContext){};val reason="아직 확인되지 않은 기록의 출발 당시 시간대 근거가 필요합니다. ".repeat(8)
        view.render(RankingState.Content(content().snapshot.copy(metrics=RankingMetric.entries.associateWith{RankingValue.Missing(reason)})),true)
        val density=instrumentation.targetContext.resources.displayMetrics.density
        val width=(440*density).toInt();val height=(600*density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
        assertEquals(width,view.measuredWidth);assertTrue(texts(view).any{it.contains(reason)})
        assertEquals(RankingMode.MY_RECORD,view.uiState.mode);assertEquals(RankingMetric.SHOP_LEVEL,view.uiState.metric)
    }}

}
