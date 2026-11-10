package io.drivegrowth.app.host.facility

import io.drivegrowth.app.data.ShopState
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class FacilityHostControllerTest {
    private val identity=GameIdentity(ProfileId("actual"),InputSource.SIMULATION)
    private val offer=FacilityOffer(Facility.FUEL_STATION,RuleVersion("central"),true,GameMoney(731))
    private val facts=FacilityFacts(identity,ContentVersion("definition"),ShopState(ShopProgress(5,100,3),12,emptySet()),GameMoney(1000),emptyList(),emptyMap(),emptySet(),Facility.entries.associateWith{offer.copy(facility=it)})
    private class Queue:Executor {val tasks=ArrayDeque<Runnable>();override fun execute(r:Runnable){tasks.add(r)};fun drain(){while(tasks.isNotEmpty())tasks.removeFirst().run()}}
    private inner class Backend:FacilityHostSource {
        var entry:FacilityPending=FacilityPending.Empty
        var currentFacts=facts
        var readError:ContractError?=null
        var reads=0;var executes=0;var saves=0;var finishes=0
        var saveError:ContractError?=null;var finishError:ContractError?=null;var executeError:ContractError?=null
        override fun pending():ContractResult<FacilityPending> = ContractResult.Success(entry)
        override fun read():ContractResult<FacilityFacts> {reads++;readError?.let{return ContractResult.Failure(it)};return ContractResult.Success(currentFacts)}
        override fun save(command:FacilityCommand):ContractResult<Unit> {saves++;saveError?.let{return ContractResult.Failure(it)};entry=FacilityPending.Own(command);return ContractResult.Success(Unit)}
        override fun execute(command:FacilityCommand):ContractResult<FacilityReceipt> {executes++;executeError?.let{return ContractResult.Failure(it)};if(command is FacilityCommand.Rest)return ContractResult.Success(FacilityReceipt.Rested(command,command.expected,command.desired));return ContractResult.Success(FacilityReceipt.Purchased(command as FacilityCommand.Buy,Settlement(SettlementId("original"),Transaction(TransactionId("tx"),command.requestId,TransactionKind.FACILITY,command.identity.source,command.offer.ruleVersion,MoneyChange(MoneyDirection.DEBIT,command.offer.price)),GameMoney(269),null,EpochMillis(1000))))}
        override fun finish(command:FacilityCommand):ContractResult<Unit> {finishes++;finishError?.let{return ContractResult.Failure(it)};entry=FacilityPending.Empty;return ContractResult.Success(Unit)}
        override fun cancelSaved(command:FacilityCommand):ContractResult<Unit> {entry=FacilityPending.Empty;return ContractResult.Success(Unit)}
    }
    private fun buy(c:FacilityHostController)=ShopRequest(RequestId("original-buy"),ShopSelection(Revision(c.state.version.toInt()),FacilityHostMapper.options(facts,true).first().option),"attempt")
    @Test fun requestIsSavedBeforeExecuteAndDoubleTapCannotDuplicate() {
        val b=Backend();val c=FacilityHostController(b,Executor{it.run()},Executor{it.run()});c.setControlsEnabled(true)
        val r=buy(c);assertTrue(c.submit(r));assertFalse(c.submit(r));assertEquals(1,b.saves);assertEquals(1,b.executes)
        assertNotNull(c.state.receipt);assertTrue(c.acknowledge());assertEquals(1,b.finishes);assertNull(c.state.pending)
    }
    @Test fun originalCommandRecoveryPrecedesCurrentFactsAndForeignIsPreserved() {
        val b=Backend();val original=FacilityCommand.Buy(identity,RequestId("original-buy"),ContentVersion("old"),offer);b.entry=FacilityPending.Own(original)
        val c=FacilityHostController(b,Executor{it.run()},Executor{it.run()});c.setControlsEnabled(true)
        assertEquals(0,b.reads);assertEquals(original,c.state.pending);assertNotNull(c.state.receipt)
        val other=Backend();other.entry=FacilityPending.Foreign
        val f=FacilityHostController(other,Executor{it.run()},Executor{it.run()});f.setControlsEnabled(true)
        assertTrue(f.state.foreign);assertEquals(0,other.reads);assertEquals(0,other.executes)
    }
    @Test fun gateLostBeforeQueuedWorkDoesNotSaveOrExecute() {
        val worker=Queue();val b=Backend();val c=FacilityHostController(b,worker,Executor{it.run()});c.setControlsEnabled(true);worker.drain()
        val r=buy(c);assertTrue(c.submit(r));c.setControlsEnabled(false);worker.drain();assertEquals(0,b.saves);assertEquals(0,b.executes)
    }
    @Test fun finishFailurePreservesOriginalReceiptAndMissingKnownCommandFailsClosed() {
        val b=Backend();val c=FacilityHostController(b,Executor{it.run()},Executor{it.run()});c.setControlsEnabled(true);c.submit(buy(c))
        val original=c.state.receipt;b.finishError=ContractError.STORAGE_FAILURE;c.acknowledge();assertEquals(original,c.state.receipt);assertNotNull(c.state.pending)
        b.entry=FacilityPending.Empty;c.refresh();assertEquals(ContractError.STORAGE_FAILURE,c.state.error);assertEquals(1,b.executes);assertNotNull(c.state.pending)
    }
    @Test fun staleSelectionAndReleasedControllerCannotSave() {
        val b=Backend();val c=FacilityHostController(b,Executor{it.run()},Executor{it.run()});c.setControlsEnabled(true);val old=buy(c);c.refresh();assertFalse(c.submit(old));c.release();assertFalse(c.submit(buy(c)));assertEquals(0,b.saves)
    }
    @Test fun asynchronousAcknowledgementReloadsActualFacts() {
        val worker=Queue();val b=Backend();val c=FacilityHostController(b,worker,Executor{it.run()})
        c.setControlsEnabled(true);worker.drain();c.submit(buy(c));worker.drain();assertTrue(c.acknowledge());worker.drain()
        assertNull(c.state.pending);assertNotNull(c.state.facts);assertTrue(b.reads>=2)
    }

    @Test fun restingDuringRentalUsesVehicleRevisionAndExpectedSettingWithoutChangingActivity() {
        val car=OwnedVehicle(OwnedVehicleId("car"),VehicleId("v"),1,Revision(2),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.GACHA)
        val b=Backend();b.currentFacts=facts.copy(vehicles=listOf(car),activities=mapOf(car.ownedVehicleId to io.drivegrowth.core.progression.VehicleActivity.RENTED))
        val c=FacilityHostController(b,Executor{it.run()},Executor{it.run()});c.setControlsEnabled(true)
        val r=RestingRequest(c.state.version,RequestId("rest-original"),io.drivegrowth.core.garage.VehicleRef(car.ownedVehicleId,car.revision),false,true)
        assertFalse(c.setResting(r.copy(expected=true)));assertFalse(c.setResting(r.copy(desired=false)))
        assertFalse(c.setResting(r.copy(vehicle=r.vehicle.copy(revision=Revision(1)))));assertTrue(c.setResting(r))
        val receipt=c.state.receipt as FacilityReceipt.Rested;assertTrue(receipt.after);assertEquals(r.vehicle,receipt.command.vehicle)
        assertEquals(io.drivegrowth.core.progression.VehicleActivity.RENTED,b.currentFacts.activities[car.ownedVehicleId])
        c.acknowledge();assertFalse(c.state.facts!!.resting.contains(car.ownedVehicleId))
    }
    @Test fun savedCommandRetryUsesOriginalOfferBeforeNewDisplayedFacts() {
        val b=Backend();val c=FacilityHostController(b,Executor{it.run()},Executor{it.run()});c.setControlsEnabled(true)
        b.executeError=ContractError.DATA_UNAVAILABLE;c.submit(buy(c));val original=c.state.pending;assertNotNull(original)
        b.currentFacts=facts.copy(offers=facts.offers.mapValues{it.value.copy(price=GameMoney(900))});b.executeError=null
        c.refresh();assertEquals(original,c.state.receipt!!.command);assertEquals(1,b.saves);assertEquals(1,b.reads)
    }

    @Test fun definitiveSaveRejectionDoesNotInventDurablePending() {
        val b=Backend();val c=FacilityHostController(b,Executor{it.run()},Executor{it.run()});c.setControlsEnabled(true)
        b.saveError=ContractError.STATE_CONFLICT;c.submit(buy(c));assertNull(c.state.pending)
        b.saveError=null;c.refresh();assertNotNull(c.state.facts);assertNull(c.state.error)
    }
    @Test fun ambiguousSaveFailureRetainsOriginalAndDoesNotPrepareAnother() {
        val b=Backend();val c=FacilityHostController(b,Executor{it.run()},Executor{it.run()});c.setControlsEnabled(true)
        b.saveError=ContractError.STORAGE_FAILURE;c.submit(buy(c));assertNotNull(c.state.pending)
        assertFalse(c.submit(buy(c)));c.refresh();assertEquals(ContractError.STORAGE_FAILURE,c.state.error);assertEquals(1,b.saves)
    }
    @Test fun unsupportedJournalCanDisplayFactsButCannotSubmit() {
        val b=Backend();b.entry=FacilityPending.Unsupported;val c=FacilityHostController(b,Executor{it.run()},Executor{it.run()});c.setControlsEnabled(true)
        assertTrue(c.state.unsupported);assertNotNull(c.state.facts);assertFalse(c.submit(buy(c)));assertEquals(0,b.saves)
    }

    @Test fun failedRefreshCannotSubmitPreviousFactsAsCurrent() {
        val b=Backend();val c=FacilityHostController(b,Executor{it.run()},Executor{it.run()});c.setControlsEnabled(true)
        val old=buy(c);b.readError=ContractError.DATA_UNAVAILABLE;c.refresh()
        assertEquals(ContractError.DATA_UNAVAILABLE,c.state.error);assertFalse(c.submit(old));assertEquals(0,b.saves)
    }

}
