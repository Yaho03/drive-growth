package io.drivegrowth.app.ui.garage

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.VehicleId
import io.drivegrowth.core.garage.CollectionState
import io.drivegrowth.core.garage.CollectionKey

/** 차종 × 희귀도 기록을 차종 단위로 넘겨 읽는다. */
class CollectionView(context: Context, private val onRetry: () -> Unit) : LinearLayout(context) {
    private val style = GarageStyle(context, pixelPanel = true, creamPanel = true)
    private val artwork = VehicleArtwork(context, style)
    internal var showHeading = true
    internal var takeNewCollectionKey: (CollectionKey) -> Boolean = { false }

    internal fun refreshNewCollectionCells() {
        fun visit(view: View) {
            if (view is CollectionNewArtwork) view.invalidate()
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(this)
    }

    private var state: CollectionScreenState = CollectionScreenState.Loading
    private var controlsEnabled = false
    private var retryPending = false
    private var filter: CollectionState? = null
    private val positions = mutableMapOf<CollectionState?, PagePosition>()
    private var renderedLayout: LayoutMode? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private val fontScale get() = resources.configuration.fontScale
    private val wide get() = viewportWidth >= style.dp(700) && fontScale <= 1.3f
    private val pageSize get() = if (wide && viewportHeight >= style.dp(395)) 2 else 1
    private val filterColumns get() = if (viewportWidth >= style.dp(520) && fontScale <= 1.3f) 4 else 2
    private val rarityColumns get() = when {
        wide -> 4
        viewportWidth >= style.dp(290) -> 2
        else -> 1
    }

    private data class LayoutMode(
        val rarityColumns: Int,
        val filterColumns: Int,
        val pageSize: Int,
        val showHeading: Boolean,
        val fontScale: Float,
    )

    private fun layoutMode() = LayoutMode(rarityColumns, filterColumns, pageSize, showHeading, fontScale)

    private data class PagePosition(val vehicle: VehicleId, val index: Int)

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
        drawScreen()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        viewportWidth = MeasureSpec.getSize(widthMeasureSpec)
        viewportHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (renderedLayout != layoutMode()) drawScreen()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 같은 크기로 다시 붙어도 페이지 조작을 새 창에 배치한다.
        renderedLayout = null
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun drawScreen(): Unit = with(style) {
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
        val viewport = scroll(body)
        val current = state
        if (current is CollectionScreenState.Content && current.entries.isNotEmpty()) {
            addView(filters(), LayoutParams(-1, -2))
        }
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
            is CollectionScreenState.Content -> showContent(body, viewport, current)
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
                            isSelected = option == filter
                        }, weightedCell(index))
                    }
                }, LayoutParams(-1, -2))
            }
        }
    }

    private fun showContent(body: LinearLayout, viewport: ScrollView, content: CollectionScreenState.Content) = with(style) {
        sourceText(content.sourceLabel)?.let { body.addView(text(it, 14, muted)) }
        if (content.entries.isEmpty()) {
            positions.clear()
            body.addView(text(s(R.string.garage_collection_empty), 24, bold = true))
            body.addView(text(s(R.string.garage_collection_empty_body), color = muted))
            return@with
        }

        val owned = content.entries.count { it.entry.state == CollectionState.OWNED }
        body.addView(text(s(R.string.garage_collection_count, owned, content.entries.size), 17, bold = true))
        body.addView(text(s(R.string.garage_collection_note), 14, muted))
        val vehicles = content.entries.groupBy { it.entry.key.vehicleId }.values
            .sortedBy { it.first().unlockShopLevel ?: Int.MAX_VALUE }
            .filter { cells -> filter == null || cells.any { it.entry.state == filter } }
        if (vehicles.isEmpty()) {
            positions.remove(filter)
            add(body, text(s(R.string.garage_collection_filter_empty), 18, bold = true))
            return@with
        }

        val lastPage = (vehicles.size - 1) / pageSize
        val position = positions[filter]
        val matched = vehicles.indexOfFirst { it.first().entry.key.vehicleId == position?.vehicle }
        val index = if (matched >= 0) matched else position?.index ?: 0
        val page = (index / pageSize).coerceAtMost(lastPage)
        val start = page * pageSize
        val anchor = if (matched >= 0) matched else start
        positions[filter] = PagePosition(vehicles[anchor].first().entry.key.vehicleId, anchor)
        if (wide) add(body, rarityHeaders(), gap = 4)
        val rows = column()
        body.addView(rows, LayoutParams(-1, -2))
        var visibleStart = -1
        var visibleRows = emptyList<View>()
        var previousStart = -1
        var previousRows = emptyList<View>()
        fun showRows(first: Int) {
            val nextRows = if (first == previousStart) previousRows else
                (first until minOf(first + pageSize, vehicles.size)).map { vehicleRow(vehicles[it]) }
            rows.removeAllViews()
            nextRows.forEach { add(rows, it, gap = 4) }
            previousStart = visibleStart
            previousRows = visibleRows
            visibleStart = first
            visibleRows = nextRows
        }
        showRows(start)
        add(this@CollectionView, pager(page, lastPage) { target ->
            val first = target * pageSize
            positions[filter] = PagePosition(vehicles[first].first().entry.key.vehicleId, first)
            showRows(first)
            viewport.scrollTo(0, 0)
        }, gap = 6)
    }

    private fun pager(initialPage: Int, lastPage: Int, onPageChanged: (Int) -> Unit) = with(style) {
        row().apply {
            gravity = Gravity.CENTER_VERTICAL
            val previous = button(s(R.string.garage_collection_previous), onClick = {}).apply { tag = "collection:previous" }
            val next = button(s(R.string.garage_collection_next), onClick = {}).apply { tag = "collection:next" }
            val label = text("", 16, bold = true).apply {
                tag = "collection:page"
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
            }
            var page = initialPage
            fun updatePager() {
                val focused = findFocus()
                label.text = s(R.string.garage_collection_page, page + 1, lastPage + 1)
                previous.isEnabled = page > 0
                previous.setTextColor(if (previous.isEnabled) ink else muted)
                next.isEnabled = page < lastPage
                next.setTextColor(if (next.isEnabled) ink else muted)
                when {
                    focused === previous && !previous.isEnabled && next.isEnabled -> next.requestFocus()
                    focused === next && !next.isEnabled && previous.isEnabled -> previous.requestFocus()
                }
            }
            fun move(offset: Int) {
                val target = page + offset
                if (parent !== this@CollectionView || !controlsEnabled || target !in 0..lastPage) return
                page = target
                onPageChanged(page)
                updatePager()
            }
            previous.setOnClickListener { if (previous.isEnabled) move(-1) }
            next.setOnClickListener { if (next.isEnabled) move(1) }
            addView(previous, LayoutParams(0, -2, 1f))
            addView(label, LayoutParams(-2, -2))
            addView(next, LayoutParams(0, -2, 1f))
            updatePager()
        }
    }

    private fun rarityHeaders() = with(style) {
        row().apply {
            addView(text(s(R.string.garage_collection_model), 14, muted, bold = true), modelColumn())
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
            addView(text(first.name, 17, bold = true))
            val unlock = first.unlockShopLevel
            addView(text(if (unlock == null) s(R.string.garage_unlock_missing)
                else s(R.string.garage_collection_unlock_level, unlock), 13, muted))
        }
        column().apply {
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
                addView(art, LayoutParams(-1, dp(44)))
            }
            addView(text(status, 14, ink, bold = true).apply { gravity = Gravity.CENTER }, LayoutParams(-1, -2))
        }
    }
}
