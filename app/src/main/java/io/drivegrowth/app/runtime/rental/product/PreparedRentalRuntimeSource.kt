package io.drivegrowth.app.runtime.rental.product

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.core.contract.*

/** 기존 RentalRuntime.visit/advance/read의 소비 어댑터. 배정은 E의 원문 저널, 관측 정산은 기존 source다. */
class PreparedRentalRuntimeSource(private val identity: () -> ContractResult<GameIdentity>,
    private val journal: RentalProductJournal, private val observed: RentalRuntimeSource) : RentalRuntimeSource {
    override fun visit(input: ContractResult<RentalVisit>): ContractResult<OwnedVehicleId?> {
        val visit = when (input) { is ContractResult.Failure -> return input; is ContractResult.Success -> input.value }
        val actual = when (val r = identity()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        if (visit.source != actual.source) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val original = when (val r = journal.pending(actual)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
            ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        if (original.identity != actual || original.copy(visit = visit) != original)
            return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val result = when (val r = journal.execute(original)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r }
        return when (val finished = journal.finish(original)) {
            is ContractResult.Failure -> finished // 원결과가 불명이면 새 visit으로 대체하지 않고 같은 pending 복구.
            is ContractResult.Success -> result
        }
    }
    override fun advance(tick: RentalTick): ContractResult<RentalRuntimeSnapshot> {
        val actual = when (val r = identity()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        if (tick.source != actual.source) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        return observed.advance(tick)
    }
    override fun read(): ContractResult<RentalRuntimeSnapshot> = when (val r = identity()) {
        is ContractResult.Failure -> r
        is ContractResult.Success -> observed.read()
    }
}
