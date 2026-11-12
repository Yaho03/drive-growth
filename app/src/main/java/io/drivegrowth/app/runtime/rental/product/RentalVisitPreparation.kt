package io.drivegrowth.app.runtime.rental.product

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.RentalClock
import io.drivegrowth.core.contract.*
import kotlin.random.Random

data class RentalPreparationInputs(val catalog: VehicleCatalogSnapshot, val definition: RentalVisitDefinition,
    val eligibleThrough: RentalClock)
fun interface RentalProductInputs { fun read(identity: GameIdentity): ContractResult<RentalPreparationInputs> }

/** 직렬 worker 전용. durable 원문 복구가 현재 정의/clock/난수보다 먼저다. 금융 제출은 하지 않는다. */
class RentalVisitPreparation(private val identity: () -> ContractResult<GameIdentity>, private val journal: RentalProductJournal,
    private val inputs: RentalProductInputs, private val random: (RentalPreparationInputs) -> Random,
    private val requestId: () -> RequestId,
    private val saveDraft: (PreparedRentalVisit, RentalPreparationInputs) -> ContractResult<PreparedRentalVisit> =
        { visit, _ -> journal.save(visit) }) {
    private val planner = RentalVisitPlanner()
    private data class Draft(val visit: PreparedRentalVisit, val authority: RentalPreparationInputs)
    private var unsaved: Draft? = null
    fun next(): ContractResult<PreparedRentalVisit?> {
        val actual = when (val r = identity()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        when (val retained = recover(actual)) {
            is ContractResult.Failure -> return retained
            is ContractResult.Success -> if (retained.value != null) return retained
        }
        val cursor = when (val r = journal.cursor(actual)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        val current = when (val r = inputs.read(actual)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        val visit = when (val r = planner.prepare(actual, current.catalog, current.definition, cursor,
            current.eligibleThrough, random(current), requestId)) {
            is ContractResult.Failure -> return r
            is ContractResult.Success -> r.value ?: return ContractResult.Success(null)
        }
        val draft = Draft(visit, current)
        unsaved = draft
        return persist(draft, saveDraft)
    }

    internal fun recover(actual: GameIdentity,
        save: (PreparedRentalVisit, RentalPreparationInputs) -> ContractResult<PreparedRentalVisit> = saveDraft
    ): ContractResult<PreparedRentalVisit?> {
        val pending = when (val r = journal.pending(actual)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        val original = unsaved
        if (original != null && original.visit.identity != actual) return conflict()
        if (pending != null) {
            if (pending.identity != actual || original != null && original.visit != pending) return conflict()
            unsaved = null
            return ContractResult.Success(pending)
        }
        return if (original == null) ContractResult.Success(null) else persist(original, save)
    }

    private fun persist(original: Draft,
        save: (PreparedRentalVisit, RentalPreparationInputs) -> ContractResult<PreparedRentalVisit>) =
        when (val saved = save(original.visit, original.authority)) {
            is ContractResult.Failure -> saved
            is ContractResult.Success -> if (saved.value != original.visit) conflict() else {
                unsaved = null
                ContractResult.Success(saved.value)
            }
        }

    private fun conflict(): ContractResult<Nothing> = ContractResult.Failure(ContractError.STATE_CONFLICT)
}
