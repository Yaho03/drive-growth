package io.drivegrowth.app.host

import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.ContractError

/** 이동 안내만 계산한다. 다른 기능의 원명령을 실행·취소·변환하지 않는다. */
internal fun pendingHostPage(command: HostCheckpointCommand?): ContractResult<HostPage?> = when (command) {
    null -> ContractResult.Success(null)
    is HostCheckpointCommand.Gacha -> ContractResult.Success(HostPage.GACHA)
    is HostCheckpointCommand.Garage, is HostCheckpointCommand.Material, is HostCheckpointCommand.Repair -> ContractResult.Success(HostPage.GARAGE)
    is HostCheckpointCommand.Expand -> ContractResult.Success(HostPage.HOME)
    is HostCheckpointCommand.Quest, is HostCheckpointCommand.Delivery -> ContractResult.Failure(ContractError.STATE_CONFLICT)
}
