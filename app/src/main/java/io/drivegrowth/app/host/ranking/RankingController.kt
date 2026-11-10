package io.drivegrowth.app.host.ranking

import io.drivegrowth.core.contract.*
import java.util.concurrent.Executor

sealed interface RankingState {
    data object Restricted:RankingState
    data object Loading:RankingState
    data class Content(val snapshot:RankingSnapshot):RankingState
    data class Failure(val error:ContractError):RankingState
}
/** UI thread 진입/응답, worker 읽기. 제한·화면 해제 뒤 늦은 결과는 버린다. */
class RankingController(private val worker:Executor,private val main:Executor,private val read:()->ContractResult<RankingSnapshot>) {
    private var enabled=false
    private var released=false
    private var generation=0L
    private var busy=false
    private var requested=false
    private var listener:((RankingState)->Unit)?=null
    fun attach(listener:(RankingState)->Unit){detach();if(released)return;this.listener=listener;listener(RankingState.Restricted)}
    fun detach(){generation++;enabled=false;requested=false;listener=null}
    fun setControlsEnabled(value:Boolean){if(released||enabled==value)return;enabled=value;generation++;if(value)refresh()else{requested=false;listener?.invoke(RankingState.Restricted)}}
    fun refresh(){if(released||!enabled)return;generation++;requested=true;listener?.invoke(RankingState.Loading);launch()}
    fun release(){detach();released=true}
    private fun launch(){
        if(released||!enabled||busy||!requested)return
        busy=true;requested=false;val started=generation
        worker.execute{
            val result=try{read()}catch(_:SecurityException){ContractResult.Failure(ContractError.PERMISSION_DENIED)}catch(_:RuntimeException){ContractResult.Failure(ContractError.STORAGE_FAILURE)}
            main.execute{busy=false;if(!released&&enabled&&started==generation)listener?.invoke(when(result){is ContractResult.Success->RankingState.Content(result.value.frozen());is ContractResult.Failure->RankingState.Failure(result.error)});launch()}
        }
    }
}
