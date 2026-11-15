package io.drivegrowth.app.ui.shop

import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class ShopInteractionTest {
    private val appearance = ShopAppearance("확정 간판", 0xff3066ff.toInt())
    private val option = ShopOption("sign-blue", "새 간판", "승인된 시험 장식", "간판 변경",
        ShopAvailability.AVAILABLE, ShopCommand.Decorate("sign-blue", appearance.copy(sign = "미리보기 간판")),
        GameMoney(0), RuleVersion("fixture"), true)
    private fun content() = ShopScreenState.Content(Revision(3), "시험 가게", 2, 1, 3, GameMoney(100),
        appearance, emptyList(), emptyList(), null, listOf(option), ShopOrigin.FIXTURE)
    private fun selection() = ShopSelection(Revision(3), option)

    @Test fun `미리보기 취소 실패와 성공 응답은 확정 사실을 변경하지 않는다`() {
        val events = mutableListOf<ShopEvent>()
        val i = ShopInteraction(events::add)
        val c = content(); i.render(c, true)
        i.select(selection()); assertEquals(c, i.state); assertTrue(i.cancel()); assertEquals(c, i.state)
        i.select(selection()); assertTrue(i.confirm(selection()))
        val request = i.uiState.pending!!
        val id = request.requestId
        i.complete(request, ContractResult.Failure(ContractError.STORAGE_FAILURE)); assertEquals(c, i.state)
        i.select(selection()); assertTrue(i.confirm(selection()))
        assertEquals(id, i.uiState.pending!!.requestId)
        val retry = i.uiState.pending!!
        i.complete(request, ContractResult.Success(Unit))
        assertEquals(retry, i.uiState.pending)
        i.complete(i.uiState.pending!!, ContractResult.Success(Unit))
        assertEquals(c, i.state); assertFalse(i.canSelect(selection()))
        assertEquals(2, events.size)
        i.render(c.copy(revision = Revision(4), appearance = (option.command as ShopCommand.Decorate).appearance), true)
        assertEquals("미리보기 간판", (i.state as ShopScreenState.Content).appearance.sign)
    }

    @Test fun `중복 클릭 재표시와 복원은 요청을 추가 전송하지 않는다`() {
        val events = mutableListOf<ShopEvent>(); val i = ShopInteraction(events::add)
        i.render(content(), true); i.select(selection()); i.confirm(selection()); i.confirm(selection())
        val saved = i.uiState; i.render(ShopScreenState.Loading, false); i.render(content(), true)
        i.confirm(selection()); assertEquals(1, events.size)
        val restored = ShopInteraction(events::add); restored.restoreUiState(saved); restored.render(content(), true)
        assertFalse(restored.confirm(selection())); assertEquals(saved.pending, restored.uiState.pending)
        assertEquals(1, events.size)
    }

    @Test fun `revision 가격 내용과 허용 변경은 이전 확인을 거절한다`() {
        for (changed in listOf(content().copy(revision = Revision(4)), content().copy(decorations = listOf(option.copy(price = GameMoney(1)))),
            content().copy(decorations = listOf(option.copy(availability = ShopAvailability.LOCKED))))) {
            val events = mutableListOf<ShopEvent>(); val i = ShopInteraction(events::add)
            i.render(content(), true); i.select(selection()); i.render(changed, true)
            assertFalse(i.confirm(selection())); assertTrue(events.isEmpty())
        }
    }

    @Test fun `제한 중 오래된 버튼과 다른 요청 응답은 무시한다`() {
        val events = mutableListOf<ShopEvent>(); val i = ShopInteraction(events::add)
        i.render(content(), true); i.select(selection()); i.render(content(), false)
        assertFalse(i.confirm(selection())); i.select(selection()); assertFalse(i.cancel()); assertTrue(events.isEmpty())
        i.render(content(), true); i.confirm(selection())
        val pending = i.uiState.pending
        i.complete(i.uiState.pending!!.copy(requestId = RequestId("other")), ContractResult.Success(Unit)); assertEquals(pending, i.uiState.pending)
        i.render(content().copy(revision = Revision(4)), false)
        i.complete(pending!!, ContractResult.Success(Unit))
        assertEquals(Revision(4), (i.state as ShopScreenState.Content).revision)
    }

    @Test fun `입력 목록과 반환 목록 변경은 확정 snapshot을 바꾸지 않는다`() {
        val options = mutableListOf(option); val i = ShopInteraction({})
        i.render(content().copy(decorations = options), true); options.clear()
        val snapshot = i.state as ShopScreenState.Content
        assertEquals(1, snapshot.decorations.size)
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.decorations as MutableList).clear() }
    }

    @Test fun `실패 재조회는 제한과 중복을 막고 명시 응답 뒤 다시 가능하다`() {
        val events = mutableListOf<ShopEvent>(); val i = ShopInteraction(events::add)
        i.render(ShopScreenState.Failure("실패"), false); i.retry(); assertTrue(events.isEmpty())
        i.render(ShopScreenState.Failure("실패"), true); i.retry(); i.retry(); assertEquals(1, events.size)
        i.render(ShopScreenState.Failure("실패"), true); i.retry(); assertEquals(1, events.size)
        i.completeRetry(); i.retry(); assertEquals(2, events.size)
    }

    @Test fun `동기 응답은 pending을 먼저 만든 뒤 처리한다`() {
        lateinit var i: ShopInteraction
        i = ShopInteraction({ event -> if (event is ShopEvent.Submit) i.complete(event.request, ContractResult.Success(Unit)) })
        i.render(content(), true); i.select(selection()); assertTrue(i.confirm(selection()))
        assertNull(i.uiState.pending); assertEquals(selection(), i.uiState.acknowledged)
    }
    @Test fun `반영 중 안내는 새 확정 상태가 그려지면 사라지고 실패 안내에 오류 코드를 노출하지 않는다`() {
        val i = ShopInteraction({})
        i.render(content(), true); i.select(selection()); i.confirm(selection())
        i.complete(i.uiState.pending!!, ContractResult.Success(Unit))
        assertEquals("반영하고 있어요…", i.uiState.message)
        i.render(content(), true)
        assertEquals("반영하고 있어요…", i.uiState.message)
        i.render(content().copy(revision = Revision(4)), true)
        assertNull(i.uiState.message)

        val failed = ShopInteraction({})
        failed.render(content(), true); failed.select(selection()); failed.confirm(selection())
        failed.complete(failed.uiState.pending!!, ContractResult.Failure(ContractError.STORAGE_FAILURE))
        assertEquals("처리하지 못했어요. 다시 시도해 주세요.", failed.uiState.message)
        assertFalse(failed.uiState.message!!.contains("STORAGE_FAILURE"))
    }

    @Test fun `공통 시설 제안 원문을 전달하고 표시 비용 불일치는 거절한다`() {
        val offer = io.drivegrowth.core.rental.FacilityOffer(io.drivegrowth.core.rental.Facility.CAR_WASH,
            RuleVersion("facility-fixture"), true, GameMoney(700))
        val wash = ShopOption("wash", "세차장", "공급자가 해금 확인", "리뷰 효과 · 공급값",
            ShopAvailability.AVAILABLE, ShopCommand.BuyFacility(offer), offer.price, offer.ruleVersion, true)
        val events = mutableListOf<ShopEvent>(); val i = ShopInteraction(events::add)
        i.render(content().copy(facilities = listOf(ShopFacility(offer.facility, wash))), true)
        val selected = ShopSelection(Revision(3), wash)
        i.select(selected); assertTrue(i.confirm(selected))
        assertEquals(ShopCommand.BuyFacility(offer), (events.single() as ShopEvent.Submit).request.selection.option.command)
        assertThrows(IllegalArgumentException::class.java) { wash.copy(price = GameMoney(0)) }
        assertThrows(IllegalArgumentException::class.java) { wash.copy(ruleVersion = RuleVersion("other")) }
    }

}
