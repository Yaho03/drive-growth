package io.drivegrowth.app.ui.garage

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.core.garage.CollectionState

/** 차종 × 희귀도 기록을 스크롤로 표시한다. 보유 이력과 해금은 서로 독립된 입력이다. */
class CollectionView(context: Context, private val onRetry: () -> Unit) : LinearLayout(context) {
    private val style = GarageStyle(context)
    private var state: CollectionScreenState = CollectionScreenState.Loading
    private var enabled = false
    private var retryPending = false
    private var columns = 1
    private var scroll: ScrollView? = null
    private var savedScrollY = 0
    private var showingContent = false

    init { orientation = VERTICAL; setBackgroundColor(style.base) }

    fun render(value: CollectionScreenState, controlsEnabled: Boolean) {
        state = value; enabled = controlsEnabled; retryPending = false
        drawScreen()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = when { w >= style.dp(1100) -> 4; w >= style.dp(600) -> 2; else -> 1 }
        if (next != columns) { columns = next; post { drawScreen() } }
    }

    private fun s(id: Int) = context.getString(id)
    private fun drawScreen(): Unit = with(style) {
        if (showingContent) scroll?.takeIf { it.isLaidOut }?.let { savedScrollY = it.scrollY }
        showingContent = enabled && state is CollectionScreenState.Content
        removeAllViews(); setPadding(dp(24), dp(16), dp(24), dp(16))
        if (!enabled) {
            addView(text(s(R.string.garage_restricted), 28, bold = true))
            addView(text(s(R.string.garage_restricted_body), color = muted))
            scroll = null
            return@with
        }
        addView(text(s(R.string.garage_collection_title), 32, bold = true))
        val body = column()
        val currentScroll = style.scroll(body, savedScrollY)
        scroll = currentScroll
        addView(currentScroll, LayoutParams(-1, 0, 1f))
        when (val current = state) {
            CollectionScreenState.Loading -> body.addView(text(s(R.string.garage_collection_loading), 24))
            is CollectionScreenState.Error -> {
                body.addView(text(s(R.string.garage_error), 28, bold = true))
                body.addView(text(current.message))
                add(body, button(s(R.string.garage_retry), !retryPending) {
                    if (!retryPending && enabled) { retryPending = true; drawScreen(); onRetry() }
                })
            }
            is CollectionScreenState.Content -> {
                body.addView(text(current.sourceLabel, color = muted))
                body.addView(text(s(R.string.garage_collection_note), color = muted))
                if (current.entries.isEmpty()) {
                    add(body, text(s(R.string.garage_collection_empty), 28, bold = true))
                    body.addView(text(s(R.string.garage_collection_empty_body), color = muted))
                }
                current.entries.groupBy { it.entry.key.vehicleId }.values.forEach { entries ->
                    add(body, text(entries.first().name, 28, bold = true), 24)
                    entries.chunked(columns).forEach { chunk ->
                        val line = row()
                        chunk.forEachIndexed { index, value ->
                            line.addView(entryCard(value), LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(16) })
                        }
                        repeat(columns - chunk.size) { line.addView(View(context), LayoutParams(0, 1, 1f).apply { marginStart = dp(16) }) }
                        add(body, line)
                    }
                }
            }
        }
    }

    private fun entryCard(value: CollectionPresentation) = with(style) {
        card().apply {
            addView(text(rarity(value.entry.key.rarity), 24, rarityColor(value.entry.key.rarity), true))
            addView(text(s(when (value.entry.state) {
                CollectionState.UNDISCOVERED -> R.string.garage_undiscovered
                CollectionState.DISCOVERED -> R.string.garage_discovered
                CollectionState.OWNED -> R.string.garage_ever_owned
            }), 24, bold = true))
            addView(text(value.unlockLabel ?: s(R.string.garage_unlock_missing), color = muted))
        }
    }
}
