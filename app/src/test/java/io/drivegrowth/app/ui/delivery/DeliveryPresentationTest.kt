package io.drivegrowth.app.ui.delivery

import io.drivegrowth.app.data.ArrivalCommit
import io.drivegrowth.app.data.DeliveryCommit
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.delivery.DeliveryHostOperation
import io.drivegrowth.app.host.delivery.DeliveryHostRead
import io.drivegrowth.app.host.delivery.DeliveryHostReceipt
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import org.junit.Assert.*
import org.junit.Test

class DeliveryPresentationTest {
    private val data = DeliveryPreviewStates
    private val active = data.active.active
    private val identity = GameIdentity(ProfileId("fixture"), InputSource.SIMULATION)
    private val route = RouteId("fixture-route")
    private val time = EpochMillis(1)
    private val rules = DeliverySettlementRules(active.proposed.list.ruleVersion, 100, 100, 5)

    private fun selected(screen: DeliveryScreen = data.activeFrame.screen): DeliveryHostRead {
        val selected = (DeliverySelection.select(active.proposed, active.offer.id, active.requestId, GameMoney(1000), null)
            as ContractResult.Success).value
        return DeliveryHostRead(identity, screen,
            pending = DeliveryHostOperation.Select(active.proposed, active.offer.id, active.requestId, route, time),
            receipt = DeliveryHostReceipt.Selected(DeliveryCommit(selected, selected.payment?.let(::settlement))))
    }

    private fun settlement(change: GameChange): Settlement = Settlement(SettlementId("fixture-settlement"),
        Transaction(TransactionId("fixture-transaction"), change.requestId, change.kind, change.source, change.ruleVersion, change.money),
        GameMoney(200), (change.vehicle as? VehicleWrite.Add)?.vehicle, time)

    private fun arrival(slots: Int, order: ActiveDelivery = active): DeliveryHostRead {
        val evidence = Observation.Available(DeliveryArrivalEvidence(route, InputSource.SIMULATION, time))
        val outcome = (DeliverySettlement(rules).resolve(DeliveryInProgress(order, route, time), evidence, time, slots)
            as ContractResult.Success).value
        val change = when (outcome) {
            is DeliveryOutcome.IncomeCompleted -> outcome.change
            is DeliveryOutcome.VehicleDelivered -> outcome.change
            else -> null
        }
        return DeliveryHostRead(identity, data.empty.screen,
            pending = DeliveryHostOperation.Arrival(rules, order.requestId, evidence, time, order.proposed.list, order.proposed.list),
            receipt = DeliveryHostReceipt.Arrived(ArrivalCommit(outcome, change?.let(::settlement))))
    }

    @Test fun confirmedSelectionPlaysOnceAcrossRefreshAndViewRecreation() {
        val presentation = DeliveryPresentation()
        val frame = data.activeFrame
        val result = selected()
        presentation.accept(frame, result, allowed = true)
        val first = presentation.scene!!
        assertEquals(DeliverySceneKind.DEPARTURE, first.kind)
        assertEquals(data.active.vehicleName, first.details.vehicleName)
        assertEquals(data.active.destinationName, first.details.destinationName)
        assertEquals(active.proposed.estimated.meters, first.details.estimatedMeters)
        assertEquals(data.purchase.rarity, first.details.rarity)
        presentation.dismiss()
        presentation.accept(frame.copy(version = 10), result, allowed = true)
        assertNull(presentation.scene)
        val restored = DeliveryPresentation(presentation.save())
        restored.accept(frame.copy(version = 11), result, allowed = true)
        assertNull(restored.scene)
    }

    @Test fun parkedReadAndWaitingFrameNeverManufactureASuccessScene() {
        val presentation = DeliveryPresentation()
        presentation.accept(data.activeFrame, null, allowed = true)
        assertNull(presentation.scene)
        val arrival = arrival(1)
        val waiting = arrival.copy(screen = data.awaiting.screen,
            receipt = DeliveryHostReceipt.Arrived(ArrivalCommit(DeliveryOutcome.AwaitingEvidence(time), null)))
        presentation.accept(data.awaiting, waiting, allowed = true)
        assertNull(presentation.scene)
        val refund = (arrival.receipt as DeliveryHostReceipt.Arrived).value.settlement!!
        val refundChange = GameChange(refund.transaction.requestId, TransactionKind.DELIVERY, InputSource.SIMULATION,
            rules.ruleVersion, money = MoneyChange(MoneyDirection.CREDIT, data.purchase.price))
        val refunded = arrival.copy(receipt = DeliveryHostReceipt.Arrived(
            ArrivalCommit(DeliveryOutcome.PurchaseRefunded(refundChange), settlement(refundChange))))
        presentation.accept(data.empty.copy(version = 4), refunded, allowed = true)
        assertNull(presentation.scene)
    }

    @Test fun wrongFrameAndRestrictedLateReceiptDoNotStartOrRestartMotion() {
        val presentation = DeliveryPresentation()
        val result = selected()
        presentation.accept(data.loading, result, allowed = true)
        assertNull(presentation.scene)
        presentation.accept(data.activeFrame, result, allowed = false)
        assertNull(presentation.scene)
        presentation.accept(data.activeFrame, result, allowed = true)
        assertNull(presentation.scene)
    }

    @Test fun losingAccessClosesCurrentSceneWithoutReplayingIt() {
        val presentation = DeliveryPresentation()
        val result = selected()
        presentation.accept(data.activeFrame, result, allowed = true)
        assertNotNull(presentation.scene)
        presentation.accept(data.activeFrame, result, allowed = false)
        presentation.accept(data.activeFrame, result, allowed = true)
        assertNull(presentation.scene)
    }

    @Test fun fullGarageArrivalKeepsTheConfirmedHeldVehicleAndNeverLooksLikeOwnership() {
        val presentation = DeliveryPresentation()
        presentation.accept(data.activeFrame, null, allowed = true)
        val held = arrival(0)
        presentation.accept(data.empty.copy(version = 3), held, allowed = true)
        val scene = presentation.scene!!
        assertEquals(DeliverySceneKind.HELD, scene.kind)
        assertEquals(data.purchase.vehicleId, scene.details.vehicleId)
        assertEquals(data.purchase.rarity, scene.details.rarity)
        assertEquals(data.active.destinationName, scene.details.destinationName)
        assertNull(scene.details.confirmedReward)
        presentation.dismiss()
        presentation.accept(data.empty.copy(version = 4), held, allowed = true)
        assertNull(presentation.scene)
    }

    @Test fun incomeAnimationUsesSettlementRewardOnlyAfterMatchingArrival() {
        val presentation = DeliveryPresentation()
        val order = active.copy(offer = data.income)
        val incomeFrame = data.activeFrame.copy(screen = DeliveryScreen.Active(data.active.copy(active = order,
            vehicleName = data.proposal.vehicleNames[data.income.vehicleId])))
        presentation.accept(incomeFrame, null, allowed = true)
        val result = arrival(1, order)
        val receipt = (result.receipt as DeliveryHostReceipt.Arrived).value
        presentation.accept(data.empty.copy(version = 3), result.copy(receipt =
            DeliveryHostReceipt.Arrived(receipt.copy(settlement = null))), allowed = true)
        assertNull(presentation.scene)
        presentation.accept(data.empty.copy(version = 4), result, allowed = true)
        val scene = presentation.scene!!
        assertEquals(DeliverySceneKind.INCOME_ARRIVAL, scene.kind)
        assertEquals(data.income.reward, scene.details.confirmedReward)
        assertEquals(data.income.vehicleId, scene.details.vehicleId)
        assertNull(scene.details.rarity)
    }
}
