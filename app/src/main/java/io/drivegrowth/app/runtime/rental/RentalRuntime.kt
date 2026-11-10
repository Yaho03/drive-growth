package io.drivegrowth.app.runtime.rental

import io.drivegrowth.core.contract.*
import java.util.concurrent.Executor

sealed interface RentalRuntimeEvent {
    data class Visit(val requestId: RequestId?, val result: ContractResult<OwnedVehicleId?>) : RentalRuntimeEvent
    data class Update(val result: ContractResult<RentalRuntimeSnapshot>) : RentalRuntimeEvent
}

/**
 * UI 스레드 진입/응답과 worker 원장 실행. 실행기 종료는 생성한 호스트의 책임이다.
 * 한 작업만 수락한다. false이면 미제출이며 호출자가 고정 명령을 보존한다.
 * stop은 대기 작업/늦은 응답을 버리지만 이미 시작한 SQLite 확정을 취소하지 않는다. 재개 시 refresh한다.
 */
class RentalRuntime(private val worker: Executor, private val main: Executor, private val source: RentalRuntimeSource) {
    @Volatile private var generation = 0L
    @Volatile private var running = false
    private var busy = false
    private var listener: ((RentalRuntimeEvent) -> Unit)? = null

    fun start(listener: (RentalRuntimeEvent) -> Unit) {
        generation++
        running = true
        this.listener = listener
    }
    fun stop() {
        generation++
        running = false
        listener = null
    }
    fun visit(input: ContractResult<RentalVisit>): Boolean {
        val id = (input as? ContractResult.Success)?.value?.request?.id
        return submit({ RentalRuntimeEvent.Visit(id, ContractResult.Failure(it)) }) {
            RentalRuntimeEvent.Visit(id, source.visit(input))
        }
    }
    fun tick(input: RentalTick): Boolean = submit({ RentalRuntimeEvent.Update(ContractResult.Failure(it)) }) {
        RentalRuntimeEvent.Update(source.advance(input))
    }
    fun refresh(): Boolean = submit({ RentalRuntimeEvent.Update(ContractResult.Failure(it)) }) {
        RentalRuntimeEvent.Update(source.read())
    }

    private fun submit(failure: (ContractError) -> RentalRuntimeEvent, action: () -> RentalRuntimeEvent): Boolean {
        if (!running || busy) return false
        busy = true
        val started = generation
        try {
            worker.execute {
                val result = if (!running || generation != started) null else try { action() }
                    catch (_: SecurityException) { failure(ContractError.PERMISSION_DENIED) }
                    catch (_: RuntimeException) { failure(ContractError.STORAGE_FAILURE) }
                main.execute {
                    busy = false
                    if (running && generation == started && result != null) listener?.invoke(result)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            busy = false
            listener?.invoke(failure(ContractError.STORAGE_FAILURE))
        }
        return true
    }
}
