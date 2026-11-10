package io.drivegrowth.app.data

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.driving.*
import io.drivegrowth.core.Gear
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.*
import org.json.*
import java.math.BigDecimal
import java.time.ZoneId
import java.util.Collections

/** 원시 관측의 double bits, decimal scale, 출발 달력과 권위 실패를 보존한다. */
internal object ObservedDrivingJson {
    private fun zone(raw:String)=try{ZoneId.of(raw)}catch(e:java.time.DateTimeException){throw JSONException("관측 시간대 형식").apply{initCause(e)}}
    private fun n(j:JSONObject,k:String,min:Long=0)=LedgerJson.integer(j,k,min,Long.MAX_VALUE)
    private fun bool(j:JSONObject,k:String)=j.get(k) as? Boolean ?: throw JSONException("관측 boolean 형식")
    private fun <T> obs(v:Observation<T>,encode:(T)->Any):JSONObject=when(v){is Observation.Available->JSONObject().put("type","available").put("value",encode(v.value));is Observation.Missing->JSONObject().put("type","missing").put("reason",v.reason.name)}
    private fun <T> obs(j:JSONObject,decode:(JSONObject)->T):Observation<T> = when(j.getString("type")){"available"->Observation.Available(decode(j));"missing"->Observation.Missing(MissingReason.valueOf(j.getString("reason")));else->throw JSONException("관측 종류")}
    fun observation(v:VehicleObservation)=JSONObject().put("source",v.source.name).put("elapsed",v.receivedElapsedMs).put("utc",v.receivedAt.value)
        .put("speed",obs(v.speedMps){java.lang.Long.toUnsignedString(it.toRawBits(),16)}).put("gear",obs(v.gear){it.name}).put("ux",obs(v.uxRestricted){it})
    fun observation(j:JSONObject)=VehicleObservation(InputSource.valueOf(j.getString("source")),n(j,"elapsed"),EpochMillis(n(j,"utc",Long.MIN_VALUE)),
        obs(j.getJSONObject("speed")){val raw=it.getString("value");require(raw.matches(Regex("[0-9a-f]{1,16}")));Double.fromBits(java.lang.Long.parseUnsignedLong(raw,16))},
        obs(j.getJSONObject("gear")){Gear.valueOf(it.getString("value"))},obs(j.getJSONObject("ux")){bool(it,"value")})
    private fun clock(v:RecordingClock)=JSONObject().put("elapsed",v.elapsed).put("utc",v.utc.value)
    private fun clock(j:JSONObject)=RecordingClock(n(j,"elapsed"),EpochMillis(n(j,"utc",Long.MIN_VALUE)))
    private fun rules(v:ContractResult<DrivingIncomeRules>):JSONObject=when(v){is ContractResult.Failure->JSONObject().put("error",v.error.name);is ContractResult.Success->JSONObject().put("version",v.value.ruleVersion.value).put("cap",v.value.dailyCapMeters).put("rate",v.value.incomePerKm.units)}
    private fun rules(j:JSONObject):ContractResult<DrivingIncomeRules> = if(j.has("error"))ContractResult.Failure(ContractError.valueOf(j.getString("error"))) else ContractResult.Success(DrivingIncomeRules(RuleVersion(j.getString("version")),n(j,"cap"),GameMoney(n(j,"rate"))).also{require(it.dailyCapMeters==500000L)})
    private fun decimal(v:BigDecimal)=JSONObject().put("plain",v.toPlainString()).put("scale",v.scale())
    private fun decimal(j:JSONObject):BigDecimal {
        val raw=j.getString("plain");val scale=LedgerJson.integer(j,"scale",Int.MIN_VALUE.toLong(),Int.MAX_VALUE.toLong()).toInt()
        require(scale<=raw.length);val value=BigDecimal(raw);require(value.signum()>=0)
        require(value.signum()==0 || scale>= -raw.length)
        return try{value.setScale(scale,java.math.RoundingMode.UNNECESSARY).also{require(it.toPlainString()==raw)}}catch(e:ArithmeticException){throw JSONException("관측 decimal scale 불일치").apply{initCause(e)}}
    }
    private fun <T> array(items:List<T>,f:(T)->JSONObject)=JSONArray().apply{items.forEach{put(f(it))}}
    private fun <T> items(j:JSONArray,f:(JSONObject)->T):List<T> = Collections.unmodifiableList(List(j.length()){f(j.getJSONObject(it))})
    fun trip(t:ObservedTrip):JSONObject=JSONObject().put("id",t.id).put("departure",observation(t.departure)).put("zone",obs(t.zone){it.id}).put("rules",rules(t.rules)).put("meters",decimal(t.meters))
        .put("segments",array(t.segments){JSONObject().put("from",observation(it.from)).put("to",observation(it.to)).put("meters",decimal(it.meters))})
        .put("gaps",array(t.gaps){JSONObject().put("reason",it.reason.name).put("from",it.from?.let(::clock)?:JSONObject.NULL).put("to",clock(it.to)).put("observation",it.observation?.let(::observation)?:JSONObject.NULL)})
    fun trip(j:JSONObject):ObservedTrip=ObservedTrip(j.getString("id"),observation(j.getJSONObject("departure")),obs(j.getJSONObject("zone")){zone(it.getString("value"))},rules(j.getJSONObject("rules")),decimal(j.getJSONObject("meters")),
        items(j.getJSONArray("segments")){DrivingSegment(observation(it.getJSONObject("from")),observation(it.getJSONObject("to")),decimal(it.getJSONObject("meters")))},
        items(j.getJSONArray("gaps")){DrivingGap(DrivingGapReason.valueOf(it.getString("reason")),if(it.isNull("from"))null else clock(it.getJSONObject("from")),clock(it.getJSONObject("to")),if(it.isNull("observation"))null else observation(it.getJSONObject("observation")))})
    fun state(v:RecordingState):JSONObject=JSONObject().put("type","observed-driving-v1").put("profile",v.identity.profileId.value).put("source",v.identity.source.name).put("revision",v.revision)
        .put("receiver",v.receiver?:JSONObject.NULL).put("trip",v.trip?.let(::trip)?:JSONObject.NULL).put("anchor",v.anchor?.let(::observation)?:JSONObject.NULL)
        .put("boundary",v.boundary?.let(::clock)?:JSONObject.NULL).put("parkSince",v.parkSince?:JSONObject.NULL).put("outbox",array(v.outbox,::trip))
    fun state(j:JSONObject):RecordingState {
        require(j.getString("type")=="observed-driving-v1")
        return RecordingState(GameIdentity(ProfileId(j.getString("profile")),InputSource.valueOf(j.getString("source"))),n(j,"revision"),if(j.isNull("receiver"))null else j.getString("receiver").also{require(it.isNotBlank())},
            if(j.isNull("trip"))null else trip(j.getJSONObject("trip")),if(j.isNull("anchor"))null else observation(j.getJSONObject("anchor")),if(j.isNull("boundary"))null else clock(j.getJSONObject("boundary")),if(j.isNull("parkSince"))null else n(j,"parkSince"),items(j.getJSONArray("outbox"),::trip))
    }
    fun receipt(v:ObservedDrivingReceipt):JSONObject=JSONObject().put("trip",trip(v.trip)).put("drive",v.commit.driveId).put("recorded",v.commit.recordedMeters).put("rewarded",v.commit.rewardedMeters).put("amount",v.commit.amount.units)
        .put("day",v.commit.day.day.toString()).put("dayRewarded",v.commit.day.rewardedMeters).put("settlement",v.commit.settlement?.let(LedgerJson::settlement)?:JSONObject.NULL)
    fun receipt(j:JSONObject)=ObservedDrivingReceipt(trip(j.getJSONObject("trip")),DriveCommit(j.getString("drive"),n(j,"recorded"),n(j,"rewarded"),GameMoney(n(j,"amount")),DrivingDay(DeliveryJson.date(j.getString("day")),n(j,"dayRewarded")),if(j.isNull("settlement"))null else LedgerJson.settlement(j.getJSONObject("settlement"))))
    fun digest(raw:String)=java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
}
