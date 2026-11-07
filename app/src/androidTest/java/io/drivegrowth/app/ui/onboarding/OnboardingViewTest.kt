package io.drivegrowth.app.ui.onboarding

import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.drivegrowth.core.contract.VehicleId

/** 공유 장치 사용 조율 뒤 실행한다. UI 스레드의 실패는 시험 스레드에 전달한다. */
@Suppress("DEPRECATION")
class OnboardingViewTest : InstrumentationTestCase() {
    private fun onUi(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun button(view: View, text: String) = all(view).filterIsInstance<Button>().first { it.text.toString() == text }
    private fun texts(view: View) = all(view).filterIsInstance<TextView>().map { it.text.toString() }

    fun testSelectionConfirmationWaitsForTheExactConfirmedResult() = onUi {
        val events = mutableListOf<OnboardingEvent>()
        lateinit var view: OnboardingView
        view = OnboardingView(instrumentation.targetContext) { event ->
            events += event
            when (event) {
                OnboardingEvent.StartChoosing -> view.render(OnboardingPreviewStates.choosing, true)
                is OnboardingEvent.ReviewSelection -> view.render(OnboardingPreviewStates.confirming.copy(candidate = OnboardingPreviewStates.avante), true)
                is OnboardingEvent.ConfirmSelection -> view.render(OnboardingScreenState.Submitting(event.vehicleId, "아반떼", OnboardingPreviewStates.SOURCE), true)
                else -> Unit
            }
        }
        view.render(OnboardingPreviewStates.intro, true)
        button(view, "첫 차량 고르기").performClick()
        all(view).filterIsInstance<Button>().single { it.tag == OnboardingPreviewStates.avante.vehicle.vehicleId }.performClick()
        assertEquals(OnboardingPreviewStates.avante.vehicle.vehicleId, view.selectedVehicleId)
        button(view, "선택 내용 확인").performClick()
        val confirm = button(view, "이 차량으로 시작")
        confirm.performClick(); confirm.performClick()
        assertEquals(1, events.filterIsInstance<OnboardingEvent.ConfirmSelection>().size)
        assertTrue(all(view).none { it is Button })
        assertFalse(texts(view).contains("첫 차량 지급을 확인했어요"))
        assertFalse(view.render(OnboardingPreviewStates.completed, true))
        val completed = OnboardingPreviewStates.completed.copy(vehicle = OnboardingPreviewStates.completed.vehicle.copy(
            vehicleId = OnboardingPreviewStates.avante.vehicle.vehicleId), displayName = "아반떼")
        assertTrue(view.render(completed, true))
        assertTrue(texts(view).contains("아반떼"))
        button(view, "가게로").performClick()
        assertEquals(OnboardingEvent.GoToShop, events.last())
    }

    fun testRestrictedStateHidesAllGameContentAndActions() = onUi {
        val events = mutableListOf<OnboardingEvent>()
        val view = OnboardingView(instrumentation.targetContext, events::add)
        view.render(OnboardingPreviewStates.choosing, true)
        view.restoreSelection(OnboardingPreviewStates.casper.vehicle.vehicleId)
        view.render(OnboardingPreviewStates.confirming, false)
        assertTrue(all(view).none { it is Button })
        assertFalse(texts(view).contains("캐스퍼"))
        assertFalse(view.handleBack())
        assertTrue(events.isEmpty())
    }

    fun testMissingEmptyAndReadFailureRemainDistinct() = onUi {
        val events = mutableListOf<OnboardingEvent>()
        val view = OnboardingView(instrumentation.targetContext, events::add)
        view.render(OnboardingPreviewStates.missing, true)
        assertTrue(texts(view).contains("차량 비교 정보를 아직 확인하지 못했어요."))
        assertFalse(all(view).filterIsInstance<Button>().single { it.tag == OnboardingPreviewStates.avante.vehicle.vehicleId }.isEnabled)
        view.restoreSelection(VehicleId("removed"))
        assertFalse(button(view, "선택 내용 확인").isEnabled)
        view.render(OnboardingPreviewStates.empty, true)
        assertTrue(texts(view).contains("선택할 차량 후보가 없어요"))
        view.render(OnboardingPreviewStates.loadError, true)
        assertTrue(texts(view).contains("첫 실행 정보를 읽지 못했어요"))
        val retry = button(view, "다시 확인")
        retry.performClick(); retry.performClick()
        assertEquals(listOf(OnboardingEvent.Retry(null)), events)
    }

    fun testGrantFailureAndRecreationUseTheSavedPendingVehicle() = onUi {
        val events = mutableListOf<OnboardingEvent>()
        val view = OnboardingView(instrumentation.targetContext, events::add)
        view.render(OnboardingPreviewStates.submitting, true)
        view.restoreSelection(OnboardingPreviewStates.avante.vehicle.vehicleId)
        assertEquals(OnboardingPreviewStates.casper.vehicle.vehicleId, view.pendingVehicleId)
        assertTrue(view.handleBack())
        view.render(OnboardingPreviewStates.grantError, true)
        assertFalse(button(view, "다른 후보 선택").isEnabled)
        button(view, "다시 확인").performClick()
        assertEquals(listOf(OnboardingEvent.Retry(OnboardingPreviewStates.casper.vehicle.vehicleId)), events)
        assertFalse(button(view, "다시 확인").isEnabled)
        view.render(OnboardingPreviewStates.grantError.copy(message = "표시 문구 갱신", sourceLabel = "출처 갱신"), true)
        assertFalse(button(view, "다시 확인").isEnabled)
        assertFalse(texts(view).contains("첫 차량 지급을 확인했어요"))
    }

    fun testAlreadyGrantedProfileOnlyRequestsShopNavigation() = onUi {
        val events = mutableListOf<OnboardingEvent>()
        val view = OnboardingView(instrumentation.targetContext, events::add)
        view.render(OnboardingPreviewStates.alreadyGranted, true)
        assertTrue(texts(view).contains("이미 첫 차량을 받은 프로필이에요"))
        assertFalse(view.render(OnboardingPreviewStates.choosing, true))
        assertEquals(listOf("가게로"), all(view).filterIsInstance<Button>().map { it.text.toString() })
        assertFalse(view.handleBack())
        button(view, "가게로").performClick()
        assertEquals(listOf(OnboardingEvent.GoToShop), events)
    }
    fun testUnknownProfileStatusNeverExposesGrantOrSelection() = onUi {
        val view = OnboardingView(instrumentation.targetContext) { fail("미확인 상태에서 요청이 발생했다") }
        view.render(OnboardingPreviewStates.unavailable, true)
        assertTrue(texts(view).contains("첫 실행 상태를 확인할 수 없어요"))
        assertTrue(all(view).none { it is Button })
        assertFalse(view.handleBack())
    }

}
