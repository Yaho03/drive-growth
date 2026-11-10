package io.drivegrowth.app.runtime.rental.product

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test
import java.math.RoundingMode
import kotlin.random.Random

class RentalVisitPreparationTest {
    private class Harness : RentalProductJournal {
        val identity=GameIdentity(ProfileId("profile"),InputSource.SIMULATION)
        val clock=RentalClock("fixture-boot",EpochMillis(1000000),1000)
        val cursor=RentalVisitCursor(Revision(0),0,clock)
        val vehicle=VehicleId("v")
        val catalog=VehicleCatalogSnapshot(ContentVersion("content"),listOf(VehicleDefinition(vehicle,"V")))
        val definition=RentalVisitDefinition(ContentVersion("trial"),RuleVersion("rules"),true,1800000,
            mapOf(vehicle to RentalCategory.SMALL),mapOf(vehicle to GameMoney(100)),mapOf(vehicle to 1),
            mapOf(1 to 1,3 to 0,6 to 0),mapOf(1 to 15,3 to 30,6 to 60),
            listOf(RentalCustomerWeight(SimulatedCustomer(30,36),false,1)),GameMoney(0),RoundingMode.DOWN)
        var pending:PreparedRentalVisit?=null;var saveFails=false;var finishFails=false;var inputsFail=false
        var inputReads=0;var ids=0;var executions=0;var finishes=0;var observedCalls=0
        val snapshots=mutableListOf<PreparedRentalVisit>()
        val source=RentalVisitPreparation({ContractResult.Success(identity)},this,RentalProductInputs {
            inputReads++
            if(inputsFail) ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            else ContractResult.Success(RentalPreparationInputs(catalog,definition,clock))
        },{Random(7)},{RequestId("visit-${++ids}")})
        val runtime=PreparedRentalRuntimeSource({ContractResult.Success(identity)},this,object:RentalRuntimeSource {
            override fun visit(input:ContractResult<RentalVisit>):ContractResult<OwnedVehicleId?> = error("direct assign is forbidden")
            override fun advance(tick:RentalTick):ContractResult<RentalRuntimeSnapshot>{observedCalls++;return read()}
            override fun read():ContractResult<RentalRuntimeSnapshot> = ContractResult.Success(RentalRuntimeSnapshot(emptyList(),null))
        })
        override fun pending(identity:GameIdentity)=ContractResult.Success(pending)
        override fun cursor(identity:GameIdentity)=ContractResult.Success(cursor)
        override fun save(input:PreparedRentalVisit):ContractResult<PreparedRentalVisit>{snapshots+=input;return if(saveFails) ContractResult.Failure(ContractError.STORAGE_FAILURE) else ContractResult.Success(input.also{pending=it})}
        override fun execute(input:PreparedRentalVisit):ContractResult<OwnedVehicleId?> {assertEquals(pending,input);executions++;return ContractResult.Success(null)}
        override fun finish(input:PreparedRentalVisit):ContractResult<Unit>{finishes++;return if(finishFails) ContractResult.Failure(ContractError.STORAGE_FAILURE) else ContractResult.Success(Unit).also{pending=null}}
        override fun beginInactive(identity:GameIdentity,marker:RequestId,since:RentalClock):ContractResult<Unit> = error("no fake inactive input")
        override fun advanceInactive(identity:GameIdentity,interval:RentalInactiveInterval):ContractResult<RentalRuntimeSnapshot> = error("no fake parked input")
        fun planned()=(source.next() as ContractResult.Success).value!!
    }
    @Test fun failedSaveRetriesSameWholeDraftWithoutNewInputsOrRandomId() {
        val h=Harness();h.saveFails=true
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),h.source.next())
        val original=h.snapshots.single();h.saveFails=false;h.inputsFail=true
        assertEquals(original,(h.source.next() as ContractResult.Success).value)
        assertEquals(1,h.inputReads);assertEquals(1,h.ids);assertEquals(original,h.snapshots.last());assertEquals(0,h.executions)
    }
    @Test fun durablePendingIsRecoveredBeforeUnavailableCurrentInputs() {
        val h=Harness();val original=h.planned();h.inputsFail=true
        assertEquals(original,(h.source.next() as ContractResult.Success).value)
        assertEquals(1,h.inputReads);assertEquals(1,h.ids);assertEquals(0,h.executions)
    }
    @Test fun originalAssignmentAndFinishAreReplayedAfterLostAcknowledgementWithoutNewVisit() {
        val h=Harness();val original=h.planned();h.finishFails=true
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),h.runtime.visit(ContractResult.Success(original.visit)))
        assertEquals(original,h.pending);h.finishFails=false
        assertEquals(ContractResult.Success<OwnedVehicleId?>(null),h.runtime.visit(ContractResult.Success(original.visit)))
        assertNull(h.pending);assertEquals(2,h.executions);assertEquals(1,h.ids)
    }
    @Test fun changedWholeVisitOrForeignSourceCannotExecutePending() {
        val h=Harness();val original=h.planned();val v=original.visit
        val changed=RentalVisit(v.source,v.request,v.catalog,v.categories,v.hourlyRates,v.terms.copy(roll=(v.terms.roll+1)%100))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),h.runtime.visit(ContractResult.Success(changed)))
        val foreign=RentalVisit(InputSource.VEHICLE,v.request,v.catalog,v.categories,v.hourlyRates,v.terms)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),h.runtime.visit(ContractResult.Success(foreign)))
        assertEquals(0,h.executions);assertEquals(original,h.pending)
    }
    @Test fun unpreparedVisitAndWrongSourceTickNeverReachFinancialOrObservedSource() {
        val h=Harness();val original=h.planned();h.pending=null
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),h.runtime.visit(ContractResult.Success(original.visit)))
        val tick=RentalTick(InputSource.VEHICLE,h.clock,h.clock,Observation.Missing(MissingReason.NO_DATA))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),h.runtime.advance(tick))
        assertEquals(0,h.executions);assertEquals(0,h.observedCalls)
    }
}
