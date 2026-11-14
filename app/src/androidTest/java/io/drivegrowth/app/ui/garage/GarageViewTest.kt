package io.drivegrowth.app.ui.garage

import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
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
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        val host = instrumentation.startActivitySync(
            Intent(context, CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options,
        )
        try {
            lateinit var view: GarageView
            val events = mutableListOf<GarageEvent>()
            val state = GaragePreviewStates.normal
            val first = state.data.vehicles.first().vehicle
            val sale = GarageEvent.RequestAction(first.ownedVehicleId, first.revision, VehicleAction.SELL)
            runOnUiTest {
                val config = Configuration(host.resources.configuration).apply { fontScale = 1f }
                view = GarageView(host.createConfigurationContext(config), events::add)
                val root = FrameLayout(host)
                root.addView(view, FrameLayout.LayoutParams(0, 0))
                host.setContentView(root)
                view.render(state, true)
            }

            fun resize(widthDp: Int) {
                var width = 0
                var height = 0
                runOnUiTest {
                    val density = view.resources.displayMetrics.density
                    width = (widthDp * density).toInt()
                    height = (600 * density).toInt()
                    view.layoutParams = FrameLayout.LayoutParams(width, height)
                }
                val deadline = SystemClock.uptimeMillis() + 5000L
                var observed = ""
                while (SystemClock.uptimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    var ready = false
                    runOnUiTest {
                        observed = "${view.width}×${view.height}px, attached=${view.isAttachedToWindow}, " +
                            "layoutRequested=${view.isLayoutRequested}"
                        ready = view.isAttachedToWindow && view.isLaidOut && !view.isLayoutRequested &&
                            view.width == width && view.height == height
                    }
                    if (ready) {
                        // onSizeChanged가 게시한 화면 교체와 그 뒤의 layout을 비운다.
                        instrumentation.waitForIdleSync()
                        return
                    }
                    SystemClock.sleep(20L)
                }
                fail("차고 ${widthDp}×600dp (${width}×${height}px) 배치 대기 시간 초과 · $observed")
            }

            resize(1200)
            runOnUiTest { assertTrue(view.findViewWithTag<Button>(first.ownedVehicleId).performClick()) }
            instrumentation.waitForIdleSync()
            runOnUiTest {
                val list = view.findViewWithTag<ScrollView>("garage:list")
                val detail = view.findViewWithTag<ScrollView>("garage:detail")
                assertTrue(list.width > 0 && detail.width > 0)
                assertTrue(list.right < detail.left)
                val selected = list.findViewWithTag<Button>(first.ownedVehicleId)
                assertTrue(selected.isSelected)
                val selectedCard = selected.parent as View
                val selectedLabel = view.context.getString(R.string.garage_selected)
                assertTrue(selectedLabel in texts(selectedCard))
                assertEquals(1, texts(list).count { it == selectedLabel })
                assertTrue(button(detail, "판매 조건 확인").performClick())
                assertFalse(view.findViewWithTag<Button>(first.ownedVehicleId).isEnabled)
                assertEquals(listOf(GarageEvent.SelectVehicle(first.ownedVehicleId), sale), events)
            }

            resize(480)
            runOnUiTest {
                assertNull(view.findViewWithTag<ScrollView>("garage:list"))
                assertNotNull(view.findViewWithTag<ScrollView>("garage:detail"))
                assertEquals(first.ownedVehicleId, view.selectedVehicleId)
                assertEquals(listOf(GarageEvent.SelectVehicle(first.ownedVehicleId), sale), events)
            }

            resize(1200)
            runOnUiTest {
                val list = view.findViewWithTag<ScrollView>("garage:list")
                val detail = view.findViewWithTag<ScrollView>("garage:detail")
                assertTrue(list.right < detail.left)
                val selected = list.findViewWithTag<Button>(first.ownedVehicleId)
                assertTrue(selected.isSelected)
                assertFalse(selected.isEnabled)
                assertEquals(first.ownedVehicleId, view.selectedVehicleId)
                assertEquals(listOf(GarageEvent.SelectVehicle(first.ownedVehicleId), sale), events)
            }
        } finally {
            runOnUiTest { host.finish() }
            instrumentation.waitForIdleSync()
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
        assertEquals(1, labels.count { it == "보유 이력 있음" })
        assertEquals(1, labels.count { it == "발견 · 보유 이력 없음" })
        assertEquals(3, labels.count { it == "미발견" }) // 상태 필터 1개와 도감 칸 2개
        assertTrue(labels.contains("가게 Lv1 해금"))
        assertFalse(labels.any { it.startsWith("잠김") })

        val next = view.findViewWithTag<Button>("collection:next")
        repeat(19) { view.findViewWithTag<Button>("collection:next").performClick() }
        assertFalse(view.findViewWithTag<Button>("collection:next").isEnabled)
        next.performClick() // 마지막 페이지에서 다음 버튼을 눌러도 범위를 벗어나지 않는다.
        assertEquals("20 / 20", collectionPage(view))
        assertTrue(texts(view).contains("해금 정보 확인 불가"))
        assertTrue(descendants(view).any {
            it.contentDescription == "캐스퍼 전설 · 수집 기록 확인 불가"
        })

        view.render(GaragePreviewStates.emptyCollection, true)
        assertTrue(texts(view).contains("도감 데이터가 없어요"))
        assertNull(view.findViewWithTag<TextView>("collection:page"))
    }

    fun testCollectionFilterKeepsEachPageAndShowsNoMatches() = runOnUiTest {
        val view = CollectionView(instrumentation.targetContext) {}
        val preview = GaragePreviewStates.collection
        val input = preview.copy(entries = preview.entries.mapIndexed { index, cell ->
            if (index >= 8 && cell.entry.state == CollectionState.DISCOVERED)
                cell.copy(entry = cell.entry.copy(state = CollectionState.UNDISCOVERED))
            else cell
        })
        view.render(input, true)
        view.findViewWithTag<Button>("collection:next").performClick()
        view.findViewWithTag<Button>("collection:next").performClick()
        assertEquals("3 / 20", collectionPage(view))

        val discovered = view.findViewWithTag<Button>("collection:filter:DISCOVERED")
        discovered.performClick()
        assertTrue(view.findViewWithTag<Button>("collection:filter:DISCOVERED").isSelected)
        assertEquals("1 / 2", collectionPage(view))
        view.findViewWithTag<Button>("collection:next").performClick()

        view.findViewWithTag<Button>("collection:filter:all").performClick()
        discovered.performClick()
        assertEquals("3 / 20", collectionPage(view))
        view.findViewWithTag<Button>("collection:filter:DISCOVERED").performClick()
        assertEquals("2 / 2", collectionPage(view))
        assertTrue(texts(view).containsAll(listOf("베뉴", "보유 이력 있음", "전설")))

        val noDiscoveries = input.entries.filterNot { it.entry.state == CollectionState.DISCOVERED }
        view.render(input.copy(entries = noDiscoveries), true)
        assertTrue(texts(view).contains("이 상태에 해당하는 차종이 없어요"))
        assertNull(view.findViewWithTag<TextView>("collection:page"))
        view.findViewWithTag<Button>("collection:filter:all").performClick()
        assertEquals("3 / 20", collectionPage(view))
    }

    fun testCollectionReusesOnlyThePreviousPageUntilSupplyChanges() = runOnUiTest {
        val context = instrumentation.targetContext
        val view = CollectionView(context) {}
        val content = GaragePreviewStates.collection
        view.render(content, true)
        fun row(name: String) = descendants(view).filterIsInstance<TextView>()
            .single { it.text.toString() == name }
        fun turn(direction: String) {
            assertTrue(view.findViewWithTag<Button>("collection:$direction").performClick())
        }

        val first = row("캐스퍼")
        turn("next")
        val second = row("베뉴")
        turn("previous")
        assertSame(first, row("캐스퍼"))
        turn("next")
        assertSame(second, row("베뉴"))

        turn("next")
        turn("previous")
        assertSame(second, row("베뉴"))
        turn("previous")
        val firstAfterEviction = row("캐스퍼")
        assertNotSame(first, firstAfterEviction)

        val changed = content.copy(entries = content.entries.map { entry ->
            if (entry.name == "캐스퍼") entry.copy(name = "변경된 캐스퍼") else entry
        })
        view.render(changed, true)
        assertEquals("1 / 20", collectionPage(view))
        assertNotSame(firstAfterEviction, row("변경된 캐스퍼"))
        turn("next")
        turn("previous")
        assertFalse(texts(view).contains("캐스퍼"))
        assertEquals("변경된 캐스퍼", row("변경된 캐스퍼").text.toString())
    }

    private fun collectionPage(view: CollectionView) =
        view.findViewWithTag<TextView>("collection:page").text.toString()

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
        val next = view.findViewWithTag<Button>("collection:next")
        val pager = next.parent as View
        assertTrue("큰 글자에서도 다음 버튼의 터치 높이를 유지해야 합니다", next.height >= (48 * density).toInt())
        assertTrue("페이지 조작이 창 아래로 잘리지 않아야 합니다", pager.bottom <= view.height)
        assertFalse("페이지 조작은 본문 스크롤과 분리해야 합니다",
            descendants(view).filterIsInstance<ScrollView>().any { next in descendants(it) })
    }

    fun testCollectionHeadersAndCellsShareSteppedBordersAndKeepRarityColors() = runOnUiTest {
        val base = instrumentation.targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { fontScale = 1f })
        val view = CollectionView(context) {}
        view.render(GaragePreviewStates.collection, true)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val width = View.MeasureSpec.makeMeasureSpec(dp(900), View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(dp(480), View.MeasureSpec.EXACTLY)
        view.measure(width, height)
        view.layout(0, 0, View.MeasureSpec.getSize(width), View.MeasureSpec.getSize(height))

        fun assertBorderAndFill(drawable: Drawable, expectedFill: Int) {
            val bitmap = Bitmap.createBitmap(dp(72), dp(40), Bitmap.Config.ARGB_8888)
            val previousBounds = drawable.copyBounds()
            try {
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
                assertEquals("모서리는 계단처럼 비워야 합니다", Color.TRANSPARENT, bitmap.getPixel(0, 0))
                assertEquals("윗변에는 같은 갈색 테두리를 씁니다", 0xff916340.toInt(), bitmap.getPixel(bitmap.width / 2, 0))
                assertEquals("등급마다 기존 채움색을 유지합니다", expectedFill, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            } finally {
                drawable.bounds = previousBounds
                bitmap.recycle()
            }
        }

        val colors = mapOf("일반" to 0xfff7e1ac.toInt(), "고급" to 0xffe4edbf.toInt(),
            "희귀" to 0xffdbeaf4.toInt(), "전설" to 0xffffdf8c.toInt())
        val nodes = descendants(view)
        for ((rarity, color) in colors) {
            val header = nodes.filterIsInstance<TextView>().single { it.text.toString() == rarity }
            val cell = nodes.single { it.contentDescription?.startsWith("캐스퍼 $rarity ·") == true }
            assertBorderAndFill(checkNotNull(header.background), color)
            assertBorderAndFill(checkNotNull(cell.background), color)
        }
    }

    fun testCollectionRepaginatesAroundSameVehicleAtNarrowAndWideSizes() = runOnUiTest {
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
        layout(900, 480)
        assertEquals("1 / 10", collectionPage(view))
        assertTrue(texts(view).containsAll(listOf("캐스퍼", "베뉴", "일반", "고급", "희귀", "전설")))
        view.findViewWithTag<Button>("collection:next").performClick()
        layout(900, 480)
        assertEquals("2 / 10", collectionPage(view))
        assertTrue(texts(view).containsAll(listOf("코나", "아반떼")))

        layout(320, 640)
        assertEquals("3 / 20", collectionPage(view))
        assertTrue(texts(view).contains("코나"))
        assertFalse(texts(view).contains("아반떼"))
        assertTrue(descendants(view).any { it.contentDescription == "코나 전설 · 미발견" })
        layout(900, 340)
        assertEquals("3 / 20", collectionPage(view))
        layout(900, 480)
        assertEquals("2 / 10", collectionPage(view))
        assertTrue(texts(view).containsAll(listOf("코나", "아반떼")))
    }
}
