package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DeliveryHostMapperTest {
    private val source=InputSource.SIMULATION
    private val offer=DeliveryOffer.Purchase(DeliveryOfferId("offer-0"),VehicleId("casper"),Rarity.COMMON,GameMoney(800))
    private val list=DeliveryList(0,source,LocalDate.of(2026,11,10),ContentVersion("c"),RuleVersion("r"),listOf(offer),1)
    private val proposed=DeliveryProposed(DestinationKey("destination"),EstimatedRouteDistance(1000,source),list,list.offers)
    private val active=ActiveDelivery(RequestId("selected"),proposed,offer)
    @Test fun `구매 후보는 실제 잔액 부족과 출처를 구분한다`() {
        val ui=DeliveryHostMapper.proposal(proposed,mapOf(offer.vehicleId to "캐스퍼"),"목적지",GameMoney(799),source)
        assertFalse(ui.availability.getValue(offer.id).allowed)
        assertFalse(DeliveryHostMapper.proposal(proposed,emptyMap(),null,GameMoney(1000),InputSource.VEHICLE).availability.getValue(offer.id).allowed)
    }
    @Test fun `소모한 변경 기회와 실제 관측 누락을 보정하지 않는다`() {
        val ui=DeliveryHostMapper.active(DeliveryTerms(active,GameMoney(800),true),emptyMap(),null,Observation.Missing(MissingReason.NO_DATA))
        assertFalse(ui.change.allowed);assertTrue(ui.abandon.allowed);assertNull(ui.observedDistanceLabel)
    }
    @Test fun `포기 화면은 기존 계산이 돌려준 환급과 수수료를 사용한다`() {
        val terms=DeliveryTerms(active,GameMoney(800),false)
        val ui=DeliveryHostMapper.abandon(terms,DeliveryChangeRules(RuleVersion("r"),10),emptyMap(),null)
        assertEquals(GameMoney(80),ui.fee);assertEquals(GameMoney(720),ui.refund);assertTrue(ui.permission.allowed)
    }

    @Test fun `포기 안내 문구는 수익 탁송과 구매 탁송을 구분한다`() {
        val income=DeliveryHostMapper.abandonNote(DeliveryOffer.Income(DeliveryOfferId("income-0"),VehicleId("sonata"),GameMoney(150)))
        assertTrue(income.contains("완료 보상은 받지 못하지만 벌금은 없어요"))
        assertFalse(income.contains("구매 차량") || income.contains("수수료"))
        assertTrue(DeliveryHostMapper.abandonNote(offer).contains("표시된 수수료를 공제한 금액"))
    }

    @Test fun `변경 후보는 전체 구매가가 아닌 실제 차액으로 잔액을 검사한다`() {
        val cheaper=offer.copy(price=GameMoney(600))
        val next=proposed.copy(list=list.copy(offers=listOf(cheaper)),offers=listOf(cheaper))
        val changed=DeliveryChangeOffer(active.requestId,100,1100,next)
        val ui=DeliveryHostMapper.changeProposal(DeliveryTerms(active,GameMoney(800),false),changed,
            DeliveryChangeRules(RuleVersion("r"),10),mapOf(offer.vehicleId to "캐스퍼"),"변경 목적지",GameMoney(0),source)
        assertTrue(ui.availability.getValue(cheaper.id).allowed)
    }
}
