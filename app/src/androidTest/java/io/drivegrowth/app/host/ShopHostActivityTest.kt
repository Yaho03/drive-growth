package io.drivegrowth.app.host

import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import io.drivegrowth.app.openMainPage
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.ui.shop.ShopView
import io.drivegrowth.app.ui.garage.GarageView
import io.drivegrowth.app.ui.gacha.GachaView

/** 격리 패키지의 실제 저장 조회/Activity 탐색. 차량 gate는 주입하거나 우회하지 않는다. */
@Suppress("DEPRECATION")
class ShopHostActivityTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun awaitContent(current: MainActivity, type: Class<*>) {
        val until = SystemClock.uptimeMillis() + 15000
        var found = false
        while (!found && SystemClock.uptimeMillis() < until) {
            instrumentation.runOnMainSync { found = descendants(current.window.decorView).any { type.isInstance(it) } }
            if (!found) SystemClock.sleep(50)
        }
        assertTrue("실제 주차 gate/저장 조회 이후 ${type.simpleName}에 도달해야 함", found)
    }
    private fun select(current: MainActivity, page: HostPage) {
        instrumentation.runOnMainSync { current.window.decorView.openMainPage(page) }
    }
    fun testHomeUsesRealShopViewAndChildPagesReturnHome() {
        assertTrue("제품 패키지로 이 시험을 실행하지 않는다", instrumentation.targetContext.packageName != "io.drivegrowth.app")
        val current = activity
        awaitContent(current, ShopView::class.java)
        select(current, HostPage.GARAGE); awaitContent(current, GarageView::class.java)
        instrumentation.runOnMainSync { current.onBackPressed() }; instrumentation.waitForIdleSync()
        awaitContent(current, ShopView::class.java)
        select(current, HostPage.GACHA); awaitContent(current, GachaView::class.java)
        instrumentation.runOnMainSync { current.onBackPressed() }; instrumentation.waitForIdleSync()
        awaitContent(current, ShopView::class.java)
    }
}
