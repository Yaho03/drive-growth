package io.drivegrowth.app.runtime.rental

import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.data.RentalLedger
import io.drivegrowth.core.contract.*

/** 공급자는 같은 프로필·출처의 기존 원장을 열고, 호출자는 worker에서만 이 API를 사용한다. */
interface RentalRuntimeSource {
    fun visit(input: ContractResult<RentalVisit>): ContractResult<OwnedVehicleId?>
    fun advance(tick: RentalTick): ContractResult<RentalRuntimeSnapshot>
    fun read(): ContractResult<RentalRuntimeSnapshot>
}

class SqliteRentalRuntimeSource(
    private val source: InputSource,
    private val open: () -> SqliteGameChangeStore,
    private val clock: () -> RentalClock,
) : RentalRuntimeSource {
    override fun visit(input: ContractResult<RentalVisit>): ContractResult<OwnedVehicleId?> {
        if (input is ContractResult.Failure) return input
        val visit = (input as ContractResult.Success).value
        if (visit.source != source) return ContractResult.Failure(ContractError.INVALID_INPUT)
        return guarded {
            val now = clock()
            if (now.bootId.isBlank() || now.epoch.value < 0 || now.elapsedMs < 0 || visit.terms.start.value > now.epoch.value)
                ContractResult.Failure(ContractError.INVALID_INPUT)
            else withProcessor { it.visit(input) }
        }
    }

    override fun advance(tick: RentalTick): ContractResult<RentalRuntimeSnapshot> = advance(tick, null)

    internal fun advance(tick: RentalTick, onNewReturn: ((RequestId, RentalLedger.ReturnCommit) -> Unit)?):
        ContractResult<RentalRuntimeSnapshot> = guarded {
        val now = clock()
        withProcessor { it.advance(tick, now, onNewReturn) }
    }
    override fun read(): ContractResult<RentalRuntimeSnapshot> = guarded { withProcessor { it.read() } }

    private fun <T> withProcessor(action: (RentalProcessor) -> ContractResult<T>): ContractResult<T> =
        open().use { store ->
            if (store.source != source) ContractResult.Failure(ContractError.INVALID_INPUT)
            else action(RentalProcessor(source, SqliteRentalOperations(store)))
        }

    private fun <T> guarded(action: () -> ContractResult<T>): ContractResult<T> = try { action() }
        catch (_: SecurityException) { ContractResult.Failure(ContractError.PERMISSION_DENIED) }
        catch (_: RuntimeException) { ContractResult.Failure(ContractError.STORAGE_FAILURE) }
}

/** 같은 기존 store.rental 호출만 묶는다. 금액·활동·재생은 RentalLedger의 책임이다. */
private class SqliteRentalOperations(store: SqliteGameChangeStore) : RentalRuntimeLedger {
    private val ledger = store.rental
    override fun assign(visit: RentalVisit) = ledger.assign(visit.request, visit.catalog, visit.categories, visit.hourlyRates, visit.terms)
    override fun pending() = ledger.pending()
    override fun account(id: RequestId, from: EpochMillis, to: EpochMillis, parkedSince: EpochMillis?,
        unobservedSince: EpochMillis?) = ledger.accountObserved(id, from, to, parkedSince, unobservedSince)
    override fun settle(id: RequestId) = ledger.settle(id)
    override fun finishReturn(id: RequestId, now: EpochMillis) = ledger.finishReturn(id, now)
    override fun finishReturnWithCommit(id: RequestId, now: EpochMillis) = ledger.finishReturnWithCommit(id, now)
    override fun firstCompleted() = ledger.firstCompletedRental()
}
