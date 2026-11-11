package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class DeliveryOfferSourceTest {
    private class Harness {
        val identity=GameIdentity(ProfileId("profile"),InputSource.SIMULATION)
        val day=LocalDate.of(2026,11,10);val content=GameContentFixtures.loaded()
        var discoveryFailure = false
        val discoveryInputs = mutableListOf<DeliveryProposed>()
        var saved:DeliveryList?=null;var discoveries=0;var persistFailure=false;var foreign=false
        val queries=object:DeliveryHostQueries {
            override fun identity()=ContractResult.Success(identity)
            override fun read(identity:GameIdentity)=ContractResult.Success(DeliveryHostData(identity,content,GameMoney(100000),1,3,null,null,emptyList()))
            override fun lists(identity:GameIdentity,day:LocalDate)=ContractResult.Success(listOfNotNull(saved))
            override fun saveList(identity:GameIdentity,list:DeliveryList):ContractResult<DeliveryList>{if(persistFailure)return ContractResult.Failure(ContractError.STORAGE_FAILURE);if(saved==null)saved=list;return ContractResult.Success(saved!!)}
            override fun discover(identity:GameIdentity,proposed:DeliveryProposed):ContractResult<Unit>{discoveries++
                discoveryInputs += proposed
                return if (discoveryFailure) ContractResult.Failure(ContractError.STORAGE_FAILURE) else ContractResult.Success(Unit)}
        }
        private val nav=object:DeliveryNavigationPort {
            override fun read(identity:GameIdentity)=ContractResult.Success(DeliveryNavigationSnapshot(DestinationKey("selected"),"선택 목적지",
                Observation.Available(EstimatedRouteDistance(20000,if(foreign)InputSource.VEHICLE else InputSource.SIMULATION)),
                Observation.Missing(MissingReason.NO_DATA),Observation.Missing(MissingReason.NO_DATA),1))
            override fun chooseDestination(identity:GameIdentity,active:ActiveDelivery?)=ContractResult.Success(Unit)
        }
        private val rules=DeliveryHostRules(DeliveryFixtures.trialRules,DeliveryChangeRules(RuleVersion("r"),10),DeliverySettlementRules(RuleVersion("r"),86400000,86400000,5))
        val source=DeliveryOfferSource(queries,{ContractResult.Success(rules)},nav,{day},{Random(41)})
        fun offers()=(source.offers(identity) as ContractResult.Success).value
    }
    @Test fun `생성 조회만 한 후보는 발견으로 쓰지 않고 실제 표시 확인 뒤 기록한다`() {
        val h=Harness();val read=h.offers();assertEquals(0,h.discoveries);assertNotNull(h.saved)
        assertTrue(h.source.displayed(h.identity,read.displayedProposal!!) is ContractResult.Success);assertEquals(1,h.discoveries)
    }
    @Test fun `표시 확인의 목록이 저장 원문과 달라지면 기록하지 않는다`() {
        val h=Harness();val read=h.offers();val value=read.displayedProposal!!
        val altered=value.copy(list=value.list.copy(nextOfferNumber=value.list.nextOfferNumber+1))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),h.source.displayed(h.identity,altered));assertEquals(0,h.discoveries)
    }
    @Test fun `목록 저장 실패와 다른 출처 경로를 정상 후보로 숨기지 않는다`() {
        val h=Harness();h.persistFailure=true;assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),h.source.offers(h.identity));assertNull(h.saved)
        h.persistFailure=false;h.foreign=true;assertTrue(h.source.offers(h.identity) is ContractResult.Failure);assertNull(h.saved);assertEquals(0,h.discoveries)
    }

    @Test fun `발견 저장 실패는 원표시 증거를 남기고 성공한 증거만 소비한다`() {
        val h = Harness()
        val original = h.offers().displayedProposal!!
        h.discoveryFailure = true
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), h.source.displayed(h.identity, original))
        h.discoveryFailure = false
        assertEquals(ContractResult.Success(Unit), h.source.displayed(h.identity, original))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), h.source.displayed(h.identity, original))
        assertEquals(listOf(original, original), h.discoveryInputs)
    }
}
