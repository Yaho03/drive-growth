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
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RuleVersion
import io.drivegrowth.core.contract.VehicleId
import java.time.LocalDate
import kotlin.random.Random

/** 목적지 식별값. 원시 위치는 별도 데이터 정책 전까지 담지 않는다. */
@JvmInline
value class DestinationKey(val value: String) {
    init { require(value.isNotBlank()) }
}

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
)

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
)

/** 예상 거리와 해금 차종으로 탁송 목록을 만든다. 저장과 화면은 호출자가 맡는다. */
class DeliveryProposal(private val rules: DeliveryRules) {
    /**
     * 같은 날, 같은 거리 구간·출처·버전의 저장 목록이 있으면 그대로 쓰고 난수를 쓰지 않는다.
     * 없을 때만 새로 만들며, 호출자는 결과의 list를 구간별로 저장한다.
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

        val band = rules.bandOf(route.meters)
        val bandIndex = rules.bands.indexOf(band)
        val list = savedLists.firstOrNull {
            it.band == bandIndex && it.source == route.source && it.createdOn == today &&
                it.contentVersion == content.catalog.contentVersion && it.ruleVersion == rules.ruleVersion
        } ?: newList(bandIndex, band, route.source, content, shopLevel, today, random)
        val offers = if (route.meters < rules.incomeMinMeters) {
            list.offers.filterIsInstance<DeliveryOffer.Purchase>()
        } else {
            list.offers
        }
        return ContractResult.Success(DeliveryProposed(destination, route, list, offers))
    }

    /**
     * 완료한 의뢰 자리만 같은 유형의 새 의뢰로 채운다. 나머지 의뢰와 다른 구간 목록은 그대로다.
     * 이미 처리한 ID나 버전이 다른 목록은 그대로 돌려주어 재실행으로 재추첨하지 않는다.
     * 같은 유형의 다른 의뢰와 겹치지 않는 후보가 없으면 그 자리는 비운다.
     */
    fun replaceCompleted(
        list: DeliveryList,
        offerId: DeliveryOfferId,
        content: GameContentSnapshot,
        shopLevel: Int,
        random: Random,
    ): ContractResult<DeliveryList> {
        if (shopLevel < 1) return ContractResult.Failure(ContractError.INVALID_INPUT)
        val completed = list.offers.firstOrNull { it.id == offerId }
        if (completed == null || list.ruleVersion != rules.ruleVersion ||
            list.contentVersion != content.catalog.contentVersion
        ) {
            return ContractResult.Success(list)
        }
        val band = rules.bands[list.band]
        val taken = list.offers
            .filter { it.id != offerId && it::class == completed::class }
            .map { it.vehicleId }
            .toSet()
        val vehicle = content.unlockedAt(shopLevel).filter { it.vehicleId !in taken }.randomOrNull(random)
        val replacement = vehicle?.let {
            when (completed) {
                is DeliveryOffer.Income -> incomeOffer(it, band, list.nextOfferNumber)
                is DeliveryOffer.Purchase -> purchaseOffer(it, band, list.nextOfferNumber, random)
            }
        }
        return ContractResult.Success(
            list.copy(
                offers = list.offers.mapNotNull { if (it.id == offerId) replacement else it },
                nextOfferNumber = list.nextOfferNumber + 1,
            ),
        )
    }

    private fun newList(
        bandIndex: Int,
        band: DeliveryBand,
        source: InputSource,
        content: GameContentSnapshot,
        shopLevel: Int,
        today: LocalDate,
        random: Random,
    ): DeliveryList {
        val unlocked = content.unlockedAt(shopLevel)
        val incomes = unlocked.shuffled(random).take(rules.incomeOfferCount)
        val purchases = unlocked.shuffled(random).take(rules.purchaseOfferCount)
        var number = 0
        val offers = incomes.map { incomeOffer(it, band, number++) } +
            purchases.map { purchaseOffer(it, band, number++, random) }
        return DeliveryList(
            bandIndex, source, today, content.catalog.contentVersion, rules.ruleVersion, offers, number,
        )
    }

    private fun incomeOffer(vehicle: VehicleGameData, band: DeliveryBand, number: Int) = DeliveryOffer.Income(
        DeliveryOfferId("offer-$number"),
        vehicle.vehicleId,
        rules.incomeReward(vehicle.price, band),
    )

    private fun purchaseOffer(vehicle: VehicleGameData, band: DeliveryBand, number: Int, random: Random): DeliveryOffer.Purchase {
        val rarity = pickRarity(band, random)
        return DeliveryOffer.Purchase(
            DeliveryOfferId("offer-$number"),
            vehicle.vehicleId,
            rarity,
            rules.purchasePrice(vehicle.price, rarity),
        )
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
