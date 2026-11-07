package io.drivegrowth.app.host

import io.drivegrowth.app.ui.results.ResultPresentation
import io.drivegrowth.app.ui.results.ResultsOrigin
import io.drivegrowth.app.ui.results.ResultsScreenState
import io.drivegrowth.core.contract.ContractError
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
}
