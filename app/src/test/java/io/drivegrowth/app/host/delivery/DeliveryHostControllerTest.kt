package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.data.AbandonCommit
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.Executor

class DeliveryHostControllerTest {
    private class Harness {
        val tasks=ArrayDeque<Runnable>();val replies=ArrayDeque<Runnable>();val calls=mutableListOf<String>()
        val identity=GameIdentity(ProfileId("profile"),InputSource.SIMULATION)
        val operation=DeliveryHostOperation.Abandon(DeliveryChangeRules(RuleVersion("r"),10),RequestId("active"))
        var pending:DeliveryHostOperation?=null;var fail=false;var cancelFailure=false;var finishFailure=false
        var prepareHook:(()->Unit)?=null;var readHook:(()->Unit)?=null;var suppliedIdentity=identity
        var screen:DeliveryScreen=DeliveryScreen.Destination(null,null,"모의 입력",true)
        val shown = mutableListOf<DeliveryProposed>()
        var discoveryFailure = false
        var discoveryError = ContractError.STORAGE_FAILURE
        var proposal: DeliveryProposed? = null
        var receipt: DeliveryHostReceipt? = null
        fun offers(): DeliveryProposed {
            val offer = DeliveryOffer.Purchase(DeliveryOfferId("offer-0"), VehicleId("casper"), Rarity.COMMON, GameMoney(800))
            val list = DeliveryList(0, identity.source, LocalDate.of(2026, 11, 11), ContentVersion("cars"),
                RuleVersion("rules"), listOf(offer), 1)
            return DeliveryProposed(DestinationKey("destination"), EstimatedRouteDistance(20_000, identity.source),
                list, list.offers).also {
                proposal = it
                screen = DeliveryScreen.Offers(DeliveryHostMapper.proposal(it, mapOf(offer.vehicleId to "캐스퍼"),
                    "목적지", GameMoney(1000), identity.source))
            }
        }
        val source=object:DeliveryHostSource {
            override fun read():ContractResult<DeliveryHostRead>{calls+="read";val hook=readHook;readHook=null;hook?.invoke();return ContractResult.Success(DeliveryHostRead(suppliedIdentity,screen,displayedProposal=proposal,pending=pending))}
            override fun prepare(identity:GameIdentity,command:DeliveryCommand):ContractResult<DeliveryHostPrepared>{calls+="prepare";prepareHook?.invoke();return ContractResult.Success(DeliveryHostPrepared(screen,operation))}
            override fun execute(identity:GameIdentity,operation:DeliveryHostOperation):ContractResult<DeliveryHostRead>{calls+="execute";pending=operation;return if(fail)ContractResult.Failure(ContractError.STORAGE_FAILURE)else ContractResult.Success(DeliveryHostRead(identity,screen,displayedProposal=proposal,pending=operation,receipt=receipt))}
            override fun cancelUnstarted(identity:GameIdentity,operation:DeliveryHostOperation):ContractResult<Unit>{calls+="cancel";if(cancelFailure)return ContractResult.Failure(ContractError.STATE_CONFLICT);pending=null;return ContractResult.Success(Unit)}
            override fun finish(identity:GameIdentity,operation:DeliveryHostOperation):ContractResult<Unit>{calls+="finish";if(finishFailure)return ContractResult.Failure(ContractError.STORAGE_FAILURE);pending=null;return ContractResult.Success(Unit)}
            override fun displayed(identity: GameIdentity, proposed: DeliveryProposed): ContractResult<Unit> {
                calls += "displayed"
                shown += proposed
                return if (discoveryFailure) ContractResult.Failure(discoveryError) else ContractResult.Success(Unit)
            }
        }
        var frame:DeliveryFrame?=null;var enabled=false;val completed=mutableListOf<DeliveryRequest>()
        val host=DeliveryHostController(source,Executor{tasks.addLast(it)},Executor{replies.addLast(it)})
        var discoveryRefreshes = 0
        init { host.attach({ f, e -> frame = f; enabled = e }, { r, f -> completed += r; frame = f },
            onDiscoverySaved = { discoveryRefreshes++ }) }
        fun drain(){while(tasks.isNotEmpty()||replies.isNotEmpty()){if(tasks.isNotEmpty())tasks.removeFirst().run();if(replies.isNotEmpty())replies.removeFirst().run()}}
        fun start(){host.setControlsEnabled(true);host.refresh();drain()}
    }
    @Test fun `실제 조회 전 조작을 열지 않고 허용된 조회를 공급한다`() {
        val h=Harness();assertFalse(h.enabled);h.start();assertTrue(h.frame!!.screen is DeliveryScreen.Destination);assertEquals(listOf("read"),h.calls)
    }
    @Test fun `보관한 원명령은 새 prepare 없이 재생한다`() {
        val h=Harness();h.pending=h.operation;h.start();assertEquals(listOf("read","execute"),h.calls)
    }
    @Test fun `release 뒤 대기 조회와 늦은 응답은 폐기한다`() {
        val h=Harness();h.host.setControlsEnabled(true);h.host.refresh();h.host.release();h.drain();assertTrue(h.calls.isEmpty())
    }

