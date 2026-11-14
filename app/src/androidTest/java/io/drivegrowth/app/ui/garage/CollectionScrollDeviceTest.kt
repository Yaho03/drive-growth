package io.drivegrowth.app.ui.garage

import android.content.Intent
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.graphics.Rect
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
                    .filterIsInstance<ScrollView>().all { it.isLaidOut && !it.isLayoutRequested } }) return
            Thread.sleep(20)
        }
        val details = onUi {
            val root = host.collection
            val scroll = descendants(root).filterIsInstance<ScrollView>().singleOrNull()
            fun layoutInfo(view: View?) = view?.let {
                "attached=${it.isAttachedToWindow}, laidOut=${it.isLaidOut}, requested=${it.isLayoutRequested}, size=${it.width}x${it.height}"
            }
            "root(${layoutInfo(root)}), scroll(${layoutInfo(scroll)})"
        }
        fail("도감의 실제 layout이 완료되지 않았다. $details")
    }
    private fun render(state: CollectionScreenState, enabled: Boolean = true) {
        onUi { host.collection.render(state, enabled) }
        waitForLayout()
    }
    private fun viewport() = host.collection.findViewWithTag<ScrollView>("collection:scroll")
    private fun moveTo(y: Int): Int = onUi { viewport().apply { scrollTo(0, y) }.scrollY }
    private fun bottom() = onUi { viewport().getChildAt(0).height }
    private fun position() = onUi { viewport().scrollY }
    private fun scrollDown(): Int = moveTo(700).also { assertTrue(it > 0) }

    fun testLoadingAndErrorLayoutsPreserveContentPosition() {
        val before = scrollDown()
        repeat(2) {
            render(CollectionScreenState.Loading)
            assertEquals(0, position())
            render(GaragePreviewStates.collectionError)
            assertEquals(0, position())
        }
        render(GaragePreviewStates.collection)
        assertEquals(before, position())
    }

    fun testConsecutiveContentRendersPreservePosition() {
        val before = scrollDown()
        val content = GaragePreviewStates.collection
        onUi {
            host.collection.render(content.copy(sourceLabel = "임시 표기"), true)
            host.collection.render(content, true)
        }
        waitForLayout()
        assertEquals(before, position())
    }

    fun testScrollReachesTwentiethModelWithoutRebuildingRows() {
        val content = GaragePreviewStates.collection
        val models = content.entries.map { it.entry.key.vehicleId }.distinct()
        val last = "collection:model:${models.last().value}"
        val scroll = onUi { viewport() }
        val filter = onUi { host.collection.findViewWithTag<Button>("collection:filter:all") }
        val row = onUi { scroll.findViewWithTag<View>(last) }
        onUi {
            assertEquals(20, descendants(scroll).count { it.tag?.toString()?.startsWith("collection:model:") == true })
            assertFalse(row.getLocalVisibleRect(Rect()))
        }
        assertTrue(moveTo(bottom()) > 0)
        onUi {
            assertSame(filter, host.collection.findViewWithTag<Button>("collection:filter:all"))
            assertSame(scroll, viewport())
            assertSame(row, scroll.findViewWithTag<View>(last))
            assertTrue(row.getLocalVisibleRect(Rect()))
        }
    }

    fun testEqualContentRefreshKeepsAlreadyLaidOutViews() {
        val before = scrollDown()
        val scroll = onUi { viewport() }
        val filter = onUi { host.collection.findViewWithTag<Button>("collection:filter:all") }
        val content = GaragePreviewStates.collection
        val row = onUi { descendants(scroll).first { it.contentDescription?.contains("일반 ·") == true } }
        render(content.copy(entries = content.entries.map { it.copy() }))
        onUi {
            assertSame(filter, host.collection.findViewWithTag<Button>("collection:filter:all"))
            assertSame(scroll, viewport())
            assertTrue(row.isAttachedToWindow)
        }
        assertEquals(before, position())
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

    fun testOffscreenOwnershipWaitsForExactCellAndOnlyAppearsOnce() {
        val identity = GameIdentity(ProfileId("collection-scroll"), InputSource.SIMULATION)
        val content = GaragePreviewStates.collection
        val owned = content.entries.filter { it.entry.state == CollectionState.OWNED }
        val first = owned.first()
        val last = owned.last()
        val pending = CollectionNewPresentation()
        val consumed = mutableListOf<io.drivegrowth.core.garage.CollectionKey>()
        onUi {
            host.collection.takeNewCollectionKey = { key ->
                pending.take(identity, key).also { if (it) consumed += key }
            }
            assertTrue(pending.offer(FirstCollectionOwnership(identity.profileId, identity.source, last.entry.key)))
            host.collection.refreshNewCollectionCells()
        }
        instrumentation.waitForIdleSync()
        onUi {
            assertTrue(consumed.isEmpty())
            assertTrue(viewport().findViewWithTag<View>("collection:model:${first.entry.key.vehicleId.value}")
                .getLocalVisibleRect(Rect()))
        }
        moveTo(bottom())
        for (attempt in 0 until 150) {
            if (onUi { consumed.isNotEmpty() }) break
            Thread.sleep(20)
        }
        onUi {
            assertEquals(listOf(last.entry.key), consumed)
            assertFalse(pending.offer(FirstCollectionOwnership(identity.profileId, identity.source, last.entry.key)))
        }
        moveTo(0)
        moveTo(bottom())
        instrumentation.waitForIdleSync()
        assertEquals(listOf(last.entry.key), onUi { consumed.toList() })
    }

    fun testClippedNewStampWaitsUntilWholeBadgeIsVisible() {
        val identity = GameIdentity(ProfileId("collection-clipped"), InputSource.SIMULATION)
        val owned = GaragePreviewStates.collection.entries.filter { it.entry.state == CollectionState.OWNED }
        val target = owned[owned.size / 2]
        val pending = CollectionNewPresentation()
        val consumed = mutableListOf<io.drivegrowth.core.garage.CollectionKey>()
        val (artwork, stamp, visibleOffset) = onUi {
            val scroll = viewport()
            val row = scroll.findViewWithTag<View>("collection:model:${target.entry.key.vehicleId.value}")
            val art = descendants(row).filterIsInstance<CollectionNewArtwork>().single()
            val badge = descendants(art).filterIsInstance<TextView>().single { it.text == "NEW" }
            val badgePoint = IntArray(2).also(badge::getLocationInWindow)
            val viewportPoint = IntArray(2).also(scroll::getLocationInWindow)
            val badgeOffset = scroll.scrollY + badgePoint[1] - viewportPoint[1]
            assertTrue("fixture는 배지 아래에 그림 일부가 있어야 한다", badge.bottom < art.height)
            Triple(art, badge, badgeOffset)
        }
        for (attempt in 0 until 150) {
            if (onUi { artwork.hasWindowFocus() }) break
            Thread.sleep(20)
        }
        val clippedOffset = visibleOffset + onUi { stamp.height }
        onUi {
            assertTrue(artwork.hasWindowFocus())
            viewport().scrollTo(0, clippedOffset)
            val visible = Rect()
            assertTrue(artwork.getLocalVisibleRect(visible))
            assertTrue("배지 전체는 위에서 잘리고 그림 하단은 남아야 한다", visible.top >= stamp.bottom)
            host.collection.takeNewCollectionKey = { key ->
                pending.take(identity, key).also { if (it) consumed += key }
            }
            assertTrue(pending.offer(FirstCollectionOwnership(identity.profileId, identity.source, target.entry.key)))
            host.collection.refreshNewCollectionCells()
            // 클리핑된 draw/posted 검사를 장치의 프레임 타이밍과 무관하게 한 번 거친다.
            val bitmap = android.graphics.Bitmap.createBitmap(artwork.width, artwork.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            try { artwork.draw(android.graphics.Canvas(bitmap)) } finally { bitmap.recycle() }
        }
        instrumentation.waitForIdleSync()
        onUi {
            assertTrue(consumed.isEmpty())
            assertEquals(View.INVISIBLE, stamp.visibility)
        }
        moveTo((visibleOffset - 2).coerceAtLeast(0))
        for (attempt in 0 until 100) {
            if (onUi { stamp.isShown }) break
            Thread.sleep(15)
        }
        onUi {
            val visible = Rect()
            assertTrue(artwork.getLocalVisibleRect(visible))
            assertTrue(visible.contains(stamp.left, stamp.top, stamp.right, stamp.bottom))
            assertTrue("정확한 칸의 배지가 실제로 표시되어야 한다", stamp.isShown)
            assertEquals(listOf(target.entry.key), consumed)
        }
        moveTo(clippedOffset)
        moveTo((visibleOffset - 2).coerceAtLeast(0))
        instrumentation.waitForIdleSync()
        assertEquals(listOf(target.entry.key), onUi { consumed.toList() })
    }

    fun testRestrictedScreenHidesContentAndPreservesPositionOnReturn() {
        val before = scrollDown()
        val oldFilter = onUi { host.collection.findViewWithTag<Button>("collection:filter:DISCOVERED") }
        render(GaragePreviewStates.collection, false)
        onUi {
            assertNull(viewport())
            assertFalse(descendants(host.collection).any { it.contentDescription?.contains("보유 이력 있음") == true })
        }
        render(GaragePreviewStates.collection)
        onUi {
            assertFalse(oldFilter.isAttachedToWindow)
            oldFilter.performClick()
            assertTrue(host.collection.findViewWithTag<Button>("collection:filter:all").isSelected)
        }
        assertEquals(before, position())
    }

    fun testShorterContentClampsPositionAndKeepsThatPosition() {
        moveTo(bottom())
        render(CollectionScreenState.Loading)
        render(GaragePreviewStates.collection.copy(entries = GaragePreviewStates.collection.entries.take(4)))
        val bounded = position()
        assertFalse(onUi { viewport().canScrollVertically(1) })
        render(GaragePreviewStates.collection)
        assertEquals(bounded, position())
    }

    fun testReattachAndNewViewRestoreFilterAndScroll() {
        onUi { host.collection.findViewWithTag<Button>("collection:filter:DISCOVERED").performClick() }
        waitForLayout()
        val before = scrollDown()
        val copy = onUi {
            val saved = host.collection.saveViewport()
            host.setContentView(View(host))
            assertFalse(host.collection.isAttachedToWindow)
            host.collection.render(GaragePreviewStates.collection, true)
            host.setContentView(host.collection)
            saved
        }
        waitForLayout()
        assertEquals(before, position())
        val recreated = onUi {
            assertTrue(host.collection.findViewWithTag<Button>("collection:filter:DISCOVERED").isSelected)
            CollectionView(host) {}.apply {
                restoreViewport(copy)
                render(GaragePreviewStates.collection, true)
                host.setContentView(this)
            }
        }
        for (attempt in 0 until 150) {
            if (onUi { recreated.findViewWithTag<ScrollView>("collection:scroll").isLaidOut }) break
            Thread.sleep(20)
        }
        onUi {
            assertTrue(recreated.findViewWithTag<Button>("collection:filter:DISCOVERED").isSelected)
            assertEquals(before, recreated.findViewWithTag<ScrollView>("collection:scroll").scrollY)
        }
    }
}
