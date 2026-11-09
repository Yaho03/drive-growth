package io.drivegrowth.app.host.gacha
import io.drivegrowth.app.data.AssetGameContent
import io.drivegrowth.app.ui.gacha.*
import io.drivegrowth.core.content.ContentLoad
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class GachaHostReviewIndependentTest {
 private inner class Harness(val queued:Boolean=false) {
  val tasks=ArrayDeque<()->Unit>();val replies=ArrayDeque<()->Unit>()
  fun drain(){while(tasks.isNotEmpty()||replies.isNotEmpty()){if(tasks.isNotEmpty())tasks.removeFirst()();if(replies.isNotEmpty())replies.removeFirst()()}}
  val content=(AssetGameContent {File("src/main/assets/$it").readText()}.load() as ContentLoad.Loaded).snapshot
  val authority=GachaAuthority(InputSource.SIMULATION,content.catalog.contentVersion,content.ruleVersion,1,3,GameMoney(500),content.gacha.pity,content.gacha.guaranteedFrom,content.unlockedAt(1).map{it.vehicleId}.toSet(),Rarity.entries.filter{content.gacha.weights.getValue(it)>0}.toSet())
  var prepares=0;var failCheckpoint=false;val commits=mutableListOf<GachaCommand>();val saved=mutableListOf<GachaHostState>()
  private val store=object:GachaChangeStore {
   override fun readGachaState()=ContractResult.Success(GachaState(authority,GachaProgress(Revision(0),0,1)))
   override fun listOwnedVehicles()=ContractResult.Success(emptyList<OwnedVehicle>())
   override fun commitGameChange(change:GameChange):ContractResult<Settlement> = error("host must not commit legacy")
   override fun commitGacha(command:GachaCommand):ContractResult<GachaSettlement> {
    commits+=command
    val money=if(command.ticket==GachaTicketUse.PURCHASED) MoneyChange(MoneyDirection.DEBIT,command.award.vehicle.acquisitionCost) else null
    return ContractResult.Success(GachaSettlement(command,Settlement(SettlementId("settled"),Transaction(TransactionId("t"),command.requestId,TransactionKind.GARAGE,command.source,command.ruleVersion,money),GameMoney(500),command.award.vehicle,EpochMillis(1)),GachaProgress(Revision(1),command.award.nextCounter,1)))
   }
  }
  private val actual=GachaStoreHostSource(content,store,{ContractResult.Success(GameMoney(1000))},GachaOrigin.FIXTURE)
  private val source=object:GachaHostSource by actual {override fun prepare(input:GachaDrawInput):ContractResult<GachaCommand>{prepares++;return actual.prepare(input)}}
  lateinit var host:GachaHostController
  val ui=GachaInteraction({event->when(event){is GachaEvent.Draw->host.draw(event.request);is GachaEvent.Reload->host.load(event.token);is GachaEvent.OpenGarage->error("not requested")}})
  init {host=GachaHostController(source,{if(queued)tasks.addLast(it)else it()},{if(queued)replies.addLast(it)else it()},{state->saved+=state;if(failCheckpoint&&state.pending?.command!=null)ContractResult.Failure(ContractError.STORAGE_FAILURE)else ContractResult.Success(Unit)},{frame,enabled->ui.render(frame,enabled)},{request,result->ui.complete(request,result)},{token->ui.completeReload(token)})}
  fun offerAndConfirm(){host.setControlsEnabled(true);assertTrue(host.load());assertTrue(ui.select("paid"));assertTrue(ui.confirm())}
 }
 @Test fun actualUiDrawUsesPreparedWholeCommandAndStoredReceiptOnly() {
  val h=Harness();h.offerAndConfirm()
  assertEquals(1,h.prepares);assertEquals(1,h.commits.size)
  val shown=h.ui.frame!!.screen as GachaScreen.Result
  assertEquals(h.commits.single().award.vehicle,shown.value.settlement.changedVehicle)
  assertEquals(GameMoney(500),shown.value.settlement.balance)
  assertEquals(h.commits.single(),h.saved.last{it.pending?.command!=null}.pending!!.command)
  assertFalse(h.ui.confirm());assertEquals(1,h.commits.size)
 }
 @Test fun failedWholeCommandCheckpointThenUiReloadDoesNotPrepareAgain() {
  val h=Harness();h.failCheckpoint=true;h.offerAndConfirm()
  assertEquals(1,h.prepares);assertTrue(h.commits.isEmpty());val original=h.host.snapshot().pending!!.command!!
  h.failCheckpoint=false;assertTrue(h.ui.reload())
  assertEquals(1,h.prepares);assertEquals(listOf(original),h.commits)
  assertTrue(h.ui.frame!!.screen is GachaScreen.Result)
 }
 @Test fun queuedCallerMutationMustNotChangeTheCheckpointedPrepareInput() {
  val h=Harness(queued=true)
  val initial=mapGachaOffers(h.content,GachaState(h.authority,GachaProgress(Revision(0),0,1)),GameMoney(1000),0,RequestId("paid"),RequestId("free"),GachaOrigin.FIXTURE)
  val offers=(initial as ContractResult.Success).value
  val candidates=ArrayList(offers.candidates!!)
  val input=offers.copy(candidates=candidates)
  val request=GachaRequest(1,GachaDrawInput(input,input.options[0]),"caller")
  h.host.setControlsEnabled(true);assertTrue(h.host.draw(request))
  val recorded=h.host.snapshot().pending!!.request
  assertEquals(8,recorded.input.offers.candidates!!.size)
  candidates.clear();h.drain()
  assertEquals("입구에서 보관한 불변 입력으로 prepare해야 한다",1,h.commits.size)
  assertEquals(recorded.input,h.host.snapshot().pending!!.request.input)
 }

}
