package io.drivegrowth.app.ui.onboarding

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.app.ui.presentation.RewardCelebrationView
import io.drivegrowth.core.contract.GameMoney
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

    fun testIntroKeepsFixtureNoticeWithoutRepeatingSimulationProfileLabel() = onUi {
        val view = OnboardingView(instrumentation.targetContext) {}
        view.render(OnboardingPreviewStates.intro.copy(sourceLabel = "모의 입력 프로필"), true)
        assertFalse(texts(view).any { it == "시연 모드" || it == "모의 입력 프로필" })
        view.render(OnboardingPreviewStates.intro, true)
        assertTrue(texts(view).contains(OnboardingPreviewStates.SOURCE))
    }

    fun testPrimaryActionsRemainVisibleInShortWindowsAndWithLargeText() = onUi {
        val context = instrumentation.targetContext
        val enlarged = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            fontScale = 1.4f
        })
        val states = listOf(
            OnboardingPreviewStates.intro to R.string.onboarding_start,
            OnboardingPreviewStates.choosing to R.string.onboarding_review,
            OnboardingPreviewStates.confirming to R.string.onboarding_confirm,
            OnboardingPreviewStates.completed to R.string.onboarding_to_shop,
        )
        for (testContext in listOf(context, enlarged)) for (widthDp in listOf(320, 936)) {
            val density = testContext.resources.displayMetrics.density
            val width = (widthDp * density).toInt()
            val height = (320 * density).toInt()
            for ((state, resource) in states) {
                val view = OnboardingView(testContext) {}
                assertTrue(view.render(state, true))
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                val action = checkNotNull(view.findViewWithTag<Button>(resource))
                val scroll = all(view).filterIsInstance<ScrollView>().single()
                val buttonBounds = Rect().also {
                    action.getDrawingRect(it)
                    view.offsetDescendantRectToMyCoords(action, it)
                }
                assertTrue("width=$widthDp state=${state.javaClass.simpleName} action=$buttonBounds",
                    buttonBounds.top >= scroll.bottom && buttonBounds.bottom <= height)
                assertTrue("본문 영역이 남아야 한다", scroll.height > 0)
                assertTrue("터치 영역은 48dp 이상이어야 한다", action.height >= (48 * density).toInt())
                val textLayout = checkNotNull(action.layout)
                assertTrue(textLayout.height <= action.height - action.compoundPaddingTop - action.compoundPaddingBottom)
                assertTrue((0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 })
            }
        }
    }

    fun testCandidateCardsUseTheWholeCardAndConfirmKeepsBothActionsBelowScroll() = onUi {
        val context = instrumentation.targetContext
        val view = OnboardingView(context) {}
        val width = (702 / 0.75f * context.resources.displayMetrics.density).toInt()
        val height = (438 / 0.75f * context.resources.displayMetrics.density).toInt()
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
        val choosing = OnboardingPreviewStates.choosing
        view.render(choosing, true)
        layout()
        val casper = checkNotNull(view.findViewWithTag<Button>(OnboardingPreviewStates.casper.vehicle.vehicleId))
        val avante = checkNotNull(view.findViewWithTag<Button>(OnboardingPreviewStates.avante.vehicle.vehicleId))
        assertTrue(casper.width > 0 && casper.height > 0)
        assertEquals((casper.parent as View).height, casper.height)
        assertEquals((avante.parent as View).height, avante.height)
        assertTrue(casper.contentDescription.contains("캐스퍼"))
        assertFalse(texts(view).any { it.startsWith("지급 예정") })

        view.render(OnboardingPreviewStates.confirming, true)
        layout()
        val scroll = all(view).filterIsInstance<ScrollView>().single()
        assertEquals(1, texts(view).count { it.startsWith("지급 예정") })
        for (tag in listOf(R.string.onboarding_confirm_body, R.string.onboarding_previous, R.string.onboarding_confirm)) {
            val item = checkNotNull(view.findViewWithTag<View>(tag))
            val bounds = Rect().also {
                item.getDrawingRect(it)
                view.offsetDescendantRectToMyCoords(item, it)
            }
            assertTrue("확인 조작은 본문 밖에 남는다: $bounds", bounds.top >= scroll.bottom && bounds.bottom <= height)
        }
    }

    fun testOlderNamedReviewButtonCannotReviewADifferentVehicle() = onUi {
        val events = mutableListOf<OnboardingEvent>()
        val view = OnboardingView(instrumentation.targetContext, events::add)
        val casper = OnboardingPreviewStates.casper.vehicle.vehicleId
        val avante = OnboardingPreviewStates.avante.vehicle.vehicleId
        view.render(OnboardingPreviewStates.choosing, true)
        view.findViewWithTag<Button>(casper).performClick()
        val previousReview = view.findViewWithTag<Button>(R.string.onboarding_review)
        view.findViewWithTag<Button>(avante).performClick()
        previousReview.performClick()
        assertTrue(events.none { it is OnboardingEvent.ReviewSelection })
        view.findViewWithTag<Button>(R.string.onboarding_review).performClick()
        assertEquals(OnboardingEvent.ReviewSelection(avante), events.last())
    }

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
        button(view, "시작하기").performClick()
        all(view).filterIsInstance<Button>().single { it.tag == OnboardingPreviewStates.avante.vehicle.vehicleId }.performClick()
        assertEquals(OnboardingPreviewStates.avante.vehicle.vehicleId, view.selectedVehicleId)
        button(view, "아반떼로 할게요").performClick()
        val confirm = button(view, "확인")
        confirm.performClick(); confirm.performClick()
        assertEquals(1, events.filterIsInstance<OnboardingEvent.ConfirmSelection>().size)
        assertTrue(all(view).none { it is Button })
        assertFalse(texts(view).contains("가게 문을 열 준비가 됐어요!"))
        assertFalse(view.render(OnboardingPreviewStates.completed, true))
        val completed = OnboardingPreviewStates.completed.copy(vehicle = OnboardingPreviewStates.completed.vehicle.copy(
            vehicleId = OnboardingPreviewStates.avante.vehicle.vehicleId), displayName = "아반떼")
        assertTrue(view.render(completed, true))
        assertTrue(texts(view).contains("아반떼"))
        button(view, "가게로 가기").performClick()
        assertEquals(OnboardingEvent.GoToShop, events.last())
    }

    fun testConfirmedRewardsFollowSharedClockAndCancellationSettlesTheScreen() = onUi {
        val events = mutableListOf<OnboardingEvent>()
        val view = OnboardingView(instrumentation.targetContext, events::add)
        val candidate = OnboardingPreviewStates.casper.vehicle.vehicleId
        view.render(OnboardingPreviewStates.choosing, true)
        view.findViewWithTag<Button>(candidate).performClick()
        view.findViewWithTag<Button>(R.string.onboarding_review).performClick()
        assertTrue(view.render(OnboardingPreviewStates.confirming, true))
        view.findViewWithTag<Button>(R.string.onboarding_confirm).performClick()
        val state = OnboardingPreviewStates.completed.copy(initialMoney = GameMoney(2100), initialParkingSpaces = 7)
        assertTrue(view.render(state, true))
        val clock = all(view).filterIsInstance<RewardCelebrationView>().single()
        fun text(value: String) = all(view).filterIsInstance<TextView>().single { it.text == value }
        fun card(title: String) = text(title).parent as View
        fun frame(elapsed: Long?) = checkNotNull(clock.onFirstGrantFrame).invoke(elapsed)

        frame(0)
        assertTrue(card("시작 게임머니").alpha == 0f)
        assertFalse(view.findViewWithTag<Button>(R.string.onboarding_to_shop).isEnabled)
        frame(149)
        assertTrue(card("시작 게임머니").alpha > 0f)
        assertTrue(card("첫 차량").alpha <= .01f)
        frame(152)
        assertTrue(card("첫 차량").alpha > .01f)
        assertTrue(card("처음 열린 주차 공간").alpha == 0f)
        frame(301)
        assertTrue(card("처음 열린 주차 공간").alpha > 0f)
        frame(449)
        val amount = (card("시작 게임머니") as ViewGroup).getChildAt(2) as TextView
        assertEquals(View.INVISIBLE, amount.visibility)
        fun slot(index: Int) = checkNotNull(view.findViewWithTag<View>("first-grant:parking-slot:$index"))
        assertNotNull(slot(6))
        assertNull(view.findViewWithTag<View>("first-grant:parking-slot:7"))
        assertNotNull(text("7칸"))
        frame(699)
        assertTrue(slot(0).alpha == 0f)
        frame(701)
        assertTrue(slot(0).alpha > 0f)
        assertTrue(slot(1).alpha == 0f)
        frame(800)
        assertEquals(View.VISIBLE, amount.visibility)
        assertEquals("1,050", amount.text.toString())
        frame(801)
        assertTrue(slot(1).alpha > 0f)
        assertTrue(slot(2).alpha == 0f)
        frame(901)
        assertTrue(slot(2).alpha > 0f)
        assertTrue(slot(3).alpha == 0f)
        frame(1301)
        assertTrue(slot(6).alpha > 0f)
        frame(1449)
        assertFalse(view.findViewWithTag<Button>(R.string.onboarding_to_shop).isEnabled)
        assertTrue(events.none { it == OnboardingEvent.GoToShop })
        frame(1450)
        assertTrue(view.findViewWithTag<Button>(R.string.onboarding_to_shop).isEnabled)
        frame(null)
        assertEquals("2,100", amount.text.toString())
        assertTrue((0..6).all { slot(it).alpha == 1f })
        assertTrue(view.findViewWithTag<Button>(R.string.onboarding_to_shop).alpha == 1f)
        assertEquals(1, events.filterIsInstance<OnboardingEvent.ConfirmSelection>().size)
        view.findViewWithTag<Button>(R.string.onboarding_to_shop).performClick()
        assertEquals(OnboardingEvent.GoToShop, events.last())
    }

    fun testNarrowRewardScrollSettlesTheClockIfVehicleLeavesTheViewport() = onUi {
        val context = instrumentation.targetContext
        val view = OnboardingView(context) {}
        view.render(OnboardingPreviewStates.confirming, true)
        view.findViewWithTag<Button>(R.string.onboarding_confirm).performClick()
        val state = OnboardingPreviewStates.completed.copy(summary = List(8) { "확정 보상 안내 ${it + 1}" })
        assertTrue(view.render(state, true))
        val size = (320 * context.resources.displayMetrics.density).toInt()
        val spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        view.measure(spec, spec)
        view.layout(0, 0, size, size)
        val clock = all(view).filterIsInstance<RewardCelebrationView>().single()
        val scroll = all(view).filterIsInstance<ScrollView>().single()
        val shop = view.findViewWithTag<Button>(R.string.onboarding_to_shop)
        checkNotNull(clock.onFirstGrantFrame).invoke(800)
        assertFalse(shop.isEnabled)
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        assertTrue(scroll.scrollY > 0)
        assertFalse(clock.getLocalVisibleRect(Rect()))
        assertTrue(shop.isEnabled)
    }

    fun testInitiallyClippedGrantRemainsStaticAfterScrollAndRebuild() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val intent = Intent(context, CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        val host = instrumentation.startActivitySync(intent, options) as CollectionScrollTestActivity
        try {
            lateinit var parent: FrameLayout
            onUi {
                parent = FrameLayout(host)
                host.setContentView(parent)
            }
            val deadline = SystemClock.uptimeMillis() + 5_000
            var focused = false
            while (!focused && SystemClock.uptimeMillis() < deadline) {
                onUi { focused = parent.hasWindowFocus() }
                if (!focused) SystemClock.sleep(25)
            }
            assertTrue("클립 조건을 시험하기 전에 창 포커스가 필요합니다", focused)

            onUi {
                val density = host.resources.displayMetrics.density
                val width = (320 * density).toInt()
                val height = (200 * density).toInt()
                val view = OnboardingView(host) {}
                parent.addView(view, FrameLayout.LayoutParams(width, height))
                view.render(OnboardingPreviewStates.confirming, true)
                view.findViewWithTag<Button>(R.string.onboarding_confirm).performClick()
                val state = OnboardingPreviewStates.completed
                assertTrue(view.render(state, true))
                fun layout() {
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                }
                layout()
                assertTrue(view.hasWindowFocus())
                val clock = all(view).filterIsInstance<RewardCelebrationView>().single()
                assertFalse(clock.getLocalVisibleRect(Rect()))
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                } finally {
                    bitmap.recycle()
                }
                assertNull(clock.onFirstGrantFrame)
                val scroll = all(view).filterIsInstance<ScrollView>().single()
                scroll.scrollTo(0, scroll.getChildAt(0).height / 2)
                assertTrue(view.render(state.copy(summary = state.summary + "확정 보상"), true))
                layout()
                assertTrue(all(view).none { it is RewardCelebrationView })
                assertTrue(view.findViewWithTag<Button>(R.string.onboarding_to_shop).isEnabled)
                assertTrue("1,000" in texts(view))
            }
        } finally {
            onUi { host.finish() }
        }
    }

    fun testTownAndCandidateArtFollowTheCurrentStageAndVehicleId() = onUi {
        val context = instrumentation.targetContext
        val sprites = VehicleSprites(context)
        val view = OnboardingView(context) { fail("그림 표시만으로 요청이 발생했다") }
        view.render(OnboardingPreviewStates.intro, true)
        assertNotNull(view.findViewWithTag<ImageView>("onboarding:town-art")?.drawable)
        view.render(OnboardingPreviewStates.choosing, true)
        assertNull(view.findViewWithTag<ImageView>("onboarding:town-art"))
        for (candidate in listOf(OnboardingPreviewStates.casper, OnboardingPreviewStates.avante)) {
            val id = candidate.vehicle.vehicleId
            val image = checkNotNull(view.findViewWithTag<ImageView>("onboarding:vehicle-art:${id.value}"))
            val expected = checkNotNull(sprites.bitmap(id))
            assertTrue(expected.sameAs((image.drawable as BitmapDrawable).bitmap))
        }
        view.render(OnboardingPreviewStates.choosing, false)
        assertTrue(all(view).none { it is ImageView })
    }

    fun testCompletedRewardsKeepConfirmedMoneyVehicleAndParkingInSeparateCards() = onUi {
        val view = OnboardingView(instrumentation.targetContext) { fail("확정 결과 표시 중 요청이 발생했다") }
        val gift = "개업 선물은 첫 영업을 마치면 받을 수 있어요."
        val state = OnboardingPreviewStates.completed.copy(summary = listOf(gift),
            initialMoney = GameMoney(2345), initialParkingSpaces = 7)
        assertTrue(view.render(state, true))
        fun card(title: String) = all(view).filterIsInstance<TextView>().single { it.text == title }.parent as ViewGroup
        assertTrue("가게 문을 열 준비가 됐어요!" in texts(view))
        assertFalse("첫 차를 받은 기록이에요" in texts(view))
        assertTrue("2,345" in texts(card("시작 게임머니")))
        assertTrue(state.displayName in texts(card("첫 차량")))
        assertTrue("7칸" in texts(card("처음 열린 주차 공간")))
        assertTrue(gift in texts(view))
        assertFalse(gift in texts(card("처음 열린 주차 공간")))

        assertTrue(view.render(state.copy(initialMoney = null, initialParkingSpaces = null), true))
        assertFalse("2,345" in texts(view))
        assertFalse("7칸" in texts(view))
        assertTrue("확인할 수 없어요" in texts(card("시작 게임머니")))
        assertTrue("확인할 수 없어요" in texts(card("처음 열린 주차 공간")))
        assertTrue(gift in texts(view))
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
        assertFalse(view.findViewWithTag<Button>(R.string.onboarding_review).isEnabled)
        view.render(OnboardingPreviewStates.empty, true)
        assertTrue(texts(view).contains("선택할 차량 후보가 없어요"))
        view.render(OnboardingPreviewStates.loadError, true)
        assertTrue(texts(view).contains("첫 실행 정보를 읽지 못했어요"))
        val retry = button(view, "다시 확인")
        retry.performClick(); retry.performClick()
        assertEquals(listOf(OnboardingEvent.Retry(null)), events)
    }

    fun testKnownSelectableVehiclesShowClassWithoutMissingComparisonNotice() = onUi {
        val view = OnboardingView(instrumentation.targetContext) {}
        val state = OnboardingPreviewStates.choosing
        view.render(state.copy(candidates = state.candidates.map { it.copy(description = null) }), true)
        assertTrue(texts(view).containsAll(listOf("경형 SUV", "준중형 세단")))
        assertFalse(texts(view).contains("차종 외 상세 비교 정보는 아직 없어요."))
        assertFalse(texts(view).contains("차량 비교 정보를 아직 확인하지 못했어요."))
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
        assertFalse(texts(view).contains("가게 문을 열 준비가 됐어요!"))
    }

    fun testAlreadyGrantedProfileOnlyRequestsShopNavigation() = onUi {
        val events = mutableListOf<OnboardingEvent>()
        val view = OnboardingView(instrumentation.targetContext, events::add)
        view.render(OnboardingPreviewStates.alreadyGranted, true)
        assertTrue(texts(view).contains("첫 차를 받은 기록이에요"))
        assertFalse(view.render(OnboardingPreviewStates.choosing, true))
        assertEquals(listOf("가게로 가기"), all(view).filterIsInstance<Button>().map { it.text.toString() })
        assertFalse(view.handleBack())
        button(view, "가게로 가기").performClick()
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
