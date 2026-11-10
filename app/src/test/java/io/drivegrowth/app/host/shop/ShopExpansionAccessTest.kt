package io.drivegrowth.app.host.shop

import io.drivegrowth.app.data.*
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test

class ShopExpansionAccessTest {
    private class Source:ShopExpansionHostSource {
        var pendingReads=0
        override fun pending():ContractResult<HostCheckpointCommand.Expand?> {pendingReads++;return ContractResult.Failure(ContractError.STATE_CONFLICT)}
        override fun read():ContractResult<ShopExpansionSnapshot> = error("other kind cannot read")
        override fun prepare(snapshot:ShopExpansionSnapshot,request:ShopRequest):ContractResult<HostCheckpointCommand.Expand> = error("never prepare")
        override fun execute(command:HostCheckpointCommand.Expand):ContractResult<Settlement> = error("never execute")
        override fun finish(command:HostCheckpointCommand.Expand):ContractResult<Settlement> = error("never finish")
        override fun cancelSaved(command:HostCheckpointCommand.Expand):ContractResult<Unit> = error("never cancel")
    }
    @Test fun firstGatePermissionStartsExactlyOneReadAndRepeatedPermissionDoesNotRepeat(){
        val source=Source();val work=ArrayDeque<()->Unit>()
        val host=ShopExpansionHostController(source,{work.addLast(it)},{it()},{_,_->},{_,_->},{})
        val access=ShopExpansionAccess(host);access.setControlsEnabled(false);assertTrue(work.isEmpty())
        access.setControlsEnabled(true);access.setControlsEnabled(true);assertEquals(1,work.size)
        work.removeFirst()();assertEquals(1,source.pendingReads)
        access.setControlsEnabled(false);access.setControlsEnabled(true);assertEquals(1,work.size)
        work.removeFirst()();assertEquals(2,source.pendingReads)
    }
    @Test fun gateRegainDuringInFlightReadDoesNotStartSecondWork(){
        val source=Source();val work=ArrayDeque<()->Unit>()
        val host=ShopExpansionHostController(source,{work.addLast(it)},{it()},{_,_->},{_,_->},{})
        val access=ShopExpansionAccess(host);access.setControlsEnabled(true);access.setControlsEnabled(false);access.setControlsEnabled(true)
        assertEquals(1,work.size);work.removeFirst()();assertEquals(1,source.pendingReads)
    }
    @Test fun repeatedPermissionDoesNotRenderAgainAndFirstFalseIsForwarded(){
        val source=Source();val work=ArrayDeque<()->Unit>();val frames=mutableListOf<Pair<ShopScreenState,Boolean>>()
        val host=ShopExpansionHostController(source,{work.addLast(it)},{it()},{s,e->frames.add(s to e)},{_,_->},{})
        val access=ShopExpansionAccess(host)
        access.setControlsEnabled(false);assertEquals(1,frames.size);assertFalse(frames.last().second)
        repeat(5){access.setControlsEnabled(false)};assertEquals("반복 false는 render하지 않는다",1,frames.size)
        access.setControlsEnabled(true);val allowedFrames=frames.size;assertEquals(1,work.size)
        repeat(5){access.setControlsEnabled(true)};assertEquals("반복 true는 render하지 않는다",allowedFrames,frames.size);assertEquals(1,work.size)
        work.removeFirst()();assertEquals(1,source.pendingReads)
        val terminalFrames=frames.size;access.setControlsEnabled(true);assertEquals(terminalFrames,frames.size)
    }
    @Test fun restrictionWhileQueuedRendersOnceAndReallowStartsOnlyOneRead(){
        val source=Source();val work=ArrayDeque<()->Unit>();val frames=mutableListOf<Pair<ShopScreenState,Boolean>>()
        val host=ShopExpansionHostController(source,{work.addLast(it)},{it()},{s,e->frames.add(s to e)},{_,_->},{})
        val access=ShopExpansionAccess(host);access.setControlsEnabled(false);access.setControlsEnabled(true)
        assertEquals(1,work.size);access.setControlsEnabled(false);assertFalse(frames.last().second)
        val restrictedFrames=frames.size;repeat(5){access.setControlsEnabled(false)};assertEquals(restrictedFrames,frames.size)
        work.removeFirst()();assertEquals(0,source.pendingReads);assertFalse(frames.last().second)
        access.setControlsEnabled(true);val gainedFrames=frames.size;repeat(5){access.setControlsEnabled(true)}
        assertEquals(gainedFrames,frames.size);assertEquals(1,work.size);work.removeFirst()();assertEquals(1,source.pendingReads)
    }

}
