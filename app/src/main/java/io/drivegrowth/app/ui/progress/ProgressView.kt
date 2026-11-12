package io.drivegrowth.app.ui.progress

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.core.driving.RestOutcome

/** 주차 허용 영역에 호스트가 넣는 표시 View. 저장·차량 SDK·경제 계산을 호출하지 않는다. */
class ProgressView(context: Context, onEvent: (ProgressEvent) -> Unit) : LinearLayout(context) {
    private val style = ProgressStyle(context)
    private val interaction = ProgressInteraction(onEvent)
    private var wide = false
    private var scroll: ScrollView? = null
    private var listY = 0
    private var detailY = 0
    private var showingDetail = false
    val uiState: ProgressUiState get() = interaction.uiState

    init { orientation = VERTICAL; setBackgroundColor(style.base) }
    fun render(state: ProgressScreenState, controlsEnabled: Boolean): Boolean {
        val accepted = interaction.render(state, controlsEnabled); drawScreen(); return accepted
    }
    fun restoreUiState(state: ProgressUiState) { interaction.restoreUiState(state); drawScreen() }
    fun completeRetry() { interaction.completeRetry(); drawScreen() }
    fun completePendingRecovery(resolved: Set<ClaimReference>) { interaction.completePendingRecovery(resolved); drawScreen() }
    fun handleBack(): Boolean {
        if (!interaction.closeDetail()) return false
        detailY = 0; drawScreen(); return true
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = w >= style.dp(1000)
        if (next != wide) { wide = next; post { drawScreen() } }
    }
    private fun s(id: Int) = context.getString(id)
    private fun rememberScrollPosition(current: ScrollView) {
        if (scroll !== current || !current.isLaidOut) return
        if (showingDetail) detailY = current.scrollY else listY = current.scrollY
    }
    private fun drawScreen(): Unit = with(style) {
        scroll?.let(::rememberScrollPosition)
        val focusedTag = findFocus()?.tag
        scroll = null; removeAllViews(); setPadding(dp(24), dp(16), dp(24), dp(16))
        if (!interaction.controlsEnabled) {
            addView(text(s(R.string.progress_restricted), 28, bold = true))
            addView(text(s(R.string.progress_restricted_body))); return@with
        }
        addView(text(s(R.string.progress_title), 32, bold = true))
        val showingContent = interaction.state is ProgressScreenState.Content
        showingDetail = showingContent && interaction.uiState.selectedKey != null
        val body = column()
        if (uiState.pendingConflicts.isNotEmpty()) {
            add(body, text(s(R.string.progress_recovery_required), color = amber))
            add(body, button(s(if (uiState.retryPending) R.string.progress_retry_pending else R.string.progress_retry), interaction.canRetry()) {
                interaction.retry(); drawScreen()
            })
        }
        when (val state = interaction.state) {
            ProgressScreenState.Loading -> add(body, text(s(R.string.progress_loading)))
            is ProgressScreenState.Empty -> { add(body, text(s(R.string.progress_empty), 26, bold = true)); add(body, text(state.message)) }
            is ProgressScreenState.Unsupported -> { add(body, text(s(R.string.progress_unsupported), 26, bold = true)); add(body, text(state.message)) }
            is ProgressScreenState.Failure -> {
                add(body, text(s(R.string.progress_failure), 26, bold = true)); add(body, text(state.message))
                if (state.retryAllowed && uiState.pendingConflicts.isEmpty()) add(body, button(s(if (uiState.retryPending) R.string.progress_retry_pending else R.string.progress_retry), !uiState.retryPending) {
                    interaction.retry(); drawScreen()
                })
            }
            is ProgressScreenState.Content -> {
                add(body, text(state.sourceLabel, 18, amber), 0)
                if (showingDetail) detail(body) else overview(body, state)
            }
        }
        val y = when {
            !showingContent -> 0
            showingDetail -> detailY
            else -> listY
        }
        val bodyScroll = object : ScrollView(context) {
            private var restore = true
            init { isFillViewport = true; addView(body) }
            override fun onDetachedFromWindow() {
                // 호스트가 제한 화면을 그리기 전에 이 View를 먼저 분리할 수 있다.
                rememberScrollPosition(this)
                super.onDetachedFromWindow()
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                if (restore) { restore = false; scrollTo(0, y) }
            }
        }
        // 중간 안내 화면이 목록이나 상세의 마지막 위치를 덮어쓰지 않게 한다.
        if (showingContent) scroll = bodyScroll
        addView(bodyScroll, LayoutParams(-1, 0, 1f))
        if (focusedTag != null) findViewWithTag<View>(focusedTag)?.requestFocus()
    }
    private fun overview(body: LinearLayout, state: ProgressScreenState.Content) = with(style) {
        val showActiveRestFirst = !wide && state.rest?.buffPhase == BuffPhase.ACTIVE
        if (showActiveRestFirst) rest(body, state.rest, prioritizeStoredState = true)
        add(body, text(state.periodLabel ?: s(R.string.progress_unknown_period), 18, muted))
        val left = column(); val right = column()
        val tabs = row()
        for (group in listOf(ProgressGroup.DAILY, ProgressGroup.WEEKLY)) {
            val label = s(if (group == ProgressGroup.DAILY) R.string.progress_daily else R.string.progress_weekly)
            val selected = group == uiState.group
            tabs.addView(button(if (selected) context.getString(R.string.progress_selected, label) else label, primary = selected) {
                interaction.changeGroup(group); listY = 0; scroll = null; drawScreen()
            }.apply { tag = group; isSelected = selected }, LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        }
        add(left, HorizontalScrollView(context).apply { addView(tabs); isFillViewport = true }, 0)
        items(left, state.entries.filter { it.group == uiState.group })
        add(right, text(s(R.string.progress_achievements), 26, bold = true), 0)
        items(right, state.entries.filter { it.group == ProgressGroup.ACHIEVEMENT })
        if (wide) {
            val columns = row()
            columns.addView(left, LayoutParams(0, -2, 1f).apply { marginEnd = dp(20) })
            columns.addView(right, LayoutParams(0, -2, 1f)); add(body, columns)
        } else { add(body, left); add(body, right) }
        add(body, text(s(R.string.progress_events), 26, bold = true))
        val events = state.entries.filter { it.group == ProgressGroup.EVENT }
        if (events.isEmpty()) add(body, text(state.eventsNote, color = muted)) else items(body, events)
        if (!showActiveRestFirst) rest(body, state.rest)
    }
    private fun items(parent: LinearLayout, entries: List<ProgressItem>) = with(style) {
        if (entries.isEmpty()) add(parent, text(s(R.string.progress_group_empty), color = muted))
        for (item in entries) {
            val card = card()
            add(card, text(item.title, 24, bold = true), 0)
            add(card, text(status(item), color = if (item.status == RewardStatus.CLAIMED) green else amber), 0)
            progress(card, item)
            add(card, text(item.rewardLabel ?: s(R.string.progress_unknown_reward), color = muted), 0)
            item.note?.let { add(card, text(it, 18, muted), 0) }
            add(card, button(s(R.string.progress_details), primary = item.status == RewardStatus.CLAIMABLE) {
                interaction.select(item.key); detailY = 0; drawScreen()
            }.apply { tag = item.key })
            add(parent, card)
        }
    }
    private fun progress(parent: LinearLayout, item: ProgressItem) = with(style) {
        add(parent, text(item.progressLabel ?: s(R.string.progress_unknown_progress), color = muted), 0)
        item.progressPercent?.let { percent ->
            val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = percent; progressTintList = ColorStateList.valueOf(green)
                progressBackgroundTintList = ColorStateList.valueOf(inset)
                contentDescription = item.progressLabel ?: s(R.string.progress_unknown_progress)
            }
            add(parent, bar, 4)
        }
    }
    private fun detail(parent: LinearLayout) = with(style) {
        val item = interaction.selectedItem
        if (item == null) {
            add(parent, text(s(R.string.progress_missing), 26, bold = true)); add(parent, text(s(R.string.progress_missing_body)))
        } else {
            val card = card()
            add(card, text(item.title, 28, bold = true), 0); add(card, text(status(item), color = amber)); progress(card, item)
            add(card, text(s(R.string.progress_confirm_title), 24, bold = true))
            add(card, text(item.rewardLabel ?: s(R.string.progress_unknown_reward), 24))
            add(card, text(s(R.string.progress_confirm_body)))
            item.note?.let { add(card, text(it)) }
            val pending = interaction.isPending(item)
            val enabled = interaction.canClaim()
            add(card, button(s(if (pending) R.string.progress_pending else if (enabled) R.string.progress_claim else R.string.progress_no_claim), enabled, true) {
                interaction.claim(item.claim?.let { ClaimReference(item.key, item.revision, it.requestKey) }); drawScreen()
            }.apply { tag = "progress-claim" })
            if (pending) add(card, text(s(R.string.progress_pending_body), color = amber))
            else if (!enabled && item.status in listOf(RewardStatus.CLAIMABLE, RewardStatus.FAILED, RewardStatus.COMPLETE))
                add(card, text(item.claim?.reason ?: s(R.string.progress_claim_unconnected), color = muted))
            add(parent, card)
        }
        add(parent, button(s(R.string.progress_close)) { handleBack() })
    }
    private fun status(item: ProgressItem): String = s(if (interaction.isPending(item)) R.string.progress_pending else when (item.status) {
        RewardStatus.LOCKED -> R.string.progress_locked
        RewardStatus.IN_PROGRESS -> R.string.progress_in_progress
        RewardStatus.COMPLETE -> R.string.progress_complete
        RewardStatus.CLAIMABLE -> R.string.progress_claimable
        RewardStatus.PENDING -> R.string.progress_pending
        RewardStatus.CLAIMED -> R.string.progress_claimed
        RewardStatus.EXPIRED -> R.string.progress_expired
        RewardStatus.FAILED -> R.string.progress_failed
        RewardStatus.UNSUPPORTED -> R.string.progress_unsupported
    })
    private fun rest(parent: LinearLayout, value: RestPresentation?, prioritizeStoredState: Boolean = false) = with(style) {
        add(parent, text(s(R.string.progress_rest_title), 26, bold = true))
        val card = card()
        if (value == null) add(card, text(s(R.string.progress_rest_missing)), 0)
        else {
            val recognition = when (value.recognition) {
                RestOutcome.ACCEPTED -> R.string.progress_rest_accepted
                RestOutcome.NOT_REST_PLACE -> R.string.progress_rest_place
                RestOutcome.DRIVING_TOO_SHORT -> R.string.progress_rest_driving
                RestOutcome.PARKING_TOO_SHORT -> R.string.progress_rest_parking
                RestOutcome.ALREADY_HANDLED -> R.string.progress_rest_handled
                RestOutcome.UNCONFIRMED, null -> R.string.progress_rest_unconfirmed
            }
            add(card, text(s(recognition), 24, bold = true), 0)
            for (condition in value.conditions) {
                val id = when (condition.satisfied) { true -> R.string.progress_condition_yes; false -> R.string.progress_condition_no; null -> R.string.progress_condition_unknown }
                add(card, text(context.getString(id, condition.label)), 0)
            }
            val phase = when (value.buffPhase) {
                BuffPhase.INACTIVE -> R.string.progress_buff_inactive
                BuffPhase.NEXT_DEPARTURE -> R.string.progress_buff_ready
                BuffPhase.ACTIVE -> R.string.progress_buff_active
                BuffPhase.UNCONFIRMED -> R.string.progress_buff_unconfirmed
                BuffPhase.UNSUPPORTED -> R.string.progress_buff_unsupported
            }
            add(card, text(s(phase), 24, amber, true))
            val effect = text(value.effectLabel ?: s(R.string.progress_unknown_effect))
            if (!prioritizeStoredState) add(card, effect)
            add(card, text(value.remainingLabel ?: s(R.string.progress_unknown_remaining)))
            value.note?.let { add(card, text(it, color = muted)) }
            if (prioritizeStoredState) add(card, effect)
        }
        add(card, text(s(R.string.progress_rest_note), 18, muted)); add(parent, card)
    }
}
