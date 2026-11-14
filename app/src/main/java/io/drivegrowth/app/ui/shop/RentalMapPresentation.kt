package io.drivegrowth.app.ui.shop

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.RentalMapDirection
import io.drivegrowth.app.runtime.rental.RentalMapEvent
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Revision
import io.drivegrowth.core.contract.RequestId
import io.drivegrowth.core.progression.VehicleActivity

internal data class RentalMapEffect(val event: RentalMapEvent, val slot: Pair<Int, Int>)

/** 현재 화면의 같은 개체와 칸을 대조한다. 지난 관측의 효과를 재생 대기열에 두지 않는다. */
internal class RentalMapPresentation {
    private data class Pending(val effect: RentalMapEffect, val revision: Revision, val expiresAt: Long)
    private var pending: Pending? = null
    private val seen = mutableSetOf<RentalMapEventKey>()
    private data class RentalMapEventKey(val identity: GameIdentity, val id: RequestId,
        val vehicle: OwnedVehicleId, val direction: RentalMapDirection)
    private fun key(event: RentalMapEvent) = RentalMapEventKey(event.identity, event.rentalId, event.vehicle, event.direction)

    fun offer(identity: GameIdentity, content: ShopScreenState.Content, events: List<RentalMapEvent>, now: Long,
        visibleSlot: (OwnedVehicleId) -> Pair<Int, Int>?) {
        if (content.origin != ShopOrigin.STORED) return
        for ((vehicle, observations) in events.filter { it.identity == identity }.groupBy { it.vehicle }) {
            val newest = observations.last()
            val hasNew = key(newest) !in seen
            observations.forEach { seen.add(key(it)) }
            if (!hasNew || !valid(newest)) continue
            val slot = visibleSlot(vehicle) ?: continue
            pending = Pending(RentalMapEffect(newest, slot), content.revision, now + 1_200L)
        }
    }

    fun consume(content: ShopScreenState.Content, now: Long): RentalMapEffect? {
        val candidate = pending ?: return null
        if (now >= candidate.expiresAt) {
            clear()
            return null
        }
        if (content.revision.value <= candidate.revision.value) return null
        clear()
        if (content.origin != ShopOrigin.STORED) return null
        val event = candidate.effect.event
        val (vehicle, slot) = shopSceneModel(content).vehicles.zip(townSlots)
            .firstOrNull { it.first.id == event.vehicle } ?: return null
        return candidate.effect.takeIf { slot == it.slot && vehicle.activity == event.activity }
    }

    fun clear() { pending = null }

    private fun valid(event: RentalMapEvent) = when (event.direction) {
        RentalMapDirection.DEPARTURE -> event.activity == VehicleActivity.RENTED
        RentalMapDirection.RETURN -> event.activity == VehicleActivity.IDLE || event.activity == VehicleActivity.NEEDS_REPAIR
    }
}
