package io.drivegrowth.app.ui.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.delivery.DeliveryHostOperation
import io.drivegrowth.app.host.delivery.DeliveryHostRead
import io.drivegrowth.app.host.delivery.DeliveryHostReceipt
import io.drivegrowth.core.contract.GameChange
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.contract.Observation
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.contract.VehicleWrite
import io.drivegrowth.core.delivery.ActiveDelivery
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.delivery.DeliveryOutcome

/** 확정 원결과의 표시 키. 같은 세션에서 새 View를 만들어도 다시 재생하지 않는다. */
data class DeliverySceneKey internal constructor(
    internal val identity: GameIdentity,
    internal val requestId: RequestId,
    internal val arrival: Boolean,
)

/** 호스트가 도착을 확정한 동일 프로필·의뢰의 표시 정보. 원정산과 함께 공급한다. */
data class DeliveryArrivalPresentation(val identity: GameIdentity, val delivery: DeliveryActiveUi)

enum class DeliveryPresentationCue { DEPARTURE, INCOME_ARRIVAL, VEHICLE_ARRIVAL, HELD }

internal enum class DeliverySceneKind(val durationMillis: Long, val cue: DeliveryPresentationCue) {
    DEPARTURE(2500, DeliveryPresentationCue.DEPARTURE),
    INCOME_ARRIVAL(2000, DeliveryPresentationCue.INCOME_ARRIVAL),
    VEHICLE_ARRIVAL(2000, DeliveryPresentationCue.VEHICLE_ARRIVAL),
    HELD(2000, DeliveryPresentationCue.HELD),
}

internal data class DeliverySceneDetails(
    val vehicleId: VehicleId?,
    val vehicleName: String?,
    val rarity: Rarity?,
    val destinationName: String?,
    val estimatedMeters: Long?,
    val source: InputSource,
    val purchase: Boolean,
    val expectedReward: GameMoney?,
    val confirmedReward: GameMoney? = null,
)

internal data class DeliveryScene(val key: DeliverySceneKey, val kind: DeliverySceneKind, val details: DeliverySceneDetails)
internal data class DeliveryHighway(val details: DeliverySceneDetails, val observedMeters: Long?)

internal class DeliveryPresentation(seenKeys: Set<DeliverySceneKey> = emptySet()) {
    private val seen = seenKeys.toMutableSet()
    private var previousActive: DeliveryArrivalPresentation? = null
    private var sceneVersion: Long? = null
    private var version = -1L
    var scene: DeliveryScene? = null
        private set
    var highway: DeliveryHighway? = null
        private set

    fun save(): Set<DeliverySceneKey> = seen.toSet()

    fun dismiss() {
        scene = null
        sceneVersion = null
    }

    fun accept(
        frame: DeliveryFrame,
        read: DeliveryHostRead?,
        allowed: Boolean,
        arrivalPresentation: DeliveryArrivalPresentation? = null,
    ) {
        if (!allowed) { dismiss(); highway = null }
        if (frame.version < version) return
        version = frame.version
        val active = when (val screen = frame.screen) {
            is DeliveryScreen.Active -> screen.delivery
            is DeliveryScreen.AwaitingResult -> screen.delivery
            is DeliveryScreen.ChangeReview -> screen.before
            is DeliveryScreen.AbandonReview -> screen.delivery
            else -> null
        }
        val current = read?.takeIf { it.screen == frame.screen }
        val result = current?.let { host ->
            when (val receipt = host.receipt) {
                is DeliveryHostReceipt.Selected -> departure(host, receipt, active)
                is DeliveryHostReceipt.Arrived -> arrival(host, receipt, arrivalPresentation)
                else -> null
            }
        }
        // 같은 원결과의 재렌더만 이어 간다. 최신 로딩·실패·다른 화면을 연출 뒤에 숨기지 않는다.
        val visibleScene = scene
        if (visibleScene != null && frame.version != sceneVersion && result?.key != visibleScene.key) dismiss()
        if (result != null) {
            if (seen.add(result.key) && allowed) scene = result
            if (scene?.key == result.key) sceneVersion = frame.version
        }

        val activeScreen = frame.screen as? DeliveryScreen.Active
        val observed = (current?.navigation?.observed as? Observation.Available)?.value
            ?.takeIf { it.source == activeScreen?.delivery?.active?.proposed?.estimated?.source }
        highway = activeScreen?.takeIf { allowed }?.let { DeliveryHighway(details(it.delivery), observed?.meters) }
        if (current != null && active != null && active.active.proposed.estimated.source == current.identity.source) {
            previousActive = DeliveryArrivalPresentation(current.identity, active)
        } else if (current?.receipt is DeliveryHostReceipt.Abandoned) {
            previousActive = null
        }
    }

