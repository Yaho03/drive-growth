package io.drivegrowth.core.quest
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class QuestContractTest {
 private val p=ProfileId("profile");private val source=InputSource.SIMULATION
 private val at=EpochMillis(Instant.parse("2026-11-10T03:00:00Z").toEpochMilli())
 private val zone=ZoneId.of("Asia/Seoul")
 private val d=QuestDefinition("daily-result-viewed",RuleVersion("trial"),QuestCadence.DAILY,QuestAction.RESULT_VIEWED,2,GameMoney(50),zone,true)
 private val period=QuestPeriod(QuestCadence.DAILY,LocalDate.parse("2026-11-10"),zone)
 private fun event(id:String="one",time:EpochMillis?=at)=QuestEvent(id,p,source,QuestCause.ResultViewed(RequestId(id),SettlementId("s-$id")),time)
 private fun <T> ok(r:ContractResult<T>):T {assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
 private fun progress(events:List<QuestEvent>,now:EpochMillis?=at)=ok(questProgress(d,period,p,source,events,now,EpochMillis(0)))
 private fun command(rev:Int=2)=QuestClaimCommand(RequestId("quest/claim/one"),p,source,d,period,Revision(rev))
 @Test fun koreanDayAndMondayPeriodUseExactStartAndExclusiveEnd(){
  val t=EpochMillis(Instant.parse("2026-11-08T15:00:00Z").toEpochMilli())
  val daily=questPeriod(QuestCadence.DAILY,zone,t);val weekly=questPeriod(QuestCadence.WEEKLY,zone,t)
  assertEquals(LocalDate.parse("2026-11-09"),daily.start);assertEquals(daily.start,weekly.start)
  assertEquals(t,daily.startsAt);assertEquals(EpochMillis(Instant.parse("2026-11-15T15:00:00Z").toEpochMilli()),weekly.endsAt)
 }
 @Test fun sameEventReplayCountsOnceAndDifferentMeaningConflicts(){
  val e=event();assertEquals(1,progress(listOf(e,e)).count)
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),questProgress(d,period,p,source,listOf(e,e.copy(occurredAt=EpochMillis(at.value+1))),at,EpochMillis(0)))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),questProgress(d,period,p,source,listOf(e,e.copy(eventId="alias")),at,EpochMillis(0)))
 }
 @Test fun oneConfirmedCauseFeedsDailyAndWeeklyWithoutDuplicateProgress(){
  val es=listOf(event(),event("two"));val daily=progress(es)
  val weekly=d.copy(id="weekly-result-viewed",cadence=QuestCadence.WEEKLY)
  val ws=ok(questProgress(weekly,questPeriod(QuestCadence.WEEKLY,zone,at),p,source,es,at,EpochMillis(0)))
  assertEquals(2,daily.count);assertEquals(2,ws.count);assertEquals(QuestStatus.CLAIMABLE,daily.status)
 }
 @Test fun unknownTimeIsNotZeroAndForeignSourceCannotAdvance(){
  val s=progress(listOf(event(time=null)));assertEquals(QuestStatus.UNKNOWN,s.status);assertNull(s.count);assertNull(s.revision)
  assertEquals(QuestStatus.UNKNOWN,progress(emptyList(),null).status)
  assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),questProgress(d,period,p,source,listOf(event().copy(source=InputSource.VEHICLE)),at,EpochMillis(0)))
 }
 @Test fun previousPeriodDoesNotCountAndDeadlineRejectsNewClaim(){
  assertEquals(0,progress(listOf(event(time=EpochMillis(period.startsAt.value-1)))).count)
  val partial=ok(questProgress(d,period,p,source,emptyList(),at,at));assertEquals(QuestStatus.UNKNOWN,partial.status);assertNull(partial.count)
  val state=progress(listOf(event(),event("two")));assertEquals(QuestStatus.EXPIRED,progress(listOf(event(),event("two")),period.endsAt).status)
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepareQuestClaim(command(),state,GameMoney(100),period.endsAt))
 }
 @Test fun exactClaimUsesWholeDefinitionAndRevisionAndDoesNotOverflow(){
  val state=progress(listOf(event(),event("two")))
  val plan=ok(prepareQuestClaim(command(),state,GameMoney(100),at));assertEquals(GameMoney(150),plan.balance);assertEquals(MoneyChange(MoneyDirection.CREDIT,GameMoney(50)),plan.money)
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepareQuestClaim(command(1),state,GameMoney(100),at))
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepareQuestClaim(command().copy(definition=d.copy(reward=GameMoney(51))),state,GameMoney(100),at))
  assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),prepareQuestClaim(command(),state,GameMoney(Long.MAX_VALUE),at))
 }
 @Test fun incompleteAndUnknownCannotClaim(){
  assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),prepareQuestClaim(command(1),progress(listOf(event())),GameMoney(100),at))
  assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),prepareQuestClaim(command(),progress(listOf(event(time=null))),GameMoney(100),at))
 }
 @Test fun periodOutsideRepresentableEpochRangeIsRejectedAtBoundary(){
  assertThrows(IllegalArgumentException::class.java){QuestPeriod(QuestCadence.DAILY,LocalDate.MAX,ZoneId.of("UTC"))}
 }

}
