package io.drivegrowth.app.ui.garage

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity

/** 호스트가 허용한 영역에 삽입하는 표시 전용 View. 탐색·저장·차량 SDK를 호출하지 않는다. */
class GarageView(context: Context, onEvent: (GarageEvent) -> Unit) : LinearLayout(context) {
    private val style = GarageStyle(context, pixelPanel = true)
    private val artwork = VehicleArtwork(context, style)
    private val interaction = GarageInteraction(onEvent)
    private var widthClass = 0
    private var listScroll: ScrollView? = null
    private var detailScroll: ScrollView? = null
    private var listY = 0
    private var detailY = 0
    val selectedVehicleId: OwnedVehicleId? get() = interaction.selectedId

    fun restoreSelection(id: OwnedVehicleId?) {
        interaction.restoreSelection(id); detailY = 0; detailScroll = null; drawScreen()
    }

    init { orientation = VERTICAL; setBackgroundColor(style.base) }

    fun render(state: GarageScreenState, controlsEnabled: Boolean) {
        interaction.render(state, controlsEnabled)
        drawScreen()
    }

    /** 호스트의 준비/확인/명령 흐름이 끝났을 때 호출한다. 성공 상태는 새 render 입력으로만 반영한다. */
    fun completeRequest() { interaction.completeRequest(); drawScreen() }

    /** 공통 Back 처리기가 상세를 닫았으면 true. Activity Back 정책 자체는 호스트가 소유한다. */
    fun closeDetail(): Boolean {
        if (interaction.selectedId == null || !interaction.controlsEnabled) return false
        interaction.closeDetail(); drawScreen(); return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = when { w >= style.dp(1000) -> 2; w >= style.dp(680) -> 1; else -> 0 }
        if (next != widthClass) { widthClass = next; post { drawScreen() } }
    }

    private fun s(id: Int) = context.getString(id)
    private fun drawScreen(): Unit = with(style) {
        listScroll?.takeIf { it.isLaidOut }?.let { listY = it.scrollY }
        detailScroll?.takeIf { it.isLaidOut }?.let { detailY = it.scrollY }
        val focusedTag = findFocus()?.tag
        listScroll = null; detailScroll = null
        removeAllViews()
        setPadding(dp(24), dp(16), dp(24), dp(16))
        if (!interaction.controlsEnabled) {
            addView(message(s(R.string.garage_restricted), s(R.string.garage_restricted_body)))
            return@with
        }
        when (val state = interaction.state) {
            GarageScreenState.Loading -> addView(message(s(R.string.garage_loading)))
            is GarageScreenState.Error -> addView(message(s(R.string.garage_error), state.message).apply {
                add(this, button(s(R.string.garage_retry), !interaction.retryPending) {
                    interaction.retry(); drawScreen()
                })
            })
            is GarageScreenState.Content -> {
                addView(text(s(R.string.garage_title), 32, bold = true))
                addView(text(state.data.capacityLabel ?: s(R.string.garage_capacity_unknown), color = muted))
                addView(text(state.data.sourceLabel, color = muted))
                addView(text(s(R.string.garage_growth_note), color = muted))
                val area = if (widthClass == 2) row() else column()
                addView(area, LayoutParams(-1, 0, 1f))
                if (widthClass == 2 || interaction.selectedId == null) {
                    val list = vehicleList(state.data)
                    listScroll = scroll(list, listY)
                    area.addView(listScroll, if (widthClass == 2) LayoutParams(0, -1, 1f) else LayoutParams(-1, -1))
                }
                if (widthClass == 2 || interaction.selectedId != null) {
                    detailScroll = scroll(detail(), detailY)
                    area.addView(detailScroll, if (widthClass == 2) LayoutParams(0, -1, 1.4f).apply {
                        marginStart = dp(24)
                    } else LayoutParams(-1, -1))
                }
            }
        }
        if (focusedTag != null) findViewWithTag<View>(focusedTag)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun message(title: String, body: String? = null) = style.column().apply {
        addView(style.text(title, 28, bold = true))
        if (body != null) addView(style.text(body, color = style.muted))
    }

    private fun vehicleList(data: GarageContent): LinearLayout = with(style) {
        column().apply {
            if (data.vehicles.isEmpty()) add(this, message(s(R.string.garage_empty), s(R.string.garage_empty_body)))
            val columns = if (widthClass == 1) 2 else 1
            data.vehicles.chunked(columns).forEach { vehicles ->
                val line = row()
                vehicles.forEachIndexed { index, vehicle ->
                    line.addView(vehicleCard(vehicle), LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(16) })
                }
                if (vehicles.size < columns) line.addView(View(context), LayoutParams(0, 1, 1f).apply { marginStart = dp(16) })
                add(this, line, 16)
            }
        }
    }

