package io.drivegrowth.app.host.delivery

import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.delivery.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class DeliveryHostControllerTest {
    private class Harness {
        val tasks=ArrayDeque<Runnable>();val replies=ArrayDeque<Runnable>();val calls=mutableListOf<String>()
        val identity=GameIdentity(ProfileId("profile"),InputSource.SIMULATION)
        val operation=DeliveryHostOperation.Abandon(DeliveryChangeRules(RuleVersion("r"),10),RequestId("active"))
        var pending:DeliveryHostOperation?=null;var fail=false;var cancelFailure=false;var finishFailure=false
        var prepareHook:(()->Unit)?=null;var readHook:(()->Unit)?=null;var suppliedIdentity=identity
        var screen:DeliveryScreen=DeliveryScreen.Destination(null,null,"모의 입력",true)
        val source=object:DeliveryHostSource {
            override fun read():ContractResult<DeliveryHostRead>{calls+="read";val hook=readHook;readHook=null;hook?.invoke();return ContractResult.Success(DeliveryHostRead(suppliedIdentity,screen,pending=pending))}
            override fun prepare(identity:GameIdentity,command:DeliveryCommand):ContractResult<DeliveryHostPrepared>{calls+="prepare";prepareHook?.invoke();return ContractResult.Success(DeliveryHostPrepared(screen,operation))}
            override fun execute(identity:GameIdentity,operation:DeliveryHostOperation):ContractResult<DeliveryHostRead>{calls+="execute";pending=operation;return if(fail)ContractResult.Failure(ContractError.STORAGE_FAILURE)else ContractResult.Success(DeliveryHostRead(identity,screen,pending=operation))}
            override fun cancelUnstarted(identity:GameIdentity,operation:DeliveryHostOperation):ContractResult<Unit>{calls+="cancel";if(cancelFailure)return ContractResult.Failure(ContractError.STATE_CONFLICT);pending=null;return ContractResult.Success(Unit)}
            override fun finish(identity:GameIdentity,operation:DeliveryHostOperation):ContractResult<Unit>{calls+="finish";if(finishFailure)return ContractResult.Failure(ContractError.STORAGE_FAILURE);pending=null;return ContractResult.Success(Unit)}
            override fun displayed(identity:GameIdentity,proposed:DeliveryProposed)=ContractResult.Success(Unit)
        }
        var frame:DeliveryFrame?=null;var enabled=false;val completed=mutableListOf<DeliveryRequest>()
        val host=DeliveryHostController(source,Executor{tasks.addLast(it)},Executor{replies.addLast(it)})
        init{host.attach({f,e->frame=f;enabled=e},{r,f->completed+=r;frame=f})}
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
}
