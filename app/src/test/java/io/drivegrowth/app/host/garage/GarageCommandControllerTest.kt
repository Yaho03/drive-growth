package io.drivegrowth.app.host.garage

import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleAction
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class GarageCommandControllerTest {
    private val event = GarageEvent.RequestAction(OwnedVehicleId("target"), Revision(2), VehicleAction.LEVEL_UP)
    private val command = GarageCommand.Single(GameChange(RequestId("fixed"), TransactionKind.GARAGE, InputSource.SIMULATION,
        RuleVersion("rule"), MoneyChange(MoneyDirection.DEBIT, GameMoney(22)), VehicleWrite.Update(event.id,event.revision,3,VehicleLifecycle.OWNED,25)))
    private val pending = GaragePending(event,ContentVersion("content"),command)
    private class Queue : Executor { val tasks=ArrayDeque<Runnable>();override fun execute(r:Runnable){tasks.add(r)};fun run(){tasks.removeFirst().run()} }
    private inner class Source : GarageCommandSource {
        var prepares=0;var commits=mutableListOf<GarageCommand>();var result:ContractResult<GarageReceipt> = ContractResult.Failure(ContractError.STORAGE_FAILURE)
        override fun prepare(request:GarageEvent.RequestAction,id:RequestId,material:io.drivegrowth.core.garage.VehicleRef?):ContractResult<GaragePreparation> {prepares++;return ContractResult.Success(GaragePreparation.Ready(pending))}
        override fun commit(command:GarageCommand):ContractResult<GarageReceipt>{commits.add(command);return result}
    }
    private class Journal : GarageCommandJournal {
        var saved:GaragePending?=null;var failure:ContractError?=null
        var cancelResult:ContractResult<Unit> = ContractResult.Failure(ContractError.STATE_CONFLICT)
        var loadFailure:ContractError?=null
        override fun load():ContractResult<GaragePending?> = loadFailure?.let{ContractResult.Failure(it)} ?: ContractResult.Success(saved)
        override fun save(pending:GaragePending):ContractResult<Unit> { failure?.let{return ContractResult.Failure(it)};saved=pending;return ContractResult.Success(Unit) }
        override fun clear(pending:GaragePending):ContractResult<Unit> {saved=null;return ContractResult.Success(Unit)}
        override fun cancelUnstarted(pending:GaragePending):ContractResult<Unit> {if(cancelResult is ContractResult.Success)saved=null;return cancelResult}
    }
    @Test fun checkpointFailureNeverCommitsAndDoesNotPretendSuccess() {
        val source=Source();val journal=Journal();journal.failure=ContractError.STORAGE_FAILURE
        val host=GarageCommandController(source,journal,Executor{it.run()},Executor{it.run()}){}
        host.setControlsEnabled(true);host.open(event);host.confirm()
        assertTrue(source.commits.isEmpty());assertTrue(host.state is GarageCommandState.Failure)
    }
    @Test fun retryAndNewControllerUseExactSavedCommandWithoutPreparingAgain() {
        val source=Source();val journal=Journal();val direct=Executor{it.run()}
        val first=GarageCommandController(source,journal,direct,direct){}
        first.setControlsEnabled(true);first.open(event);first.confirm();first.retry()
        assertEquals(1,source.prepares);assertEquals(listOf(command,command),source.commits)
        val second=GarageCommandController(source,journal,direct,direct){}
        second.setControlsEnabled(true);second.recover();second.confirm()
        assertEquals(1,source.prepares);assertEquals(listOf(command,command,command),source.commits)
    }
    @Test fun restrictionBeforeWorkerAndRepeatedConfirmNeverSendExtraCommit() {
        val source=Source();val journal=Journal();val worker=Queue();val main=Executor{it.run()}
        val host=GarageCommandController(source,journal,worker,main){}
        host.setControlsEnabled(true);host.open(event);worker.run();host.confirm();host.confirm()
        host.setControlsEnabled(false);worker.run()
        assertTrue(source.commits.isEmpty());assertTrue(host.state is GarageCommandState.Failure)
    }
    @Test fun cancelBeforeConfirmationDoesNotWriteAnyJournalOrCommand() {
        val source=Source();val journal=Journal();val direct=Executor{it.run()}
        val host=GarageCommandController(source,journal,direct,direct){}
        host.setControlsEnabled(true);host.open(event);assertTrue(host.cancel())
        assertNull(journal.saved);assertTrue(source.commits.isEmpty());assertEquals(GarageCommandState.Idle,host.state)
    }
    @Test fun actualReceiptIsRequiredAndCheckpointClearsOnlyAfterUserAcknowledges() {
        val source=Source();val journal=Journal();val direct=Executor{it.run()}
        val car=OwnedVehicle(event.id,VehicleId("car"),3,Revision(3),VehicleLifecycle.OWNED,Rarity.RARE,25,GameMoney(250),VehicleAcquisitionSource.GACHA)
        val receipt=Settlement(SettlementId("receipt"),Transaction(TransactionId("tx"),RequestId("fixed"),TransactionKind.GARAGE,
            InputSource.SIMULATION,RuleVersion("rule"),MoneyChange(MoneyDirection.DEBIT,GameMoney(22))),GameMoney(78),car,EpochMillis(1))
        source.result=ContractResult.Success(GarageReceipt.Single(receipt))
        val host=GarageCommandController(source,journal,direct,direct){}
        host.setControlsEnabled(true);host.open(event);host.confirm()
        assertTrue(host.state is GarageCommandState.Confirmed);assertEquals(pending,journal.saved)
        host.acknowledge();assertNull(journal.saved);assertEquals(GarageCommandState.Idle,host.state)
    }
    @Test fun responseForAnotherRequestIsNotShownAsConfirmed() {
        val source=Source();val journal=Journal();val direct=Executor{it.run()}
        source.result=ContractResult.Success(GarageReceipt.Single(Settlement(SettlementId("other"),
            Transaction(TransactionId("other"),RequestId("wrong"),TransactionKind.GARAGE,InputSource.SIMULATION,RuleVersion("rule"),null),
            GameMoney(0),null,EpochMillis(1))))
        val host=GarageCommandController(source,journal,direct,direct){}
        host.setControlsEnabled(true);host.open(event);host.confirm()
        assertEquals(ContractError.STORAGE_FAILURE,(host.state as GarageCommandState.Failure).error)
        assertEquals(pending,journal.saved)
    }
    @Test fun cancelledReadReplyDoesNotReopenConfirmation() {
        val source=Source();val journal=Journal();val worker=Queue();val direct=Executor{it.run()}
        val host=GarageCommandController(source,journal,worker,direct){}
        host.setControlsEnabled(true);host.open(event);assertTrue(host.cancel());worker.run()
        assertEquals(GarageCommandState.Idle,host.state);assertNull(journal.saved);assertTrue(source.commits.isEmpty())
    }
    @Test fun unknownResultCannotBeDiscardedByUiButVerifiedUnstartedCancellationCanResolveIt() {
        val source=Source();val journal=Journal();val direct=Executor{it.run()}
        val host=GarageCommandController(source,journal,direct,direct){}
        host.setControlsEnabled(true);host.open(event);host.confirm();host.cancelPending()
        assertTrue(host.state is GarageCommandState.Failure);assertEquals(pending,journal.saved)
        journal.cancelResult=ContractResult.Success(Unit);host.cancelPending()
        assertTrue(host.state is GarageCommandState.Rejected);assertNull(journal.saved)
        assertEquals(1,source.prepares);assertEquals(1,source.commits.size)
    }
    @Test fun closingUnknownResultThenOpeningAnotherActionRecoversOriginalInsteadOfPreparingNewCommand() {
        val source=Source();val journal=Journal();val direct=Executor{it.run()}
        val host=GarageCommandController(source,journal,direct,direct){}
        host.setControlsEnabled(true);host.open(event);host.confirm();assertTrue(host.cancel())
        host.open(event.copy(action=VehicleAction.SELL))
        assertEquals(pending,(host.state as GarageCommandState.Review).pending);assertEquals(1,source.prepares)
    }
    @Test fun missingProfileOrCheckpointReadFailureIsNotAnEmptySuccessfulJournal() {
        val source=Source();val journal=Journal();val direct=Executor{it.run()}
        val host=GarageCommandController(source,journal,direct,direct){}
        journal.loadFailure=ContractError.DATA_UNAVAILABLE
        host.setControlsEnabled(true);host.recover()
        assertEquals(ContractError.DATA_UNAVAILABLE,(host.state as GarageCommandState.Failure).error)
        host.open(event);assertEquals(ContractError.DATA_UNAVAILABLE,(host.state as GarageCommandState.Failure).error)
        assertEquals(0,source.prepares);assertTrue(source.commits.isEmpty())
    }
}
