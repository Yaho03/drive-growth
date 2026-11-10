package io.drivegrowth.app.runtime.rental

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class RentalRuntimeTest {
    private class Queue : Executor {
        val jobs = java.util.ArrayDeque<Runnable>()
        override fun execute(r: Runnable) { jobs.add(r) }
        fun run() { jobs.removeFirst().run() }
    }
    private class Source : RentalRuntimeSource {
        var calls = 0
        override fun visit(input: ContractResult<RentalVisit>): ContractResult<OwnedVehicleId?> { calls++;return ContractResult.Success(null) }
        override fun advance(tick: RentalTick): ContractResult<RentalRuntimeSnapshot> { calls++;return read() }
        override fun read(): ContractResult<RentalRuntimeSnapshot> { calls++;return ContractResult.Success(RentalRuntimeSnapshot(emptyList(),null)) }
    }
    @Test fun workerOnlyAndOneInFlight() {
        val w=Queue();val m=Queue();val source=Source();val events=mutableListOf<RentalRuntimeEvent>();val r=RentalRuntime(w,m,source)
        assertFalse(r.refresh());r.start(events::add);assertTrue(r.refresh());assertEquals(0,source.calls);assertFalse(r.refresh())
        w.run();assertEquals(1,source.calls);assertTrue(events.isEmpty());m.run();assertEquals(1,events.size);assertTrue(r.refresh())
    }
    @Test fun stopDiscardsQueuedCommandAndLateResultAcrossRestart() {
        val w=Queue();val m=Queue();val s=Source();val events=mutableListOf<RentalRuntimeEvent>();val r=RentalRuntime(w,m,s)
        r.start(events::add);assertTrue(r.refresh());r.stop();w.run();m.run();assertEquals(0,s.calls);assertTrue(events.isEmpty())
        r.start(events::add);r.refresh();w.run();r.stop();r.start(events::add);m.run();assertTrue(events.isEmpty());assertTrue(r.refresh())
    }
    @Test fun workerFailureIsReportedWithoutSuccess() {
        val w=Queue();val m=Queue();val events=mutableListOf<RentalRuntimeEvent>()
        val source=object:RentalRuntimeSource{
            override fun visit(input:ContractResult<RentalVisit>)=throw SecurityException()
            override fun advance(tick:RentalTick)=throw SecurityException()
            override fun read():ContractResult<RentalRuntimeSnapshot> = throw SecurityException()
        }
        val r=RentalRuntime(w,m,source);r.start(events::add);r.refresh();w.run();m.run()
        assertEquals(RentalRuntimeEvent.Update(ContractResult.Failure(ContractError.PERMISSION_DENIED)),events.single())
    }
    @Test fun explicitMissingDefinitionDoesNotOpenDatabase() {
        var opened=0
        val source=SqliteRentalRuntimeSource(InputSource.SIMULATION,{opened++;error("must not open")},{RentalClock("boot",EpochMillis(0),0)})
        for(e in listOf(ContractError.DATA_UNAVAILABLE,ContractError.POLICY_UNRESOLVED,ContractError.PERMISSION_DENIED))
            assertEquals(ContractResult.Failure(e),source.visit(ContractResult.Failure(e)))
        assertEquals(0,opened)
    }
    @Test fun wrongSourceAndFutureVisitNeverOpenDatabase() {
        var opened=0
        val source=SqliteRentalRuntimeSource(InputSource.SIMULATION,{opened++;error("must not open")},{RentalClock("boot",EpochMillis(0),0)})
        val vehicle=VehicleId("v")
        fun visit(origin:InputSource)=RentalVisit(origin,io.drivegrowth.core.rental.RentalRequest(RequestId("r"),vehicle,1),
            VehicleCatalogSnapshot(ContentVersion("c"),listOf(VehicleDefinition(vehicle,"V"))),emptyMap(),emptyMap(),
            io.drivegrowth.app.data.RentalTerms(RuleVersion("r"),io.drivegrowth.core.rental.SimulatedCustomer(30,36),99,EpochMillis(1),15,GameMoney(0),null))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),source.visit(ContractResult.Success(visit(InputSource.VEHICLE))))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),source.visit(ContractResult.Success(visit(InputSource.SIMULATION))))
        assertEquals(0,opened)
    }

}
