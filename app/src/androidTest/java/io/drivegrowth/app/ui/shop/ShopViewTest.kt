package io.drivegrowth.app.ui.shop

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.test.InstrumentationTestCase
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.R
import io.drivegrowth.app.data.ShopState
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.app.host.facility.FacilityFacts
import io.drivegrowth.app.host.facility.FacilityHostMapper
import io.drivegrowth.app.ui.garage.CollectionScrollTestActivity
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.rental.Facility
import io.drivegrowth.core.rental.FacilityOffer
import io.drivegrowth.core.rental.ShopProgress

/** 장치 슬롯 배정 후 실행할 실제 Android 위젯 시험. fixture는 실제 저장 시험이 아니다. */
@Suppress("DEPRECATION")
class ShopViewTest : InstrumentationTestCase() {
    private fun <T> onUi(block: () -> T): T {
        var value: T? = null; var failure: Throwable? = null
        instrumentation.runOnMainSync { try { value = block() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun text(view: View) = views(view).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun button(view: View, tag: String): Button = view.findViewWithTag(tag)
    private fun hudLabels(view: ShopView): List<TextView> =
        views(checkNotNull(view.findViewWithTag<View>("shop:map-hud"))).filterIsInstance<TextView>()

    private fun assertCapacity(view: ShopView, owned: Int, capacity: Int) {
        val badge = hudLabels(view).single { it.text.toString().startsWith("P ") }
        assertEquals("P $owned / $capacity", badge.text.toString())
        assertEquals("보유 / 공간 · $owned / $capacity", badge.contentDescription.toString())
    }

    private fun assertAccessibleBalance(view: ShopView, amount: String) {
        val badge = view.findViewWithTag<TextView>("shop:map-balance")
        assertEquals(amount, badge.contentDescription.toString())
    }

    private fun openVehicleList(view: ShopView) {
        view.openMapTools()
        assertTrue(button(view, "shop:map-vehicles").performClick())
    }
    private fun layout(view: View, width: Int, height: Int) = onUi { measureAndLayout(view, width, height) }
    private fun measureAndLayout(view: View, width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        val w = (width * density).toInt(); val h = (height * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    fun testCarWashConditionAndCoreFactsInBothLayouts() {
        val preview = ShopPreviewStates.normal()
        val state = preview.copy(facilities = preview.facilities.map {
            if (it.facility == Facility.CAR_WASH) it.copy(option = it.option.copy(trial = true)) else it
        })
        val view = onUi { ShopView(instrumentation.targetContext) {}.apply { render(state, true) } }
        for ((w, h) in listOf(840 to 1184, 1360 to 948, 480 to 500)) {
            layout(view, w, h)
            // 미부착 위젯은 post를 실행하지 않을 수 있어 실제 크기를 적용한 뒤 다시 그린다.
            onUi { view.render(state, true) }; layout(view, w, h)
            onUi {
                assertCapacity(view, 2, 5)
                view.showMapPanel(ShopMapTarget.FacilityLot(Facility.CAR_WASH))
                measureAndLayout(view, w, h)
                val shown = text(view)
                assertTrue(shown.contains("세차장")); assertTrue(shown.contains("가게 레벨 3 필요 · 현재 레벨 2"))
                assertFalse(shown.contains("조정 가능한 시험값"))
                assertFalse(shown.contains("규칙 ·"))
                assertFalse(shown.contains(checkNotNull(state.expansion?.ruleVersion).value))
                val label = views(view).filterIsInstance<TextView>().single { it.text.contains("가게 레벨 3 필요") }
                assertTrue(label.width > 0); assertTrue(label.height > 0)
                assertEquals(0, label.layout.getEllipsisCount(0))
                assertTrue(view.handleBack())
            }
        }
    }

    fun testLockedCarWashWithActualMapperIsReadableAfterScrollInBothNarrowWindows() {
        val events = mutableListOf<ShopEvent>()
        val offer = FacilityOffer(Facility.CAR_WASH, RuleVersion("facility-window-test"), true, GameMoney(1_000))
        val facts = FacilityFacts(GameIdentity(ProfileId("fixture"), InputSource.SIMULATION), ContentVersion("fixture"),
            ShopState(ShopProgress(2, 0, 2), 5, emptySet()), GameMoney(2_400),
            emptyList(), emptyMap(), emptySet(), mapOf(Facility.CAR_WASH to offer))
        val state = FacilityHostMapper.merge(ShopPreviewStates.normal(), facts, true)
        val view = onUi { ShopView(instrumentation.targetContext, events::add).apply { render(state, true) } }
        for ((width, height) in listOf(620 to 360, 320 to 568)) {
            layout(view, width, height)
            onUi { view.render(state, true); view.showMapPanel(ShopMapTarget.FacilityLot(Facility.CAR_WASH)) }
            layout(view, width, height)
            onUi {
                val scroll = views(view).filterIsInstance<ScrollView>().single()
                val condition = views(view).filterIsInstance<TextView>().single { it.text.contains("가게 레벨 2/3") }
                val labels = views(scroll).filterIsInstance<TextView>()
                val title = labels.first { it.text.toString() == "세차장" }
                val locked = labels.single { it.text.toString() == "잠김" }
                fun inScroll(child: View): Rect = Rect(0, 0, child.width, child.height).apply {
                    scroll.offsetDescendantRectToMyCoords(child, this)
                }
                for (label in listOf(title, locked, condition)) {
                    scroll.scrollTo(0, inScroll(label).top)
                    val viewport = Rect(
                        scroll.scrollX + scroll.paddingLeft,
                        scroll.scrollY + scroll.paddingTop,
                        scroll.scrollX + scroll.width - scroll.paddingRight,
                        scroll.scrollY + scroll.height - scroll.paddingBottom,
                    )
                    val description = "$width × $height, ${label.text}"
                    for (ancestor in generateSequence<View>(label) { it.parent as? View }) {
                        assertTrue("$description: ${ancestor.javaClass.simpleName} layout", ancestor.isLaidOut)
                        assertEquals("$description: ${ancestor.javaClass.simpleName} visibility", View.VISIBLE, ancestor.visibility)
                    }
                    val bounds = inScroll(label)
                    assertFalse("$description: empty bounds", bounds.isEmpty)
                    assertTrue("$description: $bounds is outside $viewport", viewport.contains(bounds))

                    val unclipped = Rect(0, 0, label.width, label.height).apply {
                        view.offsetDescendantRectToMyCoords(label, this)
                    }
                    val visible = Rect(0, 0, label.width, label.height)
                    assertTrue(description, (label.parent as ViewGroup).getChildVisibleRect(label, visible, null))
                    assertEquals(description, unclipped, visible)
                    for (line in 0 until label.layout.lineCount) assertEquals(description, 0, label.layout.getEllipsisCount(line))
                }
                assertNull(view.findViewWithTag<Button>("shop:map-confirm"))
                assertTrue(events.isEmpty())
            }
        }
    }

    fun testModeButtonsExposeSelectionAndRedrawTheSceneWithoutChangingFacts() {
        val events = mutableListOf<ShopEvent>()
        val state = ShopPreviewStates.normal()
        val view = onUi { ShopView(instrumentation.targetContext, events::add).apply { render(state, true) } }
        fun bitmap() = onUi {
            val scene = view.findViewWithTag<ShopSceneView>("shop:scene")
            Bitmap.createBitmap(scene.width, scene.height, Bitmap.Config.ARGB_8888).apply { scene.draw(Canvas(this)) }
        }
        layout(view, 620, 360)
        val overall = bitmap()
        try {
            onUi {
                view.openMapTools()
                assertTrue(button(view, "shop:mode:OVERALL").isSelected)
                assertFalse(button(view, "shop:mode:VEHICLES").isSelected)
                assertTrue(button(view, "shop:mode:VEHICLES").performClick())
                assertTrue(view.handleBack())
            }
            layout(view, 620, 360)
            val vehicles = bitmap()
            try {
                assertFalse("전경과 차량 중심 화면은 다르게 그려야 한다", overall.sameAs(vehicles))
            } finally { vehicles.recycle() }
            onUi {
                assertEquals(ShopMode.VEHICLES, view.uiState.mode)
                assertAccessibleBalance(view, "2,400 게임머니")
                view.openMapTools()
                assertTrue(button(view, "shop:mode:VEHICLES").isSelected)
                assertFalse(button(view, "shop:mode:OVERALL").isSelected)
                assertTrue(button(view, "shop:mode:OVERALL").performClick())
                assertTrue(view.handleBack())
            }
            layout(view, 620, 360)
            val returned = bitmap()
            try {
                assertTrue(overall.sameAs(returned))
                onUi {
                    view.openMapTools()
                    assertTrue(button(view, "shop:mode:OVERALL").isSelected)
                }
            } finally { returned.recycle() }
        } finally { overall.recycle() }
        assertTrue(events.isEmpty())
    }

    fun testDecorationShortcutOpensWithoutActivatingUntilExplicitRequest() {
        val events = mutableListOf<ShopEvent>()
        var activations = 0
        onUi {
            val view = ShopView(instrumentation.targetContext, events::add).apply {
                onActivateDecoration = { activations++ }
                render(ShopPreviewStates.normal(), true)
            }
            view.openDecorationTools()
            button(view, "shop:map-close")
            assertEquals(0, activations)
            assertTrue(view.handleBack())
            assertEquals(0, activations)
            assertTrue(events.isEmpty())
            view.openMapTools()
            assertTrue(button(view, "decoration:activate").performClick())
            assertEquals(1, activations)
        }
    }

    fun testPreviewCancelAndFailureKeepCommittedFacts() {
        val events = mutableListOf<ShopEvent>()
        val view = onUi { ShopView(instrumentation.targetContext, events::add).apply { render(ShopPreviewStates.normal(), true) } }
        onUi {
            view.showMapPanel(ShopMapTarget.Decorations)
            button(view, "shop:map-preview:blue-sign").performClick()
            val name = view.context.getString(R.string.shop_scene_default_sign)
            val after = view.context.getString(R.string.shop_after, name)
            val before = view.context.getString(R.string.shop_before, name)
            assertTrue(text(view).contains(before))
            assertTrue(text(view).contains(after))
            assertFalse(text(view).contains("변경안 · 파란 가게"))
            assertTrue(text(view).contains("2,400 게임머니")); assertTrue(events.isEmpty())
            button(view, "shop:map-close").performClick(); assertFalse(text(view).contains(after))
            view.showMapPanel(ShopMapTarget.Decorations)
            button(view, "shop:map-preview:blue-sign").performClick(); button(view, "shop:map-confirm").performClick()
            val request = (events.single() as ShopEvent.Submit).request
            view.complete(request, ContractResult.Failure(ContractError.STORAGE_FAILURE))
            assertTrue(text(view).contains("처리하지 못했어요. 다시 시도해 주세요."))
            assertFalse(text(view).contains("STORAGE_FAILURE"))
            assertFalse(text(view).contains(after))
            assertAccessibleBalance(view, "2,400 게임머니")
        }
    }

    fun testGateAndStaleDetachedButtonsCannotSubmit() {
        val events = mutableListOf<ShopEvent>(); val state = ShopPreviewStates.normal()
        val view = onUi { ShopView(instrumentation.targetContext, events::add).apply { render(state, true) } }
        onUi {
            view.showMapPanel(ShopMapTarget.Expansion)
            val oldConfirm = button(view, "shop:map-confirm")
            view.render(state, false); oldConfirm.performClick()
            assertTrue(events.isEmpty()); assertFalse(text(view).contains("2,400"))
            assertEquals(0, views(view).filterIsInstance<Button>().size)
            view.render(state.copy(revision = Revision(13)), true); oldConfirm.performClick()
            assertTrue(events.isEmpty()); assertNull(view.uiState.selection)
        }
    }

    fun testPendingRestoreAndCallbackNeedExternalCommittedRefresh() {
        val events = mutableListOf<ShopEvent>(); val state = ShopPreviewStates.normal()
        val view = onUi { ShopView(instrumentation.targetContext, events::add).apply { render(state, true) } }
        onUi {
            view.showMapPanel(ShopMapTarget.Expansion)
            button(view, "shop:map-confirm").performClick()
            button(view, "shop:map-confirm").performClick(); view.render(state, true)
            assertEquals(1, events.size)
            val restored = ShopView(instrumentation.targetContext, events::add)
            restored.restoreUiState(view.uiState); restored.render(state, true)
            assertEquals(1, events.size)
            assertTrue(text(restored).contains("요청 처리 중"))
            val request = restored.uiState.pending!!
            restored.complete(request.copy(requestId = RequestId("unrelated")), ContractResult.Success(Unit)); assertNotNull(restored.uiState.pending)
            restored.complete(request, ContractResult.Success(Unit))
            assertCapacity(restored, 2, 5)
            assertTrue(text(restored).contains("반영하고 있어요…"))
            restored.render(ShopScreenState.Loading, true)
            assertTrue(text(restored).contains("반영하고 있어요…"))
            restored.render(state.copy(revision = Revision(13), level = 3, capacity = 7, balance = GameMoney(900)), true)
            assertCapacity(restored, 2, 7); assertAccessibleBalance(restored, "900 게임머니")
            assertFalse(text(restored).contains("반영하고 있어요…"))
        }
    }

    fun testMissingFactsAndLoadStatesNeverShowInventedZeroOrSuccess() {
        val events = mutableListOf<ShopEvent>()
        val view = onUi { ShopView(instrumentation.targetContext, events::add) }
        onUi {
            view.render(ShopPreviewStates.missing(), true)
            assertAccessibleBalance(view, "정보 미제공")
            assertEquals("P — / —", hudLabels(view).single { it.text.toString().startsWith("P ") }.text.toString())
            view.showMapPanel(ShopMapTarget.Decorations)
            assertTrue(text(view).contains("승인된 장식·배치 규칙 미제공"))
            assertFalse(text(view).contains("0 게임머니"))
            view.render(ShopScreenState.Loading, true); assertTrue(text(view).contains("불러오는 중"))
            view.render(ShopScreenState.Empty("프로필 확인 필요"), true); assertTrue(text(view).contains("프로필 확인 필요"))
            view.render(ShopScreenState.Failure("저장 조회 실패"), true)
            button(view, "shop:retry").performClick(); button(view, "shop:retry").performClick(); assertEquals(1, events.size)
            view.render(ShopScreenState.Unsupported("미지원 공급자"), true)
            assertFalse(views(view).filterIsInstance<Button>().any { it.tag == "shop:retry" })
        }
    }

    fun testModeSwitchBackAndLockedFacility() {
        val events = mutableListOf<ShopEvent>()
        val view = onUi { ShopView(instrumentation.targetContext, events::add).apply { render(ShopPreviewStates.normal(), true) } }
        onUi {
            view.showMapPanel(ShopMapTarget.FacilityLot(Facility.FUEL_STATION))
            assertNull(view.findViewWithTag<Button>("shop:map-confirm"))
            assertNull(view.uiState.selection)
            assertTrue(view.handleBack())
            view.openMapTools()
            button(view, "shop:mode:VEHICLES").performClick(); assertEquals(ShopMode.VEHICLES, view.uiState.mode)
            view.showMapPanel(ShopMapTarget.Decorations)
            button(view, "shop:map-preview:blue-sign").performClick(); assertTrue(view.handleBack()); assertNull(view.uiState.selection)
            assertNotNull(view.findViewWithTag<View>("shop:map-overlay"))
            assertTrue(view.handleBack())
            assertFalse(view.handleBack()); assertTrue(events.isEmpty())
        }
    }
    fun testFirstRestoreAfterRenderInvalidatesOldContentAndButtons() = onUi {
        val events = mutableListOf<ShopEvent>()
        val old = ShopPreviewStates.normal().copy(revision = Revision(3))
        val latest = old.copy(revision = Revision(5),capacity = 7,balance = GameMoney(900))
        val saved = ShopInteraction({}).apply { render(latest,true) }.uiState
        val view = ShopView(instrumentation.targetContext,events::add)
        view.render(old,true); view.showMapPanel(ShopMapTarget.Expansion)
        val stale = button(view,"shop:map-confirm")
        view.restoreUiState(saved)
        assertTrue(text(view).contains("불러오는 중")); assertFalse(text(view).contains("2,400"))
        stale.performClick(); assertTrue(events.isEmpty())
        view.render(old,true); assertFalse(text(view).contains("2,400"))
        view.render(latest,false); assertTrue(views(view).none { it is Button })
        view.render(latest,true); assertAccessibleBalance(view, "900 게임머니")
        view.showMapPanel(ShopMapTarget.Expansion); button(view,"shop:map-confirm").performClick()
        assertEquals(Revision(5),(events.single() as ShopEvent.Submit).request.selection.revision)
    }


    fun testWorldCoversLandscapeAndPortraitWithVehicleInMap() {
        val state = ShopPreviewStates.normal()
        val id = state.vehicles.first().id
        for ((width, height) in listOf(360 to 720, 480 to 340, 480 to 360, 936 to 416)) {
            val view = onUi {
                ShopView(instrumentation.targetContext) {}.apply {
                    onOpenVehicle = { _, _ -> }
                    updateMapHud("영업 상태를 불러오는 중", onSettings = {})
                    render(state, true)
                }
            }
            layout(view, width, height)
            onUi { view.render(state, true) }
            layout(view, width, height)
            onUi {
                val scene = view.findViewWithTag<ShopSceneView>("shop:scene")
                val vehicle = scene.findViewWithTag<View>("shop:map-vehicle:${id.value}")
                fun bounds(child: View) = Rect(0, 0, child.width, child.height).apply {
                    view.offsetDescendantRectToMyCoords(child, this)
                }
                val description = "${width}×${height}dp"
                val mapBounds = bounds(scene)
                assertTrue(description, mapBounds.left >= 0 && mapBounds.right <= view.width)
                assertEquals(description, Rect(0, 0, view.width, view.height), mapBounds)
                assertTrue(description, mapBounds.intersects(bounds(vehicle).left, bounds(vehicle).top,
                    bounds(vehicle).right, bounds(vehicle).bottom))
                assertNull("기본 지도에 보조 메뉴를 상시 노출하지 않는다", view.findViewWithTag<View>("shop:mode:VEHICLES"))
                val hud = view.findViewWithTag<View>("shop:map-hud")
                assertTrue(description, mapBounds.contains(bounds(hud)))
                val settings = bounds(button(view, "shop:map-settings"))
                val balance = bounds(view.findViewWithTag<View>("shop:map-balance"))
                val parking = bounds(hudLabels(view).single { it.text.toString().startsWith("P ") })
                assertTrue(description, mapBounds.contains(settings) && mapBounds.contains(balance))
                assertTrue(description, balance.right <= settings.left)
                assertTrue(description, parking.right <= balance.left)
                assertNull(view.findViewWithTag<View>("shop:controls-scroll"))
            }
        }
    }

    fun testWorldSignsOpenPanelsAndNotifyHostWithoutSubmitting() = onUi {
        val modalStates = mutableListOf<Boolean>()
        var settings = 0
        val state = ShopPreviewStates.normal()
        val view = ShopView(instrumentation.targetContext) { fail("지도 탐색에서 거래를 요청했습니다") }
        view.onMapModalChanged = modalStates::add
        view.updateMapHud("대여 대기", onSettings = { settings++ })
        view.render(state, true)
        measureAndLayout(view, 936, 500)

        val settingsButton = button(view, "shop:map-settings")
        assertEquals("설정", settingsButton.contentDescription)
        assertTrue(settingsButton.performClick())
        assertEquals(1, settings)

        val fuel = button(view, "shop:map-facility:FUEL_STATION")
        assertEquals(View.VISIBLE, fuel.visibility)
        assertTrue(fuel.performClick())
        assertNotNull(view.findViewWithTag<View>("shop:map-overlay"))
        assertEquals(listOf(true), modalStates)
        assertNull(view.findViewWithTag<View>("shop:map-confirm"))
        assertTrue(view.handleBack())
        assertEquals(listOf(true, false), modalStates)
        fuel.performClick()
        settingsButton.performClick()
        assertEquals(1, settings)
        assertNull(view.findViewWithTag<View>("shop:map-overlay"))

        measureAndLayout(view, 936, 500)
        val expansion = button(view, "shop:map-expansion")
        assertEquals(View.VISIBLE, expansion.visibility)
        assertTrue(expansion.top in 0 until view.height)
        assertTrue(expansion.performClick())
        assertNotNull(view.findViewWithTag<View>("shop:map-confirm"))
        view.render(state, false)
        assertEquals(listOf(true, false, true, false), modalStates)
        assertNull(view.findViewWithTag<View>("shop:scene"))
    }

    fun testSceneUsesAppWidthAndKeepsUnknownFactsInNarrowAndWideWindows() {
        val state = ShopPreviewStates.normal().copy(name = "긴 가게 이름과 확인해야 할 간판 문구", capacity = 6)
        val view = onUi { ShopView(instrumentation.targetContext) {}.apply { render(state,true) } }
        for (width in listOf(480,936,1360)) {
            layout(view,width,650);onUi { view.render(state,true) };layout(view,width,650)
            onUi {
                val scene = view.findViewWithTag<ShopSceneView>("shop:scene")
                assertTrue(scene.width > 0);assertTrue(scene.height > 0)
                assertEquals(view.width, scene.width)
                assertEquals(view.height, scene.height)
                val bitmap=android.graphics.Bitmap.createBitmap(scene.width,scene.height,android.graphics.Bitmap.Config.ARGB_8888)
                scene.draw(android.graphics.Canvas(bitmap))
                assertTrue("실제 전경 드로잉", bitmap.getPixel(scene.width / 2, scene.height / 2) !=
                    io.drivegrowth.app.ui.pixel.PixelStyle.background)
                bitmap.recycle()
            }
        }
        onUi {
            view.render(ShopPreviewStates.missing(),true)
            assertTrue(view.findViewWithTag<ShopSceneView>("shop:scene").contentDescription.contains("주차 공간 확인 중"))
            assertFalse(text(view).contains("0 게임머니"))
            view.render(state,false);assertNull(view.findViewWithTag<View>("shop:scene"))
        }
    }
    fun testFacilityMenuCardsShowSuppliedStateWithoutSendingPurchase() = onUi {
        val events = mutableListOf<ShopEvent>()
        val view = ShopView(instrumentation.targetContext, events::add)
        val state = ShopPreviewStates.normal()
        fun render(insurance: ShopAvailability?) {
            val facilities = state.facilities.mapNotNull { facility ->
                when (facility.facility) {
                    Facility.FUEL_STATION -> facility
                    Facility.CAR_WASH -> facility.copy(option = facility.option.copy(availability = ShopAvailability.INSTALLED))
                    Facility.INSURANCE -> insurance?.let { facility.copy(option = facility.option.copy(availability = it)) }
                }
            }
            view.render(state.copy(facilities = facilities), true)
            measureAndLayout(view, 320, 480)
        }
        render(ShopAvailability.AVAILABLE)
        view.openMapTools()
        measureAndLayout(view, 320, 480)

        val fuel = button(view, "shop:menu-facility:FUEL_STATION")
        assertTrue(fuel.contentDescription.endsWith(" · 잠김"))
        assertTrue(button(view, "shop:menu-facility:INSURANCE").contentDescription.endsWith(" · 건설 가능"))
        assertTrue(button(view, "shop:menu-facility:CAR_WASH").contentDescription.endsWith(" · 운영 중"))
        for (facility in Facility.entries) {
            val art = view.findViewWithTag<View>("shop:menu-art:${facility.name}")
            assertTrue(art.width > 0 && art.height > 0)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, art.importantForAccessibility)
        }
        val footer = button(view, "shop:map-sheet-close")
        val scroll = view.findViewWithTag<ScrollView>("shop:controls-scroll")
        assertFalse(footer in views(scroll))
        assertTrue(fuel.performClick())
        assertNotNull(view.findViewWithTag<View>("shop:map-overlay"))
        assertNull(view.findViewWithTag<View>("shop:map-confirm"))
        assertTrue(events.isEmpty())

        assertTrue(view.handleBack())
        render(null)
        view.openMapTools()
        assertTrue(button(view, "shop:menu-facility:INSURANCE").contentDescription.endsWith(" · 상태 확인 불가"))
        view.render(state, false)
        fuel.performClick()
        assertNull(view.findViewWithTag<View>("shop:map-sheet"))
        assertTrue(events.isEmpty())
    }

    fun testUnreportedCapacityDoesNotPaintLockedParkingSpaces() = onUi {
        val view = ShopView(instrumentation.targetContext) {}
        val state = ShopPreviewStates.normal().copy(vehicles = emptyList(), ownedCount = 0)
        val width = 1536
        val height = 1024
        fun parkingPixel(capacity: Int?): Int {
            view.render(state.copy(capacity = capacity), true)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val scene = view.findViewWithTag<ShopSceneView>("shop:scene")
            val (x, y) = townSlots[3]
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return try {
                scene.draw(Canvas(bitmap))
                bitmap.getPixel(x, y - 18)
            } finally { bitmap.recycle() }
        }

        val openParkingPixel = parkingPixel(townSlots.size)
        assertEquals(openParkingPixel, parkingPixel(null))
        assertFalse(openParkingPixel == parkingPixel(3))
    }

    fun testFacilityRecoveryPanelStaysReachableWhileHomeLoadsOrFails() = onUi {
        val context = instrumentation.targetContext
        val view = ShopView(context) {}
        val panel = Button(context).apply { tag = "facility:ack"; text = "결과 확인" }
        view.facilityPanel = panel
        view.render(ShopScreenState.Loading, true)
        assertSame(panel, view.findViewWithTag<Button>("facility:ack"))
        view.render(ShopScreenState.Failure("시험 조회 실패"), true)
        assertSame(panel, view.findViewWithTag<Button>("facility:ack"))
        view.render(ShopScreenState.Loading, false)
        assertNull(view.findViewWithTag<Button>("facility:ack"))
        view.render(ShopPreviewStates.normal(), true, null, focusRestRecovery = true)
        assertSame(panel, view.findViewWithTag<Button>("facility:ack"))
    }

    fun testFacilityRecoveryDismissesUntilNextRecovery() = onUi {
        val context = instrumentation.targetContext
        val state = ShopPreviewStates.normal()
        val view = ShopView(context) { fail("패널 닫기에서 거래 요청 발생") }
        val receipt = Button(context).apply { tag = "facility:ack" }
        view.facilityPanel = receipt

        view.render(state, true, null, focusRestRecovery = true)
        assertSame(receipt, view.findViewWithTag<Button>("facility:ack"))
        assertTrue(button(view, "shop:map-sheet-close").performClick())
        assertNull(view.findViewWithTag<Button>("facility:ack"))
        view.render(state, true, null, focusRestRecovery = true)
        assertNull(view.findViewWithTag<Button>("facility:ack"))
        assertFalse(view.handleBack())

        view.openMapTools()
        assertSame(receipt, view.findViewWithTag<Button>("facility:ack"))
        assertTrue(view.handleBack())
        assertNull(view.findViewWithTag<Button>("facility:ack"))

        view.render(state, true, null, focusRestRecovery = false)
        view.render(state, true, null, focusRestRecovery = true)
        assertSame(receipt, view.findViewWithTag<Button>("facility:ack"))
        assertTrue(view.handleBack())
        view.render(state, true, null, focusRestRecovery = true)
        assertNull(view.findViewWithTag<Button>("facility:ack"))
        assertFalse(view.handleBack())
    }

    fun testClosedOrReplacedFacilityMenuCannotActivateDecoration() = onUi {
        var activations = 0
        val state = ShopPreviewStates.normal()
        val view = ShopView(instrumentation.targetContext) {}
        view.onActivateDecoration = { activations++ }
        view.render(state, true)
        view.openMapTools()
        val closed = button(view, "decoration:activate")
        assertTrue(view.handleBack())
        closed.performClick()
        view.openMapTools()
        val replaced = button(view, "decoration:activate")
        view.render(state.copy(revision = Revision(state.revision.value + 1)), true)
        replaced.performClick()
        assertEquals(0, activations)
        button(view, "decoration:activate").performClick()
        assertEquals(1, activations)
    }

    fun testAttachedFacilityDetailsKeepScrollOnSameContentRender() {
        val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val events = mutableListOf<ShopEvent>()
        val fixture = ShopPreviewStates.normal()
        val state = fixture.copy(facilities = fixture.facilities.map {
            if (it.facility == Facility.FUEL_STATION) it.copy(option = it.option.copy(
                condition = "긴 시설 조건과 현재 달성 상태를 확인합니다. ".repeat(24),
                availability = ShopAvailability.AVAILABLE,
            )) else it
        })
        try {
            lateinit var view: ShopView
            onUi {
                val density = host.resources.displayMetrics.density
                view = ShopView(host, events::add)
                val root = FrameLayout(host)
                host.setContentView(root)
                root.addView(view, FrameLayout.LayoutParams((300 * density).toInt(), (240 * density).toInt()))
                view.render(state, true)
                view.showMapPanel(ShopMapTarget.FacilityLot(Facility.FUEL_STATION))
            }
            instrumentation.waitForIdleSync()
            val expectedScroll = onUi {
                assertTrue(view.isAttachedToWindow)
                val scroll = view.findViewWithTag<ScrollView>("shop:map-scroll")
                assertTrue(scroll.isLaidOut)
                assertTrue(scroll.canScrollVertically(1))
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                val position = scroll.scrollY
                assertTrue(position > 0)

                val currentConfirm = button(view, "shop:map-confirm")
                assertTrue(currentConfirm.isEnabled)
                view.render(state, true)
                assertSame(currentConfirm, button(view, "shop:map-confirm"))
                assertTrue(events.isEmpty())
                position
            }
            instrumentation.waitForIdleSync()
            onUi {
                val scroll = view.findViewWithTag<ScrollView>("shop:map-scroll")
                assertTrue(scroll.isAttachedToWindow)
                assertTrue(scroll.isLaidOut)
                assertEquals(expectedScroll, scroll.scrollY)
            }
        } finally {
            onUi { host.finish() }
        }
    }

    fun testPixelMapVehicleTapUsesCurrentOwnedIdAndRevision() = onUi {
        val opened = mutableListOf<Pair<OwnedVehicleId, Revision>>()
        val view = ShopView(instrumentation.targetContext) { fail("지도 탐색에서 거래 요청 발생") }
        view.onOpenVehicle = { id, revision -> opened += id to revision }
        val preview = ShopPreviewStates.normal()
        val id = OwnedVehicleId("initial-grant")
        val secondId = OwnedVehicleId("owned-second")
        val first = preview.vehicles.first().copy(id = id, name = "캐스퍼")
        val second = first.copy(id = secondId)
        val state = preview.copy(vehicles = listOf(first, second))
        val tag = "shop:map-vehicle:${id.value}"
        view.render(state, true)
        val firstListTag = "shop:list-vehicle:${id.value}"
        val secondListTag = "shop:list-vehicle:${secondId.value}"
        openVehicleList(view)
        assertEquals("캐스퍼 1번", button(view, firstListTag).text.toString())
        assertEquals("캐스퍼 2번", button(view, secondListTag).text.toString())
        assertFalse(text(view).contains(id.value) || text(view).contains(secondId.value))
        view.handleBack()
        val stale = view.findViewWithTag<View>(tag)
        assertTrue(stale.contentDescription.toString().contains("캐스퍼 1번"))
        val newer = state.copy(revision = Revision(state.revision.value + 1), vehicles = listOf(second, first))
        view.render(newer, true)
        stale.performClick()
        assertTrue(opened.isEmpty())
        assertTrue(view.findViewWithTag<View>(tag).performClick())
        openVehicleList(view)
        assertEquals("캐스퍼 2번", button(view, secondListTag).text.toString())
        assertTrue(button(view, secondListTag).performClick())
        assertEquals(listOf(id to newer.revision, secondId to newer.revision), opened)
        view.render(newer.copy(capacity = null), true)
        assertNull(view.findViewWithTag<View>(tag))
        openVehicleList(view)
        assertNotNull(view.findViewWithTag<Button>(firstListTag))
    }

    fun testPanFromVehicleMovesItsHitTargetWithoutSelectingIt() = onUi {
        val selected = mutableListOf<OwnedVehicleId>()
        val state = ShopPreviewStates.normal()
        val view = ShopView(instrumentation.targetContext) { fail("지도 조작에서 거래가 발생했습니다") }
        view.onOpenVehicle = { id, _ -> selected += id }
        view.restoreUiState(ShopUiState(mode = ShopMode.VEHICLES))
        view.render(state, true)
        measureAndLayout(view, 440, 680)
        val map = view.findViewWithTag<ShopSceneView>("shop:scene")
        val id = state.vehicles[1].id
        val vehicle = map.findViewWithTag<View>("shop:map-vehicle:${id.value}")
        val x = (vehicle.left + vehicle.right) / 2f
        val y = (vehicle.top + vehicle.bottom) / 2f
        assertTrue("확대한 지도에서 차량 터치 영역을 볼 수 있어야 한다", x in 0f..map.width.toFloat())
        val downAt = android.os.SystemClock.uptimeMillis()
        val drag = 48f * view.resources.displayMetrics.density
        for ((action, offset) in listOf(MotionEvent.ACTION_DOWN to 0f, MotionEvent.ACTION_MOVE to drag,
            MotionEvent.ACTION_UP to drag)) {
            val event = MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action, x + offset, y, 0)
            try { map.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        assertTrue((vehicle.left + vehicle.right) / 2f > x)
        assertTrue(selected.isEmpty())
        assertTrue(vehicle.performClick())
        assertEquals(listOf(id), selected)
    }

    fun testSceneModeAndScrollRestoreDoNotCreateTransactionsOrPreviewSuccess() = onUi {
        val events=mutableListOf<ShopEvent>();val state=ShopPreviewStates.normal()
        val view=ShopView(instrumentation.targetContext,events::add)
        view.restoreUiState(ShopUiState(mode=ShopMode.VEHICLES,sceneScrollY=180,controlsScrollY=240));view.render(state,true)
        assertEquals(ShopMode.VEHICLES,view.uiState.mode);assertEquals(180,view.uiState.sceneScrollY);assertEquals(240,view.uiState.controlsScrollY)
        view.openMapTools()
        val mode = button(view, "shop:mode:OVERALL")
        view.showMapPanel(ShopMapTarget.Expansion); button(view,"shop:map-confirm").performClick()
        assertEquals(1,events.size); assertNull(view.findViewWithTag<View>("shop:mode:OVERALL"))
        mode.performClick();assertEquals(ShopMode.VEHICLES,view.uiState.mode)
        assertEquals(5,view.findViewWithTag<ShopSceneView>("shop:scene").model.slots)
        val saved=view.uiState;val restored=ShopView(instrumentation.targetContext,events::add)
        restored.restoreUiState(saved);restored.render(state,true)
        assertEquals(saved.pending,restored.uiState.pending);assertEquals(1,events.size)
    }
}
