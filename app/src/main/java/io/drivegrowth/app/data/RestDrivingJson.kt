package io.drivegrowth.app.data

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.rest.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.json.*
import java.util.Collections

/** 기존 관측/Core Rest codec를 재사용한 전체 입력. 위치 Missing을 장소/0으로 바꾸지 않는다. */
internal object RestDrivingJson {
 private fun n(j:JSONObject,k:String,min:Long=0)=LedgerJson.integer(j,k,min,Long.MAX_VALUE)
 private fun boolean(j:JSONObject,k:String)=j.get(k) as? Boolean?:throw JSONException("휴식 boolean 형식")
 private fun bits(v:Double)=java.lang.Long.toUnsignedString(v.toRawBits(),16)
 private fun double(j:JSONObject,k:String):Double {val s=j.getString(k);require(s.matches(Regex("[0-9a-f]{1,16}")));return Double.fromBits(java.lang.Long.parseUnsignedLong(s,16)).also{require(it.isFinite())}}
 fun policy(p:RestEvidencePolicy)=JSONObject().put("gap",p.maxGapMs).put("locationAge",p.maxLocationAgeMs).put("utcSkew",p.maxUtcSkewMs).put("moving",bits(p.movingMps)).put("stationary",bits(p.stationaryMps))
 fun policy(j:JSONObject)=RestEvidencePolicy(n(j,"gap",1),n(j,"locationAge",1),n(j,"utcSkew"),double(j,"moving"),double(j,"stationary"))
 private fun place(p:Observation<RestAreaProof>):JSONObject=when(p) {
  is Observation.Missing->JSONObject().put("type","missing").put("reason",p.reason.name)
  is Observation.Available->with(p.value){JSONObject().put("type","available").put("id",areaId).put("kind",kind.name).put("dataset",dataset.value).put("url",sourceUrl).put("licence",licence).put("updated",updatedOn).put("source",source.name).put("inside",inside).put("elapsed",elapsedMs).put("utc",utc.value)}
 }
 private fun place(j:JSONObject):Observation<RestAreaProof> = when(j.getString("type")) {
  "missing"->Observation.Missing(MissingReason.valueOf(j.getString("reason")))
  "available"->Observation.Available(RestAreaProof(j.getString("id"),RestPlace.valueOf(j.getString("kind")),ContentVersion(j.getString("dataset")),j.getString("url"),j.getString("licence"),j.getString("updated"),InputSource.valueOf(j.getString("source")),boolean(j,"inside"),n(j,"elapsed"),EpochMillis(n(j,"utc"))))
  else->throw JSONException("휴식 장소 관측 종류")
 }
 fun span(s:RestObservedSpan)=JSONObject().put("boot",s.bootId).put("from",ObservedDrivingJson.observation(s.from)).put("to",ObservedDrivingJson.observation(s.to)).put("fromPlace",place(s.fromPlace)).put("toPlace",place(s.toPlace))
 fun span(j:JSONObject)=RestObservedSpan(j.getString("boot").also{require(it.isNotBlank())},ObservedDrivingJson.observation(j.getJSONObject("from")),ObservedDrivingJson.observation(j.getJSONObject("to")),place(j.getJSONObject("fromPlace")),place(j.getJSONObject("toPlace")))
 private fun spans(values:List<RestObservedSpan>)=JSONArray().apply{values.forEach{put(span(it))}}
 private fun spans(j:JSONArray):List<RestObservedSpan> = Collections.unmodifiableList(List(j.length()){span(j.getJSONObject(it))})
 fun proof(p:RestProof)=JSONObject().put("profile",p.identity.profileId.value).put("source",p.identity.source.name).put("stop",p.stopNumber).put("drive",p.driveId).put("driving",spans(p.driving)).put("parked",spans(p.parked)).put("departure",span(p.departure))
 fun proof(j:JSONObject)=RestProof(GameIdentity(ProfileId(j.getString("profile")),InputSource.valueOf(j.getString("source"))),n(j,"stop"),j.getString("drive").also{require(it.isNotBlank())},spans(j.getJSONArray("driving")),spans(j.getJSONArray("parked")),span(j.getJSONObject("departure")))
 fun context(c:RestDriveContext)=JSONObject().put("type","rest-context-v1").put("profile",c.identity.profileId.value).put("source",c.identity.source.name)
  .put("trip",ObservedDrivingJson.trip(c.trip)).put("rules",RestJson.rules(c.restRules)).put("pricing",c.pricingPolicy.id)
  .put("departureClock",clock(c.departureClock))
  .put("segments",JSONArray().apply{c.segments.forEach{put(JSONObject().put("index",it.index).put("domain",it.clockDomain).put("span",span(it.span)).put("policy",policy(it.policy)))}})
 fun context(j:JSONObject):RestDriveContext {
  require(j.getString("type")=="rest-context-v1")
  val pricing=RestPricingPolicy.entries.singleOrNull{it.id==j.getString("pricing")}?:throw JSONException("휴식 계산 정책")
  val a=j.getJSONArray("segments")
  return RestDriveContext(GameIdentity(ProfileId(j.getString("profile")),InputSource.valueOf(j.getString("source"))),ObservedDrivingJson.trip(j.getJSONObject("trip")),RestJson.rules(j.getJSONObject("rules")),pricing,
   Collections.unmodifiableList(List(a.length()){val v=a.getJSONObject(it);RestSegmentEvidence(LedgerJson.integer(v,"index",0,Int.MAX_VALUE.toLong()).toInt(),v.getString("domain").also{d->require(d.isNotBlank())},span(v.getJSONObject("span")),policy(v.getJSONObject("policy")))}),clock(j.getJSONObject("departureClock")))
 }
 private fun clock(c:RestClockDomain)=JSONObject().put("domain",c.domain).put("boot",c.bootId)
 private fun clock(j:JSONObject)=RestClockDomain(j.getString("domain"),j.getString("boot"))
 fun anchored(d:RestAnchoredDeparture)=JSONObject().put("type","rest-departure-v2").put("request",d.requestId.value).put("segment",d.segment)
  .put("proof",proof(d.packet.proof)).put("rules",RestJson.rules(d.packet.rules)).put("policy",policy(d.packet.policy)).put("clock",clock(d.packet.clock))
 fun anchored(j:JSONObject):RestAnchoredDeparture {
  require(j.getString("type")=="rest-departure-v2")
  return RestAnchoredDeparture(RequestId(j.getString("request")),LedgerJson.integer(j,"segment",-1,Int.MAX_VALUE.toLong()).toInt(),RestDeparturePacket(proof(j.getJSONObject("proof")),RestJson.rules(j.getJSONObject("rules")),policy(j.getJSONObject("policy")),clock(j.getJSONObject("clock"))))
 }
 fun grant(g:RestActiveGrant)=JSONObject().put("stop",g.stopNumber).put("rules",RestJson.rules(g.rules))
 fun grant(j:JSONObject)=RestActiveGrant(n(j,"stop"),RestJson.rules(j.getJSONObject("rules")))
 fun priced(c:RestPricedCommand)=JSONObject().put("type","driving-rest-v1").put("context",context(c.context))
  .put("departures",JSONArray().apply{c.departures.forEach{put(anchored(it))}}).put("before",RestJson.state(c.before))
  .put("grantBefore",c.grantBefore?.let(::grant)?:JSONObject.NULL).put("dayBefore",c.dayBefore?.let{JSONObject().put("day",it.day.toString()).put("rewarded",it.rewardedMeters)}?:JSONObject.NULL).put("ordinal",c.ordinal)
 fun priced(j:JSONObject):RestPricedCommand {
  require(j.getString("type")=="driving-rest-v1");val a=j.getJSONArray("departures")
  val day=if(j.isNull("dayBefore"))null else j.getJSONObject("dayBefore").let{DrivingDay(DeliveryJson.date(it.getString("day")),n(it,"rewarded"))}
  return RestPricedCommand(context(j.getJSONObject("context")),Collections.unmodifiableList(List(a.length()){anchored(a.getJSONObject(it))}),RestJson.state(j.getJSONObject("before")),if(j.isNull("grantBefore"))null else grant(j.getJSONObject("grantBefore")),day,n(j,"ordinal",1))
 }
 private fun distance(f:RestDistanceFraction)=JSONObject().put("numerator",f.numerator.toString()).put("denominator",f.denominator.toString())
 fun receipt(c:RestPricedCommand,r:RestPriceCalculation,commit:DriveCommit):JSONObject = JSONObject().put("type","rest-driving-receipt-v1").put("command",priced(c))
  .put("observed",ObservedDrivingJson.receipt(io.drivegrowth.app.host.driving.ObservedDrivingReceipt(c.context.trip,commit)))
  .put("after",RestJson.state(r.timeline.after)).put("grantAfter",r.timeline.activeGrant?.let(::grant)?:JSONObject.NULL)
  .put("pricing",JSONObject().put("policy",r.price.policyVersion).put("boostedMs",r.price.boostedMs).put("slices",JSONArray().apply{r.price.slices.forEach{v->put(JSONObject().put("segment",v.window.segmentIndex).put("from",v.window.fromOffsetMs).put("until",v.window.untilOffsetMs).put("percent",v.window.bonusPercent).put("rawMeters",distance(v.rawMeters)).put("rewardedMeters",distance(v.rewardedMeters)))}}))
 fun command(c:RestDrivingCommand)=JSONObject().put("type","rest-driving-v1").put("profile",c.identity.profileId.value).put("source",c.identity.source.name)
  .put("drive",c.drive.driveId).put("day",c.drive.departedOn.toString()).put("meters",c.drive.distance.meters).put("drivingRules",JSONObject().put("rule",c.drivingRules.ruleVersion.value).put("cap",c.drivingRules.dailyCapMeters).put("rate",c.drivingRules.incomePerKm.units))
  .put("restRules",RestJson.rules(c.restRules)).put("policy",policy(c.evidencePolicy)).put("departure",c.departure?.let(::proof)?:JSONObject.NULL).put("consume",c.consumeId).put("afterStop",c.afterStop?:JSONObject.NULL).put("spans",spans(c.spans))
 fun command(j:JSONObject):RestDrivingCommand {
  require(j.getString("type")=="rest-driving-v1");val r=j.getJSONObject("drivingRules");val source=InputSource.valueOf(j.getString("source"))
  return RestDrivingCommand(GameIdentity(ProfileId(j.getString("profile")),source),ConfirmedDrive(j.getString("drive"),DeliveryJson.date(j.getString("day")),ObservedDistance(n(j,"meters"),source)),
   DrivingIncomeRules(RuleVersion(r.getString("rule")),n(r,"cap",1),GameMoney(n(r,"rate"))),RestJson.rules(j.getJSONObject("restRules")),policy(j.getJSONObject("policy")),if(j.isNull("departure"))null else proof(j.getJSONObject("departure")),j.getString("consume").also{require(it.isNotBlank())},if(j.isNull("afterStop"))null else n(j,"afterStop"),spans(j.getJSONArray("spans")))
 }
}
