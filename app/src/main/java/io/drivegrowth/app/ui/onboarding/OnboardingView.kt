package io.drivegrowth.app.ui.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import io.drivegrowth.app.ui.pixel.MapPanelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.app.ui.presentation.RewardCelebrationKind
import io.drivegrowth.app.ui.presentation.RewardCelebrationView
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.VehicleId
import java.io.IOException

/** 실제 지급·저장·탐색을 호출하지 않고 호스트가 전달한 단계와 확정 결과만 표시한다. */
class OnboardingView(context: Context, onEvent: (OnboardingEvent) -> Unit) : LinearLayout(context) {
    internal var showHeading = true
    private val hud = ReferenceHudStyle(context)
    private val panels = MapPanelStyle(context)
    private val style = GarageStyle(context, pixelPanel = true, creamActions = true)
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
    private val guideBitmap by lazy(LazyThreadSafetyMode.NONE) {
        try {
            context.assets.open("pixel/prototype-actors/mechanic.png").use {
                BitmapFactory.decodeStream(it)?.apply { density = Bitmap.DENSITY_NONE }
            }
        } catch (_: IOException) {
            null
        }
    }
    private var layoutWidth = 0
    private val wide get() = layoutWidth >= style.dp(560)
    private var scroll: ScrollView? = null
    private var savedY = 0
    private var previousScene: Class<*>? = null
    val selectedVehicleId: VehicleId? get() = interaction.selectedId
    val pendingVehicleId: VehicleId? get() = interaction.pendingSelection

