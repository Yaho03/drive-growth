package io.drivegrowth.core.delivery

import io.drivegrowth.core.content.GameContentSnapshot
import io.drivegrowth.core.content.VehicleGameData
import io.drivegrowth.core.contract.ContentVersion
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.EstimatedRouteDistance
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MissingReason
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.ObservedDistance
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.VehicleId
import java.time.LocalDate
import java.util.Collections
import kotlin.random.Random

/** 목적지 식별값. 원시 위치는 별도 데이터 정책 전까지 담지 않는다. */
@JvmInline
value class DestinationKey(val value: String) {
    init { require(value.isNotBlank()) }
}

/** 의뢰 ID `offer-N`. 한 목록 안에서만 유일하며, 다른 구간이나 날짜의 목록과 같은 값일 수 있다. */
@JvmInline
value class DeliveryOfferId(val value: String) {
    init { require(value.isNotBlank()) }
}

sealed interface DeliveryOffer {
    val id: DeliveryOfferId
    val vehicleId: VehicleId

    /** 도착 후 보상을 받는다. 차량을 얻지 않아 희귀도가 없다. */
    data class Income(
        override val id: DeliveryOfferId,
        override val vehicleId: VehicleId,
        val reward: GameMoney,
    ) : DeliveryOffer

    /** 확정할 때 결제하고 도착 후 이 희귀도의 차량을 얻는다. */
    data class Purchase(
        override val id: DeliveryOfferId,
        override val vehicleId: VehicleId,
        val rarity: Rarity,
        val price: GameMoney,
    ) : DeliveryOffer
}

/**
 * 거리 구간별 하루 목록. 저장해 다시 읽으며 재실행이나 목적지 변경으로 재추첨하지 않는다.
 * 후보가 없으면 offers가 비어 있다.
 *
 * @param nextOfferNumber 다음 의뢰 ID 번호. 완료한 자리를 채운 의뢰가 이전 ID를 다시 쓰지 않게 한다.
 */
data class DeliveryList(
    val band: Int,
    val source: InputSource,
    val createdOn: LocalDate,
    val contentVersion: ContentVersion,
    val ruleVersion: RuleVersion,
    val offers: List<DeliveryOffer>,
    val nextOfferNumber: Int,
) {
    internal fun snapshot(): DeliveryList = copy(offers = offers.frozen())

    /** 의뢰 ID가 모두 `offer-N` 형식이고 서로 다르며, 다음 번호가 기존 번호와 겹치지 않고 더 늘릴 수 있는지 본다. */
    internal fun hasValidOfferNumbers(): Boolean {
        val numbers = offers.map { it.id.value.removePrefix("offer-").toIntOrNull() }
        return nextOfferNumber in 0 until Int.MAX_VALUE &&
            numbers.all { it != null && it in 0 until nextOfferNumber } &&
            numbers.toSet().size == numbers.size
    }
}

/**
 * 한 목적지에 제시한 결과.
 *
 * @param list 저장할 구간 목록.
 * @param offers 이 거리에서 고를 수 있는 의뢰. 수익 탁송 최소 거리 미만이면 수익 의뢰를 뺀다.
 */
data class DeliveryProposed(
    val destination: DestinationKey,
    val estimated: EstimatedRouteDistance,
    val list: DeliveryList,
    val offers: List<DeliveryOffer>,
) {
    internal fun snapshot(): DeliveryProposed = copy(list = list.snapshot(), offers = offers.frozen())
}

private fun <T> List<T>.frozen(): List<T> = Collections.unmodifiableList(ArrayList(this))

/**
 * 예상 거리와 해금 차종으로 탁송 목록을 만든다. 저장과 화면은 호출자가 맡는다.
 *
 * 구간별 하루 목록, 완료한 자리만 교체, 수치는 모두 #24 제안 단계의 시험 규칙이다. 제품에 연결하기 전에 팀 합의를 확인한다.
 * 입력은 복사해 쓰고 결과는 수정할 수 없는 목록으로 돌려준다.
 */
