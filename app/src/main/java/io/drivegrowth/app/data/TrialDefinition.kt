package io.drivegrowth.app.data

import io.drivegrowth.app.host.shop.ShopExpansionDefinition
import io.drivegrowth.app.host.shop.parseShopExpansionDefinition
import io.drivegrowth.app.runtime.rental.product.RentalCity
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.achievement.*
import io.drivegrowth.core.rental.*
import io.drivegrowth.core.delivery.*
import io.drivegrowth.core.driving.DrivingIncomeRules
import io.drivegrowth.core.driving.RestRules
import java.util.Collections

/** 숫자 입력만 공급한다. Quest 진행/기간/청구 가능 여부를 만들지 않는다. */
data class TrialQuestNumbers(val goal: Int, val reward: GameMoney) {
    init { require(goal>0 && reward.units>0) }
}
class TrialDefinition internal constructor(
    val expansions: ShopExpansionDefinition, rates: Map<VehicleId,GameMoney>, facilities: Map<Facility,FacilityOffer>,
    val fuelBonusPerRental: GameMoney, quests: Map<String,TrialQuestNumbers>, provenance: Map<String,String>,
    internal val delivery: TrialDeliveryRules, internal val driving: DrivingIncomeRules, internal val rental:TrialRentalInputs,internal val rest:RestRules,internal val restPricing:RestPricingPolicy,
    achievements: ContractResult<Map<AchievementMetric, AchievementDefinition>> =
        ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
) {
    val version get()=expansions.version
    val rules get()=expansions.rules
    val trial get()=expansions.trial
    val rentalRates: Map<VehicleId,GameMoney> = Collections.unmodifiableMap(LinkedHashMap(rates))
    val facilityOffers: Map<Facility,FacilityOffer> = Collections.unmodifiableMap(LinkedHashMap(facilities))
    val questRewards: Map<String,TrialQuestNumbers> = Collections.unmodifiableMap(LinkedHashMap(quests))
    val achievementDefinitions: ContractResult<Map<AchievementMetric, AchievementDefinition>> = when (achievements) {
        is ContractResult.Failure -> achievements
        is ContractResult.Success -> ContractResult.Success(Collections.unmodifiableMap(LinkedHashMap(achievements.value)))
    }
    val provenance: Map<String,String> = Collections.unmodifiableMap(LinkedHashMap(provenance))
    fun deliveryRules():ContractResult<DeliveryRules> = ContractResult.Success(delivery.proposal)
    fun deliveryChangeRules():ContractResult<DeliveryChangeRules> = ContractResult.Success(delivery.change)
    fun deliverySettlementRules():ContractResult<DeliverySettlementRules> = ContractResult.Success(delivery.settlement)
    fun drivingIncomeRules():ContractResult<DrivingIncomeRules> = ContractResult.Success(driving)
    fun restRules():ContractResult<RestRules> = ContractResult.Success(rest)
    fun restPricingPolicy():ContractResult<RestPricingPolicy> = ContractResult.Success(restPricing)
    fun rentalRate(id:VehicleId):ContractResult<GameMoney> = rentalRates[id]?.let { ContractResult.Success(it) }
        ?: ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    fun questNumbers(id:String):ContractResult<TrialQuestNumbers> = questRewards[id]?.let { ContractResult.Success(it) }
        ?: ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    /** 실제 Quest 계약의 등록 ID 전체와 대조한다. 이 표가 새 Quest 의미/진행을 정의하지 않는다. */
    fun questNumbersFor(registered:Set<String>):ContractResult<Map<String,TrialQuestNumbers>> =
        if(registered==questRewards.keys)ContractResult.Success(questRewards) else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
}
data class TrialDeliveryRules(val proposal:DeliveryRules,val change:DeliveryChangeRules,val settlement:DeliverySettlementRules)
fun interface TrialDefinitionSource { fun load():ContractResult<TrialDefinition> }

