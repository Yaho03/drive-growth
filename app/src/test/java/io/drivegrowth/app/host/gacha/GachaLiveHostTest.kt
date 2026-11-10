package io.drivegrowth.app.host.gacha

import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/** 메모리 원장은 실제 core 확정 경로다. journal은 SQLite를 흉내낸 시험 입력이며 디스크 검증이 아니다. */
class GachaLiveHostTest {
    private class Harness(tickets:Long=1, money:Long=1000, capacity:Int=3, counter:Int=0) {
        val content=(AssetGameContent { File("src/main/assets/$it").readText() }.load() as ContentLoad.Loaded).snapshot
        val authority=GachaAuthority(InputSource.SIMULATION,content.catalog.contentVersion,content.ruleVersion,1,capacity,GameMoney(500),
            content.gacha.pity,content.gacha.guaranteedFrom,content.unlockedAt(1).map{it.vehicleId}.toSet(),Rarity.entries.toSet())
        val store=InMemoryGameChangeStore(emptyList(),GameMoney(money),gachaState=GachaState(authority,GachaProgress(Revision(0),counter,tickets)))
        val tasks=ArrayDeque<Runnable>();val replies=ArrayDeque<Runnable>()
        var onWorker=false
        var balance=GameMoney(money)
        var pending:GachaCommand?=null
        var loseReply=false;var finishFailure=false;var loadFailure=false
        var started=false
        var prepares=0;var executes=0;var finishes=0;var changes=0
        val commands=mutableListOf<GachaCommand>()
        private val delegate=GachaStoreHostSource(content,store,{ContractResult.Success(balance)},GachaOrigin.FIXTURE)
        private val journal=object:GachaCommandJournal {
            private fun worker(){assertTrue("DB는 worker에서만",onWorker)}
            override fun cancelUnstarted(command:GachaCommand):ContractResult<Unit>{worker();if(started)return ContractResult.Failure(ContractError.STATE_CONFLICT);assertEquals(pending,command);pending=null;return ContractResult.Success(Unit)}
            override fun pending():ContractResult<GachaCommand?> {worker();return if(loadFailure)ContractResult.Failure(ContractError.STORAGE_FAILURE) else ContractResult.Success(pending)}
            override fun save(command:GachaCommand):ContractResult<Unit>{worker();if(pending!=null && pending!=command)return ContractResult.Failure(ContractError.STATE_CONFLICT);pending=command;return ContractResult.Success(Unit)}
            override fun execute(command:GachaCommand):ContractResult<GachaSettlement>{worker();executes++;commands+=command;val result=store.commitGacha(command)
                if(result is ContractResult.Success){balance=result.value.settlement.balance;started=true}
                return if(loseReply)ContractResult.Failure(ContractError.STORAGE_FAILURE) else result}
            override fun finish(command:GachaCommand):ContractResult<GachaSettlement>{worker();finishes++;if(finishFailure)return ContractResult.Failure(ContractError.STORAGE_FAILURE)
                assertEquals(pending,command);val result=store.commitGacha(command);if(result is ContractResult.Success)pending=null;return result}
        }
        private val source=CheckpointGachaSource(object:GachaHostSource by delegate {
            override fun prepare(input:GachaDrawInput):ContractResult<GachaCommand>{assertTrue(onWorker);prepares++;return delegate.prepare(input)}
        },journal)
        var host=newHost()
        var ui: GachaInteraction = GachaInteraction({ event -> when(event){is GachaEvent.Draw->{host.remember(uiState());host.draw(event.request)};is GachaEvent.Reload->{host.remember(uiState());host.refresh(event.token)};else->Unit}})
        private fun uiState()=ui.uiState
        fun newHost()=GachaLiveHost(source,Executor{tasks.addLast(it)},Executor{replies.addLast(it)}){changes++}
        fun attach(){host.attach({f,e->ui.render(f,e);host.remember(ui.uiState)}, {r,v->ui.complete(r,v);host.remember(ui.uiState)},
            {ui.completeReload(it);host.remember(ui.uiState)},{ui.resolveFailedRequest(it);host.remember(ui.uiState)})}
        fun start(){attach();host.setControlsEnabled(true);drain()}
        fun drain(){while(tasks.isNotEmpty()||replies.isNotEmpty()){if(tasks.isNotEmpty()){onWorker=true;tasks.removeFirst().run();onWorker=false};if(replies.isNotEmpty())replies.removeFirst().run()}}
        fun draw(kind:GachaOptionKind){val offers=(ui.frame!!.screen as GachaScreen.Offers).value;assertTrue(ui.select(offers.options.single{it.kind==kind}.id));assertTrue(ui.confirm());drain()}
    }
    @Test fun `무료와 유료 지급은 실제 정산 개체와 비용을 표시하고 finish는 결과 뒤다`() {
        for(kind in GachaOptionKind.entries){val h=Harness();h.start();h.draw(kind)
            val result=(h.ui.frame!!.screen as GachaScreen.Result).value.settlement
            assertEquals(if(kind==GachaOptionKind.FREE)GameMoney(0) else GameMoney(500),result.changedVehicle!!.acquisitionCost)
            assertEquals(result.changedVehicle,(h.store.listOwnedVehicles() as ContractResult.Success).value.single())
            assertNotNull(h.pending);assertEquals(0,h.finishes);assertEquals(1,h.prepares);assertEquals(1,h.executes)
            h.host.acknowledge{assertTrue(it is ContractResult.Success)};h.drain();assertNull(h.pending)
            assertEquals(1,h.changes)
        }
    }
    @Test fun `취소는 명령 준비와 지급을 실행하지 않는다`() {
        val h=Harness();h.start();assertTrue(h.ui.select("paid"));assertTrue(h.ui.cancel());h.drain()
        assertEquals(0,h.prepares);assertEquals(0,h.executes);assertNull(h.pending)
    }
    @Test fun `응답 유실과 재실행은 새 조회 추첨 없이 같은 명령 결과를 복원한다`() {
        val h=Harness();h.start();h.loseReply=true;h.draw(GachaOptionKind.PAID)
        assertTrue(h.ui.frame!!.screen is GachaScreen.Failure);val original=h.pending!!
        h.host.release();h.loseReply=false;h.host=h.newHost();h.ui=GachaInteraction({});h.start()
        assertEquals(original,h.commands.last());assertEquals(1,h.prepares)
        assertEquals(original.award.vehicle,(h.ui.frame!!.screen as GachaScreen.Result).value.settlement.changedVehicle)
        assertEquals(1,(h.store.listOwnedVehicles() as ContractResult.Success).value.size);assertEquals(GameMoney(500),h.balance)
    }
    @Test fun `finish 실패는 원명령을 남기며 재조회도 원래 결과다`() {
        val h=Harness();h.start();h.draw(GachaOptionKind.FREE);val original=h.pending!!;h.finishFailure=true
        h.host.acknowledge{assertTrue(it is ContractResult.Failure)};h.drain();assertEquals(original,h.pending)
        h.host.refresh();h.drain();assertTrue(h.ui.frame!!.screen is GachaScreen.Result);assertEquals(1,h.prepares)
        h.finishFailure=false;h.host.acknowledge{assertTrue(it is ContractResult.Success)};h.drain();assertNull(h.pending)
    }
    @Test fun `만차 잔액 무료권 부족은 확인을 허용하지 않고 원장을 변경하지 않는다`() {
        for(h in listOf(Harness(capacity=0),Harness(money=499),Harness(tickets=0))){h.start()
            val offers=(h.ui.frame!!.screen as GachaScreen.Offers).value
            val option=if(offers.space!!.capacity==0)"paid" else if(offers.balance!!.units==499L)"paid" else "free"
            assertFalse(h.ui.select(option));assertEquals(0,h.prepares);assertNull(h.pending)
        }
    }
    @Test fun `보장 회차도 실제 지급으로 카운터를 초기화한다`() {
        val h=Harness(counter=29);h.start();h.draw(GachaOptionKind.PAID)
        val car=(h.ui.frame!!.screen as GachaScreen.Result).value.settlement.changedVehicle!!
        assertTrue(car.rarity>=Rarity.RARE);assertEquals(0,(h.store.readGachaState() as ContractResult.Success).value.progress.drawsSinceGuarantee)
    }
    @Test fun `확인 후 gate 제한은 worker에서 추첨 지급을 막는다`() {
        val h=Harness();h.start();assertTrue(h.ui.select("paid"));assertTrue(h.ui.confirm());h.host.setControlsEnabled(false);h.drain()
        assertEquals(0,h.prepares);assertEquals(0,h.executes);assertFalse(h.ui.controlsEnabled)
    }
    @Test fun `detach 뒤 늦은 결과는 새 View의 gate를 풀지 않는다`() {
        val h=Harness();h.start();assertTrue(h.ui.select("paid"));assertTrue(h.ui.confirm())
        h.onWorker=true;h.tasks.removeFirst().run();h.onWorker=false;h.replies.removeFirst().run()
        h.onWorker=true;h.tasks.removeFirst().run();h.onWorker=false
        h.host.detach();h.ui=GachaInteraction({},h.host.uiState);h.attach();h.drain()
        assertFalse(h.ui.controlsEnabled);assertTrue(h.ui.frame!!.screen is GachaScreen.Result)
    }
    @Test fun `checkpoint 조회 실패에서 새 후보를 열거나 명령을 지우지 않는다`() {
        val h=Harness();h.loadFailure=true;h.start();assertTrue(h.ui.frame!!.screen is GachaScreen.Failure)
        assertEquals(0,h.prepares);assertEquals(0,h.executes)
    }
    @Test fun `명시 취소도 STARTED 불명 결과를 지우거나 새 추첨하지 않는다`() {
        val h=Harness();h.start();h.loseReply=true;h.draw(GachaOptionKind.PAID);val original=h.pending!!
        h.host.cancelUnstarted();h.drain();assertEquals(original,h.pending);assertEquals(1,h.prepares);assertTrue(h.ui.frame!!.screen is GachaScreen.Failure)
    }

