package io.drivegrowth.app.ui.results

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class ResultsInteractionTest {
    private fun result(id: String) = ResultPresentation(Settlement(
        SettlementId(id),
        Transaction(TransactionId("t-$id"), RequestId("r-$id"), TransactionKind.RENTAL,
            InputSource.SIMULATION, RuleVersion("fixture"), MoneyChange(MoneyDirection.CREDIT, GameMoney(200))),
        GameMoney(900), null, EpochMillis(123456),
    ), "같은 표시 이름")
    private val first = result("first")
    private val second = result("second")
    private fun content() = ResultsScreenState.Content(listOf(first, second), ResultsOrigin.FIXTURE)

    @Test fun exactSettlementIdSelectsAmongDuplicateNamesAndRejectsStaleButton() {
        val events = mutableListOf<ResultsEvent>()
        val interaction = ResultsInteraction(events::add)
        interaction.render(content(), true)
        interaction.select(second.settlement.settlementId)
        assertEquals(second, interaction.selectedResult)
        assertFalse(interaction.requestMarkViewed(first.settlement.settlementId))
        assertTrue(interaction.requestMarkViewed(second.settlement.settlementId))
        assertEquals(ResultsEvent.MarkViewed(second.settlement.settlementId), events.last())
    }

    @Test fun repeatedRequestsStayPendingAcrossRendersAndDetailChanges() {
        val events = mutableListOf<ResultsEvent>()
        val interaction = ResultsInteraction(events::add)
        interaction.render(content(), true)
        interaction.select(first.settlement.settlementId)
        assertTrue(interaction.requestMarkViewed(first.settlement.settlementId))
        interaction.render(content(), true)
        assertFalse(interaction.requestMarkViewed(first.settlement.settlementId))
        assertTrue(interaction.closeDetail())
        interaction.select(second.settlement.settlementId)
        assertFalse(interaction.requestMarkViewed(second.settlement.settlementId))
        assertEquals(1, events.filterIsInstance<ResultsEvent.MarkViewed>().size)
        assertEquals(ResultsReadState.Pending, interaction.uiState.readStates[first.settlement.settlementId])
    }

    @Test fun failureAllowsRetryAndOnlySuccessConfirmsRead() {
        val interaction = ResultsInteraction {}
        interaction.render(content(), true)
        interaction.select(first.settlement.settlementId)
        interaction.requestMarkViewed(first.settlement.settlementId)
        assertEquals(ResultsReadState.Pending, interaction.uiState.readStates[first.settlement.settlementId])
        interaction.completeMarkViewed(first.settlement.settlementId, ContractResult.Failure(ContractError.STORAGE_FAILURE))
        assertEquals(ResultsReadState.Failed(ContractError.STORAGE_FAILURE), interaction.uiState.readStates[first.settlement.settlementId])
        assertTrue(interaction.requestMarkViewed(first.settlement.settlementId))
        interaction.completeMarkViewed(first.settlement.settlementId, ContractResult.Success(Unit))
        interaction.render(content(), true)
        assertEquals(ResultsReadState.Done, interaction.uiState.readStates[first.settlement.settlementId])
        assertFalse(interaction.requestMarkViewed(first.settlement.settlementId))
    }

    @Test fun synchronousSuccessCannotBeOverwrittenByPending() {
        lateinit var interaction: ResultsInteraction
        interaction = ResultsInteraction { event ->
            if (event is ResultsEvent.MarkViewed) interaction.completeMarkViewed(event.id, ContractResult.Success(Unit))
        }
        interaction.render(content(), true)
        interaction.select(first.settlement.settlementId)
        assertTrue(interaction.requestMarkViewed(first.settlement.settlementId))
        assertEquals(ResultsReadState.Done, interaction.uiState.readStates[first.settlement.settlementId])
    }

    @Test fun synchronousFailureIsRetryable() {
        lateinit var interaction: ResultsInteraction
        interaction = ResultsInteraction { event ->
            if (event is ResultsEvent.MarkViewed) interaction.completeMarkViewed(event.id, ContractResult.Failure(ContractError.STORAGE_FAILURE))
        }
        interaction.render(content(), true)
        interaction.select(first.settlement.settlementId)
        assertTrue(interaction.requestMarkViewed(first.settlement.settlementId))
        assertTrue(interaction.requestMarkViewed(first.settlement.settlementId))
        assertEquals(ResultsReadState.Failed(ContractError.STORAGE_FAILURE), interaction.uiState.readStates[first.settlement.settlementId])
    }

    @Test fun controlsDisabledBlocksEveryUserActionWithoutDroppingSelection() {
        val events = mutableListOf<ResultsEvent>()
        val interaction = ResultsInteraction(events::add)
        interaction.restoreSelection(first.settlement.settlementId)
        interaction.render(content(), false)
        interaction.select(second.settlement.settlementId)
        assertFalse(interaction.closeDetail())
        assertFalse(interaction.requestMarkViewed(first.settlement.settlementId))
        interaction.render(ResultsScreenState.Error("조회 실패"), false)
        assertFalse(interaction.retry())
        assertEquals(first.settlement.settlementId, interaction.uiState.selectedId)
        assertTrue(events.isEmpty())
    }

    @Test fun retryOnlyForRecoverableStatesAndRequiresExplicitCompletion() {
        val events = mutableListOf<ResultsEvent>()
        val interaction = ResultsInteraction(events::add)
        listOf(ResultsScreenState.Loading, content(), ResultsScreenState.Unsupported("조회 미지원")).forEach {
            interaction.render(it, true)
            assertFalse(interaction.retry())
        }
        interaction.render(ResultsScreenState.Error("조회 실패"), true)
        assertTrue(interaction.retry())
        interaction.render(ResultsScreenState.Error("조회 실패"), true)
        assertFalse(interaction.retry())
        interaction.completeRetry()
        interaction.render(ResultsScreenState.Missing("확정 결과 없음"), true)
        assertTrue(interaction.retry())
        assertEquals(listOf(ResultsEvent.Retry, ResultsEvent.Retry), events)
    }

    @Test fun stateAndSelectionRestorePreservePendingInANewInteraction() {
        val interaction = ResultsInteraction {}
        interaction.render(content(), true)
        interaction.select(first.settlement.settlementId)
        interaction.requestMarkViewed(first.settlement.settlementId)
        val restored = ResultsInteraction {}
        restored.restoreUiState(interaction.uiState)
        restored.render(content(), true)
        assertEquals(first, restored.selectedResult)
        assertFalse(restored.requestMarkViewed(first.settlement.settlementId))
        restored.completeMarkViewed(first.settlement.settlementId, ContractResult.Success(Unit))
        assertEquals(ResultsReadState.Done, restored.uiState.readStates[first.settlement.settlementId])
    }

    @Test fun selectionMaySurviveMissingResultsAndLoadingWithoutSendingAnEvent() {
        val events = mutableListOf<ResultsEvent>()
        val interaction = ResultsInteraction(events::add)
        interaction.restoreSelection(first.settlement.settlementId)
        interaction.render(ResultsScreenState.Loading, true)
        assertNull(interaction.selectedResult)
        interaction.render(ResultsScreenState.Content(emptyList(), ResultsOrigin.STORED), true)
        assertNull(interaction.selectedResult)
        assertFalse(interaction.requestMarkViewed(first.settlement.settlementId))
        assertEquals(first.settlement.settlementId, interaction.uiState.selectedId)
        assertTrue(events.isEmpty())
        assertTrue(interaction.closeDetail())
        assertNull(interaction.uiState.selectedId)
        assertEquals(listOf(ResultsEvent.CloseDetail), events)
    }

    @Test fun externalListAndMapChangesCannotAlterRenderedOrRestoredState() {
        val input = mutableListOf(first, second)
        val reads = mutableMapOf<SettlementId, ResultsReadState>(first.settlement.settlementId to ResultsReadState.Pending)
        val interaction = ResultsInteraction {}
        interaction.render(ResultsScreenState.Content(input, ResultsOrigin.FIXTURE), true)
        interaction.restoreUiState(ResultsUiState(first.settlement.settlementId, reads))
        input.clear(); reads.clear()
        assertEquals(2, (interaction.state as ResultsScreenState.Content).results.size)
        assertEquals(ResultsOrigin.FIXTURE, (interaction.state as ResultsScreenState.Content).origin)
        assertEquals(ResultsReadState.Pending, interaction.uiState.readStates[first.settlement.settlementId])
        assertThrows(UnsupportedOperationException::class.java) {
            ((interaction.state as ResultsScreenState.Content).results as MutableList<*>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (interaction.uiState.readStates as MutableMap<*, *>).clear()
        }
    }

    @Test fun unsolicitedOrRepeatedCompletionsDoNotConfirmOrOverwriteReadState() {
        val interaction = ResultsInteraction {}
        interaction.render(content(), true)
        interaction.completeMarkViewed(first.settlement.settlementId, ContractResult.Success(Unit))
        assertTrue(interaction.uiState.readStates.isEmpty())
        interaction.select(first.settlement.settlementId)
        interaction.requestMarkViewed(first.settlement.settlementId)
        interaction.completeMarkViewed(second.settlement.settlementId, ContractResult.Success(Unit))
        assertEquals(ResultsReadState.Pending, interaction.uiState.readStates[first.settlement.settlementId])
        interaction.completeMarkViewed(first.settlement.settlementId, ContractResult.Success(Unit))
        interaction.completeMarkViewed(first.settlement.settlementId, ContractResult.Failure(ContractError.STORAGE_FAILURE))
        assertEquals(ResultsReadState.Done, interaction.uiState.readStates[first.settlement.settlementId])
    }

    @Test fun duplicateSettlementIdsAreExplicitlyRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ResultsScreenState.Content(listOf(first, first.copy(vehicleName = "다른 표시 이름")), ResultsOrigin.STORED)
        }
    }
}
