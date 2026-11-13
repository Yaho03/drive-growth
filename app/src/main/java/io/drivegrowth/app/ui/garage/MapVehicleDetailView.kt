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
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.vehicleDisplayName
import io.drivegrowth.core.contract.MAX_VEHICLE_LEVEL
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.VehicleLifecycle
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity

/**
 * 지도와 같은 콘텐츠 영역에 겹쳐 놓는 차량 상세. 바깥쪽 터치는 아래 지도에 전달한다.
 * 선택·조작 허용·거래 확인은 호스트가 관리한다.
 */
@SuppressLint("ViewConstructor")
internal class MapVehicleDetailView(
    context: Context,
    onEvent: (GarageEvent) -> Unit,
) : FrameLayout(context) {
    private val style = GarageStyle(context, pixelPanel = true)
    private val artwork = VehicleArtwork(context, style)
    private val interaction = GarageInteraction(onEvent)
    private val detailPanel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = style.shape(style.panel, selected = true)
        val inset = style.dp(12)
        setPadding(inset, inset, inset, inset)
        isClickable = true
        tag = "map:vehicle-detail"
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
        if (visibility != VISIBLE || interaction.requestPending) return false
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
        val margin = style.dp(12)
        val availableWidth = (width - margin * 2).coerceAtLeast(0)
        val availableHeight = (height - margin * 2).coerceAtLeast(0)
        val side = usesSidePanel(width, height, resources.displayMetrics.density)
        val maxHeight = if (side) availableHeight else minOf(availableHeight, height * 3 / 5)
        val panelWidth = if (side) minOf(style.dp(360), availableWidth) else availableWidth
        (detailPanel.layoutParams as LayoutParams).apply {
            this.width = panelWidth
            this.height = WRAP_CONTENT
            gravity = Gravity.END or if (side) Gravity.CENTER_VERTICAL else Gravity.BOTTOM
            setMargins(margin, margin, margin, margin)
        }
        // FrameLayout의 WRAP_CONTENT가 앱 영역보다 크게 측정되지 않도록 패널의 최대 높이를 고정한다.
        if (visibility == VISIBLE) {
            detailPanel.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            detailPanel.layoutParams.height = detailPanel.measuredHeight
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun drawPanel(): Unit = with(style) {
        contentScroll?.takeIf { it.isLaidOut }?.let { scrollY = it.scrollY }
        val focused = findFocus()?.tag
        val version = ++generation
        contentScroll = null
        detailPanel.removeAllViews()
        val visible = interaction.controlsEnabled && interaction.selectedId != null
        visibility = if (visible) VISIBLE else GONE
        if (!visible) return@with
        val selected = interaction.selectedVehicle?.takeIf { it.vehicle.lifecycle == VehicleLifecycle.OWNED }
        val content = column()
        when (val state = interaction.state) {
            GarageScreenState.Loading -> content.addView(text(s(R.string.garage_loading), 18)
                .apply { tag = "shop:focus-query-status" })
            is GarageScreenState.Error -> {
                content.addView(text(s(R.string.garage_error), 20, bold = true))
                content.addView(text(state.message, 16, muted).apply { tag = "shop:focus-query-status" })
                add(content, control(s(R.string.garage_retry), "map:vehicle-retry", !interaction.retryPending) {
                    interaction.retry()
                    if (generation == version) drawPanel()
                }, 6)
            }
            is GarageScreenState.Content -> if (selected == null) {
                content.addView(text(s(R.string.garage_removed), 20, bold = true))
            } else if (showActions) {
                showManagement(content, selected, version)
            } else {
                showSummary(content, selected)
            }
        }
        val scroll = scroll(content, scrollY).apply {
            isFillViewport = false
            tag = "map:vehicle-detail-scroll"
        }
        contentScroll = scroll
        detailPanel.addView(scroll, LinearLayout.LayoutParams(-1, -2, 1f))
        val pending = interaction.requestPending
        if (pending) add(detailPanel, text(s(R.string.garage_pending), 16, PixelStyle.focus), 4)
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
        parent.addView(row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(artwork.view(car.vehicleId, car.rarity, 72), LinearLayout.LayoutParams(dp(86), dp(72)))
            addView(column().apply {
                addView(text(vehicleDisplayName(value.name, value.instanceLabel), 20, bold = true))
                addView(text("${rarity(car.rarity)} · ${context.getString(R.string.garage_level, car.level)}",
                    16, rarityColor(car.rarity), bold = true))
                addView(text(activityLabel(value.activity), 16, color = PixelStyle.focus))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        })
        if (car.level == MAX_VEHICLE_LEVEL) {
            add(parent, text(s(R.string.garage_map_max_level), 16, muted), 4)
        } else {
            parent.addView(progressTrack(car.levelProgressPercent), LinearLayout.LayoutParams(-1, dp(8)).apply {
                topMargin = dp(6)
            })
            parent.addView(text(context.getString(R.string.garage_progress, "${car.levelProgressPercent}%"), 16, muted))
        }
        value.activityDetail?.let { parent.addView(text(it, 16, muted)) }
        if (value.activity == null) value.informationNote?.let { parent.addView(text(it, 16, muted)) }
    }

    private fun showManagement(parent: LinearLayout, selected: VehiclePresentation, version: Long): Unit = with(style) {
        val car = selected.vehicle
        parent.addView(text(vehicleDisplayName(selected.name, selected.instanceLabel), 20, bold = true))
        if (onRest != null) add(parent, control(restLabel ?: "영업 쉬기 확인 불가", "shop:focus-rest",
            restLabel != null && restEnabled && !actionsBlocked && !interaction.requestPending) { onRest?.invoke() }, 6)
        for ((action, label) in managementActions) {
            val info = selected.actions[action]
            add(parent, control(s(label), action, info?.enabled == true && !actionsBlocked && !interaction.requestPending) {
                if (interaction.request(action, car.ownedVehicleId, car.revision) && generation == version) drawPanel()
            }, 6)
            parent.addView(text(info?.explanation ?: s(R.string.garage_action_unknown), 15, muted))
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
            textSize = 16f
            minimumHeight = style.dp(48)
            minHeight = style.dp(48)
            setPadding(style.dp(8), style.dp(6), style.dp(8), style.dp(6))
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
            width >= (760 * density).toInt() && width > height

        private val managementActions = listOf(
            VehicleAction.LEVEL_UP to R.string.garage_action_level,
            VehicleAction.USE_MATERIAL to R.string.garage_action_material,
            VehicleAction.REPAIR to R.string.garage_action_repair,
            VehicleAction.SELL to R.string.garage_action_sell,
            VehicleAction.SCRAP to R.string.garage_action_scrap,
        )
    }
}
