package io.drivegrowth.app.ui.ranking

import android.content.Context
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.host.ranking.*
import io.drivegrowth.app.ui.shop.ShopStyle
import io.drivegrowth.core.contract.InputSource

enum class RankingMode { MY_RECORD, DEMO }
data class RankingUiState(val mode:RankingMode=RankingMode.MY_RECORD,val metric:RankingMetric=RankingMetric.SHOP_LEVEL,
    val scrollY:Int=0,val retryPending:Boolean=false) { init { require(scrollY>=0) } }
sealed interface RankingEvent { data object Retry:RankingEvent }

/** 임시 native View. 실제 내 기록과 합성 시연 순위를 다른 화면/출처로 유지한다. 신호·DB를 직접 읽지 않는다. */
class RankingView(context:Context,private val onEvent:(RankingEvent)->Unit):LinearLayout(context) {
    private val style=ShopStyle(context)
    private val demo=DemoRankingProvider()
    private var saved=RankingUiState()
    private var state:RankingState=RankingState.Loading
    private var allowed=false
    private var scroll:ScrollView?=null
    val uiState:RankingUiState get()=saved.copy(scrollY=scroll?.takeIf{it.isLaidOut}?.scrollY?:saved.scrollY)
    init { orientation=VERTICAL;setBackgroundColor(style.base) }
    fun restoreUiState(value:RankingUiState){saved=value.copy();paint()}
    fun render(value:RankingState,controlsEnabled:Boolean){
        saved=uiState
        state=if(value is RankingState.Content)value.copy(snapshot=value.snapshot.frozen()) else value;allowed=controlsEnabled && value!=RankingState.Restricted
        if(value is RankingState.Content || value is RankingState.Failure) saved=saved.copy(retryPending=false)
        paint()
    }
    fun handleBack():Boolean {
        if(!allowed || saved.mode==RankingMode.MY_RECORD)return false
        saved=uiState.copy(mode=RankingMode.MY_RECORD);paint();return true
    }
    private fun paint(){
        removeAllViews();scroll=null
        if(!allowed){addView(style.text("주차 후 조작이 허용되면 이용할 수 있습니다."));return}
        val body=style.column().apply{setPadding(style.dp(24),style.dp(16),style.dp(24),style.dp(16))}
        body.addView(style.text("기록과 랭킹",28,bold=true))
        val modes=style.row()
        listOf(RankingMode.MY_RECORD to "내 기록",RankingMode.DEMO to "시연용 랭킹 (예시 데이터)").forEach{(mode,label)->
            modes.addView(style.button(label,primary=saved.mode==mode){if(allowed){saved=uiState.copy(mode=mode);paint()}})
        }
        body.addView(HorizontalScrollView(context).apply{addView(modes)})
        if(saved.mode==RankingMode.DEMO) showDemo(body) else showMine(body)
        scroll=style.scroll(body,saved.scrollY).also{addView(it,LayoutParams(-1,-1))}
    }
    private fun showMine(body:LinearLayout){
        body.addView(style.text("내 기기에 저장된 기록 · 실제 사용자 간 온라인 순위는 제공하지 않습니다.",18,color=style.muted))
        when(val current=state){
            RankingState.Loading->body.addView(style.text("기록을 조회하고 있습니다."))
            is RankingState.Failure->{body.addView(style.text("기록을 읽지 못했습니다. 다시 조회해 주세요."));retry(body)}
            is RankingState.Content->{
                val data=current.snapshot
                body.addView(style.text((if(data.source==InputSource.VEHICLE)"차량 입력 기록" else "모의 입력 기록")+" · 기준 "+java.time.format.DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(java.time.ZoneId.of("Asia/Seoul")).format(java.time.Instant.ofEpochMilli(data.asOf.value))+" 한국시간",16,color=style.muted))
                for(metric in RankingMetric.entries){
                    val card=style.card();card.addView(style.text(metric.title,22,bold=true))
                    when(val value=data.metrics[metric]){
                        is RankingValue.Available->{card.addView(style.text(value.amount.stripTrailingZeros().toPlainString()+" "+metric.unit,26,bold=true));card.addView(style.text(value.basis,16,color=style.muted))}
                        is RankingValue.Empty->card.addView(style.text(value.reason))
                        is RankingValue.Missing->card.addView(style.text("확인 불가 · ${value.reason}"))
                        is RankingValue.Failure->card.addView(style.text("조회 실패 · 기록을 확인할 수 없습니다."))
                        null->card.addView(style.text("확인 불가 · 지표가 공급되지 않았습니다."))
                    }
                    style.add(body,card)
                }
                retry(body)
            }
            RankingState.Restricted->Unit
        }
    }
    private fun showDemo(body:LinearLayout){
        body.addView(style.text("시연용 랭킹 (예시 데이터)",24,bold=true))
        body.addView(style.text("고정 합성 가게만 표시합니다. 내 실제 기록·사용자·위치는 포함하지 않습니다.",18,color=style.muted))
        val selectors=style.row()
        RankingMetric.entries.forEach{metric->selectors.addView(style.button(metric.title,primary=saved.metric==metric){if(allowed){saved=uiState.copy(metric=metric);paint()}})}
        body.addView(HorizontalScrollView(context).apply{addView(selectors)})
        body.addView(style.text("시연 정렬: 큰 값 우선, 동률 공동 순위(1, 2, 2, 4). 실제 온라인 동률 정책이 아닙니다.",16,color=style.muted))
        demo.rows(saved.metric).forEach{row->style.add(body,style.card().apply{
            addView(style.text("${row.rank}위 · ${row.entry.label}",22,bold=true))
            addView(style.text(row.value.stripTrailingZeros().toPlainString()+" "+saved.metric.unit))
        })}
    }
    private fun retry(body:LinearLayout){body.addView(style.button(if(saved.retryPending)"조회 중" else "다시 조회",!saved.retryPending){
        if(allowed&&!saved.retryPending){saved=uiState.copy(retryPending=true);paint();onEvent(RankingEvent.Retry)}
    })}
}