class DeliveryProposal(private val rules: DeliveryRules) {
    /**
     * 같은 날, 같은 거리 구간·출처·버전의 저장 목록이 있으면 그대로 쓰고 난수를 쓰지 않는다.
     * 없을 때만 새로 만들며, 호출자는 결과의 list를 구간별로 저장한다.
     * 찾은 저장 목록의 의뢰 번호가 손상됐거나 금액이 GameMoney 범위를 넘으면 INVALID_INPUT이다.
     *
     * @param savedLists 저장된 구간 목록. 조건이 맞지 않는 목록은 무시한다.
     * @param today 자정 기준 날짜. 시간대 결정은 호출자가 맡는다.
     */
    fun propose(
        savedLists: List<DeliveryList>,
        destination: DestinationKey,
        estimated: Observation<EstimatedRouteDistance>,
        content: GameContentSnapshot,
        shopLevel: Int,
        today: LocalDate,
        random: Random,
    ): ContractResult<DeliveryProposed> {
        if (shopLevel < 1) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val route = when (estimated) {
            is Observation.Available -> estimated.value
            is Observation.Missing -> return ContractResult.Failure(
                if (estimated.reason == MissingReason.PERMISSION_DENIED) ContractError.PERMISSION_DENIED
                else ContractError.DATA_UNAVAILABLE,
            )
        }
        if (route.meters == 0L) return ContractResult.Failure(ContractError.INVALID_INPUT)

        val bandIndex = rules.bandIndexOf(route.meters)
        val saved = savedLists.firstOrNull {
            it.band == bandIndex && it.source == route.source && it.createdOn == today &&
                it.contentVersion == content.catalog.contentVersion && it.ruleVersion == rules.ruleVersion
        }?.snapshot()
        if (saved != null && !saved.hasValidOfferNumbers()) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val list = saved ?: newList(bandIndex, route.source, content, shopLevel, today, random)
            ?: return ContractResult.Failure(ContractError.INVALID_INPUT)
        val offers = if (route.meters < rules.incomeMinMeters) {
            list.offers.filterIsInstance<DeliveryOffer.Purchase>()
        } else {
            list.offers
        }
        return ContractResult.Success(DeliveryProposed(destination, route, list, offers.frozen()))
    }

    /**
     * 완료한 의뢰 자리만 같은 유형의 새 의뢰로 채운다. 나머지 의뢰와 다른 구간 목록은 그대로다.
     * 호출자는 가장 최근 결과 목록을 넘겨야 한다. 그러면 이미 처리한 ID는 없는 ID가 되어 다시 뽑지 않는다.
     * 버전이 다른 목록은 바꾸지 않는다. 같은 유형의 다른 의뢰와 겹치지 않는 후보가 없으면 그 자리는 비운다.
     * 구간이나 의뢰 번호가 손상됐거나 금액이 GameMoney 범위를 넘으면 INVALID_INPUT이다.
     */
    fun replaceCompleted(
        list: DeliveryList,
        offerId: DeliveryOfferId,
        content: GameContentSnapshot,
        shopLevel: Int,
        random: Random,
    ): ContractResult<DeliveryList> {
        if (shopLevel < 1) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val current = list.snapshot()
        if (current.ruleVersion != rules.ruleVersion || current.contentVersion != content.catalog.contentVersion) {
            return ContractResult.Success(current)
        }
        if (current.band !in rules.bands.indices || !current.hasValidOfferNumbers()) {
            return ContractResult.Failure(ContractError.INVALID_INPUT)
        }
        val completed = current.offers.firstOrNull { it.id == offerId } ?: return ContractResult.Success(current)
        val band = rules.bands[current.band]
        val taken = current.offers
            .filter { it.id != offerId && it::class == completed::class }
            .map { it.vehicleId }
            .toSet()
        val vehicle = content.unlockedAt(shopLevel).filter { it.vehicleId !in taken }.randomOrNull(random)
        val replacement = if (vehicle == null) {
            null
        } else {
            when (completed) {
                is DeliveryOffer.Income -> incomeOffer(vehicle, band, current.nextOfferNumber)
                is DeliveryOffer.Purchase -> purchaseOffer(vehicle, band, current.nextOfferNumber, random)
            } ?: return ContractResult.Failure(ContractError.INVALID_INPUT)
        }
        return ContractResult.Success(
            current.copy(
                offers = current.offers.mapNotNull { if (it.id == offerId) replacement else it }.frozen(),
                nextOfferNumber = current.nextOfferNumber + 1,
            ),
        )
    }

