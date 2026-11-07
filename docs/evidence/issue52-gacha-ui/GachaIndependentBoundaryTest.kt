package io.drivegrowth.app.ui.gacha
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
class GachaIndependentBoundaryTest {
 private fun failed(): Pair<GachaInteraction,GachaRequest> {
  val ui=GachaInteraction({});ui.render(GachaPreviewStates.ready(),true)
  assertTrue(ui.select("paid"));assertTrue(ui.confirm());val r=ui.uiState.pending!!
  ui.complete(r,ContractResult.Failure(ContractError.STORAGE_FAILURE));return ui to r
 }
 @Test fun `미해소 경제키를 새 옵션 ID와 변경 원문으로 재제출하지 않는다`() {
  val (ui,r)=failed();val v=(GachaPreviewStates.ready().screen as GachaScreen.Offers).value
  val changed=v.copy(options=listOf(r.input.option.copy(id="paid-v2",moneyCost=GameMoney(999))))
  ui.render(GachaFrame(2,GachaScreen.Offers(changed)),true)
  assertFalse("미해소 같은 경제키의 변경 원문을 차단해야 한다",ui.select("paid-v2"))
 }
 @Test fun `미해소 경제키를 다른 기존 옵션에 이동하여 재사용하지 않는다`() {
  val (ui,r)=failed();val v=(GachaPreviewStates.ready().screen as GachaScreen.Offers).value
  val changed=v.copy(options=v.options.map { if(it.id=="paid") it.copy(requestId=RequestId("new-paid")) else it.copy(requestId=r.input.option.requestId) })
  ui.render(GachaFrame(2,GachaScreen.Offers(changed)),true)
  assertFalse("실패 paid키를 free 다른원문으로 재사용 차단",ui.select("free"))
 }
 @Test fun `최초복원후Loading도낮은세대로표시를되돌리지않는다`() {
  val ui=GachaInteraction({});ui.render(GachaFrame(8,GachaScreen.Loading),false)
  val restored=GachaInteraction({},ui.uiState)
  assertFalse(restored.render(GachaPreviewStates.ready(),true));assertFalse(restored.controlsEnabled)
 }
 @Test fun `옛 차고이동 완료가 같은 정산의 새 이동 잠금을 풀지 않는다`() {
  val events=mutableListOf<GachaEvent>();val ui=GachaInteraction(events::add)
  ui.render(GachaPreviewStates.result(),true);assertTrue(ui.openGarage())
  val id=(events.single() as GachaEvent.OpenGarage).request
  ui.completeGarageNavigation(id);assertTrue(ui.openGarage())
  ui.completeGarageNavigation(id)
  assertFalse("첫 이동의 늦은 중복 응답이 두번째 이동을 해제하면 안됨",ui.openGarage())
 }
 @Test fun `현재차고token은생성자복원뒤에도이전응답으로풀리지않는다`() {
  val events=mutableListOf<GachaEvent>();val ui=GachaInteraction(events::add)
  ui.render(GachaPreviewStates.result(),true);assertTrue(ui.openGarage())
  val first=(events.last() as GachaEvent.OpenGarage).request
  ui.completeGarageNavigation(first);assertTrue(ui.openGarage())
  val current=(events.last() as GachaEvent.OpenGarage).request
  assertNotEquals(first.token,current.token)
  val restored=GachaInteraction(events::add,ui.uiState);restored.render(GachaPreviewStates.result(),true)
  restored.completeGarageNavigation(first);assertEquals(current,restored.uiState.garagePending);assertFalse(restored.openGarage())
  restored.completeGarageNavigation(current.copy(ownedVehicleId=OwnedVehicleId("other")));assertFalse(restored.openGarage())
  restored.completeGarageNavigation(current);assertTrue(restored.openGarage())
 }
 @Test fun `Pending과성공대기키는실패해소로버리지않는다`() {
  val ui=GachaInteraction({});ui.render(GachaPreviewStates.ready(),true);assertTrue(ui.select("paid"));assertTrue(ui.confirm())
  val r=ui.uiState.pending!!;val key=r.input.option.requestId!!
  assertFalse(ui.resolveFailedRequest(key));assertEquals(r,ui.uiState.pending)
  ui.complete(r,ContractResult.Success(Unit));assertFalse(ui.resolveFailedRequest(key));assertTrue(ui.awaitingConfirmation);assertFalse(ui.select("free"))
 }
 @Test fun `명시적확정실패해소후에만재바인딩을허용한다`() {
  val (ui,r)=failed();val v=(GachaPreviewStates.ready().screen as GachaScreen.Offers).value
  ui.render(GachaFrame(2,GachaScreen.Offers(v.copy(options=listOf(r.input.option.copy(id="paid-v2",moneyCost=GameMoney(999)))))),true)
  assertFalse(ui.select("paid-v2"));assertTrue(ui.resolveFailedRequest(r.input.option.requestId!!))
  assertTrue(ui.select("paid-v2"));assertTrue(ui.confirm());assertEquals(GameMoney(999),ui.uiState.pending!!.input.option.moneyCost)
 }
}
