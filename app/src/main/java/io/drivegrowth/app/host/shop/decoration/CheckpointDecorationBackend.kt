package io.drivegrowth.app.host.shop.decoration

import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.*
import io.drivegrowth.app.ui.shop.ShopScreenState
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*

internal fun decorationPending(identity: GameIdentity, checkpoint: HostCommandCheckpoint?): DecorationPending {
    if (checkpoint == null) return DecorationPending.Empty
    val command = (checkpoint.command as? HostCheckpointCommand.Decoration)?.value
    return if (checkpoint.profileId == identity.profileId && command?.profileId == identity.profileId && command.source == identity.source)
        DecorationPending.Own(command) else DecorationPending.Foreign
}

internal fun decorationReceipt(command: DecorationCommand, result: HostCheckpointResult): ContractResult<DecorationSettlement> {
    val receipt = (result as? HostCheckpointResult.Decoration)?.value
        ?: return ContractResult.Failure(ContractError.STATE_CONFLICT)
    return when (val checked = replayDecorationChange(command, receipt)) {
        is ContractResult.Failure -> checked
        is ContractResult.Success -> ContractResult.Success(receipt)
    }
}

/** E 공개 API만 호출한다. 실제 SQL/마커/지갑/정산을 만들지 않고 같은 factory 연결을 작업마다 닫는다. */
class CheckpointDecorationBackend(private val games: GameSessionFactory, private val identity: GameIdentity,
    private val readShop: (GameSession) -> ContractResult<ShopScreenState.Content>) : DecorationBackend {
    private fun <T> use(command: DecorationCommand? = null, action: (GameSession) -> ContractResult<T>): ContractResult<T> {
        if (command != null && (command.profileId != identity.profileId || command.source != identity.source))
            return ContractResult.Failure(ContractError.STATE_CONFLICT)
        return when (val opened = games.open()) {
            is ContractResult.Failure -> opened
            is ContractResult.Success -> opened.value.use { game ->
                if (game.identity != identity) ContractResult.Failure(ContractError.STATE_CONFLICT) else action(game)
            }
        }
    }

    /** 미리보기/조회와 분리된 명시 활성화다. 다른 기능의 한 슬롯을 건드리지 않는다. */
    override fun activate(): ContractResult<Unit> = use { game ->
        when (val slot = game.store.hostCommands.load(identity.profileId)) {
            is ContractResult.Failure -> return@use slot
            is ContractResult.Success -> if (slot.value != null) return@use ContractResult.Failure(ContractError.STATE_CONFLICT)
        }
        when (val result = game.store.decorations.activate(identity.profileId)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> if (result.value.profileId == identity.profileId && result.value.source == identity.source)
                ContractResult.Success(Unit) else ContractResult.Failure(ContractError.STATE_CONFLICT)
        }
    }
    override fun pending(): ContractResult<DecorationPending> = use { game ->
        when (val result = game.store.hostCommands.load(identity.profileId)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> ContractResult.Success(decorationPending(identity, result.value))
        }
    }

    /** 같은 연결의 현재 상태/정의/가게 표시 공급이다. 순차 조회이며 원자 snapshot을 주장하지 않는다. */
    override fun read(): ContractResult<DecorationFacts> = use { game ->
        val state = when (val result = game.store.decorations.state(identity.profileId)) {
            is ContractResult.Failure -> return@use result
            is ContractResult.Success -> result.value
        }
        val definitions = when (val result = game.store.decorations.definitions()) {
            is ContractResult.Failure -> return@use result
            is ContractResult.Success -> result.value
        }
        when (val shop = readShop(game)) {
            is ContractResult.Failure -> shop
            is ContractResult.Success -> ContractResult.Success(DecorationFacts(state, definitions, shop.value))
        }
    }

    override fun save(command: DecorationCommand): ContractResult<Unit> = use(command) { game ->
        val wrapped = HostCheckpointCommand.Decoration(command)
        when (val result = game.store.hostCommands.save(identity.profileId, wrapped)) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> if (result.value.profileId == identity.profileId && result.value.command == wrapped)
                ContractResult.Success(Unit) else ContractResult.Failure(ContractError.STATE_CONFLICT)
        }
    }
    override fun execute(command: DecorationCommand): ContractResult<DecorationSettlement> = use(command) { game ->
        when (val result = game.store.hostCommands.execute(identity.profileId, HostCheckpointCommand.Decoration(command))) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> decorationReceipt(command, result.value)
        }
    }
    override fun finish(command: DecorationCommand): ContractResult<Unit> = use(command) { game ->
        when (val result = game.store.hostCommands.finish(identity.profileId, HostCheckpointCommand.Decoration(command))) {
            is ContractResult.Failure -> result
            is ContractResult.Success -> when (val checked = decorationReceipt(command, result.value)) {
                is ContractResult.Failure -> checked
                is ContractResult.Success -> ContractResult.Success(Unit)
            }
        }
    }
    override fun cancelSaved(command: DecorationCommand): ContractResult<Unit> = use(command) { game ->
        game.store.hostCommands.cancelUnstarted(identity.profileId, HostCheckpointCommand.Decoration(command))
    }
}
