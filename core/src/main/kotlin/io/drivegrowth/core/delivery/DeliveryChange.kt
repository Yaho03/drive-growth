package io.drivegrowth.core.delivery

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.TransactionKind
import java.math.BigInteger

/**
 * 변경 목록. [DeliveryProposal.proposeChange]가 만든다.
 *
 * @param requestId 이 목록을 만든 활성 의뢰. 다른 의뢰의 변경에 쓰지 못한다.
 * @param traveledMeters 출발부터 중단 지점까지 실제 이동 거리.
 * @param combinedMeters 목록 구간을 정한 거리(실제 이동 + 새 예상 거리).
 */
data class DeliveryChangeOffer(
    val requestId: RequestId,
    val traveledMeters: Long,
    val combinedMeters: Long,
    val proposed: DeliveryProposed,
)

/**
 * 변경·포기 규칙. 취소 수수료 10%는 PRD 시험값이며 최소값은 두지 않는다(#24 제안 단계).
 */
data class DeliveryChangeRules(
    val ruleVersion: RuleVersion,
    val cancelFeePercent: Int,
) {
    init { require(cancelFeePercent in 0..100) { "수수료는 0~100%" } }
}

/**
 * 진행 중 의뢰의 결제·변경 상태. 활성 의뢰와 함께 저장한다.
 *
 * @param paid 지금 보류 중인 구매 대금. 수익 탁송은 0이다.
 * @param changeUsed 변경 기회를 썼으면 true. 다시 변경할 수 없고 포기만 할 수 있다.
 */
data class DeliveryTerms(
    val active: ActiveDelivery,
    val paid: GameMoney,
    val changeUsed: Boolean,
) {
    init {
        require(active.offer is DeliveryOffer.Purchase || paid == GameMoney(0)) { "수익 탁송은 결제액이 없다" }
    }

    companion object {
        /** 선택을 확정한 직후의 상태. */
        fun of(selected: DeliverySelected): DeliveryTerms =
            DeliveryTerms(selected.active, (selected.active.offer as? DeliveryOffer.Purchase)?.price ?: GameMoney(0), false).snapshot()
    }

    /** 호출자가 넘긴 후보 목록을 나중에 바꿔도 검증·반환 결과가 바뀌지 않도록 복사본으로 고정한다. */
    internal fun snapshot(): DeliveryTerms = copy(active = active.copy(proposed = active.proposed.snapshot()))
}

/**
 * @param change 차액 결제(DEBIT) 또는 환급(CREDIT). 차액이 0이거나 수익 탁송이면 null이다.
 */
data class DeliveryChanged(
    val terms: DeliveryTerms,
    val change: GameChange?,
)

/**
 * @param change 구매 포기의 수수료 공제 환급. 수익 탁송 포기는 돈이 오가지 않아 null이다.
 */
data class DeliveryAbandoned(
    val terms: DeliveryTerms,
    val change: GameChange?,
)

/**
 * 진행 중 의뢰의 목적지 변경과 포기를 계산한다. 저장, 실제 거리 입력, 화면은 담당하지 않는다.
 *
 * - 변경은 의뢰마다 1회다. 같은 유형의 변경 후보만 고를 수 있고, 구매는 보류 중인 대금과의 차액만 결제하거나 환급한다. 취소 수수료는 붙이지 않는다.
 * - 포기는 변경 전후 언제든 할 수 있다. 수익 탁송은 벌금이 없고, 구매 탁송은 보류 대금에서 수수료를 내림 공제해 환급한다.
 * - 포기 환급은 도착 정산·미도착 환급과 같은 요청 ID(`delivery/<요청 ID>/settle`)를 써서 원장에서 하나만 확정된다. 변경 차액은 `delivery/<요청 ID>/change`다.
 * - 입력은 진입할 때 복사본으로 고정해 검증·선택·반환에 같은 값을 쓴다. 반환 목록은 수정할 수 없다.
 * - 실패하면 아무것도 바꾸지 않는다. 호출자는 결과 상태와 돈 변화, 경로 세대 변경을 한 트랜잭션으로 저장한다.
 */
class DeliveryChange(private val rules: DeliveryChangeRules) {
    /**
     * @param balance 차액을 결제할 때 확인하는 현재 잔액.
     */
    fun change(
        terms: DeliveryTerms,
        offer: DeliveryChangeOffer,
        offerId: DeliveryOfferId,
        balance: GameMoney,
    ): ContractResult<DeliveryChanged> {
        val current = terms.snapshot()
        val active = current.active
        val request = offer.proposed.snapshot()
        if (current.changeUsed || offer.requestId != active.requestId) return ContractResult.Failure(ContractError.STATE_CONFLICT)
        val source = active.proposed.estimated.source
        if (request.estimated.source != source || request.list.source != source) {
            return ContractResult.Failure(ContractError.INVALID_INPUT)
        }
        val picked = request.offers.firstOrNull { it.id == offerId }?.takeIf { it in request.list.offers }
            ?: return ContractResult.Failure(ContractError.INVALID_INPUT)
        if (picked::class != active.offer::class) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val newPaid = (picked as? DeliveryOffer.Purchase)?.price ?: GameMoney(0)
        val diff = BigInteger.valueOf(newPaid.units) - BigInteger.valueOf(current.paid.units)
        if (diff.signum() > 0 && diff > BigInteger.valueOf(balance.units)) return ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS)
        val money = when (diff.signum()) {
            1 -> MoneyChange(MoneyDirection.DEBIT, GameMoney(diff.toLong()))
            -1 -> MoneyChange(MoneyDirection.CREDIT, GameMoney(diff.negate().toLong()))
            else -> null
        }
        val change = money?.let {
            GameChange(RequestId("delivery/${active.requestId.value}/change"), TransactionKind.DELIVERY, source, rules.ruleVersion, it)
        }
        val next = DeliveryTerms(ActiveDelivery(active.requestId, request, picked), newPaid, changeUsed = true)
        return ContractResult.Success(DeliveryChanged(next, change))
    }

    fun abandon(terms: DeliveryTerms): ContractResult<DeliveryAbandoned> {
        val current = terms.snapshot()
        val active = current.active
        if (active.offer is DeliveryOffer.Income) return ContractResult.Success(DeliveryAbandoned(current, null))
        val fee = BigInteger.valueOf(current.paid.units) * BigInteger.valueOf(rules.cancelFeePercent.toLong()) / BigInteger.valueOf(100)
        val refund = GameMoney(current.paid.units - fee.toLong())
        val change = GameChange(
            RequestId("delivery/${active.requestId.value}/settle"), TransactionKind.DELIVERY, active.proposed.estimated.source,
            rules.ruleVersion, MoneyChange(MoneyDirection.CREDIT, refund),
        )
        return ContractResult.Success(DeliveryAbandoned(current, change))
    }
}
