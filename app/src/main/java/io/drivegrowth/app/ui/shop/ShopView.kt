package io.drivegrowth.app.ui.shop

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.rental.Facility
import java.text.NumberFormat
import java.util.Locale

/** 주차 gate·저장·거래는 호스트 책임. 단독 생성해 fixture를 render할 수 있다. */
class ShopView(context: Context, onEvent: (ShopEvent) -> Unit) : LinearLayout(context) {
    private val style = ShopStyle(context)
    private val interaction = ShopInteraction(onEvent)
    private var wide = false
    private var sceneScroll: ScrollView? = null
    private var controlsScroll: ScrollView? = null
    private var sceneY = 0
    private var controlsY = 0
    val uiState: ShopUiState get() = interaction.uiState

    init { orientation = VERTICAL; setBackgroundColor(style.base) }
    fun render(state: ShopScreenState, controlsEnabled: Boolean) { interaction.render(state, controlsEnabled); drawScreen() }
    fun restoreUiState(state: ShopUiState) { interaction.restoreUiState(state); drawScreen() }
    fun complete(request: ShopRequest, result: ContractResult<Unit>) { interaction.complete(request, result); drawScreen() }
    fun completeRetry() { interaction.completeRetry(); drawScreen() }
    /** 부모 탐색 전에 미리보기를 닫는다. 처리 중인 요청은 부모 탐색을 차단하지 않는다. */
    fun handleBack(): Boolean { val handled = interaction.cancel(); if (handled) drawScreen(); return handled }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = w >= style.dp(1000)
        if (next != wide) { wide = next; post { drawScreen() } }
    }
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun money(value: GameMoney?) = value?.let { s(R.string.shop_money, NumberFormat.getIntegerInstance(Locale.KOREA).format(it.units)) } ?: s(R.string.shop_unknown)
    private fun drawScreen(): Unit = with(style) {
        sceneScroll?.takeIf { it.isLaidOut }?.let { sceneY = it.scrollY }
        controlsScroll?.takeIf { it.isLaidOut }?.let { controlsY = it.scrollY }
        val focus = findFocus()?.tag
        sceneScroll = null; controlsScroll = null; removeAllViews()
        setPadding(dp(24), dp(16), dp(24), dp(16))
        if (!interaction.controlsEnabled) { addView(text(s(R.string.shop_restricted), 28)); return@with }
        addView(text(s(R.string.shop_title), 32, bold = true))
        when (val state = interaction.state) {
            ShopScreenState.Loading -> addView(text(s(R.string.shop_loading)))
            is ShopScreenState.Empty -> problem(s(R.string.shop_empty), state.message, true)
            is ShopScreenState.Failure -> problem(s(R.string.shop_failure), state.message, true)
            is ShopScreenState.Unsupported -> problem(s(R.string.shop_unsupported), state.message, false)
            is ShopScreenState.Content -> {
                addView(text(s(if (state.origin == ShopOrigin.FIXTURE) R.string.shop_fixture else R.string.shop_committed), color = accent))
                val area = if (wide) row() else column()
                addView(area, LayoutParams(-1, 0, 1f))
                if (wide) {
                    sceneScroll = scroll(scene(state), sceneY); controlsScroll = scroll(controls(state), controlsY)
                    area.addView(sceneScroll, LayoutParams(0, -1, 1f))
                    area.addView(controlsScroll, LayoutParams(0, -1, 1f).apply { marginStart = dp(20) })
                } else {
                    // 좁은 화면은 한 스크롤로 모든 정보와 확인/취소에 접근한다.
                    controlsScroll = scroll(column().apply { addView(scene(state)); addView(controls(state)) }, controlsY)
                    area.addView(controlsScroll, LayoutParams(-1, -1))
                }
            }
        }
        if (focus != null) findViewWithTag<View>(focus)?.takeIf { it.isEnabled }?.requestFocus()
    }
    private fun problem(title: String, body: String, retry: Boolean) = with(style) {
        val content = column().apply {
            addView(text(title, 28, bold = true)); addView(text(body))
            if (retry) add(this, button(s(if (uiState.retryPending) R.string.shop_retry_pending else R.string.shop_retry), !uiState.retryPending) {
                interaction.retry(); drawScreen()
            }.apply { tag = "shop:retry" })
        }
        addView(scroll(content, 0), LayoutParams(-1, 0, 1f))
    }
    private fun scene(c: ShopScreenState.Content) = with(style) {
        column().apply {
            addView(text(c.name, 28, bold = true))
            addView(text(s(R.string.shop_level, c.level?.toString() ?: s(R.string.shop_unknown))))
            addView(text(s(R.string.shop_capacity, c.ownedCount?.toString() ?: s(R.string.shop_unknown), c.capacity?.toString() ?: s(R.string.shop_unknown))))
            addView(text(s(R.string.shop_balance, money(c.balance))))
            val modes = row()
            for (mode in ShopMode.entries) modes.addView(button(s(if (mode == ShopMode.OVERALL) R.string.shop_overall else R.string.shop_vehicles), primary = uiState.mode == mode) {
                interaction.mode(mode); drawScreen()
            }.apply { tag = "shop:mode:$mode" }, LayoutParams(0, -2, 1f))
            add(this, modes)
            add(this, card().apply {
                addView(text(s(R.string.shop_committed), color = muted))
                addView(text(c.appearance.sign, 28, color = ink, bold = true))
                val preview = interaction.selection?.option?.command as? ShopCommand.Decorate
                if (preview != null) {
                    addView(text(s(R.string.shop_preview_label), color = accent))
                    addView(text(s(R.string.shop_after, preview.appearance.sign), 28, color = ink, bold = true))
                }
                if (uiState.mode == ShopMode.OVERALL) {
                    addView(View(context).apply { background = shape(c.appearance.accentArgb); contentDescription = c.appearance.sign }, LayoutParams(-1, dp(36)))
                }
                addView(text(s(R.string.shop_neutral), color = muted))
                if (c.vehicles.isEmpty()) addView(text(s(R.string.shop_no_vehicles)))
                c.vehicles.forEach { vehicle ->
                    val line = row()
                    line.addView(ImageView(context).apply {
                        setImageResource(R.drawable.shop_vehicle); contentDescription = vehicle.name
                    }, LayoutParams(dp(100), dp(72)))
                    line.addView(column().apply {
                        addView(text(vehicle.name, 24, bold = true)); addView(text(vehicle.id.value, color = muted)); addView(text(vehicle.status))
                    }, LayoutParams(0, -2, 1f))
                    add(this, line)
                }
            })
        }
    }
    private fun controls(c: ShopScreenState.Content) = with(style) {
        column().apply {
            uiState.pending?.let { add(this, text(s(R.string.shop_pending), color = accent)) }
            uiState.message?.let { add(this, text(it, color = accent)) }
            uiState.acknowledged?.takeIf { it.revision == c.revision }?.let { add(this, text(s(R.string.shop_wait_refresh))) }
            interaction.selection?.let { selection ->
                add(this, card().apply {
                    addView(text(s(R.string.shop_preview_label), 24, bold = true))
                    addView(text(selection.option.title, 28, bold = true))
                    addView(text(s(R.string.shop_before, when (val command = selection.option.command) {
                        is ShopCommand.Expand -> s(R.string.shop_capacity, c.ownedCount?.toString() ?: s(R.string.shop_unknown), c.capacity?.toString() ?: s(R.string.shop_unknown)) + " · " + s(R.string.shop_level, c.level?.toString() ?: s(R.string.shop_unknown))
                        is ShopCommand.BuyFacility -> s(R.string.shop_available)
                        else -> c.appearance.sign
                    })))
                    addView(text(s(R.string.shop_after, selection.option.effect)))
                    addView(text(s(R.string.shop_price, money(selection.option.price))))
                    addView(text(s(R.string.shop_condition, selection.option.condition)))
                    add(this, button(s(R.string.shop_confirm), interaction.canSelect(selection), primary = true) {
                        interaction.confirm(selection); drawScreen()
                    }.apply { tag = "shop:confirm" })
                    add(this, button(s(R.string.shop_cancel), uiState.pending == null) { interaction.cancel(); drawScreen() }.apply { tag = "shop:cancel" })
                })
            }
            add(this, text(s(R.string.shop_expand), 28, bold = true))
            c.expansion?.let { add(this, option(c, it)) } ?: add(this, text(s(R.string.shop_no_expansion)))
            add(this, text(s(R.string.shop_facilities), 28, bold = true))
            for (facility in Facility.entries) {
                val title = s(when (facility) {
                    Facility.FUEL_STATION -> R.string.shop_facility_fuel
                    Facility.INSURANCE -> R.string.shop_facility_insurance
                    Facility.CAR_WASH -> R.string.shop_facility_wash
                })
                val supplied = c.facilities.find { it.facility == facility }
                if (supplied == null) add(this, card().apply { addView(text(title, 24, bold = true)); addView(text(s(R.string.shop_unknown))) })
                else add(this, option(c, supplied.option, title))
            }
            add(this, text(s(R.string.shop_decorations), 28, bold = true))
            if (c.decorations.isEmpty()) add(this, text(s(R.string.shop_no_decorations)))
            c.decorations.forEach { add(this, option(c, it)) }
        }
    }
    private fun option(c: ShopScreenState.Content, option: ShopOption, title: String = option.title) = with(style) {
        card().apply {
            addView(text(title, 26, bold = true))
            addView(text(s(when (option.availability) {
                ShopAvailability.LOCKED -> R.string.shop_locked
                ShopAvailability.AVAILABLE -> R.string.shop_available
                ShopAvailability.INSTALLED -> R.string.shop_installed
                ShopAvailability.UNKNOWN -> R.string.shop_unknown
            }), color = accent))
            addView(text(s(R.string.shop_condition, option.condition)))
            addView(text(s(R.string.shop_effect, option.effect)))
            addView(text(s(R.string.shop_price, money(option.price))))
            addView(text(s(R.string.shop_rule, option.ruleVersion?.value ?: s(R.string.shop_unknown)), color = muted))
            if (option.trial) addView(text(s(R.string.shop_trial), color = muted))
            val selection = ShopSelection(c.revision, option)
            if (option.command != null) add(this, button(s(R.string.shop_preview), interaction.canSelect(selection)) {
                interaction.select(selection); controlsY = 0; controlsScroll = null; drawScreen()
            }.apply { tag = "shop:preview:${option.id}" })
        }
    }
}
