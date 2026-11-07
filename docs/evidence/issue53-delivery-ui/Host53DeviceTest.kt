package io.drivegrowth.app.ui.review
import android.test.ActivityInstrumentationTestCase2
import android.test.TouchUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.graphics.Rect
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import io.drivegrowth.app.ui.delivery.*
import io.drivegrowth.core.contract.*
@Suppress("DEPRECATION")
class Host53DeviceTest : ActivityInstrumentationTestCase2<Host53Activity>(Host53Activity::class.java) {

    private fun onUi(block: () -> Unit) { var failure: Throwable? = null; instrumentation.runOnMainSync { try { block() } catch(t: Throwable) { failure = t } }; failure?.let { throw it }; instrumentation.waitForIdleSync() }
    private fun all(v: View): List<View> = listOf(v) + if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun texts(v: View) = all(v).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun tap(v: View) { onUi { v.requestRectangleOnScreen(Rect(0,0,v.width,v.height),true) }; TouchUtils.clickView(this, v); instrumentation.waitForIdleSync() }
    private fun shot(name: String) { instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(350); instrumentation.uiAutomation.takeScreenshot().let { b -> FileOutputStream(File(activity.filesDir,name)).use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle() } }


    fun testNativeTouchWidthsRestrictionPendingAndRestoration() {
        val a = activity
        for(width in listOf(320,1100)) {
            onUi { a.requests.clear(); a.show(width) }
            val v = a.screen
            instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(350)
            onUi { assertTrue(v.width <= (v.parent as View).width) }
            File(a.filesDir,"layout.txt").appendText("requested=$width measuredPx=${v.width} density=${v.resources.displayMetrics.density} heightPx=${v.height}\n")
            tap(v.findViewWithTag<Button>("delivery:offer:offer-1"))
            assertTrue(a.requests.isEmpty())
            shot("53-${width}-confirm.png")
            tap(v.findViewWithTag<Button>("delivery:confirm"))
            assertEquals(1,a.requests.size)
            val request = a.requests.single()
            onUi { v.render(DeliveryPreviewStates.offers,false) }
            assertTrue(all(v).none { it is Button })
            onUi { assertTrue(v.complete(request,DeliveryPreviewStates.activeFrame)) }
            assertTrue(all(v).none { it is Button })
            onUi { v.render(DeliveryPreviewStates.activeFrame,true) }
            assertTrue(texts(v).contains("진행 중 탁송"))
            shot("53-${width}-active.png")
        }
        onUi { a.requests.clear(); a.show(320) }
        val v = a.screen
        tap(v.findViewWithTag<Button>("delivery:offer:offer-1"))
        val saved = v.saveUiState()
        onUi {
            val restored = DeliveryView(a,{a.requests.add(it)},saved)
            restored.render(DeliveryPreviewStates.offers,true)
            assertEquals(saved.selectedOfferId,restored.saveUiState().selectedOfferId)
        }
        tap(v.findViewWithTag<Button>("delivery:confirm"))
        val pending = v.saveUiState()
        onUi {
            val restored = DeliveryView(a,{a.requests.add(it)},pending)
            restored.render(DeliveryPreviewStates.offers,true)
            assertEquals(pending.pending,restored.saveUiState().pending)
            assertTrue(all(restored).filterIsInstance<Button>().all { !it.isEnabled })
            assertEquals(1,a.requests.size)
            assertTrue(restored.complete(pending.pending!!,DeliveryPreviewStates.activeFrame))
        }
    }

}
