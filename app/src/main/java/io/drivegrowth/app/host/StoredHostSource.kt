package io.drivegrowth.app.host

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource

/** worker에서 호출하는 읽기 전용 진입점. 기존 개별 조회를 원자적인 일괄 조회로 취급하지 않는다. */
internal class StoredHostSource(
    context: Context,
    private val collectionHistory: () -> ContractResult<CollectionHistory> = {
        ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    },
) {
    private val context = context.applicationContext

    fun read(): ContractResult<HostSnapshot> {
        var operation = "content"
        return try {
            val content = (AssetGameContent(context.assets).load() as? ContentLoad.Loaded)?.snapshot
            if (content == null) ContractResult.Failure(ContractError.DATA_UNAVAILABLE) else {
                operation = "ledger-open"
                SqliteGameChangeStore(context, InputSource.SIMULATION).use { store ->
                    operation = "balance"
                    val balance = when (val result = store.balance()) {
                        is ContractResult.Failure -> return result
                        is ContractResult.Success -> result.value
                    }
                    operation = "owned-vehicles"
                    val owned = when (val result = store.listOwnedVehicles()) {
                        is ContractResult.Failure -> return result
                        is ContractResult.Success -> result.value
                    }
                    operation = "settlements"
                    val settlements = when (val result = store.listUnviewedSettlements()) {
                        is ContractResult.Failure -> return result
                        is ContractResult.Success -> result.value
                    }
                    operation = "collection-history"
                    val history = collectionHistory()
                    operation = "ledger-close"
                    ContractResult.Success(HostSnapshot(content, InputSource.SIMULATION, balance, owned, settlements, history))
                }
            }
        } catch (error: RuntimeException) {
            // 예외 메시지나 원장 값은 기록하지 않는다. 실패 응답으로 실행 중 상태를 해제한다.
            Log.w("StoredHostSource", "$operation failed: ${error.javaClass.simpleName}")
            ContractResult.Failure(when (error) {
                is SQLiteException -> ContractError.STORAGE_FAILURE
                is SecurityException -> ContractError.PERMISSION_DENIED
                else -> ContractError.DATA_UNAVAILABLE
            })
        }
    }
}
