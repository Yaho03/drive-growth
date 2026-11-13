package io.drivegrowth.app.ui.delivery

import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.delivery.ActiveDelivery
import io.drivegrowth.core.delivery.DeliveryOfferId
import io.drivegrowth.core.delivery.DeliveryProposed

/** 허용은 호스트가 제공한다. 이유가 없거나 정보가 없으면 UI가 허용을 추정하지 않는다. */
data class DeliveryAvailability(val allowed: Boolean, val reason: String? = null)

data class DeliveryProposalUi(
    val proposed: DeliveryProposed,
    val destinationName: String?,
    val vehicleNames: Map<VehicleId, String>,
    val availability: Map<DeliveryOfferId, DeliveryAvailability>,
    val confirmationTerms: String?,
)

data class DeliveryActiveUi(
    val active: ActiveDelivery,
    val destinationName: String?,
    val vehicleName: String?,
    val observedDistanceLabel: String?,
    val progressLabel: String?,
    val change: DeliveryAvailability,
    val abandon: DeliveryAvailability,
)

/** 호스트 계산 결과. 차액과 취소 수수료는 서로 다른 항목이며 UI에서 뺄셈하지 않는다. */
data class DeliveryMoneyUi(val direction: MoneyDirection, val amount: GameMoney)

sealed interface DeliveryScreen {
    data object Loading : DeliveryScreen
    data class Unsupported(val message: String) : DeliveryScreen
    data class Failure(val message: String, val retryAllowed: Boolean) : DeliveryScreen
    data class Destination(
        val name: String?, val estimatedDistanceLabel: String?, val sourceLabel: String?,
        val chooseAllowed: Boolean, val reason: String? = null,
    ) : DeliveryScreen
    data class Offers(val proposal: DeliveryProposalUi) : DeliveryScreen
    data class Active(val delivery: DeliveryActiveUi) : DeliveryScreen
    data class ChangeReview(
        val before: DeliveryActiveUi, val after: DeliveryProposalUi, val offerId: DeliveryOfferId,
        val difference: DeliveryMoneyUi?, val rarityNotice: String?, val permission: DeliveryAvailability,
    ) : DeliveryScreen
    data class AbandonReview(
        val delivery: DeliveryActiveUi, val fee: GameMoney?, val refund: GameMoney?,
        val consequence: String?, val permission: DeliveryAvailability,
    ) : DeliveryScreen
    /** 도착·정산 증거를 기다리는 상태. P·거리 0을 성공으로 바꾸지 않는다. */
    data class AwaitingResult(val delivery: DeliveryActiveUi, val message: String) : DeliveryScreen
}

/** 화면 공급 순서이며 공통 계약의 revision이나 저장 버전이 아니다. 호스트가 세션 안에서 단조 증가시킨다. */
data class DeliveryFrame(val version: Long, val screen: DeliveryScreen) {
    init { require(version >= 0) }
}

sealed interface DeliveryCommand {
    data class ChooseDestination(val active: ActiveDelivery?) : DeliveryCommand
    data class Select(val proposed: DeliveryProposed, val offerId: DeliveryOfferId) : DeliveryCommand
    data class Change(val active: ActiveDelivery, val proposed: DeliveryProposed, val offerId: DeliveryOfferId) : DeliveryCommand
    data class ReviewAbandon(val active: ActiveDelivery) : DeliveryCommand
    data class Abandon(val active: ActiveDelivery) : DeliveryCommand
    data object ExitReview : DeliveryCommand
    data object Refresh : DeliveryCommand
}

/** sequence는 메모리상 UI 응답 상관값이다. 경제 requestId는 호스트가 기존 저장 경계에서 보존한다. */
data class DeliveryRequest(val sequence: Long, val frameVersion: Long, val command: DeliveryCommand)
data class DeliveryInteractionState(
    val frame: DeliveryFrame? = null,
    val pending: DeliveryRequest? = null,
    val nextSequence: Long = 1,
    val selectedOfferId: DeliveryOfferId? = null,
    val selectedFrameVersion: Long? = null,
    val presentationKeys: Set<DeliverySceneKey> = emptySet(),
)