    /**
     * 진행 중 목적지를 바꿀 때 보여 줄 변경 목록을 만든다. 하루 목록과 별도이며 저장된 하루 목록을 바꾸지 않는다.
     *
     * - 구간은 실제 이동 거리 + 새 목적지까지의 예상 거리로 정한다. 예상 거리는 목록 기준에만 쓰고 기본 주행 수익으로 지급하지 않는다.
     * - 진행 중인 의뢰와 같은 유형의 의뢰만 만든다. 구매를 수익으로 바꿔 포기하면 취소 수수료를 피할 수 있어 유형 전환을 막는다(#24 제안 단계).
     * - 구매 후보는 뽑은 희귀도를 1단계 낮추고 가격도 낮춘 희귀도로 계산한다. 일반은 일반으로 둔다(#24 제안 단계).
     * - 거리를 얻지 못했거나 출처가 의뢰와 다르면 실패한다. 후보가 없으면 빈 목록이며 변경할 수 없다.
     * - 의뢰 ID는 `change-N`이다. 하루 목록의 `offer-N`과 겹치지 않아, 변경 뒤 완료해도 하루 목록의 다른 자리를 바꾸지 않는다.
     */
    fun proposeChange(
        active: ActiveDelivery,
        traveled: Observation<ObservedDistance>,
        remaining: Observation<EstimatedRouteDistance>,
        destination: DestinationKey,
        content: GameContentSnapshot,
        shopLevel: Int,
        today: LocalDate,
        random: Random,
    ): ContractResult<DeliveryChangeOffer> {
        if (shopLevel < 1) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val moved = when (traveled) {
            is Observation.Available -> traveled.value
            is Observation.Missing -> return ContractResult.Failure(missing(traveled.reason))
        }
        val route = when (remaining) {
            is Observation.Available -> remaining.value
            is Observation.Missing -> return ContractResult.Failure(missing(remaining.reason))
        }
        val source = active.proposed.estimated.source
        if (moved.source != source || route.source != source) return ContractResult.Failure(ContractError.INVALID_INPUT)
        if (route.meters == 0L || moved.meters > Long.MAX_VALUE - route.meters) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val combined = moved.meters + route.meters
        val bandIndex = rules.bandIndexOf(combined)
        val band = rules.bands[bandIndex]
        val unlocked = content.unlockedAt(shopLevel)
        val offers = ArrayList<DeliveryOffer>()
        when (active.offer) {
            is DeliveryOffer.Income -> if (combined >= rules.incomeMinMeters) {
                for (vehicle in unlocked.shuffled(random).take(rules.incomeOfferCount)) {
                    offers += incomeOffer(vehicle, band, offers.size, CHANGE_PREFIX)
                        ?: return ContractResult.Failure(ContractError.INVALID_INPUT)
                }
            }
            is DeliveryOffer.Purchase -> for (vehicle in unlocked.shuffled(random).take(rules.purchaseOfferCount)) {
                offers += purchaseOffer(vehicle, band, offers.size, random, lowerRarity = true, prefix = CHANGE_PREFIX)
                    ?: return ContractResult.Failure(ContractError.INVALID_INPUT)
            }
        }
        val list = DeliveryList(
            bandIndex, source, today, content.catalog.contentVersion, rules.ruleVersion, offers.frozen(), offers.size,
        )
        return ContractResult.Success(
            DeliveryChangeOffer(active.requestId, moved.meters, combined, DeliveryProposed(destination, route, list, list.offers)),
        )
    }

    private fun missing(reason: MissingReason) =
        if (reason == MissingReason.PERMISSION_DENIED) ContractError.PERMISSION_DENIED else ContractError.DATA_UNAVAILABLE

    private fun newList(
        bandIndex: Int,
        source: InputSource,
        content: GameContentSnapshot,
        shopLevel: Int,
        today: LocalDate,
        random: Random,
    ): DeliveryList? {
        val band = rules.bands[bandIndex]
        val unlocked = content.unlockedAt(shopLevel)
        val offers = ArrayList<DeliveryOffer>()
        for (vehicle in unlocked.shuffled(random).take(rules.incomeOfferCount)) {
            offers += incomeOffer(vehicle, band, offers.size) ?: return null
        }
        for (vehicle in unlocked.shuffled(random).take(rules.purchaseOfferCount)) {
            offers += purchaseOffer(vehicle, band, offers.size, random) ?: return null
        }
        return DeliveryList(
            bandIndex, source, today, content.catalog.contentVersion, rules.ruleVersion, offers.frozen(), offers.size,
        )
    }

    private fun incomeOffer(vehicle: VehicleGameData, band: DeliveryBand, number: Int, prefix: String = OFFER_PREFIX): DeliveryOffer.Income? {
        val reward = rules.incomeReward(vehicle.price, band) ?: return null
        return DeliveryOffer.Income(DeliveryOfferId("$prefix$number"), vehicle.vehicleId, reward)
    }

    private fun purchaseOffer(
        vehicle: VehicleGameData,
        band: DeliveryBand,
        number: Int,
        random: Random,
        lowerRarity: Boolean = false,
        prefix: String = OFFER_PREFIX,
    ): DeliveryOffer.Purchase? {
        val picked = pickRarity(band, random)
        val rarity = if (lowerRarity) Rarity.entries[(picked.ordinal - 1).coerceAtLeast(0)] else picked
        val price = rules.purchasePrice(vehicle.price, rarity) ?: return null
        return DeliveryOffer.Purchase(DeliveryOfferId("$prefix$number"), vehicle.vehicleId, rarity, price)
    }

    private companion object {
        const val OFFER_PREFIX = "offer-"
        const val CHANGE_PREFIX = "change-"
    }

    private fun pickRarity(band: DeliveryBand, random: Random): Rarity {
        var roll = random.nextInt(band.rarityWeights.values.sum())
        for (rarity in Rarity.entries) {
            roll -= band.rarityWeights[rarity] ?: 0
            if (roll < 0) return rarity
        }
        error("가중치 합 계산 오류")
    }
}
