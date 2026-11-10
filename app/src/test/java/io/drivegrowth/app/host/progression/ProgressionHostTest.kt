package io.drivegrowth.app.host.progression

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.progress.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.quest.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.concurrent.Executor

class ProgressionHostTest {
 private val identity=GameIdentity(ProfileId("profile"),InputSource.SIMULATION)
 private val version=RuleVersion("injected-trial")
 private val at=EpochMillis(Instant.parse("2026-11-10T03:00:00Z").toEpochMilli())
 private val zone=ZoneId.of("Asia/Seoul")
 private val quests=QuestCadence.entries.flatMap{cadence->QuestAction.entries.map{action->
  val d=QuestDefinition("${cadence.name.lowercase()}-${action.slug}",version,cadence,action,1,GameMoney(50),zone,true)
  QuestProgress(identity.profileId,identity.source,d,questPeriod(cadence,zone,at),1,Revision(1),QuestStatus.CLAIMABLE)
 }}
 private fun command(p:QuestProgress=quests.first(),id:String="one")=QuestClaimCommand(RequestId("quest/claim/$id"),identity.profileId,identity.source,p.definition,p.period,p.revision!!)
 private fun data(pending:ProgressionJournalState=ProgressionJournalState.Clear,supported:Boolean=true):ProgressionRead=ProgressionRead(identity,quests,
  quests.associate{progressKey(it) to command(it,it.definition.id)},pending,supported)
 private fun receipt(c:QuestClaimCommand=command())=QuestClaimSettlement(c,quests.first{it.definition==c.definition}.copy(status=QuestStatus.CLAIMED),
  Settlement(SettlementId("s"),Transaction(TransactionId("t"),c.requestId,TransactionKind.GARAGE,c.source,c.definition.version,MoneyChange(MoneyDirection.CREDIT,c.definition.reward)),GameMoney(150),null,at))
 private class Queue:Executor {val jobs=ArrayDeque<Runnable>();override fun execute(command:Runnable){jobs.add(command)};fun one(){jobs.removeFirst().run()};fun drain(){while(jobs.isNotEmpty())one()}}
 private inner class FakeSource:ProgressionSource {
  var value=data();var reads=0;var saves=0;var executions=0;var cancels=0;var executeFailure:ContractError?=null;var failure:ContractError?=null
  override fun read(references:Set<ClaimReference>):ContractResult<ProgressionRead>{reads++;return failure?.let{ContractResult.Failure(it)}?:ContractResult.Success(value.copy(resolved=value.resolved.filterKeys{it in references}))}
  override fun save(identity:GameIdentity,command:QuestClaimCommand):ContractResult<Unit>{saves++;value=value.copy(journal=ProgressionJournalState.Quest(command,ProgressionPhase.SAVED));return ContractResult.Success(Unit)}
  override fun execute(identity:GameIdentity,command:QuestClaimCommand):ContractResult<QuestClaimSettlement>{executions++;executeFailure?.let{return ContractResult.Failure(it)};val r=receipt(command);value=value.copy(quests=value.quests.map{if(progressKey(it)==progressKey(r.progress))r.progress else it},journal=ProgressionJournalState.Clear,offers=emptyMap());return ContractResult.Success(r)}
  override fun cancelSaved(identity:GameIdentity,command:QuestClaimCommand):ContractResult<Unit>{cancels++;value=value.copy(journal=ProgressionJournalState.Clear);return ContractResult.Success(Unit)}
 }
 private inner class Rig {val worker=Queue();val main=Queue();val source=FakeSource();val frames=mutableListOf<ProgressionFrame>();val controller=ProgressionController(worker,main,source)
  init{controller.attach(frames::add)}
  fun drain(){repeat(20){worker.drain();main.drain()};assertTrue(worker.jobs.isEmpty());assertTrue(main.jobs.isEmpty())}
  fun start(){controller.setControlsEnabled(true);drain()}
  fun claim():ProgressEvent.ClaimReward {val item=(frames.last().screen as ProgressScreenState.Content).entries.first{it.claim?.enabled==true};return ProgressEvent.ClaimReward(item.key,item.revision,item.claim!!.requestKey)}
 }
 @Test fun sixActualQuestsUseInjectedNumbersAndMissingOtherFeaturesAreUnsupported(){
  val content=ProgressionMapper().content(data())
  assertEquals(6,content.entries.count{it.group==ProgressGroup.DAILY||it.group==ProgressGroup.WEEKLY})
  assertTrue(content.entries.filter{it.group!=ProgressGroup.ACHIEVEMENT}.all{it.rewardLabel!!.contains("50")})
  assertEquals(BuffPhase.UNSUPPORTED,content.rest!!.buffPhase);assertTrue(content.eventsNote.contains("미지원"))
  assertEquals(RewardStatus.UNSUPPORTED,content.entries.single{it.group==ProgressGroup.ACHIEVEMENT}.status)
 }
 @Test fun unknownPeriodIsNeverZeroOrClaimable(){
  val unknown=quests.map{it.copy(count=null,revision=null,status=QuestStatus.UNKNOWN)}
  val content=ProgressionMapper().content(data().copy(quests=unknown,offers=emptyMap()))
  assertTrue(content.entries.filter{it.group!=ProgressGroup.ACHIEVEMENT}.all{it.progressPercent==null&&it.claim==null&&it.progressLabel!!.contains("확인")})
 }
 @Test fun claimTerminalStateRaisesDisplayRevisionEvenWithSameBackendRevision(){
  val mapper=ProgressionMapper();val before=mapper.content(data());val c=data().offers.values.first();val after=mapper.receipt(data(),receipt(c))
  val key=progressKey(receipt(c).progress)
  assertTrue(after.sequence>before.sequence);assertTrue(after.entries.first{it.key==key}.revision>before.entries.first{it.key==key}.revision)
  assertEquals(RewardStatus.CLAIMED,after.entries.first{it.key==key}.status)
 }
 @Test fun restoredUiFloorDoesNotRestorePermissionOrPayment(){
  val mapper=ProgressionMapper();val key=progressKey(quests.first());mapper.restoreFloors(mapOf(key to 100L));val item=mapper.content(data()).entries.first{it.key==key}
  assertTrue(item.revision>100);assertEquals(RewardStatus.CLAIMABLE,item.status)
  val r=Rig();r.controller.restoreUiState(ProgressUiState(acceptedRevisions=mapOf(key to 100)));r.drain();assertEquals(0,r.source.reads);assertFalse(r.frames.last().controlsEnabled)
 }
 @Test fun missingOrForeignJournalDisablesAllNewClaims(){
  for(read in listOf(data(supported=false),data(ProgressionJournalState.Foreign))){val c=ProgressionMapper().content(read);assertTrue(c.entries.all{it.claim?.enabled!=true})}
 }
 @Test fun duplicateTapSavesAndExecutesOneWholeCommand(){
  val r=Rig();r.start();val event=r.claim();r.controller.handle(event);r.controller.handle(event);r.drain()
  assertEquals(1,r.source.saves);assertEquals(1,r.source.executions)
  assertTrue(r.frames.any{(it.screen as? ProgressScreenState.Content)?.entries?.any{p->p.key==event.key&&p.status==RewardStatus.CLAIMED}==true})
 }
 @Test fun staleUiRevisionOrRequestKeyCannotSave(){
  val r=Rig();r.start();val e=r.claim();r.controller.handle(e.copy(revision=e.revision+1));r.controller.handle(e.copy(requestKey="other"));r.drain()
  assertEquals(0,r.source.saves);assertEquals(0,r.source.executions)
 }
 @Test fun closeReopenDuringReadDoesNotExecuteOldRecoveryUntilFreshQuery(){
  val r=Rig();r.source.value=data(ProgressionJournalState.Quest(command(),ProgressionPhase.STARTED));r.controller.setControlsEnabled(true);r.worker.one()
  r.controller.setControlsEnabled(false);r.controller.setControlsEnabled(true);r.main.drain()
  assertEquals(0,r.source.executions);r.drain();assertTrue(r.source.reads>=2);assertEquals(1,r.source.executions)
 }
 @Test fun closingGateAfterSaveBeforeExecuteKeepsDurableCommandForResume(){
  val r=Rig();r.start();r.controller.handle(r.claim());r.worker.one();assertEquals(1,r.source.saves)
  r.controller.setControlsEnabled(false);r.main.drain();r.worker.drain();assertEquals(0,r.source.executions)
  r.controller.setControlsEnabled(true);r.drain();assertEquals(1,r.source.executions)
 }
 @Test fun releaseDropsLateReadAndDoesNotExecuteRecovery(){
  val r=Rig();r.source.value=data(ProgressionJournalState.Quest(command(),ProgressionPhase.STARTED));r.controller.setControlsEnabled(true);r.worker.one();r.controller.release();r.main.drain();r.worker.drain()
  assertEquals(0,r.source.executions)
 }
 @Test fun failureRetryDoesNotTreatUiPendingAsFinancialSuccess(){
  val r=Rig();r.source.failure=ContractError.DATA_UNAVAILABLE;r.start();assertTrue(r.frames.last().screen is ProgressScreenState.Failure)
  r.controller.restoreUiState(ProgressUiState(pending=ClaimReference(progressKey(quests.first()),9,"quest/claim/missing")));r.controller.handle(ProgressEvent.Retry);r.drain()
  assertEquals(0,r.source.executions);assertTrue(r.frames.last().screen is ProgressScreenState.Failure)
 }
 @Test fun actualClaimWinsOverAbsentConflictingUiReferenceForSamePeriod(){
  val read=data();val c=read.offers.values.first();val key=progressKey(quests.first());val a=ClaimReference(key,9,c.requestId.value);val b=ClaimReference(key,10,"quest/claim/absent")
  val content=ProgressionMapper().content(read.copy(resolved=linkedMapOf(a to receipt(c),b to null)))
  assertEquals(RewardStatus.CLAIMED,content.entries.first{it.key==key}.status)
 }
 @Test fun restoredPendingRevisionIsFloorForActualReceiptEvenWithoutAcceptedMap(){
  val r=Rig();val c=r.source.value.offers.values.first();val key=progressKey(quests.first());val ref=ClaimReference(key,99,c.requestId.value)
  r.source.value=r.source.value.copy(quests=r.source.value.quests.map{if(progressKey(it)==key)receipt(c).progress else it},resolved=mapOf(ref to receipt(c)))
  r.controller.restoreUiState(ProgressUiState(pending=ref));r.start()
  val item=(r.frames.last().screen as ProgressScreenState.Content).entries.first{it.key==key}
  assertEquals(RewardStatus.CLAIMED,item.status);assertTrue(item.revision>99)
 }
 @Test fun sourceProfileCannotChangeInsideSameControllerEvenAfterRetry(){
  val r=Rig();r.start();r.source.value=r.source.value.copy(identity=GameIdentity(ProfileId("other"),identity.source))
  r.controller.refresh();r.drain();assertTrue(r.frames.last().screen is ProgressScreenState.Failure)
  r.controller.refresh();r.drain();assertTrue(r.frames.last().screen is ProgressScreenState.Failure)
 }

