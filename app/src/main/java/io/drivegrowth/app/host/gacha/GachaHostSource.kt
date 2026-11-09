package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.contract.*

interface GachaHostSource {
    fun read(paid: RequestId, free: RequestId): ContractResult<GachaOffers>
    fun prepare(input: GachaDrawInput): ContractResult<GachaCommand>
    fun commit(command: GachaCommand): ContractResult<GachaSettlement>
}
