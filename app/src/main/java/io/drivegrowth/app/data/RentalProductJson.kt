package io.drivegrowth.app.data

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.*
import io.drivegrowth.app.runtime.rental.product.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import org.json.*
import java.math.RoundingMode

internal object RentalProductJson {
    fun n(j:JSONObject,k:String,min:Long=0)=LedgerJson.integer(j,k,min,Long.MAX_VALUE)
    private fun int(j:JSONObject,k:String,min:Long=0)=LedgerJson.integer(j,k,min,Int.MAX_VALUE.toLong()).toInt()
    private fun boolean(j:JSONObject,k:String)=j.get(k) as? Boolean ?: throw JSONException("영업 boolean 형식")
    fun clock(c:RentalClock)=JSONObject().put("boot",c.bootId).put("epoch",c.epoch.value).put("elapsed",c.elapsedMs)
    fun clock(j:JSONObject)=RentalClock(j.getString("boot").also{require(it.isNotBlank())},EpochMillis(n(j,"epoch")),n(j,"elapsed"))
    fun cursor(c:RentalVisitCursor)=JSONObject().put("revision",c.revision.value).put("sequence",c.sequence).put("next",clock(c.nextAt))
    fun cursor(j:JSONObject)=RentalVisitCursor(Revision(int(j,"revision")),n(j,"sequence"),clock(j.getJSONObject("next")))
    fun prepared(p:PreparedRentalVisit):JSONObject {
        val v=p.visit;val terms=v.terms
        val cars=JSONArray().apply{v.catalog.vehicles.forEach{put(JSONObject().put("id",it.vehicleId.value).put("name",it.displayName))}}
        val categories=JSONArray().apply{v.categories.entries.sortedBy{it.key.value}.forEach{put(JSONObject().put("id",it.key.value).put("value",it.value.name))}}
        val rates=JSONArray().apply{v.hourlyRates.entries.sortedBy{it.key.value}.forEach{put(JSONObject().put("id",it.key.value).put("value",it.value.units))}}
        return JSONObject().put("type","rental-prepared-v1").put("profile",p.identity.profileId.value).put("source",p.identity.source.name).put("definition",p.definitionVersion.value).put("trial",p.trial)
            .put("expected",cursor(p.expected)).put("next",cursor(p.next)).put("request",v.request.id.value).put("desired",v.request.desired.value).put("hours",v.request.hours)
            .put("catalogVersion",v.catalog.contentVersion.value).put("catalog",cars).put("categories",categories).put("rates",rates)
            .put("rules",terms.rules.value).put("age",terms.customer.age).put("licensed",terms.customer.licensedMonths).put("roll",terms.roll).put("start",terms.start.value)
            .put("return",terms.returnMinutes).put("fuel",terms.fuelBonus.units).put("rounding",terms.rounding?.name?:JSONObject.NULL).put("regular",terms.regularCustomer?:JSONObject.NULL)
    }
    fun prepared(j:JSONObject):PreparedRentalVisit {
        require(j.getString("type")=="rental-prepared-v1")
        val source=InputSource.valueOf(j.getString("source"));val catalog=j.getJSONArray("catalog");val categories=j.getJSONArray("categories");val rates=j.getJSONArray("rates")
        val cars=List(catalog.length()){catalog.getJSONObject(it).let{r->VehicleDefinition(VehicleId(r.getString("id")),r.getString("name"))}}
        require(cars.isNotEmpty()&&cars.map{it.vehicleId}.toSet().size==cars.size)
        fun <T> entries(array:JSONArray,f:(JSONObject)->T):Map<VehicleId,T> {val result=linkedMapOf<VehicleId,T>();for(i in 0 until array.length()){val r=array.getJSONObject(i);require(result.put(VehicleId(r.getString("id")),f(r))==null)};return result}
        val visit=RentalVisit(source,RentalRequest(RequestId(j.getString("request")),VehicleId(j.getString("desired")),int(j,"hours")),VehicleCatalogSnapshot(ContentVersion(j.getString("catalogVersion")),cars),
            entries(categories){RentalCategory.valueOf(it.getString("value"))},entries(rates){GameMoney(n(it,"value"))},
            RentalTerms(RuleVersion(j.getString("rules")),SimulatedCustomer(int(j,"age"),int(j,"licensed")),int(j,"roll"),EpochMillis(n(j,"start")),int(j,"return"),GameMoney(n(j,"fuel")),if(j.isNull("rounding"))null else RoundingMode.valueOf(j.getString("rounding")),if(j.isNull("regular"))null else boolean(j,"regular")))
        return PreparedRentalVisit(GameIdentity(ProfileId(j.getString("profile")),source),ContentVersion(j.getString("definition")),boolean(j,"trial"),cursor(j.getJSONObject("expected")),cursor(j.getJSONObject("next")),visit)
    }
    fun interval(i:RentalInactiveInterval)=JSONObject().put("marker",i.marker.value).put("since",clock(i.originalSince)).put("from",clock(i.from)).put("to",clock(i.to))
        .apply { if (i.forwardWallJump) put("forwardWallJump", true) }
    fun interval(j:JSONObject)=RentalInactiveInterval(RequestId(j.getString("marker")),clock(j.getJSONObject("since")),clock(j.getJSONObject("from")),clock(j.getJSONObject("to")),
        j.has("forwardWallJump") && boolean(j,"forwardWallJump"))
}
