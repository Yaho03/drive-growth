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
    private fun layout(view: View, width: Int, height: Int) = onUi { measureAndLayout(view, width, height) }
    private fun measureAndLayout(view: View, width: Int, height: Int) {
        val density = view.resources.displayMetrics.density
        val w = (width * density).toInt(); val h = (height * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

    fun testCarWashConditionAndCoreFactsInBothLayouts() {
        val state = ShopPreviewStates.normal()
        val view = onUi { ShopView(instrumentation.targetContext) {}.apply { render(state, true) } }
        for ((w, h) in listOf(840 to 1184, 1360 to 948, 480 to 500)) {
            layout(view, w, h)
            // 미부착 위젯은 post를 실행하지 않을 수 있어 실제 크기를 적용한 뒤 다시 그린다.
            onUi { view.render(state, true) }; layout(view, w, h)
            onUi {
                assertTrue(text(view).contains("보유 / 공간 · 2 / 5"))
                view.showMapPanel(ShopMapTarget.FacilityLot(Facility.CAR_WASH))
                measureAndLayout(view, w, h)
                val shown = text(view)
                assertTrue(shown.contains("세차장")); assertTrue(shown.contains("가게 레벨 3 필요 · 현재 레벨 2"))
                assertEquals(state.facilities.single { it.facility == Facility.CAR_WASH }.option.trial,
                    "조정 가능한 시험값" in shown)
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
                val card = condition.parent as ViewGroup
                val title = views(card).filterIsInstance<TextView>().first { it.text.toString() == "세차장" }
                val locked = views(card).filterIsInstance<TextView>().single { it.text.toString() == "잠김" }
                fun inScroll(child: View): Rect = Rect(0, 0, child.width, child.height).apply {
                    scroll.offsetDescendantRectToMyCoords(child, this)
                }
                scroll.scrollTo(0, inScroll(title).top)
                val viewport = Rect(
                    scroll.scrollX + scroll.paddingLeft,
                    scroll.scrollY + scroll.paddingTop,
                    scroll.scrollX + scroll.width - scroll.paddingRight,
                    scroll.scrollY + scroll.height - scroll.paddingBottom,
                )
                for (label in listOf(title, locked, condition)) {
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
                    assertTrue(description, card.getChildVisibleRect(label, visible, null))
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
                assertTrue(button(view, "shop:mode:OVERALL").isSelected)
                assertFalse(button(view, "shop:mode:VEHICLES").isSelected)
                assertTrue(button(view, "shop:mode:VEHICLES").performClick())
            }
            layout(view, 620, 360)
            val vehicles = bitmap()
            try {
                assertFalse("전경과 차량 중심 화면은 다르게 그려야 한다", overall.sameAs(vehicles))
            } finally { vehicles.recycle() }
            onUi {
                assertEquals(ShopMode.VEHICLES, view.uiState.mode)
                assertTrue(button(view, "shop:mode:VEHICLES").isSelected)
                assertFalse(button(view, "shop:mode:OVERALL").isSelected)
                assertTrue(text(view).contains("2,400 게임머니"))
                assertTrue(button(view, "shop:mode:OVERALL").performClick())
            }
            layout(view, 620, 360)
            val returned = bitmap()
            try {
                assertTrue(overall.sameAs(returned))
                onUi { assertTrue(button(view, "shop:mode:OVERALL").isSelected) }
            } finally { returned.recycle() }
        } finally { overall.recycle() }
        assertTrue(events.isEmpty())
    }

    fun testPreviewCancelAndFailureKeepCommittedFacts() {
        val events = mutableListOf<ShopEvent>()
        val view = onUi { ShopView(instrumentation.targetContext, events::add).apply { render(ShopPreviewStates.normal(), true) } }
        onUi {
            view.showMapPanel(ShopMapTarget.Decorations)
            button(view, "shop:map-preview:blue-sign").performClick()
            assertTrue(text(view).contains("변경안 · 파란 가게"))
            assertTrue(text(view).contains("2,400 게임머니")); assertTrue(events.isEmpty())
            button(view, "shop:map-close").performClick(); assertFalse(text(view).contains("변경안 · 파란 가게"))
            view.showMapPanel(ShopMapTarget.Decorations)
            button(view, "shop:map-preview:blue-sign").performClick(); button(view, "shop:map-confirm").performClick()
            val request = (events.single() as ShopEvent.Submit).request
            view.complete(request, ContractResult.Failure(ContractError.STORAGE_FAILURE))
            assertTrue(text(view).contains("STORAGE_FAILURE")); assertTrue(text(view).contains("2,400 게임머니"))
            assertFalse(text(view).contains("변경안 · 파란 가게"))
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
            assertTrue(text(restored).contains("보유 / 공간 · 2 / 5")); assertTrue(text(restored).contains("확정 상태 재조회"))
            restored.render(state.copy(revision = Revision(13), level = 3, capacity = 7, balance = GameMoney(900)), true)
            assertTrue(text(restored).contains("보유 / 공간 · 2 / 7")); assertTrue(text(restored).contains("900 게임머니"))
        }
    }

    fun testMissingFactsAndLoadStatesNeverShowInventedZeroOrSuccess() {
        val events = mutableListOf<ShopEvent>()
        val view = onUi { ShopView(instrumentation.targetContext, events::add) }
        onUi {
            view.render(ShopPreviewStates.missing(), true)
            assertTrue(text(view).contains("확정 잔액 · 정보 미제공"))
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
        view.render(latest,true); assertTrue(text(view).contains("900 게임머니"))
        view.showMapPanel(ShopMapTarget.Expansion); button(view,"shop:map-confirm").performClick()
        assertEquals(Revision(5),(events.single() as ShopEvent.Submit).request.selection.revision)
    }


    fun testHomeMapAndVehicleAreVisibleBeforeSummaryWithoutScrolling() {
        val state = ShopPreviewStates.normal()
        val id = state.vehicles.first().id
        for ((width, height) in listOf(480 to 340, 480 to 360, 936 to 416)) {
            val view = onUi {
                ShopView(instrumentation.targetContext) {}.apply {
                    onOpenVehicle = { _, _ -> }
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
                assertEquals(description, Rect(0, 0, view.width, view.height), mapBounds)
                assertTrue(description, mapBounds.intersects(bounds(vehicle).left, bounds(vehicle).top,
                    bounds(vehicle).right, bounds(vehicle).bottom))
                val modeRow = button(view, "shop:mode:VEHICLES").parent as View
                assertTrue(description, mapBounds.contains(bounds(modeRow)))
                assertNull(view.findViewWithTag<View>("shop:controls-scroll"))
            }
        }
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
            assertTrue(view.findViewWithTag<ShopSceneView>("shop:scene").contentDescription.contains("미제공"))
            assertFalse(text(view).contains("0 게임머니"))
            view.render(state,false);assertNull(view.findViewWithTag<View>("shop:scene"))
        }
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

        assertTrue(button(view, "shop:map-facilities").performClick())
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

                val staleConfirm = button(view, "shop:map-confirm")
                assertTrue(staleConfirm.isEnabled)
                view.render(state, true)
                staleConfirm.performClick()
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
        button(view, "shop:map-vehicles").performClick()
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
        button(view, "shop:map-vehicles").performClick()
        assertEquals("캐스퍼 2번", button(view, secondListTag).text.toString())
        assertTrue(button(view, secondListTag).performClick())
        assertEquals(listOf(id to newer.revision, secondId to newer.revision), opened)
        view.render(newer.copy(capacity = null), true)
        assertNull(view.findViewWithTag<View>(tag))
        button(view, "shop:map-vehicles").performClick()
        assertNotNull(view.findViewWithTag<Button>(firstListTag))
    }

    fun testPanFromVehicleMovesItsHitTargetWithoutSelectingIt() = onUi {
        val selected = mutableListOf<OwnedVehicleId>()
        val state = ShopPreviewStates.normal()
        val view = ShopView(instrumentation.targetContext) { fail("지도 조작에서 거래가 발생했습니다") }
        view.onOpenVehicle = { id, _ -> selected += id }
        view.render(state, true)
        measureAndLayout(view, 440, 680)
        val map = view.findViewWithTag<ShopSceneView>("shop:scene")
        val vehicle = map.findViewWithTag<View>("shop:map-vehicle:${state.vehicles.first().id.value}")
        val x = (vehicle.left + vehicle.right) / 2f
        val y = (vehicle.top + vehicle.bottom) / 2f
        val downAt = android.os.SystemClock.uptimeMillis()
        for ((action, offset) in listOf(MotionEvent.ACTION_DOWN to 0f, MotionEvent.ACTION_MOVE to 90f,
            MotionEvent.ACTION_UP to 90f)) {
            val event = MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action, x + offset, y, 0)
            try { map.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        assertTrue((vehicle.left + vehicle.right) / 2f > x)
        assertTrue(selected.isEmpty())
        assertTrue(vehicle.performClick())
        assertEquals(listOf(state.vehicles.first().id), selected)
    }

    fun testSceneModeAndScrollRestoreDoNotCreateTransactionsOrPreviewSuccess() = onUi {
        val events=mutableListOf<ShopEvent>();val state=ShopPreviewStates.normal()
        val view=ShopView(instrumentation.targetContext,events::add)
        view.restoreUiState(ShopUiState(mode=ShopMode.VEHICLES,sceneScrollY=180,controlsScrollY=240));view.render(state,true)
        assertEquals(ShopMode.VEHICLES,view.uiState.mode);assertEquals(180,view.uiState.sceneScrollY);assertEquals(240,view.uiState.controlsScrollY)
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
