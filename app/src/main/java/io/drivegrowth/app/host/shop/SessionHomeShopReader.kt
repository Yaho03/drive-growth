package io.drivegrowth.app.host.shop

import android.content.Context
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.host.facility.FacilityFacts
import io.drivegrowth.app.host.facility.FacilityHostMapper
import io.drivegrowth.app.host.facility.FactoryFacilityHostSource
import io.drivegrowth.app.ui.shop.ShopScreenState
import io.drivegrowth.core.contract.*

/** 동일 연결에서 읽은 HOME 사실이며 표시 revision이나 꾸미기 소유/외형을 만들지 않는다. */
internal data class HomeShopFacts(
    val identity: GameIdentity,
    val expansion: ShopExpansionSnapshot,
    val facility: FacilityFacts,
) {
    val screen: ShopScreenState.Content
        get() = FacilityHostMapper.merge(expansion.screen, facility, true)
}

/** worker 전용. 전달된 GameSession의 읽기 범위만 빌리며 새 연결을 열거나 닫지 않는다. */
internal class SessionHomeShopReader(context: Context, games: GameSessionFactory) {
    private val context = context.applicationContext
    private val facilities = FactoryFacilityHostSource(context, games)

    fun read(game: GameSession): ContractResult<HomeShopFacts> {
        val identity = game.identity
        val store = game.store
        if (store.source != identity.source) return ContractResult.Failure(ContractError.INVALID_INPUT)
        return store.atomic {
            val expansion = when (val result = StoredShopExpansionSource(context, identity.source,
                identity.profileId, { store }).readIn(store)) {
                is ContractResult.Failure -> return@atomic result
                is ContractResult.Success -> result.value
            }
            val facility = when (val result = facilities.read(game)) {
                is ContractResult.Failure -> return@atomic result
                is ContractResult.Success -> result.value
            }
            val screen = expansion.screen
            if (expansion.profile != identity.profileId || expansion.source != identity.source ||
                facility.identity != identity || screen.balance != facility.balance ||
                screen.level != facility.shop.progress.shopLevel ||
                screen.ownedCount != facility.shop.progress.ownedVehicles ||
                screen.capacity != facility.shop.capacity ||
                screen.vehicles.map { it.id }.toSet() != facility.vehicles.map { it.ownedVehicleId }.toSet())
                return@atomic ContractResult.Failure(ContractError.STATE_CONFLICT)
            ContractResult.Success(HomeShopFacts(identity, expansion, facility))
        }
    }
}
