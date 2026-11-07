package io.drivegrowth.app.ui.shop

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class ShopFirstRestoreTest {
    private val old = ShopPreviewStates.normal().copy(revision = Revision(3))
    private val latest = old.copy(revision = Revision(5), capacity = 7, balance = GameMoney(900))
    private fun saved(): ShopUiState = ShopInteraction({}).apply { render(latest,true) }.uiState

    @Test fun `첫 render 뒤 첫 복원은 보존 이력보다 낮은 내용과 명령을 무효화한다`() {
        val events = mutableListOf<ShopEvent>(); val ui = ShopInteraction(events::add)
        ui.render(old,true)
        val stale = ShopSelection(old.revision,old.expansion!!)
        ui.select(stale); ui.restoreUiState(saved())
        assertEquals(ShopScreenState.Loading,ui.state)
        assertFalse(ui.canSelect(stale)); assertFalse(ui.confirm(stale)); assertTrue(events.isEmpty())
        ui.render(old,true); assertEquals(ShopScreenState.Loading,ui.state)
        ui.render(latest,true); assertEquals(latest,ui.state)
        assertTrue(ui.canSelect(ShopSelection(latest.revision,latest.expansion!!)))
    }
    @Test fun `먼저 복원한 뒤 낮은 render를 받아도 제한과 최신 이력을 유지한다`() {
        val ui = ShopInteraction({}); ui.restoreUiState(saved()); ui.render(old,false)
        assertEquals(ShopScreenState.Loading,ui.state); assertFalse(ui.controlsEnabled)
        ui.render(old,true); assertFalse(ui.controlsEnabled)
        ui.render(latest,true); assertEquals(latest,ui.state); assertTrue(ui.controlsEnabled)
    }
    @Test fun `늦은 첫 복원에서 현재 내용만 폐기하고 정상 Pending 요청 키와 조회 잠금은 보존한다`() {
        val source = ShopInteraction({}); source.render(latest,true)
        val selection = ShopSelection(latest.revision,latest.expansion!!)
        source.select(selection); source.confirm(selection)
        val preserved = source.uiState.copy(retryPending = true)
        val ui = ShopInteraction({}); ui.render(old,false); ui.restoreUiState(preserved)
        assertEquals(ShopScreenState.Loading,ui.state); assertFalse(ui.controlsEnabled)
        assertEquals(preserved.pending,ui.uiState.pending); assertEquals(preserved.requestIds,ui.uiState.requestIds)
        assertTrue(ui.uiState.retryPending)
        ui.render(latest,true); assertFalse(ui.confirm(selection))
        ui.complete(preserved.pending!!,ContractResult.Failure(ContractError.STORAGE_FAILURE))
        ui.select(selection); assertTrue(ui.confirm(selection))
        assertEquals(preserved.pending.requestId,ui.uiState.pending!!.requestId)
    }
}
