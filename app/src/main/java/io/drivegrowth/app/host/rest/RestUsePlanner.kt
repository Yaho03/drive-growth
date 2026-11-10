package io.drivegrowth.app.host.rest

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*

data class RestUseSlice(val span:RestObservedSpan,val boostedMs:Long)
data class RestUsePlan(val afterStop:Long?,val slices:List<RestUseSlice>,val observedMs:Long,val remainingMs:Long)
class RestUsePlanner(private val policy:RestEvidencePolicy) {
 fun plan(identity:GameIdentity,before:RestBonusState,spans:List<RestObservedSpan>):ContractResult<RestUsePlan> {
  if(before.lastStopNumber?.let{it<0}==true)return ContractResult.Failure(ContractError.INVALID_INPUT)
  val observed=when(val checked=RestEvidenceValidator(policy).drivingMsForUse(identity,spans)) {
   is ContractResult.Failure->return checked
   is ContractResult.Success->checked.value
  }
  var remaining=before.remainingMs
  val slices=spans.map{span->
   val boost=minOf(remaining,span.to.receivedElapsedMs-span.from.receivedElapsedMs)
   remaining-=boost;RestUseSlice(span,boost)
  }
  return ContractResult.Success(RestUsePlan(before.lastStopNumber,java.util.Collections.unmodifiableList(slices),observed,remaining))
 }
}
