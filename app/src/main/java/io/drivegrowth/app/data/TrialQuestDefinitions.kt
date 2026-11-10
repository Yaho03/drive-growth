package io.drivegrowth.app.data

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.quest.*
import java.time.ZoneId

/** 기간의 시간대는 호출자가 명시한다. 숫자 시연 허가가 전역 시간대 정책은 아니다. */
fun TrialDefinition.questDefinitions(zone: ZoneId): ContractResult<QuestDefinitions> {
    val entries=QuestCadence.entries.flatMap { cadence->QuestAction.entries.map { action->
        Triple(cadence.name.lowercase()+"-"+action.slug,cadence,action)
    } }
    val numbers=when(val supplied=questNumbersFor(entries.map{it.first}.toSet())) {
        is ContractResult.Failure->return supplied
        is ContractResult.Success->supplied.value
    }
    return ContractResult.Success(QuestDefinitions(rules,entries.map { (id,cadence,action)->
        val value=numbers.getValue(id)
        QuestDefinition(id,rules,cadence,action,value.goal,value.reward,zone,trial)
    }))
}
