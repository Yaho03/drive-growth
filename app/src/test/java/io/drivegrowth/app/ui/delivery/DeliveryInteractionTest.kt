package io.drivegrowth.app.ui.delivery

import org.junit.Assert.*
import org.junit.Test

class DeliveryInteractionTest {
    private fun ready(version: Long = 1) = DeliveryFrame(version, DeliveryScreen.Destination(null, null, null, true))

    @Test fun `응답 전 재표시는 요청 중복을 풀지 않는다`() {
        val session = DeliveryInteraction()
        session.render(ready(), true)
        val first = session.request(DeliveryCommand.ChooseDestination(null))!!
        session.render(ready(2), true)
        assertNull(session.request(first.command))
        assertNotNull(session.pending)
        assertTrue(session.complete(first, ready(3)))
        assertNotNull(session.request(first.command))
    }

    @Test fun `제한 중 응답은 허용을 복구하지 않고 오래된 버튼은 거절한다`() {
        val session = DeliveryInteraction()
        session.render(ready(), true)
        val first = session.request(DeliveryCommand.ChooseDestination(null))!!
        session.render(ready(), false)
        assertTrue(session.complete(first, ready(2)))
        assertFalse(session.controlsEnabled)
        assertNull(session.request(first.command))
        assertFalse(session.render(ready(), true))
        assertFalse(session.controlsEnabled)
    }

    @Test fun `이전 응답은 재시도 결과와 새 화면을 덮지 않는다`() {
        val session = DeliveryInteraction()
        session.render(ready(), true)
        val first = session.request(DeliveryCommand.ChooseDestination(null))!!
        session.complete(first, ready(2))
        val second = session.request(first.command)!!
        assertFalse(session.complete(first, ready(5)))
        assertEquals(second, session.pending)
        assertEquals(2L, session.frame!!.version)
        assertFalse(session.complete(second, ready(1)))
        assertEquals(second, session.pending)
    }

    @Test fun `해제 뒤 입력과 응답은 폐기한다`() {
        val session = DeliveryInteraction()
        session.render(ready(), true)
        val first = session.request(DeliveryCommand.ChooseDestination(null))!!
        session.release()
        assertFalse(session.complete(first, ready(2)))
        assertFalse(session.render(ready(3), true))
        assertNull(session.request(first.command))
    }

    @Test fun `재생성은 같은 미확정 요구를 보존하며 자동 재전송하지 않는다`() {
        val session = DeliveryInteraction()
        session.render(ready(), true)
        val first = session.request(DeliveryCommand.ChooseDestination(null))!!
        val restored = DeliveryInteraction(session.save())
        restored.render(ready(2), true)
        assertEquals(first, restored.pending)
        assertNull(restored.request(first.command))
        assertTrue(restored.complete(first, ready(3)))
        assertTrue(restored.request(first.command)!!.sequence > first.sequence)
    }

    @Test fun `미지원 누락과 로딩은 정상 선택으로 바뀌지 않는다`() {
        val session = DeliveryInteraction()
        listOf(DeliveryScreen.Loading, DeliveryScreen.Unsupported("미지원"),
            DeliveryScreen.Failure("저장 실패", false), DeliveryScreen.Destination(null, null, null, false))
            .forEachIndexed { index, state ->
                session.render(DeliveryFrame(index.toLong(), state), true)
                assertNull(session.request(DeliveryCommand.ChooseDestination(null)))
                assertNull(session.request(DeliveryCommand.Refresh))
            }
    }

    @Test fun `수익과 구매는 제시된 전체 문맥과 허용된 후보만 제출한다`() {
        val session = DeliveryInteraction()
        val data = DeliveryPreviewStates
        session.render(data.offers, true)
        val income = DeliveryCommand.Select(data.proposed, data.income.id)
        val purchase = DeliveryCommand.Select(data.proposed, data.purchase.id)
        assertTrue(session.canRequest(income))
        assertTrue(session.canRequest(purchase))
        assertFalse(session.canRequest(purchase.copy(proposed = data.proposed.copy(
            destination = io.drivegrowth.core.delivery.DestinationKey("다른 목적지")))))
        session.request(purchase)
        assertFalse(session.canRequest(income))
        assertEquals(data.proposed, (session.pending!!.command as DeliveryCommand.Select).proposed)
    }

    @Test fun `부족 정보와 확인 조건 누락은 금액0이나 허용으로 대체하지 않는다`() {
        val data = DeliveryPreviewStates
        listOf(data.proposal.copy(availability = emptyMap()),
            data.proposal.copy(confirmationTerms = null),
            data.proposal.copy(availability = mapOf(data.purchase.id to DeliveryAvailability(false, "잔액 부족"))))
            .forEach { proposal ->
                val session = DeliveryInteraction()
                session.render(DeliveryFrame(1, DeliveryScreen.Offers(proposal)), true)
                assertFalse(session.canRequest(DeliveryCommand.Select(data.proposed, data.purchase.id)))
            }
    }

