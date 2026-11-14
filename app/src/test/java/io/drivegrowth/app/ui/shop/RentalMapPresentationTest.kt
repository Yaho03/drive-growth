package io.drivegrowth.app.ui.shop

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.runtime.rental.RentalMapDirection
import io.drivegrowth.app.runtime.rental.RentalMapEvent
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleActivity
import org.junit.Assert.*
import org.junit.Test

class RentalMapPresentationTest {
    private val identity = GameIdentity(ProfileId("map"), InputSource.SIMULATION)
    private val state = ShopPreviewStates.normal().copy(origin = ShopOrigin.STORED)
    private val vehicle = state.vehicles.first().id
    private val departure = RentalMapEvent(identity, RequestId("rental"), vehicle, RentalMapDirection.DEPARTURE, VehicleActivity.RENTED)
    private val returned = departure.copy(direction = RentalMapDirection.RETURN, activity = VehicleActivity.NEEDS_REPAIR)
    private fun updated(activity: VehicleActivity) = state.copy(revision = Revision(state.revision.value + 1),
        vehicles = state.vehicles.map { if (it.id == vehicle) it.copy(activity = activity) else it })

    @Test fun `확정 통지와 새 조회의 같은 개체와 칸이 일치해야 한다`() {
        val motion = RentalMapPresentation()
        assertNull(motion.consume(updated(VehicleActivity.RENTED), 0))
        motion.offer(identity, state, listOf(departure), 10) { townSlots.first() }
        assertNull(motion.consume(state, 11))
        assertEquals(RentalMapEffect(departure, townSlots.first()), motion.consume(updated(VehicleActivity.RENTED), 12))
        motion.offer(identity, state, listOf(departure), 13) { townSlots.first() }
        assertNull(motion.consume(updated(VehicleActivity.RENTED), 14))
    }

    @Test fun `프로필 외부와 자리 이동 및 활동 불일치는 건너뛴다`() {
        val sameModelDifferentId = OwnedVehicleId("other-same-model")
        val wrongProfile = departure.copy(identity = identity.copy(profileId = ProfileId("other")))
        for (event in listOf(wrongProfile, departure.copy(vehicle = sameModelDifferentId), departure)) {
            val motion = RentalMapPresentation()
            motion.offer(identity, state, listOf(event), 1) { townSlots[1] }
            assertNull(motion.consume(updated(VehicleActivity.RENTED), 2))
        }
        val motion = RentalMapPresentation()
        motion.offer(identity, state, listOf(departure), 3) { townSlots.first() }
        assertNull(motion.consume(updated(VehicleActivity.RETURNING), 4))
    }

    @Test fun `화면 이탈 및 기한 만료 뒤 이전 통지는 재생하지 않는다`() {
        val motion = RentalMapPresentation()
        motion.offer(identity, state, listOf(departure), 1) { townSlots.first() }
        motion.clear()
        assertNull(motion.consume(updated(VehicleActivity.RENTED), 2))
        motion.offer(identity, state, listOf(returned), 3) { townSlots.first() }
        assertNull(motion.consume(updated(VehicleActivity.NEEDS_REPAIR), 2_000))
    }

    @Test fun `한 응답의 연속 변경은 화면에 남은 확인 상태만 재생한다`() {
        val motion = RentalMapPresentation()
        motion.offer(identity, state, listOf(departure, returned), 10) { townSlots.first() }
        assertEquals(RentalMapEffect(returned, townSlots.first()), motion.consume(updated(VehicleActivity.NEEDS_REPAIR), 11))
        motion.offer(identity, state, listOf(departure), 12) { townSlots.first() }
        assertNull(motion.consume(updated(VehicleActivity.RENTED), 13))
    }
}