    @Test fun `알고 있는 원명령의 checkpoint가 없어지면 새 후보나 취소로 보정하지 않는다`() {
        val h=Harness();h.start();h.loseReply=true;h.draw(GachaOptionKind.PAID)
        h.pending=null;h.loseReply=false;h.host.refresh();h.drain()
        assertTrue(h.ui.frame!!.screen is GachaScreen.Failure);assertEquals(1,h.prepares)
        h.host.cancelUnstarted();h.drain();assertTrue(h.ui.frame!!.screen is GachaScreen.Failure);assertEquals(1,h.prepares)
    }

    @Test fun `확정 거절된 SAVED를 명시 취소하면 잠금을 해소하고 새 조건만 조회한다`() {
        val h=Harness();h.start();h.store.nextCommitFailure=ContractError.STORAGE_FAILURE;h.draw(GachaOptionKind.PAID)
        assertNotNull(h.pending);assertFalse(h.started);h.host.cancelUnstarted();h.drain()
        assertNull(h.pending);assertTrue(h.ui.frame!!.screen is GachaScreen.Offers);assertEquals(1,h.prepares)
        assertEquals(GameMoney(1000),h.balance);assertTrue(h.ui.select("paid"))
    }
    @Test fun `결과 확인 뒤 다시 열어도 이전 화면에 갇히지 않고 새 확인을 받는다`() {
        val h=Harness();h.start();h.draw(GachaOptionKind.PAID)
        h.host.acknowledge{assertTrue(it is ContractResult.Success)};h.drain();h.host.refresh();h.drain()
        assertTrue(h.ui.frame!!.screen is GachaScreen.Offers);assertNull(h.pending);assertEquals(1,h.prepares)
        h.draw(GachaOptionKind.PAID);assertEquals(2,h.prepares);assertEquals(GameMoney(0),h.balance)
    }
    @Test fun `이전 View의 차고 이동 잠금은 detach 후 새 View에 남기지 않는다`() {
        val h=Harness();h.start();h.draw(GachaOptionKind.PAID);assertTrue(h.ui.openGarage());h.host.remember(h.ui.uiState)
        h.host.detach();assertNull(h.host.uiState.garagePending)
    }

    @Test fun `저장 실패 안내는 내부 코드를 노출하지 않고 원래 요청 확인을 안내한다`() {
        val h=Harness();h.loadFailure=true;h.start()
        val failure=h.ui.frame!!.screen as GachaScreen.Failure
        assertEquals(ContractError.STORAGE_FAILURE,failure.error)
        assertEquals("지급 결과를 확인하지 못했어요. 보관한 원래 요청으로 다시 확인해 주세요. 다른 관리 요청이 보관 중이면 차량 관리에서 확인해 주세요.",failure.message)
        assertFalse(ContractError.entries.any{it.name in failure.message});assertTrue(failure.retryAllowed)
        assertEquals(0,h.executes);assertEquals(0,h.prepares)
    }

}
