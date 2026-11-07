package io.drivegrowth.core.garage

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameChangeStore
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.VehicleWrite

/**
 * 첫 실행 지급: 초기 재화와 고른 첫 차를 거래 한 건으로 저장한다(저장소 잔액은 0에서 시작).
 * 프로필당 고정 requestId라 같은 차로 다시 요청하면 처음 정산이 돌아오고, 다른 차면 STATE_CONFLICT(선택 취소 불가).
 * 저장 실패는 전체 미반영이므로 재시작 시 "선택 전" 또는 "확정"만 남는다.
 * 호출 조건: 신규 프로필로 확인된 경우에만 부른다. 이 requestId가 없는 기존 데이터(초기 재화·첫 차를 다른 경로로 받은 DB)는
 * 막지 못한다. 빈 보유 목록·미확인 정산 없음은 신규 프로필 근거가 아니다. 사전 확인은 처분 포함 이력 조회(#116)로 연결한다.
 * ponytail: 개업 선물 뽑기권은 보유 수 저장(#33·#34)과 첫 영업 완료 신호(#42)가 생기면 추가.
 */
class FirstGrant(private val content: GameContentSnapshot) {
    fun grant(store: GameChangeStore, chosen: VehicleId, source: InputSource): ContractResult<Settlement> {
        if (chosen !in CANDIDATES) return ContractResult.Failure(ContractError.INVALID_INPUT)
        if (content.catalog.vehicles.none { it.vehicleId == chosen }) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val vehicle = OwnedVehicle(
            OwnedVehicleId("initial-grant"), chosen, 1, Revision(0), VehicleLifecycle.OWNED,
            Rarity.COMMON, 0, GameMoney(0), VehicleAcquisitionSource.INITIAL_GRANT,
        )
        return store.commitGameChange(GameChange(
            REQUEST_ID, TransactionKind.GARAGE, source, content.ruleVersion,
            money = MoneyChange(MoneyDirection.CREDIT, INITIAL_MONEY),
            vehicle = VehicleWrite.Add(vehicle),
        ))
    }

    companion object {
        val REQUEST_ID = RequestId("initial-grant")
        /** PRD 「초기 상태」 시험값. */
        val INITIAL_MONEY = GameMoney(1_000)
        const val INITIAL_GARAGE_CAPACITY = 3
        /** PRD 「최초 차량」 후보. 읽기 전용(Kotlin listOf는 원소 교체가 가능해 List.of를 쓴다). */
        val CANDIDATES: List<VehicleId> = java.util.List.of(VehicleId("hyundai_casper"), VehicleId("hyundai_avante"))
    }
}
