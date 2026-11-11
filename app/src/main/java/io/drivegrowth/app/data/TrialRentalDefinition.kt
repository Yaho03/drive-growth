package io.drivegrowth.app.data

import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.Collections

internal class TrialRentalInputs(categories:Map<VehicleId,RentalCategory>,basis:Map<VehicleId,String>,demand:Map<VehicleId,Int>,durations:Map<Int,Int>,returns:Map<Int,Int>,customers:List<RentalCustomerWeight>,val replayLimit:Int,val interval:Long,val rounding:RoundingMode) {
    val categories=Collections.unmodifiableMap(LinkedHashMap(categories))
    val basis=Collections.unmodifiableMap(LinkedHashMap(basis))
    val demand=Collections.unmodifiableMap(LinkedHashMap(demand))
    val durations=Collections.unmodifiableMap(LinkedHashMap(durations))
    val returns=Collections.unmodifiableMap(LinkedHashMap(returns))
    val customers=Collections.unmodifiableList(ArrayList(customers))
}
/** PRD의 차종별 대여 유형 하나를 중앙 시험 정의에서 공급한다. 실차 자료는 분류 근거로만 사용한다. */
fun TrialDefinition.rentalVisitDefinition():ContractResult<RentalVisitDefinition> = ContractResult.Success(RentalVisitDefinition(version,rules,trial,rental.interval,rental.categories,rentalRates,rental.demand,rental.durations,rental.returns,rental.customers,fuelBonusPerRental,rental.rounding))
fun TrialDefinition.rentalReplayLimit():ContractResult<Int> = ContractResult.Success(rental.replayLimit)
fun TrialDefinition.rentalCategoryBasis():Map<VehicleId,String> = rental.basis
