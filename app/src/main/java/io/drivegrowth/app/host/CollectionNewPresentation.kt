package io.drivegrowth.app.host

import io.drivegrowth.app.data.FirstCollectionOwnership
import io.drivegrowth.app.data.FirstGachaReceipt
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.contract.Settlement
import io.drivegrowth.core.contract.VehicleAcquisitionSource
import io.drivegrowth.core.garage.CollectionKey

/** 현재 HostSession에서 확인한 최초 보유만 표시한다. 조회·저장 복원 입력으로 생성하지 않는다. */
internal class CollectionNewPresentation {
    private data class Slot(val identity: GameIdentity, val key: CollectionKey)

    private val confirmed = mutableSetOf<Slot>()
    private val pending = mutableSetOf<Slot>()
    private val gachaReceipts = mutableSetOf<Pair<Slot, FirstGachaReceipt>>()

    fun offer(event: FirstCollectionOwnership): Boolean {
        val slot = Slot(GameIdentity(event.profileId, event.source), event.key)
        if (!confirmed.add(slot)) return false
        pending.add(slot)
        event.gachaReceipt?.let { gachaReceipts.add(slot to it) }
        return true
    }

    fun take(identity: GameIdentity, key: CollectionKey): Boolean = pending.remove(Slot(identity, key))

    fun takeGacha(identity: GameIdentity, settlement: Settlement): Boolean {
        val vehicle = settlement.changedVehicle ?: return false
        if (settlement.transaction.source != identity.source || vehicle.acquisitionSource != VehicleAcquisitionSource.GACHA)
            return false
        val receipt = FirstGachaReceipt(settlement.transaction.requestId, settlement.settlementId, vehicle.ownedVehicleId)
        return gachaReceipts.remove(Slot(identity, CollectionKey(vehicle.vehicleId, vehicle.rarity)) to receipt)
    }

    fun discardGachaRequest(identity: GameIdentity, request: RequestId) {
        gachaReceipts.removeAll { (slot, receipt) -> slot.identity == identity && receipt.requestId == request }
    }
}
