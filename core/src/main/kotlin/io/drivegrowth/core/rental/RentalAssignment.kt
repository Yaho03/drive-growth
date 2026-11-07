package io.drivegrowth.core.rental

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.Garage
import io.drivegrowth.core.progression.VehicleActivity

enum class RentalCategory { SMALL, SEDAN, SUV, VAN, ECO }
enum class RentalMatch(val percent: Int) { EXACT(120), SAME_CATEGORY(80) }

data class RentalRequest(val id: RequestId, val desired: VehicleId, val hours: Int) {
    init { require(hours in setOf(1, 3, 6)) }
}

sealed interface AssignmentPlan {
    data class Candidate(val request: RentalRequest, val vehicle: OwnedVehicle, val match: RentalMatch) : AssignmentPlan
    data object Revisit : AssignmentPlan
}

/** 점유 거래 직전의 후보. 실제 확정에는 동일 revision과 유휴 상태를 다시 검사해야 한다. */
fun Garage.planRental(
    request: RentalRequest,
    vehicles: List<OwnedVehicle>,
    categoryOf: Map<VehicleId, RentalCategory>,
    resting: Set<OwnedVehicleId>,
    activityOf: (OwnedVehicleId) -> ContractResult<VehicleActivity>,
): ContractResult<AssignmentPlan> {
    val desiredCategory = categoryOf[request.desired] ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    val available = when (val result = availableForRental(vehicles, activityOf)) {
        is ContractResult.Failure -> return result
        is ContractResult.Success -> result.value.filterNot { it.ownedVehicleId in resting }
    }
    if (available.any { it.vehicleId !in categoryOf }) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    // 같은 궁합이면 높은 레벨을 우선하고 마지막 동률은 개체 ID로 고정한다.
    val best = available.filter { categoryOf[it.vehicleId] == desiredCategory }
        .minWithOrNull(compareByDescending<OwnedVehicle> { it.vehicleId == request.desired }
            .thenByDescending { it.level }.thenBy { it.ownedVehicleId.value })
        ?: return ContractResult.Success(AssignmentPlan.Revisit)
    val match = if (best.vehicleId == request.desired) RentalMatch.EXACT else RentalMatch.SAME_CATEGORY
    return ContractResult.Success(AssignmentPlan.Candidate(request, best, match))
}

/** 도시·용도에 맞는 방문 후보는 콘텐츠에서 전달한다. 각 항목의 중복 빈도로 수요를 표현한다. */
fun generateRentalRequest(id: RequestId, demand: List<VehicleId>, random: kotlin.random.Random): ContractResult<RentalRequest> {
    if (demand.isEmpty()) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    return ContractResult.Success(RentalRequest(id, demand.random(random), listOf(1, 3, 6).random(random)))
}
