package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*

/** 기존 core/저장 입력의 표시만 담당한다. */
object DeliveryHostMapper {
    fun proposal(value: DeliveryProposed, names: Map<VehicleId, String>, destination: String?,
        balance: GameMoney, source: InputSource): DeliveryProposalUi {
        val availability = value.offers.associate { offer ->
            val reason = structuralReason(value, offer, names, source)
                ?: if (offer is DeliveryOffer.Purchase && offer.price.units > balance.units) "게임머니가 부족해요." else null
            offer.id to DeliveryAvailability(reason == null, reason)
        }
        return DeliveryProposalUi(value, destination, names, availability,
            "수익 탁송은 도착 확인 후 보상을 받아요. 구매 탁송은 지금 결제하고 도착 확인 후 차량을 받아요.")
    }

    fun changeProposal(terms: DeliveryTerms, change: DeliveryChangeOffer, rules: DeliveryChangeRules,
        names: Map<VehicleId, String>, destination: String?, balance: GameMoney, source: InputSource): DeliveryProposalUi {
        val base = proposal(change.proposed, names, destination, balance, source)
        val availability = change.proposed.offers.associate { offer ->
            val structural = structuralReason(change.proposed, offer, names, source)
            val reason = structural ?: when (val preview = DeliveryChange(rules).change(terms, change, offer.id, balance)) {
                is ContractResult.Success -> null
                is ContractResult.Failure -> if (preview.error == ContractError.INSUFFICIENT_FUNDS) "차액을 결제할 게임머니가 부족해요." else "이 후보로 변경할 수 없어요."
            }
            offer.id to DeliveryAvailability(reason == null, reason)
        }
        return base.copy(availability = availability, confirmationTerms = "후보 선택 뒤 기존 결제금과의 차액을 확인하고 변경을 확정해요.")
    }
    private fun structuralReason(value: DeliveryProposed, offer: DeliveryOffer, names: Map<VehicleId, String>, source: InputSource): String? = when {
        value.estimated.source != source || value.list.source != source -> "목적지와 게임 기록의 입력 출처가 달라요."
        value.offers.count { it.id == offer.id } != 1 || value.list.offers.count { it.id == offer.id } != 1 || offer !in value.list.offers -> "후보 정보를 다시 확인해 주세요."
        names[offer.vehicleId].isNullOrBlank() -> "차량 정보를 확인할 수 없어요."
        else -> null
    }

    fun active(terms: DeliveryTerms, names: Map<VehicleId, String>, destination: String?,
        observed: Observation<ObservedDistance>): DeliveryActiveUi {
        val distance = (observed as? Observation.Available)?.value
            ?.takeIf { it.source == terms.active.proposed.estimated.source }
        return DeliveryActiveUi(terms.active, destination, names[terms.active.offer.vehicleId],
            distance?.let { "실제 관측 이동 거리 ${it.meters} m" }, "목적지 도착 증거를 확인하고 있어요.",
            DeliveryAvailability(!terms.changeUsed, if (terms.changeUsed) "목적지 변경 기회를 이미 사용했어요." else null),
            DeliveryAvailability(true))
    }

    fun abandon(terms: DeliveryTerms, rules: DeliveryChangeRules, names: Map<VehicleId, String>,
        destination: String?): DeliveryScreen.AbandonReview {
        val ui = active(terms, names, destination, Observation.Missing(MissingReason.NO_DATA))
        return when (val calculated = DeliveryChange(rules).abandon(terms)) {
            is ContractResult.Failure -> DeliveryScreen.AbandonReview(ui, null, null, null,
                DeliveryAvailability(false, "포기 조건을 확인할 수 없어요."))
            is ContractResult.Success -> {
                // core가 확정한 환급에서 이미 낸 대금과의 표시상 차이만 구한다. 수수료 규칙을 다시 계산하지 않는다.
                val refund = calculated.value.change?.money?.amount ?: GameMoney(0)
                DeliveryScreen.AbandonReview(ui, GameMoney(terms.paid.units - refund.units), refund,
                    abandonNote(terms.active.offer), DeliveryAvailability(true))
            }
        }
    }

    /** 포기 안내는 의뢰 종류에 맞춘다. 수익 탁송은 결제가 없어 수수료·환급이 아니라 완료 보상만 사라진다. */
    internal fun abandonNote(offer: DeliveryOffer): String = when (offer) {
        is DeliveryOffer.Income ->
            "포기하면 의뢰가 종료돼요. 완료 보상은 받지 못하지만 벌금은 없어요. 이미 운전한 거리의 기본 주행 수익은 그대로예요."
        is DeliveryOffer.Purchase ->
            "포기하면 의뢰가 종료돼요. 구매 차량은 받지 못하며 표시된 수수료를 공제한 금액을 돌려받아요."
    }
}
