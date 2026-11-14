package io.drivegrowth.app.host.garage

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.app.ui.garage.GarageEvent
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.VehicleRef
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
    fun testRestoredSaleShowsReceiptAndHistoryWithoutMotionOrAnotherCommand() {
        instrumentation.runOnMainSync {
            var acknowledged = 0
            val payment = MoneyChange(MoneyDirection.CREDIT, GameMoney(150))
            val command = change.copy(money = payment,
                vehicle = (change.vehicle as VehicleWrite.Update).copy(lifecycle = VehicleLifecycle.SOLD))
            val restored = pending.copy(request = pending.request.copy(action = VehicleAction.SELL),
                command = GarageCommand.Single(command))
            val receipt = Settlement(SettlementId("sold"), Transaction(TransactionId("tx"), command.requestId,
                command.kind, command.source, command.ruleVersion, payment), GameMoney(250),
                car.copy(lifecycle = VehicleLifecycle.SOLD), EpochMillis(1))
            val confirmed = GarageCommandState.Confirmed(restored, GarageReceipt.Single(receipt))
            val view = GarageCommandView(instrumentation.targetContext, { error("기존 원결과를 재실행하지 않는다") },
                {}, {}, {}, {}, { acknowledged++ })
            view.render(confirmed, true)
            view.render(confirmed, true)
            assertNull(view.findViewWithTag<View>("garage:care-motion"))
            val texts = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.containsAll(listOf("판매 완료", "확정 지급액 150 게임머니", "도감에 보유 기록이 남아요")))
            val confirmResult = descendants(view).filterIsInstance<Button>().single { it.text == "결과 확인" }
            assertTrue(confirmResult.isEnabled)
            confirmResult.performClick()
            assertEquals(1, acknowledged)
        }
    }

    fun testNewConfirmationKeepsMotionAcrossReparentAndDismissesItWithResultAction() {
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        val host = instrumentation.startActivitySync(Intent(context, CollectionScrollTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) as CollectionScrollTestActivity
        try {
            lateinit var view: GarageCommandView
            lateinit var panel: FrameLayout
            var commands = 0
            var acknowledgements = 0
            val ready = pending.copy(vehicle = car.copy(level = 2, revision = Revision(2)))
            val result = GarageReceipt.Single(Settlement(SettlementId("new"), Transaction(TransactionId("tx"),
                change.requestId, change.kind, change.source, change.ruleVersion, change.money), GameMoney(78), car, EpochMillis(1)))
            val confirmed = GarageCommandState.Confirmed(ready, result)
            instrumentation.runOnMainSync {
                panel = FrameLayout(host)
                view = GarageCommandView(host, {
                    commands++
                    view.render(GarageCommandState.Running(ready), true)
                    view.render(confirmed, true)
                    panel.removeView(view)
                    panel.addView(view)
                }, {}, {}, {}, {}, { acknowledgements++ })
                panel.addView(view)
                host.setContentView(panel)
                view.render(GarageCommandState.Review(ready, true), true)
            }
            val focusDeadline = SystemClock.uptimeMillis() + 3_000
            var hasFocus = false
            while (!hasFocus && SystemClock.uptimeMillis() < focusDeadline) {
                instrumentation.runOnMainSync { hasFocus = view.hasWindowFocus() && view.isLaidOut }
                if (!hasFocus) SystemClock.sleep(20)
            }
            assertTrue("계측 창 포커스와 배치가 필요하다", hasFocus)
            var expectsMotion = false
            var observed = false
            instrumentation.runOnMainSync {
                expectsMotion = ValueAnimator.areAnimatorsEnabled()
                val button = descendants(view).filterIsInstance<Button>().single { it.text == "확정" }
                button.performClick()
                button.performClick()
                assertEquals(1, commands)
                assertEquals(expectsMotion, view.findViewWithTag<View>("garage:care-motion") != null)
            }
            val deadline = SystemClock.uptimeMillis() + 500
            while (expectsMotion && !observed && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    observed = view.findViewWithTag<View>("garage:care-motion")?.visibility == View.VISIBLE
                }
                if (!observed) SystemClock.sleep(12)
            }
            assertEquals(expectsMotion, observed)
            instrumentation.runOnMainSync {
                view.render(confirmed, true)
                assertEquals(expectsMotion, view.findViewWithTag<View>("garage:care-motion") != null)
                descendants(view).filterIsInstance<Button>().single { it.text == "결과 확인" }.performClick()
                assertEquals(1, acknowledgements)
                assertTrue(view.findViewWithTag<View>("garage:care-motion")?.visibility != View.VISIBLE)
                view.render(confirmed, false)
                view.render(confirmed, true)
                assertNull(view.findViewWithTag<View>("garage:care-motion"))
                val background = GarageCommandView(host, {}, {}, {}, {}, {}, {})
                lateinit var delayed: GarageCommandView
                delayed = GarageCommandView(host, { delayed.render(GarageCommandState.Running(ready), true) },
                    {}, {}, {}, {}, {})
                panel.removeView(view)
                panel.addView(delayed)
                delayed.render(GarageCommandState.Review(ready, true), true)
                descendants(delayed).filterIsInstance<Button>().single { it.text == "확정" }.performClick()
                delayed.dismissMotion() // 시스템 Back/닫기에서 부르는 경계
                panel.removeView(delayed)
                panel.addView(background)
                delayed.render(confirmed, true)
                assertNull(delayed.findViewWithTag<View>("garage:care-motion"))
            }
        } finally {
            instrumentation.runOnMainSync { host.finish() }
        }
    }

    fun testMaterialChoiceAndReviewShowGarageNamesAndKeepOriginalRef() {
        instrumentation.runOnMainSync {
            val first = car.copy(ownedVehicleId = OwnedVehicleId("material-internal-a"), revision = Revision(5))
            val second = first.copy(ownedVehicleId = OwnedVehicleId("material-internal-b"), revision = Revision(8))
            val request = GarageEvent.RequestAction(car.ownedVehicleId, car.revision, VehicleAction.USE_MATERIAL)
            val selected = mutableListOf<VehicleRef>()
            val view = GarageCommandView(instrumentation.targetContext, {}, {}, {}, {}, selected::add, {})
            fun texts() = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
            view.render(GarageCommandState.Materials(GaragePreparation.Materials(request, listOf(first, second),
                mapOf(first.ownedVehicleId to "캐스퍼 2번", second.ownedVehicleId to "캐스퍼 4번"))), true)
            assertTrue(texts().any { it.startsWith("캐스퍼 2번 ·") })
            val button = descendants(view).filterIsInstance<Button>().single { it.text.startsWith("캐스퍼 4번 ·") }
            assertTrue(button.performClick())
            assertEquals(listOf(VehicleRef(second.ownedVehicleId, second.revision)), selected)
            assertFalse(texts().any { "material-internal" in it })

            val command = MaterialCommand(RequestId("material-confirm"), InputSource.SIMULATION, ContentVersion("content"), RuleVersion("rule"),
                VehicleWrite.Update(car.ownedVehicleId, car.revision, 4, VehicleLifecycle.OWNED, 0),
                VehicleWrite.Update(second.ownedVehicleId, second.revision, second.level, VehicleLifecycle.CONSUMED,
                    second.levelProgressPercent), GameMoney(0))
            val ready = GaragePending(request, command.contentVersion, GarageCommand.Material(command),
                vehicle = car, name = "캐스퍼 1번", materialName = "캐스퍼 4번")
            view.render(GarageCommandState.Review(ready, true), true)
            assertTrue(texts().contains("캐스퍼 1번"))
            assertTrue(texts().contains("소비 대상 · 캐스퍼 4번"))
            assertFalse(texts().any { "material-internal" in it })

            val recovered = (restoreGaragePending(ready.checkpoint()) as ContractResult.Success).value
            assertEquals(ready.command, recovered.command)
            view.render(GarageCommandState.Review(recovered, false, recovering = true), true)
            assertTrue(texts().contains("보관한 강화 대상 차량"))
            assertTrue(texts().contains("소비 대상 · 보관한 재료 차량"))
            assertFalse(descendants(view).filterIsInstance<Button>().single { it.text == "보관 요청 처리 · 결과 확인" }.isEnabled)
        }
    }
}
