package io.drivegrowth.app.runtime.rental

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity
import java.util.Collections

/** worker에서 기존 원장만 호출한다. 고객 생성·시간 보정·수익 산식은 소유하지 않는다. */
internal class RentalProcessor(private val source: InputSource, private val ledger: RentalRuntimeLedger) {
    fun visit(input: ContractResult<RentalVisit>): ContractResult<OwnedVehicleId?> {
        val visit = when (input) {
            is ContractResult.Failure -> return input
            is ContractResult.Success -> input.value
        }
        if (visit.source != source) return ContractResult.Failure(ContractError.INVALID_INPUT)
        return ledger.assign(visit)
    }

    fun read(): ContractResult<RentalRuntimeSnapshot> = snapshot(emptyMap())

    fun advance(tick: RentalTick, now: RentalClock): ContractResult<RentalRuntimeSnapshot> {
        if (tick.source != source || !valid(tick.from) || !valid(tick.to) || !valid(now))
            return ContractResult.Failure(ContractError.INVALID_INPUT)
        if (tick.to.epoch.value > now.epoch.value || tick.to.elapsedMs > now.elapsedMs)
            return ContractResult.Failure(ContractError.INVALID_INPUT)
        if (!continuous(tick.from, tick.to) || !continuous(tick.to, now))
            return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val parked = ((tick.motion as? Observation.Available)?.value as? RentalMotion.Parked)?.originalSince
        if (parked != null && parked.value !in 0..tick.from.epoch.value)
            return ContractResult.Failure(ContractError.INVALID_INPUT)
        val pending = when (val read = ledger.pending()) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        val deferred = linkedMapOf<RequestId, ContractError>()
        for (rental in pending) {
            if (rental.phase == VehicleActivity.RETURNING) {
                if (tick.to.epoch.value >= rental.returnEnd.value) {
                    val returned = ledger.finishReturn(rental.id, tick.to.epoch)
                    if (returned is ContractResult.Failure) deferred[rental.id] = returned.error
                }
                continue
            }
            var cursor = rental.cursor
            if (cursor.value < rental.end.value && tick.to.epoch.value > cursor.value) {
                val missing = tick.motion as? Observation.Missing
                if (missing != null || tick.from.epoch.value > cursor.value) {
                    deferred[rental.id] = if (missing?.reason == MissingReason.PERMISSION_DENIED)
                        ContractError.PERMISSION_DENIED else ContractError.DATA_UNAVAILABLE
                    continue
                }
                // 연속 주차의 새 기준을 만들지 않는다. 확인된 주행은 기존 기준을 종료할 수 있다.
                if (parked != null && rental.parkedSince != null && parked != rental.parkedSince) {
                    deferred[rental.id] = ContractError.STATE_CONFLICT
                    continue
                }
                when (val accounted = ledger.account(rental.id, tick.from.epoch, tick.to.epoch, parked)) {
                    is ContractResult.Failure -> { deferred[rental.id] = accounted.error; continue }
                    is ContractResult.Success -> cursor = accounted.value
                }
            }
            if (cursor.value == rental.end.value) {
                when (val settled = ledger.settle(rental.id)) {
                    is ContractResult.Failure -> deferred[rental.id] = settled.error
                    is ContractResult.Success -> if (tick.to.epoch.value >= rental.returnEnd.value) {
                        val returned = ledger.finishReturn(rental.id, tick.to.epoch)
                        if (returned is ContractResult.Failure) deferred[rental.id] = returned.error
                    }
                }
            }
        }
        return snapshot(deferred)
    }

    private fun snapshot(deferred: Map<RequestId, ContractError>): ContractResult<RentalRuntimeSnapshot> {
        val pending = when (val read = ledger.pending()) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        val completed = when (val read = ledger.firstCompleted()) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value
        }
        return ContractResult.Success(RentalRuntimeSnapshot(
            Collections.unmodifiableList(ArrayList(pending)), completed,
            Collections.unmodifiableMap(LinkedHashMap(deferred))))
    }

    private fun valid(c: RentalClock) = c.bootId.isNotBlank() && c.epoch.value >= 0 && c.elapsedMs >= 0
    private fun continuous(a: RentalClock, b: RentalClock) = a.bootId == b.bootId &&
        b.epoch.value >= a.epoch.value && b.elapsedMs >= a.elapsedMs &&
        b.epoch.value - a.epoch.value == b.elapsedMs - a.elapsedMs
}
