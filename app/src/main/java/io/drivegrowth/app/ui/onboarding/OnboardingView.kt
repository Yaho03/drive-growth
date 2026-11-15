package io.drivegrowth.app.ui.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import io.drivegrowth.app.ui.pixel.CleanUi
import io.drivegrowth.app.ui.pixel.MapPanelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.app.ui.presentation.RewardCelebrationKind
import io.drivegrowth.app.ui.presentation.RewardCelebrationView
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.contract.VehicleId
import java.io.IOException
import java.text.NumberFormat
import java.util.Locale

/** 실제 지급·저장·탐색을 호출하지 않고 호스트가 전달한 단계와 확정 결과만 표시한다. */
class OnboardingView(context: Context, onEvent: (OnboardingEvent) -> Unit) : LinearLayout(context) {
    internal var showHeading = true
    private val hud = ReferenceHudStyle(context)
    private val panels = MapPanelStyle(context)
    private val style = GarageStyle(context, pixelPanel = true)
    internal var playFirstGrantAudio: (() -> (() -> Unit)?)? = null
    private var firstGrantElapsed: Long? = null
    private var prepareFirstGrant: (() -> Unit)? = null
    private var firstGrantWillAnimate: Boolean? = null
    private val celebration = RewardCelebrationView(context).apply {
        playFirstGrantAudio = { this@OnboardingView.playFirstGrantAudio?.invoke() }
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
    private val wide get() = layoutWidth >= style.dp(560) && resources.configuration.fontScale <= 1.3f
    private var scroll: ScrollView? = null
    private var savedY = 0
    private var previousScene: Class<*>? = null
    val selectedVehicleId: VehicleId? get() = interaction.selectedId
    val pendingVehicleId: VehicleId? get() = interaction.pendingSelection

    init { orientation = VERTICAL; setBackgroundColor(CleanUi.CARD_SOFT) }

    /** false인 늦은/역행 응답은 표시하지 않는다. 호스트는 프로필·requestId·응답 순서도 대조한다. */
    fun render(state: OnboardingScreenState, controlsEnabled: Boolean): Boolean {
        val previous = interaction.state
        val selection = interaction.selectedId
        val previouslyEnabled = interaction.controlsEnabled
        val accepted = interaction.render(state, controlsEnabled)
        if (childCount == 0 || previous != interaction.state || selection != interaction.selectedId ||
            previouslyEnabled != interaction.controlsEnabled) drawScreen()
        return accepted
    }

    fun restoreSelection(id: VehicleId?) {
        val previous = interaction.selectedId
        interaction.restoreSelection(id)
        if (previous != interaction.selectedId) drawScreen()
    }
    internal fun stopFirstGrantAudio() = celebration.stopFirstGrantAudio()

    fun handleBack(): Boolean {
        val handled = interaction.handleBack()
        if (handled) drawScreen()
        return handled
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width != layoutWidth) {
            layoutWidth = width
            if (childCount > 0) drawScreen()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val prepare = prepareFirstGrant
        prepareFirstGrant = null
        prepare?.invoke()
        super.dispatchDraw(canvas)
    }

    private fun s(id: Int) = context.getString(id)
    private fun drawScreen(): Unit = with(style) {
        val scene = interaction.state.javaClass
        if (scene == previousScene) scroll?.takeIf { it.isLaidOut }?.let { savedY = it.scrollY } else savedY = 0
        previousScene = scene
        val focused = findFocus()?.tag
        prepareFirstGrant = null
        celebration.onFirstGrantFrame = null
        if (interaction.state !is OnboardingScreenState.Completed || !interaction.controlsEnabled) {
            celebration.render(null, RewardCelebrationKind.FIRST_GRANT)
            firstGrantElapsed = null
        }
        celebration.rebuildContent { removeAllViews() }; scroll = null
        setPadding(dp(PixelStyle.panelPaddingDp), dp(PixelStyle.gapDp), dp(PixelStyle.panelPaddingDp), dp(PixelStyle.gapDp))
        if (!interaction.controlsEnabled) {
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
                intro(body)
                action(this@OnboardingView, R.string.onboarding_start, primary = true) { interaction.start() }
            }
            is OnboardingScreenState.Choosing -> {
                source(body, state.sourceLabel)
                stageTitle(body, R.string.onboarding_choose_title)
                body.addView(label(s(R.string.onboarding_choose_body), 17, panels.muted))
                if (state.candidates.isEmpty()) {
                    heading(body, R.string.onboarding_empty, R.string.onboarding_empty_body)
                    action(this@OnboardingView, R.string.onboarding_retry) { interaction.retry() }
                } else {
                    state.candidates.chunked(if (wide) 2 else 1).forEach { candidates ->
                        val row = row()
                        candidates.forEachIndexed { index, value ->
                            row.addView(candidate(value), LayoutParams(0, -2, 1f).apply {
                                if (index > 0) marginStart = dp(PixelStyle.gapDp)
                            })
                        }
                        add(body, row, PixelStyle.gapDp)
                    }
                    val selected = interaction.selectedCandidate
                    val reviewedId = selected?.vehicle?.vehicleId
                    if (selected == null && interaction.selectedId != null)
                        add(body, label(s(R.string.onboarding_selection_missing), 16, panels.muted))
                    val review = selected?.vehicle?.displayName?.let {
                        context.getString(R.string.onboarding_review_selected, it)
                    } ?: s(R.string.onboarding_select_required)
                    add(this@OnboardingView, actionButton(review, R.string.onboarding_review,
                        enabled = selected?.selectable == true, primary = true) {
                        interaction.selectedId == reviewedId && interaction.review()
                    }, PixelStyle.gapDp)
                }
            }
            is OnboardingScreenState.Confirming -> {
                source(body, state.sourceLabel)
                val review = pixelCard(selected = true).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addVehicleArt(this, state.candidate.vehicle.vehicleId, height = 140)
                    addView(label(context.getString(R.string.onboarding_confirm_title, state.candidate.vehicle.displayName),
                        26, bold = true).apply { gravity = Gravity.CENTER })
                    if (!state.candidate.selectable)
                        addView(label(state.candidate.unavailableReason ?: s(R.string.onboarding_unavailable), 17, panels.muted))
                    val benefits = if (state.plannedBenefits.isEmpty()) s(R.string.onboarding_benefits_missing)
                    else context.getString(R.string.onboarding_planned_summary, state.plannedBenefits.joinToString(" · "))
                    addView(label(benefits, 16, panels.muted).apply { gravity = Gravity.CENTER })
                }
                addCentered(body, review, 560)
                if (interaction.pendingSelection != null) body.addView(label(s(R.string.onboarding_submitting), 24))
                val footer = row()
                footer.addView(actionButton(s(R.string.onboarding_previous), R.string.onboarding_previous, state.backAllowed) {
                    interaction.handleBack()
                }, LayoutParams(0, -2, 1f))
                footer.addView(actionButton(s(R.string.onboarding_confirm), R.string.onboarding_confirm,
                    state.candidate.selectable, primary = true) {
                    interaction.confirm(state.candidate.vehicle.vehicleId)
                }, LayoutParams(0, -2, 1f).apply { marginStart = dp(PixelStyle.gapDp) })
                val confirmation = column().apply {
                    addView(label(s(R.string.onboarding_confirm_body), 17, panels.muted).apply {
                        gravity = Gravity.CENTER
                        tag = R.string.onboarding_confirm_body
                    }, LayoutParams(-1, -2))
                    add(this, footer, PixelStyle.gapDp)
                }
                add(this@OnboardingView, confirmation, PixelStyle.gapDp)
            }
            is OnboardingScreenState.Submitting -> {
                source(body, state.sourceLabel)
                heading(body, R.string.onboarding_submitting)
                body.addView(label(state.vehicleName ?: s(R.string.onboarding_vehicle_missing), 28, bold = true))
            }
            is OnboardingScreenState.LoadError -> {
                heading(body, R.string.onboarding_load_error)
                add(body, pixelCard().apply { addView(label(state.message, 18, panels.muted)) })
                action(body, R.string.onboarding_retry, state.retryAllowed) { interaction.retry() }
            }
            is OnboardingScreenState.GrantError -> {
                source(body, state.sourceLabel)
                heading(body, R.string.onboarding_grant_error)
                body.addView(label(state.vehicleName ?: s(R.string.onboarding_vehicle_missing), 28, bold = true))
                add(body, pixelCard().apply { addView(label(state.message, 18, panels.muted)) })
                action(body, R.string.onboarding_retry, state.retryAllowed, true) { interaction.retry() }
                action(body, R.string.onboarding_change, state.changeAllowed) { interaction.handleBack() }
                if (interaction.pendingSelection != null) body.addView(label(s(R.string.onboarding_submitting), 24))
            }
            is OnboardingScreenState.Completed -> {
                source(body, state.sourceLabel)
                stageTitle(body, if (state.alreadyGranted) R.string.onboarding_already_granted else R.string.onboarding_complete,
                    centered = true)
                completedRewards(body, state)
            }
        }
        if (focused != null) findViewWithTag<View>(focused)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun completedRewards(body: LinearLayout, state: OnboardingScreenState.Completed) = with(style) {
        val numberFormat = NumberFormat.getIntegerInstance(Locale.KOREA)
        val money = state.initialMoney?.units
        val spaces = state.initialParkingSpaces?.let { context.getString(R.string.onboarding_reward_parking_spaces, it) }
        val moneyValue = rewardValue(money?.let(numberFormat::format))
        val parkingValue = rewardValue(spaces)
        val slotLights = mutableListOf<View>()
        val parkingArt = state.initialParkingSpaces?.takeIf { it > 0 }?.let { capacity ->
            parkingSlots(capacity) { slotLights += it }
        } ?: rewardIcon(ReferenceHudIcon.GARAGE)
        val rewards = if (wide) row() else column()
        val sprite = sprites.bitmap(state.vehicle.vehicleId)
        val effectKey = state.vehicle.ownedVehicleId.value.takeIf {
            sprite != null && !state.alreadyGranted && firstGrantWillAnimate != false && confirmedChoice == state.vehicle.vehicleId
        }
        celebration.render(effectKey, RewardCelebrationKind.FIRST_GRANT, vehicle = sprite)
        if (effectKey == null) firstGrantElapsed = null
        val vehicle = pixelCard(selected = true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label(s(R.string.onboarding_reward_vehicle), 14, color = panels.muted))
            addVehicleArt(this, state.vehicle.vehicleId, celebrate = true)
            addView(label(state.displayName, 22, bold = true))
            addView(onboardingRarityChip(state.vehicle.rarity))
            addView(label(context.getString(R.string.onboarding_level, state.vehicle.level), 16))
        }
        val cards = listOf(
            rewardCard(s(R.string.onboarding_reward_money), rewardIcon(ReferenceHudIcon.COIN), moneyValue),
            vehicle,
            rewardCard(s(R.string.onboarding_reward_parking), parkingArt, parkingValue),
        )
        cards.forEachIndexed { index, card ->
            val params = if (wide) LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = dp(PixelStyle.gapDp) }
            else LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(PixelStyle.gapDp) }
            rewards.addView(card, params)
        }
        add(body, rewards)
        state.summary.forEach {
            add(body, label(it, color = panels.muted), PixelStyle.gapDp)
        }
        val shop = actionButton(s(R.string.onboarding_to_shop), R.string.onboarding_to_shop, primary = true) {
            if (firstGrantElapsed?.let { it < 1450L } == true) false else interaction.goToShop()
        }
        val shopAvailable = shop.isEnabled
        add(this@OnboardingView, shop, PixelStyle.gapDp)
        if (effectKey != null) {
            val renderFrame: (Long?) -> Unit = { elapsed ->
                firstGrantElapsed = elapsed
                cards.forEachIndexed { index, card ->
                    val start = index * 150L
                    val opacity = elapsed?.let { rewardPhase(it, start, 100) } ?: 1f
                    // 차 카드의 캔버스가 다음 프레임을 그릴 수 있도록 완전히 투명하게 만들지 않는다.
                    card.alpha = if (index == 1) opacity.coerceAtLeast(.01f) else opacity
                    val lift = elapsed?.let { rewardPhase(it, start, 400) } ?: 1f
                    card.translationY = dp(24) * (1f - lift) * (1f - lift)
                }
                if (money != null) {
                    moneyValue.visibility = if (elapsed == null || elapsed >= 450L) View.VISIBLE else View.INVISIBLE
                    val ticks = ((elapsed ?: 1150L) - 450L).coerceIn(0L, 700L)
                    val amount = (money / 700L) * ticks + (money % 700L) * ticks / 700L
                    val formatted = numberFormat.format(amount)
                    if (moneyValue.text != formatted) moneyValue.text = formatted
                }
                slotLights.forEachIndexed { index, light ->
                    val cue = 700L + index * 100L
                    light.alpha = elapsed?.let { rewardPhase(it, cue, 80) } ?: 1f
                }
                val shopReady = elapsed == null || elapsed >= 1450L
                shop.isEnabled = shopAvailable && shopReady
                shop.alpha = elapsed?.let { rewardPhase(it, 1450, 100) } ?: 1f
                val scale = elapsed?.let { .8f + .2f * rewardPhase(it, 1450, 300) } ?: 1f
                shop.scaleX = scale
                shop.scaleY = scale
            }
            celebration.onFirstGrantFrame = renderFrame
            renderFrame(firstGrantElapsed)
            val visibleClockBounds = Rect()
            fun showStaticReward() {
                firstGrantWillAnimate = false
                celebration.onFirstGrantFrame = null
                celebration.render(null, RewardCelebrationKind.FIRST_GRANT, vehicle = sprite)
                renderFrame(null)
            }
            fun settleIfClockClipped() {
                if (firstGrantElapsed != null && !celebration.getLocalVisibleRect(visibleClockBounds)) {
                    showStaticReward()
                }
            }
            if (firstGrantWillAnimate == null) {
                prepareFirstGrant = {
                    if (hasWindowFocus() && ValueAnimator.areAnimatorsEnabled() &&
                        celebration.getLocalVisibleRect(visibleClockBounds)) {
                        firstGrantWillAnimate = true
                        renderFrame(0)
                        settleIfClockClipped()
                    } else {
                        // 첫 프레임에 정적 결과를 보여준 경우 이후 스크롤·포커스로 연출을 다시 열지 않는다.
                        showStaticReward()
                    }
                }
            }
            scroll?.apply {
                setOnScrollChangeListener { _, _, _, _, _ -> settleIfClockClipped() }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> settleIfClockClipped() }
            }
        }
    }

    private fun rewardPhase(elapsed: Long, start: Long, duration: Long): Float =
        ((elapsed - start).toFloat() / duration).coerceIn(0f, 1f)

    private fun rewardValue(value: String?) =
        if (value != null) label(value, 26, bold = true).apply { gravity = Gravity.CENTER }
        else label(s(R.string.onboarding_reward_unknown), 16, color = panels.muted)

    private fun rewardIcon(icon: ReferenceHudIcon) = ImageView(context).apply {
        setImageDrawable(hud.icon(icon, 42))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun parkingSlots(capacity: Int, rememberLight: (View) -> Unit) = with(style) {
        val slots = row()
        val shown = capacity.coerceAtMost(12)
        val gap = dp(2)
        slots.gravity = Gravity.CENTER
        slots.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        repeat(shown) { index ->
            val bay = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(27, 40, 62))
                    setStroke(dp(1).coerceAtLeast(1), PixelStyle.muted)
                }
                addView(View(context).apply {
                    tag = "first-grant:parking-slot:$index"
                    setBackgroundColor(panels.accent)
                    rememberLight(this)
                }, FrameLayout.LayoutParams(-1, -1).apply { setMargins(gap, gap, gap, gap) })
            }
            slots.addView(bay, LayoutParams(0, dp(32), 1f).apply { if (index > 0) marginStart = gap })
        }
        slots
    }

    private fun rewardCard(title: String, art: View, value: View) = pixelCard().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        val artWidth = if (art is ImageView) style.dp(54) else LayoutParams.MATCH_PARENT
        addView(art, LayoutParams(artWidth, style.dp(54)))
        addView(label(title, 15, color = panels.muted))
        addView(value)
    }

    private fun intro(body: LinearLayout) = with(style) {
        val welcome = pixelCard().apply {
            val guide = guideBitmap
            if (guide != null) addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(artwork(BitmapDrawable(resources, guide).apply { setFilterBitmap(false) }).apply {
                    tag = "onboarding:town-art"
                    background = null
                }, LayoutParams(dp(48), dp(48)))
                addView(label(s(R.string.onboarding_mechanic), 16, panels.muted),
                    LayoutParams(-2, -2).apply { marginStart = dp(10) })
            })
            add(this, label(s(R.string.onboarding_intro_title), 31, bold = true))
            addView(label(s(R.string.onboarding_intro_body), 17, panels.muted))
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
    private fun stageTitle(body: LinearLayout, title: Int, centered: Boolean = false) {
        body.addView(label(s(title), 26, bold = true).apply {
            isAccessibilityHeading = true
            if (centered) gravity = Gravity.CENTER
        }, LayoutParams(-1, -2))
    }

    private fun vehicleClass(id: VehicleId): String? = when (id.value) {
        "hyundai_casper" -> s(R.string.onboarding_casper_class)
        "hyundai_avante" -> s(R.string.onboarding_avante_class)
        else -> null
    }

    private fun candidate(value: OnboardingCandidate): View = with(style) {
        val id = value.vehicle.vehicleId
        val selected = interaction.selectedId == id
        val classification = vehicleClass(id)
        val description = value.description ?: s(R.string.onboarding_description_missing)
            .takeIf { !value.selectable || classification == null }
        val details = if (value.selectable && classification != null) listOf(classification)
        else listOfNotNull(classification, description).distinct()
        val unavailable = if (!value.selectable) value.unavailableReason ?: s(R.string.onboarding_unavailable) else null
        val content = pixelCard(selected).apply {
            addVehicleArt(this, id, height = 148)
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(value.vehicle.displayName, 25, bold = true), LayoutParams(0, -2, 1f))
                if (selected) addView(label(s(R.string.onboarding_selected), 16, bold = true), LayoutParams(-2, -2))
            }, LayoutParams(-1, -2))
            details.forEach { addView(label(it, 18, panels.muted)) }
            if (unavailable != null) addView(label(unavailable, 17, panels.muted))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        FrameLayout(context).apply {
            addView(content, FrameLayout.LayoutParams(-1, -2))
            val select = button("", value.selectable && !interaction.inputPending && interaction.pendingSelection == null) {
                if (interaction.select(id)) drawScreen()
            }.apply {
                tag = id
                isSelected = selected
                background = RippleDrawable(ColorStateList.valueOf(0x40539cff), null, ColorDrawable(Color.WHITE))
                contentDescription = (listOf(value.vehicle.displayName) + details + listOfNotNull(unavailable,
                    s(if (selected) R.string.onboarding_selected else R.string.onboarding_select))).joinToString(", ")
                setOnFocusChangeListener { _, focused ->
                    content.background = if (selected || focused) hud.detailSurface(true) else hud.signSurface()
                }
            }
            addView(select, FrameLayout.LayoutParams(-1, -1))
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
        val padding = style.dp(14)
        setPadding(padding, padding, padding, padding)
    }

    private fun artwork(drawable: Drawable) = ImageView(context).apply {
        setImageDrawable(drawable)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = hud.detailSurface(false)
        clipToOutline = true
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun addVehicleArt(card: LinearLayout, id: VehicleId, celebrate: Boolean = false, height: Int = GRANT_ART_HEIGHT_DP) {
        val drawable = sprites.drawable(id) ?: return
        val art = artwork(drawable).apply {
            tag = "onboarding:vehicle-art:${id.value}"
            setPadding(0, style.dp(8), 0, style.dp(8))
        }
        art.background = null
        card.addView(if (celebrate) celebration.over(art, fillHeight = true, stage = true) else art,
            LayoutParams(-1, style.dp(height)))
    }

    private fun action(body: LinearLayout, label: Int, enabled: Boolean = true, primary: Boolean = false,
                       callback: () -> Boolean) {
        style.add(body, actionButton(s(label), label, enabled, primary, callback), PixelStyle.gapDp)
    }

    private fun actionButton(label: CharSequence, tag: Int, enabled: Boolean = true, primary: Boolean = false,
                             callback: () -> Boolean) = with(style) {
        val available = enabled && !interaction.inputPending && interaction.pendingSelection == null
        button(label.toString(), available, primary = primary) { callback(); drawScreen() }.apply {
            this.tag = tag
            textSize = 20f
        }
    }

    private companion object {
        const val GRANT_ART_HEIGHT_DP = 132
    }
}
