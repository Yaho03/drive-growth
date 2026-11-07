package io.drivegrowth.app.ui.gacha

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class GachaInteractionTest {
    private fun ready() = GachaPreviewStates.ready()
    private fun draw(ui: GachaInteraction, option: String = "paid"): GachaRequest {
        assertTrue(ui.select(option)); assertTrue(ui.confirm()); return ui.uiState.pending!!
    }
    @Test fun `확인 취소는 경제 이벤트 없이 확정 입력을 보존한다`() {
        val events = mutableListOf<GachaEvent>(); val ui = GachaInteraction(events::add)
        val frame = ready(); ui.render(frame, true)
        assertTrue(ui.select("paid")); assertTrue(ui.cancel()); assertEquals(frame, ui.frame); assertTrue(events.isEmpty())
    }
    @Test fun `실패 A B A는 공급받은 원래 경제 키와 새로운 응답 상관값을 유지한다`() {
        val events = mutableListOf<GachaEvent>(); val ui = GachaInteraction(events::add)
        ui.render(ready(), true); val a = draw(ui)
        ui.complete(a, ContractResult.Failure(ContractError.STORAGE_FAILURE))
        val b = draw(ui, "free"); ui.complete(b, ContractResult.Failure(ContractError.STORAGE_FAILURE))
        val retry = draw(ui)
        assertEquals(a.input.option.requestId, retry.input.option.requestId)
        assertNotEquals(a.attemptId, retry.attemptId); assertNotEquals(a.input.option.requestId, b.input.option.requestId)
        ui.complete(a, ContractResult.Success(Unit)); assertEquals(retry, ui.uiState.pending)
    }
    @Test fun `중복 재표시 복원 제한과 동기 응답은 요청을 추가하지 않는다`() {
        val events = mutableListOf<GachaEvent>(); val ui = GachaInteraction(events::add)
        ui.render(ready(), true); val request = draw(ui)
        assertFalse(ui.confirm()); ui.render(ready(), true); assertFalse(ui.select("free"))
        val restored = GachaInteraction(events::add, ui.uiState)
        restored.render(ready(), false); assertFalse(restored.confirm())
        restored.complete(request, ContractResult.Success(Unit))
        assertFalse(restored.controlsEnabled); assertEquals(1, events.size)
        restored.render(ready(), true); assertFalse(restored.select("paid"))
        assertTrue(restored.frame!!.screen is GachaScreen.Offers)
    }
    @Test fun `Loading과 실패 및 새 View 복원 뒤 낮은 조회 세대는 최신 표시를 되돌리지 않는다`() {
        val ui = GachaInteraction({}); val old = ready(); val latest = old.copy(generation = 3)
        ui.render(latest, true); ui.render(old, true); assertEquals(latest, ui.frame)
        ui.render(GachaFrame(4, GachaScreen.Loading), false)
        val restored = GachaInteraction({}, ui.uiState)
        assertFalse(restored.render(old, true)); assertFalse(restored.controlsEnabled)
        assertEquals(4L, restored.frame!!.generation)
    }
    @Test fun `미지원 누락 빈 후보 보장불가 공간과 재화 권 부족은 요청을 막는다`() {
        for (frame in GachaPreviewStates.blocked()) {
            val events = mutableListOf<GachaEvent>(); val ui = GachaInteraction(events::add)
            ui.render(frame, true); assertFalse(ui.select("paid")); assertFalse(ui.confirm()); assertTrue(events.isEmpty())
        }
    }
    @Test fun `callback 성공만으로 결과나 잔액 보유권 보장값을 바꾸지 않는다`() {
        val ui = GachaInteraction({}); val frame = ready(); ui.render(frame, true)
        val request = draw(ui); ui.complete(request, ContractResult.Success(Unit))
        assertEquals(frame, ui.frame); assertNull(ui.uiState.pending); assertFalse(ui.select("paid"))
        ui.render(GachaPreviewStates.result(), true)
        assertTrue(ui.frame!!.screen is GachaScreen.Result)
    }
    @Test fun `성공 응답 뒤 다른 옵션도 외부 확정 조회 전에는 제출하지 않는다`() {
        val ui = GachaInteraction({}); ui.render(ready(),true); val r = draw(ui)
        ui.complete(r,ContractResult.Success(Unit)); assertFalse(ui.select("free"))
        ui.render(GachaPreviewStates.result(),true)
        val v = (ready().screen as GachaScreen.Offers).value
        ui.render(GachaFrame(4,GachaScreen.Offers(v.copy(options=v.options.map { it.copy(requestId=RequestId("next-${it.id}")) }))),true)
        assertTrue(ui.select("paid"))
    }
    @Test fun `호스트 경제 키나 확인 내용이 바뀌면 이전 실패 확인 전 새 요청을 막는다`() {
        val ui = GachaInteraction({}); ui.render(ready(),true); val first=draw(ui)
        ui.complete(first,ContractResult.Failure(ContractError.STORAGE_FAILURE))
        val v = (ready().screen as GachaScreen.Offers).value
        val updated=v.copy(options=v.options.map { if(it.id == "paid") it.copy(requestId=RequestId("different-key")) else it })
        ui.render(GachaFrame(2,GachaScreen.Offers(updated)),true); assertFalse(ui.select("paid"))
        assertFalse(ui.resolveFailedRequest(RequestId("unrelated")))
        assertTrue(ui.resolveFailedRequest(first.input.option.requestId!!)); assertTrue(ui.select("paid"))
        assertTrue(ui.confirm()); assertEquals(RequestId("different-key"),ui.uiState.pending!!.input.option.requestId)
    }
    @Test fun `입력 원본과 반환 목록 맵 변경은 표시 요청 및 복원 상태에 영향을 주지 않는다`() {
        val v=(ready().screen as GachaScreen.Offers).value
        val ids=v.candidates!!.toMutableList(); val rates=v.appliedPercent!!.toMutableMap(); val options=v.options.toMutableList()
        val weights=v.content!!.gacha.weights.toMutableMap()
        val content=v.content.copy(gacha=v.content.gacha.copy(weights=weights))
        val ui=GachaInteraction({}); ui.render(GachaFrame(1,GachaScreen.Offers(v.copy(content=content,candidates=ids,appliedPercent=rates,options=options))),true)
        val request=draw(ui); ids.clear(); rates.clear(); options.clear(); weights.clear()
        val restored=GachaInteraction({},ui.uiState)
        assertEquals(2,request.input.offers.candidates!!.size)
        assertEquals(4,restored.uiState.pending!!.input.offers.content!!.gacha.weights.size)
        assertThrows(UnsupportedOperationException::class.java) { (request.input.offers.options as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (restored.uiState.reservations as MutableMap).clear() }
    }
    @Test fun `동일 경제 키를 다른 옵션에 넣거나 적용 확률 합계가 틀리면 표시 오류로 거절한다`() {
        val v=(ready().screen as GachaScreen.Offers).value
        val frames=listOf(v.copy(options=v.options.map { it.copy(requestId=RequestId("collision")) }),
            v.copy(appliedPercent=v.appliedPercent!! + (Rarity.COMMON to java.math.BigDecimal("74.8"))))
        for(value in frames) {
            val ui=GachaInteraction({});ui.render(GachaFrame(1,GachaScreen.Offers(value)),true)
            assertEquals(GachaAvailability.INVALID_DISPLAY,value.displayProblem());assertFalse(ui.select("paid"))
        }
    }
    @Test fun `재조회 잠금은 새 인스턴스 복원과 늦은 다른 조회 응답으로 풀리지 않는다`() {
        val events=mutableListOf<GachaEvent>(); val ui=GachaInteraction(events::add)
        ui.render(GachaFrame(1,GachaScreen.Missing("입력 누락",true)),true)
        assertTrue(ui.reload());val key=ui.uiState.reloadToken!!
        val restored=GachaInteraction(events::add,ui.uiState)
        restored.render(GachaFrame(1,GachaScreen.Missing("입력 누락",true)),true)
        assertFalse(restored.reload());restored.completeReload("other");assertFalse(restored.reload())
        restored.completeReload(key);assertTrue(restored.reload());assertEquals(2,events.size)
    }
    @Test fun `동기 응답은 Pending 등록 뒤만 처리하고 해제 뒤 입력과 응답은 버린다`() {
        lateinit var ui:GachaInteraction
        ui=GachaInteraction({ e -> if(e is GachaEvent.Draw) ui.complete(e.request,ContractResult.Success(Unit)) })
        ui.render(ready(),true);assertTrue(ui.select("paid"));assertTrue(ui.confirm());assertNull(ui.uiState.pending)
        assertTrue(ui.awaitingConfirmation)
        val saved=ui.uiState;ui.release();assertFalse(ui.render(GachaPreviewStates.result(),true));assertEquals(saved,ui.uiState)
    }
    @Test fun `같은 세대의 다른 내용과 낮은 세대의 허용은 제한을 풀지 않는다`() {
        val ui=GachaInteraction({});val frame=ready();ui.render(frame,true)
        assertFalse(ui.render(frame.copy(screen=GachaScreen.Loading),false));assertFalse(ui.controlsEnabled)
        assertFalse(ui.render(frame.copy(generation=0),true));assertFalse(ui.controlsEnabled)
        assertEquals(frame,ui.frame)
    }
    @Test fun `차고 이동은 외부 결과에서만 가능하고 중복이나 다른 응답을 구분한다`() {
        val events=mutableListOf<GachaEvent>();val ui=GachaInteraction(events::add)
        ui.render(ready(),true);assertFalse(ui.openGarage());ui.render(GachaPreviewStates.result(),false);assertFalse(ui.openGarage())
        ui.render(GachaPreviewStates.result(),true);assertTrue(ui.openGarage());assertFalse(ui.openGarage())
        val request=(events.last() as GachaEvent.OpenGarage).request
        ui.completeGarageNavigation(request.copy(token="other"));assertFalse(ui.openGarage())
        ui.completeGarageNavigation(request)
        assertTrue(ui.openGarage());assertEquals(2,events.size)
    }

    @Test fun `옵션별 비용 권수 요청키 누락은 이전 요청 오류로 표시하지 않는다`() {
        val v=(ready().screen as GachaScreen.Offers).value; val option=v.options.first()
        for (missing in listOf(option.copy(moneyCost=null),option.copy(ticketCost=null),option.copy(ownedTickets=null),option.copy(requestId=null))) {
            val ui=GachaInteraction({});ui.render(GachaFrame(1,GachaScreen.Offers(v.copy(options=listOf(missing)))),true)
            assertEquals(GachaAvailability.MISSING,missing.displayProblem());assertFalse(ui.select(missing.id))
        }
    }
    @Test fun `현재 후보의 게임 콘텐츠 참조 누락은 정상 후보군으로 제출하지 않는다`() {
        val v=(ready().screen as GachaScreen.Offers).value
        val missing=v.copy(content=v.content!!.copy(vehicles=emptyList()))
        val ui=GachaInteraction({});ui.render(GachaFrame(1,GachaScreen.Offers(missing)),true)
        assertEquals(GachaAvailability.INVALID_DISPLAY,missing.displayProblem());assertFalse(ui.select("paid"))
    }

}
