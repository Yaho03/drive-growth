package io.drivegrowth.app.host

import io.drivegrowth.app.data.HostCheckpointCommand

/** 이동 안내만 계산한다. 다른 기능의 원명령을 실행·취소·변환하지 않는다. */
internal fun pendingHostPage(command: HostCheckpointCommand?): HostPage? = when (command) {
    null -> null
    is HostCheckpointCommand.Gacha -> HostPage.GACHA
    is HostCheckpointCommand.Garage, is HostCheckpointCommand.Material, is HostCheckpointCommand.Repair -> HostPage.GARAGE
    is HostCheckpointCommand.Expand -> HostPage.HOME
    is HostCheckpointCommand.Delivery -> HostPage.DELIVERY
    is HostCheckpointCommand.Quest -> null // 진행 화면 소비 전까지 다른 기능에서 실행하지 않는다.
}
