package io.drivegrowth.app.host.shop

import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*

/**
 * 공개 호출/reply/render는 UI 한 스레드, source는 worker 전용이다. gate/Activity 수명은 조립자가 공급한다.
 * 표시/선택은 무차감이며 prepare의 durable save 성공 뒤에만 execute한다. release는 저장 명령을 지우지 않는다.
 */
class ShopExpansionHostController(
    private val source: ShopExpansionHostSource,
    private val worker: (() -> Unit) -> Unit,
    private val reply: (() -> Unit) -> Unit,
    private val render: (ShopScreenState, Boolean) -> Unit,
    private val complete: (ShopRequest, ContractResult<Unit>) -> Unit,
    private val completeRetry: () -> Unit,
    /** 원래 settlement가 재생될 수 있다. 이 callback에서 보상/가게 상태를 직접 누적하지 않는다. */
    private val committed: (Settlement) -> Unit = {},
    initialRevision: Revision = Revision(0),
) {
    @Volatile private var allowed=false
    @Volatile private var released=false
    private var busy=false
    private var sequence=0L
    private var generation=initialRevision.value
    private var frame:ShopScreenState=ShopScreenState.Loading
    private var current:ShopExpansionSnapshot?=null
    private var pending:HostCheckpointCommand.Expand?=null
    private var activeRequest:ShopRequest?=null
    init { require(initialRevision.value<Int.MAX_VALUE) }

    fun setControlsEnabled(value:Boolean) { if(!released){allowed=value;render(frame,allowed)} }
    fun release() { released=true;allowed=false;sequence++ }

    /** 시작 시 durable 슬롯을 먼저 읽으며 현재 정의로 원명령을 다시 준비하지 않는다. */
    fun load():Boolean {
        if(released || !allowed || busy)return false
        busy=true;val serial=++sequence;show(ShopScreenState.Loading)
        worker {
            val saved=if(released || !allowed) ContractResult.Failure(ContractError.UX_RESTRICTED) else source.pending()
            reply {
                if(valid(serial)) {
                    completeRetry()
                    if(valid(serial)) when(saved) {
                        is ContractResult.Failure->fail(saved.error)
                        is ContractResult.Success->{
                            pending=saved.value
                            if(pending!=null)execute() else read(serial)
                        }
                    }
                }
            }
        }
        return true
    }

    fun submit(request:ShopRequest):Boolean {
        if(released)return false
        if(busy){
            if(activeRequest!=request)complete(request,ContractResult.Failure(ContractError.STATE_CONFLICT))
            return false
        }
        if(!allowed){complete(request,ContractResult.Failure(ContractError.UX_RESTRICTED));return false}
        val snapshot=current
        if(snapshot==null || frame !is ShopScreenState.Content || request.selection.revision!=snapshot.screen.revision ||
            request.selection.option!=snapshot.screen.expansion || request.selection.option.availability!=ShopAvailability.AVAILABLE || pending!=null) {
            complete(request,ContractResult.Failure(ContractError.STATE_CONFLICT));return false
        }
        busy=true;activeRequest=request;val serial=++sequence;show(ShopScreenState.Loading)
        worker {
            val prepared=if(released || !allowed)ContractResult.Failure(ContractError.UX_RESTRICTED) else source.prepare(snapshot,request)
            reply { if(valid(serial)) when(prepared) {
                is ContractResult.Failure->fail(prepared.error)
                is ContractResult.Success->{pending=prepared.value;execute()}
            } }
        }
        return true
    }

    /** 사용자의 명시 취소용. 실제 ledger가 SAVED/미실행만 허용하며 STARTED 결과 불명은 보존한다. */
    fun cancelSaved():Boolean {
        val command=pending ?: return false
        if(released || !allowed || busy)return false
        busy=true;val serial=++sequence
        worker {
            val cancelled=if(released || !allowed)ContractResult.Failure(ContractError.UX_RESTRICTED) else source.cancelSaved(command)
            reply { if(valid(serial)) when(cancelled) {
                is ContractResult.Failure->fail(cancelled.error)
                is ContractResult.Success->{pending=null;activeRequest=null;busy=false;load()}
            } }
        }
        return true
    }

    private fun execute() {
        val command=checkNotNull(pending)
        if(released)return
        if(!allowed){fail(ContractError.UX_RESTRICTED);return}
        busy=true;val serial=++sequence
        worker {
            val result=if(released || !allowed)ContractResult.Failure(ContractError.UX_RESTRICTED) else source.execute(command)
            reply { if(valid(serial)) when(result) {
                is ContractResult.Failure->fail(result.error)
                is ContractResult.Success->{
                    val request=activeRequest;activeRequest=null
                    committed(result.value)
                    if(valid(serial) && request!=null)complete(request,ContractResult.Success(Unit))
                    if(valid(serial))finish(serial,command)
                }
            } }
        }
    }
    private fun finish(serial:Long,command:HostCheckpointCommand.Expand) {
        // 실제 확정 결과를 받은 뒤의 원래 결과 검증/슬롯 정리다. 제한을 풀거나 새 게임 명령을 만들지 않는다.
        worker {
            val result=source.finish(command)
            reply { if(valid(serial)) when(result) {
                is ContractResult.Failure->fail(result.error)
                is ContractResult.Success->{pending=null;busy=false;if(allowed)load() else render(frame,false)}
            } }
        }
    }
    private fun read(serial:Long) {
        worker {
            val result=source.read()
            reply { if(valid(serial)) when(result) {
                is ContractResult.Failure->fail(result.error)
                is ContractResult.Success->{
                    if(generation==Int.MAX_VALUE){fail(ContractError.INVALID_INPUT);return@reply}
                    current=result.value.copy(screen=result.value.screen.copy(revision=Revision(++generation)))
                    busy=false;show(current!!.screen)
                }
            } }
        }
    }
    private fun fail(error:ContractError) {
        busy=false
        val request=activeRequest;activeRequest=null
        val serial=sequence
        if(request!=null)complete(request,ContractResult.Failure(error))
        if (valid(serial)) {
            val recovery = if (pending != null) "저장된 원명령을 재시도하거나 실행 전인 경우 명시 취소해 주세요."
                else "저장 상태를 다시 확인해 주세요."
            show(ShopScreenState.Failure("가게 확장 정보를 확인하지 못했어요. $recovery", error))
        }
    }
    private fun valid(serial:Long)=!released && sequence==serial
    private fun show(state:ShopScreenState){frame=state;render(state,allowed)}
}
