package io.drivegrowth.app.ui.shop

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.rental.Facility
import java.text.NumberFormat
import java.util.Locale

internal sealed interface ShopMapTarget {
    data class FacilityLot(val facility: Facility) : ShopMapTarget
    data object Expansion : ShopMapTarget
    data object Decorations : ShopMapTarget
}

/** ShopView의 선택·보관 요청을 그대로 사용하고 지도 위에 확인 내용을 표시한다. */
@SuppressLint("ViewConstructor")
internal class ShopMapOverlayView(
    context: Context,
    private val onClose: () -> Unit,
    private val onChanged: () -> Unit,
) : FrameLayout(context) {
    private val style = ShopStyle(context)
    private val format = NumberFormat.getIntegerInstance(Locale.KOREA)
    private var interaction: ShopInteraction? = null
    private var target: ShopMapTarget? = null
    private var generation = 0L
    private var scroll: ScrollView? = null
    private var scrollY = 0

    init {
        visibility = GONE
        isClickable = true
        setBackgroundColor(0x990b101e.toInt())
        tag = "shop:map-overlay"
    }

    fun scrollPosition(): Pair<ShopMapTarget, Int>? =
        target?.let { it to (scroll?.takeIf { it.isLaidOut }?.scrollY ?: scrollY) }

    fun render(
        target: ShopMapTarget,
        interaction: ShopInteraction,
        projectedBalance: Map<String, GameMoney> = emptyMap(),
        initialScrollY: Int = 0,
    ) = with(style) {
        val sameTarget = this@ShopMapOverlayView.target == target && this@ShopMapOverlayView.interaction === interaction
        scrollY = if (sameTarget) scroll?.takeIf { it.isLaidOut }?.scrollY ?: scrollY else initialScrollY
        this@ShopMapOverlayView.target = target
        this@ShopMapOverlayView.interaction = interaction
        val token = ++generation
        removeAllViews()
        scroll = null
        visibility = if (interaction.controlsEnabled) VISIBLE else GONE
        if (!interaction.controlsEnabled) return@with
        fun current() = generation == token && visibility == VISIBLE && interaction.controlsEnabled

        val state = interaction.state
        val content = state as? ShopScreenState.Content
        val title = when (target) {
            is ShopMapTarget.FacilityLot -> label(target.facility)
            ShopMapTarget.Expansion -> s(R.string.shop_expand)
            ShopMapTarget.Decorations -> s(R.string.shop_decorations)
        }
        val pending = interaction.uiState.pending != null
        val body = card().apply {
            addView(text(title, 26, bold = true).apply { isAccessibilityHeading = true })
            if (pending) addView(text(s(R.string.shop_pending), 18, color = accent))
            interaction.uiState.message?.let { addView(text(it, 18)) }
            when {
                content == null -> {
                    val status = when (state) {
                        ShopScreenState.Loading -> s(R.string.shop_loading)
                        is ShopScreenState.Empty -> state.message
                        is ShopScreenState.Failure -> state.message
                        is ShopScreenState.Unsupported -> state.message
                        is ShopScreenState.Content -> error("이미 표시 내용을 읽었습니다")
                    }
                    if (state is ShopScreenState.Failure || state is ShopScreenState.Empty) {
                        addView(text(status, 18))
                        val retryPending = interaction.uiState.retryPending
                        add(this, button(s(if (retryPending) R.string.shop_retry_pending else R.string.shop_retry),
                            !retryPending, primary = true) {
                            if (current() && interaction.state == state && !interaction.uiState.retryPending) {
                                interaction.retry()
                                onChanged()
                            }
                        }.apply { tag = "shop:map-retry" })
                    } else {
                        addView(text(status, 18))
                    }
                }
                target == ShopMapTarget.Decorations -> {
                    val selected = interaction.selection?.takeIf { it.option in content.decorations }
                    if (selected != null) {
                        val command = selected.option.command as? ShopCommand.Decorate
                        if (command != null) {
                            addView(text(s(R.string.shop_preview_label), 18, color = accent))
                            addView(signPreview(s(R.string.shop_before, content.appearance.sign), content.appearance))
                            addView(signPreview(s(R.string.shop_after, command.appearance.sign), command.appearance))
                        }
                        describe(this, content, selected.option, projectedBalance)
                        confirm(this, interaction, selected, ::current, alreadySelected = true)
                    } else if (content.decorations.isEmpty()) {
                        addView(text(s(R.string.shop_no_decorations), 18))
                    } else for (option in content.decorations) {
                        val selection = ShopSelection(content.revision, option)
                        val item = column()
                        describe(item, content, option, projectedBalance)
                        if (option.availability == ShopAvailability.AVAILABLE && option.command is ShopCommand.Decorate) {
                            add(item, button(s(R.string.shop_preview), interaction.canSelect(selection)) {
                                if (current() && interaction.canSelect(selection)) {
                                    interaction.select(selection)
                                    onChanged()
                                }
                            }.apply { tag = "shop:map-preview:${option.id}" })
                        }
                        add(this, item)
                    }
                }
                else -> {
                    val option = when (target) {
                        is ShopMapTarget.FacilityLot -> content.facilities.firstOrNull { it.facility == target.facility }?.option
                        ShopMapTarget.Expansion -> content.expansion
                        ShopMapTarget.Decorations -> null
                    }
                    if (option == null) addView(text(s(if (target == ShopMapTarget.Expansion)
                        R.string.shop_no_expansion else R.string.shop_unknown), 18))
                    else {
                        describe(this, content, option, projectedBalance)
                        if (option.availability == ShopAvailability.AVAILABLE && option.command != null)
                            confirm(this, interaction, ShopSelection(content.revision, option), ::current)
                    }
                }
            }
        }
        val panel = column().apply {
            background = shape(style.panel)
            addView(scroll(body, scrollY).apply { tag = "shop:map-scroll" }
                .also { this@ShopMapOverlayView.scroll = it }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(button(if (!pending && interaction.uiState.selection != null) s(R.string.shop_cancel) else "닫기") {
                if (current()) handleBack()
            }.apply { tag = "shop:map-close" }, LinearLayout.LayoutParams(-1, -2))
        }
        addView(panel, LayoutParams(-1, -1, Gravity.CENTER).apply {
            val gap = dp(16)
            setMargins(gap, gap, gap, gap)
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (childCount != 0) {
            val params = getChildAt(0).layoutParams as LayoutParams
            params.width = (width - params.leftMargin - params.rightMargin).coerceIn(0, style.dp(480))
            val wide = width >= style.dp(720) && width > height
            val availableHeight = (height - params.topMargin - params.bottomMargin).coerceAtLeast(0)
            params.height = if (wide) minOf(availableHeight, style.dp(560)) else minOf(availableHeight, (height * .80f).toInt())
            params.gravity = Gravity.END or if (wide) Gravity.CENTER_VERTICAL else Gravity.BOTTOM
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun handleBack(): Boolean {
        val current = interaction ?: return false
        if (visibility != VISIBLE || !current.controlsEnabled) return false
        // 닫기는 보관 요청을 취소하지 않는다. 미리보기만 기존 상호작용으로 취소한다.
        val returnToDecorations = target == ShopMapTarget.Decorations &&
            current.uiState.pending == null && current.selection != null
        if (current.uiState.pending == null) current.cancel()
        if (returnToDecorations) {
            generation++
            onChanged()
            return true
        }
        generation++
        target = null
        visibility = GONE
        onClose()
        return true
    }

    private fun describe(
        parent: LinearLayout,
        content: ShopScreenState.Content,
        option: ShopOption,
        projectedBalance: Map<String, GameMoney>,
    ) = with(style) {
        add(parent, text(option.title, 21, bold = true))
        parent.addView(text(s(when (option.availability) {
            ShopAvailability.LOCKED -> R.string.shop_locked
            ShopAvailability.AVAILABLE -> R.string.shop_available
            ShopAvailability.INSTALLED -> R.string.shop_installed
            ShopAvailability.UNKNOWN -> R.string.shop_unknown
        }), 18, color = accent))
        parent.addView(text(s(R.string.shop_condition, option.condition), 18))
        parent.addView(text(s(R.string.shop_effect, option.effect), 18))
        if (option.availability != ShopAvailability.INSTALLED)
            parent.addView(text(s(R.string.shop_price, money(option.price)), 18))
        val expansion = (option.command as? ShopCommand.Expand)?.offer
        if (expansion != null && content.capacity == expansion.currentCapacity)
            parent.addView(text("차량 공간 ${expansion.currentCapacity} → ${expansion.nextCapacity}칸", 20, bold = true))
        if (option.availability == ShopAvailability.AVAILABLE) {
            parent.addView(text(s(R.string.shop_balance, money(content.balance)), 18))
            parent.addView(text("적용 후 예상 잔액 · ${money(projectedBalance[option.id])}", 18))
        }
        if (option.trial) parent.addView(text(s(R.string.shop_trial), 16, color = muted))
    }

    private fun confirm(
        parent: LinearLayout,
        interaction: ShopInteraction,
        selection: ShopSelection,
        current: () -> Boolean,
        alreadySelected: Boolean = false,
    ) = with(style) {
        val label = when (selection.option.command) {
            is ShopCommand.Expand -> "확장하기"
            is ShopCommand.BuyFacility -> "건설하기"
            else -> "적용하기"
        }
        add(parent, button(label, interaction.canSelect(selection), primary = true) {
            if (current() && interaction.canSelect(selection)) {
                if (!alreadySelected) interaction.select(selection)
                if (interaction.confirm(selection)) onChanged()
            }
        }.apply { tag = "shop:map-confirm" })
    }

    private fun signPreview(label: String, appearance: ShopAppearance) = with(style) {
        row().apply {
            addView(android.view.View(context).apply { background = shape(appearance.accentArgb) },
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8); gravity = Gravity.CENTER_VERTICAL })
            addView(text(label, 18), LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun label(facility: Facility) = s(when (facility) {
        Facility.FUEL_STATION -> R.string.shop_facility_fuel
        Facility.INSURANCE -> R.string.shop_facility_insurance
        Facility.CAR_WASH -> R.string.shop_facility_wash
    })

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun money(value: GameMoney?) = value?.let { s(R.string.shop_money, format.format(it.units)) } ?: s(R.string.shop_unknown)
}
