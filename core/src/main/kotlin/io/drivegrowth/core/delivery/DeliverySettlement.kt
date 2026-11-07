package io.drivegrowth.core.delivery

import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.DeliveryArrivalEvidence
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.OwnedVehicle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.RouteId
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.TransactionKind
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.contract.VehicleWrite
import java.math.BigInteger

/**
 * 도착 정산 규칙. 시험값은 PRD의 미도착 24시간, 임시 보관 24시간, 보관 만료 수수료 5%다.
 *
 * @param arrivalWindowMs 의뢰 시작부터 도착을 인정하는 기간.
 * @param holdWindowMs 차고가 가득 찬 구매 차량을 임시 보관하는 기간.
 * @param holdRefundFeePercent 보관 기한이 지나 환급할 때 공제하는 수수료. 수수료는 내림한다.
 */
data class DeliverySettlementRules(
    val ruleVersion: RuleVersion,
    val arrivalWindowMs: Long,
    val holdWindowMs: Long,
    val holdRefundFeePercent: Int,
) {
    init {
        require(arrivalWindowMs > 0 && holdWindowMs > 0) { "기간은 양수" }
        require(holdRefundFeePercent in 0..100) { "수수료는 0~100%" }
    }
}

/**
 * 진행 중인 의뢰.
 *
 * @param route 앱이 이 의뢰의 현재 경로에 발급한 세대. 목적지 변경이나 재요청마다 새로 발급한다.
 * @param startedAt 미도착 기한을 세는 시작 시각.
 */
data class DeliveryInProgress(
    val active: ActiveDelivery,
    val route: RouteId,
    val startedAt: EpochMillis,
)

/**
 * 차고가 가득 차 임시 보관 중인 구매 차량. 결제는 이미 끝났다.
 *
 * @param price 실제로 낸 구매 대금. 보관 만료 환급의 기준이다.
 */
data class HeldVehicle(
    val requestId: RequestId,
    val source: InputSource,
    val vehicle: OwnedVehicle,
    val price: GameMoney,
    val heldAt: EpochMillis,
    val deadline: EpochMillis,
)

sealed interface DeliveryOutcome {
    /** 도착을 확인하지 못했다. 지급하지 않고 기한까지 기다린다. */
    data class AwaitingEvidence(val deadline: EpochMillis) : DeliveryOutcome

    /** 수익 탁송 완료. 보상을 지급한다. */
    data class IncomeCompleted(val change: GameChange) : DeliveryOutcome

    /** 구매 탁송 완료. 차량을 지급한다. */
    data class VehicleDelivered(val change: GameChange) : DeliveryOutcome

    /** 구매 탁송이 도착했지만 차고가 가득 차 임시 보관한다. 돈과 차량은 바뀌지 않는다. */
    data class VehicleHeld(val held: HeldVehicle) : DeliveryOutcome

    /** 수익 탁송이 기한 안에 도착하지 않았다. 벌금 없이 끝난다. */
    data object IncomeExpired : DeliveryOutcome

    /** 구매 탁송이 기한 안에 도착하지 않았다. 수수료 없이 전액 환급한다. */
    data class PurchaseRefunded(val change: GameChange) : DeliveryOutcome
}

sealed interface HoldOutcome {
    /** 아직 칸이 없고 기한 전이다. */
    data class StillHeld(val held: HeldVehicle) : HoldOutcome

    /** 칸이 생겨 차량을 받는다. */
    data class Claimed(val change: GameChange) : HoldOutcome

    /** 기한이 지나 수수료를 공제하고 환급한다. */
    data class Refunded(val change: GameChange) : HoldOutcome
}

/**
 * 도착 정산을 계산한다. 저장, 실제 내비 어댑터, 화면은 담당하지 않는다.
 *
 * - 도착은 활성 경로 세대와 출처가 같고 기한 안에 받은 증거만 인정한다. 정차, P, 거리 0, 증거 누락은 도착이 아니다.
 * - 증거 생성 쪽은 현재 경로에 연결할 근거(#80)가 있을 때만 [DeliveryArrivalEvidence]를 만든다. 이 계산은 그 연결을 다시 증명하지 못한다.
 * - 완료·환급은 같은 요청 ID(`delivery/<requestId>/settle`)를 써서 원장에서 한 번만 확정된다. 보관 수령·환급은 `delivery/<requestId>/hold`다.
 * - 호출자는 결과와 활성 의뢰 종료, 완료 자리 교체를 한 트랜잭션으로 저장한다.
 *
 * 미도착 기한의 시작 시점과 수수료 반올림은 PRD에 정해지지 않아 호출자 입력과 규칙으로 둔다.
 */
