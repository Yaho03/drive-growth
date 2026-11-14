package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.core.contract.*

/** 같은 factory의 실제 profile/source/단일 슬롯만 소비한다. SDK나 새 원장을 소유하지 않는다. */
class SessionDeliveryJournal(private val games: GameSessionFactory) : DeliveryOperationJournal {
    private var nonfinal: Pair<GameIdentity, HostCheckpointCommand.Delivery>? = null
    private fun <T> use(identity: GameIdentity, action: (GameSession) -> ContractResult<T>): ContractResult<T> =
        when (val opened = games.open()) {
            is ContractResult.Failure -> opened
            is ContractResult.Success -> opened.value.use {
                if (identity != it.identity) ContractResult.Failure(ContractError.STATE_CONFLICT) else action(it)
            }
        }
    override fun pending(identity: GameIdentity): ContractResult<DeliveryHostOperation?> = when (val result = slot(identity)) {
        is ContractResult.Failure -> result
        is ContractResult.Success -> when (val slot = result.value) {
            DeliveryJournalSlot.Clear -> ContractResult.Success(null)
            DeliveryJournalSlot.Foreign -> conflict()
            is DeliveryJournalSlot.Own -> ContractResult.Success(slot.operation)
        }
    }

    override fun slot(identity: GameIdentity): ContractResult<DeliveryJournalSlot> = use(identity) { game ->
        when (val loaded = game.store.hostCommands.load(identity.profileId)) {
            is ContractResult.Failure -> loaded
            is ContractResult.Success -> when (val command = loaded.value?.command) {
                null -> ContractResult.Success(DeliveryJournalSlot.Clear)
                is HostCheckpointCommand.Delivery -> if (command.source == identity.source)
                    ContractResult.Success(DeliveryJournalSlot.Own(command.operation.host())) else conflict()
                else -> ContractResult.Success(DeliveryJournalSlot.Foreign)
            }
        }
    }
    private fun command(game: GameSession, operation: DeliveryHostOperation, creating: Boolean): ContractResult<HostCheckpointCommand.Delivery> {
        val pending = when (val read = game.store.hostCommands.load(game.identity.profileId)) {
            is ContractResult.Failure -> return read
            is ContractResult.Success -> read.value?.command
        }
        if (pending != null) return if (pending is HostCheckpointCommand.Delivery && pending.source == game.identity.source && pending.operation.host() == operation)
            ContractResult.Success(pending) else conflict()
        if (!creating) return ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
        val content = when (val read = game.content) { is ContractResult.Failure -> return read; is ContractResult.Success -> read.value }
        return ContractResult.Success(HostCheckpointCommand.Delivery(content.catalog.contentVersion, game.identity.source, operation.checkpoint()))
    }
    override fun save(identity: GameIdentity, operation: DeliveryHostOperation) = use(identity) { game ->
        when (val command = command(game, operation, true)) {
            is ContractResult.Failure -> command
            is ContractResult.Success -> game.store.hostCommands.save(identity.profileId, command.value).unit()
        }
    }
    override fun execute(identity: GameIdentity, operation: DeliveryHostOperation) = use(identity) { game ->
        val command = when (val read = command(game, operation, false)) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        when (val result = game.store.hostCommands.execute(identity.profileId, command)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> {
                val delivery = (result.value as? HostCheckpointResult.Delivery)?.value ?: return@use conflict()
                nonfinal = if (!delivery.isFinal) identity to command else null
                ContractResult.Success(delivery.host())
            }
        }
    }
    override fun cancelUnstarted(identity: GameIdentity, operation: DeliveryHostOperation) = use(identity) { game ->
        when (val command = command(game, operation, false)) {
            is ContractResult.Failure -> command
            is ContractResult.Success -> game.store.hostCommands.cancelUnstarted(identity.profileId, command.value)
        }
    }
    override fun finish(identity: GameIdentity, operation: DeliveryHostOperation) = use(identity) { game ->
        val command = when (val read = command(game, operation, false)) { is ContractResult.Failure -> return@use read; is ContractResult.Success -> read.value }
        // 명시적으로 반환받은 AwaitingEvidence/StillHeld는 지급 확정이 아니다.
        // E가 SAVED로 되돌린 같은 원명령만 해소하며 STARTED/불명 결과는 자동 취소하지 않는다.
        val result = if (nonfinal == (identity to command)) game.store.hostCommands.cancelUnstarted(identity.profileId, command)
            else game.store.hostCommands.finish(identity.profileId, command).unit()
        if (result is ContractResult.Success) nonfinal = null
        result
    }
}
private fun <T> conflict(): ContractResult<T> = ContractResult.Failure(ContractError.STATE_CONFLICT)
private fun ContractResult<*>.unit(): ContractResult<Unit> = when(this) {
    is ContractResult.Failure -> this
    is ContractResult.Success -> ContractResult.Success(Unit)
}
internal fun DeliveryHostOperation.checkpoint(): DeliveryCheckpointOperation = when(this) {
    is DeliveryHostOperation.Select -> DeliveryCheckpointOperation.Select(proposed, offerId, requestId, route, at)
    is DeliveryHostOperation.Change -> DeliveryCheckpointOperation.Change(rules, offer, offerId, route, observedRoute)
    is DeliveryHostOperation.Abandon -> DeliveryCheckpointOperation.Abandon(rules, requestId)
    is DeliveryHostOperation.Arrival -> DeliveryCheckpointOperation.Arrival(rules, requestId, evidence, now, expected, replacement)
    is DeliveryHostOperation.Hold -> DeliveryCheckpointOperation.Hold(rules, requestId, now)
}
internal fun DeliveryCheckpointOperation.host(): DeliveryHostOperation = when(this) {
    is DeliveryCheckpointOperation.Select -> DeliveryHostOperation.Select(proposed, offerId, requestId, route, at)
    is DeliveryCheckpointOperation.Change -> DeliveryHostOperation.Change(rules, offer, offerId, route, observedRoute)
    is DeliveryCheckpointOperation.Abandon -> DeliveryHostOperation.Abandon(rules, requestId)
    is DeliveryCheckpointOperation.Arrival -> DeliveryHostOperation.Arrival(rules, requestId, evidence, now, expected, replacement)
    is DeliveryCheckpointOperation.Hold -> DeliveryHostOperation.Hold(rules, requestId, now)
}
private fun DeliveryCheckpointReceipt.host(): DeliveryHostReceipt = when(this) {
    is DeliveryCheckpointReceipt.Selected -> DeliveryHostReceipt.Selected(value)
    is DeliveryCheckpointReceipt.Changed -> DeliveryHostReceipt.Changed(value)
    is DeliveryCheckpointReceipt.Abandoned -> DeliveryHostReceipt.Abandoned(value)
    is DeliveryCheckpointReceipt.Arrived -> DeliveryHostReceipt.Arrived(value)
    is DeliveryCheckpointReceipt.Held -> DeliveryHostReceipt.Held(value)
}
