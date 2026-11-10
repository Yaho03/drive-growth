package io.drivegrowth.app.host.garage

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.progression.VehicleAction

/** 명시 화면 입력의 확인/gate/과거 결과 표현 시험. 실제 DB/차량 수신 시험과 구분한다. */
@Suppress("DEPRECATION")
class GarageCommandViewTest : InstrumentationTestCase() {
    private val car=OwnedVehicle(OwnedVehicleId("target"),VehicleId("car"),3,Revision(3),VehicleLifecycle.OWNED,Rarity.RARE,25,GameMoney(250),VehicleAcquisitionSource.GACHA)
    private val change=GameChange(RequestId("original"),TransactionKind.GARAGE,InputSource.SIMULATION,RuleVersion("rule"),
        MoneyChange(MoneyDirection.DEBIT,GameMoney(22)),VehicleWrite.Update(car.ownedVehicleId,Revision(2),3,VehicleLifecycle.OWNED,25))
    private val pending=GaragePending(GarageEvent.RequestAction(car.ownedVehicleId,Revision(2),VehicleAction.LEVEL_UP),ContentVersion("content"),GarageCommand.Single(change),GameMoney(100))
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))}else emptyList()
    fun testUnavailableCheckpointCannotConfirmAndOldButtonCannotActAfterRestriction() {
        instrumentation.runOnMainSync {
            var confirmed=0
            val view=GarageCommandView(instrumentation.targetContext,{confirmed++},{},{},{},{},{})
            view.render(GarageCommandState.Review(pending,false),true)
            val disabled=descendants(view).filterIsInstance<Button>().first{it.text=="확정"}
            assertFalse(disabled.isEnabled);disabled.performClick();assertEquals(0,confirmed)
            view.render(GarageCommandState.Review(pending,true),true)
            val old=descendants(view).filterIsInstance<Button>().first{it.text=="확정"}
            view.render(GarageCommandState.Review(pending,true),false)
            old.performClick();assertEquals(0,confirmed)
        }
    }
    fun testRecoveredRequestDoesNotClaimItWasNeverExecuted() {
        instrumentation.runOnMainSync {
            val view=GarageCommandView(instrumentation.targetContext,{},{},{},{},{},{})
            view.render(GarageCommandState.Review(pending,true,recovering=true),true)
            val texts=descendants(view).filterIsInstance<TextView>().map{it.text.toString()}
            assertTrue(texts.any{it.contains("미실행이면")&&it.contains("이미 실행됐으면")})
            assertFalse(texts.any{it.contains("아직 실행하지 않았습니다")})
        }
    }
    fun testConfirmedBalanceComesFromActualReceiptRatherThanQuote() {
        instrumentation.runOnMainSync {
            val receipt=Settlement(SettlementId("receipt"),Transaction(TransactionId("tx"),change.requestId,change.kind,change.source,change.ruleVersion,change.money),GameMoney(78),car,EpochMillis(1))
            val view=GarageCommandView(instrumentation.targetContext,{},{},{},{},{},{})
            view.render(GarageCommandState.Confirmed(pending,GarageReceipt.Single(receipt)),true)
            val texts=descendants(view).filterIsInstance<TextView>().map{it.text.toString()}
            assertTrue(texts.contains("당시 확정 잔액 78 게임머니"));assertTrue(texts.contains("조회 잔액 100 게임머니"))
        }
    }
}