internal fun parseTrialDefinition(text:String, knownVehicles:Set<VehicleId>):ContractResult<TrialDefinition> {
    return try {
        val lines=text.lineSequence().map(String::trim).filter{it.isNotEmpty()}.toList()
        val marker=lines.indexOfFirst{it.startsWith("[")};require(marker>0 && knownVehicles.isNotEmpty())
        val expansion=when(val r=parseShopExpansionDefinition(lines.take(marker).joinToString("\n"))) {
            is ContractResult.Failure->return r
            is ContractResult.Success->r.value
        }
        val names=setOf("rental_rates","facilities","fuel_bonus","quest_rewards","provenance","delivery_bands","delivery_prices","delivery_proposal","delivery_change","delivery_settlement","driving","rental_visits","rental_categories","rental_durations","rental_customers","rest")
        val demandSections = setOf("rental_visit_intervals", "rental_city_uses")
        val sections=linkedMapOf<String,MutableList<List<String>>>()
        var current:String?=null
        for(line in lines.drop(marker)) {
            if(line.startsWith("[")) {
                require(line.endsWith("]"));val name=line.substring(1,line.length-1)
                if (name == "achievement_rewards") {
                    // 중복 업적 섹션은 업적 정의만 거절하도록 표식한다.
                    if (name in sections) sections.getValue(name).add(emptyList())
                    else sections[name] = mutableListOf()
                } else {
                    require(name in (names + demandSections) && name !in sections)
                    sections[name] = mutableListOf()
                }
                current = name
            } else sections.getValue(checkNotNull(current)).add(line.split(',').map(String::trim))
        }
        require(sections.keys - "achievement_rewards" - demandSections == names)
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
        val achievements = parseAchievementDefinitions(sections["achievement_rewards"])
        val provenance=records("provenance",listOf("key","value")) {require(it[1].isNotBlank());it[0] to it[1]}
        require(provenance.keys==setOf("authorization","prior_agreement","balance_status","quest_ids"))
        fun scalar(name:String,keys:Set<String>):Map<String,String> {
            val values=records(name,listOf("key","value")){it[0] to it[1]};require(values.keys==keys);return values
        }
        fun Map<String,String>.n(key:String)=number(getValue(key))
        fun Map<String,String>.i(key:String):Int {val v=n(key);require(v<=Int.MAX_VALUE);return v.toInt()}
        val bands=sections.getValue("delivery_bands");require(bands.size>1&&bands.first()==listOf("max_meters","common","advanced","rare","legend","income_percent"))
        val bandValues=bands.drop(1).map { row->
            require(row.size==6)
            fun i(at:Int):Int {val v=number(row[at]);require(v<=Int.MAX_VALUE);return v.toInt()}
            DeliveryBand(if(row[0]=="unbounded")null else number(row[0]),Rarity.entries.mapIndexed{index,rarity->rarity to i(index+1)}.toMap(),i(5))
        }
        val prices=records("delivery_prices",listOf("rarity","percent")){val v=number(it[1]);require(v<=Int.MAX_VALUE);Rarity.valueOf(it[0]) to v.toInt()}
        require(prices.keys==Rarity.entries.toSet())
        val proposal=scalar("delivery_proposal",setOf("income_rate_percent","income_min_meters","income_offer_count","purchase_offer_count","rounding"))
        val change=scalar("delivery_change",setOf("cancel_fee_percent"))
        val settle=scalar("delivery_settlement",setOf("arrival_window_ms","hold_window_ms","hold_refund_fee_percent"))
        val drive=scalar("driving",setOf("daily_cap_meters","income_per_km"));require(drive.n("daily_cap_meters")==500000L)
        val delivery=TrialDeliveryRules(DeliveryRules(expansion.rules,bandValues,prices,proposal.i("income_rate_percent"),proposal.n("income_min_meters"),
            proposal.i("income_offer_count"),proposal.i("purchase_offer_count"),DeliveryRounding.valueOf(proposal.getValue("rounding"))),
            DeliveryChangeRules(expansion.rules,change.i("cancel_fee_percent")),
            DeliverySettlementRules(expansion.rules,settle.n("arrival_window_ms"),settle.n("hold_window_ms"),settle.i("hold_refund_fee_percent")))
        val visit = records("rental_visits", listOf("key", "value")) { it[0] to it[1] }
        val visitKeys = setOf("interval_ms", "max_replay_visits", "rounding")
        val hasDemandPolicy = "demand_definition_version" in visit
        require(visit.keys == if (hasDemandPolicy) visitKeys + "demand_definition_version" else visitKeys)
        require(sections.keys.intersect(demandSections) == if (hasDemandPolicy) demandSections else emptySet())
        require(visit.n("interval_ms") == 1_800_000L && visit.i("max_replay_visits") > 0)
        val demandPolicy = if (hasDemandPolicy) {
            val version = ContentVersion(visit.getValue("demand_definition_version"))
            require(version != expansion.version)
            val intervals = records("rental_visit_intervals", listOf("shop_level", "interval_ms")) {
                val level = number(it[0])
                require(level in 1..4)
                level.toInt() to number(it[1])
            }
            require(intervals.keys == (1..4).toSet() && intervals.getValue(1) == visit.n("interval_ms"))
            val ordered = (1..4).map(intervals::getValue)
            require(ordered.last() > 0 && ordered.zipWithNext().all { (a, b) -> a > b })

            val purposes = RentalCategory.entries
            val cities = records("rental_city_uses", listOf("city") + purposes.map { it.name }) { row ->
                RentalCity.valueOf(row.first()) to purposes.associateWith { purpose ->
                    val weight = number(row[purpose.ordinal + 1])
                    require(weight in 1..Int.MAX_VALUE)
                    weight.toInt()
                }
            }
            require(cities.keys == RentalCity.entries.toSet())
            val weights = purposes.associateWith { purpose ->
                RentalCity.entries.associateWith { city -> cities.getValue(city).getValue(purpose) }
            }
            TrialRentalDemandPolicy(version, intervals, weights)
        } else null
        val categoryRows=sections.getValue("rental_categories");require(categoryRows.first()==listOf("vehicle_id","category","basis","demand_weight"))
        val categoryBases=setOf("market-small","body-sedan","body-suv","body-mpv","eco-powertrain")
        val categories=linkedMapOf<VehicleId,RentalCategory>();val categoryBasis=linkedMapOf<VehicleId,String>();val demand=linkedMapOf<VehicleId,Int>()
        for(row in categoryRows.drop(1)) {
            require(row.size==4);val id=VehicleId(row[0]);require(id !in categories)
            categories[id]=RentalCategory.valueOf(row[1]);require(row[2] in categoryBases);categoryBasis[id]=row[2]
            val weight=number(row[3]);require(weight in 1..Int.MAX_VALUE);demand[id]=weight.toInt()
        };require(categories.keys==knownVehicles)
        val durations=records("rental_durations",listOf("hours","weight","return_minutes")) {
            val hour=number(it[0]);val weight=number(it[1]);val returned=number(it[2]);require(hour in setOf(1L,3L,6L)&&weight in 1..Int.MAX_VALUE&&returned in setOf(15L,30L,60L));hour.toInt() to (weight.toInt() to returned.toInt())
        };require(durations.keys==setOf(1,3,6))
        val customers=sections.getValue("rental_customers");require(customers.first()==listOf("age","licensed_months","regular","weight")&&customers.size>1)
        val customerInputs=customers.drop(1).map {row->
            require(row.size==4);val age=number(row[0]);val months=number(row[1]);val weight=number(row[3]);require(age<=Int.MAX_VALUE&&months<=Int.MAX_VALUE&&weight in 1..Int.MAX_VALUE)
            val regular=when(row[2]){"true"->true;"false"->false;else->throw IllegalArgumentException("단골 입력")}
            io.drivegrowth.app.runtime.rental.product.RentalCustomerWeight(SimulatedCustomer(age.toInt(),months.toInt()),regular,weight.toInt())
        };require(customerInputs.toSet().size==customerInputs.size)
        val rental=TrialRentalInputs(categories,categoryBasis,demand,durations.mapValues{it.value.first},durations.mapValues{it.value.second},customerInputs,visit.i("max_replay_visits"),visit.n("interval_ms"),java.math.RoundingMode.valueOf(visit.getValue("rounding")),demandPolicy)
        val rest=scalar("rest",setOf("min_driving_ms","min_parked_ms","bonus_duration_ms","bonus_percent","pricing_policy"))
        val pricing=RestPricingPolicy.entries.singleOrNull{it.id==rest.getValue("pricing_policy")}?:throw IllegalArgumentException("휴식 가격 기술 정책")
        ContractResult.Success(TrialDefinition(expansion,rates,facilities,GameMoney(number(fuel[0][1])),quests,provenance,delivery,
            DrivingIncomeRules(expansion.rules,drive.n("daily_cap_meters"),GameMoney(drive.n("income_per_km"))),rental,
            RestRules(expansion.rules,rest.n("min_driving_ms"),rest.n("min_parked_ms"),rest.n("bonus_duration_ms"),rest.i("bonus_percent")),pricing, achievements))
    } catch (_:IllegalArgumentException) { ContractResult.Failure(ContractError.INVALID_INPUT) }
      catch (_:IllegalStateException) { ContractResult.Failure(ContractError.INVALID_INPUT) }
}


private fun parseAchievementDefinitions(rows: List<List<String>>?): ContractResult<Map<AchievementMetric, AchievementDefinition>> {
    if (rows == null) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    return try {
        require(rows.firstOrNull() == listOf("id", "version", "trial", "goal", "reward"))
        val definitions = linkedMapOf<AchievementMetric, AchievementDefinition>()
        for (row in rows.drop(1)) {
            require(row.size == 5 && row[2] == "true" && row[3].matches(Regex("[1-9][0-9]*")) &&
                row[4].matches(Regex("[1-9][0-9]*")))
            val metric = AchievementMetric.entries.singleOrNull { it.id == row[0] } ?: throw IllegalArgumentException("업적 ID")
            val definition = AchievementDefinition(metric, RuleVersion(row[1]), row[3].toInt(), GameMoney(row[4].toLong()), true)
            require(definitions.put(metric, definition) == null)
        }
        require(definitions.keys == AchievementMetric.entries.toSet() && definitions.values.map { it.version }.distinct().size == 1)
        ContractResult.Success(definitions)
    } catch (_: IllegalArgumentException) { ContractResult.Failure(ContractError.INVALID_INPUT) }
}
