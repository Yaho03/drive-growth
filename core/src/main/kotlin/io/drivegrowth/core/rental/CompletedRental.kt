package io.drivegrowth.core.rental

import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Settlement

data class RentalReceipt(
    val settlement: Settlement,
    val accident: Boolean,
    val matchReview: Boolean,
    val washReview: Boolean,
    val facilityBonus: GameMoney,
    val regularCustomer: Boolean?,
)

data class CompletedRental(val rentalId: RequestId, val ownedVehicleId: OwnedVehicleId, val receipt: RentalReceipt)

/** 조회는 지급·열람·차량 상태 변경을 일으키지 않는다. */
interface CompletedRentalSource {
    fun firstCompletedRental(): ContractResult<CompletedRental?>
    fun completedRental(id: RequestId): ContractResult<CompletedRental?>
}
