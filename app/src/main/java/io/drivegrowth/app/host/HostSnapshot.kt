package io.drivegrowth.app.host

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.garage.CollectionKey
import io.drivegrowth.core.contract.ContentVersion
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.progression.VehicleActivity
import java.util.Collections

/** 조회한 공통 계약을 문자열 요약으로 바꾸지 않고 화면 호스트까지 전달한다. */
data class HostSnapshot(
    val content: GameContentSnapshot,
    val source: InputSource,
    val balance: GameMoney,
    val ownedVehicles: List<OwnedVehicle>,
    val unviewedSettlements: List<Settlement>,
    val collectionHistory: ContractResult<CollectionHistory> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
    val garageState: ContractResult<StoredGarageState> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
)

/** 현재 보유 목록과 별도로 공급해야 하는 처분 포함 획득·발견 이력이다. */
class CollectionHistory(
    everOwned: Set<CollectionKey>,
    discovered: Set<CollectionKey>,
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
) {
    val everOwned: Set<CollectionKey> = Collections.unmodifiableSet(LinkedHashSet(everOwned))
    val discovered: Set<CollectionKey> = Collections.unmodifiableSet(LinkedHashSet(discovered))
}

/** 개별 조회의 연결 기준은 원래 개체 전체 snapshot이다. 활동/사고 실패를 정상 값으로 바꾸지 않는다. */
data class StoredVehicleState(
    val vehicle: OwnedVehicle,
    val activity: ContractResult<VehicleActivity>,
    val accidents: ContractResult<Int>,
)

/** 순차 조회의 결과이며 원장 전체의 원자 snapshot/전역 revision이 아니다. */
class StoredGarageState(
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    vehicles: List<StoredVehicleState>,
    val capacity: ContractResult<Int>,
) {
    val vehicles: List<StoredVehicleState> = Collections.unmodifiableList(ArrayList(vehicles))
}
