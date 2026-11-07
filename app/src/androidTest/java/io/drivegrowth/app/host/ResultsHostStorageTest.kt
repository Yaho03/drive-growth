package io.drivegrowth.app.host

import android.test.InstrumentationTestCase
import io.drivegrowth.app.data.SqliteGameChangeStore
import io.drivegrowth.app.ui.results.ResultsReadState
import io.drivegrowth.app.ui.results.ResultsUiState
import io.drivegrowth.core.contract.*
import java.util.UUID
import java.util.concurrent.Executor

/** 실제 SQLite 어댑터 시험. 이번 시험용 격리 패키지 외에서는 쓰기를 시작하지 않는다. */
@Suppress("DEPRECATION")
class ResultsHostStorageTest : InstrumentationTestCase() {
    fun testRealSourceAcknowledgementAndReopenNeverChangeSettlementVehicleOrBalance() {
        val context = instrumentation.targetContext
        assertEquals("io.drivegrowth.app.integration51", context.packageName)
        val source = StoredHostSource(context)
        val before = (source.read() as ContractResult.Success).value
        val id = UUID.randomUUID().toString()
        val vehicle = OwnedVehicle(OwnedVehicleId("host-$id"), before.content.catalog.vehicles.first().vehicleId,
            1, Revision(0), VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
        val command = GameChange(RequestId("host-$id"), TransactionKind.GARAGE, InputSource.SIMULATION,
            before.content.ruleVersion, MoneyChange(MoneyDirection.CREDIT, GameMoney(120)), VehicleWrite.Add(vehicle))
        val saved = SqliteGameChangeStore(context, InputSource.SIMULATION).use {
            (it.commitGameChange(command) as ContractResult.Success).value
        }
        val first = (source.read() as ContractResult.Success).value
        assertEquals(saved, first.unviewedSettlements.single { it.settlementId == saved.settlementId })
        repeat(3) { assertEquals(ContractResult.Success(Unit), source.markViewed(saved.settlementId)) }
        val reopened = (StoredHostSource(context).read() as ContractResult.Success).value
        assertFalse(reopened.unviewedSettlements.any { it.settlementId == saved.settlementId })
        assertEquals(first.balance, reopened.balance)
        assertEquals(first.ownedVehicles, reopened.ownedVehicles)
        SqliteGameChangeStore(context, InputSource.SIMULATION).use {
            assertEquals(ContractResult.Success(saved), it.commitGameChange(command))
        }
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), source.markViewed(SettlementId("missing-$id")))
    }

    fun testProcessBundlePreservesIdentityButPendingIsNotStoredSuccessOrAutomaticWrite() {
        val id = SettlementId("pending-result")
        val saved = ResultsStateBundle.encode(ResultsUiState(id, mapOf(id to ResultsReadState.Pending), true))
        val restored = ResultsStateBundle.decode(saved)
        var writes = 0
        val controller = ResultsHostController(Executor { it.run() }, Executor { it.run() }, {
            writes++; ContractResult.Success(Unit)
        }, {}, restored)
        assertEquals(id, controller.uiState.selectedId)
        assertEquals(ResultsReadState.Failed(ContractError.DATA_UNAVAILABLE), controller.uiState.readStates[id])
        assertFalse(controller.uiState.retryPending)
        assertEquals(0, writes)
    }
}