 @Test fun loadingDoesNotPrematurelyReleaseViewRetryLock(){
  val r=Rig();r.controller.setControlsEnabled(true)
  assertTrue(r.frames.last().screen is ProgressScreenState.Loading);assertFalse(r.frames.last().retryComplete)
  r.drain();assertTrue(r.frames.last().retryComplete)
 }
 @Test fun restoringHigherUiFloorAfterRenderInvalidatesOldClaimCallback(){
  val r=Rig();r.start();val old=r.claim()
  r.controller.restoreUiState(ProgressUiState(acceptedRevisions=mapOf(old.key to old.revision+10)))
  r.controller.handle(old);r.drain();assertEquals(0,r.source.saves);assertEquals(0,r.source.executions)
 }

 @Test fun repeatedSameGateDoesNotRenderOrReadButRestrictionIsImmediate(){
  val r=Rig();val initial=r.frames.size;repeat(20){r.controller.setControlsEnabled(false)};r.drain()
  assertEquals(initial,r.frames.size);assertEquals(0,r.source.reads)
  r.start();val frames=r.frames.size;val reads=r.source.reads
  repeat(20){r.controller.setControlsEnabled(true)};r.drain();assertEquals(frames,r.frames.size);assertEquals(reads,r.source.reads)
  r.controller.setControlsEnabled(false);assertFalse(r.frames.last().controlsEnabled)
  val restricted=r.frames.size;repeat(20){r.controller.setControlsEnabled(false)};r.drain();assertEquals(restricted,r.frames.size)
 }

