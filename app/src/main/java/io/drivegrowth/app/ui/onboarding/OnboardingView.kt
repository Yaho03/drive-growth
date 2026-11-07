package io.drivegrowth.app.ui.onboarding

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.core.contract.VehicleId

/** 실제 지급·저장·탐색을 호출하지 않고 호스트가 전달한 단계와 확정 결과만 표시한다. */
class OnboardingView(context: Context, onEvent: (OnboardingEvent) -> Unit) : LinearLayout(context) {
    private val style = GarageStyle(context)
    private val interaction = OnboardingInteraction(onEvent)
    private val accent = Color.rgb(141, 106, 238)
    private var wide = false
    private var scroll: ScrollView? = null
    private var savedY = 0
    private var previousScene: Class<*>? = null
    val selectedVehicleId: VehicleId? get() = interaction.selectedId
    val pendingVehicleId: VehicleId? get() = interaction.pendingSelection

    init { orientation = VERTICAL; setBackgroundColor(style.base) }

    /** false인 늦은/역행 응답은 표시하지 않는다. 호스트는 프로필·requestId·응답 순서도 대조한다. */
    fun render(state: OnboardingScreenState, controlsEnabled: Boolean): Boolean {
        val accepted = interaction.render(state, controlsEnabled)
        drawScreen()
        return accepted
    }

    fun restoreSelection(id: VehicleId?) { interaction.restoreSelection(id); drawScreen() }
    fun handleBack(): Boolean {
        val handled = interaction.handleBack()
        if (handled) drawScreen()
        return handled
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = w >= style.dp(760)
        if (wide != next) { wide = next; post { drawScreen() } }
    }

