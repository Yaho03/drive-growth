package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.vehicle.navigation.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DestinationKey
import org.junit.Assert.*
import org.junit.Test

class NavigationDestinationConfirmationTest {
    private val identity = GameIdentity(ProfileId("actual-profile"), InputSource.VEHICLE)
    private val stamp = NavigationStamp(1000, 100, 7, 99)
    private val destination = NavigationDestination(37.4, 127.1, "실제 목적지", "실제 주소", null, null, null)
    private fun raw(distanceStamp: NavigationStamp = stamp) = NavigationSnapshot(stamp, NavigationConnection.CONNECTED_OBSERVED,
        NavigationField.Value(destination, stamp), NavigationField.Value(50L, distanceStamp),
        NavigationField.Missing(NavigationMissing.NO_ROUTE_ID), NavigationField.Missing(NavigationMissing.NOT_OBSERVED),
        NavigationField.Missing(NavigationMissing.NOT_OBSERVED), null, null)
    @Test fun `확인은 같은 SDK 응답 목적지 거리와 별도 선택 revision을 고정한다`() {
        val state = NavigationDestinationConfirmation({ true }, { DestinationKey("chosen") })
        state.setEnabled(true); state.bind(identity); state.accept(raw())
        assertNull(state.selected(identity))
        val first = (state.confirm() as ContractResult.Success).value
        assertEquals(1L, first.selectionRevision); assertEquals(stamp, first.actualStamp); assertEquals(destination, first.actualDestination)
        assertEquals(first, state.selected(identity))
        assertEquals(2L, (state.confirm() as ContractResult.Success).value.selectionRevision)
    }
    @Test fun `서로 다른 응답 거리와 오래된 응답은 확인하지 않는다`() {
        for (fresh in listOf(true, false)) {
            val state = NavigationDestinationConfirmation({ fresh }, { DestinationKey("chosen") })
            state.setEnabled(true); state.bind(identity); state.accept(raw(if (fresh) stamp.copy(sequence = 100) else stamp))
            assertTrue(state.confirm() is ContractResult.Failure)
        }
    }
    @Test fun `gate 해제와 프로필 전환은 확인을 무효화하고 정지 중 수신을 버린다`() {
        val state = NavigationDestinationConfirmation({ true }, { DestinationKey("chosen") })
        state.setEnabled(true); state.bind(identity); state.accept(raw()); state.confirm()
        state.bind(identity.copy(profileId = ProfileId("other"))); assertNull(state.selected(identity))
        state.setEnabled(false); state.accept(raw()); assertNull(state.snapshot)
        state.setEnabled(true); assertTrue(state.confirm() is ContractResult.Failure)
    }
}
