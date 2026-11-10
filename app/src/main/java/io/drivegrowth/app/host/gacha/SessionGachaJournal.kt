package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.data.*
import io.drivegrowth.core.contract.*

/** 같은 factory가 고정한 실제 profile/source/store만 사용한다. 자체 DB·권위·재화는 없다. */
class SessionGachaJournal(private val games: GameSessionFactory) : GachaCommandJournal {
    private fun <T> use(action: (GameSession) -> ContractResult<T>): ContractResult<T> = when (val opened = games.open()) {
        is ContractResult.Failure -> opened
        is ContractResult.Success -> opened.value.use(action)
    }
    override fun pending(): ContractResult<GachaCommand?> = use { session ->
        when (val read = session.store.hostCommands.load(session.identity.profileId)) {
            is ContractResult.Failure -> read
            is ContractResult.Success -> when (val command = read.value?.command) {
                null -> ContractResult.Success(null)
                is HostCheckpointCommand.Gacha -> ContractResult.Success(command.value)
                else -> ContractResult.Failure(ContractError.STATE_CONFLICT)
            }
        }
    }
    override fun save(command: GachaCommand): ContractResult<Unit> = use { session ->
        when (val saved = session.store.hostCommands.save(session.identity.profileId, HostCheckpointCommand.Gacha(command))) {
            is ContractResult.Failure -> saved
            is ContractResult.Success -> ContractResult.Success(Unit)
        }
    }
    override fun cancelUnstarted(command: GachaCommand) = use { it.store.hostCommands.cancelUnstarted(it.identity.profileId, HostCheckpointCommand.Gacha(command)) }
    override fun execute(command: GachaCommand) = use { it.store.hostCommands.execute(it.identity.profileId, HostCheckpointCommand.Gacha(command)).gacha() }
    override fun finish(command: GachaCommand) = use { it.store.hostCommands.finish(it.identity.profileId, HostCheckpointCommand.Gacha(command)).gacha() }
}
private fun ContractResult<HostCheckpointResult>.gacha(): ContractResult<GachaSettlement> = when (this) {
    is ContractResult.Failure -> this
    is ContractResult.Success -> when (val result = value) {
        is HostCheckpointResult.Gacha -> ContractResult.Success(result.value)
        else -> ContractResult.Failure(ContractError.STATE_CONFLICT)
    }
}