    @Test fun `조회 중 gate 세대가 바뀌면 옛 조회로 실행하지 않는다`() {
        val h=Harness();h.pending=h.operation;h.readHook={h.host.setControlsEnabled(false);h.host.setControlsEnabled(true)}
        h.start();assertEquals(listOf("read","read","execute"),h.calls)
    }
    @Test fun `다른 프로필 응답은 보관 명령을 실행하지 않는다`() {
        val h=Harness();h.start();h.calls.clear();h.suppliedIdentity=GameIdentity(ProfileId("other"),InputSource.SIMULATION);h.pending=h.operation
        h.host.refresh();h.drain();assertEquals(listOf("read"),h.calls);assertTrue(h.frame!!.screen is DeliveryScreen.Failure)
    }
    @Test fun `알고 있던 원명령이 사라져도 새 prepare로 보정하지 않는다`() {
        val h=Harness();h.pending=h.operation;h.fail=true;h.start();h.pending=null;h.calls.clear()
        h.host.refresh();h.drain();assertEquals(listOf("read"),h.calls);assertTrue(h.frame!!.screen is DeliveryScreen.Failure)
    }


    @Test fun `gate가 먼저 닫힌 확인 요청은 실패 응답으로 UI 잠금을 해소한다`() {
        val h=Harness();h.start();val input=DeliveryRequest(1,h.frame!!.version,DeliveryCommand.ChooseDestination(null))
        h.host.setControlsEnabled(false);h.host.onRequest(input);h.drain()
        assertEquals(listOf(input),h.completed);assertFalse(h.enabled);assertFalse(h.calls.contains("prepare"))
    }
    @Test fun `gate로 폐기한 요청은 재허용 후 새 조회로 복원한다`() {
        val h=Harness();h.start();val input=DeliveryRequest(1,h.frame!!.version,DeliveryCommand.ChooseDestination(null))
        h.host.onRequest(input);h.host.setControlsEnabled(false);h.drain();assertTrue(h.completed.isEmpty())
        h.host.setControlsEnabled(true);h.drain();assertEquals(listOf(input),h.completed)
        assertEquals(listOf("read","read"),h.calls)
    }

    @Test fun `명시 취소가 성공한 미실행 요청만 해소하고 현재 상태를 다시 읽는다`() {
        val h=Harness();h.pending=h.operation;h.fail=true;h.start();h.host.cancelUnstarted();h.drain()
        assertNull(h.pending);assertEquals(listOf("read","execute","cancel","read"),h.calls)
    }
    @Test fun `시작됐거나 불명인 요청의 취소 거절은 원명령을 보존한다`() {
        val h=Harness();h.pending=h.operation;h.fail=true;h.cancelFailure=true;h.start();h.host.cancelUnstarted();h.drain()
        assertEquals(h.operation,h.pending);assertEquals(listOf("read","execute","cancel"),h.calls)
    }
    @Test fun `확정 결과를 미실행 요청으로 취소하지 않는다`() {
        val h=Harness();h.pending=h.operation;h.start();h.host.cancelUnstarted();h.drain();assertEquals(listOf("read","execute"),h.calls)
    }

