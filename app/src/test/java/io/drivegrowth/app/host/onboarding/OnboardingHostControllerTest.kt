package io.drivegrowth.app.host.onboarding

import io.drivegrowth.app.host.GameIdentity

import io.drivegrowth.app.ui.onboarding.*
import io.drivegrowth.core.content.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.FirstGrant
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

class OnboardingHostControllerTest {
    private class Queue : Executor { val jobs = java.util.ArrayDeque<Runnable>(); override fun execute(r: Runnable) { jobs.add(r) }; fun run() { assertFalse("worker/main 작업이 예약되어야 함",jobs.isEmpty()); jobs.removeFirst().run() } }
    private fun content() = (GameContentLoader.load(File("src/main/assets/content/vehicle-facts.csv").readText(),File("src/main/assets/content/game-content.csv").readText()) as ContentLoad.Loaded).snapshot
    private fun newState(): InitializationState {
        val c=content();val p=ProfileId("real-local-profile");return InitializationState(p,InputSource.VEHICLE,c.catalog.contentVersion,c.ruleVersion,
            ProfileInitializationStatus.New(NewProfileEvidence(p,InputSource.VEHICLE,"issued-proof",Revision(0))),GameMoney(0),0,FirstGrant.INITIAL_MONEY,3,FirstGrant.CANDIDATES.toSet())
    }
    private fun result(state: InitializationState, id: VehicleId): InitializationSettlement {
        val car=OwnedVehicle(OwnedVehicleId("initial-grant"),id,1,Revision(0),VehicleLifecycle.OWNED,Rarity.COMMON,0,GameMoney(0),VehicleAcquisitionSource.INITIAL_GRANT)
        val proof=(state.status as ProfileInitializationStatus.New).evidence
        val c=InitializationCommand(state.profileId,proof,state.contentVersion,GameChange(FirstGrant.REQUEST_ID,TransactionKind.GARAGE,state.source,state.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)),VehicleWrite.Add(car)),3)
        return InitializationSettlement(c,Settlement(SettlementId("saved"),Transaction(TransactionId("tx"),c.grant.requestId,c.grant.kind,c.grant.source,c.grant.ruleVersion,c.grant.money),GameMoney(1000),car,EpochMillis(1)),3,InitializationMarker(state.profileId,c.grant.requestId,proof))
    }
    private inner class Source : OnboardingHostSource {
        var snapshot=OnboardingSnapshot(GameIdentity(newState().profileId,InputSource.VEHICLE),newState(),ContractResult.Success(content()),null)
        var reads=0;var commits=0;var failure:ContractError?=null;var giftClaims=0
        override fun read():ContractResult<OnboardingSnapshot>{reads++;return ContractResult.Success(snapshot)}
        override fun choose(identity:GameIdentity,vehicleId:VehicleId):ContractResult<InitializationSettlement>{
            commits++;failure?.let{return ContractResult.Failure(it)}
            (snapshot.initialization.status as? ProfileInitializationStatus.Existing)?.original?.let { return ContractResult.Success(it) }
            val saved=result(snapshot.initialization,vehicleId)
            snapshot=snapshot.copy(initialization=snapshot.initialization.copy(status=ProfileInitializationStatus.Existing(saved),balance=GameMoney(1000),ownedVehicleCount=1))
            return ContractResult.Success(saved)
        }
        override fun claimGift(identity:GameIdentity):ContractResult<FirstRentalGiftSettlement> {
            giftClaims++
            val state=(snapshot.gift as ContractResult.Success).value
            state.granted?.let { return ContractResult.Success(it) }
            val rental=state.firstCompletedRental!!
            val command=FirstRentalGiftCommand(FirstGrant.GIFT_REQUEST_ID,identity.profileId,identity.source,state.contentVersion,state.ruleVersion,
                FirstRentalGiftCause(rental.rentalId,rental.receipt.settlement.settlementId),state.progress.revision)
            val ready=(prepareFirstRentalGift(command,state) as ContractResult.Success).value as FirstRentalGiftPreparation.Ready
            val result=FirstRentalGiftSettlement(command,SettlementId("gift"),Transaction(TransactionId("gift-tx"),command.requestId,TransactionKind.GARAGE,identity.source,command.ruleVersion,null),
                ready.balance,ready.progress,ready.marker,EpochMillis(2))
            snapshot=snapshot.copy(gift=ContractResult.Success(state.copy(progress=result.progress,granted=result)))
            return ContractResult.Success(result)
        }
    }
    private inner class Harness(initial:OnboardingHostUiState=OnboardingHostUiState()) {
        val w=Queue();val m=Queue();val source=Source();val frames=mutableListOf<Pair<OnboardingScreenState,Boolean>>();var shops=0
        val host=OnboardingHostController(source,w,m,initial)
        init {host.attach({s,e->frames+=s to e},{shops++})}
        fun load(){host.setControlsEnabled(true);w.run();m.run()}
        fun choose(){host.onEvent(OnboardingEvent.StartChoosing);host.onEvent(OnboardingEvent.SelectVehicle(FirstGrant.CANDIDATES.first()));host.onEvent(OnboardingEvent.ReviewSelection(FirstGrant.CANDIDATES.first()))}
    }
    @Test fun newProfileSelectionConfirmationCommitsOnceAndRequeries() {
        val h=Harness();h.load();assertTrue(h.frames.last().first is OnboardingScreenState.Intro);h.choose()
        assertTrue(h.frames.last().first is OnboardingScreenState.Confirming)
        h.host.onEvent(OnboardingEvent.ConfirmSelection(FirstGrant.CANDIDATES.first()));h.host.onEvent(OnboardingEvent.ConfirmSelection(FirstGrant.CANDIDATES.first()))
        assertEquals(0,h.source.commits);h.w.run();h.m.run();assertEquals(1,h.source.commits);assertEquals(2,h.source.reads)
        assertTrue(h.frames.last().first is OnboardingScreenState.Completed);h.host.onEvent(OnboardingEvent.GoToShop);assertEquals(1,h.shops)
    }
    @Test fun gateLossDropsQueuedCommitAndLateReadThenRestoresSameChoiceRetry() {
        val h=Harness();h.load();h.choose();val saved=h.host.uiState
        h.host.onEvent(OnboardingEvent.ConfirmSelection(FirstGrant.CANDIDATES.first()));h.host.setControlsEnabled(false);h.w.run();h.m.run();assertEquals(0,h.source.commits)
        h.host.setControlsEnabled(true);h.w.run();h.m.run();assertTrue(h.frames.last().first is OnboardingScreenState.GrantError);assertEquals(saved.selected,h.host.uiState.selected)
    }
    @Test fun unknownProfileNeverShowsCandidatesOrCommits() {
        val h=Harness();h.source.snapshot=h.source.snapshot.copy(initialization=newState().copy(status=ProfileInitializationStatus.Unknown(MissingReason.NO_DATA)));h.load()
        assertTrue(h.frames.last().first is OnboardingScreenState.Unavailable);h.choose();h.host.onEvent(OnboardingEvent.ConfirmSelection(FirstGrant.CANDIDATES.first()));assertEquals(0,h.source.commits);assertTrue(h.w.jobs.isEmpty())
    }
    @Test fun existingResultRestoresWithoutCurrentCatalogOrNewCommit() {
        val h=Harness();val saved=result(newState(),FirstGrant.CANDIDATES.first())
        h.source.snapshot=h.source.snapshot.copy(initialization=newState().copy(status=ProfileInitializationStatus.Existing(saved)),content=ContractResult.Failure(ContractError.DATA_UNAVAILABLE));h.load()
        val shown=h.frames.last().first as OnboardingScreenState.Completed;assertEquals(saved.grant.changedVehicle,shown.vehicle);assertTrue(shown.alreadyGranted);assertEquals(0,h.source.commits)
    }
    @Test fun storageFailureShowsRetryAndDoesNotNavigate() {
        val h=Harness();h.load();h.choose();h.source.failure=ContractError.STORAGE_FAILURE
        h.host.onEvent(OnboardingEvent.ConfirmSelection(FirstGrant.CANDIDATES.first()));h.w.run();h.m.run()
        assertTrue(h.frames.last().first is OnboardingScreenState.GrantError);h.host.onEvent(OnboardingEvent.GoToShop);assertEquals(0,h.shops)
    }
    @Test fun detachedOrOtherProfileSelectionCannotLeakIntoFreshProfile() {
        val initial=OnboardingHostUiState(GameIdentity(ProfileId("other"),InputSource.SIMULATION),OnboardingStep.CONFIRMING,FirstGrant.CANDIDATES.first())
        val h=Harness(initial);h.load();assertTrue(h.frames.last().first is OnboardingScreenState.Intro);assertNull(h.host.uiState.selected)
        h.host.detach();h.host.onEvent(OnboardingEvent.StartChoosing);assertTrue(h.w.jobs.isEmpty())
    }
    private fun completedGiftState(): FirstRentalGiftState {
        val s=newState();val rental=RequestId("first-rental")
        val receipt=Settlement(SettlementId("rental-receipt"),Transaction(TransactionId("rental-tx"),RequestId("rental/settle/${rental.value}"),TransactionKind.RENTAL,s.source,s.ruleVersion,MoneyChange(MoneyDirection.CREDIT,GameMoney(100))),GameMoney(1100),null,EpochMillis(1))
        return FirstRentalGiftState(s.profileId,s.source,s.contentVersion,s.ruleVersion,GameMoney(1100),GachaProgress(Revision(0),0,0),
            CompletedRental(rental,OwnedVehicleId("initial-grant"),RentalReceipt(receipt,false,true,false,GameMoney(0),null)),null)
    }
    @Test fun firstRentalGiftCommitsThenReloadsAndNeverDuplicatesOnRefresh() {
        val h=Harness();val first=result(newState(),FirstGrant.CANDIDATES.first())
        h.source.snapshot=h.source.snapshot.copy(initialization=newState().copy(status=ProfileInitializationStatus.Existing(first)),gift=ContractResult.Success(completedGiftState()))
        h.load();assertEquals(1,h.source.giftClaims);assertEquals(2,h.source.reads)
        assertTrue((h.frames.last().first as OnboardingScreenState.Completed).summary.contains("현재 무료 뽑기권 1장"))
        val gifted=(h.source.snapshot.gift as ContractResult.Success).value
        h.source.snapshot=h.source.snapshot.copy(gift=ContractResult.Success(gifted.copy(progress=GachaProgress(Revision(2),0,0))))
        h.host.refresh();h.w.run();h.m.run();assertEquals(1,h.source.giftClaims)
        assertTrue((h.frames.last().first as OnboardingScreenState.Completed).summary.contains("현재 무료 뽑기권 0장"))
    }
    @Test fun mismatchedGiftSourceIsRejectedBeforeAnyGiftCommit() {
        val h=Harness();val first=result(newState(),FirstGrant.CANDIDATES.first())
        h.source.snapshot=h.source.snapshot.copy(initialization=newState().copy(status=ProfileInitializationStatus.Existing(first)),gift=ContractResult.Success(completedGiftState().copy(source=InputSource.SIMULATION)))
        h.load();assertEquals(0,h.source.giftClaims);assertTrue(h.frames.last().first is OnboardingScreenState.LoadError)
    }
    @Test fun originalViewPendingLockCanRetryAfterGateCancelsQueuedCommit() {
        val h=Harness();val input=OnboardingInteraction(h.host::onEvent)
        h.host.attach({state,enabled->assertTrue(input.render(state,enabled));input.restoreSelection(h.host.uiState.selected)},{h.shops++})
        h.load();assertTrue(input.start());assertTrue(input.select(FirstGrant.CANDIDATES.first()));assertTrue(input.review());assertTrue(input.confirm(FirstGrant.CANDIDATES.first()))
        h.host.setControlsEnabled(false);h.w.run();h.m.run();h.host.setControlsEnabled(true);h.w.run();h.m.run()
        assertTrue(input.state is OnboardingScreenState.GrantError);assertTrue(input.retry());h.w.run();h.m.run()
        assertTrue(input.state is OnboardingScreenState.Completed);assertEquals(1,h.source.commits)
    }
    @Test fun completedCommitLateCallbackIsDiscardedAndDatabaseResultRestores() {
        val h=Harness();h.load();h.choose();h.host.onEvent(OnboardingEvent.ConfirmSelection(FirstGrant.CANDIDATES.first()));h.w.run()
        h.host.setControlsEnabled(false);val frames=h.frames.size;h.m.run();assertEquals(frames,h.frames.size)
        h.host.setControlsEnabled(true);h.w.run();h.m.run();assertTrue(h.frames.last().first is OnboardingScreenState.Completed);assertEquals(1,h.source.commits)
    }
}
