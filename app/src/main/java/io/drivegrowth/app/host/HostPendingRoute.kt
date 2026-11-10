package io.drivegrowth.app.host

import io.drivegrowth.app.data.HostCheckpointCommand
import io.drivegrowth.core.contract.ContractResult

/** 연결된 실제 페이지로만 이동한다. 다른 기능의 원명령을 실행·취소하지 않는다. */
internal fun pendingHostPage(command: HostCheckpointCommand?): ContractResult<HostPage?> = ContractResult.Success(when(command) {
    null -> null
    is HostCheckpointCommand.Gacha -> HostPage.GACHA
    is HostCheckpointCommand.Garage, is HostCheckpointCommand.Material, is HostCheckpointCommand.Repair -> HostPage.GARAGE
    is HostCheckpointCommand.Expand -> HostPage.HOME
    is HostCheckpointCommand.Delivery -> HostPage.DELIVERY
    is HostCheckpointCommand.Quest -> HostPage.PROGRESSION
})
