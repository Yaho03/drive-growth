package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import org.json.JSONArray
import org.json.JSONObject

/** F 공통 모델의 canonical 보존 형식. 현재 정의로 과거 구매를 재계산하지 않는다. */
internal object DecorationJson {
    private fun int(j:JSONObject,k:String,min:Long=0)=LedgerJson.integer(j,k,min,Int.MAX_VALUE.toLong()).toInt()
    private fun nullableString(j:JSONObject,k:String):String?=when(val value=j.get(k)) {
        JSONObject.NULL->null
        is String->value
        else->throw SQLiteException("꾸미기 선택 문자열 형식")
    }
    private fun boolean(j:JSONObject,k:String)=j.get(k) as? Boolean?:throw SQLiteException("꾸미기 시험값 형식")
    fun command(c:DecorationCommand)=JSONObject().put("request",c.requestId.value).put("profile",c.profileId.value)
        .put("source",c.source.name).put("item",c.decorationId).put("action",c.action.name).put("rule",c.ruleVersion.value)
        .put("revision",c.expectedRevision.value).put("price",c.expectedPrice.units)
    fun command(j:JSONObject)=DecorationCommand(RequestId(j.getString("request")),ProfileId(j.getString("profile")),InputSource.valueOf(j.getString("source")),
        j.getString("item"),DecorationAction.valueOf(j.getString("action")),RuleVersion(j.getString("rule")),Revision(int(j,"revision")),GameMoney(LedgerJson.integer(j,"price",0,Long.MAX_VALUE)))
    fun state(s:DecorationState)=JSONObject().put("profile",s.profileId.value).put("source",s.source.name).put("revision",s.revision.value)
        .put("balance",s.balance.units).put("shopLevel",s.shopLevel).put("owned",JSONArray(s.ownedIds.sorted())).put("selected",s.selectedId?:JSONObject.NULL)
    private fun state(j:JSONObject):DecorationState {
        val owned=j.getJSONArray("owned");val ids=buildSet{for(i in 0 until owned.length())add(owned.getString(i))}
        return DecorationState(ProfileId(j.getString("profile")),InputSource.valueOf(j.getString("source")),Revision(int(j,"revision")),
            GameMoney(LedgerJson.integer(j,"balance",0,Long.MAX_VALUE)),int(j,"shopLevel",1),ids,nullableString(j,"selected"))
    }
    private fun definitions(d:DecorationDefinitions)=JSONObject().put("rule",d.ruleVersion.value).put("trial",d.trial)
        .put("items",JSONArray(d.items.map{JSONObject().put("id",it.id).put("theme",it.themeKey).put("name",it.displayName).put("level",it.requiredShopLevel).put("price",it.price.units)}))
    private fun definitions(j:JSONObject):DecorationDefinitions {
        val items=j.getJSONArray("items")
        return DecorationDefinitions(RuleVersion(j.getString("rule")),boolean(j,"trial"),(0 until items.length()).map{i->val item=items.getJSONObject(i)
            DecorationDefinition(item.getString("id"),item.getString("theme"),item.getString("name"),int(item,"level",1),GameMoney(LedgerJson.integer(item,"price",0,Long.MAX_VALUE)))})
    }
    fun module(profile:ProfileId,source:InputSource,state:DecorationState?)=JSONObject().put("type","decoration-module-v1")
        .put("profile",profile.value).put("source",source.name).put("state",state?.let(::state)?:JSONObject.NULL).toString()
    fun module(raw:String,profile:ProfileId,source:InputSource):DecorationState? {
        val j=JSONObject(raw)
        if(j.getString("type")!="decoration-module-v1"||j.getString("profile")!=profile.value||j.getString("source")!=source.name)throw SQLiteException("꾸미기 도입 권위 손상")
        val s=when(val value=j.get("state")){JSONObject.NULL->null;is JSONObject->state(value);else->throw SQLiteException("꾸미기 모듈 상태 형식")}
        if(s?.let{it.profileId!=profile||it.source!=source}==true||module(profile,source,s)!=raw)throw SQLiteException("꾸미기 모듈 전체 원문 불일치")
        return s
    }
    fun record(c:DecorationCommand,before:DecorationState,d:DecorationDefinitions,r:DecorationSettlement)=JSONObject().put("type","decoration-result-v1")
        .put("command",command(c)).put("before",state(before)).put("definitions",definitions(d)).put("after",state(r.state))
        .put("settlement",r.settlement?.let(LedgerJson::settlement)?:JSONObject.NULL).toString()
    fun record(db:SQLiteDatabase,raw:String,profile:ProfileId,source:InputSource):Pair<DecorationState,DecorationSettlement> {
        val j=JSONObject(raw)
        if(j.getString("type")!="decoration-result-v1")throw SQLiteException("꾸미기 원결과 형식")
        val c=command(j.getJSONObject("command"));val before=state(j.getJSONObject("before"));val d=definitions(j.getJSONObject("definitions"));val after=state(j.getJSONObject("after"))
        val receipt=when(val value=j.get("settlement")) {
            JSONObject.NULL->null
            is JSONObject->{LedgerJson.integer(value,"balance",0,Long.MAX_VALUE);LedgerJson.integer(value,"at",Long.MIN_VALUE,Long.MAX_VALUE);LedgerJson.settlement(value)}
            else->throw SQLiteException("꾸미기 금융 참조 형식")
        }
        val result=DecorationSettlement(c,after,receipt)
        if(c.profileId!=profile||c.source!=source||record(c,before,d,result)!=raw)throw SQLiteException("꾸미기 원결과 전체 원문 불일치")
        val planned=(prepareDecorationChange(c,before,d) as? ContractResult.Success)?.value?:throw SQLiteException("꾸미기 당시 조건 손상")
        if(planned.state!=after||replayDecorationChange(c,result)!=ContractResult.Success(result))throw SQLiteException("꾸미기 당시 변경/결과 불일치")
        if(planned.money==null) {
            if(db.rawQuery("SELECT 1 FROM settlements WHERE request_id=?",arrayOf(c.requestId.value)).use{it.moveToFirst()})throw SQLiteException("비금융 꾸미기의 잘못된 정산")
        } else {
            val change=GameChange(c.requestId,TransactionKind.FACILITY,c.source,c.ruleVersion,planned.money)
            if(readRecordedSettlement(db,change)!=receipt)throw SQLiteException("꾸미기 최초 공용 정산 소실/불일치")
            val financial=receipt?:throw SQLiteException("꾸미기 최초 공용 정산 누락")
            db.rawQuery("SELECT result FROM settlements WHERE request_id=?",arrayOf(c.requestId.value)).use {
                if(!it.moveToFirst()||it.text(0)!=LedgerJson.settlement(financial).toString())throw SQLiteException("꾸미기 공용 금융 원문 불일치")
            }
        }
        return before to result
    }
}
