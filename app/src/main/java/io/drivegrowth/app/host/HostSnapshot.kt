package io.drivegrowth.app.host

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.garage.CollectionKey

/** 조회한 공통 계약을 문자열 요약으로 바꾸지 않고 화면 호스트까지 전달한다. */
data class HostSnapshot(
    val content: GameContentSnapshot,
    val source: InputSource,
    val balance: GameMoney,
    val ownedVehicles: List<OwnedVehicle>,
    val unviewedSettlements: List<Settlement>,
    val collectionHistory: ContractResult<CollectionHistory> = ContractResult.Failure(ContractError.DATA_UNAVAILABLE),
)

/** 현재 보유 목록과 별도로 공급해야 하는 처분 포함 획득·발견 이력이다. */
data class CollectionHistory(
    val everOwned: Set<CollectionKey>,
    val discovered: Set<CollectionKey>,
)
