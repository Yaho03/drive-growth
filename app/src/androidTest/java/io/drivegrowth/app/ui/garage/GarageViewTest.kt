package io.drivegrowth.app.ui.garage

import android.content.res.Configuration
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.core.contract.ContractError
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.EpochMillis
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.garage.CollectionState
import io.drivegrowth.core.progression.VehicleAction

/** 장치 사용 조율 후 실행한다. UI 스레드 실패는 시험 스레드에서 보고한다. */
@Suppress("DEPRECATION")
class GarageViewTest : InstrumentationTestCase() {
    private fun runOnUiTest(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun button(view: View, title: String) = descendants(view).filterIsInstance<Button>().first { it.text.toString() == title }
    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }

    fun testInstanceSelectionAndDuplicateRequest() = runOnUiTest {
        val events = mutableListOf<GarageEvent>()
        val view = GarageView(instrumentation.targetContext, events::add)
        view.render(GaragePreviewStates.normal, true)
        descendants(view).filterIsInstance<Button>().first { it.contentDescription.toString().startsWith("캐스퍼 #1,") }.performClick()
        assertTrue(texts(view).any { it == "캐스퍼 #1" })
        button(view, "판매 조건 확인").performClick()
        assertFalse(button(view, "판매 조건 확인").isEnabled)
        button(view, "판매 조건 확인").performClick()
        assertEquals(1, events.filterIsInstance<GarageEvent.RequestAction>().size)
        view.render(GaragePreviewStates.normal, true)
        assertFalse(button(view, "판매 조건 확인").isEnabled)
        assertFalse(texts(view).any { it.contains("판매 완료") })
    }

