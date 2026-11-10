package io.drivegrowth.app.runtime.rental

import io.drivegrowth.app.data.RentalLedger
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.*
import java.util.Collections

/** 공급자가 보존한 방문 명령. 재시도 때 현재 규칙/난수로 다시 만들지 않는다. */
class RentalVisit(
    val source: InputSource, val request: RentalRequest, catalog: VehicleCatalogSnapshot,
    categories: Map<VehicleId, RentalCategory>, hourlyRates: Map<VehicleId, GameMoney>, val terms: RentalTerms,
) {
    val catalog = catalog.copy(vehicles = Collections.unmodifiableList(ArrayList(catalog.vehicles)))
    val categories: Map<VehicleId, RentalCategory> = Collections.unmodifiableMap(LinkedHashMap(categories))
    val hourlyRates: Map<VehicleId, GameMoney> = Collections.unmodifiableMap(LinkedHashMap(hourlyRates))
}

/** 동일 부팅의 보정된 epoch/elapsed 쌍. bootId는 실제 입력 공급자가 발급·대조한다. */
data class RentalClock(val bootId: String, val epoch: EpochMillis, val elapsedMs: Long)
sealed interface RentalMotion {
    data object Driving : RentalMotion
    data class Parked(val originalSince: EpochMillis) : RentalMotion
}

/** from..to 전체가 같은 주행 또는 원래 연속 주차라는 증거. 공백은 Missing이다. */
data class RentalTick(val source: InputSource, val from: RentalClock, val to: RentalClock,
                      val motion: Observation<RentalMotion>)

/** 대여별 확정 경계다. 한 대여의 보류가 다른 대여의 이미 확정된 결과를 취소하지 않는다. */
data class RentalRuntimeSnapshot(
    val pending: List<RentalLedger.Pending>,
    val firstCompleted: CompletedRental?,
    val deferred: Map<RequestId, ContractError> = emptyMap(),
)

internal interface RentalRuntimeLedger {
    fun assign(visit: RentalVisit): ContractResult<OwnedVehicleId?>
    fun pending(): ContractResult<List<RentalLedger.Pending>>
    fun account(id: RequestId, from: EpochMillis, to: EpochMillis, parkedSince: EpochMillis?): ContractResult<EpochMillis>
    fun settle(id: RequestId): ContractResult<RentalReceipt>
    fun finishReturn(id: RequestId, now: EpochMillis): ContractResult<VehicleActivity>
    fun firstCompleted(): ContractResult<CompletedRental?>
}
