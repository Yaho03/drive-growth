package io.drivegrowth.app.ui.ranking

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.host.ranking.DemoRankingProvider
import io.drivegrowth.app.host.ranking.RankingMetric
import io.drivegrowth.app.host.ranking.RankingState
import io.drivegrowth.app.host.ranking.RankingValue
import io.drivegrowth.app.host.ranking.frozen
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.app.ui.progress.ProgressStyle
import io.drivegrowth.app.ui.shop.ShopStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class RankingMode { MY_RECORD, DEMO }
data class RankingUiState(val mode:RankingMode=RankingMode.MY_RECORD,val metric:RankingMetric=RankingMetric.SHOP_LEVEL,
    val scrollY:Int=0,val retryPending:Boolean=false) { init { require(scrollY>=0) } }
sealed interface RankingEvent { data object Retry:RankingEvent }

/** 실제 내 기록과 시연 순위를 보여 주는 View. 합성 시연 순위와 실제 기록의 출처를 분리한다. */
class RankingView(context: Context, private val onEvent: (RankingEvent) -> Unit) : LinearLayout(context) {
    internal var showHeading = true
    private val style = ProgressStyle(context)
    private val scrollStyle = ShopStyle(context)
    private val demo = DemoRankingProvider()
    private val timestamp = DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))
    private var saved = RankingUiState()
    private var state: RankingState = RankingState.Loading
    private var allowed = false
    private var scroll: ScrollView? = null
    private var twoColumns = true
    val uiState: RankingUiState get() = saved.copy(scrollY = scroll?.takeIf { it.isLaidOut }?.scrollY ?: saved.scrollY)

    init { orientation = VERTICAL; setBackgroundColor(style.base) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = w >= style.dp(400)
        if (next != twoColumns) {
            twoColumns = next
            post { saved = uiState; paint() }
        }
    }

    fun restoreUiState(value: RankingUiState) {
        saved = value.copy()
        paint()
    }

    fun render(value: RankingState, controlsEnabled: Boolean) {
        val previous = state
        val previouslyAllowed = allowed
        val previousUi = uiState
        saved = previousUi
        state = if (value is RankingState.Content) value.copy(snapshot = value.snapshot.frozen()) else value
        allowed = controlsEnabled && value != RankingState.Restricted
        if (value is RankingState.Content || value is RankingState.Failure) saved = saved.copy(retryPending = false)
        if (childCount == 0 || previous != state || previouslyAllowed != allowed || previousUi != saved) paint()
    }

    fun handleBack(): Boolean {
        if (!allowed || saved.mode == RankingMode.MY_RECORD) return false
        saved = uiState.copy(mode = RankingMode.MY_RECORD)
        paint()
        return true
    }

    private fun paint(): Unit = with(style) {
        removeAllViews()
        scroll = null
        if (!allowed) {
            addView(text("주차 후 조작이 허용되면 이용할 수 있습니다."))
            return@with
        }
        val body = column().apply { setPadding(dp(16), dp(12), dp(16), dp(16)) }
        if (showHeading) body.addView(text("기록과 랭킹", 24, bold = true).apply {
            background = surface()
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setCompoundDrawables(icon(ReferenceHudIcon.RECORD), null, null, null)
            compoundDrawablePadding = dp(8)
        })
        val modes = row()
        listOf(RankingMode.MY_RECORD to "내 기록", RankingMode.DEMO to "예시 랭킹").forEach { (mode, label) ->
            addTab(modes, label, saved.mode == mode) {
                if (allowed) { saved = uiState.copy(mode = mode); paint() }
            }
        }
        body.addView(tabStrip(modes))
        if (saved.mode == RankingMode.DEMO) showDemo(body) else showMine(body)
        scroll = scrollStyle.scroll(body, saved.scrollY).also { addView(it, LayoutParams(-1, -1)) }
    }

    private fun showMine(body: LinearLayout): Unit = with(style) {
        val card = card().apply { addView(text("내 기록", 22, bold = true)) }
        add(body, card)
        when (val current = state) {
            RankingState.Loading -> card.addView(text("기록을 조회하고 있습니다.", 16))
            is RankingState.Failure -> {
                card.addView(text("기록을 읽지 못했습니다. 다시 조회해 주세요.", 16))
                retry(card)
            }
            is RankingState.Content -> {
                val data = current.snapshot
                card.addView(text("조회 기준 ${timestamp.format(Instant.ofEpochMilli(data.asOf.value))} 한국시간", 15, muted))
                RankingMetric.entries.chunked(if (twoColumns) 2 else 1).forEach { metrics ->
                    val cells = row().apply { isBaselineAligned = false }
                    metrics.forEachIndexed { index, metric ->
                        val item = tile().apply {
                            addView(text(metric.title, 15, muted, bold = true))
                            when (val value = data.metrics[metric]) {
                                is RankingValue.Available -> {
                                    addView(text("${value.amount.stripTrailingZeros().toPlainString()} ${metric.unit}", 22, bold = true))
                                    addView(text(value.basis, 14, muted))
                                }
                                is RankingValue.Empty -> addView(text(value.reason, 15))
                                is RankingValue.Missing -> addView(text("확인 불가 · ${value.reason}", 15))
                                is RankingValue.Failure -> addView(text("조회 실패 · 기록을 확인할 수 없습니다.", 15))
                                null -> addView(text("확인 불가 · 이 기록을 확인할 수 없어요.", 15))
                            }
                        }
                        cells.addView(item, LayoutParams(0, -1, 1f).apply {
                            if (index != 0) marginStart = dp(10)
                        })
                    }
                    add(card, cells, gap = 10)
                }
                retry(card)
            }
            RankingState.Restricted -> Unit
        }
    }

    private fun showDemo(body: LinearLayout): Unit = with(style) {
        val selectors = row()
        RankingMetric.entries.forEach { metric ->
            addTab(selectors, metric.title, saved.metric == metric) {
                if (allowed) { saved = uiState.copy(metric = metric); paint() }
            }
        }
        add(body, tabStrip(selectors), gap = 8)
        add(body, card().apply {
            addView(text("${saved.metric.title} 순위", 22, bold = true))
            addView(text("예시 랭킹", 15, ink, bold = true).apply {
                background = surface(selected = true)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }, LayoutParams(-2, -2))
            addView(text("예시 데이터로 만든 순위예요. 점수가 같으면 같은 순위로 표시해요.", 14, muted))
            val rows = demo.rows(saved.metric)
            val tied = rows.groupingBy { it.rank }.eachCount()
            rows.forEach { entry ->
                add(this, row().apply {
                    isBaselineAligned = false
                    gravity = Gravity.CENTER_VERTICAL
                    background = surface()
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    addView(text("${entry.rank}위 · ${entry.entry.label}", 17, bold = true).apply {
                        if ((tied[entry.rank] ?: 0) > 1) contentDescription = "공동 ${entry.rank}위 · ${entry.entry.label}"
                    }, LayoutParams(0, -2, 1f))
                    addView(text("${entry.value.stripTrailingZeros().toPlainString()} ${saved.metric.unit}", 16, bold = true)
                        .apply { gravity = Gravity.END }, LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                }, gap = 10)
            }
        })
    }

    private fun addTab(row: LinearLayout, label: String, selected: Boolean, action: () -> Unit) = with(style) {
        val tab = button(label, primary = selected, action = action).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(48)
            minimumHeight = minHeight
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isSelected = selected
        }
        row.addView(tab, LayoutParams(-2, -2).apply { if (row.childCount > 0) marginStart = dp(6) })
    }

    private fun tabStrip(tabs: View) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(tabs)
    }

    private fun tile() = style.column().apply {
        background = style.surface()
        setPadding(style.dp(12), style.dp(8), style.dp(12), style.dp(8))
    }

    private fun retry(body: LinearLayout): Unit = with(style) {
        add(body, button(if (saved.retryPending) "조회 중" else "다시 조회", !saved.retryPending) {
            if (allowed && !saved.retryPending) {
                saved = uiState.copy(retryPending = true)
                paint()
                onEvent(RankingEvent.Retry)
            }
        }.apply {
            textSize = 18f
            minHeight = dp(52)
            minimumHeight = minHeight
            setPadding(dp(16), dp(8), dp(16), dp(8))
        })
    }
}
