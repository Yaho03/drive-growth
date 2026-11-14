package io.drivegrowth.app.ui.garage

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.MapPanelStyle
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import io.drivegrowth.app.ui.vehicleDisplayName
import io.drivegrowth.core.contract.MAX_VEHICLE_LEVEL
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.app.ui.shop.ShopFocusLayout
import io.drivegrowth.app.ui.shop.ShopFocusRegion
import io.drivegrowth.core.progression.VehicleActivity

/**
 * 지도와 같은 콘텐츠 영역에 겹쳐 놓는 차량 상세.
 * 선택·조작 허용·거래 확인은 호스트가 관리한다.
 */
@SuppressLint("ViewConstructor")
internal class MapVehicleDetailView(
    context: Context,
    onEvent: (GarageEvent) -> Unit,
) : FrameLayout(context) {
    private val style = GarageStyle(context, pixelPanel = true)
    private val mapStyle = MapPanelStyle(context)
    private val signs = ReferenceHudStyle(context)
    private val artwork = VehicleArtwork(context, style)
    private val interaction = GarageInteraction(onEvent)
    private val detailPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = mapStyle.card()
        val inset = style.dp(12)
        setPadding(inset, inset, inset, inset)
        isClickable = true
        tag = "map:vehicle-detail"
    }
    private var focusRegion: ShopFocusRegion? = null
    private var summaryArtwork: View? = null

    internal fun setMapFocus(region: ShopFocusRegion) {
        focusRegion = region
        isClickable = true
        requestLayout()
    }

    private var actionsBlocked = false
    private var showActions = false
    private var restLabel: String? = null
    private var restEnabled = false
    private var onRest: (() -> Unit)? = null
    private var generation = 0L
    private var contentScroll: ScrollView? = null
    private var scrollY = 0

    init {
        visibility = GONE
        addView(detailPanel, LayoutParams(0, 0))
    }

    fun render(state: GarageScreenState, selectedId: OwnedVehicleId?, controlsEnabled: Boolean, actionsBlocked: Boolean) {
        val selectionChanged = selectedId != interaction.selectedId
        val changed = selectionChanged || state != interaction.state || controlsEnabled != interaction.controlsEnabled ||
            actionsBlocked != this.actionsBlocked
        if (!changed) return
        if (selectionChanged || !controlsEnabled) {
            showActions = false
            resetScroll()
        }
        interaction.restoreSelection(selectedId)
        interaction.render(state, controlsEnabled)
        this.actionsBlocked = actionsBlocked
        drawPanel()
    }

    fun updateRest(label: String?, enabled: Boolean, action: () -> Unit) {
        val changed = label != restLabel || enabled != restEnabled || onRest == null
        restLabel = label
        restEnabled = enabled
        onRest = action
        if (changed) drawPanel()
    }

    /** 준비·확정·취소의 실제 결과를 받은 호스트가 해제한다. */
    fun completeRequest() {
        if (!interaction.requestPending) return
        interaction.completeRequest()
        drawPanel()
    }

    /** 호스트는 기존 거래 확인 화면의 Back을 먼저 처리한다. */
    fun handleBack(): Boolean {
        if (visibility == GONE || interaction.requestPending) return false
        if (showActions) {
            showActions = false
            resetScroll()
            drawPanel()
        } else {
            closeDetail()
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val fit = ShopFocusLayout.fit(width, height, resources.displayMetrics.density)
        val margin = fit.margin
        val panelWidth = fit.panelWidth
        summaryArtwork?.let { artworkPanel ->
            val desiredHeight = (fit.maxPanelHeight / 5).coerceIn(style.dp(72), style.dp(128))
            artworkPanel.layoutParams.height = desiredHeight
        }
        (detailPanel.layoutParams as LayoutParams).apply {
            this.width = panelWidth
            this.height = WRAP_CONTENT
            gravity = if (fit.side) Gravity.CENTER_VERTICAL or
                (if (focusRegion?.panelOnLeft == true) Gravity.LEFT else Gravity.RIGHT)
            else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setMargins(margin, margin, margin, margin)
        }
        // FrameLayout의 WRAP_CONTENT가 앱 영역보다 크게 측정되지 않도록 패널의 최대 높이를 고정한다.
        if (visibility != GONE) {
            val compactSummary = fit.side && summaryArtwork != null
            val panelHeight = if (compactSummary) minOf(fit.maxPanelHeight, style.dp(460)) else fit.maxPanelHeight
            detailPanel.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(panelHeight, if (fit.side) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
            detailPanel.layoutParams.height = detailPanel.measuredHeight
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun drawPanel(): Unit = with(style) {
        contentScroll?.takeIf { it.isLaidOut }?.let { scrollY = it.scrollY }
        val focused = findFocus()?.tag
        val version = ++generation
        contentScroll = null
        summaryArtwork = null
        detailPanel.removeAllViews()
        val visible = interaction.controlsEnabled && interaction.selectedId != null
        visibility = if (visible) VISIBLE else GONE
        if (!visible) return@with
        val selected = interaction.selectedVehicle?.takeIf { it.vehicle.lifecycle == VehicleLifecycle.OWNED }
        val content = column()
        when (val state = interaction.state) {
            GarageScreenState.Loading -> content.addView(text(s(R.string.garage_loading), 18, mapStyle.ink)
                .apply { tag = "shop:focus-query-status" })
            is GarageScreenState.Error -> {
                content.addView(text(s(R.string.garage_error), 20, mapStyle.ink, bold = true))
                content.addView(text(state.message, 16, mapStyle.muted).apply { tag = "shop:focus-query-status" })
                add(content, control(s(R.string.garage_retry), "map:vehicle-retry", !interaction.retryPending) {
                    interaction.retry()
                    if (generation == version) drawPanel()
                }, 6)
            }
            is GarageScreenState.Content -> if (selected == null) {
                content.addView(text(s(R.string.garage_removed), 20, mapStyle.ink, bold = true))
            } else if (showActions) {
                showManagement(content, selected, version)
            } else {
                showSummary(content, selected)
            }
        }
        val scroll = scroll(content, scrollY).apply {
            isFillViewport = false
            tag = "map:vehicle-detail-scroll"
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
        }
        contentScroll = scroll
        detailPanel.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
        val pending = interaction.requestPending
        if (pending) add(detailPanel, text(s(R.string.garage_pending), 16, mapStyle.accent), 4)
        val footer = row()
        if (!showActions && selected != null) {
            footer.addView(control(s(R.string.garage_map_manage), "map:vehicle-manage", !actionsBlocked && !pending, primary = true) {
                showActions = true
                resetScroll()
                drawPanel()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        }
        footer.addView(control(s(if (showActions) R.string.garage_map_back else R.string.garage_close),
            "map:vehicle-close", !pending, action = { handleBack() }), LinearLayout.LayoutParams(0, -2, 1f))
        add(detailPanel, footer, 6)
        if (focused != null) findViewWithTag<View>(focused)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun showSummary(parent: LinearLayout, value: VehiclePresentation): Unit = with(style) {
        val car = value.vehicle
        val heading = vehicleDisplayName(value.name, value.instanceLabel)
        add(parent, column().apply {
            background = mapStyle.chip()
            setPadding(dp(12), dp(10), dp(12), dp(12))
            val picture = artwork.view(car.vehicleId, car.rarity, 128, showRing = false, fillHeight = true).apply {
                background = null
                tag = "map:vehicle-artwork"
            }
            summaryArtwork = picture
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(picture, LinearLayout.LayoutParams(0, dp(128), 1f).apply { marginEnd = dp(12) })
                addView(column().apply {
                    addView(text(heading, 28, mapStyle.ink, bold = true).apply { isAccessibilityHeading = true })
                    addView(badge("${rarity(car.rarity)} · ${context.getString(R.string.garage_level, car.level)}"))
                    add(this, badge(activityLabel(value.activity), value.activity == VehicleActivity.IDLE), 4)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f))
            })
            if (!artwork.hasSprite(car.vehicleId)) addView(text(s(R.string.garage_art_placeholder), 16, mapStyle.muted))
            if (car.level == MAX_VEHICLE_LEVEL) {
                add(this, text(s(R.string.garage_map_max_level), 15, mapStyle.ink, bold = true), 8)
            } else {
                val percent = "${car.levelProgressPercent}%"
                add(this, row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text("EXP", 17, mapStyle.ink, bold = true))
                    addView(mapStyle.segmentedExperienceTrack(car.levelProgressPercent).apply { tag = "map:vehicle-exp" },
                        LinearLayout.LayoutParams(0, dp(22), 1f).apply {
                            marginStart = dp(8)
                            marginEnd = dp(8)
                        })
                    addView(text(percent, 17, mapStyle.ink, bold = true).apply {
                        contentDescription = context.getString(R.string.garage_progress, percent)
                    })
                }, 8)
            }
        }, 0)
        val acquisition = context.getString(R.string.shop_money,
            java.text.NumberFormat.getIntegerInstance(java.util.Locale.KOREA).format(car.acquisitionCost.units))
        add(parent, column().apply {
            background = mapStyle.chip()
            setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(fact(context.getString(R.string.garage_acquisition, acquisition), ReferenceHudIcon.COIN))
            addView(fact(value.modelPriceLabel?.let { context.getString(R.string.garage_model_price, it) }
                ?: s(R.string.garage_model_price_missing), ReferenceHudIcon.GARAGE))
            addView(fact(value.accidentLabel?.let { context.getString(R.string.garage_accidents, it) }
                ?: s(R.string.garage_accidents_missing), ReferenceHudIcon.RECORD))
        }, 8)
        value.activityDetail?.let { add(parent, text(it, 16, mapStyle.muted), 4) }
        if (value.activity == null) value.informationNote?.let { add(parent, text(it, 16, mapStyle.muted), 4) }
    }

    private fun badge(label: String, active: Boolean = false) = with(style) {
        text(label, 17, mapStyle.ink, bold = true).apply {
            background = mapStyle.chip(active)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(7))
        }
    }

    private fun fact(label: String, icon: ReferenceHudIcon) = with(style) {
        text(label, 19, mapStyle.ink).apply {
            setCompoundDrawablesRelative(signs.icon(icon, 24), null, null, null)
            compoundDrawablePadding = dp(8)
        }
    }

    private fun showManagement(parent: LinearLayout, selected: VehiclePresentation, version: Long): Unit = with(style) {
        val car = selected.vehicle
        parent.addView(text(vehicleDisplayName(selected.name, selected.instanceLabel), 24, mapStyle.ink, bold = true)
            .apply { isAccessibilityHeading = true })
        if (onRest != null) add(parent, control(restLabel ?: "영업 쉬기 확인 불가", "shop:focus-rest",
            restLabel != null && restEnabled && !actionsBlocked && !interaction.requestPending) { onRest?.invoke() }, 6)
        val sharedReasons = sharedGarageRestrictions(selected)
        sharedReasons.forEach { reason -> add(parent, text(reason, 16, mapStyle.muted), 4) }
        for ((action, label) in garageManagementActions) {
            val info = selected.actions[action]
            val enabled = info?.enabled == true && !actionsBlocked && !interaction.requestPending
            val restriction = when {
                interaction.requestPending -> s(R.string.garage_pending)
                info == null -> s(R.string.garage_action_unknown)
                !info.enabled -> info.explanation.ifBlank { s(R.string.garage_action_unknown) }
                actionsBlocked -> s(R.string.garage_action_unavailable)
                else -> null
            }
            add(parent, control(s(label), action, enabled) {
                if (interaction.request(action, car.ownedVehicleId, car.revision) && generation == version) drawPanel()
            }.apply {
                restriction?.let { contentDescription = context.getString(R.string.garage_action_with_reason, s(label), it) }
            }, 6)
            if (restriction != null && restriction !in sharedReasons && !interaction.requestPending && info?.enabled != true) {
                add(parent, text(restriction, 15, mapStyle.muted), 0)
            }
        }
    }

    private fun closeDetail() {
        val version = generation
        interaction.closeDetail()
        resetScroll()
        if (generation == version) drawPanel()
    }

    private fun resetScroll() { contentScroll = null; scrollY = 0 }
    private fun s(id: Int) = context.getString(id)

    private fun control(label: String, key: Any, enabled: Boolean, primary: Boolean = false, action: () -> Unit): Button =
        style.button(label, enabled, primary, onClick = action).apply {
            val version = generation
            tag = key
            mapStyle.styleButton(this, primary)
            setOnClickListener {
                if (isEnabled && version == generation && visibility == VISIBLE &&
                    this@MapVehicleDetailView.visibility == VISIBLE && interaction.controlsEnabled) action()
            }
        }

    private fun activityLabel(activity: VehicleActivity?) = s(when (activity) {
        VehicleActivity.IDLE -> R.string.garage_idle
        VehicleActivity.RENTED -> R.string.garage_rented
        VehicleActivity.RETURNING -> R.string.garage_returning
        VehicleActivity.NEEDS_REPAIR -> R.string.garage_repair_needed
        null -> R.string.garage_activity_missing
    })

    companion object {
        internal fun usesSidePanel(width: Int, height: Int, density: Float): Boolean =
            ShopFocusLayout.usesSidePanel(width, height, density)
    }
}
