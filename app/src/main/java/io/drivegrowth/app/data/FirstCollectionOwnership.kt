package io.drivegrowth.app.data

import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.ProfileId
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.SettlementId
import io.drivegrowth.core.garage.CollectionKey

/** 같은 쓰기 거래에서 처분 포함 이력으로 확인한 최초 보유. 현재 앱 세션의 표시 입력이다. */
internal data class FirstCollectionOwnership(
    val profileId: ProfileId,
    val source: InputSource,
    val key: CollectionKey,
    val gachaReceipt: FirstGachaReceipt? = null,
)

internal data class FirstGachaReceipt(val requestId: RequestId, val settlementId: SettlementId, val vehicleId: OwnedVehicleId)
