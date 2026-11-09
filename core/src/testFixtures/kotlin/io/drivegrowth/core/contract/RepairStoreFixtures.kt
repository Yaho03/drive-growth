package io.drivegrowth.core.contract

import io.drivegrowth.core.progression.VehicleActivity
import java.util.Collections

/** 수리 참조의 명시 시험 입력. 제품의 실제 활동·정산 사고·VehicleCare 견적 공급을 대신하지 않는다. */
class RepairFixtureConfiguration(
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    activities: Map<OwnedVehicleId, ContractResult<VehicleActivity>>,
    accidentCounts: Map<OwnedVehicleId, ContractResult<Int>>,
    repairCosts: Map<OwnedVehicleId, ContractResult<GameMoney>>,
) {
    val activities: Map<OwnedVehicleId, ContractResult<VehicleActivity>> = Collections.unmodifiableMap(HashMap(activities))
    val accidentCounts: Map<OwnedVehicleId, ContractResult<Int>> = Collections.unmodifiableMap(HashMap(accidentCounts))
    val repairCosts: Map<OwnedVehicleId, ContractResult<GameMoney>> = Collections.unmodifiableMap(HashMap(repairCosts))
}
