package io.drivegrowth.app.ui.garage

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.garage.CollectionState
import io.drivegrowth.core.garage.CollectionKey

/** 차종 × 희귀도 기록을 차종 순서대로 스크롤해 읽는다. */
class CollectionView(context: Context, private val onRetry: () -> Unit) : LinearLayout(context) {
    private val style = GarageStyle(context, pixelPanel = true, featureWindow = true)
    private val artwork = VehicleArtwork(context, style)
    internal var showHeading = true
    internal var takeNewCollectionKey: (CollectionKey) -> Boolean = { false }

    private val visibleBounds = Rect()

    internal fun refreshNewCollectionCells() {
        val viewport = contentScroll ?: return
        refreshNewCollectionCells(viewport)
    }

    private fun refreshNewCollectionCells(view: View) {
        if (!view.getLocalVisibleRect(visibleBounds)) return
        if (view is CollectionNewArtwork) view.invalidate()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) refreshNewCollectionCells(view.getChildAt(index))
        }
    }

    private var state: CollectionScreenState = CollectionScreenState.Loading
    private var controlsEnabled = false
    private var retryPending = false
    private var filter: CollectionState? = null
    private val positions = mutableMapOf<CollectionState?, Int>()
    private var contentScroll: ScrollView? = null
    private var contentFilter: CollectionState? = null
    private var renderedLayout: LayoutMode? = null
    private var viewportWidth: Int? = null
    private val fontScale get() = resources.configuration.fontScale
    private val wide get() = (viewportWidth ?: 0) >= style.dp(700) && fontScale <= 1.3f
    private val filterColumns get() = if ((viewportWidth ?: 0) >= style.dp(520) && fontScale <= 1.3f) 4 else 2
    private val rarityColumns get() = when {
        wide -> 4
        (viewportWidth ?: 0) >= style.dp(290) -> 2
        else -> 1
    }

    private data class LayoutMode(
        val rarityColumns: Int,
        val filterColumns: Int,
        val wide: Boolean,
        val showHeading: Boolean,
        val fontScale: Float,
    )

    private fun layoutMode() = LayoutMode(rarityColumns, filterColumns, wide, showHeading, fontScale)

    init {
        orientation = VERTICAL
        setBackgroundColor(style.base)
        setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(8))
    }

    fun render(value: CollectionScreenState, controlsEnabled: Boolean) {
        if (this.controlsEnabled == controlsEnabled && !retryPending &&
            renderedLayout == layoutMode() && state == value
        ) return
        state = value
        this.controlsEnabled = controlsEnabled
        retryPending = false
        if (viewportWidth != null) drawScreen() else requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        viewportWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (renderedLayout != layoutMode()) drawScreen()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** 화면 재생성 때 호스트가 보존할 표시 선택과 스크롤 위치다. */
    internal fun saveViewport(): Bundle {
        rememberPosition()
        return Bundle().apply {
            putString("filter", filter?.name)
            for ((option, offset) in positions) putInt(option?.name ?: "all", offset)
        }
    }

    internal fun restoreViewport(saved: Bundle) {
        contentScroll = null
        filter = CollectionState.entries.firstOrNull { it.name == saved.getString("filter") }
        positions.clear()
        for (option in listOf(null) + CollectionState.entries) {
            positions[option] = saved.getInt(option?.name ?: "all").coerceAtLeast(0)
        }
        if (viewportWidth != null) drawScreen() else requestLayout()
    }

    private fun rememberPosition() {
        // Loading/Error의 스크롤이나 배치되기 전에 교체된 Content는 이전 값을 덮지 않는다.
        contentScroll?.takeIf { it.isLaidOut }?.let { positions[contentFilter] = it.scrollY }
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun drawScreen(): Unit = with(style) {
        rememberPosition()
        contentScroll = null
        renderedLayout = layoutMode()
        val focusedTag = findFocus()?.tag
        removeAllViews()
        if (!controlsEnabled) {
            addView(text(s(R.string.garage_restricted), 28, bold = true))
            addView(text(s(R.string.garage_restricted_body), color = muted))
            return@with
        }

        if (showHeading) addView(heading(s(R.string.garage_collection_title), ReferenceHudIcon.COLLECTION))
        val body = card().apply { setPadding(dp(10), dp(6), dp(10), dp(8)) }
        val current = state
        val hasContent = current is CollectionScreenState.Content && current.entries.isNotEmpty()
        val viewport = scroll(body, if (hasContent) positions[filter] ?: 0 else 0).apply {
            tag = "collection:scroll"
            isVerticalScrollBarEnabled = true
        }
        if (hasContent) {
            contentScroll = viewport
            contentFilter = filter
            // 다시 보이는 칸은 하드웨어가 기존 display list를 재사용해도 NEW를 확인한다.
            viewport.setOnScrollChangeListener { view, _, _, _, _ ->
                if (view === contentScroll && view.isAttachedToWindow) refreshNewCollectionCells()
            }
        } else if (current is CollectionScreenState.Content) {
            positions.clear()
        }
        if (hasContent) addView(filters(), LayoutParams(-1, -2))
        addView(viewport, LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })

        when (current) {
            CollectionScreenState.Loading -> body.addView(text(s(R.string.garage_collection_loading), 24))
            is CollectionScreenState.Error -> {
                body.addView(text(s(R.string.garage_error), 24, bold = true))
                body.addView(text(current.message))
                add(body, button(s(R.string.garage_retry), !retryPending) {
                    if (viewport.parent === this@CollectionView && !retryPending) {
                        retryPending = true
                        drawScreen()
                        onRetry()
                    }
                })
            }
            is CollectionScreenState.Content -> showContent(body, current)
        }
        if (focusedTag != null) findViewWithTag<View>(focusedTag)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun filters() = with(style) {
        val options = listOf(
            null to R.string.garage_collection_filter_all,
            CollectionState.OWNED to R.string.garage_collection_filter_owned,
            CollectionState.DISCOVERED to R.string.garage_collection_filter_discovered,
            CollectionState.UNDISCOVERED to R.string.garage_undiscovered,
        )
        column().apply {
            options.chunked(filterColumns).forEach { line ->
                addView(row().apply {
                    val filterRow = this
                    line.forEachIndexed { index, (option, label) ->
                        addView(button(s(label), primary = option == filter) {
                            if (filterRow.parent?.parent === this@CollectionView && filter != option) {
                                filter = option
                                drawScreen()
                            }
                        }.apply {
                            tag = "collection:filter:${option?.name ?: "all"}"
                            textSize = 17f
                            minimumHeight = maxOf(minimumHeight, dp(50))
                            isSelected = option == filter
                        }, weightedCell(index))
                    }
                }, LayoutParams(-1, -2))
            }
        }
    }

    private fun showContent(body: LinearLayout, content: CollectionScreenState.Content) = with(style) {
        sourceText(content.sourceLabel)?.let { body.addView(text(it, 14, muted)) }
        if (content.entries.isEmpty()) {
            body.addView(text(s(R.string.garage_collection_empty), 24, bold = true))
            body.addView(text(s(R.string.garage_collection_empty_body), color = muted))
            return@with
        }

        val owned = content.entries.count { it.entry.state == CollectionState.OWNED }
        body.addView(text(s(R.string.garage_collection_count, owned, content.entries.size), 20, bold = true))
        body.addView(text(s(R.string.garage_collection_note), 16, muted))
        val vehicles = content.entries.groupBy { it.entry.key.vehicleId }.values
            .sortedBy { it.first().unlockShopLevel ?: Int.MAX_VALUE }
            .filter { cells -> filter == null || cells.any { it.entry.state == filter } }
        if (vehicles.isEmpty()) {
            positions.remove(filter)
            add(body, text(s(R.string.garage_collection_filter_empty), 18, bold = true))
            return@with
        }

        if (wide) add(body, rarityHeaders(), gap = 4)
        vehicles.forEach { cells -> add(body, vehicleRow(cells), gap = 4) }
    }

    private fun rarityHeaders() = with(style) {
        row().apply {
            addView(text(s(R.string.garage_collection_model), 16, muted, bold = true), modelColumn())
            Rarity.entries.forEach { rarity ->
                addView(rarityChip(rarity).apply { gravity = Gravity.CENTER }, weightedCell(1, sameHeight = true))
            }
        }
    }

    private fun vehicleRow(cells: List<CollectionPresentation>) = with(style) {
        val first = cells.first()
        val model = column().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape(inset)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(text(first.name, 19, bold = true))
            val unlock = first.unlockShopLevel
            addView(text(if (unlock == null) s(R.string.garage_unlock_missing)
                else s(R.string.garage_collection_unlock_level, unlock), 13, muted))
        }
        column().apply {
            tag = "collection:model:${first.entry.key.vehicleId.value}"
            if (!wide) addView(model, LayoutParams(-1, -2))
            Rarity.entries.chunked(rarityColumns).forEach { line ->
                add(this, row().apply {
                    if (wide) addView(model, modelColumn())
                    line.forEachIndexed { index, rarity ->
                        val cell = cells.firstOrNull { it.entry.key.rarity == rarity }
                        addView(entryCell(first.name, rarity, cell),
                            weightedCell(if (wide) 1 else index, sameHeight = true))
                    }
                }, gap = if (wide) 0 else 4)
            }
        }
    }

    private fun weightedCell(index: Int, sameHeight: Boolean = false) =
        LayoutParams(0, if (sameHeight) -1 else -2, 1f).apply {
            if (index > 0) marginStart = style.dp(5)
        }

    private fun modelColumn() = LayoutParams(style.dp(118), -1)

    private fun entryCell(model: String, rarity: Rarity, value: CollectionPresentation?) = with(style) {
        column().apply {
            background = raritySurface(rarity)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            val status = s(when (value?.entry?.state) {
                CollectionState.UNDISCOVERED -> R.string.garage_undiscovered
                CollectionState.DISCOVERED -> R.string.garage_discovered
                CollectionState.OWNED -> R.string.garage_ever_owned
                null -> R.string.garage_collection_entry_missing
            })
            contentDescription = "$model ${rarity(rarity)} · $status"
            if (!wide) addView(text(rarity(rarity), 14, rarityColor(rarity), bold = true).apply {
                gravity = Gravity.CENTER
            }, LayoutParams(-1, -2))
            if (value != null) {
                val image = artwork.view(value.entry.key.vehicleId, rarity, 44, value.entry.state, showRing = false).apply {
                    background = null
                }
                val art = if (value.entry.state == CollectionState.OWNED) {
                    CollectionNewArtwork(context, image, {
                        artwork.view(value.entry.key.vehicleId, rarity, 44, CollectionState.UNDISCOVERED, showRing = false)
                            .apply { background = null }
                    }) { controlsEnabled && takeNewCollectionKey(value.entry.key) }
                } else image
                addView(art, LayoutParams(-1, dp(40)))
            }
            addView(text(status, 16, rarityColor(rarity), bold = true).apply { gravity = Gravity.CENTER }, LayoutParams(-1, -2))
        }
    }
}
