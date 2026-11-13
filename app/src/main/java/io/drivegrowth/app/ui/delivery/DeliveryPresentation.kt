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

enum class DeliveryPresentationCue { DEPARTURE, INCOME_ARRIVAL, VEHICLE_ARRIVAL, HELD }

internal enum class DeliverySceneKind(val durationMillis: Long, val cue: DeliveryPresentationCue) {
    DEPARTURE(2500, DeliveryPresentationCue.DEPARTURE),
    INCOME_ARRIVAL(2000, DeliveryPresentationCue.INCOME_ARRIVAL),
    VEHICLE_ARRIVAL(2000, DeliveryPresentationCue.VEHICLE_ARRIVAL),
    HELD(2000, DeliveryPresentationCue.HELD),
}

internal data class DeliverySceneDetails(
    val vehicleId: VehicleId,
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
internal data class DeliveryHighway(val details: DeliverySceneDetails, val observedMeters: Long?) {
    val moving get() = observedMeters != null && observedMeters > 0
}

internal class DeliveryPresentation(
    seenKeys: Set<DeliverySceneKey> = emptySet(),
    restoredActive: DeliveryActiveUi? = null,
) {
    private val seen = seenKeys.toMutableSet()
    private var previousActive = restoredActive
    private var identity: GameIdentity? = null
    private var version = -1L
    var scene: DeliveryScene? = null
        private set
    var highway: DeliveryHighway? = null
        private set

    fun save(): Set<DeliverySceneKey> = seen.toSet()

    fun dismiss() { scene = null }

    fun accept(frame: DeliveryFrame, read: DeliveryHostRead?, allowed: Boolean) {
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
        if (scene?.kind == DeliverySceneKind.DEPARTURE &&
            (frame.screen !is DeliveryScreen.Active || active?.active?.requestId != scene?.key?.requestId)) dismiss()
        val current = read?.takeIf { it.screen == frame.screen }
        val currentIdentity = current?.identity
        if (currentIdentity != null) {
            if (identity != null && currentIdentity != identity) {
                previousActive = null
                dismiss()
            }
            identity = currentIdentity
        }
        val observed = (current?.navigation?.observed as? Observation.Available)?.value
            ?.takeIf { it.source == active?.active?.proposed?.estimated?.source }
        highway = (frame.screen as? DeliveryScreen.Active)?.delivery?.takeIf { allowed }
            ?.let { DeliveryHighway(details(it), observed?.meters) }

        val result = current?.let { when (val receipt = it.receipt) {
            is DeliveryHostReceipt.Selected -> departure(it, receipt, active)
            is DeliveryHostReceipt.Arrived -> arrival(it, receipt)
            else -> null
        } }
        if (result != null && seen.add(result.key) && allowed) scene = result
        if (active != null) previousActive = active
        else if (current?.receipt is DeliveryHostReceipt.Abandoned) previousActive = null
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

    private fun arrival(read: DeliveryHostRead, receipt: DeliveryHostReceipt.Arrived): DeliveryScene? {
        val operation = read.pending as? DeliveryHostOperation.Arrival ?: return null
        val prior = previousActive?.takeIf {
            it.active.requestId == operation.requestId && it.active.proposed.estimated.source == read.identity.source
        }
        val key = DeliverySceneKey(read.identity, operation.requestId, true)
        val settlement = receipt.value.settlement
        return when (val outcome = receipt.value.outcome) {
            is DeliveryOutcome.IncomeCompleted -> {
                if (prior == null || prior.active.offer !is DeliveryOffer.Income ||
                    !matchesArrival(operation.requestId, read.identity.source, outcome.change, settlement)) return null
                val reward = settlement?.transaction?.money?.takeIf { it.direction == MoneyDirection.CREDIT }?.amount ?: return null
                DeliveryScene(key, DeliverySceneKind.INCOME_ARRIVAL, details(prior).copy(confirmedReward = reward))
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
