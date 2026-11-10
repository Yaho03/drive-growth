package io.drivegrowth.core.contract

import io.drivegrowth.core.rental.CompletedRental

/** 현재 잔액이 아닌 저장된 최초 완료 영업 정산의 식별자. 최초 영업 조회 실패는 빈 근거로 바꾸지 않는다. */
data class FirstRentalGiftCause(val rentalId: RequestId, val settlementId: SettlementId)

data class FirstRentalGiftCommand(
    val requestId: RequestId,
    val profileId: ProfileId,
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    val cause: FirstRentalGiftCause,
    val expectedProgressRevision: Revision,
)

data class FirstRentalGiftMarker(val profileId: ProfileId, val requestId: RequestId, val cause: FirstRentalGiftCause)

data class FirstRentalGiftSettlement(
    val command: FirstRentalGiftCommand,
    val settlementId: SettlementId,
    val transaction: Transaction,
    val balance: GameMoney,
    val progress: GachaProgress,
    val marker: FirstRentalGiftMarker,
    val confirmedAt: EpochMillis,
)

/** firstCompletedRental은 #116의 검증된 조회 결과다. null은 아직 완료 없음, 조회 오류는 state를 만들지 않는다. */
data class FirstRentalGiftState(
    val profileId: ProfileId,
    val source: InputSource,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    val balance: GameMoney,
    val progress: GachaProgress,
    val firstCompletedRental: CompletedRental?,
    val granted: FirstRentalGiftSettlement?,
)

/** Ready는 무료권 +1·마커의 예정 결과일 뿐 실제 지급/예약이 아니다. 돈·차량은 바꾸지 않는다. */
sealed interface FirstRentalGiftPreparation {
    data class Ready(val command: FirstRentalGiftCommand, val balance: GameMoney, val progress: GachaProgress,
                     val marker: FirstRentalGiftMarker) : FirstRentalGiftPreparation
    data class Replay(val result: FirstRentalGiftSettlement) : FirstRentalGiftPreparation
}

/**
 * testFixtures의 같은 메모리 원장 참조가 제공된다. 실제 SQLite/live 공급은 후속이다.
 * 같은 요청/전체 명령은 최초 전체 결과, 다른 요청/원인/내용의 이미 지급된 프로필은 STATE_CONFLICT다.
 * 다른 종류의 기존 요청 ID도 STATE_CONFLICT다. 현재 상태 조회보다 원래 요청/최초 결과 재생을 먼저 검사한다.
 * #116 최초 완료 정산은 같은 프로필/출처 원장에서 읽고 프로필 마커·현재 GachaProgress revision과 같은 잠금/DB에서 검사한다.
 * 저장 측은 granted의 command/transaction(GARAGE·money=null)/progress/marker 연결을 검증한 뒤 공급한다.
 * 무료권 소비와 같은 revision 공간에서 +1/마커/정산/요청 이력을 한 번에 확정하고 실패는 전부 미반영한다.
 * 완료 없음/미지원은 DATA_UNAVAILABLE, 저장/권한 오류는 그대로 전달한다. 금전 없는 명령을 가짜0원 GameChange로 만들지 않는다.
 */
interface FirstRentalGiftStore : GachaChangeStore {
    fun readFirstRentalGift(profileId: ProfileId): ContractResult<FirstRentalGiftState>
    fun commitFirstRentalGift(command: FirstRentalGiftCommand): ContractResult<FirstRentalGiftSettlement>
}

fun prepareFirstRentalGift(command: FirstRentalGiftCommand, current: FirstRentalGiftState): ContractResult<FirstRentalGiftPreparation> {
    if (command.profileId != current.profileId || command.source != current.source)
        return ContractResult.Failure(ContractError.INVALID_INPUT)
    current.granted?.let { original ->
        return if (command == original.command) ContractResult.Success(FirstRentalGiftPreparation.Replay(original))
            else ContractResult.Failure(ContractError.STATE_CONFLICT)
    }
    if (command.contentVersion != current.contentVersion || command.ruleVersion != current.ruleVersion ||
        command.expectedProgressRevision != current.progress.revision)
        return ContractResult.Failure(ContractError.STATE_CONFLICT)
    val rental = current.firstCompletedRental ?: return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
    val settlement = rental.receipt.settlement
    if (settlement.transaction.kind != TransactionKind.RENTAL || settlement.transaction.source != current.source ||
        settlement.transaction.requestId.value != "rental/settle/" + rental.rentalId.value)
        return ContractResult.Failure(ContractError.STORAGE_FAILURE)
    if (command.cause != FirstRentalGiftCause(rental.rentalId, settlement.settlementId))
        return ContractResult.Failure(ContractError.STATE_CONFLICT)
    if (current.progress.revision.value == Int.MAX_VALUE || current.progress.freeTickets == Long.MAX_VALUE)
        return ContractResult.Failure(ContractError.INVALID_INPUT)
    val progress = current.progress.copy(revision = Revision(current.progress.revision.value + 1),
        freeTickets = current.progress.freeTickets + 1)
    return ContractResult.Success(FirstRentalGiftPreparation.Ready(command, current.balance, progress,
        FirstRentalGiftMarker(current.profileId, command.requestId, command.cause)))
}
