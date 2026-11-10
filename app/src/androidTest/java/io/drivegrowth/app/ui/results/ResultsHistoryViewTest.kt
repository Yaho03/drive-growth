package io.drivegrowth.app.ui.results

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.*

@Suppress("DEPRECATION")
class ResultsHistoryViewTest:InstrumentationTestCase() {
    fun testWholeHistoryDisplaysStoredRentalFieldsAndViewedRowCannotMarkAgain() {
        val events=mutableListOf<ResultsEvent>()
        instrumentation.runOnMainSync {
            val receipt=Settlement(SettlementId("viewed"),Transaction(TransactionId("tx"),RequestId("rental/settle/r"),TransactionKind.RENTAL,InputSource.SIMULATION,RuleVersion("original"),MoneyChange(MoneyDirection.CREDIT,GameMoney(233))),GameMoney(900),null,EpochMillis(1000))
            val row=ResultPresentation(receipt,viewed=true,rental=CompletedRental(RequestId("r"),OwnedVehicleId("actual-car"),RentalReceipt(receipt,false,true,false,GameMoney(7),null)),rentalVehicleName="저장된 대여 차량")
            val view=ResultsView(instrumentation.targetContext,events::add)
            view.render(ResultsScreenState.Content(listOf(row),ResultsOrigin.STORED,true),true)
            view.findViewWithTag<View>("results:scope:ALL").performClick();view.findViewWithTag<View>("results:open:viewed").performClick()
            assertNotNull(view.findViewWithTag<View>("results:rental-detail"));assertFalse(view.findViewWithTag<View>("results:mark:viewed").isEnabled)
            fun texts(v:View):List<String> = (if(v is TextView)listOf(v.text.toString())else emptyList()) + if(v is ViewGroup)(0 until v.childCount).flatMap{texts(v.getChildAt(it))}else emptyList()
            val shown=texts(view).joinToString("\n");assertTrue(shown.contains("저장된 대여 차량"));assertTrue(shown.contains("단골 여부: 확인 불가"));assertTrue(shown.contains("가상 영업 사고: 없음"));assertTrue(events.none{it is ResultsEvent.MarkViewed})
        }
    }
}