    private fun vehicleCard(value: VehiclePresentation): LinearLayout = with(style) {
        val selected = interaction.selectedId == value.vehicle.ownedVehicleId
        card(selected).apply {
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(artwork.view(value.vehicle.vehicleId, value.vehicle.rarity, 76), LayoutParams(dp(88), dp(76)))
                addView(column().apply {
                    addView(text("${value.name} ${value.instanceLabel}", 20, bold = true))
                    addView(text(rarity(value.vehicle.rarity), 16, rarityColor(value.vehicle.rarity), bold = true))
                    addView(activityBadge(value))
                }, LayoutParams(0, -2, 1f).apply { marginStart = dp(PixelStyle.gapDp) })
            })
            if (!artwork.hasSprite(value.vehicle.vehicleId))
                addView(text(s(R.string.garage_art_placeholder), 16, muted))
            addView(text(context.getString(R.string.garage_level, value.vehicle.level), 20, bold = true))
            addView(progressTrack(value.vehicle.levelProgressPercent), LayoutParams(-1, dp(8)))
            addView(text(progress(value), 16, muted))
            if (value.activityDetail != null) addView(text(value.activityDetail, color = muted))
            if (value.informationNote != null) addView(text(value.informationNote, color = muted))
            if (selected) addView(text(s(R.string.garage_selected), bold = true))
            add(this, button(s(R.string.garage_select), primary = selected) {
                if (interaction.selectedId != value.vehicle.ownedVehicleId) { detailY = 0; detailScroll = null }
                interaction.select(value.vehicle.ownedVehicleId); drawScreen()
            }.apply { tag = value.vehicle.ownedVehicleId; isSelected = selected; contentDescription = "${value.name} ${value.instanceLabel}, ${s(R.string.garage_select)}" })
        }
    }

    private fun detail(): LinearLayout = with(style) {
        column().apply {
            if (interaction.selectedId == null) {
                add(this, message(s(R.string.garage_select_prompt), s(R.string.garage_select_body)))
                return@apply
            }
            add(this, button(s(R.string.garage_close)) { closeDetail() })
            val value = interaction.selectedVehicle
            if (value == null) {
                add(this, message(s(R.string.garage_removed), s(R.string.garage_removed_body)))
                return@apply
            }
            add(this, card(true).apply {
                addView(text(rarity(value.vehicle.rarity), color = rarityColor(value.vehicle.rarity), bold = true))
                addView(activityBadge(value), LayoutParams(-2, -2))
                if (value.activityDetail != null) addView(text(value.activityDetail, color = muted))
                if (value.informationNote != null) addView(text(value.informationNote, color = muted))
                addView(artwork.view(value.vehicle.vehicleId, value.vehicle.rarity, 144), LayoutParams(-1, dp(144)))
                if (!artwork.hasSprite(value.vehicle.vehicleId))
                    addView(text(s(R.string.garage_art_placeholder), 16, muted))
                addView(text("${value.name} ${value.instanceLabel}", 28, bold = true))
                addView(text(context.getString(R.string.garage_level, value.vehicle.level), 22, bold = true))
                addView(progressTrack(value.vehicle.levelProgressPercent), LayoutParams(-1, dp(8)))
                addView(text(progress(value), 16, muted))
                addView(text(value.accidentLabel?.let { context.getString(R.string.garage_accidents, it) }
                    ?: s(R.string.garage_accidents_missing)))
                addView(text(value.modelPriceLabel?.let { context.getString(R.string.garage_model_price, it) }
                    ?: s(R.string.garage_model_price_missing), color = muted))
                addView(text(context.getString(R.string.garage_acquisition, "${value.vehicle.acquisitionCost.units} 게임머니"), color = muted))
            })
            if (interaction.requestPending) add(this, text(s(R.string.garage_pending), 24, bold = true))
            add(this, text(s(R.string.garage_action_note), color = muted))
            listOf(
                VehicleAction.LEVEL_UP to R.string.garage_action_level,
                VehicleAction.USE_MATERIAL to R.string.garage_action_material,
                VehicleAction.REPAIR to R.string.garage_action_repair,
                VehicleAction.SELL to R.string.garage_action_sell,
                VehicleAction.SCRAP to R.string.garage_action_scrap,
            ).forEach { (action, label) ->
                val info = value.actions[action]
                val enabled = info?.enabled == true && !interaction.requestPending
                add(this, button(s(label), enabled, primary = action == VehicleAction.USE_MATERIAL) {
                    interaction.request(action, value.vehicle.ownedVehicleId, value.vehicle.revision); drawScreen()
                }.apply { tag = action })
                addView(text(info?.explanation ?: s(R.string.garage_action_unknown), color = muted))
                if (info?.enabled == false) addView(text(s(R.string.garage_action_unavailable), color = muted))
            }
        }
    }

    private fun activityBadge(value: VehiclePresentation) = with(style) {
        val (fill, foreground) = when (value.activity) {
            VehicleActivity.IDLE -> 0xff1f3a2c.toInt() to 0xff7ee2a6.toInt()
            VehicleActivity.RENTED -> 0xff1e2f57.toInt() to PixelStyle.focus
            VehicleActivity.RETURNING -> 0xff173c3c.toInt() to 0xff7fd8cf.toInt()
            VehicleActivity.NEEDS_REPAIR -> 0xff4a2026.toInt() to 0xffff9a8a.toInt()
            null -> inset to muted
        }
        text(activity(value), 16, foreground, bold = true).apply {
            background = shape(fill)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
    }

    private fun progress(value: VehiclePresentation) =
        context.getString(R.string.garage_progress, "${value.vehicle.levelProgressPercent}%")

    private fun activity(value: VehiclePresentation) = s(when (value.activity) {
        VehicleActivity.IDLE -> R.string.garage_idle
        VehicleActivity.RENTED -> R.string.garage_rented
        VehicleActivity.RETURNING -> R.string.garage_returning
        VehicleActivity.NEEDS_REPAIR -> R.string.garage_repair_needed
        null -> R.string.garage_activity_missing
    })
}
