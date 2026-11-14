package io.drivegrowth.app.host.shop

import android.content.Context
import io.drivegrowth.app.host.*
import io.drivegrowth.app.data.*
import io.drivegrowth.app.host.facility.FacilityCommand
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.app.host.shop.decoration.*
import io.drivegrowth.core.contract.*

/** 기존 factory/프로필/출처와 F backend만 조립한다. 모듈 미활성과 이후 정의/가게 실패를 구분한다. */
internal class FactoryHomeShopPort(context: Context, private val games: GameSessionFactory) : HomeShopPort {
    private val reader = SessionHomeShopReader(context, games)
    override val expansion = SessionShopExpansionSource(context, games)
    override fun load(): ContractResult<HomeShopLoad> = when (val opened = games.open()) {
        is ContractResult.Failure -> opened
        is ContractResult.Success -> opened.value.use { game ->
            val pending = when (val result = game.store.hostCommands.load(game.identity.profileId)) {
                is ContractResult.Failure -> return@use result
                is ContractResult.Success -> result.value?.command
            }
            // 원명령 복구 전에 현재 정의·현재 꾸미기 조회를 요구하지 않는다.
            if (pending?.isHomeShopCommand() == true) return@use ContractResult.Success(HomeShopLoad(game.identity, pending))
            val active = when (val result = game.store.decorations.state(game.identity.profileId)) {
                is ContractResult.Success -> true
                is ContractResult.Failure -> if (result.error == ContractError.DATA_UNAVAILABLE) false else return@use result
            }
            when (val result = reader.read(game)) {
                is ContractResult.Failure -> result
                is ContractResult.Success -> {
                    val facts = result.value
                    if (pending == null || !active) ContractResult.Success(HomeShopLoad(game.identity, pending, facts, active))
                    else when (val decoration = decorationBackend(game.identity) { ContractResult.Success(facts.screen) }.readIn(game)) {
                        is ContractResult.Failure -> decoration
                        is ContractResult.Success -> ContractResult.Success(HomeShopLoad(game.identity, pending, facts,
                            active, decoration.value))
                    }
                }
            }
        }
    }
    override fun pendingIdentity(): ContractResult<HomeShopLoad> = when (val opened = games.open()) {
        is ContractResult.Failure -> opened
        is ContractResult.Success -> opened.value.use { game ->
            when (val slot = game.store.hostCommands.load(game.identity.profileId)) {
                is ContractResult.Failure -> slot
                is ContractResult.Success -> ContractResult.Success(HomeShopLoad(game.identity, slot.value?.command))
            }
        }
    }
    override fun original(identity: GameIdentity, request: ShopRequest,
        facility: FacilityCommand.Buy?): ContractResult<HostCheckpointResult?> = when (val opened = games.open()) {
        is ContractResult.Failure -> opened
        is ContractResult.Success -> opened.value.use { game ->
            if (game.identity != identity) return@use ContractResult.Failure(ContractError.STATE_CONFLICT)
            when (val command = request.selection.option.command) {
                is ShopCommand.Decorate -> when (val read = ShopOriginalResultReader(game.store).decoration(identity.profileId, request.requestId)) {
                    is ContractResult.Failure -> read
                    is ContractResult.Success -> ContractResult.Success(read.value?.let { HostCheckpointResult.Decoration(it) })
                }
                is ShopCommand.Expand -> when (val read = ShopOriginalResultReader(game.store).expansion(identity.profileId, request.requestId, command.offer)) {
                    is ContractResult.Failure -> read
                    is ContractResult.Success -> ContractResult.Success(read.value?.let { HostCheckpointResult.Expand(it) })
                }
                is ShopCommand.BuyFacility -> {
                    if (facility == null) return@use ContractResult.Failure(ContractError.DATA_UNAVAILABLE)
                    if (facility.identity != identity || facility.requestId != request.requestId || facility.offer != command.offer)
                        return@use ContractResult.Failure(ContractError.STATE_CONFLICT)
                    game.store.hostCommands.facilityResult(identity.profileId, HostCheckpointCommand.FacilityPurchase(
                        facility.requestId, identity.source, facility.definitionVersion, facility.offer))
                }
                null -> ContractResult.Failure(ContractError.INVALID_INPUT)
            }
        }
    }
    override fun decoration(identity: GameIdentity, currentFacts: (HomeShopFacts) -> Unit): DecorationBackend =
        decorationBackend(identity) { game ->
            when (val read = reader.read(game)) {
                is ContractResult.Failure -> read
                is ContractResult.Success -> {
                    currentFacts(read.value)
                    ContractResult.Success(read.value.screen)
                }
            }
        }
    private fun decorationBackend(identity: GameIdentity, readShop: (GameSession) -> ContractResult<ShopScreenState.Content>) =
        CheckpointDecorationBackend(games, identity, readShop)
}
