package io.drivegrowth.app.host

import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.ContractError

/** 연결된 실제 페이지로만 이동한다. 다른 기능의 원명령을 실행·취소하지 않는다. */
internal fun pendingHostPage(command: HostCheckpointCommand?, facilityPageAvailable: Boolean = false): ContractResult<HostPage?> = when(command) {
    null -> ContractResult.Success(null)
    is HostCheckpointCommand.Gacha -> ContractResult.Success(HostPage.GACHA)
    is HostCheckpointCommand.Garage, is HostCheckpointCommand.Material, is HostCheckpointCommand.Repair -> ContractResult.Success(HostPage.GARAGE)
    is HostCheckpointCommand.Expand -> ContractResult.Success(HostPage.HOME)
    is HostCheckpointCommand.Delivery -> ContractResult.Success(HostPage.DELIVERY)
    is HostCheckpointCommand.Quest -> ContractResult.Success(HostPage.PROGRESSION)
    is HostCheckpointCommand.Decoration -> ContractResult.Failure(ContractError.STATE_CONFLICT)
    is HostCheckpointCommand.FacilityPurchase, is HostCheckpointCommand.VehicleResting ->
        if (facilityPageAvailable) ContractResult.Success(HostPage.HOME) else ContractResult.Failure(ContractError.STATE_CONFLICT)
}
