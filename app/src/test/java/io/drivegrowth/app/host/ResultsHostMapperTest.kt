package io.drivegrowth.app.host

import io.drivegrowth.app.ui.results.*
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class ResultsHostMapperTest {
    @Test fun mapsStoredSettlementWithItsHistoricalVehicleAndBalanceWithoutPayingOrRecalculating() {
        val saved = ResultsHostTestData.settlement()
        val state = ResultsHostMapper().map(HostQueryState.Content(ResultsHostTestData.snapshot(saved))) as ResultsScreenState.Content
        assertEquals(ResultsOrigin.STORED, state.origin)
        assertSame(saved, state.results.single().settlement)
        assertEquals("당시 차량", state.results.single().vehicleName)
        assertEquals(2, state.results.single().settlement.changedVehicle!!.level)
        assertEquals(GameMoney(100), state.results.single().settlement.balance)
        assertEquals(GameMoney(500), state.results.single().settlement.transaction.money!!.amount)
    }

    @Test fun externalListMutationDoesNotChangeDisplayedSnapshotAndBrokenSourcesAreRejected() {
        val saved = ResultsHostTestData.settlement()
        val external = mutableListOf(saved)
        val input = ResultsHostTestData.snapshot().copy(unviewedSettlements = external)
        val mapper = ResultsHostMapper()
        val shown = mapper.map(HostQueryState.Content(input)) as ResultsScreenState.Content
        external.clear()
        assertEquals(listOf(saved), shown.results.map { it.settlement })
        try {
            @Suppress("UNCHECKED_CAST")
            (shown.results as MutableList<ResultPresentation>).clear()
            fail("외부에서 다음 render 전 표시 목록을 바꿀 수 없어야 한다")
        } catch (_: UnsupportedOperationException) { }
        assertTrue(mapper.map(HostQueryState.Content(ResultsHostTestData.snapshot(saved, saved))) is ResultsScreenState.Error)
        val wrongSource = saved.copy(transaction = saved.transaction.copy(source = InputSource.VEHICLE))
        assertTrue(mapper.map(HostQueryState.Content(ResultsHostTestData.snapshot(wrongSource))) is ResultsScreenState.Error)
    }

    @Test fun emptyMissingAndStorageErrorRemainDifferentStates() {
        val mapper = ResultsHostMapper()
        assertTrue((mapper.map(HostQueryState.Content(ResultsHostTestData.snapshot())) as ResultsScreenState.Content).results.isEmpty())
        assertEquals(ResultsScreenState.Loading, mapper.map(HostQueryState.Loading))
        assertTrue(mapper.map(HostQueryState.Failure(ContractError.DATA_UNAVAILABLE)) is ResultsScreenState.Missing)
        assertTrue(mapper.map(HostQueryState.Failure(ContractError.STORAGE_FAILURE)) is ResultsScreenState.Error)
    }
}
