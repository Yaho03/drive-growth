package io.drivegrowth.app.runtime.rental

import io.drivegrowth.app.data.RentalLedger
import io.drivegrowth.app.data.RentalTerms
import io.drivegrowth.app.host.GameIdentity
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

/** from..to는 수락한 연속 구간이다. unobservedSince..from은 같은 수신기의 확인된 앞 공백이다. */
data class RentalTick(val source: InputSource, val from: RentalClock, val to: RentalClock,
                      val motion: Observation<RentalMotion>, val unobservedSince: RentalClock? = null)

enum class RentalMapDirection { DEPARTURE, RETURN }

/** 현재 관측의 확정 쓰기에서 만들어진 표시 전용 값. 원장이나 복원 상태에 저장하지 않는다. */
data class RentalMapEvent(val identity: GameIdentity, val rentalId: RequestId, val vehicle: OwnedVehicleId,
    val direction: RentalMapDirection, val activity: VehicleActivity)

/** 대여별 확정 경계다. 한 대여의 보류가 다른 대여의 이미 확정된 결과를 취소하지 않는다. */
data class RentalRuntimeSnapshot(
    val pending: List<RentalLedger.Pending>,
    val firstCompleted: CompletedRental?,
    val deferred: Map<RequestId, ContractError> = emptyMap(),
    val mapEvents: List<RentalMapEvent> = emptyList(),
)

internal interface RentalRuntimeLedger {
    fun assign(visit: RentalVisit): ContractResult<OwnedVehicleId?>
    fun pending(): ContractResult<List<RentalLedger.Pending>>
    fun account(id: RequestId, from: EpochMillis, to: EpochMillis, parkedSince: EpochMillis?,
        unobservedSince: EpochMillis?): ContractResult<EpochMillis>
    fun settle(id: RequestId): ContractResult<RentalReceipt>
    fun finishReturn(id: RequestId, now: EpochMillis): ContractResult<VehicleActivity>
    fun finishReturnWithCommit(id: RequestId, now: EpochMillis): ContractResult<RentalLedger.ReturnCommit> =
        when (val result = finishReturn(id, now)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> ContractResult.Success(RentalLedger.ReturnCommit(result.value, null))
        }
    fun firstCompleted(): ContractResult<CompletedRental?>
}
