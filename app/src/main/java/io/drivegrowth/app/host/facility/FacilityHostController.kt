package io.drivegrowth.app.host.facility

import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import java.util.concurrent.Executor

/** 공개 메서드/changed는 UI 스레드, source는 직렬 worker 전용이다. 지급/설정의 실제 검증은 E 책임이다. */
class FacilityHostController(private val source:FacilityHostSource,private val worker:Executor,private val reply:Executor) {
    @Volatile private var enabled=false
    @Volatile private var released=false
    @Volatile private var epoch=0L
    @Volatile private var known:FacilityCommand?=null
    private var busy=false
    var changed:((FacilityHostState,Boolean)->Unit)?=null
    var state=FacilityHostState()
        private set
    fun setControlsEnabled(value:Boolean) {
        if(released || enabled==value)return
        enabled=value;epoch++;changed?.invoke(state,enabled)
        if(value && !busy)refresh()
    }
    fun release(){released=true;enabled=false;epoch++;changed=null}
    private fun publish(value:FacilityHostState){state=value;changed?.invoke(state,enabled && !released)}
    private fun failure(error:ContractError)=ContractResult.Failure(error)
    private fun available()=enabled && !released && !busy
    private fun run(action:(()->Boolean)->ContractResult<FacilityHostState>):Boolean {
        if(!available())return false
        busy=true;val token=epoch;publish(state.copy(busy=true,error=null,newInstallation=null))
        worker.execute {
            val valid={enabled && !released && epoch==token}
            val result=if(valid())action(valid) else failure(ContractError.UX_RESTRICTED)
            reply.execute {
                if(released)return@execute
                busy=false
                if(epoch!=token){publish(state.copy(busy=false));if(enabled)refresh();return@execute}
                when(result) {
                    is ContractResult.Failure->publish(state.copy(busy=false,error=result.error,pending=known))
                    is ContractResult.Success->publish(result.value.copy(version=state.version+1,busy=false))
                }
            }
        }
        return true
    }
    fun refresh():Boolean=run { valid ->
        when(val loaded=source.pending()) {
            is ContractResult.Failure->loaded
            is ContractResult.Success->when(val pending=loaded.value) {
                is FacilityPending.Own->{
                    if(known!=null && known!=pending.command)failure(ContractError.STATE_CONFLICT)
                    else {known=pending.command;execute(pending.command,valid)}
                }
                FacilityPending.Foreign->if(known!=null)failure(ContractError.STATE_CONFLICT) else ContractResult.Success(FacilityHostState(foreign=true))
                FacilityPending.Empty,FacilityPending.Unsupported->if(known!=null)failure(ContractError.STORAGE_FAILURE)
                    else when(val read=source.read()) {
                        is ContractResult.Failure->read
                        is ContractResult.Success->ContractResult.Success(FacilityHostState(facts=read.value,unsupported=pending==FacilityPending.Unsupported))
                    }
            }
        }
    }
    fun submit(request: ShopRequest, presentInstallation: Boolean = false): Boolean {
        if(!available() || state.pending!=null || state.receipt!=null || state.unsupported || state.foreign || state.error!=null)return false
        val facts=state.facts?:return false
        val option=FacilityHostMapper.options(facts,true).map{it.option}.singleOrNull{it==request.selection.option}?:return false
        if(request.selection.revision.value!=state.version || option.availability!=ShopAvailability.AVAILABLE)return false
        val offer=(option.command as? ShopCommand.BuyFacility)?.offer?:return false
        return save(FacilityCommand.Buy(facts.identity,request.requestId,facts.definitionVersion,offer), presentInstallation)
    }
    fun setResting(request:RestingRequest):Boolean {
        if(!available() || state.pending!=null || state.receipt!=null || state.unsupported || state.foreign || state.error!=null)return false
        val facts=state.facts?:return false
        val car=facts.vehicles.singleOrNull{it.ownedVehicleId==request.vehicle.ownedVehicleId}?:return false
        if(state.version!=request.version || car.revision!=request.vehicle.revision || car.lifecycle!=VehicleLifecycle.OWNED ||
            (car.ownedVehicleId in facts.resting)!=request.expected || request.expected==request.desired)return false
        return save(FacilityCommand.Rest(facts.identity,request.requestId,request.vehicle,request.expected,request.desired))
    }
    private fun save(command: FacilityCommand, presentInstallation: Boolean = false) = run { valid ->
        known=command
        when(val saved=source.save(command)) {
            is ContractResult.Failure->{
                if(saved.error in setOf(ContractError.INVALID_INPUT,ContractError.POLICY_UNRESOLVED,ContractError.UX_RESTRICTED,
                        ContractError.INSUFFICIENT_FUNDS,ContractError.CAPACITY_EXCEEDED,ContractError.STATE_CONFLICT))known=null
                saved
            }
            is ContractResult.Success -> if (presentInstallation && command is FacilityCommand.Buy && valid()) {
                when (val purchase = source.executeForInstallation(command)) {
                    is ContractResult.Failure -> purchase
                    is ContractResult.Success -> {
                        val result = purchase.value
                        if (result.receipt.command != command) failure(ContractError.STATE_CONFLICT)
                        else ContractResult.Success(FacilityHostState(pending = command, receipt = result.receipt,
                            newInstallation = (result.receipt as? FacilityReceipt.Purchased)?.takeIf { result.newlyInstalled }))
                    }
                }
            } else execute(command, valid)
        }
    }
    private fun execute(command:FacilityCommand,valid:()->Boolean):ContractResult<FacilityHostState> {
        if(!valid())return failure(ContractError.UX_RESTRICTED)
        return when(val result=source.execute(command)) {
            is ContractResult.Failure->result
            is ContractResult.Success->if(result.value.command!=command)failure(ContractError.STATE_CONFLICT)
                else ContractResult.Success(FacilityHostState(pending=command,receipt=result.value))
        }
    }
    fun acknowledge():Boolean {
        val command=known?:return false
        if(state.receipt?.command!=command)return false
        return run { _ -> when(val result=source.finish(command)) {
            is ContractResult.Failure->result
            is ContractResult.Success->{known=null;readAfterResolution()}
        } }
    }
    private fun readAfterResolution():ContractResult<FacilityHostState> = when(val read=source.read()) {
        is ContractResult.Failure->read
        is ContractResult.Success->ContractResult.Success(FacilityHostState(facts=read.value))
    }
    fun cancelSaved():Boolean {
        val command=known?:return false
        if(state.receipt!=null)return false
        return run { _ ->when(val result=source.cancelSaved(command)) {
            is ContractResult.Failure->result
            is ContractResult.Success->{known=null;readAfterResolution()}
        } }
    }
}
