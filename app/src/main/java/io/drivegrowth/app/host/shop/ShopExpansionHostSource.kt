package io.drivegrowth.app.host.shop

import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.app.ui.shop.ShopRequest
import io.drivegrowth.app.ui.shop.ShopScreenState
import io.drivegrowth.core.contract.*

/** revision은 UI 표시 세대이며 DB revision으로 사용하지 않는다. */
data class ShopExpansionSnapshot(val profile: ProfileId, val source: InputSource, val definitionVersion: ContentVersion,
    val completedRentals: Int, val screen: ShopScreenState.Content)

/** worker 전용. prepare 성공은 같은 전체 명령의 durable save 완료를 의미한다. */
interface ShopExpansionHostSource {
    fun read(): ContractResult<ShopExpansionSnapshot>
    fun prepare(snapshot: ShopExpansionSnapshot, request: ShopRequest): ContractResult<HostCheckpointCommand.Expand>
    fun pending(): ContractResult<HostCheckpointCommand.Expand?>
    fun execute(command: HostCheckpointCommand.Expand): ContractResult<Settlement>
    fun finish(command: HostCheckpointCommand.Expand): ContractResult<Settlement>
    fun cancelSaved(command: HostCheckpointCommand.Expand): ContractResult<Unit>
}