 @Test fun savedRejectionCanBeExplicitlyCancelledButNeverByBack(){
  val r=Rig();r.source.executeFailure=ContractError.STATE_CONFLICT;r.source.value=data(ProgressionJournalState.Quest(command(),ProgressionPhase.SAVED));r.start()
  r.controller.handle(ProgressEvent.CloseDetail);r.drain();assertEquals(0,r.source.cancels)
  r.controller.cancelSaved();r.drain();assertEquals(1,r.source.cancels)
 }
 @Test fun uncertainStartedCommandCannotBeCancelled(){
  val r=Rig();r.source.executeFailure=ContractError.STORAGE_FAILURE;r.source.value=data(ProgressionJournalState.Quest(command(),ProgressionPhase.STARTED));r.start()
  r.controller.cancelSaved();r.drain();assertEquals(0,r.source.cancels)
 }

 @Test fun laterAbsentConflictingRequestDoesNotEraseVerifiedClaimForGoal(){
  val mapper=ProgressionMapper();val read=data();val c=read.offers.values.first();val key=progressKey(quests.first())
  mapper.receipt(read,receipt(c))
  val second=mapper.content(read.copy(quests=emptyList(),offers=emptyMap(),resolved=mapOf(ClaimReference(key,9,"quest/claim/absent") to null)))
  assertEquals(RewardStatus.CLAIMED,second.entries.first{it.key==key}.status)
 }

}
