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
    private val inputs: RentalProductInputs, private val random: () -> Random, private val requestId: () -> RequestId) {
    private val planner = RentalVisitPlanner()
    private var unsaved: PreparedRentalVisit? = null
    fun next(): ContractResult<PreparedRentalVisit?> {
        val actual = when (val r = identity()) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        when (val retained = recover(actual)) {
            is ContractResult.Failure -> return retained
            is ContractResult.Success -> if (retained.value != null) return retained
        }
        val cursor = when (val r = journal.cursor(actual)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        val current = when (val r = inputs.read(actual)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        val original = when (val r = planner.prepare(actual, current.catalog, current.definition, cursor,
            current.eligibleThrough, random(), requestId)) {
            is ContractResult.Failure -> return r
            is ContractResult.Success -> r.value ?: return ContractResult.Success(null)
        }
        unsaved = original
        return persist(original, journal::save)
    }

    internal fun recover(actual: GameIdentity, save: (PreparedRentalVisit) -> ContractResult<PreparedRentalVisit> = journal::save):
        ContractResult<PreparedRentalVisit?> {
        val pending = when (val r = journal.pending(actual)) { is ContractResult.Failure -> return r; is ContractResult.Success -> r.value }
        val original = unsaved
        if (original != null && original.identity != actual) return conflict()
        if (pending != null) {
            if (pending.identity != actual || original != null && original != pending) return conflict()
            unsaved = null
            return ContractResult.Success(pending)
        }
        return if (original == null) ContractResult.Success(null) else persist(original, save)
    }

    private fun persist(original: PreparedRentalVisit, save: (PreparedRentalVisit) -> ContractResult<PreparedRentalVisit>) =
        when (val saved = save(original)) {
            is ContractResult.Failure -> saved
            is ContractResult.Success -> if (saved.value != original) conflict() else {
                unsaved = null
                ContractResult.Success(saved.value)
            }
        }

    private fun conflict(): ContractResult<Nothing> = ContractResult.Failure(ContractError.STATE_CONFLICT)
}
