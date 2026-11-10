package io.drivegrowth.app.host.shop

import io.drivegrowth.app.data.*
import io.drivegrowth.app.ui.shop.*
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*
import org.junit.Assert.*
import org.junit.Test

private class ExpansionFakeSource : ShopExpansionHostSource {
    val definition=(parseShopExpansionDefinition(expansionDefinitionFixture) as ContractResult.Success).value
    val profile=ProfileId("same-profile")
    val snapshot=mapShopExpansion(profile,InputSource.SIMULATION,definition,ShopState(ShopProgress(1,99,1),3,emptySet()),
        3,GameMoney(1000),emptyList())
    var saved:HostCheckpointCommand.Expand?=null
    var prepares=0;var executions=0;var finishes=0
    var phase=HostCheckpointPhase.SAVED
    var executeFailure:ContractError?=null
    var prepareFailure:ContractError?=null
    var finishFailure:ContractError?=null
    var readFailure:ContractError?=null
    var applied:Settlement?=null
    override fun read():ContractResult<ShopExpansionSnapshot> = readFailure?.let { ContractResult.Failure(it) } ?: ContractResult.Success(
        if(applied==null)snapshot else mapShopExpansion(profile,InputSource.SIMULATION,definition,
            ShopState(ShopProgress(1,99,2),5,emptySet()),3,GameMoney(500),emptyList()))
    override fun pending()=ContractResult.Success(saved)
    override fun prepare(snapshot:ShopExpansionSnapshot,request:ShopRequest):ContractResult<HostCheckpointCommand.Expand> {
        prepares++;prepareFailure?.let{return ContractResult.Failure(it)};val c=HostCheckpointCommand.Expand(request.requestId,InputSource.SIMULATION,definition.version,
            (request.selection.option.command as ShopCommand.Expand).offer);saved=c;return ContractResult.Success(c)
    }
    override fun execute(command:HostCheckpointCommand.Expand):ContractResult<Settlement> {
        executions++;executeFailure?.let{return ContractResult.Failure(it)}
        phase=HostCheckpointPhase.STARTED
        val result=applied ?: Settlement(SettlementId("original"),Transaction(TransactionId("transaction"),command.requestId,
            TransactionKind.FACILITY,command.source,command.offer.ruleVersion,MoneyChange(MoneyDirection.DEBIT,GameMoney(500))),
            GameMoney(500),null,EpochMillis(123)).also{applied=it}
        return ContractResult.Success(result)
    }
    override fun finish(command:HostCheckpointCommand.Expand):ContractResult<Settlement>{
        finishes++;finishFailure?.let{return ContractResult.Failure(it)};saved=null;return ContractResult.Success(applied!!)
    }
    override fun cancelSaved(command:HostCheckpointCommand.Expand):ContractResult<Unit> {
        if(phase==HostCheckpointPhase.STARTED)return ContractResult.Failure(ContractError.STATE_CONFLICT)
        saved=null;return ContractResult.Success(Unit)
    }
}
private class ExpansionHarness(val source:ExpansionFakeSource=ExpansionFakeSource(), initial:Revision=Revision(0)) {
    val work=ArrayDeque<()->Unit>();val ui=ArrayDeque<()->Unit>()
    val frames=mutableListOf<Pair<ShopScreenState,Boolean>>()
    val completions=mutableListOf<ContractResult<Unit>>();val receipts=mutableListOf<Settlement>()
    val host=ShopExpansionHostController(source,{work.add(it)},{ui.add(it)},{s,a->frames.add(s to a)},
        {_,r->completions.add(r)},{},receipts::add,initial)
    fun drain(){var n=0;while(work.isNotEmpty()||ui.isNotEmpty()){
        check(++n<100);if(work.isNotEmpty())work.removeFirst()();if(ui.isNotEmpty())ui.removeFirst()()
    }}
    fun load(){host.setControlsEnabled(true);assertTrue(host.load());drain()}
    fun request():ShopRequest {val s=frames.last().first as ShopScreenState.Content
        return ShopRequest(RequestId("expand"),ShopSelection(s.revision,s.expansion!!),"attempt")}
}
class ShopExpansionHostControllerTest {
    @Test fun confirmationUsesDurablePreparationThenExistingExecutionAndRefresh() {
        val source=ExpansionFakeSource();val work=ArrayDeque<()->Unit>();val frames=mutableListOf<ShopScreenState>()
        val replies=mutableListOf<ContractResult<Unit>>();val receipts=mutableListOf<Settlement>()
        val host=ShopExpansionHostController(source,{work.add(it)},{it()},{state,_->frames.add(state)},
            {_,result->replies.add(result)},{},receipts::add)
        host.setControlsEnabled(true);assertTrue(host.load());while(work.isNotEmpty())work.removeFirst()()
        val shown=frames.last() as ShopScreenState.Content
        assertEquals(0,source.prepares);assertEquals(0,source.executions)
        val request=ShopRequest(RequestId("expand"),ShopSelection(shown.revision,shown.expansion!!),"attempt")
        assertTrue(host.submit(request));assertFalse(host.submit(request))
        while(work.isNotEmpty())work.removeFirst()()
        assertEquals(1,source.prepares);assertEquals(1,source.executions);assertEquals(1,source.finishes)
        assertEquals(listOf(ContractResult.Success(Unit)),replies);assertEquals(listOf(source.applied),receipts)
        assertTrue(frames.last() is ShopScreenState.Content)
    }
    @Test fun staleSelectionAndRestrictionCannotPrepareOrCharge() {
        val h=ExpansionHarness();assertFalse(h.host.load());assertTrue(h.work.isEmpty());h.load()
        val request=h.request()
        assertFalse(h.host.submit(request.copy(selection=request.selection.copy(revision=Revision(0)))))
        h.host.setControlsEnabled(false);assertFalse(h.host.submit(request))
        assertEquals(0,h.source.prepares);assertNull(h.source.applied);assertFalse(h.frames.last().second)
    }
    @Test fun staleButtonDuringReloadIsCompletedAsRejectedWithoutPreparing() {
        val h=ExpansionHarness();h.load();val request=h.request()
        assertTrue(h.host.load());assertFalse(h.host.submit(request));h.drain()
        assertEquals(listOf(ContractResult.Failure(ContractError.STATE_CONFLICT)),h.completions)
        assertEquals(0,h.source.prepares);assertNull(h.source.applied)
    }
    @Test fun gateLossAfterDurableSavePreservesCommandWithoutExecuting() {
        val h=ExpansionHarness();h.load();assertTrue(h.host.submit(h.request()))
        h.work.removeFirst()();h.host.setControlsEnabled(false);h.ui.removeFirst()()
        assertNotNull(h.source.saved);assertEquals(0,h.source.executions)
        h.host.setControlsEnabled(true);assertTrue(h.host.load());h.drain()
        assertEquals(1,h.source.prepares);assertEquals(1,h.source.executions);assertNull(h.source.saved)
    }
    @Test fun preparationFailureRecoversInputWithoutInventingSuccess() {
        val h=ExpansionHarness();h.load();h.source.prepareFailure=ContractError.INSUFFICIENT_FUNDS
        assertTrue(h.host.submit(h.request()));h.drain()
        assertEquals(listOf(ContractResult.Failure(ContractError.INSUFFICIENT_FUNDS)),h.completions)
        assertTrue(h.frames.last().first is ShopScreenState.Failure);assertNull(h.source.applied);assertNull(h.source.saved)
        h.source.prepareFailure=null;assertTrue(h.host.load());h.drain();assertTrue(h.host.submit(h.request()));h.drain()
        assertEquals(GameMoney(500),h.source.applied!!.balance)
    }
    @Test fun normalRejectionRequiresExplicitSavedCancellation() {
        val h=ExpansionHarness();h.load();h.source.executeFailure=ContractError.STATE_CONFLICT
        assertTrue(h.host.submit(h.request()));h.drain()
        assertNotNull(h.source.saved);assertNull(h.source.applied);assertEquals(0,h.source.finishes)
        assertTrue(h.host.cancelSaved());h.drain();assertNull(h.source.saved)
        assertTrue(h.frames.last().first is ShopScreenState.Content)
    }
    @Test fun startedUnknownIsNotAutomaticallyCancelled() {
        val h=ExpansionHarness();h.load();val r=h.request()
        h.source.prepare(h.source.snapshot,r);h.source.phase=HostCheckpointPhase.STARTED
        h.source.executeFailure=ContractError.STORAGE_FAILURE
        assertTrue(h.host.load());h.drain();val original=h.source.saved
        assertTrue(h.host.cancelSaved());h.drain()
        assertEquals(original,h.source.saved);assertNull(h.source.applied)
        assertTrue(h.frames.last().first is ShopScreenState.Failure)
    }
    @Test fun responseLossAndRecreationReuseOriginalCommandAndResult() {
        val h=ExpansionHarness();h.load();assertTrue(h.host.submit(h.request()))
        h.work.removeFirst()();h.ui.removeFirst()();h.work.removeFirst()()
        val original=h.source.applied!!;h.host.release();h.ui.removeFirst()()
        assertNotNull(h.source.saved);assertEquals(0,h.source.finishes)
        h.source.readFailure=ContractError.DATA_UNAVAILABLE
        val recreated=ExpansionHarness(h.source,Revision(20));recreated.load()
        assertEquals(listOf(original),recreated.receipts);assertNull(h.source.saved)
        assertEquals(1,h.source.prepares);assertEquals(2,h.source.executions)
        assertTrue(recreated.frames.last().first is ShopScreenState.Failure)
    }
    @Test fun finishFailurePreservesOriginalResultForRetry() {
        val h=ExpansionHarness();h.load();h.source.finishFailure=ContractError.STORAGE_FAILURE
        assertTrue(h.host.submit(h.request()));h.drain();val original=h.source.applied!!
        assertNotNull(h.source.saved);h.source.finishFailure=null
        assertTrue(h.host.load());h.drain()
        assertEquals(listOf(original,original),h.receipts);assertNull(h.source.saved)
        assertEquals(1,h.source.prepares)
    }
    @Test fun previewAndCancelDoNotPrepareOrExecute() {
        val h=ExpansionHarness();h.load()
        val interaction=ShopInteraction({})
        interaction.render(h.frames.last().first,true)
        val selection=h.request().selection
        interaction.select(selection);assertEquals(selection,interaction.selection);assertTrue(interaction.cancel())
        assertEquals(0,h.source.prepares);assertEquals(0,h.source.executions);assertNull(h.source.applied)
    }
    @Test fun synchronousCompletionCanReloadWithoutOlderFailureOverwritingLoading() {
        val source=ExpansionFakeSource();val work=ArrayDeque<()->Unit>();val frames=mutableListOf<ShopScreenState>()
        lateinit var host:ShopExpansionHostController
        host=ShopExpansionHostController(source,{work.add(it)},{it()},{s,_->frames.add(s)},
            {_,_->host.load()},{})
        host.setControlsEnabled(true);host.load();while(work.isNotEmpty())work.removeFirst()()
        val shown=frames.last() as ShopScreenState.Content
        source.prepareFailure=ContractError.STATE_CONFLICT
        host.submit(ShopRequest(RequestId("reentrant"),ShopSelection(shown.revision,shown.expansion!!),"a"))
        work.removeFirst()()
        assertTrue(frames.last() is ShopScreenState.Loading)
        while(work.isNotEmpty())work.removeFirst()()
        assertTrue(frames.last() is ShopScreenState.Content)
    }
}
