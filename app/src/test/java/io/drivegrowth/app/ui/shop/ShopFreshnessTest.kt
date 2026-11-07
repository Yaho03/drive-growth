package io.drivegrowth.app.ui.shop

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class ShopFreshnessTest {
    @Test fun `늦은 낮은 revision은 최신 사실과 선택 허용을 되돌리지 않는다`() {
        val old = ShopPreviewStates.normal()
        val latest = old.copy(revision = Revision(13), balance = GameMoney(900), capacity = 7)
        val ui = ShopInteraction({})
        ui.render(old, true); ui.render(latest, true); ui.render(old, true)
        assertEquals(latest, ui.state)
        assertFalse(ui.canSelect(ShopSelection(old.revision, old.expansion!!)))
        ui.render(old, false)
        assertEquals(latest, ui.state); assertFalse(ui.controlsEnabled)
        ui.render(old, true); assertFalse(ui.controlsEnabled)
    }
    @Test fun `Loading과 재생성 뒤에도 최신 revision 기준을 잃지 않는다`() {
        val old = ShopPreviewStates.normal(); val latest = old.copy(revision = Revision(13))
        val ui = ShopInteraction({})
        ui.render(latest, true); ui.render(ShopScreenState.Loading, false); ui.render(old, true)
        assertEquals(ShopScreenState.Loading, ui.state); assertFalse(ui.controlsEnabled)
        val restored = ShopInteraction({}); restored.restoreUiState(ui.uiState)
        restored.render(old, true)
        assertEquals(ShopScreenState.Loading, restored.state)
        restored.render(latest, true); assertEquals(latest, restored.state)
    }
    @Test fun `A실패 B실패 취소 제한 재생성 뒤 A는 원래 거래 ID로 재시도한다`() {
        val c = ShopPreviewStates.normal()
        val a = ShopSelection(c.revision, c.decorations.single())
        val b = ShopSelection(c.revision, c.expansion!!)
        var next = 0
        val ui = ShopInteraction({}, { RequestId("freshness-${++next}") })
        ui.render(c, true)
        ui.select(a); assertTrue(ui.confirm(a)); val first = ui.uiState.pending!!
        ui.complete(first, ContractResult.Failure(ContractError.STORAGE_FAILURE))
        ui.select(b); assertTrue(ui.cancel()); ui.select(b); assertTrue(ui.confirm(b)); val second = ui.uiState.pending!!
        ui.complete(second, ContractResult.Failure(ContractError.STORAGE_FAILURE))
        assertNotEquals(first.requestId, second.requestId)
        ui.render(c, false)
        val restored = ShopInteraction({}, { RequestId("freshness-${++next}") })
        restored.restoreUiState(ui.uiState); restored.render(c, true)
        restored.select(a); assertTrue(restored.confirm(a)); val retry = restored.uiState.pending!!
        assertEquals(first.requestId, retry.requestId); assertNotEquals(first.attemptId, retry.attemptId)
        restored.complete(first, ContractResult.Success(Unit)); assertEquals(retry, restored.uiState.pending)
    }
    @Test fun `복원 요청 ID 맵은 원본 변경을 격리하고 새 revision은 이전 키를 버린다`() {
        val c = ShopPreviewStates.normal(); val key = ShopSelection(c.revision, c.expansion!!)
        val source = mutableMapOf(key to RequestId("retained"))
        val ui = ShopInteraction({})
        ui.restoreUiState(ShopUiState(requestIds = source, latestRevision = c.revision)); source.clear()
        ui.render(c, true); ui.select(key); assertTrue(ui.confirm(key))
        assertEquals(RequestId("retained"), ui.uiState.pending!!.requestId)
        assertThrows(UnsupportedOperationException::class.java) { (ui.uiState.requestIds as MutableMap).clear() }
        val request = ui.uiState.pending!!
        ui.complete(request, ContractResult.Success(Unit))
        assertFalse(ui.canSelect(key)); ui.complete(request, ContractResult.Failure(ContractError.STORAGE_FAILURE))
        assertFalse(ui.canSelect(key))
        ui.render(c.copy(revision = Revision(13)), true)
        assertTrue(ui.uiState.requestIds.isEmpty())
        assertTrue(ui.canSelect(key.copy(revision = Revision(13))))
    }

}
