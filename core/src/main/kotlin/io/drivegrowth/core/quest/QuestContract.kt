package io.drivegrowth.core.quest
import io.drivegrowth.core.contract.*
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.Collections

enum class QuestCadence { DAILY, WEEKLY }
enum class QuestAction(val slug:String) { RESULT_VIEWED("result-viewed"), VEHICLE_LEVEL_UP("vehicle-level-up"), RENTAL_COMPLETED("rental-completed") }
data class QuestDefinition(val id:String,val version:RuleVersion,val cadence:QuestCadence,val action:QuestAction,val goal:Int,val reward:GameMoney,val zone:ZoneId,val trial:Boolean) {
 init {require(id==cadence.name.lowercase()+"-"+action.slug);require(goal>0&&reward.units>0)}
}
class QuestDefinitions(val version:RuleVersion,definitions:List<QuestDefinition>) {
 val definitions:List<QuestDefinition> = Collections.unmodifiableList(ArrayList(definitions))
 init {require(this.definitions.size==6);require(this.definitions.map{it.id}.toSet().size==6);require(this.definitions.all{it.version==version})}
}
data class QuestPeriod(val cadence:QuestCadence,val start:LocalDate,val zone:ZoneId) {
 private val bounds=try {
  EpochMillis(start.atStartOfDay(zone).toInstant().toEpochMilli()) to EpochMillis(start.plusDays(if(cadence==QuestCadence.DAILY)1 else 7).atStartOfDay(zone).toInstant().toEpochMilli())
 } catch(e:DateTimeException) {throw IllegalArgumentException("지원하지 않는 Quest 기간",e)}
   catch(e:ArithmeticException) {throw IllegalArgumentException("Quest 기간 시각 범위 초과",e)}
 init {require(cadence!=QuestCadence.WEEKLY || start.dayOfWeek==DayOfWeek.MONDAY);require(bounds.second.value>bounds.first.value)}
 val startsAt:EpochMillis get()=bounds.first
 val endsAt:EpochMillis get()=bounds.second
}
sealed interface QuestCause {
 val requestId:RequestId;val settlementId:SettlementId;val action:QuestAction
 data class ResultViewed(override val requestId:RequestId,override val settlementId:SettlementId):QuestCause {override val action=QuestAction.RESULT_VIEWED}
 data class VehicleLevelUp(override val requestId:RequestId,override val settlementId:SettlementId,val vehicleId:OwnedVehicleId,val beforeLevel:Int,val afterLevel:Int):QuestCause {
  init {require(beforeLevel in 1..20&&afterLevel in 1..20&&beforeLevel<afterLevel)};override val action=QuestAction.VEHICLE_LEVEL_UP
 }
 data class RentalCompleted(val rentalId:RequestId,override val settlementId:SettlementId):QuestCause {override val requestId get()=RequestId("rental/settle/"+rentalId.value);override val action=QuestAction.RENTAL_COMPLETED}
}
data class QuestEvent(val eventId:String,val profileId:ProfileId,val source:InputSource,val cause:QuestCause,val occurredAt:EpochMillis?) {init{require(eventId.isNotBlank());require(occurredAt==null||occurredAt.value>=0)}}
enum class QuestStatus { LOCKED, IN_PROGRESS, CLAIMABLE, CLAIMED, EXPIRED, UNKNOWN }
data class QuestProgress(val profileId:ProfileId,val source:InputSource,val definition:QuestDefinition,val period:QuestPeriod,val count:Int?,val revision:Revision?,val status:QuestStatus)
data class QuestClaimCommand(val requestId:RequestId,val profileId:ProfileId,val source:InputSource,val definition:QuestDefinition,val period:QuestPeriod,val expectedRevision:Revision) {
 init {require(requestId.value.startsWith("quest/claim/"));require(definition.cadence==period.cadence&&definition.zone==period.zone)}
}
data class QuestClaimPlan(val balance:GameMoney,val money:MoneyChange)
data class QuestClaimSettlement(val command:QuestClaimCommand,val progress:QuestProgress,val settlement:Settlement)

