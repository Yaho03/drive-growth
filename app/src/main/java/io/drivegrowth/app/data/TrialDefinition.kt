package io.drivegrowth.app.data

import io.drivegrowth.app.host.shop.ShopExpansionDefinition
import io.drivegrowth.app.host.shop.parseShopExpansionDefinition
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import java.util.Collections

/** 숫자 입력만 공급한다. Quest 진행/기간/청구 가능 여부를 만들지 않는다. */
data class TrialQuestNumbers(val goal: Int, val reward: GameMoney) {
    init { require(goal>0 && reward.units>0) }
}
class TrialDefinition internal constructor(
    val expansions: ShopExpansionDefinition, rates: Map<VehicleId,GameMoney>, facilities: Map<Facility,FacilityOffer>,
    val fuelBonusPerRental: GameMoney, quests: Map<String,TrialQuestNumbers>, provenance: Map<String,String>,
) {
    val version get()=expansions.version
    val rules get()=expansions.rules
    val trial get()=expansions.trial
    val rentalRates: Map<VehicleId,GameMoney> = Collections.unmodifiableMap(LinkedHashMap(rates))
    val facilityOffers: Map<Facility,FacilityOffer> = Collections.unmodifiableMap(LinkedHashMap(facilities))
    val questRewards: Map<String,TrialQuestNumbers> = Collections.unmodifiableMap(LinkedHashMap(quests))
    val provenance: Map<String,String> = Collections.unmodifiableMap(LinkedHashMap(provenance))
    fun rentalRate(id:VehicleId):ContractResult<GameMoney> = rentalRates[id]?.let { ContractResult.Success(it) }
        ?: ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    fun questNumbers(id:String):ContractResult<TrialQuestNumbers> = questRewards[id]?.let { ContractResult.Success(it) }
        ?: ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    /** 실제 Quest 계약의 등록 ID 전체와 대조한다. 이 표가 새 Quest 의미/진행을 정의하지 않는다. */
    fun questNumbersFor(registered:Set<String>):ContractResult<Map<String,TrialQuestNumbers>> =
        if(registered==questRewards.keys)ContractResult.Success(questRewards) else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
}
fun interface TrialDefinitionSource { fun load():ContractResult<TrialDefinition> }

internal fun parseTrialDefinition(text:String, knownVehicles:Set<VehicleId>):ContractResult<TrialDefinition> {
    return try {
        val lines=text.lineSequence().map(String::trim).filter{it.isNotEmpty()}.toList()
        val marker=lines.indexOfFirst{it.startsWith("[")};require(marker>0 && knownVehicles.isNotEmpty())
        val expansion=when(val r=parseShopExpansionDefinition(lines.take(marker).joinToString("\n"))) {
            is ContractResult.Failure->return r
            is ContractResult.Success->r.value
        }
        val names=setOf("rental_rates","facilities","fuel_bonus","quest_rewards","provenance")
        val sections=linkedMapOf<String,MutableList<List<String>>>()
        var current:String?=null
        for(line in lines.drop(marker)) {
            if(line.startsWith("[")) {
                require(line.endsWith("]"));val name=line.substring(1,line.length-1)
                require(name in names && name !in sections);sections[name]=mutableListOf();current=name
            } else sections.getValue(checkNotNull(current)).add(line.split(',').map(String::trim))
        }
        require(sections.keys==names)
        fun number(value:String):Long { require(value.matches(Regex("0|[1-9][0-9]*")));return value.toLong() }
        fun <K,V> records(name:String,header:List<String>,read:(List<String>)->Pair<K,V>):Map<K,V> {
            val rows=sections.getValue(name);require(rows.size>1 && rows.first()==header)
            val result=linkedMapOf<K,V>()
            for(row in rows.drop(1)) {require(row.size==header.size);val(k,v)=read(row);require(result.put(k,v)==null)}
            return result
        }
        val rates=records("rental_rates",listOf("vehicle_id","hourly_rate")) {
            val amount=number(it[1]);require(amount>0);VehicleId(it[0]) to GameMoney(amount)
        }
        require(rates.keys==knownVehicles)
        val facilities=records("facilities",listOf("facility","price")) {
            val facility=Facility.valueOf(it[0]);facility to FacilityOffer(facility,expansion.rules,expansion.trial,GameMoney(number(it[1])))
        }
        require(facilities.keys==Facility.entries.toSet())
        val fuel=sections.getValue("fuel_bonus");require(fuel.size==1 && fuel[0].size==2 && fuel[0][0]=="per_rental")
        val quests=records("quest_rewards",listOf("quest_id","goal","reward")) {
            require(it[0].isNotBlank());val goal=number(it[1]);require(goal<=Int.MAX_VALUE)
            it[0] to TrialQuestNumbers(goal.toInt(),GameMoney(number(it[2])))
        }
        val provenance=records("provenance",listOf("key","value")) {require(it[1].isNotBlank());it[0] to it[1]}
        require(provenance.keys==setOf("authorization","prior_agreement","balance_status","quest_ids"))
        ContractResult.Success(TrialDefinition(expansion,rates,facilities,GameMoney(number(fuel[0][1])),quests,provenance))
    } catch (_:IllegalArgumentException) { ContractResult.Failure(ContractError.INVALID_INPUT) }
      catch (_:IllegalStateException) { ContractResult.Failure(ContractError.INVALID_INPUT) }
}
