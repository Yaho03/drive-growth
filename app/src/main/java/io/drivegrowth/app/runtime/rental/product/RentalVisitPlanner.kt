package io.drivegrowth.app.runtime.rental.product

import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.app.runtime.rental.RentalVisit
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import java.math.RoundingMode
import java.util.Collections
import kotlin.random.Random

/** 중앙 정의 공급자가 확정한 시험 입력. 고객 분포나 유형을 planner가 기본값으로 만들지 않는다. */
data class RentalCustomerWeight(val customer: SimulatedCustomer, val regularCustomer: Boolean, val weight: Int)
enum class RentalCity { URBAN, SUBURBAN, TRANSPORT_HUB }
internal data class RentalDemandSelection(val desired: VehicleId, val purpose: RentalCategory, val city: RentalCity?)
/** 준비 방문과 그 방문에 적용한 수요 선택을 함께 반환한다. */
internal data class RentalVisitPlan(val prepared: PreparedRentalVisit, val demand: RentalDemandSelection)

class RentalVisitDefinition(val version: ContentVersion, val rules: RuleVersion, val trial: Boolean, val intervalMillis: Long,
    categories: Map<VehicleId, RentalCategory>, rates: Map<VehicleId, GameMoney>,
    demand: Map<VehicleId, Int>, durations: Map<Int, Int>, returns: Map<Int, Int>,
    customers: List<RentalCustomerWeight>, val fuelBonus: GameMoney, val rounding: RoundingMode,
    cityWeights: Map<RentalCategory, Map<RentalCity, Int>> = emptyMap()) {
    val categories: Map<VehicleId,RentalCategory> = Collections.unmodifiableMap(LinkedHashMap(categories))
    val rates: Map<VehicleId,GameMoney> = Collections.unmodifiableMap(LinkedHashMap(rates))
    val demand: Map<VehicleId,Int> = Collections.unmodifiableMap(LinkedHashMap(demand))
    val durations: Map<Int,Int> = Collections.unmodifiableMap(LinkedHashMap(durations))
    val returns: Map<Int,Int> = Collections.unmodifiableMap(LinkedHashMap(returns))
    val customers: List<RentalCustomerWeight> = Collections.unmodifiableList(ArrayList(customers))
    internal val cityWeights: Map<RentalCategory, Map<RentalCity, Int>> =
        Collections.unmodifiableMap(cityWeights.mapValues { (_, weights) ->
            Collections.unmodifiableMap(LinkedHashMap(weights))
        })
}

/** 방문 한 건만 계획한다. 저장·배정·수익을 실행하지 않으며 원명령 복구가 이 함수보다 먼저다. */
class RentalVisitPlanner {
    fun prepare(identity: GameIdentity, catalog: VehicleCatalogSnapshot, definition: RentalVisitDefinition,
        cursor: RentalVisitCursor, eligibleThrough: RentalClock, random: Random,
        requestId: () -> RequestId): ContractResult<PreparedRentalVisit?> =
        when (val result = prepareWithDemand(identity, catalog, definition, cursor, eligibleThrough, random, requestId)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> ContractResult.Success(result.value?.prepared)
        }

    internal fun prepareWithDemand(identity: GameIdentity, catalog: VehicleCatalogSnapshot, definition: RentalVisitDefinition,
        cursor: RentalVisitCursor, eligibleThrough: RentalClock, random: Random,
        requestId: () -> RequestId): ContractResult<RentalVisitPlan?> {
        val frozenCatalog = catalog.copy(vehicles = catalog.vehicles.toList())
        val ids = frozenCatalog.vehicles.map { it.vehicleId }.toSet()
        if (ids.isEmpty() || ids.size != frozenCatalog.vehicles.size || definition.intervalMillis <= 0 ||
            definition.categories.keys != ids || definition.rates.keys != ids || definition.rates.values.any { it.units <= 0 } ||
            definition.demand.keys != ids || !weights(definition.demand.values) ||
            (definition.cityWeights.isNotEmpty() && !validCities(definition.cityWeights)) ||
            definition.durations.keys != setOf(1,3,6) || !weights(definition.durations.values) ||
            definition.returns.keys != setOf(1,3,6) || definition.returns.values.any { it !in setOf(15,30,60) } ||
            !weights(definition.customers.map { it.weight }) || cursor.sequence < 0 || !valid(cursor.nextAt) || !valid(eligibleThrough))
            return ContractResult.Failure(ContractError.INVALID_INPUT)
        val next = cursor.nextAt
        if (next.bootId != eligibleThrough.bootId) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val epochDelta = eligibleThrough.epoch.value - next.epoch.value
        val elapsedDelta = eligibleThrough.elapsedMs - next.elapsedMs
        if (epochDelta != elapsedDelta) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (epochDelta < 0) return ContractResult.Success(null)
        if (cursor.revision.value == Int.MAX_VALUE || cursor.sequence == Long.MAX_VALUE)
            return ContractResult.Failure(ContractError.INVALID_INPUT)
        val following = try { RentalClock(next.bootId, EpochMillis(Math.addExact(next.epoch.value, definition.intervalMillis)),
            Math.addExact(next.elapsedMs, definition.intervalMillis)) }
        catch (_: ArithmeticException) { return ContractResult.Failure(ContractError.INVALID_INPUT) }
        // RentalTerms도 종료/반납 덧셈의 안전 범위를 검증한다. 난수/ID를 쓰기 전에 거절한다.
        if (next.epoch.value > Long.MAX_VALUE - 7 * 3_600_000L) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val desired = choose(definition.demand.entries.toList(), { it.value }, random).key
        val hours = choose(definition.durations.entries.toList(), { it.value }, random).key
        val customer = choose(definition.customers, { it.weight }, random)
        val visit = RentalVisit(identity.source, RentalRequest(requestId(), desired, hours), frozenCatalog,
            definition.categories, definition.rates, RentalTerms(definition.rules, customer.customer, random.nextInt(100),
                next.epoch, definition.returns.getValue(hours), definition.fuelBonus, definition.rounding, customer.regularCustomer))
        // 기존 차종·기간·고객·사고 난수 순서를 유지한 뒤 가상 도시를 고른다.
        val purpose = definition.categories.getValue(desired)
        val city = definition.cityWeights[purpose]?.let { weights ->
            choose(weights.entries.toList(), { it.value }, random).key
        }
        val prepared = PreparedRentalVisit(identity, definition.version, definition.trial, cursor,
            RentalVisitCursor(Revision(cursor.revision.value + 1), cursor.sequence + 1, following), visit, city)
        return ContractResult.Success(RentalVisitPlan(prepared, RentalDemandSelection(desired, purpose, city)))
    }

    private fun validCities(cityWeights: Map<RentalCategory, Map<RentalCity, Int>>): Boolean =
        cityWeights.keys == RentalCategory.entries.toSet() && cityWeights.values.all { weights ->
            weights.keys == RentalCity.entries.toSet() && weights.values.all { it > 0 }
        }

    private fun valid(clock: RentalClock) = clock.bootId.isNotBlank() && clock.epoch.value >= 0 && clock.elapsedMs >= 0
    private fun weights(values: Collection<Int>) = values.isNotEmpty() && values.all { it >= 0 } && values.any { it > 0 }
    private fun <T> choose(values: List<T>, weight: (T) -> Int, random: Random): T {
        var draw = random.nextLong(values.sumOf { weight(it).toLong() })
        for (value in values) { draw -= weight(value); if (draw < 0) return value }
        error("검증된 가중치 범위")
    }
}
