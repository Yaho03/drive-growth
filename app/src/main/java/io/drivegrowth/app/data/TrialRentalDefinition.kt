package io.drivegrowth.app.data

import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.Collections

internal class TrialRentalInputs(
    categories: Map<VehicleId, RentalCategory>,
    basis: Map<VehicleId, String>,
    demand: Map<VehicleId, Int>,
    durations: Map<Int, Int>,
    returns: Map<Int, Int>,
    customers: List<RentalCustomerWeight>,
    val replayLimit: Int,
    val interval: Long,
    val rounding: RoundingMode,
    val demandPolicy: TrialRentalDemandPolicy?,
) {
    val categories=Collections.unmodifiableMap(LinkedHashMap(categories))
    val basis=Collections.unmodifiableMap(LinkedHashMap(basis))
    val demand=Collections.unmodifiableMap(LinkedHashMap(demand))
    val durations=Collections.unmodifiableMap(LinkedHashMap(durations))
    val returns=Collections.unmodifiableMap(LinkedHashMap(returns))
    val customers=Collections.unmodifiableList(ArrayList(customers))
}
internal data class TrialRentalDemandPolicy(
    val version: ContentVersion,
    val intervalsByLevel: Map<Int, Long>,
    val cityWeightsByPurpose: Map<RentalCategory, Map<RentalCity, Int>>,
)

/** 현재 호스트와 기존 방문 fixture가 쓰는 정의. 가게 레벨을 알 수 없으므로 레벨 정책을 적용하지 않는다. */
fun TrialDefinition.rentalVisitDefinition(): ContractResult<RentalVisitDefinition> =
    ContractResult.Success(rentalVisitDefinition(version, rental.interval, rental.demand))

/** 동일한 가게 레벨·콘텐츠를 읽은 신규 방문 계획과 저장 검증에서 사용하는 정의다. */
fun TrialDefinition.rentalVisitDefinition(
    content: GameContentSnapshot,
    shopLevel: Int,
): ContractResult<RentalVisitDefinition> {
    val policy = rental.demandPolicy ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    val interval = policy.intervalsByLevel[shopLevel] ?: return ContractResult.Failure(ContractError.INVALID_INPUT)
    val ids = content.vehicles.map { it.vehicleId }
    val catalogIds = content.catalog.vehicles.map { it.vehicleId }
    if (ids.size != rental.demand.size || ids.toSet() != rental.demand.keys ||
        catalogIds.size != rental.demand.size || catalogIds.toSet() != rental.demand.keys)
        return ContractResult.Failure(ContractError.INVALID_INPUT)
    val unlocked = content.unlockedAt(shopLevel).mapTo(hashSetOf()) { it.vehicleId }
    if (unlocked.isEmpty()) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    val demand = rental.demand.mapValues { (id, weight) -> if (id in unlocked) weight else 0 }
    return ContractResult.Success(rentalVisitDefinition(policy.version, interval, demand, policy.cityWeightsByPurpose))
}

private fun TrialDefinition.rentalVisitDefinition(
    visitVersion: ContentVersion,
    interval: Long,
    demand: Map<VehicleId, Int>,
    cityWeights: Map<RentalCategory, Map<RentalCity, Int>> = emptyMap(),
) = RentalVisitDefinition(visitVersion, rules, trial, interval, rental.categories, rentalRates, demand,
    rental.durations, rental.returns, rental.customers, fuelBonusPerRental, rental.rounding, cityWeights)
fun TrialDefinition.rentalReplayLimit():ContractResult<Int> = ContractResult.Success(rental.replayLimit)
fun TrialDefinition.rentalCategoryBasis():Map<VehicleId,String> = rental.basis
