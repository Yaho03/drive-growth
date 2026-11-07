package io.drivegrowth.app.ui.delivery

import io.drivegrowth.core.delivery.ActiveDelivery
import io.drivegrowth.core.delivery.DeliveryOfferId
import io.drivegrowth.core.delivery.DeliveryProposed
import java.util.Collections

/** UI 입력·응답만 관리한다. SDK·DB·거래·비동기 작업을 소유하지 않는다. */
class DeliveryInteraction(restored: DeliveryInteractionState = DeliveryInteractionState()) {
    var frame: DeliveryFrame? = restored.frame?.snapshot()
        private set
    var pending: DeliveryRequest? = restored.pending?.copy(command = restored.pending.command.snapshot())
        private set
    var controlsEnabled = false
        private set
    private var nextSequence = restored.nextSequence
    private var released = false

    fun save() = DeliveryInteractionState(frame, pending, nextSequence)

    fun render(value: DeliveryFrame, enabled: Boolean): Boolean {
        if (released) return false
        // 거절할 오래된 응답이어도 최신 제한은 적용한다. 오래된 허용으로 제한을 풀지는 않는다.
        if (!enabled) controlsEnabled = false
        val current = frame
        if (current != null && (value.version < current.version ||
                value.version == current.version && value != current)) return false
        frame = value.snapshot()
        controlsEnabled = enabled
        return true
    }

    fun canRequest(command: DeliveryCommand): Boolean = !released && controlsEnabled && pending == null &&
        when (val state = frame?.screen) {
            is DeliveryScreen.Destination -> command == DeliveryCommand.ChooseDestination(null) && state.chooseAllowed
            is DeliveryScreen.Failure -> command == DeliveryCommand.Refresh && state.retryAllowed
            is DeliveryScreen.Offers -> command is DeliveryCommand.Select && command.proposed == state.proposal.proposed &&
                selectable(state.proposal, command.offerId)
            is DeliveryScreen.Active -> when (command) {
                is DeliveryCommand.ChooseDestination -> command.active == state.delivery.active && state.delivery.change.allowed
                is DeliveryCommand.ReviewAbandon -> command.active == state.delivery.active && state.delivery.abandon.allowed
                else -> false
            }
            is DeliveryScreen.ChangeReview -> command == DeliveryCommand.ExitReview ||
                command is DeliveryCommand.Change && command.active == state.before.active &&
                command.proposed == state.after.proposed && command.offerId == state.offerId &&
                state.permission.allowed && state.before.change.allowed && state.difference != null &&
                !state.rarityNotice.isNullOrBlank() && selectable(state.after, command.offerId)
            is DeliveryScreen.AbandonReview -> command == DeliveryCommand.ExitReview ||
                command == DeliveryCommand.Abandon(state.delivery.active) && state.permission.allowed &&
                state.delivery.abandon.allowed && state.fee != null && state.refund != null && !state.consequence.isNullOrBlank()
            else -> false
        }

    fun request(command: DeliveryCommand): DeliveryRequest? {
        if (!canRequest(command)) return null
        return DeliveryRequest(nextSequence++, frame!!.version, command.snapshot()).also { pending = it }
    }

    /** 실제 응답에만 사용한다. 일반 render나 문구 변경은 미확정 요청을 해제하지 않는다. */
    fun complete(request: DeliveryRequest, result: DeliveryFrame): Boolean {
        if (released || request != pending || result.version <= (frame?.version ?: -1)) return false
        frame = result.snapshot()
        pending = null
        return true
    }

    fun release() { released = true; controlsEnabled = false; pending = null }

    private fun selectable(value: DeliveryProposalUi, id: DeliveryOfferId) =
        value.proposed.offers.count { it.id == id } == 1 && value.proposed.list.offers.count { it.id == id } == 1 &&
            value.availability[id]?.allowed == true && !value.confirmationTerms.isNullOrBlank()
}

private fun DeliveryProposed.snapshot() = copy(
    offers = Collections.unmodifiableList(ArrayList(offers)),
    list = list.copy(offers = Collections.unmodifiableList(ArrayList(list.offers))),
)
private fun ActiveDelivery.snapshot() = copy(proposed = proposed.snapshot())
private fun DeliveryProposalUi.snapshot() = copy(proposed = proposed.snapshot(),
    vehicleNames = Collections.unmodifiableMap(HashMap(vehicleNames)), availability = Collections.unmodifiableMap(HashMap(availability)))
private fun DeliveryActiveUi.snapshot() = copy(active = active.snapshot())
private fun DeliveryFrame.snapshot(): DeliveryFrame = copy(screen = when (val value = screen) {
    is DeliveryScreen.Offers -> value.copy(proposal = value.proposal.snapshot())
    is DeliveryScreen.Active -> value.copy(delivery = value.delivery.snapshot())
    is DeliveryScreen.ChangeReview -> value.copy(before = value.before.snapshot(), after = value.after.snapshot())
    is DeliveryScreen.AbandonReview -> value.copy(delivery = value.delivery.snapshot())
    is DeliveryScreen.AwaitingResult -> value.copy(delivery = value.delivery.snapshot())
    else -> value
})

private fun DeliveryCommand.snapshot(): DeliveryCommand = when (this) {
    is DeliveryCommand.Select -> copy(proposed = proposed.snapshot())
    is DeliveryCommand.Change -> copy(active = active.snapshot(), proposed = proposed.snapshot())
    is DeliveryCommand.ChooseDestination -> copy(active = active?.snapshot())
    is DeliveryCommand.ReviewAbandon -> copy(active = active.snapshot())
    is DeliveryCommand.Abandon -> copy(active = active.snapshot())
    else -> this
}