    fun testWideGarageKeepsSelectionBesideListAndReturnsToSinglePaneWhenNarrow() {
        lateinit var view: GarageView
        val events = mutableListOf<GarageEvent>()
        val state = GaragePreviewStates.normal
        val first = state.data.vehicles.first().vehicle
        fun layout(widthDp: Int) {
            runOnUiTest {
                val density = view.resources.displayMetrics.density
                val width = (widthDp * density).toInt()
                val height = (600 * density).toInt()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, width, height)
            }
        }
        runOnUiTest {
            val base = instrumentation.targetContext
            val config = Configuration(base.resources.configuration).apply { fontScale = 1f }
            view = GarageView(base.createConfigurationContext(config), events::add)
            view.render(state, true)
        }
        layout(1200)
        runOnUiTest {
            view.render(state, true)
            view.findViewWithTag<Button>(first.ownedVehicleId).performClick()
        }
        layout(1200)
        runOnUiTest {
            val list = view.findViewWithTag<ScrollView>("garage:list")
            val detail = view.findViewWithTag<ScrollView>("garage:detail")
            assertTrue(list.width > 0 && detail.width > 0)
            assertTrue(list.right < detail.left)
            assertTrue(list.findViewWithTag<Button>(first.ownedVehicleId).isSelected)
            val selectedCard = list.findViewWithTag<Button>(first.ownedVehicleId).parent as View
            val selectedLabel = view.context.getString(R.string.garage_selected)
            assertTrue(selectedLabel in texts(selectedCard))
            assertEquals(1, texts(list).count { it == selectedLabel })
            button(detail, "판매 조건 확인").performClick()
            assertFalse(view.findViewWithTag<Button>(first.ownedVehicleId).isEnabled)
            assertEquals(GarageEvent.RequestAction(first.ownedVehicleId, first.revision,
                VehicleAction.SELL), events.last())
        }
        layout(480)
        runOnUiTest {
            view.render(state, true)
            assertNull(view.findViewWithTag<ScrollView>("garage:list"))
            assertNotNull(view.findViewWithTag<ScrollView>("garage:detail"))
            assertEquals(first.ownedVehicleId, view.selectedVehicleId)
        }
    }

    fun testSingleVehicleUsesModelNameAndKeepsOwnedIdForSelection() = runOnUiTest {
        val events = mutableListOf<GarageEvent>()
        val view = GarageView(instrumentation.targetContext, events::add)
        val sample = GaragePreviewStates.normal.data.vehicles.first()
        val id = OwnedVehicleId("initial-grant")
        val single = sample.copy(vehicle = sample.vehicle.copy(ownedVehicleId = id), instanceLabel = "")
        view.render(GarageScreenState.Content(GaragePreviewStates.normal.data.copy(vehicles = listOf(single))), true)
        val select = view.findViewWithTag<Button>(id)
        assertTrue(select.contentDescription.toString().startsWith("캐스퍼,"))
        assertTrue(select.performClick())
        assertEquals(listOf(GarageEvent.SelectVehicle(id)), events)
        assertTrue(texts(view).contains("캐스퍼"))
        assertFalse(texts(view).any { id.value in it })
    }

    fun testMissingSelectionAndRestrictedContent() = runOnUiTest {
        val view = GarageView(instrumentation.targetContext) {}
        view.render(GaragePreviewStates.normal, true)
        descendants(view).filterIsInstance<Button>().first().performClick()
        view.render(GaragePreviewStates.removed, true)
        assertTrue(texts(view).contains("선택한 차량을 찾을 수 없어요"))
        view.render(GaragePreviewStates.normal, false)
        assertTrue(descendants(view).none { it is Button })
        assertFalse(texts(view).any { it.contains("캐스퍼") })
    }

    fun testEmptyLoadingErrorAndRetry() = runOnUiTest {
        var retries = 0
        val view = GarageView(instrumentation.targetContext) { if (it == GarageEvent.Retry) retries++ }
        view.render(GaragePreviewStates.empty, true)
        assertTrue(texts(view).contains("차고에 보유 차량이 없어요"))
        view.render(GaragePreviewStates.loading, true)
        assertTrue(texts(view).contains("차고를 불러오고 있어요"))
        view.render(GaragePreviewStates.error, true)
        button(view, "다시 시도").performClick()
        button(view, "다시 시도").performClick()
        assertEquals(1, retries)
    }

    fun testHeldVehicleCardUsesProvidedRecordsAndOnlyOpensDelivery() = runOnUiTest {
        val events = mutableListOf<GarageEvent>()
        val view = GarageView(instrumentation.targetContext, events::add)
        val base = GaragePreviewStates.normal
        val car = base.data.vehicles.first().vehicle
        val held = HeldVehiclePresentation(car, "보관 시험 차종", "1번", EpochMillis(86_400_000))
        val available = base.copy(data = base.data.copy(heldVehicles = ContractResult.Success(listOf(held))))
        val openLabel = "탁송에서 보관 차량 확인"
        view.render(available, true)
        assertTrue(texts(view).contains("임시 보관함 1대"))
        assertTrue(texts(view).any { it.startsWith("보관 시험 차종 1번") })
        assertTrue(texts(view).contains("보관 기한 1970-01-02 09:00 (한국시간)"))
        val oldOpen = button(view, openLabel)
        oldOpen.performClick()
        assertEquals(listOf(GarageEvent.OpenHeldDeliveries), events)

        view.render(base.copy(data = base.data.copy(heldVehicles = ContractResult.Success(emptyList()))), true)
        oldOpen.performClick()
        assertFalse(texts(view).any { it.contains("임시 보관") || it == openLabel })

        view.render(base.copy(data = base.data.copy(heldVehicles = ContractResult.Failure(ContractError.STORAGE_FAILURE))), true)
        assertTrue(texts(view).contains("임시 보관 차량을 확인할 수 없어요"))
        assertFalse(texts(view).any { it == openLabel || it.contains("0대") && it.startsWith("임시") })

        view.render(available, false)
        oldOpen.performClick()
        assertFalse(texts(view).any { it.contains("임시 보관") || it == openLabel })
        assertEquals(listOf(GarageEvent.OpenHeldDeliveries), events)
    }

    fun testCollectionKeepsOwnershipUnlockMissingAndEmptyDistinct() = runOnUiTest {
        val view = CollectionView(instrumentation.targetContext) {}
        val content = GaragePreviewStates.collection
        val casper = content.entries.take(3).map { it.copy(unlockShopLevel = null) }
        view.render(content.copy(entries = casper + content.entries.drop(4)), true)

        assertTrue(texts(view).contains("차량 도감"))
        view.showHeading = false
        view.render(content.copy(entries = casper + content.entries.drop(4)), true)
        val labels = texts(view)
        assertFalse(labels.contains("차량 도감"))
        assertTrue(labels.contains("수집 20 / 79칸"))
        assertTrue(labels.count { it == "보유 이력 있음" } >= 1)
        // 한 목록에 모든 차종이 나오므로 개수는 최소 조건으로 본다
        assertTrue(labels.count { it == "발견 · 보유 이력 없음" } >= 1)
        assertTrue(labels.count { it == "미발견" } > 1) // 상태 필터 1개와 도감 칸들
        assertTrue(labels.contains("가게 Lv1 해금"))
        assertFalse(labels.any { it.startsWith("잠김") })

        assertNull("도감은 페이지 넘기기 대신 한 목록을 스크롤한다", view.findViewWithTag<Button>("collection:next"))
        assertTrue(texts(view).contains("해금 정보 확인 불가"))
        assertTrue(descendants(view).any {
            it.contentDescription == "캐스퍼 전설 · 수집 기록 확인 불가"
        })

        view.render(GaragePreviewStates.emptyCollection, true)
        assertTrue(texts(view).contains("도감 데이터가 없어요"))
    }

    fun testCollectionFilterShowsMatchesAndNoMatches() = runOnUiTest {
        val view = CollectionView(instrumentation.targetContext) {}
        val preview = GaragePreviewStates.collection
        val input = preview.copy(entries = preview.entries.mapIndexed { index, cell ->
            if (index >= 8 && cell.entry.state == CollectionState.DISCOVERED)
                cell.copy(entry = cell.entry.copy(state = CollectionState.UNDISCOVERED))
            else cell
        })
        view.render(input, true)
        view.findViewWithTag<Button>("collection:filter:DISCOVERED").performClick()
        assertTrue(view.findViewWithTag<Button>("collection:filter:DISCOVERED").isSelected)
        assertTrue(texts(view).containsAll(listOf("베뉴", "보유 이력 있음", "전설")))

        val noDiscoveries = input.entries.filterNot { it.entry.state == CollectionState.DISCOVERED }
        view.render(input.copy(entries = noDiscoveries), true)
        assertTrue(texts(view).contains("이 상태에 해당하는 차종이 없어요"))
        view.findViewWithTag<Button>("collection:filter:all").performClick()
        assertFalse(texts(view).contains("이 상태에 해당하는 차종이 없어요"))
    }

    fun testCollectionDiscoveredStatusFitsAtLargeFontScale() = runOnUiTest {
        val base = instrumentation.targetContext
        val config = Configuration(base.resources.configuration).apply { fontScale = 2f }
        val context = base.createConfigurationContext(config)
        val view = CollectionView(context) {}
        view.render(GaragePreviewStates.collection, true)
        val density = context.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)

        val status = context.getString(R.string.garage_discovered)
        val label = descendants(view).filterIsInstance<TextView>().first { it.text.toString() == status }
        val layout = checkNotNull(label.layout)
        assertTrue("큰 글자에서 상태 문구가 여러 줄이어야 합니다", layout.lineCount > 1)
        val lastLine = layout.lineCount - 1
        assertEquals(status.length, layout.getLineEnd(lastLine))
        assertEquals(0, layout.getEllipsisCount(lastLine))
        assertTrue("마지막 줄이 문구 영역 안에 있어야 합니다",
            label.totalPaddingTop + layout.getLineBottom(lastLine) <= label.height - label.totalPaddingBottom)
        assertTrue("셀 안에 문구 전체가 들어가야 합니다", label.bottom <= (label.parent as View).height)
        val scroll = view.findViewWithTag<ScrollView>("collection:scroll")
        assertTrue("큰 글자에서도 도감 목록이 창 안에서 스크롤되어야 합니다", scroll.bottom <= view.height)
    }

    fun testCollectionListsEveryVehicleAtNarrowAndWideSizes() = runOnUiTest {
        val base = instrumentation.targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = 1f })
        val view = CollectionView(context) {}
        fun layout(widthDp: Int, heightDp: Int) {
            val density = context.resources.displayMetrics.density
            val width = (widthDp * density).toInt()
            val height = (heightDp * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
        view.render(GaragePreviewStates.collection, true)
        for ((w, h) in listOf(900 to 480, 320 to 640, 900 to 340)) {
            layout(w, h)
            assertTrue(texts(view).containsAll(listOf("캐스퍼", "베뉴", "코나", "아반떼")))
            assertTrue(descendants(view).any { it.contentDescription == "코나 전설 · 미발견" })
            assertNull(view.findViewWithTag<Button>("collection:next"))
        }
    }
}
