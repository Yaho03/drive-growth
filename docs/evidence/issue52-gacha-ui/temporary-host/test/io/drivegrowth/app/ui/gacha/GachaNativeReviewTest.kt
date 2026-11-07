package io.drivegrowth.app.ui.gacha
import android.test.ActivityInstrumentationTestCase2
import android.view.*
import android.widget.*
import android.graphics.Bitmap
import android.os.SystemClock
import io.drivegrowth.core.contract.*
import java.io.File
@Suppress("DEPRECATION")
class GachaNativeReviewTest:ActivityInstrumentationTestCase2<GachaReviewHostActivity>(GachaReviewHostActivity::class.java) {
 private fun ui(f:()->Unit){instrumentation.runOnMainSync(f);instrumentation.waitForIdleSync()}
 private fun nodes(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {nodes(v.getChildAt(it))}else emptyList()
 private fun tap(tag:String){val h=activity;instrumentation.waitForIdleSync();SystemClock.sleep(120);lateinit var b:Button
  ui {b=h.view.findViewWithTag(tag);b.requestRectangleOnScreen(android.graphics.Rect(0,0,b.width,b.height),true)}
  instrumentation.waitForIdleSync();SystemClock.sleep(160);val p=IntArray(2);ui{b.getLocationOnScreen(p); val visible=android.graphics.Rect();check(b.getGlobalVisibleRect(visible)){"button not visible $tag at ${p.toList()}"};android.util.Log.i("Gacha52ReviewA","tap $tag point=${p.toList()} size=${b.width}x${b.height} visible=$visible")}
  val x=p[0]+b.width/2f;val y=p[1]+b.height/2f;val now=SystemClock.uptimeMillis()
  instrumentation.sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0));instrumentation.sendPointerSync(MotionEvent.obtain(now,now+60,MotionEvent.ACTION_UP,x,y,0));instrumentation.waitForIdleSync()
 }
 private fun shot(name:String){val bmp=instrumentation.uiAutomation.takeScreenshot();File(activity.filesDir,"$name.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}
 fun testNativeFailureAlternationRestrictionConstructorRestoreAndLateCallback(){
  val h=activity
  tap("gacha:option:paid");tap("gacha:confirm");assertEquals(1,h.events.size)
  val a=(h.events.last() as GachaEvent.Draw).request
  ui{h.view.complete(a,ContractResult.Failure(ContractError.STORAGE_FAILURE))}
  tap("gacha:option:free");tap("gacha:confirm");assertEquals(2,h.events.size)
  val b=(h.events.last() as GachaEvent.Draw).request
  ui{h.view.complete(b,ContractResult.Failure(ContractError.STORAGE_FAILURE));h.replace()}
  tap("gacha:option:paid");lateinit var old:Button
  ui{old=h.view.findViewWithTag("gacha:confirm");h.restrict(true);old.performClick();assertEquals(2,h.events.size);assertTrue(nodes(h.view).none{it is Button})}
  ui{h.restrict(false)};tap("gacha:confirm");assertEquals(3,h.events.size)
  val retry=(h.events.last() as GachaEvent.Draw).request
  assertEquals(a.input.option.requestId,retry.input.option.requestId);assertFalse(a.attemptId==retry.attemptId)
  ui{h.replace();h.view.complete(a,ContractResult.Success(Unit));assertEquals(retry,h.view.uiState.pending)}
  shot("native-pending-restored")
  ui{h.view.complete(retry,ContractResult.Success(Unit));assertTrue(nodes(h.view).filterIsInstance<Button>().filter{it.tag.toString().startsWith("gacha:option:")}.none{it.isEnabled});h.show(GachaPreviewStates.result())}
  tap("gacha:garage");assertEquals(4,h.events.size)
  val firstNav=(h.events.last() as GachaEvent.OpenGarage).request
  ui{h.view.completeGarageNavigation(firstNav)};tap("gacha:garage")
  val secondNav=(h.events.last() as GachaEvent.OpenGarage).request
  ui{h.replace();h.view.completeGarageNavigation(firstNav);assertEquals(secondNav,h.view.uiState.garagePending);assertFalse(h.view.findViewWithTag<Button>("gacha:garage").isEnabled)}
  assertEquals(5,h.events.size);shot("current-token-restored")
  ui{h.view.completeGarageNavigation(secondNav)};tap("gacha:garage");assertEquals(6,h.events.size)
  shot("external-result")
  android.util.Log.i("Gacha52ReviewA","PASS native A-B-A economicKey preserved attempt changed staleResponse ignored restriction constructorPending garage token late-response blocked restored current completion accepted")
 }
 fun testNarrowAndLandscapeLongCandidatesUseHostWidthOnly(){
  val h=activity;val v=(GachaPreviewStates.ready().screen as GachaScreen.Offers).value
  val content=v.content!!.copy(catalog=v.content.catalog.copy(vehicles=v.content.catalog.vehicles.map{it.copy(displayName=it.displayName+" 긴 후보 이름".repeat(10))}))
  ui{h.width(320);h.show(GachaFrame(5,GachaScreen.Offers(v.copy(content=content))))};shot("long-candidates-320dp")
  ui{assertEquals(320f,h.view.width/h.resources.displayMetrics.density,1f);assertTrue(nodes(h.view).all{it.width<=h.view.width})}
  ui{h.width(936);h.show(GachaPreviewStates.guaranteed().copy(generation=6))};shot("guaranteed-936dp")
  ui{assertEquals(936f,h.view.width/h.resources.displayMetrics.density,1f);assertTrue(nodes(h.view).all{it.width<=h.view.width})}
  android.util.Log.i("Gacha52ReviewA","PASS host widths320,936 no global wm/font change narrow long candidates and wide guarantee")
 }
}
