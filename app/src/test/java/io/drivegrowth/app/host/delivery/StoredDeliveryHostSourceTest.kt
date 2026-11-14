package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.data.AbandonCommit
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.core.content.GameContentFixtures
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class StoredDeliveryHostSourceTest {
    private class Harness(purchase:Boolean=false) {
        val identity=GameIdentity(ProfileId("profile"),InputSource.SIMULATION);val day=LocalDate.of(2026,11,10)
        val content=GameContentFixtures.loaded();val calls=mutableListOf<String>();var readFailure=false;var saveFailure=false
        var foreign=false;var pending:DeliveryHostOperation?=null;var navMissing=false;var navGeneration:Long?=1L;var navDestination=DestinationKey("destination");var discoveries=0
        val list=DeliveryList(0,identity.source,day,content.catalog.contentVersion,RuleVersion("delivery-test"),listOf(if(purchase)DeliveryOffer.Purchase(DeliveryOfferId("offer-0"),content.catalog.vehicles.first().vehicleId,Rarity.COMMON,GameMoney(8000))else DeliveryOffer.Income(DeliveryOfferId("offer-0"),content.catalog.vehicles.first().vehicleId,GameMoney(20))),1)
        var savedLists = listOf(list)
        val active=ActiveDelivery(RequestId("active"),DeliveryProposed(DestinationKey("destination"),EstimatedRouteDistance(20000,identity.source),list,list.offers),list.offers.single())
        var terms:DeliveryTerms?=null;var progress:DeliveryInProgress?=null
        val queries=object:DeliveryHostQueries {
            override fun identity()=ContractResult.Success(identity)
            override fun read(identity:GameIdentity):ContractResult<DeliveryHostData>{calls+="read";return if(readFailure)ContractResult.Failure(ContractError.DATA_UNAVAILABLE)else ContractResult.Success(DeliveryHostData(identity,content,GameMoney(100000),1,3,terms,progress,emptyList()))}
            override fun lists(identity:GameIdentity,day:LocalDate)=ContractResult.Success(savedLists)
            override fun saveList(identity:GameIdentity,list:DeliveryList)=ContractResult.Success(list).also { calls += "saveList" }
            override fun discoverChange(identity:GameIdentity,change:DeliveryChangeOffer):ContractResult<Unit>{discoveries++;return ContractResult.Success(Unit)}
            override fun discover(identity:GameIdentity,proposed:DeliveryProposed)=ContractResult.Success(Unit)
        }
        private val nav=object:DeliveryNavigationPort {
            override fun read(identity:GameIdentity):ContractResult<DeliveryNavigationSnapshot> = if(navMissing)ContractResult.Failure(ContractError.DATA_UNAVAILABLE) else ContractResult.Success(DeliveryNavigationSnapshot(navDestination,"목적지",Observation.Available(EstimatedRouteDistance(20000,identity.source)),Observation.Available(ObservedDistance(1000,identity.source)),Observation.Missing(MissingReason.NO_DATA),navGeneration))
            override fun chooseDestination(identity:GameIdentity,active:ActiveDelivery?)=ContractResult.Success(Unit)
        }
        private val journal=object:DeliveryOperationJournal {
            override fun pending(identity:GameIdentity) = if (foreign) ContractResult.Failure(ContractError.STATE_CONFLICT) else ContractResult.Success(pending)
            override fun slot(identity: GameIdentity) = ContractResult.Success(
                if (foreign) DeliveryJournalSlot.Foreign else pending?.let(DeliveryJournalSlot::Own) ?: DeliveryJournalSlot.Clear
            )
            override fun save(identity:GameIdentity,operation:DeliveryHostOperation):ContractResult<Unit>{calls+="save";if(saveFailure)return ContractResult.Failure(ContractError.STORAGE_FAILURE);pending=operation;return ContractResult.Success(Unit)}
            override fun execute(identity:GameIdentity,operation:DeliveryHostOperation):ContractResult<DeliveryHostReceipt>{calls+="execute";assertEquals(pending,operation);return ContractResult.Success(DeliveryHostReceipt.Abandoned(AbandonCommit(RequestId("active"),null)))}
            override fun cancelUnstarted(identity:GameIdentity,operation:DeliveryHostOperation):ContractResult<Unit> = ContractResult.Failure(ContractError.STATE_CONFLICT)
            override fun finish(identity:GameIdentity,operation:DeliveryHostOperation):ContractResult<Unit>{calls+="finish";pending=null;return ContractResult.Success(Unit)}
        }
        val rules=DeliveryHostRules(DeliveryFixtures.trialRules,DeliveryChangeRules(RuleVersion("r"),10),DeliverySettlementRules(RuleVersion("r"),10000,10000,5))
        var nowMillis = 500L
        val source=StoredDeliveryHostSource(queries,{ContractResult.Success(rules)},nav,journal,{day},{EpochMillis(nowMillis)},{Random(1)},{RequestId("selection")},{RouteId("route")})
        val original=DeliveryHostOperation.Abandon(rules.change,RequestId("active"))
    }
    @Test fun `기존 원명령은 현재 콘텐츠 읽기 실패보다 먼저 복구한다`() {
        val h=Harness();h.pending=h.original;h.readFailure=true
        val result=(h.source.read() as ContractResult.Success).value;assertEquals(h.original,result.pending);assertTrue(h.calls.isEmpty())
    }
    @Test fun `다른 기능의 슬롯은 활성 탁송 읽기를 유지하고 만료 처리도 시작하지 않는다`() {
        val h = Harness()
        h.foreign = true
        h.nowMillis = 20_000
        h.terms = DeliveryTerms(h.active, GameMoney(0), false)
        h.progress = DeliveryInProgress(h.active, RouteId("route"), EpochMillis(0))
        val read = (h.source.read() as ContractResult.Success).value
        val active = (read.screen as DeliveryScreen.Active).delivery
        assertEquals(h.active, active.active)
        assertFalse(active.change.allowed)
        assertFalse(active.abandon.allowed)
        assertTrue(read.readOnly)
        assertNull(read.pending)
        assertEquals(listOf("read"), h.calls)
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            h.source.prepare(h.identity, DeliveryCommand.ChooseDestination(h.active)))
        h.foreign = false
        assertTrue((h.source.read() as ContractResult.Success).value.pending is DeliveryHostOperation.Arrival)
    }

    @Test fun `다른 기능의 슬롯에서 새 탁송 목록을 만들지 않는다`() {
        val h = Harness()
        h.foreign = true
        val read = (h.source.read() as ContractResult.Success).value
        assertTrue(read.readOnly)
        assertNull(read.displayedProposal)
        assertFalse("saveList" in h.calls)
    }

    @Test fun `다른 기능의 슬롯에서 일치하는 저장 목록은 선택 없이 표시한다`() {
        val h = Harness()
        h.foreign = true
        h.savedLists = listOf(h.list.copy(
            band = h.rules.proposal.bandIndexOf(20_000), ruleVersion = h.rules.proposal.ruleVersion,
        ))
        val read = (h.source.read() as ContractResult.Success).value
        val offered = (read.screen as DeliveryScreen.Offers).proposal
        assertEquals(setOf(h.list.offers.single().id), offered.availability.keys)
        assertTrue(offered.availability.values.none { it.allowed })
        assertNull(read.displayedProposal)
        assertEquals(listOf("read"), h.calls)
    }

    @Test fun `저장 실패는 실행을 시작하지 않는다`() {
        val h=Harness();h.saveFailure=true
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),h.source.execute(h.identity,h.original));assertEquals(listOf("save"),h.calls)
    }
    @Test fun `확정 결과 뒤 현재 조회 실패도 원래 정산 증거를 버리지 않는다`() {
        val h=Harness();h.readFailure=true
        val result=(h.source.execute(h.identity,h.original) as ContractResult.Success).value
        assertEquals(listOf("save","execute","read"),h.calls);assertEquals(h.original,result.pending);assertTrue(result.receipt is DeliveryHostReceipt.Abandoned)
    }
    @Test fun `도착 증거가 없으면 정산 요청을 만들지 않고 활성 의뢰를 유지한다`() {
        val h=Harness();h.terms=DeliveryTerms(h.active,GameMoney(0),false);h.progress=DeliveryInProgress(h.active,RouteId("route"),EpochMillis(0))
        val result=(h.source.read() as ContractResult.Success).value;assertNull(result.pending);assertTrue(result.screen is DeliveryScreen.Active);assertEquals(listOf("read"),h.calls)
    }

    @Test fun `내비 읽기 실패도 저장된 활성 의뢰를 없애거나 포기를 막지 않는다`() {
        val h=Harness();h.terms=DeliveryTerms(h.active,GameMoney(0),false);h.navMissing=true
        val read=(h.source.read() as ContractResult.Success).value
        assertTrue(read.screen is DeliveryScreen.Active);assertNull(read.pending)
        val review=(h.source.prepare(h.identity,DeliveryCommand.ReviewAbandon(h.active)) as ContractResult.Success).value
        assertTrue(review.screen is DeliveryScreen.AbandonReview)
    }
    @Test fun `변경 구매 후보도 표시 확인 뒤에만 발견으로 기록한다`() {
        val h=Harness(true);h.terms=DeliveryTerms(h.active,GameMoney(8000),false)
        assertTrue(h.source.prepare(h.identity,DeliveryCommand.ChooseDestination(h.active)) is ContractResult.Success)
        h.navGeneration=2;val read=(h.source.read() as ContractResult.Success).value
        assertEquals(0,h.discoveries);assertNotNull(read.displayedProposal)
        assertTrue(h.source.displayed(h.identity,read.displayedProposal!!) is ContractResult.Success);assertEquals(1,h.discoveries)
    }

    @Test fun `확인 중 목적지가 달라지면 옛 후보로 새 결제를 준비하지 않는다`() {
        val h=Harness();h.navDestination=DestinationKey("other-destination")
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),h.source.prepare(h.identity,
            DeliveryCommand.Select(h.active.proposed,h.active.offer.id)))
    }

    @Test fun `사용자 선택 revision이 없으면 연결 세대가 바뀌어도 변경 후보를 만들지 않는다`() {
        val h=Harness();h.terms=DeliveryTerms(h.active,GameMoney(0),false);h.navGeneration=null
        assertTrue(h.source.prepare(h.identity,DeliveryCommand.ChooseDestination(h.active)) is ContractResult.Success)
        val read=(h.source.read() as ContractResult.Success).value
        assertTrue(read.screen is DeliveryScreen.AwaitingResult);assertNull(read.pending)
    }

    @Test fun `변경 후보 확인 중 새 목적지가 선택되면 옛 후보를 결제에 쓰지 않는다`() {
        val h=Harness(true);h.terms=DeliveryTerms(h.active,GameMoney(8000),false)
        h.source.prepare(h.identity,DeliveryCommand.ChooseDestination(h.active));h.navGeneration=2
        val offered=((h.source.read() as ContractResult.Success).value.screen as DeliveryScreen.Offers).proposal.proposed
        h.navDestination=DestinationKey("new-selection");h.navGeneration=3
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),h.source.prepare(h.identity,
            DeliveryCommand.Select(offered,offered.offers.first().id)))
        val refreshed=((h.source.read() as ContractResult.Success).value.screen as DeliveryScreen.Offers).proposal.proposed
        assertEquals(h.navDestination,refreshed.destination)
    }
}
