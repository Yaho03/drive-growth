package io.drivegrowth.core.delivery

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.TransactionKind

/** 선택한 활성 의뢰. 제시 값을 복사해 이후 목록이 바뀌어도 선택 조건을 유지한다. */
data class ActiveDelivery(
    val requestId: RequestId,
    val proposed: DeliveryProposed,
    val offer: DeliveryOffer,
)

/**
 * @param payment 구매 탁송의 결제. 수익 탁송은 돈이 오가지 않아 null이다.
 *   차량은 도착 전 지급하지 않으므로 어느 쪽도 차량 변경을 담지 않는다.
 */
data class DeliverySelected(
    val active: ActiveDelivery,
    val payment: GameChange?,
)

object DeliverySelection {
    /**
     * 제시된 의뢰를 선택한다. 실패하면 아무것도 바꾸지 않으며, 의뢰와 결제의 저장은 호출자가 한 거래로 묶는다.
     *
     * @param current 이미 진행 중인 의뢰. 활성 의뢰는 동시에 하나뿐이다.
     */
    fun select(
        proposed: DeliveryProposed,
        offerId: DeliveryOfferId,
        requestId: RequestId,
        balance: GameMoney,
        current: ActiveDelivery?,
    ): ContractResult<DeliverySelected> {
        if (current != null) {
            return when {
                current.requestId != requestId -> ContractResult.Failure(ContractError.CAPACITY_EXCEEDED)
                current.offer.id == offerId && current.proposed == proposed ->
                    ContractResult.Success(DeliverySelected(current, paymentOf(current)))
                else -> ContractResult.Failure(ContractError.STATE_CONFLICT)
            }
        }
        val offer = proposed.offers.firstOrNull { it.id == offerId }
            ?: return ContractResult.Failure(ContractError.INVALID_INPUT)
        if (offer is DeliveryOffer.Purchase && offer.price.units > balance.units) {
            return ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS)
        }
        val active = ActiveDelivery(requestId, proposed, offer)
        return ContractResult.Success(DeliverySelected(active, paymentOf(active)))
    }

    private fun paymentOf(active: ActiveDelivery): GameChange? = when (val offer = active.offer) {
        is DeliveryOffer.Income -> null
        is DeliveryOffer.Purchase -> GameChange(
            requestId = active.requestId,
            kind = TransactionKind.DELIVERY,
            source = active.proposed.estimated.source,
            ruleVersion = active.proposed.list.ruleVersion,
            money = MoneyChange(MoneyDirection.DEBIT, offer.price),
        )
    }
}