fun questPeriod(cadence:QuestCadence,zone:ZoneId,at:EpochMillis):QuestPeriod = QuestPeriod(cadence,Instant.ofEpochMilli(at.value).atZone(zone).toLocalDate().let{if(cadence==QuestCadence.WEEKLY)it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))else it},zone)
/** completeSince는 실제 이벤트 hook의 기록 완전성 근거이며 빈 사건 목록의 기본값이 아니다. */
fun questProgress(definition:QuestDefinition,period:QuestPeriod,profile:ProfileId,source:InputSource,events:List<QuestEvent>,now:EpochMillis?,completeSince:EpochMillis?):ContractResult<QuestProgress> {
 if(definition.cadence!=period.cadence || definition.zone!=period.zone || now?.value?.let{it<0}==true || completeSince?.value?.let{it<0}==true)
  return ContractResult.Failure(ContractError.INVALID_INPUT)
 val unique=linkedMapOf<String,QuestEvent>();val causes=mutableSetOf<Pair<QuestAction,RequestId>>()
 for(event in events) {
  if(event.profileId!=profile || event.source!=source)return ContractResult.Failure(ContractError.INVALID_INPUT)
  val previous=unique[event.eventId]
  if(previous!=null) {if(previous!=event)return ContractResult.Failure(ContractError.STATE_CONFLICT);continue}
  if(!causes.add(event.cause.action to event.cause.requestId))return ContractResult.Failure(ContractError.STATE_CONFLICT)
  unique[event.eventId]=event
 }
 val matching=unique.values.filter{it.cause.action==definition.action}
 if(now==null || completeSince==null || completeSince.value>period.startsAt.value || matching.any{it.occurredAt==null || it.occurredAt.value>now.value})
  return ContractResult.Success(QuestProgress(profile,source,definition,period,null,null,QuestStatus.UNKNOWN))
 val count=matching.count{it.occurredAt!!.value>=period.startsAt.value && it.occurredAt.value<period.endsAt.value}
 val status=when {now.value<period.startsAt.value->QuestStatus.LOCKED;now.value>=period.endsAt.value->QuestStatus.EXPIRED;count>=definition.goal->QuestStatus.CLAIMABLE;else->QuestStatus.IN_PROGRESS}
 return ContractResult.Success(QuestProgress(profile,source,definition,period,count,Revision(count),status))
}
fun prepareQuestClaim(command:QuestClaimCommand,progress:QuestProgress,balance:GameMoney,now:EpochMillis):ContractResult<QuestClaimPlan> {
 if(command.profileId!=progress.profileId || command.source!=progress.source)return ContractResult.Failure(ContractError.INVALID_INPUT)
 if(progress.status==QuestStatus.UNKNOWN || progress.count==null || progress.revision==null)return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
 if(command.definition!=progress.definition || command.period!=progress.period || command.expectedRevision!=progress.revision || progress.revision.value!=progress.count ||
  progress.count<command.definition.goal || progress.status!=QuestStatus.CLAIMABLE || now.value !in command.period.startsAt.value until command.period.endsAt.value)
  return ContractResult.Failure(ContractError.STATE_CONFLICT)
 return try {ContractResult.Success(QuestClaimPlan(GameMoney(Math.addExact(balance.units,command.definition.reward.units)),MoneyChange(MoneyDirection.CREDIT,command.definition.reward)))}
 catch(_:ArithmeticException){ContractResult.Failure(ContractError.INVALID_INPUT)}
}

/** 공용 store의 capability. 실제 원인 기록은 app의 trusted 같은 거래 hook에서만 제공한다. */
interface QuestChangeStore {
 fun read(id:ProfileId,at:Observation<EpochMillis>):ContractResult<List<QuestProgress>>
 fun claim(command:QuestClaimCommand):ContractResult<QuestClaimSettlement>
 fun claimedRequestIds(id:ProfileId):ContractResult<Set<RequestId>>
}
