package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.vehicle.navigation.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.DestinationKey
import org.junit.Assert.*
import org.junit.Test

/** B 연결의 프로필 전환 캐시 경계만 검증한다. SDK/기기 시험이 아니다. */
class NavigationProfileBoundaryIndependentTest {
    private val first = GameIdentity(ProfileId("first"), InputSource.SIMULATION)
    private val stamp = NavigationStamp(1000, 100, 1, 1)
    private val destination = NavigationDestination(37.0, 127.0, "이전 프로필 목적지", null, null, null, null)
    private fun raw() = NavigationSnapshot(stamp, NavigationConnection.CONNECTED_OBSERVED,
        NavigationField.Value(destination, stamp), NavigationField.Value(1000L, stamp),
        NavigationField.Missing(NavigationMissing.NO_ROUTE_ID), NavigationField.Missing(NavigationMissing.NOT_OBSERVED),
        NavigationField.Missing(NavigationMissing.NOT_OBSERVED), null, null)
    private fun ready() = NavigationDestinationConfirmation({ true }, { DestinationKey("user-confirmed") }).apply {
        setEnabled(true); bind(first); accept(raw()); assertTrue(confirm() is ContractResult.Success)
    }
    @Test fun profileSwitchRequiresNewObservationBeforeConfirmation() {
        val state = ready()
        val other = first.copy(profileId = ProfileId("second"))
        state.bind(other)
        assertNull(state.selected(other))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), state.confirm())
    }
    @Test fun sourceSwitchRequiresNewObservationBeforeConfirmation() {
        val state = ready()
        val other = first.copy(source = InputSource.VEHICLE)
        state.bind(other)
        assertNull(state.selected(other))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE), state.confirm())
    }
    @Test fun bindingSameIdentityRetainsCurrentConfirmation() {
        val state = ready(); val original = state.selected(first)
        state.bind(first)
        assertEquals(original, state.selected(first))
    }
}
