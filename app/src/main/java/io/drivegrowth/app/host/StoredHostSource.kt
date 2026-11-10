package io.drivegrowth.app.host

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.CollectionState
import io.drivegrowth.core.garage.Garage
import io.drivegrowth.core.garage.VehicleHistoryStore
import io.drivegrowth.core.progression.VehicleActivity
import java.util.Collections

/** worker의 읽기 진입점. 개별 조회를 원자적인 일괄 조회로 취급하지 않는다. databaseName은 격리 시험용이다. */
internal class StoredHostSource(context: Context, private val databaseName: String? = null, private val sessions: GameSessionFactory? = null) {
    private val context = context.applicationContext

    private fun openStore() = if (databaseName == null) SqliteGameChangeStore(context, InputSource.SIMULATION)
        else SqliteGameChangeStore(context, InputSource.SIMULATION, databaseName)

    private fun <T> withStore(action: (SqliteGameChangeStore, InputSource) -> ContractResult<T>): ContractResult<T> {
        val factory = sessions ?: return openStore().use { action(it, InputSource.SIMULATION) }
        return when (val opened = factory.open()) {
            is ContractResult.Failure -> opened
            is ContractResult.Success -> opened.value.use { action(it.store, it.identity.source) }
        }
    }

    fun read(): ContractResult<HostSnapshot> {
        var operation = "content"
        return try {
            val content = (AssetGameContent(context.assets).load() as? ContentLoad.Loaded)?.snapshot
                ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            operation = "ledger-read"
            withStore { store, source -> readStoredHostSnapshot(content, source, LedgerHostReads(store)) }
        } catch (error: RuntimeException) {
            failure(operation, error)
        }
    }

    fun markViewed(id: SettlementId): ContractResult<Unit> = try {
        withStore { store, _ -> store.markViewed(id) }
    } catch (error: RuntimeException) {
        failure("mark-viewed", error)
    }

    private fun failure(operation: String, error: RuntimeException): ContractResult.Failure {
        // 예외 메시지나 원장 값은 기록하지 않는다. 실패 응답으로 실행 중 상태를 해제한다.
        Log.w("StoredHostSource", "$operation failed: ${error.javaClass.simpleName}")
        return ContractResult.Failure(when (error) {
            is SQLiteException -> ContractError.STORAGE_FAILURE
            is SecurityException -> ContractError.PERMISSION_DENIED
            else -> ContractError.DATA_UNAVAILABLE
        })
    }
}

/** 실제 store와 동일한 읽기 경로를 시험한다. 이 경계에서 쓰기 API는 호출하지 않는다. */
internal interface StoredHostReads : VehicleHistoryStore {
    val source: InputSource
    fun balance(): ContractResult<GameMoney>
    fun owned(): ContractResult<List<OwnedVehicle>>
    fun settlements(): ContractResult<List<Settlement>>
    fun activity(id: OwnedVehicleId): ContractResult<VehicleActivity>
    fun accidents(id: OwnedVehicleId): ContractResult<Int>
    fun capacity(): ContractResult<Int>
}

private class LedgerHostReads(private val store: SqliteGameChangeStore) : StoredHostReads, VehicleHistoryStore by store {
    override val source get() = store.source
    override fun balance() = store.balance()
    override fun owned() = store.listOwnedVehicles()
    override fun settlements() = store.listUnviewedSettlements()
    override fun activity(id: OwnedVehicleId) = store.rental.activity(id)
    override fun accidents(id: OwnedVehicleId) = store.rental.accidentCount(id)
    override fun capacity(): ContractResult<Int> = when (val result = store.rental.shop()) {
        is ContractResult.Failure -> result
        is ContractResult.Success -> ContractResult.Success(result.value.capacity)
    }
}

internal fun readStoredHostSnapshot(
    content: GameContentSnapshot,
    expectedSource: InputSource,
    reads: StoredHostReads,
): ContractResult<HostSnapshot> {
    if (reads.source != expectedSource) return ContractResult.Failure(ContractError.INVALID_INPUT)
    val balance = when (val result = reads.balance()) {
        is ContractResult.Failure -> return result
        is ContractResult.Success -> result.value
    }
    val owned = when (val result = reads.owned()) {
        is ContractResult.Failure -> return result
        is ContractResult.Success -> Collections.unmodifiableList(ArrayList(result.value))
    }
    val settlements = when (val result = reads.settlements()) {
        is ContractResult.Failure -> return result
        is ContractResult.Success -> Collections.unmodifiableList(ArrayList(result.value))
    }
    if (settlements.any { it.transaction.source != expectedSource }) return ContractResult.Failure(ContractError.INVALID_INPUT)
    val garage = Garage(content.catalog)
    when (val result = garage.owned(owned)) {
        is ContractResult.Failure -> return result
        is ContractResult.Success -> Unit
    }
    val history = when (val result = garage.collection(reads)) {
        is ContractResult.Failure -> result
        is ContractResult.Success -> ContractResult.Success(CollectionHistory(
            result.value.filter { it.state == CollectionState.OWNED }.mapTo(linkedSetOf()) { it.key },
            result.value.filter { it.state == CollectionState.DISCOVERED }.mapTo(linkedSetOf()) { it.key },
            expectedSource, content.catalog.contentVersion, content.ruleVersion))
    }
    val vehicles = owned.map { vehicle ->
        StoredVehicleState(vehicle, reads.activity(vehicle.ownedVehicleId), nonNegative(reads.accidents(vehicle.ownedVehicleId)))
    }
    val capacity = nonNegative(reads.capacity())
    // 개체 ID/revision의 조회 중 변경만 감지한다. 활동·잔액·이력 전체의 원자성/전역 revision 보장은 아니다.
    when (val result = reads.owned()) {
        is ContractResult.Failure -> return result
        is ContractResult.Success -> if (owned != result.value) return ContractResult.Failure(ContractError.STATE_CONFLICT)
    }
    return ContractResult.Success(HostSnapshot(content, expectedSource, balance, owned, settlements, history,
        ContractResult.Success(StoredGarageState(expectedSource, content.catalog.contentVersion, content.ruleVersion, vehicles, capacity))))
}

private fun nonNegative(result: ContractResult<Int>): ContractResult<Int> =
    if (result is ContractResult.Success && result.value < 0) ContractResult.Failure(ContractError.STORAGE_FAILURE) else result
