package io.drivegrowth.app.ui.delivery

import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import java.time.LocalDate

/** 고정 UI 입력. 실제 SDK·DB·지급 결과가 아니다. */
object DeliveryPreviewStates {
    val income = DeliveryOffer.Income(DeliveryOfferId("offer-0"), VehicleId("fixture-van"), GameMoney(120))
    val purchase = DeliveryOffer.Purchase(DeliveryOfferId("offer-1"), VehicleId("fixture-car"), Rarity.RARE, GameMoney(800))
    val proposed = DeliveryProposed(DestinationKey("fixture-destination"), EstimatedRouteDistance(12500, InputSource.SIMULATION),
        DeliveryList(1, InputSource.SIMULATION, LocalDate.of(2026, 11, 7), ContentVersion("fixture-content"),
            RuleVersion("fixture-rule"), listOf(income, purchase), 2), listOf(income, purchase))
    val proposal = DeliveryProposalUi(proposed, "시험 목적지", mapOf(income.vehicleId to "수익 시험 차량", purchase.vehicleId to "구매 시험 차량"),
        mapOf(income.id to DeliveryAvailability(true), purchase.id to DeliveryAvailability(true)), "확정 때 전액 결제 · 차량은 도착 후 수령")
    val active = DeliveryActiveUi(ActiveDelivery(RequestId("fixture-request"), proposed, purchase), "시험 목적지", "구매 시험 차량", null, null,
        DeliveryAvailability(true, "변경 기회 1회 남음"), DeliveryAvailability(true))
    val offers = DeliveryFrame(1, DeliveryScreen.Offers(proposal))

    val empty = DeliveryFrame(2, DeliveryScreen.Offers(proposal.copy(proposed = proposed.copy(offers = emptyList()))))
    val missing = DeliveryFrame(2, DeliveryScreen.Destination(null, null, null, false, "예상 경로가 제공되지 않았어요"))
    val loading = DeliveryFrame(2, DeliveryScreen.Loading)
    val failure = DeliveryFrame(2, DeliveryScreen.Failure("고정 실패 입력 · 저장 결과를 확인하지 못했어요", true))
    val unsupported = DeliveryFrame(2, DeliveryScreen.Unsupported("현재 목적지 입력 경로를 지원하지 않아요"))
    val activeFrame = DeliveryFrame(2, DeliveryScreen.Active(active))
    val awaiting = DeliveryFrame(3, DeliveryScreen.AwaitingResult(active, "도착 증거·정산 결과 확인 대기 · 완료 아님"))
    val change = DeliveryFrame(3, DeliveryScreen.ChangeReview(active, proposal, purchase.id,
        DeliveryMoneyUi(MoneyDirection.CREDIT, GameMoney(20)), "희귀도 변경 결과 · 고정 시험 입력", DeliveryAvailability(true)))
    val abandon = DeliveryFrame(3, DeliveryScreen.AbandonReview(active, GameMoney(80), GameMoney(720),
        "포기 후 목록 유지 · 차량 지급 없음 · 고정 시험 입력", DeliveryAvailability(true)))
    val many: DeliveryFrame
        get() {
            val candidates = (0 until 30).map { index -> purchase.copy(id = DeliveryOfferId("offer-$index")) }
            val result = proposed.copy(offers = candidates, list = proposed.list.copy(offers = candidates, nextOfferNumber = 30))
            return DeliveryFrame(2, DeliveryScreen.Offers(proposal.copy(proposed = result,
                availability = candidates.associate { it.id to DeliveryAvailability(true) })))
        }
    val longText = DeliveryFrame(2, DeliveryScreen.Offers(proposal.copy(destinationName = "긴 목적지 설명 ".repeat(12),
        vehicleNames = mapOf(purchase.vehicleId to "긴 차량 이름과 조건 설명 ".repeat(10)), confirmationTerms = "지급 예정 조건을 확인해 주세요. ".repeat(20))))
}
