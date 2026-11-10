package io.drivegrowth.app.host.garage

import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
import java.util.UUID
import java.util.concurrent.Executor

sealed interface GarageCommandState {
    data object Idle : GarageCommandState
    data object Loading : GarageCommandState
    data class Materials(val value: GaragePreparation.Materials) : GarageCommandState
    data class Review(val pending: GaragePending, val canConfirm: Boolean, val recovering: Boolean = false) : GarageCommandState
    data class Running(val pending: GaragePending) : GarageCommandState
    data class Confirmed(val pending: GaragePending, val receipt: GarageReceipt) : GarageCommandState
    data class Rejected(val error: ContractError) : GarageCommandState
    data class Failure(val error: ContractError, val pending: GaragePending? = null, val cancelError: ContractError? = null) : GarageCommandState
}

/** UI는 상태/입력만 다룬다. source/journal은 worker, 응답은 main. checkpoint 성공 전 commit하지 않는다. */
class GarageCommandController(
    private val source: GarageCommandSource,
    private val journal: GarageCommandJournal,
    private val worker: Executor,
    private val main: Executor,
    private val nextId: () -> RequestId = { RequestId(UUID.randomUUID().toString()) },
    private val onState: (GarageCommandState) -> Unit,
) {
    var state: GarageCommandState = GarageCommandState.Idle; private set
    @Volatile private var allowed = false
    @Volatile private var released = false
    private var busy = false
    private var generation = 0L
    private var persisted = false
    fun setControlsEnabled(value: Boolean) { allowed = value }
    fun release() { released = true; allowed = false; generation++ }
    private fun show(value: GarageCommandState) { if (!released) { state = value; onState(value) } }
    fun recover() {
        if (released || busy || !allowed) return
        busy = true; val serial = ++generation
        worker.execute {
            val result = safely { journal.load() }
            main.execute {
                if (released || serial != generation) return@execute
                busy = false
                when (result) {
                    is ContractResult.Success -> { persisted = result.value != null; show(result.value?.let { GarageCommandState.Review(it, true, recovering = true) } ?: GarageCommandState.Idle) }
                    is ContractResult.Failure -> show(GarageCommandState.Failure(result.error))
                }
            }
        }
    }
    fun open(request: GarageEvent.RequestAction, material: VehicleRef? = null) {
        if (released || busy || !allowed) return
        if (persisted) { recover(); return }
        busy = true; val serial = ++generation; show(GarageCommandState.Loading)
        worker.execute {
            val saved = safely { journal.load() }
            val result = if (!allowed) ContractResult.Failure(ContractError.UX_RESTRICTED)
                else if (saved is ContractResult.Failure) saved
                else if (saved is ContractResult.Success && saved.value != null) ContractResult.Success(GaragePreparation.Ready(saved.value!!))
                else safely { source.prepare(request, nextId(), material) }
            main.execute {
                if (released || serial != generation) return@execute
                busy = false; persisted = saved is ContractResult.Success && saved.value != null
                when (result) {
                    is ContractResult.Failure -> show(GarageCommandState.Failure(result.error))
                    is ContractResult.Success -> when (val prepared = result.value) {
                        is GaragePreparation.Materials -> show(GarageCommandState.Materials(prepared))
                        is GaragePreparation.Ready -> show(GarageCommandState.Review(prepared.pending, saved is ContractResult.Success, recovering = persisted))
                    }
                }
            }
        }
    }
    fun chooseMaterial(ref: VehicleRef) {
        val choices = (state as? GarageCommandState.Materials)?.value ?: return
        if (choices.candidates.none { it.ownedVehicleId == ref.ownedVehicleId && it.revision == ref.revision }) return
        open(choices.request, ref)
    }
    fun confirm() {
        val review = state as? GarageCommandState.Review ?: return
        if (review.canConfirm) execute(review.pending)
    }
    fun retry() { (state as? GarageCommandState.Failure)?.pending?.let(::execute) }
    private fun execute(pending: GaragePending) {
        if (released || busy || !allowed) return
        busy = true; val serial = ++generation; show(GarageCommandState.Running(pending))
        worker.execute {
            val result = if (!allowed || released) ContractResult.Failure(ContractError.UX_RESTRICTED)
                else when (val saved = safely { journal.save(pending) }) {
                    is ContractResult.Failure -> saved
                    is ContractResult.Success -> if (!allowed || released) ContractResult.Failure(ContractError.UX_RESTRICTED)
                        else safely { source.commit(pending.command) }
                }
            main.execute {
                if (released || serial != generation) return@execute
                busy = false
                // 실패 응답도 이미 확정됐을 수 있다. 명령은 자동 삭제/재준비하지 않는다.
                persisted = true
                when (result) {
                    is ContractResult.Success -> if (result.value.matches(pending.command)) show(GarageCommandState.Confirmed(pending, result.value))
                        else show(GarageCommandState.Failure(ContractError.STORAGE_FAILURE, pending))
                    is ContractResult.Failure -> show(GarageCommandState.Failure(result.error, pending))
                }
            }
        }
    }
    fun acknowledge() {
        val confirmed = state as? GarageCommandState.Confirmed ?: return
        if (released || busy || !allowed) return
        busy = true
        worker.execute {
            val result = safely { journal.clear(confirmed.pending) }
            main.execute {
                if (released) return@execute
                busy = false
                if (result is ContractResult.Success) { persisted = false; show(GarageCommandState.Idle) }
                else show(GarageCommandState.Failure((result as ContractResult.Failure).error, confirmed.pending))
            }
        }
    }
    /** 거절 오류만 보고 지우지 않는다. 공용 저장소가 SAVED/미실행을 확인한 명시 취소 성공만 해소한다. */
    fun cancelPending() {
        val failed = state as? GarageCommandState.Failure ?: return
        val pending = failed.pending ?: return
        if (released || busy || !allowed) return
        busy = true
        worker.execute {
            val result = safely { journal.cancelUnstarted(pending) }
            main.execute {
                if (released) return@execute
                busy = false
                when(result) {
                    is ContractResult.Success -> { persisted = false; show(GarageCommandState.Rejected(failed.error)) }
                    is ContractResult.Failure -> show(failed.copy(cancelError = result.error))
                }
            }
        }
    }
    fun cancel(): Boolean {
        if (!allowed || state is GarageCommandState.Running || state is GarageCommandState.Confirmed) return false
        generation++; busy = false; show(GarageCommandState.Idle)
        return true // 보관한 원래 명령은 지우지 않고 다음 recover에서 같은 명령을 확인한다.
    }
}
private fun <T> safely(action: () -> ContractResult<T>): ContractResult<T> = try { action() }
    catch (_: RuntimeException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }

/** 원래 명령과 다른 응답을 성공 표시로 사용하지 않는다. */
private fun GarageReceipt.matches(command: GarageCommand): Boolean = when {
    this is GarageReceipt.Material && command is GarageCommand.Material -> value.command == command.value
    this is GarageReceipt.Repair && command is GarageCommand.Repair -> value.command == command.value
    this is GarageReceipt.Single && command is GarageCommand.Single -> {
        val c = command.value; val tx = value.transaction; val write = c.vehicle as? VehicleWrite.Update
        val vehicle = value.changedVehicle
        tx.requestId == c.requestId && tx.kind == c.kind && tx.source == c.source && tx.ruleVersion == c.ruleVersion && tx.money == c.money &&
            write != null && vehicle != null && vehicle.ownedVehicleId == write.ownedVehicleId &&
            vehicle.revision.value.toLong() == write.expectedRevision.value.toLong() + 1 && vehicle.level == write.level &&
            vehicle.levelProgressPercent == write.levelProgressPercent && vehicle.lifecycle == write.lifecycle
    }
    else -> false
}
