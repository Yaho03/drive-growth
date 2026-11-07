package io.drivegrowth.app.ui.gacha

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class GachaBoundaryRegressionTest {
    private fun failed(): Pair<GachaInteraction,GachaRequest> {
        val ui=GachaInteraction({});ui.render(GachaPreviewStates.ready(),true)
        ui.select("paid");ui.confirm();val request=ui.uiState.pending!!
        ui.complete(request,ContractResult.Failure(ContractError.STORAGE_FAILURE));return ui to request
    }
    @Test fun `미해소 경제 키를 새 옵션 ID와 변경 비용으로 옮겨 제출하지 않는다`() {
        val (ui,request)=failed();val offers=(GachaPreviewStates.ready().screen as GachaScreen.Offers).value
        val changed=offers.copy(options=listOf(request.input.option.copy(id="paid-v2",moneyCost=GameMoney(999))))
        ui.render(GachaFrame(2,GachaScreen.Offers(changed)),true)
        assertFalse(ui.select("paid-v2"));assertFalse(ui.confirm());assertNull(ui.uiState.pending)
        assertTrue(ui.resolveFailedRequest(request.input.option.requestId!!))
        assertTrue(ui.select("paid-v2"));assertTrue(ui.confirm())
    }
    @Test fun `미해소 paid 경제 키를 기존 free 옵션의 다른 원문으로 옮기지 않는다`() {
        val (ui,request)=failed();val offers=(GachaPreviewStates.ready().screen as GachaScreen.Offers).value
        val changed=offers.copy(options=offers.options.map { if(it.id=="paid") it.copy(requestId=RequestId("new-paid")) else it.copy(requestId=request.input.option.requestId) })
        ui.render(GachaFrame(2,GachaScreen.Offers(changed)),true)
        assertFalse(ui.select("free"));assertFalse(ui.confirm());assertNull(ui.uiState.pending)
        assertTrue(ui.resolveFailedRequest(request.input.option.requestId!!))
        assertTrue(ui.select("free"));assertTrue(ui.confirm())
    }
    @Test fun `같은 정산의 첫 이동 늦은 완료는 두번째 이동 잠금을 풀지 않는다`() {
        val events=mutableListOf<GachaEvent>();val ui=GachaInteraction(events::add)
        ui.render(GachaPreviewStates.result(),true);assertTrue(ui.openGarage())
        val first=(events.last() as GachaEvent.OpenGarage).request
        ui.completeGarageNavigation(first);assertTrue(ui.openGarage())
        val second=(events.last() as GachaEvent.OpenGarage).request
        assertNotEquals(first.token,second.token)
        ui.completeGarageNavigation(first)
        assertFalse(ui.openGarage());assertEquals(second,ui.uiState.garagePending)
        val restored=GachaInteraction(events::add,ui.uiState)
        restored.render(GachaPreviewStates.result(),false);restored.completeGarageNavigation(first)
        assertEquals(second,restored.uiState.garagePending)
        restored.render(GachaPreviewStates.result(),true);assertFalse(restored.openGarage())
        restored.completeGarageNavigation(second);assertTrue(restored.openGarage())
    }
    @Test fun `재시도 Pending과 성공 대기 키는 확정 실패 해소로 지우지 않는다`() {
        val (ui,first)=failed();val key=first.input.option.requestId!!
        ui.select("paid");assertTrue(ui.confirm());val retry=ui.uiState.pending!!
        assertFalse(ui.resolveFailedRequest(key));assertEquals(retry,ui.uiState.pending)
        ui.complete(retry,ContractResult.Success(Unit))
        assertFalse(ui.resolveFailedRequest(key));assertTrue(ui.awaitingConfirmation)
        assertFalse(ui.select("paid"));assertFalse(ui.select("free"))
    }

}