    @Test fun `확정 결과 확인 뒤에만 finish하고 다음 상태를 읽는다`() {
        val h=Harness();h.pending=h.operation;h.start();h.host.acknowledge();h.drain()
        assertNull(h.pending);assertEquals(listOf("read","execute","finish","read"),h.calls)
    }
    @Test fun `finish 실패는 원명령을 보존하고 새 준비로 바꾸지 않는다`() {
        val h=Harness();h.pending=h.operation;h.finishFailure=true;h.start();h.host.acknowledge();h.drain()
        assertEquals(h.operation,h.pending);assertEquals(listOf("read","execute","finish"),h.calls)
    }

    @Test fun `명령 준비 중 gate가 닫히면 미저장 명령을 실행 불명으로 오인하지 않는다`() {
        val h=Harness();h.start();h.prepareHook={h.host.setControlsEnabled(false)}
        h.host.onRequest(DeliveryRequest(1,h.frame!!.version,DeliveryCommand.ChooseDestination(null)));h.drain()
        h.host.setControlsEnabled(true);h.drain()
        assertEquals(listOf("read","prepare","read"),h.calls);assertTrue(h.frame!!.screen is DeliveryScreen.Destination)
    }

    @Test fun `발견 실패 뒤 재시도는 이미 표시한 원목록을 저장한다`() {
        val h = Harness()
        val original = h.offers()
        h.start()
        assertTrue(h.shown.isEmpty())
        h.discoveryFailure = true
        h.host.acknowledgeDisplayed(h.frame!!.version)
        h.drain()
        assertTrue(h.frame!!.screen is DeliveryScreen.Failure)
        assertEquals(0, h.discoveryRefreshes)
        h.proposal = null
        h.screen = DeliveryScreen.Destination(null, null, "모의 입력", true)
        h.discoveryFailure = false
        val retry = DeliveryRequest(1, h.frame!!.version, DeliveryCommand.Refresh)
        h.host.onRequest(retry)
        h.drain()
        assertEquals(listOf("read", "displayed", "displayed"), h.calls)
        assertEquals(listOf(original, original), h.shown)
        assertEquals(original, (h.frame!!.screen as DeliveryScreen.Offers).proposal.proposed)
        assertEquals(listOf(retry), h.completed)
        h.host.acknowledgeDisplayed(h.frame!!.version)
        h.drain()
        assertEquals(2, h.shown.size)
        assertEquals(1, h.discoveryRefreshes)
    }

    @Test fun `저장 직후 탁송에서 나가도 같은 세션 조회에 알리고 이전 화면을 복원하지 않는다`() {
        val h = Harness()
        val original = h.offers()
        h.start()
        h.host.acknowledgeDisplayed(h.frame!!.version)
        h.tasks.removeFirst().run()
        h.host.setControlsEnabled(false)
        h.drain()
        assertEquals(1, h.discoveryRefreshes)
        assertFalse(h.enabled)
        assertTrue(h.frame!!.screen is DeliveryScreen.Loading)
        h.proposal = null
        h.screen = DeliveryScreen.Destination(null, null, "모의 입력", true)
        h.host.setControlsEnabled(true)
        h.host.refresh()
        h.drain()
        assertEquals(listOf(original), h.shown)
        assertTrue(h.frame!!.screen is DeliveryScreen.Destination)
    }

