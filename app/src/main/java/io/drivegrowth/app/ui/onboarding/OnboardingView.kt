package io.drivegrowth.app.ui.onboarding

import io.drivegrowth.app.ui.pixel.PlayerText
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.app.ui.presentation.RewardCelebrationKind
import io.drivegrowth.app.ui.presentation.RewardCelebrationView
import io.drivegrowth.core.contract.VehicleId
import java.io.IOException

/** 실제 지급·저장·탐색을 호출하지 않고 호스트가 전달한 단계와 확정 결과만 표시한다. */
class OnboardingView(context: Context, onEvent: (OnboardingEvent) -> Unit) : LinearLayout(context) {
    private val style = GarageStyle(context)
    internal var onCelebration: ((RewardCelebrationKind) -> Unit)? = null
    private val celebration = RewardCelebrationView(context).apply {
        onStarted = { onCelebration?.invoke(it) }
    }
    private var confirmedChoice: VehicleId? = null
    private val interaction = OnboardingInteraction { event ->
        if (event is OnboardingEvent.ConfirmSelection) confirmedChoice = event.vehicleId
        onEvent(event)
    }
    private val sprites = VehicleSprites(context)
    private val staffBitmap by lazy(LazyThreadSafetyMode.NONE) {   // 안내하는 정비사(마을 시안 그림)
        try {
            context.assets.open("pixel/prototype-actors/mechanic.png").use { BitmapFactory.decodeStream(it)?.apply { density = Bitmap.DENSITY_NONE } }
        } catch (_: IOException) { null }
    }
    private val townBitmap by lazy(LazyThreadSafetyMode.NONE) {
        try {
            context.assets.open("pixel/town.png").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                    ?.apply { density = Bitmap.DENSITY_NONE }
            }
        } catch (_: IOException) {
            null
        }
    }
    private var wide = false
    private var scroll: ScrollView? = null
    private var savedY = 0
    private var previousScene: Class<*>? = null
    val selectedVehicleId: VehicleId? get() = interaction.selectedId
    val pendingVehicleId: VehicleId? get() = interaction.pendingSelection

    init { orientation = VERTICAL; setBackgroundColor(PixelStyle.background) }

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
        celebration.rebuildContent { removeAllViews() }; scroll = null
        setPadding(dp(PixelStyle.panelPaddingDp), dp(PixelStyle.gapDp), dp(PixelStyle.panelPaddingDp), dp(PixelStyle.gapDp))
        if (!interaction.controlsEnabled) {
            celebration.render(null, RewardCelebrationKind.FIRST_GRANT)
            confirmedChoice = null
            addView(label(s(R.string.onboarding_restricted), 28, bold = true))
            addView(label(s(R.string.onboarding_restricted_body), color = PixelStyle.muted))
            return@with
        }
        addView(label(s(R.string.onboarding_title), 26, bold = true))
        val body = column()
        scroll = style.scroll(body, savedY)
        addView(scroll, LayoutParams(-1, 0, 1f))
        when (val state = interaction.state) {
            OnboardingScreenState.Loading -> body.addView(label(s(R.string.onboarding_loading), 28))
            is OnboardingScreenState.Unavailable -> {
                heading(body, R.string.onboarding_unavailable_title)
                body.addView(label(state.message, 24))
            }
            is OnboardingScreenState.Intro -> {
                source(body, state.sourceLabel)
                intro(body, state)
                action(this@OnboardingView, R.string.onboarding_start, primary = true) { interaction.start() }
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
                            row.addView(candidate(value, true), LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(PixelStyle.gapDp) })
                        }
                        add(body, row, PixelStyle.gapDp)
                    }
                    if (interaction.selectedCandidate == null) body.addView(label(s(if (interaction.selectedId == null)
                        R.string.onboarding_select_required else R.string.onboarding_selection_missing), color = PixelStyle.muted))
                }
                action(body, R.string.onboarding_previous) { interaction.handleBack() }
                if (state.candidates.isNotEmpty())
                    action(this@OnboardingView, R.string.onboarding_review,
                        interaction.selectedCandidate?.selectable == true, true) { interaction.review() }
            }
            is OnboardingScreenState.Confirming -> {
                source(body, state.sourceLabel)
                heading(body, R.string.onboarding_confirm_title, R.string.onboarding_confirm_body)
                add(body, candidate(state.candidate, false))
                benefits(body, state.plannedBenefits)
                action(body, R.string.onboarding_previous, state.backAllowed) { interaction.handleBack() }
                if (interaction.pendingSelection != null) body.addView(label(s(R.string.onboarding_submitting), 24))
                action(this@OnboardingView, R.string.onboarding_confirm, state.candidate.selectable, true) {
                    interaction.confirm(state.candidate.vehicle.vehicleId)
                }
            }
            is OnboardingScreenState.Submitting -> {
                source(body, state.sourceLabel)
                heading(body, R.string.onboarding_submitting, R.string.onboarding_pending_note)
                body.addView(label(state.vehicleName ?: s(R.string.onboarding_vehicle_missing), 28, bold = true))
            }
            is OnboardingScreenState.LoadError -> {
                heading(body, R.string.onboarding_load_error)
                body.addView(label(state.message, 24))
                action(body, R.string.onboarding_retry, state.retryAllowed) { interaction.retry() }
            }
            is OnboardingScreenState.GrantError -> {
                source(body, state.sourceLabel)
                heading(body, R.string.onboarding_grant_error, R.string.onboarding_recovery_note)
                body.addView(label(state.vehicleName ?: s(R.string.onboarding_vehicle_missing), 28, bold = true))
                body.addView(label(state.message, 24))
                action(body, R.string.onboarding_retry, state.retryAllowed, true) { interaction.retry() }
                action(body, R.string.onboarding_change, state.changeAllowed) { interaction.handleBack() }
                if (interaction.pendingSelection != null) body.addView(label(s(R.string.onboarding_submitting), 24))
            }
            is OnboardingScreenState.Completed -> {
                source(body, state.sourceLabel)
                heading(body, if (state.alreadyGranted) R.string.onboarding_already_granted else R.string.onboarding_complete,
                    R.string.onboarding_completed_note)
                completedRewards(body, state)
                add(body, label(s(R.string.onboarding_next), color = PixelStyle.muted))
                action(this@OnboardingView, R.string.onboarding_to_shop, primary = true) { interaction.goToShop() }
            }
        }
        if (focused != null) findViewWithTag<View>(focused)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun completedRewards(body: LinearLayout, state: OnboardingScreenState.Completed) = with(style) {
        // 호스트의 확정 표시 문구를 분류하고 금액·공간 값은 문구 그대로 보존한다.
        val money = state.summary.firstOrNull { it.startsWith("최초 확정 게임머니 ") || it.startsWith("초기 재화 ") }
        val spaces = state.summary.firstOrNull { it.startsWith("최초 차량 공간 ") || it.startsWith("차고 ") }
        val rewards = if (wide) row() else column()
        val vehicle = pixelCard(selected = true).apply {
            addView(label("첫 차량", color = PixelStyle.muted))
            val effectKey = state.vehicle.ownedVehicleId.value.takeIf { confirmedChoice == state.vehicle.vehicleId }
            celebration.render(effectKey, RewardCelebrationKind.FIRST_GRANT, vehicle = sprites.bitmap(state.vehicle.vehicleId))
            addVehicleArt(this, state.vehicle.vehicleId, celebrate = true)
            addView(label(state.displayName, 28, bold = true))
            addView(label(rarity(state.vehicle.rarity), 24, PixelStyle.rarityColor(state.vehicle.rarity), true))
            addView(label(context.getString(R.string.onboarding_level, state.vehicle.level), 24))
            addView(label(context.getString(R.string.onboarding_acquisition, state.vehicle.acquisitionCost.units), 24))
        }
        val cards = listOf(
            rewardCard("게임머니", "●", PixelStyle.gold, money),
            vehicle,
            rewardCard("주차 칸", "▥", PixelStyle.focus, spaces),
        )
        cards.forEachIndexed { index, card ->
            val params = if (wide) LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = dp(PixelStyle.gapDp) }
            else LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(PixelStyle.gapDp) }
            rewards.addView(card, params)
        }
        add(body, rewards)
        state.summary.filterNot { it == money || it == spaces }.forEach {
            add(body, label(it, color = PixelStyle.muted), PixelStyle.gapDp)
        }
        if (state.summary.isEmpty()) add(body, label(s(R.string.onboarding_result_missing), color = PixelStyle.muted))
    }

    private fun rewardCard(title: String, icon: String, color: Int, value: String?) = pixelCard().apply {
        addView(label(title, color = PixelStyle.muted))
        addView(label(icon, 48, color).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            background = pixelShape(PixelStyle.inset)
        }, LayoutParams(-1, style.dp(GRANT_ART_HEIGHT_DP)))
        addView(if (value != null) label(value, 24, bold = true)
            else label("확정 정보 없음", color = PixelStyle.muted))
    }

    private fun intro(body: LinearLayout, state: OnboardingScreenState.Intro) = with(style) {
        val welcome = column().apply {
            add(this, pixelCard().apply {
                addView(row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    staffBitmap?.let { addView(artwork(BitmapDrawable(resources, it).apply { setFilterBitmap(false) }).apply { background = null },
                        LayoutParams(dp(40), dp(64))) }
                    addView(label(s(R.string.onboarding_guide), color = PixelStyle.muted), LayoutParams(-2, -2).apply { marginStart = dp(8) })
                })
                addView(label(s(R.string.onboarding_intro_title), 40, bold = true))
                addView(label(s(R.string.onboarding_intro_body), 22, PixelStyle.muted))
            })
        }
        val town = townBitmap?.let { bitmap ->
            artwork(BitmapDrawable(resources, bitmap).apply { setFilterBitmap(false) }).apply {
                tag = "onboarding:town-art"
            }
        }
        if (wide && town != null) {
            add(body, row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(town, LayoutParams(0, dp(320), 1.2f))
                addView(welcome, LayoutParams(0, -2, 1f).apply { marginStart = dp(PixelStyle.gapDp) })
            })
        } else {
            if (town != null) body.addView(town, LayoutParams(-1, dp(216)))
            add(body, welcome)
        }
    }

    private fun heading(body: LinearLayout, title: Int, description: Int? = null) = with(style) {
        add(body, label(s(title), 30, bold = true))
        if (description != null) add(body, label(s(description), 24, PixelStyle.muted))
    }
    private fun source(body: LinearLayout, value: String) { PlayerText.source(value)?.let { body.addView(label(it, color = PixelStyle.muted)) } }
    private fun benefits(body: LinearLayout, lines: List<String>) = with(style) {
        add(body, pixelCard().apply {
            addView(label(s(R.string.onboarding_planned_title), 24, bold = true))
            if (lines.isEmpty()) addView(label(s(R.string.onboarding_benefits_missing), color = PixelStyle.muted))
            else lines.forEach { addView(label(it, 24)) }
            addView(label(s(R.string.onboarding_planned_note), color = PixelStyle.muted))
        })
    }
    private fun candidate(value: OnboardingCandidate, selectable: Boolean) = with(style) {
        val selected = interaction.selectedId == value.vehicle.vehicleId
        pixelCard(selected).apply {
            addVehicleArt(this, value.vehicle.vehicleId)
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(value.vehicle.displayName, 28, bold = true), LayoutParams(0, -2, 1f))
                addView(label(rarity(io.drivegrowth.core.contract.Rarity.COMMON), 16, PixelStyle.rarityColor(io.drivegrowth.core.contract.Rarity.COMMON), true).apply {   // 첫 차는 늘 일반 등급
                    background = GradientDrawable().apply { cornerRadius = style.dp(14).toFloat(); setStroke(style.dp(2), PixelStyle.rarityColor(io.drivegrowth.core.contract.Rarity.COMMON)) }
                    setPadding(style.dp(12), style.dp(2), style.dp(12), style.dp(2))
                })
            })
            // 고를 수 있는 차에 공급된 설명이 없으면 HTML 시제품의 한 줄 소개를 쓴다
            val blurb = value.description ?: if (!value.selectable) s(R.string.onboarding_description_missing) else when (value.vehicle.vehicleId.value) {
                "hyundai_casper" -> "경형 SUV · 작고 높아서 짧은 대여에 좋아요"
                "hyundai_avante" -> "준중형 세단 · 무난해서 누구나 찾아요"
                else -> s(R.string.onboarding_description_missing)
            }
            addView(label(blurb, 20, PixelStyle.muted))
            if (selectable && value.selectable) setOnClickListener {
                if (!interaction.inputPending && interaction.pendingSelection == null) { interaction.select(value.vehicle.vehicleId); drawScreen() }
            }
            if (!value.selectable) addView(label(value.unavailableReason ?: s(R.string.onboarding_unavailable), color = PixelStyle.muted))
            if (selected) addView(label(s(R.string.onboarding_selected), 24, bold = true))
            if (selectable) action(this, R.string.onboarding_select, value.selectable, selected, value.vehicle.vehicleId, "${value.vehicle.displayName}, ${s(R.string.onboarding_select)}") {
                interaction.select(value.vehicle.vehicleId)
            }
        }
    }
    private fun label(value: CharSequence, size: Int = 20, color: Int = PixelStyle.text, bold: Boolean = false) =
        style.text(value, size, color, bold)

    private fun pixelCard(selected: Boolean = false) = style.column().apply {
        background = pixelShape(PixelStyle.panel, selected)
        val padding = style.dp(PixelStyle.panelPaddingDp)
        setPadding(padding, padding, padding, padding)
    }

    private fun pixelShape(fill: Int, highlighted: Boolean = false) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = style.dp(PixelStyle.panelRadiusDp).toFloat()
        setStroke(style.dp(if (highlighted) 2 else 1), if (highlighted) PixelStyle.focus else PixelStyle.outline)
    }

    private fun artwork(drawable: Drawable) = ImageView(context).apply {
        setImageDrawable(drawable)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = pixelShape(PixelStyle.inset)
        clipToOutline = true
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun addVehicleArt(card: LinearLayout, id: VehicleId, celebrate: Boolean = false) {
        val drawable = sprites.drawable(id) ?: return
        val art = artwork(drawable).apply {
            tag = "onboarding:vehicle-art:${id.value}"
            setPadding(0, style.dp(8), 0, style.dp(8))
        }
        if (celebrate) art.background = null
        card.addView(if (celebrate) celebration.over(art, fillHeight = true, stage = true) else art,
            LayoutParams(-1, style.dp(if (celebrate) GRANT_ART_HEIGHT_DP else 104)))
    }

    private fun action(body: LinearLayout, label: Int, enabled: Boolean = true, primary: Boolean = false,
                       tag: Any = label, description: String? = null, callback: () -> Boolean) = with(style) {
        val available = enabled && !interaction.inputPending && interaction.pendingSelection == null
        add(body, button(s(label), available) { callback(); drawScreen() }.apply {
            this.tag = tag
            if (description != null) contentDescription = description
            val fill = if (primary && available) PixelStyle.primary else PixelStyle.inset
            setTextColor(if (available) PixelStyle.text else PixelStyle.muted)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), pixelShape(fill, highlighted = true))
                addState(intArrayOf(), pixelShape(fill))
            }
        }, PixelStyle.gapDp)
    }

    private companion object {
        const val GRANT_ART_HEIGHT_DP = 132
    }
}
