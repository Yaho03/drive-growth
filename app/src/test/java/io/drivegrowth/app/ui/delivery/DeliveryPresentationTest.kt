package io.drivegrowth.app.ui.delivery

import io.drivegrowth.app.data.ArrivalCommit
import io.drivegrowth.app.data.DeliveryCommit
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.delivery.DeliveryHostOperation
import io.drivegrowth.app.host.delivery.DeliveryHostRead
import io.drivegrowth.app.host.delivery.DeliveryHostReceipt
import io.drivegrowth.app.host.delivery.DeliveryNavigationSnapshot
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
        presentation.accept(data.activeFrame, DeliveryHostRead(identity, data.activeFrame.screen), allowed = true)
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

    @Test fun newerLoadingAndFailureReplaceAnOldArrivalScene() {
        val receipt = arrival(0)
        for (replacement in listOf(data.loading.screen, data.failure.screen)) {
            val presentation = DeliveryPresentation()
            val completed = data.empty.copy(version = 3)
            presentation.accept(completed, receipt, allowed = true)
            assertEquals(DeliverySceneKind.HELD, presentation.scene?.kind)
            presentation.accept(completed.copy(version = 4), receipt, allowed = true)
            assertEquals(DeliverySceneKind.HELD, presentation.scene?.kind)
            presentation.accept(DeliveryFrame(5, replacement), receipt.copy(screen = replacement), allowed = true)
            assertNull("최신 화면이 기존 도착 연출을 닫아야 한다", presentation.scene)
            presentation.accept(completed.copy(version = 6), receipt, allowed = true)
            assertNull("같은 정산을 다시 열지 않아야 한다", presentation.scene)
        }
    }

    @Test fun firstReadOfConfirmedIncomeArrivalDoesNotRequireAnActiveFrame() {
        val presentation = DeliveryPresentation()
        val receipt = arrival(1, active.copy(offer = data.income))
        presentation.accept(data.empty, receipt, allowed = true)
        assertEquals(DeliverySceneKind.INCOME_ARRIVAL, presentation.scene?.kind)
        assertEquals(data.income.reward, presentation.scene?.details?.confirmedReward)
        assertNull(presentation.scene?.details?.vehicleId)
        assertNull(presentation.scene?.details?.destinationName)
        assertNull(presentation.scene?.details?.estimatedMeters)
    }

    @Test fun originalArrivalDetailsMustMatchProfileRequestAndSource() {
        val order = active.copy(offer = data.income)
        val receipt = arrival(1, order)
        val details = data.active.copy(active = order, vehicleName = data.proposal.vehicleNames[data.income.vehicleId])
        val matching = DeliveryArrivalPresentation(identity, details)
        val foreign = matching.copy(identity = GameIdentity(ProfileId("other"), InputSource.SIMULATION))
        val otherRequest = matching.copy(delivery = details.copy(active = order.copy(requestId = RequestId("other"))))
        val vehicleEstimate = order.proposed.estimated.copy(source = InputSource.VEHICLE)
        val otherSource = matching.copy(delivery = details.copy(active = order.copy(
            proposed = order.proposed.copy(estimated = vehicleEstimate))))
        val firstView = DeliveryPresentation()
        firstView.accept(data.empty, receipt, allowed = true, arrivalPresentation = matching)
        assertEquals(details.vehicleName, firstView.scene?.details?.vehicleName)
        assertEquals(details.destinationName, firstView.scene?.details?.destinationName)
        assertEquals(order.proposed.estimated.meters, firstView.scene?.details?.estimatedMeters)
        for (mismatch in listOf(foreign, otherRequest, otherSource)) {
            val presentation = DeliveryPresentation()
            presentation.accept(data.empty, receipt, allowed = true, arrivalPresentation = mismatch)
            assertEquals(data.income.reward, presentation.scene?.details?.confirmedReward)
            assertNull(presentation.scene?.details?.vehicleId)
            assertNull(presentation.scene?.details?.destinationName)
        }
    }

    @Test fun highwayAvailabilityDoesNotDependOnObservedDistance() {
        val presentation = DeliveryPresentation()
        val missing = Observation.Missing(MissingReason.NO_DATA)
        for ((index, meters) in listOf(null, 0L, 1L, 12500L).withIndex()) {
            val frame = data.activeFrame.copy(version = index + 1L)
            val observed = meters?.let { Observation.Available(ObservedDistance(it, InputSource.SIMULATION)) } ?: missing
            val nav = DeliveryNavigationSnapshot(null, null, missing, observed, missing, null)
            val read = DeliveryHostRead(identity, frame.screen, navigation = nav)
            presentation.accept(frame, read, allowed = true)
            assertEquals(data.purchase.vehicleId, presentation.highway?.details?.vehicleId)
            assertEquals(meters, presentation.highway?.observedMeters)
            assertNull(presentation.scene)
        }
        presentation.accept(data.activeFrame.copy(version = 5), null, allowed = false)
        assertNull(presentation.highway)
    }

    @Test fun incomeAnimationUsesSettlementRewardOnlyAfterMatchingArrival() {
        val presentation = DeliveryPresentation()
        val order = active.copy(offer = data.income)
        val incomeFrame = data.activeFrame.copy(screen = DeliveryScreen.Active(data.active.copy(active = order,
            vehicleName = data.proposal.vehicleNames[data.income.vehicleId])))
        presentation.accept(incomeFrame, DeliveryHostRead(identity, incomeFrame.screen), allowed = true)
        val result = arrival(1, order)
        val receipt = (result.receipt as DeliveryHostReceipt.Arrived).value
        presentation.accept(data.empty.copy(version = 3), result.copy(receipt =
            DeliveryHostReceipt.Arrived(receipt.copy(settlement = null))), allowed = true)
        assertNull(presentation.scene)
        presentation.accept(data.empty.copy(version = 4), result, allowed = true)
        val scene = presentation.scene!!
        assertEquals(DeliverySceneKind.INCOME_ARRIVAL, scene.kind)
        assertEquals(data.income.reward, scene.details.confirmedReward)
        assertEquals(receipt.settlement?.balance, scene.details.confirmedBalance)
        assertEquals(data.income.vehicleId, scene.details.vehicleId)
        assertNull(scene.details.rarity)
    }
}
