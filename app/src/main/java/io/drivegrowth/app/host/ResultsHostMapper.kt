package io.drivegrowth.app.host

import io.drivegrowth.app.data.SettlementHistoryEntry
import io.drivegrowth.app.ui.results.RentalDemandPresentation
import io.drivegrowth.app.ui.results.ResultPresentation
import io.drivegrowth.app.ui.results.ResultsOrigin
import io.drivegrowth.app.ui.results.ResultsScreenState
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.TransactionKind
import java.util.Collections

/** 저장된 당시 정산을 표시한다. 최신 차량/잔액으로 바꾸거나 수익을 재계산하지 않는다. */
class ResultsHostMapper {
    fun map(state: HostQueryState): ResultsScreenState = when (state) {
        HostQueryState.Loading, HostQueryState.Restricted -> ResultsScreenState.Loading
        is HostQueryState.Failure -> if (state.error == ContractError.DATA_UNAVAILABLE)
            ResultsScreenState.Missing("정산 정보를 확인할 수 없어요")
        else ResultsScreenState.Error("정산을 읽지 못했어요 (${state.error})")
        is HostQueryState.Content -> {
            val data = state.snapshot
            val rows = data.unviewedSettlements
            if (rows.map { it.settlementId }.distinct().size != rows.size ||
                rows.any { it.transaction.source != data.source }) {
                ResultsScreenState.Error("정산 ID 또는 출처를 확인할 수 없어요")
            } else {
                val names = data.content.catalog.vehicles.associate { it.vehicleId to it.displayName }
                ResultsScreenState.Content(Collections.unmodifiableList(rows.map {
                    ResultPresentation(it, it.changedVehicle?.vehicleId?.let(names::get))
                }), ResultsOrigin.STORED)
            }
        }
    }
    fun history(read: io.drivegrowth.core.contract.ContractResult<io.drivegrowth.app.data.SettlementHistorySnapshot>): ResultsScreenState = when (read) {
        is io.drivegrowth.core.contract.ContractResult.Failure -> if (read.error == ContractError.DATA_UNAVAILABLE)
            ResultsScreenState.Missing("정산 이력을 확인할 수 없어요") else ResultsScreenState.Error("정산 이력을 읽지 못했어요 (${read.error})")
        is io.drivegrowth.core.contract.ContractResult.Success -> {
            val data = read.value
            val rows = data.entries
            if (rows.map { it.settlement.settlementId }.distinct().size != rows.size ||
                rows.map { it.settlement.transaction.requestId }.distinct().size != rows.size ||
                rows.any { it.settlement.transaction.source != data.identity.source ||
                    (it.rental != null && it.rental.receipt.settlement != it.settlement) ||
                    !rentalBasisMatches(it, data.identity) || !rentalDemandMatches(it, data.identity) })
                ResultsScreenState.Error("정산 이력의 식별·출처·영업 연결을 확인할 수 없어요")
            else ResultsScreenState.Content(Collections.unmodifiableList(rows.map {
                ResultPresentation(it.settlement, it.settlement.changedVehicle?.vehicleId?.let(data.vehicleNames::get),
                    it.viewed, it.rental, it.rentalVehicleId?.let(data.vehicleNames::get), it.rentalBasis,
                    when (val demand = it.rentalDemand) {
                        is Observation.Missing -> demand
                        is Observation.Available -> Observation.Available(RentalDemandPresentation(
                            demand.value.desired.displayName, demand.value.purpose, demand.value.city,
                        ))
                    }, cause = it.cause)
            }), ResultsOrigin.STORED, historyAvailable = true)
        }
    }
    private fun rentalDemandMatches(entry: SettlementHistoryEntry, identity: GameIdentity): Boolean {
        val demand = (entry.rentalDemand as? Observation.Available)?.value ?: return true
        val rental = entry.rental ?: return false
        val transaction = entry.settlement.transaction
        return demand.identity == identity && demand.rental == rental &&
            transaction.kind == TransactionKind.RENTAL &&
            transaction.requestId.value == "rental/settle/" + rental.rentalId.value
    }

    private fun rentalBasisMatches(entry: io.drivegrowth.app.data.SettlementHistoryEntry, identity: GameIdentity): Boolean {
        val basis = (entry.rentalBasis as? io.drivegrowth.core.contract.Observation.Available)?.value ?: return true
        val rental = entry.rental ?: return false
        val settlement = entry.settlement
        return settlement.transaction.kind == io.drivegrowth.core.contract.TransactionKind.RENTAL &&
            settlement.transaction.requestId.value == "rental/settle/" + rental.rentalId.value &&
            basis.rentalId == rental.rentalId && basis.settlementId == settlement.settlementId &&
            basis.profileId == identity.profileId && basis.source == identity.source &&
            basis.ruleVersion == settlement.transaction.ruleVersion
    }
}