    @Test fun `활성 의뢰에서는 새 선택을 막고 변경 기회 사용을 존중한다`() {
        val data = DeliveryPreviewStates
        val session = DeliveryInteraction()
        session.render(DeliveryFrame(1, DeliveryScreen.Active(data.active.copy(change = DeliveryAvailability(false, "이미 사용")))), true)
        assertFalse(session.canRequest(DeliveryCommand.ChooseDestination(data.active.active)))
        assertFalse(session.canRequest(DeliveryCommand.Select(data.proposed, data.purchase.id)))
        assertTrue(session.canRequest(DeliveryCommand.ReviewAbandon(data.active.active)))
    }

    @Test fun `변경과 포기는 명시된 금액과 조건이 있을 때만 확정한다`() {
        val data = DeliveryPreviewStates
        val session = DeliveryInteraction()
        val change = DeliveryScreen.ChangeReview(data.active, data.proposal, data.purchase.id,
            DeliveryMoneyUi(io.drivegrowth.core.contract.MoneyDirection.CREDIT, io.drivegrowth.core.contract.GameMoney(20)),
            "희귀도 변경 · 공급자 결과", DeliveryAvailability(true))
        val command = DeliveryCommand.Change(data.active.active, data.proposed, data.purchase.id)
        session.render(DeliveryFrame(1, change), true)
        assertTrue(session.canRequest(command))
        session.render(DeliveryFrame(2, change.copy(difference = null)), true)
        assertFalse(session.canRequest(command))
        assertTrue(session.canRequest(DeliveryCommand.ExitReview))
        val abandon = DeliveryScreen.AbandonReview(data.active, io.drivegrowth.core.contract.GameMoney(80),
            io.drivegrowth.core.contract.GameMoney(720), "목록 유지 · 차량 지급 없음", DeliveryAvailability(true))
        session.render(DeliveryFrame(3, abandon), true)
        assertTrue(session.canRequest(DeliveryCommand.Abandon(data.active.active)))
        session.render(DeliveryFrame(4, abandon.copy(refund = null)), true)
        assertFalse(session.canRequest(DeliveryCommand.Abandon(data.active.active)))
        assertTrue(session.canRequest(DeliveryCommand.ExitReview))
    }

    @Test fun `도착 확인 대기와 빈 후보는 성공이나 대체 선택을 만들지 않는다`() {
        val data = DeliveryPreviewStates
        val session = DeliveryInteraction()
        session.render(DeliveryFrame(1, DeliveryScreen.AwaitingResult(data.active, "도착 증거 확인 중")), true)
        assertFalse(session.canRequest(DeliveryCommand.Abandon(data.active.active)))
        session.render(DeliveryFrame(2, DeliveryScreen.Offers(data.proposal.copy(proposed = data.proposed.copy(offers = emptyList())))), true)
        assertFalse(session.canRequest(DeliveryCommand.Select(data.proposed, data.purchase.id)))
    }

    @Test fun `공급자 목록 변경은 표시와 요청 스냅샷을 바꾸지 않는다`() {
        val data = DeliveryPreviewStates
        val offers = data.proposed.offers.toMutableList()
        val names = data.proposal.vehicleNames.toMutableMap()
        val proposed = data.proposed.copy(offers = offers, list = data.proposed.list.copy(offers = offers))
        val frame = DeliveryFrame(1, DeliveryScreen.Offers(data.proposal.copy(proposed = proposed, vehicleNames = names)))
        val session = DeliveryInteraction()
        session.render(frame, true)
        offers.clear(); names.clear()
        val shown = (session.frame!!.screen as DeliveryScreen.Offers).proposal
        assertEquals(2, shown.proposed.offers.size)
        assertEquals(2, shown.vehicleNames.size)
        assertTrue(session.canRequest(DeliveryCommand.Select(shown.proposed, data.purchase.id)))
    }

    @Test fun `제출과 복원 이후 원본 변경은 미확정 명령을 바꾸지 않는다`() {
        val data = DeliveryPreviewStates
        val offers = data.proposed.offers.toMutableList()
        val proposed = data.proposed.copy(offers = offers, list = data.proposed.list.copy(offers = offers))
        val session = DeliveryInteraction()
        session.render(DeliveryFrame(1, DeliveryScreen.Offers(data.proposal.copy(proposed = proposed))), true)
        val request = session.request(DeliveryCommand.Select(proposed, data.purchase.id))!!
        val restoredRequest = DeliveryRequest(30, 1, DeliveryCommand.Select(proposed, data.purchase.id))
        val restored = DeliveryInteraction(DeliveryInteractionState(pending = restoredRequest, nextSequence = 31))
        offers.clear()
        assertEquals(2, (request.command as DeliveryCommand.Select).proposed.offers.size)
        assertEquals(2, ((restored.pending!!.command) as DeliveryCommand.Select).proposed.list.offers.size)
    }
}
