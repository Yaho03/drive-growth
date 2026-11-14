package io.drivegrowth.app.ui.garage

import android.content.Intent
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import io.drivegrowth.app.data.FirstCollectionOwnership
import io.drivegrowth.app.host.CollectionNewPresentation
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.ProfileId
import io.drivegrowth.core.garage.CollectionState

/** 실제 Activity 부착과 중간 상태 layout을 거친다. assertion은 instrumentation 스레드에서 수행한다. */
@Suppress("DEPRECATION")
class CollectionScrollDeviceTest : InstrumentationTestCase() {
    private lateinit var host: CollectionScrollTestActivity

    override fun setUp() {
        super.setUp()
        val context = instrumentation.targetContext
        val display = context.getSystemService(DisplayManager::class.java).displays
            .singleOrNull { it.displayId != 0 && it.name == "Emulator 2D Display" }
        val options = display?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
        host = instrumentation.startActivitySync(Intent(context,
            CollectionScrollTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options) as CollectionScrollTestActivity
        waitForLayout()
    }

    override fun tearDown() {
        if (::host.isInitialized) instrumentation.runOnMainSync { host.finish() }
        instrumentation.waitForIdleSync()
        super.tearDown()
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun <T> onUi(block: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { result = block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    private fun waitForLayout() {
        repeat(150) {
            instrumentation.waitForIdleSync()
            if (onUi { host.collection.isAttachedToWindow && !host.collection.isLayoutRequested && descendants(host.collection)
                    .filterIsInstance<ScrollView>().all { it.isLaidOut } &&
                    (host.collection.findViewWithTag<Button>("collection:next")?.isLaidOut != false) }) return
            Thread.sleep(20)
        }
        val details = onUi {
            val root = host.collection
            val scroll = descendants(root).filterIsInstance<ScrollView>().singleOrNull()
            val next = root.findViewWithTag<Button>("collection:next")
            fun layoutInfo(view: View?) = view?.let {
                "attached=${it.isAttachedToWindow}, laidOut=${it.isLaidOut}, requested=${it.isLayoutRequested}, size=${it.width}x${it.height}"
            }
            "root(${layoutInfo(root)}), scroll(${layoutInfo(scroll)}), next(${layoutInfo(next)})"
        }
        fail("도감의 실제 layout이 완료되지 않았다. $details")
    }
    private fun render(state: CollectionScreenState, enabled: Boolean = true) {
        onUi { host.collection.render(state, enabled) }
        waitForLayout()
    }
    private fun pageLabel() = host.collection.findViewWithTag<TextView>("collection:page")?.text?.toString()

    private fun advance(): String {
        val before = onUi { pageLabel() }
        onUi {
            val next = host.collection.findViewWithTag<Button>("collection:next")
            assertTrue(next.isEnabled)
            assertTrue(next.performClick())
        }
        waitForLayout()
        return checkNotNull(onUi { pageLabel() }).also { assertTrue(it != before) }
    }

    fun testLoadingLayoutPreservesPage() {
        val before = advance()
        render(CollectionScreenState.Loading)
        assertNull(onUi { pageLabel() })
        render(GaragePreviewStates.collection)
        assertEquals(before, onUi { pageLabel() })
    }

    fun testErrorLayoutPreservesPage() {
        val before = advance()
        render(GaragePreviewStates.collectionError)
        assertNull(onUi { pageLabel() })
        render(GaragePreviewStates.collection)
        assertEquals(before, onUi { pageLabel() })
    }

    fun testConsecutiveContentRendersPreservePage() {
        val before = advance()
        onUi {
            host.collection.render(GaragePreviewStates.collection, true)
            host.collection.render(GaragePreviewStates.collection, true)
        }
        waitForLayout()
        assertEquals(before, onUi { pageLabel() })
    }

    fun testPageChangesOnlyReplaceVehicleRows() {
        val next = onUi { host.collection.findViewWithTag<Button>("collection:next") }
        val previous = onUi { host.collection.findViewWithTag<Button>("collection:previous") }
        val filter = onUi { host.collection.findViewWithTag<Button>("collection:filter:all") }
        val scroll = onUi { descendants(host.collection).filterIsInstance<ScrollView>().single() }
        val firstPage = onUi { pageLabel() }
        val disabledColor = onUi { previous.currentTextColor }
        val enabledColor = onUi { next.currentTextColor }
        assertTrue(disabledColor != enabledColor)
        val firstCar = onUi { descendants(scroll).first { it.contentDescription?.contains("캐스퍼 일반") == true } }

        val nextPage = advance()
        onUi {
            assertSame(filter, host.collection.findViewWithTag<Button>("collection:filter:all"))
            assertSame(next, host.collection.findViewWithTag<Button>("collection:next"))
            assertSame(previous, host.collection.findViewWithTag<Button>("collection:previous"))
            assertSame(scroll, descendants(host.collection).filterIsInstance<ScrollView>().single())
            assertFalse(firstCar.isAttachedToWindow)
            assertEquals(enabledColor, previous.currentTextColor)
            assertTrue(previous.performClick())
            assertEquals(disabledColor, previous.currentTextColor)
        }
        waitForLayout()
        assertEquals(firstPage, onUi { pageLabel() })
        assertTrue(nextPage != firstPage)
    }

    fun testEqualContentRefreshKeepsAlreadyLaidOutViews() {
        val page = advance()
        val next = onUi { host.collection.findViewWithTag<Button>("collection:next") }
        val scroll = onUi { descendants(host.collection).filterIsInstance<ScrollView>().single() }

        // 호스트가 새 조회 결과 객체를 보내도 표시 데이터가 같으면 현재 행을 유지해야 한다.
        val content = GaragePreviewStates.collection
        val row = onUi { descendants(scroll).first { it.contentDescription?.contains("일반 ·") == true } }
        render(content.copy(entries = content.entries.map { it.copy() }))
        onUi {
            assertSame(next, host.collection.findViewWithTag<Button>("collection:next"))
            assertSame(scroll, descendants(host.collection).filterIsInstance<ScrollView>().single())
            assertTrue(row.isAttachedToWindow)
        }
        assertEquals(page, onUi { pageLabel() })
    }

    fun testConfirmedOwnershipInvalidatesAlreadyVisibleCachedCellWithoutRender() {
        val identity = GameIdentity(ProfileId("collection-preview"), InputSource.SIMULATION)
        val entry = GaragePreviewStates.collection.entries.first { it.entry.state == CollectionState.OWNED }
        val pending = CollectionNewPresentation()
        render(GaragePreviewStates.collection.copy(entries = listOf(entry)))
        val (cell, stamp) = onUi {
            host.collection.takeNewCollectionKey = { key -> pending.take(identity, key) }
            val cell = descendants(host.collection).filterIsInstance<CollectionNewArtwork>().single()
            cell to descendants(cell).filterIsInstance<TextView>().single { it.text == "NEW" }
        }
        for (attempt in 0 until 150) {
            if (onUi { cell.hasWindowFocus() }) break
            Thread.sleep(20)
        }
        onUi {
            assertTrue("이미 배치된 도감이 창 포커스를 받아야 한다", cell.hasWindowFocus())
            assertEquals(View.INVISIBLE, stamp.visibility)
            assertTrue(pending.offer(FirstCollectionOwnership(identity.profileId, identity.source, entry.entry.key)))
            host.collection.refreshNewCollectionCells()
        }
        repeat(100) {
            if (onUi { stamp.isShown }) {
                onUi {
                    assertSame(cell, descendants(host.collection).filterIsInstance<CollectionNewArtwork>().single())
                    assertFalse(pending.take(identity, entry.entry.key))
                }
                return
            }
            Thread.sleep(15)
        }
        fail("새 render 없이 이미 보이는 도감 칸이 NEW를 표시해야 한다")
    }

    fun testRestrictedScreenHidesContentAndPreservesPageOnReturn() {
        val before = advance()
        val oldNext = onUi { host.collection.findViewWithTag<Button>("collection:next") }
        render(GaragePreviewStates.collection, false)
        assertNull(onUi { pageLabel() })
        assertFalse(onUi { descendants(host.collection).any { it.contentDescription?.contains("보유 이력 있음") == true } })
        render(GaragePreviewStates.collection)
        onUi {
            assertFalse(oldNext.isAttachedToWindow)
            oldNext.performClick()
        }
        assertEquals(before, onUi { pageLabel() })
    }

    fun testShorterContentClampsPageAndKeepsThatPosition() {
        advance()
        advance()
        render(CollectionScreenState.Loading)
        val shorter = GaragePreviewStates.collection.copy(entries = GaragePreviewStates.collection.entries.take(4))
        render(shorter)
        assertEquals("1 / 1", onUi { pageLabel() })
        assertFalse(onUi { host.collection.findViewWithTag<Button>("collection:next").isEnabled })
        assertFalse(onUi { host.collection.findViewWithTag<Button>("collection:previous").isEnabled })

        render(GaragePreviewStates.collection)
        assertTrue(onUi { pageLabel()?.startsWith("1 / ") == true })
    }

    fun testReturningToSameViewAfterLeavingKeepsFilterAndPage() {
        onUi { host.collection.findViewWithTag<Button>("collection:filter:DISCOVERED").performClick() }
        val before = advance()
        onUi {
            host.setContentView(View(host))
            assertFalse(host.collection.isAttachedToWindow)
            host.collection.render(GaragePreviewStates.collection, true)
            host.setContentView(host.collection)
        }
        waitForLayout()
        assertEquals(before, onUi { pageLabel() })
        assertTrue(onUi { host.collection.findViewWithTag<Button>("collection:filter:DISCOVERED").isSelected })
    }
}
