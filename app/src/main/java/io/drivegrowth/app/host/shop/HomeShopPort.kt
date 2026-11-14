package io.drivegrowth.app.host.shop

import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.data.HostCheckpointResult
import io.drivegrowth.app.host.facility.FacilityCommand
import io.drivegrowth.app.ui.shop.ShopRequest
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.shop.decoration.DecorationBackend
import io.drivegrowth.app.host.shop.decoration.DecorationFacts
import io.drivegrowth.core.contract.ContractResult

/** HOME 조회·조립용 입력이다. pending이 있으면 당시 명령을 먼저 복구하며 현재 정의를 요구하지 않는다. */
internal data class HomeShopLoad(val identity: GameIdentity, val pending: HostCheckpointCommand?,
    val facts: HomeShopFacts? = null, val decorationActive: Boolean? = null, val decorationFacts: DecorationFacts? = null)

internal interface HomeShopPort {
    val expansion: ShopExpansionHostSource
    fun load(): ContractResult<HomeShopLoad>
    /** 현재 정의/표시 조회보다 먼저 원요청 복구의 실제 identity와 슬롯만 읽는다. */
    fun pendingIdentity(): ContractResult<HomeShopLoad>
    fun original(identity: GameIdentity, request: ShopRequest, facility: FacilityCommand.Buy?): ContractResult<HostCheckpointResult?>
    fun decoration(identity: GameIdentity, currentFacts: (HomeShopFacts) -> Unit): DecorationBackend
}

internal fun HostCheckpointCommand.isHomeShopCommand(): Boolean = when (this) {
    is HostCheckpointCommand.Decoration, is HostCheckpointCommand.Expand,
    is HostCheckpointCommand.FacilityPurchase, is HostCheckpointCommand.VehicleResting -> true
    else -> false
}
