package io.drivegrowth.app.host.rest

import io.drivegrowth.app.host.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import java.util.Collections

/** E가 같은 원장·버프·구간·원정산을 한 번에 확정할 원문. 현재 DrivingLedger는 배율을 지원하지 않는다. */
data class RestDrivingCommand(val identity:GameIdentity,val drive:ConfirmedDrive,val drivingRules:DrivingIncomeRules,
 val restRules:RestRules,val evidencePolicy:RestEvidencePolicy,val departure:RestProof?,val consumeId:String,val afterStop:Long?,val spans:List<RestObservedSpan>)
data class RestDrivingReceipt(val command:RestDrivingCommand,val departure:RestDeparture?,val remaining:RestBonusState,
 val settlement:Settlement?,val recordedMeters:Long,val rewardedMeters:Long)
/** 구현은 E 소유. 같은 session.store.atomic 안에서 depart/지급/consume/cursor/ack/provenance를 묶는다. */
interface RestDrivingJournal {
 /** 실제 RestLedger와 module marker/allocator/provenance를 함께 검증한다. missing은 NONE가 아니다. */
 fun state(session:GameSession):ContractResult<RestBonusState>
 fun commit(session:GameSession,command:RestDrivingCommand):ContractResult<RestDrivingReceipt>
}
class RestHostSource(private val games:GameSessionFactory,private val validator:RestEvidenceValidator,
 private val journal:RestDrivingJournal?) {
 fun preview(proof:RestProof):ContractResult<RestStop> = validator.stop(proof)
 /** 현재 helper 미지원/손상은 실제 실패다. NONE/활성으로 보정하지 않는다. worker 전용. */
 fun state():ContractResult<RestBonusState> {
  val reader=journal?:return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  return when(val opened=games.open()) {
   is ContractResult.Failure->opened
   is ContractResult.Success->opened.value.use{reader.state(it)}
  }
 }
 fun commit(value:RestDrivingCommand):ContractResult<RestDrivingReceipt> {
  val writer=journal?:return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
  val command=value.copy(spans=Collections.unmodifiableList(ArrayList(value.spans)),departure=value.departure?.let{
   it.copy(driving=Collections.unmodifiableList(ArrayList(it.driving)),parked=Collections.unmodifiableList(ArrayList(it.parked)))})
  if(command.consumeId.isBlank()||command.drive.distance.source!=command.identity.source||command.drivingRules.dailyCapMeters!=500000L||
   command.departure?.let{it.identity!=command.identity||it.driveId!=command.drive.driveId}==true)
   return ContractResult.Failure(ContractError.INVALID_INPUT)

  // 원결과 우선/중복 제거/현재 state 경쟁/지급·소모 검증은 E journal의 같은 거래에서 수행한다.
  return when(val opened=games.open()) {
   is ContractResult.Failure->opened
   is ContractResult.Success->opened.value.use{
    if(it.identity!=command.identity)ContractResult.Failure(ContractError.INVALID_INPUT)
    else when(val committed=writer.commit(it,command)) {
     is ContractResult.Failure->committed
     is ContractResult.Success->if(committed.value.command==command)committed else ContractResult.Failure(ContractError.STORAGE_FAILURE)
    }
   }
  }
 }
}
