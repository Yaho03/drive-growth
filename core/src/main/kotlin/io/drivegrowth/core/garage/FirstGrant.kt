package io.drivegrowth.core.garage

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.FirstRentalGiftCause
import io.drivegrowth.core.contract.FirstRentalGiftCommand
import io.drivegrowth.core.contract.FirstRentalGiftStore
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameChangeStore
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InitializationChangeStore
import io.drivegrowth.core.contract.InitializationSettlement
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.ProfileId
import io.drivegrowth.core.contract.ProfileInitializationStatus
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.VehicleWrite
import io.drivegrowth.core.contract.commitFirstGrantInitialization
import io.drivegrowth.core.contract.prepareFirstRentalGift

/**
 * 첫 실행 지급: 초기 재화와 고른 첫 차를 거래 한 건으로 저장한다(저장소 잔액은 0에서 시작).
 * 프로필당 고정 requestId라 같은 차로 다시 요청하면 처음 정산이 돌아오고, 다른 차면 STATE_CONFLICT(선택 취소 불가).
 * 저장 실패는 전체 미반영이므로 재시작 시 "선택 전" 또는 "확정"만 남는다.
 * 호출 조건: 신규 프로필로 확인된 경우에만 부른다. 이 requestId가 없는 기존 데이터(초기 재화·첫 차를 다른 경로로 받은 DB)는
 * 막지 못한다. 빈 보유 목록·미확인 정산 없음은 신규 프로필 근거가 아니다. 제품 경로는 NEW 근거를 쓰는 [choices]·[choose](#121)다.
 * 개업 선물(첫 영업 완료 시 무료 뽑기권 1장)은 [prepareOpeningGift]가 명령을 만든다.
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

    /**
     * 처분 포함 이력에 첫 지급 개체(`INITIAL_GRANT`)가 있으면 true: 이미 받은 프로필이라 온보딩 지급을 열지 않는다.
     * false는 "이력에 첫 지급 기록 없음"일 뿐 신규 프로필의 근거가 아니다(마커 없는 기존 DB는 구분 못 함, #121 NEW/EXISTING/UNKNOWN 후속).
     * 조회 실패는 그대로 전달한다.
     */
    fun alreadyGranted(history: VehicleHistoryStore): ContractResult<Boolean> =
        when (val result = history.listVehicleHistory()) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> ContractResult.Success(result.value.any { it.acquisitionSource == VehicleAcquisitionSource.INITIAL_GRANT })
        }

    /**
     * 첫 차 선택 화면 후보. 저장 측이 NEW 근거를 준 프로필만 PRD 후보 중 현재 콘텐츠·저장 측 후보에 있는 차를 돌려준다.
     * 이미 받은 프로필(EXISTING, 원래 결과 유무 무관)은 빈 목록(다시 고르지 않음), UNKNOWN·후보 없음은 DATA_UNAVAILABLE(무지급).
     */
    fun choices(store: InitializationChangeStore, profileId: ProfileId): ContractResult<List<VehicleId>> {
        val state = when (val result = store.readInitialization(profileId)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        return when (state.status) {
            is ProfileInitializationStatus.Existing -> ContractResult.Success(emptyList())
            is ProfileInitializationStatus.Unknown -> ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            is ProfileInitializationStatus.New -> {
                val known = content.catalog.vehicles.map { it.vehicleId }.toSet()
                val available = CANDIDATES.filter { it in state.availableCandidates && it in known }
                if (available.isEmpty()) ContractResult.Failure(ContractError.DATA_UNAVAILABLE) else ContractResult.Success(available)
            }
        }
    }

    /**
     * 첫 차 선택 확정. NEW 근거로 초기 재화·첫 차·초기 공간·마커를 `commitInitialization` 한 번에 확정한다.
     * 이미 확정된 프로필은 원래 근거로 같은 명령을 다시 제출한다: 같은 차면 저장소가 처음 결과를 재생하고 다른 차면 STATE_CONFLICT.
     * 그래서 응답을 잃어도 같은 선택으로 다시 부르면 된다. 원래 결과가 없는 기존 프로필·UNKNOWN은 DATA_UNAVAILABLE(무지급).
     */
    fun choose(store: InitializationChangeStore, profileId: ProfileId, chosen: VehicleId): ContractResult<InitializationSettlement> {
        val state = when (val result = store.readInitialization(profileId)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val evidence = when (val status = state.status) {
            is ProfileInitializationStatus.New -> status.evidence
            is ProfileInitializationStatus.Existing -> status.original?.command?.evidence
                ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            is ProfileInitializationStatus.Unknown -> return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        }
        return commitFirstGrantInitialization(store, evidence, content, chosen, INITIAL_GARAGE_CAPACITY)
    }

    /**
     * 개업 선물 명령: 같은 프로필 원장의 최초 완료 영업을 근거로 무료 뽑기권 +1(#121 `FirstRentalGiftCommand`).
     * 저장하지 않는다. 호출자가 `store.commitFirstRentalGift(command)`로 확정한다.
     * 이미 지급된 프로필은 원래 명령을 돌려주므로 다시 제출하면 저장소가 처음 결과를 재생한다(프로필당 1회).
     * 거절: 완료 영업 없음 DATA_UNAVAILABLE / 버전·가챠 revision 불일치 STATE_CONFLICT / 조회 실패는 그대로.
     */
    fun prepareOpeningGift(store: FirstRentalGiftStore, profileId: ProfileId, source: InputSource): ContractResult<FirstRentalGiftCommand> {
        val state = when (val result = store.readFirstRentalGift(profileId)) {
            is ContractResult.Failure -> return result
            is ContractResult.Success -> result.value
        }
        val command = state.granted?.command ?: run {
            val rental = state.firstCompletedRental ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
            FirstRentalGiftCommand(GIFT_REQUEST_ID, profileId, source, content.catalog.contentVersion, content.ruleVersion,
                FirstRentalGiftCause(rental.rentalId, rental.receipt.settlement.settlementId), state.progress.revision)
        }
        return when (val checked = prepareFirstRentalGift(command, state)) {
            is ContractResult.Failure -> checked
            is ContractResult.Success -> ContractResult.Success(command)
        }
    }

    companion object {
        val REQUEST_ID = RequestId("initial-grant")
        val GIFT_REQUEST_ID = RequestId("opening-gift")
        /** PRD 「초기 상태」 시험값. */
        val INITIAL_MONEY = GameMoney(1_000)
        const val INITIAL_GARAGE_CAPACITY = 3
        /** PRD 「최초 차량」 후보. 읽기 전용(Kotlin listOf는 원소 교체가 가능해 List.of를 쓴다). */
        val CANDIDATES: List<VehicleId> = java.util.List.of(VehicleId("hyundai_casper"), VehicleId("hyundai_avante"))
    }
}