class DeliverySettlement(private val rules: DeliverySettlementRules) {
    fun resolve(
        progress: DeliveryInProgress,
        evidence: Observation<DeliveryArrivalEvidence>,
        now: EpochMillis,
        freeSlots: Int,
    ): ContractResult<DeliveryOutcome> {
        if (freeSlots < 0) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val active = progress.active
        val source = active.proposed.estimated.source
        val deadline = add(progress.startedAt, rules.arrivalWindowMs) ?: return ContractResult.Failure(ContractError.INVALID_INPUT)
        val arrival = (evidence as? Observation.Available)?.value?.takeIf {
            it.matches(progress.route, source) && it.receivedAt.value >= progress.startedAt.value && it.receivedAt.value < deadline.value
        }
        val settleId = RequestId("delivery/${active.requestId.value}/settle")
        val offer = active.offer
        if (arrival != null) {
            return ContractResult.Success(
                when (offer) {
                    is DeliveryOffer.Income -> DeliveryOutcome.IncomeCompleted(credit(settleId, source, offer.reward))
                    is DeliveryOffer.Purchase -> {
                        val vehicle = vehicleFor(active.requestId, offer)
                        if (freeSlots > 0) {
                            DeliveryOutcome.VehicleDelivered(
                                GameChange(settleId, TransactionKind.DELIVERY, source, rules.ruleVersion, vehicle = VehicleWrite.Add(vehicle)),
                            )
                        } else {
                            val holdDeadline = add(arrival.receivedAt, rules.holdWindowMs)
                                ?: return ContractResult.Failure(ContractError.INVALID_INPUT)
                            DeliveryOutcome.VehicleHeld(
                                HeldVehicle(active.requestId, source, vehicle, offer.price, arrival.receivedAt, holdDeadline),
                            )
                        }
                    }
                },
            )
        }
        if (now.value < deadline.value) return ContractResult.Success(DeliveryOutcome.AwaitingEvidence(deadline))
        return ContractResult.Success(
            when (offer) {
                is DeliveryOffer.Income -> DeliveryOutcome.IncomeExpired
                is DeliveryOffer.Purchase -> DeliveryOutcome.PurchaseRefunded(credit(settleId, source, offer.price))
            },
        )
    }

    /** 임시 보관 차량을 처리한다. 기한 전이고 칸이 있으면 수령, 기한이 지나면 수수료를 공제해 환급한다. */
    fun resolveHold(held: HeldVehicle, now: EpochMillis, freeSlots: Int): ContractResult<HoldOutcome> {
        if (freeSlots < 0) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val holdId = RequestId("delivery/${held.requestId.value}/hold")
        if (now.value >= held.deadline.value) {
            val fee = BigInteger.valueOf(held.price.units) * BigInteger.valueOf(rules.holdRefundFeePercent.toLong()) / BigInteger.valueOf(100)
            return ContractResult.Success(HoldOutcome.Refunded(credit(holdId, held.source, GameMoney(held.price.units - fee.toLong()))))
        }
        if (freeSlots == 0) return ContractResult.Success(HoldOutcome.StillHeld(held))
        return ContractResult.Success(
            HoldOutcome.Claimed(
                GameChange(holdId, TransactionKind.DELIVERY, held.source, rules.ruleVersion, vehicle = VehicleWrite.Add(held.vehicle)),
            ),
        )
    }

    private fun credit(id: RequestId, source: InputSource, amount: GameMoney) =
        GameChange(id, TransactionKind.DELIVERY, source, rules.ruleVersion, MoneyChange(MoneyDirection.CREDIT, amount))

    private fun vehicleFor(requestId: RequestId, offer: DeliveryOffer.Purchase) = OwnedVehicle(
        OwnedVehicleId("delivery/${requestId.value}"), offer.vehicleId, 1, Revision(0), VehicleLifecycle.OWNED,
        offer.rarity, 0, offer.price, VehicleAcquisitionSource.PURCHASE_DELIVERY,
    )

    private fun add(at: EpochMillis, ms: Long): EpochMillis? =
        if (at.value > Long.MAX_VALUE - ms) null else EpochMillis(at.value + ms)
}
