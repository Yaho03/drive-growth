package io.drivegrowth.app.host.facility

import io.drivegrowth.app.data.ShopState
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*

/** 같은 원장 조회 사실. 순차 조회이며 일괄 원자 snapshot을 주장하지 않는다. */
data class FacilityFacts(val identity:GameIdentity, val definitionVersion:ContentVersion,
    val shop:ShopState, val balance:GameMoney, val vehicles:List<OwnedVehicle>,
    val activities:Map<OwnedVehicleId,VehicleActivity>, val resting:Set<OwnedVehicleId>,
    val offers:Map<Facility,FacilityOffer>,
    val fuelBonusPerRental:GameMoney? = null)

/** 호스트 포트의 전체 입력이다. 실제 E typed codec/원결과는 별도 adapter가 공급한다. */
sealed interface FacilityCommand {
    val identity:GameIdentity
    val requestId:RequestId
    data class Buy(override val identity:GameIdentity, override val requestId:RequestId,
        val definitionVersion:ContentVersion, val offer:FacilityOffer):FacilityCommand
    data class Rest(override val identity:GameIdentity, override val requestId:RequestId,
        val vehicle:VehicleRef, val expected:Boolean, val desired:Boolean):FacilityCommand
}
sealed interface FacilityReceipt {
    val command:FacilityCommand
    data class Purchased(override val command:FacilityCommand.Buy,val settlement:Settlement):FacilityReceipt
    /** 재화 없는 설정 결과. 금융 Settlement를 생성하지 않는다. */
    data class Rested(override val command:FacilityCommand.Rest,val before:Boolean,val after:Boolean):FacilityReceipt
}
sealed interface FacilityPending {
    data object Empty:FacilityPending
    data object Unsupported:FacilityPending
    data object Foreign:FacilityPending
    data class Own(val command:FacilityCommand):FacilityPending
}
/** 이 실행이 설치를 확정했고, 같은 저장 경계에서 운영 상태를 읽은 경우에만 true다. */
data class FacilityPurchaseExecution(val receipt: FacilityReceipt, val newlyInstalled: Boolean)

interface FacilityHostSource {
    fun pending():ContractResult<FacilityPending>
    fun read():ContractResult<FacilityFacts>
    fun save(command:FacilityCommand):ContractResult<Unit>
    fun execute(command:FacilityCommand):ContractResult<FacilityReceipt>
    fun executeForInstallation(command: FacilityCommand.Buy): ContractResult<FacilityPurchaseExecution> =
        when (val result = execute(command)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> ContractResult.Success(FacilityPurchaseExecution(result.value, false))
        }
    fun finish(command:FacilityCommand):ContractResult<Unit>
    fun cancelSaved(command:FacilityCommand):ContractResult<Unit>
}
data class RestingRequest(val version:Int,val requestId:RequestId,val vehicle:VehicleRef,val expected:Boolean,val desired:Boolean)
data class RestingRow(val vehicle:VehicleRef,val activity:VehicleActivity?,val resting:Boolean,val description:String)
data class FacilityHostState(val version:Int=0,val facts:FacilityFacts?=null,val pending:FacilityCommand?=null,
    val receipt:FacilityReceipt?=null,val error:ContractError?=null,val foreign:Boolean=false,
    val unsupported:Boolean=false,val busy:Boolean=false,
    val newInstallation: FacilityReceipt.Purchased? = null)
