package io.drivegrowth.app.ui.results

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class ResultsHistoryInteractionTest {
    private fun row(id:String,viewed:Boolean)=ResultPresentation(Settlement(SettlementId(id),Transaction(TransactionId("tx-$id"),RequestId("request-$id"),TransactionKind.GARAGE,InputSource.SIMULATION,RuleVersion("old"),null),GameMoney(17),null,EpochMillis(1000)),viewed=viewed)
    private val viewed=row("viewed",true)
    private val fresh=row("fresh",false)
    private fun content()=ResultsScreenState.Content(listOf(viewed,fresh),ResultsOrigin.STORED,historyAvailable=true)
    @Test fun unviewedFilterAndWholeHistoryDoNotEraseRowsOrReissueViewedMark() {
        val events=mutableListOf<ResultsEvent>();val state=ResultsInteraction(events::add);state.render(content(),true)
        assertEquals(listOf(fresh),state.visibleResults);state.select(viewed.settlement.settlementId);assertNull(state.uiState.selectedId)
        state.scope(ResultsScope.ALL);assertEquals(listOf(viewed,fresh),state.visibleResults);state.select(viewed.settlement.settlementId)
        assertEquals(viewed,state.selectedResult);assertFalse(state.requestMarkViewed(viewed.settlement.settlementId));assertTrue(events.none{it is ResultsEvent.MarkViewed})
    }
    @Test fun realAcknowledgementKeepsSelectedHistoricalDetailsAndHidesOnlyFromUnviewedFilter() {
        val state=ResultsInteraction{};state.render(content(),true);state.select(fresh.settlement.settlementId);assertTrue(state.requestMarkViewed(fresh.settlement.settlementId))
        state.completeMarkViewed(fresh.settlement.settlementId,ContractResult.Success(Unit))
        state.render(content().copy(results=listOf(viewed,fresh.copy(viewed=true))),true)
        assertTrue(state.visibleResults.isEmpty());assertEquals(fresh.settlement,state.selectedResult!!.settlement)
        assertFalse(state.requestMarkViewed(fresh.settlement.settlementId));state.scope(ResultsScope.ALL);assertEquals(2,state.visibleResults.size)
    }
    @Test fun filterAndSelectionRestoreWithoutAutomaticWritesAndGateBlocksFilterChanges() {
        val events=mutableListOf<ResultsEvent>();val state=ResultsInteraction(events::add)
        state.restoreUiState(ResultsUiState(selectedId=viewed.settlement.settlementId,scope=ResultsScope.ALL));state.render(content(),false)
        state.scope(ResultsScope.UNVIEWED);assertEquals(ResultsScope.ALL,state.uiState.scope);assertEquals(viewed,state.selectedResult);assertTrue(events.isEmpty())
        state.render(content(),true);assertEquals(ResultsReadState.Done,state.readState(viewed.settlement.settlementId));assertFalse(state.requestMarkViewed(viewed.settlement.settlementId))
    }
}
