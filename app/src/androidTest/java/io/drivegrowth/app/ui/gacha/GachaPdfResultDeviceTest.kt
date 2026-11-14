package io.drivegrowth.app.ui.gacha

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.contract.MoneyChange
import io.drivegrowth.core.contract.MoneyDirection

@Suppress("DEPRECATION")
class GachaPdfResultDeviceTest : InstrumentationTestCase() {
    fun testRestoredResultUsesTransactionDebitAndHidesInternalCopy() = onUi {
        val fixture = GachaPreviewStates.result()
        val result = (fixture.screen as GachaScreen.Result).value
        val receipt = result.settlement
        val technical = "저장된 공급 상태 · ${receipt.transaction.requestId.value} · ${receipt.settlementId.value}"
        for (debit in listOf(null, MoneyChange(MoneyDirection.DEBIT, GameMoney(350)))) {
            val view = GachaView(instrumentation.targetContext, onEvent = { fail("결과 표시가 요청을 보냈다") })
            val supplied = result.copy(origin = GachaOrigin.STORED, summary = technical,
                settlement = receipt.copy(transaction = receipt.transaction.copy(money = debit)))
            assertTrue(view.render(fixture.copy(screen = GachaScreen.Result(supplied)), true))
            val shown = copy(view)
            assertTrue("아반떼 · 희귀" in shown)
            assertEquals(debit != null, "이번 뽑기에 쓴 금액 · 350 게임머니" in shown)
            listOf("실제 취득 기준액", "저장된 공급 상태", receipt.transaction.requestId.value,
                receipt.settlementId.value, "도감 등록").forEach { forbidden ->
                assertTrue(shown.none { forbidden in it })
            }
            assertEquals("한 번 더 뽑기", view.findViewWithTag<Button>("gacha:draw-again").text.toString())
            assertEquals("차고에서 보기", view.findViewWithTag<Button>("gacha:garage").text.toString())
        }
    }

    fun testFailedResultShowsPlayerFacingRecoveryWithoutInternalDetail() = onUi {
        val view = GachaView(instrumentation.targetContext, onEvent = { fail("오류 표시가 요청을 보냈다") })
        val frame = GachaFrame(1, GachaScreen.Failure(ContractError.STORAGE_FAILURE,
            "확정 정산 ID · fixture-gacha-result · STORAGE_FAILURE", true))
        assertTrue(view.render(frame, true))
        val shown = copy(view)
        assertTrue("뽑기 결과를 확인하지 못했어요. 다시 확인해 주세요" in shown)
        assertTrue(shown.none { "fixture-gacha-result" in it || "STORAGE_FAILURE" in it })
        assertNull(view.findViewWithTag<Button>("gacha:garage"))
        assertTrue(view.findViewWithTag<Button>("gacha:reload").isEnabled)
    }

    private fun copy(view: View): List<String> =
        listOfNotNull((view as? TextView)?.text?.toString(), view.contentDescription?.toString()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { copy(view.getChildAt(it)) } else emptyList()

    private fun onUi(block: () -> Unit) {
        var error: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (failure: Throwable) { error = failure } }
        error?.let { throw it }
    }
}
