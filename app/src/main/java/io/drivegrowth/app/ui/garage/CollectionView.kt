package io.drivegrowth.app.ui.garage

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.garage.CollectionState

/** 차종 × 희귀도 기록을 차종 단위로 넘겨 읽는다. */
class CollectionView(context: Context, private val onRetry: () -> Unit) : LinearLayout(context) {
    private val style = GarageStyle(context, pixelPanel = true, creamPanel = true)
    private val artwork = VehicleArtwork(context, style)
    internal var showHeading = true
    private var state: CollectionScreenState = CollectionScreenState.Loading
    private var controlsEnabled = false
    private var retryPending = false
    private var filter: CollectionState? = null
    /** 필터마다 마지막 스크롤 위치. 다시 그려도(로딩·오류·제한 뒤 복귀) 같은 자리로 돌아온다. */
    private val positions = mutableMapOf<CollectionState?, Int>()
    private var viewportWidth = 0
    private var viewportHeight = 0
    private val fontScale get() = resources.configuration.fontScale
    private val wide get() = viewportWidth >= style.dp(700) && fontScale <= 1.3f

    init {
        orientation = VERTICAL
        setBackgroundColor(style.base)
        setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(8))
    }

    fun render(value: CollectionScreenState, controlsEnabled: Boolean) {
        state = value
        this.controlsEnabled = controlsEnabled
        retryPending = false
        drawScreen()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (viewportWidth != measuredWidth || viewportHeight != measuredHeight) {
            viewportWidth = measuredWidth
            viewportHeight = measuredHeight
            drawScreen()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun drawScreen(): Unit = with(style) {
        val focusedTag = findFocus()?.tag
        removeAllViews()
        if (!controlsEnabled) {
            addView(text(s(R.string.garage_restricted), 28, bold = true))
            addView(text(s(R.string.garage_restricted_body), color = muted))
            return@with
        }

        if (showHeading) addView(heading(s(R.string.garage_collection_title), ReferenceHudIcon.COLLECTION))
        val body = card().apply { setPadding(dp(10), dp(6), dp(10), dp(8)) }
        val shownFilter = filter
        val viewport = scroll(body, positions[shownFilter] ?: 0).apply {
            tag = "collection:scroll"
            isVerticalScrollBarEnabled = true
            setOnScrollChangeListener { _, _, y, _, _ -> if (parent === this@CollectionView) positions[shownFilter] = y }
        }
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
        val columns = if (viewportWidth >= dp(520) && fontScale <= 1.3f) 4 else 2
        column().apply {
            options.chunked(columns).forEach { line ->
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

    private fun showContent(body: LinearLayout, content: CollectionScreenState.Content) = with(style) {
        sourceText(content.sourceLabel)?.let { body.addView(text(it, 14, muted)) }
        if (content.entries.isEmpty()) {
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
            add(body, text(s(R.string.garage_collection_filter_empty), 18, bold = true))
            return@with
        }

        // 모든 차종을 한 목록에 두고 위아래로 스크롤해 본다
        if (wide) add(body, rarityHeaders(), gap = 4)
        vehicles.forEach { add(body, vehicleRow(it), gap = 4) }
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
        val columns = when {
            wide -> 4
            viewportWidth >= dp(290) -> 2
            else -> 1
        }
        column().apply {
            if (!wide) addView(model, LayoutParams(-1, -2))
            Rarity.entries.chunked(columns).forEach { line ->
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
                addView(artwork.view(value.entry.key.vehicleId, rarity, 44, value.entry.state, showRing = false).apply {
                    background = null
                }, LayoutParams(-1, dp(44)))
            }
            addView(text(status, 14, ink, bold = true).apply { gravity = Gravity.CENTER }, LayoutParams(-1, -2))
        }
    }
}