    @Test fun `옛 표시 세대나 제한된 화면은 재시도할 발견을 만들지 않는다`() {
        val h = Harness()
        h.offers()
        h.start()
        val current = h.frame!!.version
        h.host.acknowledgeDisplayed(current - 1)
        h.host.setControlsEnabled(false)
        h.host.acknowledgeDisplayed(current)
        h.proposal = null
        h.screen = DeliveryScreen.Destination(null, null, "모의 입력", true)
        h.host.setControlsEnabled(true)
        h.host.refresh()
        h.drain()
        assertEquals(listOf("read", "read"), h.calls)
        assertTrue(h.shown.isEmpty())
        assertEquals(0, h.discoveryRefreshes)
    }

    @Test fun `결과 확인은 완료하고 일시 실패한 원 표시를 보존한다`() {
        val h = Harness()
        val original = h.offers()
        h.pending = h.operation
        val receipt = DeliveryHostReceipt.Abandoned(AbandonCommit(h.operation.requestId, null))
        h.receipt = receipt
        h.start()
        assertEquals(receipt, h.host.state!!.receipt)
        h.discoveryFailure = true
        h.host.acknowledgeDisplayed(h.frame!!.version)
        h.drain()
        h.host.acknowledge()
        h.drain()
        assertEquals(listOf("read", "execute", "displayed", "finish"), h.calls)
        assertNull(h.pending)
        assertTrue(h.frame!!.screen is DeliveryScreen.Failure)
        assertEquals(0, h.discoveryRefreshes)
        h.discoveryFailure = false
        h.proposal = null
        h.screen = DeliveryScreen.Destination(null, null, "모의 입력", true)
        h.host.refresh()
        h.drain()
        assertEquals(listOf("read", "execute", "displayed", "finish", "displayed"), h.calls)
        assertEquals(listOf(original, original), h.shown)
        assertEquals(original, (h.frame!!.screen as DeliveryScreen.Offers).proposal.proposed)
        assertEquals(1, h.discoveryRefreshes)
        assertNull(h.host.state!!.pending)
        assertNull(h.host.state!!.receipt)
    }
    @Test fun permanentDiscoveryConflictDoesNotTrapCommittedReceiptAndFreshRead() {
        val h = Harness()
        h.offers()
        h.pending = h.operation
        h.start()
        h.discoveryError = ContractError.STATE_CONFLICT
        h.discoveryFailure = true
        h.host.acknowledgeDisplayed(h.frame!!.version)
        h.drain()
        assertTrue(h.frame!!.screen is DeliveryScreen.Failure)
        h.proposal = null
        h.screen = DeliveryScreen.Destination(null, null, "fresh", true)
        h.host.acknowledge()
        h.drain()
        assertEquals("An already committed operation must be finishable even when old display evidence is stale", null, h.pending)
        assertTrue(h.calls.contains("finish"))
        assertTrue(h.frame!!.screen is DeliveryScreen.Destination)
        assertEquals(0, h.discoveryRefreshes)
    }

    @Test fun `무효 표시 저장은 실패를 표시하고 다음 요청에서 새 후보를 읽는다`() {
        val h = Harness()
        val original = h.offers()
        h.start()
        h.discoveryError = ContractError.STATE_CONFLICT
        h.discoveryFailure = true
        h.host.acknowledgeDisplayed(h.frame!!.version)
        h.drain()
        assertTrue(h.frame!!.screen is DeliveryScreen.Failure)
        val replacement = original.copy(destination = DestinationKey("new"))
        h.proposal = replacement
        val offer = replacement.list.offers.single() as DeliveryOffer.Purchase
        h.screen = DeliveryScreen.Offers(DeliveryHostMapper.proposal(replacement,
            mapOf(offer.vehicleId to "캐스퍼"), "새 목적지", GameMoney(1000), h.identity.source))
        h.host.onRequest(DeliveryRequest(1, h.frame!!.version, DeliveryCommand.Refresh))
        h.drain()
        assertEquals(listOf("read", "displayed", "read"), h.calls)
        assertEquals(listOf(original), h.shown)
        assertEquals(0, h.discoveryRefreshes)
        assertEquals(replacement, (h.frame!!.screen as DeliveryScreen.Offers).proposal.proposed)
    }

}