    private fun departure(read: DeliveryHostRead, receipt: DeliveryHostReceipt.Selected, active: DeliveryActiveUi?): DeliveryScene? {
        val operation = read.pending as? DeliveryHostOperation.Select ?: return null
        val selected = receipt.value.selected
        val order = selected.active
        if (read.screen !is DeliveryScreen.Active || active?.active != order || operation.requestId != order.requestId ||
            operation.proposed != order.proposed || operation.offerId != order.offer.id ||
            read.identity.source != order.proposed.estimated.source) return null
        val payment = selected.payment
        val settlement = receipt.value.settlement
        when (val offer = order.offer) {
            is DeliveryOffer.Income -> if (payment != null || settlement != null) return null
            is DeliveryOffer.Purchase -> {
                val money = payment?.money ?: return null
                if (payment.requestId != order.requestId || money.direction != MoneyDirection.DEBIT ||
                    money.amount != offer.price || !matches(payment, settlement)) return null
            }
        }
        return DeliveryScene(DeliverySceneKey(read.identity, order.requestId, false), DeliverySceneKind.DEPARTURE, details(active))
    }

    private fun arrival(
        read: DeliveryHostRead,
        receipt: DeliveryHostReceipt.Arrived,
        presentation: DeliveryArrivalPresentation?,
    ): DeliveryScene? {
        if (read.screen !is DeliveryScreen.Offers && read.screen !is DeliveryScreen.Destination) return null
        val operation = read.pending as? DeliveryHostOperation.Arrival ?: return null
        val prior = listOfNotNull(presentation, previousActive).firstOrNull {
            it.identity == read.identity && it.delivery.active.requestId == operation.requestId &&
                it.delivery.active.proposed.estimated.source == read.identity.source
        }?.delivery
        val key = DeliverySceneKey(read.identity, operation.requestId, true)
        val settlement = receipt.value.settlement
        return when (val outcome = receipt.value.outcome) {
            is DeliveryOutcome.IncomeCompleted -> {
                if (!matchesArrival(operation.requestId, read.identity.source, outcome.change, settlement)) return null
                val reward = settlement?.transaction?.money?.takeIf { it.direction == MoneyDirection.CREDIT }?.amount ?: return null
                val details = prior?.takeIf { it.active.offer is DeliveryOffer.Income }?.let(::details)
                    ?: DeliverySceneDetails(null, null, null, null, null, read.identity.source, purchase = false, expectedReward = null)
                DeliveryScene(key, DeliverySceneKind.INCOME_ARRIVAL, details.copy(confirmedReward = reward))
            }
            is DeliveryOutcome.VehicleDelivered -> {
                if (!matchesArrival(operation.requestId, read.identity.source, outcome.change, settlement)) return null
                val vehicle = settlement?.changedVehicle ?: return null
                DeliveryScene(key, DeliverySceneKind.VEHICLE_ARRIVAL,
                    purchaseDetails(prior, vehicle.vehicleId, vehicle.rarity, read.identity.source))
            }
            is DeliveryOutcome.VehicleHeld -> {
                val held = outcome.held
                if (settlement != null || held.requestId != operation.requestId || held.source != read.identity.source) return null
                DeliveryScene(key, DeliverySceneKind.HELD,
                    purchaseDetails(prior, held.vehicle.vehicleId, held.vehicle.rarity, read.identity.source))
            }
            else -> null
        }
    }

    private fun purchaseDetails(prior: DeliveryActiveUi?, id: VehicleId, rarity: Rarity, source: InputSource): DeliverySceneDetails =
        prior?.takeIf { it.active.offer is DeliveryOffer.Purchase && it.active.offer.vehicleId == id }
            ?.let { details(it).copy(rarity = rarity) }
            ?: DeliverySceneDetails(id, null, rarity, null, null, source, purchase = true, expectedReward = null)

    private fun matchesArrival(requestId: RequestId, source: InputSource, change: GameChange, settlement: Settlement?) =
        change.requestId.value == "delivery/${requestId.value}/settle" && change.source == source && matches(change, settlement)

    private fun matches(change: GameChange, settlement: Settlement?) = settlement != null &&
        settlement.transaction.requestId == change.requestId && settlement.transaction.kind == change.kind &&
        settlement.transaction.source == change.source && settlement.transaction.ruleVersion == change.ruleVersion &&
        settlement.transaction.money == change.money && settlement.changedVehicle == (change.vehicle as? VehicleWrite.Add)?.vehicle

    private fun details(value: DeliveryActiveUi): DeliverySceneDetails {
        val order: ActiveDelivery = value.active
        val offer = order.offer
        return DeliverySceneDetails(offer.vehicleId, value.vehicleName, (offer as? DeliveryOffer.Purchase)?.rarity,
            value.destinationName, order.proposed.estimated.meters, order.proposed.estimated.source,
            offer is DeliveryOffer.Purchase, (offer as? DeliveryOffer.Income)?.reward)
    }
}