    private fun s(id: Int) = context.getString(id)
    private fun drawScreen(): Unit = with(style) {
        val scene = interaction.state.javaClass
        if (scene == previousScene) scroll?.takeIf { it.isLaidOut }?.let { savedY = it.scrollY } else savedY = 0
        previousScene = scene
        val focused = findFocus()?.tag
        removeAllViews(); scroll = null
        setPadding(dp(24), dp(16), dp(24), dp(16))
        if (!interaction.controlsEnabled) {
            addView(text(s(R.string.onboarding_restricted), 28, bold = true))
            addView(text(s(R.string.onboarding_restricted_body), color = muted))
            return@with
        }
        addView(text(s(R.string.onboarding_title), 32, bold = true))
        val body = column()
        scroll = style.scroll(body, savedY)
        addView(scroll, LayoutParams(-1, 0, 1f))
        when (val state = interaction.state) {
            OnboardingScreenState.Loading -> body.addView(text(s(R.string.onboarding_loading), 28))
            is OnboardingScreenState.Unavailable -> {
                heading(body, R.string.onboarding_unavailable_title)
                body.addView(text(state.message, 24))
            }
            is OnboardingScreenState.Intro -> {
                source(body, state.sourceLabel)
                heading(body, R.string.onboarding_intro_title, R.string.onboarding_intro_body)
                body.addView(art())
                benefits(body, state.plannedBenefits)
                action(body, R.string.onboarding_start, primary = true) { interaction.start() }
            }
            is OnboardingScreenState.Choosing -> {
                source(body, state.sourceLabel)
                heading(body, R.string.onboarding_choose_title, R.string.onboarding_choose_body)
                if (state.candidates.isEmpty()) {
                    heading(body, R.string.onboarding_empty, R.string.onboarding_empty_body)
                    action(body, R.string.onboarding_retry) { interaction.retry() }
                } else {
                    state.candidates.chunked(if (wide) 2 else 1).forEach { candidates ->
                        val row = row()
                        candidates.forEachIndexed { index, value ->
                            row.addView(candidate(value, true), LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(16) })
                        }
                        add(body, row, 16)
                    }
                    if (interaction.selectedCandidate == null) body.addView(text(s(if (interaction.selectedId == null)
                        R.string.onboarding_select_required else R.string.onboarding_selection_missing), color = muted))
                    action(body, R.string.onboarding_review, interaction.selectedCandidate?.selectable == true, true) { interaction.review() }
                }
                benefits(body, state.plannedBenefits)
                action(body, R.string.onboarding_previous) { interaction.handleBack() }
            }
            is OnboardingScreenState.Confirming -> {
                source(body, state.sourceLabel)
                heading(body, R.string.onboarding_confirm_title, R.string.onboarding_confirm_body)
                add(body, candidate(state.candidate, false))
                benefits(body, state.plannedBenefits)
                action(body, R.string.onboarding_confirm, state.candidate.selectable, true) {
                    interaction.confirm(state.candidate.vehicle.vehicleId)
                }
                action(body, R.string.onboarding_previous, state.backAllowed) { interaction.handleBack() }
                if (interaction.pendingSelection != null) body.addView(text(s(R.string.onboarding_submitting), 24))
            }
            is OnboardingScreenState.Submitting -> {
                source(body, state.sourceLabel)
                heading(body, R.string.onboarding_submitting, R.string.onboarding_pending_note)
                body.addView(text(state.vehicleName ?: s(R.string.onboarding_vehicle_missing), 28, bold = true))
            }
            is OnboardingScreenState.LoadError -> {
                heading(body, R.string.onboarding_load_error)
                body.addView(text(state.message, 24))
                action(body, R.string.onboarding_retry, state.retryAllowed) { interaction.retry() }
            }
            is OnboardingScreenState.GrantError -> {
                source(body, state.sourceLabel)
                heading(body, R.string.onboarding_grant_error, R.string.onboarding_recovery_note)
                body.addView(text(state.vehicleName ?: s(R.string.onboarding_vehicle_missing), 28, bold = true))
                body.addView(text(state.message, 24))
                action(body, R.string.onboarding_retry, state.retryAllowed, true) { interaction.retry() }
                action(body, R.string.onboarding_change, state.changeAllowed) { interaction.handleBack() }
                if (interaction.pendingSelection != null) body.addView(text(s(R.string.onboarding_submitting), 24))
            }
            is OnboardingScreenState.Completed -> {
                source(body, state.sourceLabel)
                heading(body, if (state.alreadyGranted) R.string.onboarding_already_granted else R.string.onboarding_complete,
                    R.string.onboarding_completed_note)
                add(body, card(true).apply {
                    addView(art())
                    addView(text(state.displayName, 30, bold = true))
                    addView(text(rarity(state.vehicle.rarity), 24, rarityColor(state.vehicle.rarity), true))
                    addView(text(context.getString(R.string.onboarding_level, state.vehicle.level), 24))
                    addView(text(context.getString(R.string.onboarding_acquisition, state.vehicle.acquisitionCost.units), 24))
                })
                if (state.summary.isEmpty()) body.addView(text(s(R.string.onboarding_result_missing), color = muted))
                else state.summary.forEach { add(body, text(it, 24)) }
                add(body, text(s(R.string.onboarding_next), color = muted))
                action(body, R.string.onboarding_to_shop, primary = true) { interaction.goToShop() }
            }
        }
        if (focused != null) findViewWithTag<View>(focused)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun heading(body: LinearLayout, title: Int, description: Int? = null) = with(style) {
        add(body, text(s(title), 30, bold = true))
        if (description != null) add(body, text(s(description), 24, muted))
    }
    private fun source(body: LinearLayout, label: String) { body.addView(style.text(label, color = style.muted)) }
    private fun benefits(body: LinearLayout, lines: List<String>) = with(style) {
        add(body, card().apply {
            addView(text(s(R.string.onboarding_planned_title), 24, bold = true))
            if (lines.isEmpty()) addView(text(s(R.string.onboarding_benefits_missing), color = muted))
            else lines.forEach { addView(text(it, 24)) }
            addView(text(s(R.string.onboarding_planned_note), color = muted))
        })
    }
    private fun candidate(value: OnboardingCandidate, selectable: Boolean) = with(style) {
        val selected = interaction.selectedId == value.vehicle.vehicleId
        card(selected).apply {
            addView(art())
            addView(text(value.vehicle.displayName, 28, bold = true))
            addView(text(value.description ?: s(R.string.onboarding_description_missing), 24, muted))
            addView(text(s(R.string.onboarding_first_vehicle), color = muted))
            addView(text(s(R.string.onboarding_game_note), color = muted))
            if (!value.selectable) addView(text(value.unavailableReason ?: s(R.string.onboarding_unavailable), color = muted))
            if (selected) addView(text(s(R.string.onboarding_selected), 24, bold = true))
            if (selectable) action(this, R.string.onboarding_select, value.selectable, selected, value.vehicle.vehicleId, "${value.vehicle.displayName}, ${s(R.string.onboarding_select)}") {
                interaction.select(value.vehicle.vehicleId)
            }
        }
    }
    private fun art() = style.column().apply {
        addView(ImageView(context).apply {
            setImageResource(R.drawable.garage_vehicle_placeholder)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LayoutParams(-1, style.dp(88)))
        addView(style.text(s(R.string.onboarding_art), color = style.muted))
    }
    private fun action(body: LinearLayout, label: Int, enabled: Boolean = true, primary: Boolean = false,
                       tag: Any = label, description: String? = null, callback: () -> Boolean) = with(style) {
        val available = enabled && !interaction.inputPending && interaction.pendingSelection == null
        add(body, button(s(label), available) { callback(); drawScreen() }.apply {
            this.tag = tag
            if (description != null) contentDescription = description
            if (primary && available) background = shape(accent)
        }, 16)
    }
}
