package io.drivegrowth.app.host.ranking

import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import io.drivegrowth.app.MainActivity
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.app.ui.ranking.*
import io.drivegrowth.app.ui.shop.ShopView
import io.drivegrowth.core.contract.*

/** 격리 패키지·실제 SDK gate에서 Main 진입/명시 시연·재생성·Back을 확인한다. gate 주입0. */
@Suppress("DEPRECATION")
class RankingMainActivityTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))}else emptyList()
    private fun ranking(current:MainActivity)=descendants(current.window.decorView).filterIsInstance<RankingView>().singleOrNull()
    private fun await(message:String,check:()->Boolean){val until=SystemClock.uptimeMillis()+15000;var found=false
        while(!found&&SystemClock.uptimeMillis()<until){instrumentation.runOnMainSync{found=check()};if(!found)SystemClock.sleep(50)}
        assertTrue(message,found)}
    private fun click(current:MainActivity,label:String){instrumentation.runOnMainSync{
        descendants(current.window.decorView).filterIsInstance<Button>().single{it.text.toString()==label}.performClick()
    };instrumentation.waitForIdleSync()}
    private fun balanceAndVehicles():Pair<GameMoney,List<OwnedVehicle>> {
        val opened=GameSessionFactory(instrumentation.targetContext).open() as ContractResult.Success
        return opened.value.use{game->(game.store.balance() as ContractResult.Success).value to (game.store.listOwnedVehicles() as ContractResult.Success).value}
    }
    fun testMainExplicitDemoSelectionRecreationAndBackPreserveActualRecords(){
        assertTrue("제품 패키지에서 실행하지 않는다",instrumentation.targetContext.packageName!="io.drivegrowth.app")
        val first=activity
        await("실제 gate 이후 랭킹 진입과 실제6지표 조회",{
            val menu=first.window.decorView.findViewWithTag<Spinner>("host:destination")
            if(menu!=null&&menu.selectedItem.toString()!="기록·랭킹")menu.setSelection((0 until menu.count).first{menu.getItemAtPosition(it).toString()=="기록·랭킹"})
            ranking(first)!=null && RankingMetric.entries.all{metric->descendants(ranking(first)!!).filterIsInstance<TextView>().any{it.text.toString()==metric.title}}
        })
        val before=balanceAndVehicles()
        instrumentation.runOnMainSync{assertEquals(RankingMode.MY_RECORD,ranking(first)!!.uiState.mode)}
        click(first,"시연용 랭킹 (예시 데이터)");click(first,RankingMetric.WEEKLY_DISTANCE.title)
        val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
        var next:MainActivity?=null
        try {
            instrumentation.runOnMainSync{first.recreate()}
            next=instrumentation.waitForMonitorWithTimeout(monitor,15000) as? MainActivity
            assertNotNull("실제 새 Activity 인스턴스",next);val recreated=next!!;assertNotSame(first,recreated)
            await("새 gate 관측 이후 원래 페이지/시연 선택 복원",{ranking(recreated)?.uiState?.let{it.mode==RankingMode.DEMO&&it.metric==RankingMetric.WEEKLY_DISTANCE}==true && recreated.window.decorView.findViewWithTag<Spinner>("host:destination")!=null})
            instrumentation.runOnMainSync{recreated.onBackPressed();assertEquals(RankingMode.MY_RECORD,ranking(recreated)!!.uiState.mode)}
            instrumentation.runOnMainSync{recreated.onBackPressed()}
            await("두 번째 Back은 가게",{descendants(recreated.window.decorView).any{it is ShopView}})
            assertEquals(before,balanceAndVehicles())
        } finally {instrumentation.removeMonitor(monitor);next?.let{current->instrumentation.runOnMainSync{current.finish()}}}
    }
}
