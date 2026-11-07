package io.drivegrowth.app.host

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.GachaRules
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class HostQueryControllerTest {
    private fun snapshot(): HostSnapshot {
        val vehicle = OwnedVehicle(OwnedVehicleId("owned"), VehicleId("model"), 1, Revision(3),
            VehicleLifecycle.OWNED, Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT)
        val settlement = Settlement(SettlementId("settlement"), Transaction(TransactionId("transaction"),
            RequestId("request"), TransactionKind.GARAGE, InputSource.SIMULATION, RuleVersion("rule"), null),
            GameMoney(20), vehicle, EpochMillis(100))
        val content = GameContentSnapshot(VehicleCatalogSnapshot(ContentVersion("content"),
            listOf(VehicleDefinition(vehicle.vehicleId, "시험 차량"))), RuleVersion("rule"), true, emptyList(), GachaRules())
        return HostSnapshot(content, InputSource.SIMULATION, GameMoney(20), listOf(vehicle), listOf(settlement))
    }
    private class Tasks : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.addLast(command) }
        fun runNext() { pending.removeFirst().run() }
    }

    @Test fun successfulQueryPreservesRawOwnedVehicleAndSettlementAndLateSuccessCannotReopen() {
        val worker = Tasks()
        val main = Tasks()
        val seen = mutableListOf<HostQueryState>()
        val data = snapshot()
        val host = HostQueryController(worker, main, { ContractResult.Success(data) }, seen::add)
        host.setControlsEnabled(true)
        worker.runNext()
        main.runNext()
        val shown = (seen.last() as HostQueryState.Content).snapshot
        assertSame(data, shown)
        assertSame(data.ownedVehicles.first(), shown.ownedVehicles.first())
        assertSame(data.unviewedSettlements.first(), shown.unviewedSettlements.first())
        host.refresh()
        worker.runNext()
        host.setControlsEnabled(false)
        main.runNext()
        assertEquals(HostQueryState.Restricted, seen.last())
        assertEquals(1, seen.count { it is HostQueryState.Content })
    }

    @Test fun repeatedRefreshAndGateChangesKeepOneWorkerAndDiscardOldGeneration() {
        val worker = Tasks()
        val main = Tasks()
        val seen = mutableListOf<HostQueryState>()
        var reads = 0
        val host = HostQueryController(worker, main, {
            reads++
            ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        }, seen::add)
        host.setControlsEnabled(true)
        repeat(100) {
            host.refresh()
            host.setControlsEnabled(false)
            host.setControlsEnabled(true)
        }
        assertEquals(1, worker.pending.size)
        worker.runNext()
        main.runNext()
        assertEquals(1, reads)
        assertEquals(1, worker.pending.size)
        assertFalse(seen.any { it is HostQueryState.Failure })
        worker.runNext()
        main.runNext()
        assertEquals(2, reads)
        assertEquals(0, worker.pending.size)
        assertEquals(HostQueryState.Failure(ContractError.DATA_UNAVAILABLE), seen.last())
    }

    @Test fun restrictionAndReleaseDiscardInFlightResultWithoutSchedulingWork() {
        val worker = Tasks()
        val main = Tasks()
        val seen = mutableListOf<HostQueryState>()
        val host = HostQueryController(worker, main, {
            ContractResult.Failure(ContractError.PERMISSION_DENIED)
        }, seen::add)
        host.setControlsEnabled(true)
        host.setControlsEnabled(false)
        worker.runNext()
        main.runNext()
        assertEquals(HostQueryState.Restricted, seen.last())
        assertTrue(worker.pending.isEmpty())
        host.setControlsEnabled(true)
        host.release()
        val count = seen.size
        worker.runNext()
        main.runNext()
        host.refresh()
        host.setControlsEnabled(true)
        assertEquals(count, seen.size)
        assertTrue(worker.pending.isEmpty())
    }

    @Test fun explicitFailureIsPreservedAndRetryStartsFreshQuery() {
        val worker = Tasks()
        val main = Tasks()
        val seen = mutableListOf<HostQueryState>()
        val host = HostQueryController(worker, main, {
            ContractResult.Failure(ContractError.STORAGE_FAILURE)
        }, seen::add)
        host.setControlsEnabled(true)
        worker.runNext()
        main.runNext()
        assertEquals(HostQueryState.Failure(ContractError.STORAGE_FAILURE), seen.last())
        host.refresh()
        assertEquals(HostQueryState.Loading, seen.last())
        assertEquals(1, worker.pending.size)
    }
}