    init { orientation = VERTICAL; setBackgroundColor(io.drivegrowth.app.ui.pixel.CleanUi.CARD_SOFT) }

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
        if (w != layoutWidth) { layoutWidth = w; post { drawScreen() } }
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
            heading(this@OnboardingView, R.string.onboarding_restricted, R.string.onboarding_restricted_body)
            return@with
        }
        if (showHeading) addView(label(s(R.string.onboarding_title), 22, bold = true).apply {
            background = hud.detailSurface(false)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setCompoundDrawables(hud.icon(ReferenceHudIcon.GARAGE, 28), null, null, null)
            compoundDrawablePadding = dp(8)
        })
        val body = column()
        scroll = style.scroll(body, savedY)
        addView(scroll, LayoutParams(-1, 0, 1f))
        when (val state = interaction.state) {
            OnboardingScreenState.Loading -> heading(body, R.string.onboarding_loading)
            is OnboardingScreenState.Unavailable -> {
                heading(body, R.string.onboarding_unavailable_title)
                add(body, pixelCard().apply { addView(label(state.message, 18, panels.muted)) })
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
                    if (interaction.selectedCandidate == null) heading(body, if (interaction.selectedId == null)
                        R.string.onboarding_select_required else R.string.onboarding_selection_missing)
                }
                benefits(body, state.plannedBenefits)
                action(body, R.string.onboarding_previous) { interaction.handleBack() }
                if (state.candidates.isNotEmpty())
                    action(this@OnboardingView, R.string.onboarding_review,
                        interaction.selectedCandidate?.selectable == true, true) { interaction.review() }
            }
            is OnboardingScreenState.Confirming -> {
                source(body, state.sourceLabel)
                val review = pixelCard().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(candidate(state.candidate, false))
                    addView(label(s(R.string.onboarding_confirm_title), 22, bold = true))
                    add(this, label(s(R.string.onboarding_confirm_body), 16, hud.signInk, true).apply {
                        background = hud.detailSurface(true)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                    })
                    benefits(this, state.plannedBenefits)
                    action(this, R.string.onboarding_previous, state.backAllowed) { interaction.handleBack() }
                }
                addCentered(body, review, 560)
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
                add(body, pixelCard().apply { addView(label(state.message, 18, panels.muted)) })
                action(body, R.string.onboarding_retry, state.retryAllowed) { interaction.retry() }
            }
            is OnboardingScreenState.GrantError -> {
                source(body, state.sourceLabel)
                heading(body, R.string.onboarding_grant_error, R.string.onboarding_recovery_note)
                body.addView(label(state.vehicleName ?: s(R.string.onboarding_vehicle_missing), 28, bold = true))
                add(body, pixelCard().apply { addView(label(state.message, 18, panels.muted)) })
                action(body, R.string.onboarding_retry, state.retryAllowed, true) { interaction.retry() }
                action(body, R.string.onboarding_change, state.changeAllowed) { interaction.handleBack() }
                if (interaction.pendingSelection != null) body.addView(label(s(R.string.onboarding_submitting), 24))
            }
            is OnboardingScreenState.Completed -> {
                source(body, state.sourceLabel)
                heading(body, if (state.alreadyGranted) R.string.onboarding_already_granted else R.string.onboarding_complete,
                    R.string.onboarding_completed_note)
                completedRewards(body, state)
                add(body, label(s(R.string.onboarding_next), color = panels.muted))
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
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label("첫 차량", 14, color = panels.muted))
            val effectKey = state.vehicle.ownedVehicleId.value.takeIf { confirmedChoice == state.vehicle.vehicleId }
            celebration.render(effectKey, RewardCelebrationKind.FIRST_GRANT, vehicle = sprites.bitmap(state.vehicle.vehicleId))
            addVehicleArt(this, state.vehicle.vehicleId, celebrate = true)
            addView(label(state.displayName, 22, bold = true))
            addView(onboardingRarityChip(state.vehicle.rarity))
            addView(label(context.getString(R.string.onboarding_level, state.vehicle.level), 16))
            addView(label(context.getString(R.string.onboarding_acquisition, state.vehicle.acquisitionCost.units), 14, panels.muted))
        }
        val cards = listOf(
            rewardCard("게임머니", ReferenceHudIcon.COIN, money),
            vehicle,
            rewardCard("주차 칸", ReferenceHudIcon.GARAGE, spaces),
        )
        cards.forEachIndexed { index, card ->
            val params = if (wide) LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = dp(PixelStyle.gapDp) }
            else LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(PixelStyle.gapDp) }
            rewards.addView(card, params)
        }
        add(body, rewards)
        state.summary.filterNot { it == money || it == spaces }.forEach {
            add(body, label(it, color = panels.muted), PixelStyle.gapDp)
        }
        if (state.summary.isEmpty()) add(body, label(s(R.string.onboarding_result_missing), color = panels.muted))
    }

    private fun rewardCard(title: String, icon: ReferenceHudIcon, value: String?) = pixelCard().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(context).apply {
            setImageDrawable(hud.icon(icon, 42))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(style.dp(54), style.dp(54)))
        addView(label(title, 15, color = panels.muted))
        addView(if (value != null) label(value, 21, bold = true).apply { gravity = Gravity.CENTER }
            else label("확정 정보 없음", 16, color = panels.muted))
    }

    private fun intro(body: LinearLayout, state: OnboardingScreenState.Intro) = with(style) {
        val welcome = pixelCard().apply {
            val guide = guideBitmap
            if (guide != null) addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(artwork(BitmapDrawable(resources, guide).apply { setFilterBitmap(false) }).apply {
                    tag = "onboarding:town-art"
                    background = null
                }, LayoutParams(dp(48), dp(48)))
                addView(label("정비사", 15, panels.muted),
                    LayoutParams(-2, -2).apply { marginStart = dp(10) })
            })
            add(this, label(s(R.string.onboarding_intro_title), 31, bold = true))
            addView(label(s(R.string.onboarding_intro_body), 17, panels.muted))
            benefits(this, state.plannedBenefits)
        }
        addCentered(body, welcome, 640)
    }

    private fun addCentered(parent: LinearLayout, child: View, maxWidth: Int) = with(style) {
        parent.addView(FrameLayout(context).apply {
            addView(child, FrameLayout.LayoutParams(
                if (layoutWidth > dp(maxWidth + 36)) dp(maxWidth) else -1, -2, Gravity.CENTER_HORIZONTAL,
            ))
        }, LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun heading(body: LinearLayout, title: Int, description: Int? = null) = with(style) {
        add(body, pixelCard().apply {
            addView(label(s(title), 24, bold = true))
            if (description != null) addView(label(s(description), 16, panels.muted))
        })
    }

    private fun source(body: LinearLayout, value: String) {
        if (value == "모의 입력 프로필" || value == "차량 모드 프로필") return
        style.add(body, pixelCard().apply { addView(label(value, color = panels.muted)) })
    }
    private fun benefits(body: LinearLayout, lines: List<String>) = with(style) {
        add(body, pixelCard().apply {
            addView(label(s(R.string.onboarding_planned_title), 18, bold = true))
            if (lines.isEmpty()) addView(label(s(R.string.onboarding_benefits_missing), color = panels.muted))
            else lines.forEach { addView(label(it, 16)) }
            addView(label(s(R.string.onboarding_planned_note), color = panels.muted))
        })
    }
    private fun candidate(value: OnboardingCandidate, selectable: Boolean) = with(style) {
        val selected = interaction.selectedId == value.vehicle.vehicleId
        pixelCard(selected).apply {
            addVehicleArt(this, value.vehicle.vehicleId)
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(value.vehicle.displayName, 22, bold = true), LayoutParams(0, -2, 1f))
                addView(onboardingRarityChip(Rarity.COMMON), LayoutParams(-2, -2))
            })
            addView(label(value.description ?: s(R.string.onboarding_description_missing), 16, panels.muted))
            addView(label(s(R.string.onboarding_first_vehicle), 14, color = panels.muted))
            addView(label(s(R.string.onboarding_game_note), color = panels.muted))
            if (!value.selectable) addView(label(value.unavailableReason ?: s(R.string.onboarding_unavailable), color = panels.muted))
            if (selected) addView(label(s(R.string.onboarding_selected), 16, bold = true))
            if (selectable) action(this, R.string.onboarding_select, value.selectable, selected, value.vehicle.vehicleId, "${value.vehicle.displayName}, ${s(R.string.onboarding_select)}") {
                interaction.select(value.vehicle.vehicleId)
            }
        }
    }
    private fun onboardingRarityChip(value: Rarity) = label(style.rarity(value), 14, bold = true).apply {
        background = hud.detailSurface(false)
        setPadding(style.dp(10), style.dp(4), style.dp(10), style.dp(4))
    }

    private fun label(value: CharSequence, size: Int = 16, color: Int = hud.signInk, bold: Boolean = false) =
        style.text(value, size, color, bold)

    private fun pixelCard(selected: Boolean = false) = style.column().apply {
        background = if (selected) hud.detailSurface(true) else hud.signSurface()
        val padding = style.dp(PixelStyle.panelPaddingDp)
        setPadding(padding, padding, padding, padding)
    }

    private fun artwork(drawable: Drawable) = ImageView(context).apply {
        setImageDrawable(drawable)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = hud.detailSurface(false)
        clipToOutline = true
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun addVehicleArt(card: LinearLayout, id: VehicleId, celebrate: Boolean = false) {
        val drawable = sprites.drawable(id) ?: return
        val art = artwork(drawable).apply {
            tag = "onboarding:vehicle-art:${id.value}"
            setPadding(0, style.dp(8), 0, style.dp(8))
        }
        art.background = null
        card.addView(if (celebrate) celebration.over(art, fillHeight = true, stage = true) else art,
            LayoutParams(-1, style.dp(if (celebrate) GRANT_ART_HEIGHT_DP else 120)))
    }

    private fun action(body: LinearLayout, label: Int, enabled: Boolean = true, primary: Boolean = false,
                       tag: Any = label, description: String? = null, callback: () -> Boolean) = with(style) {
        val available = enabled && !interaction.inputPending && interaction.pendingSelection == null
        add(body, button(s(label), available, primary = primary) { callback(); drawScreen() }.apply {
            this.tag = tag
            backgroundTintList = null
            background = hud.signButton(primary)
            setTextColor(if (!available) panels.muted else if (primary) android.graphics.Color.WHITE else hud.signInk)
            if (description != null) contentDescription = description
        }, PixelStyle.gapDp)
    }

    private companion object {
        const val GRANT_ART_HEIGHT_DP = 132
    }
}
