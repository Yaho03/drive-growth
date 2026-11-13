package io.drivegrowth.app.ui.garage

import io.drivegrowth.app.ui.pixel.PlayerText
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.vehicleDisplayName
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
                // HTML 차고처럼: 제목 줄에 [보유/칸] 칩, 선택 전에는 카드 격자 + 빈 칸·잠긴 칸
                addView(row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(s(R.string.garage_title), 28, bold = true))
                    addView(text(state.data.capacityLabel ?: s(R.string.garage_capacity_unknown), 17, ink, bold = true).apply {
                        background = shape(panel); setPadding(dp(14), dp(6), dp(14), dp(6))
                    }, LayoutParams(-2, -2).apply { marginStart = dp(12) })
                    PlayerText.source(state.data.sourceLabel)?.let { addView(text(it, 15, muted), LayoutParams(-2, -2).apply { marginStart = dp(12) }) }
                })
                val browsing = widthClass == 2 && interaction.selectedId == null
                val area = if (widthClass == 2) row() else column()
                addView(area, LayoutParams(-1, 0, 1f))
                if ((widthClass == 2 && browsing) || (widthClass != 2 && interaction.selectedId == null)) {   // 상세를 열면 HTML처럼 상세가 화면을 다 쓴다
                    val list = vehicleList(state.data, if (browsing) 4 else null)
                    listScroll = scroll(list, listY)
                    area.addView(listScroll, if (widthClass == 2) LayoutParams(0, -1, 1f) else LayoutParams(-1, -1))
                }
                if ((widthClass == 2 && !browsing) || interaction.selectedId != null) {
                    detailScroll = scroll(detail(), detailY)
                    area.addView(detailScroll, if (widthClass == 2) LayoutParams(0, -1, 1f) else LayoutParams(-1, -1))
                }
            }
        }
        if (focusedTag != null) findViewWithTag<View>(focusedTag)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun message(title: String, body: String? = null) = style.column().apply {
        addView(style.text(title, 28, bold = true))
        if (body != null) addView(style.text(body, color = style.muted))
    }

    private fun vehicleList(data: GarageContent, gridColumns: Int? = null): LinearLayout = with(style) {
        column().apply {
            if (data.vehicles.isEmpty()) add(this, message(s(R.string.garage_empty), s(R.string.garage_empty_body)))
            val columns = gridColumns ?: if (widthClass == 1) 2 else 1
            // 격자일 때는 빈 칸·잠긴 칸 카드도 함께 놓는다(보유/칸 숫자는 표시 문구에서 읽는다)
            val counts = data.capacityLabel?.let { Regex("(\\d+)\\s*/\\s*(\\d+)").find(it) }?.destructured?.let { (o, c) -> o.toInt() to c.toInt() }
            val tiles: List<View> = data.vehicles.map { vehicleCard(it) } + if (gridColumns != null && counts != null) {
                List((counts.second - data.vehicles.size).coerceAtLeast(0)) { slotCard(s(R.string.garage_slot_empty), s(R.string.garage_slot_empty_body), false) } +
                    listOfNotNull((10 - counts.second).takeIf { it > 0 }?.let { slotCard(context.getString(R.string.garage_slot_locked, it), s(R.string.garage_slot_locked_body), true) })
            } else emptyList()
            tiles.chunked(columns).forEach { vehicles ->
                val line = row()
                vehicles.forEachIndexed { index, tile ->
                    line.addView(tile, LayoutParams(0, if (gridColumns != null) -1 else -2, 1f).apply { if (index > 0) marginStart = dp(16) })
                }
                if (vehicles.size < columns) line.addView(View(context), LayoutParams(0, 1, 1f).apply { marginStart = dp(16) })
                add(this, line, 16)
            }
        }
    }

    private fun slotCard(title: String, body: String, locked: Boolean): View = with(style) {
        column().apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(260)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (locked) 0x80121622.toInt() else 0)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(3), PixelStyle.outline, if (locked) 0f else dp(10).toFloat(), if (locked) 0f else dp(8).toFloat())
            }
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(text(title, 22, bold = true).apply { gravity = Gravity.CENTER })
            addView(text(body, 16, muted).apply { gravity = Gravity.CENTER })
        }
    }

    private fun vehicleCard(value: VehiclePresentation): LinearLayout = with(style) {
        val selected = interaction.selectedId == value.vehicle.ownedVehicleId
        val title = vehicleDisplayName(value.name, value.instanceLabel)
        card(selected).apply {
            // HTML 차고 카드처럼: 위에 큰 차 그림, 아래 이름 + 등급 칩, 상태 칩
            addView(artwork.view(value.vehicle.vehicleId, value.vehicle.rarity, 112), LayoutParams(-1, dp(112)))
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(title, 22, bold = true), LayoutParams(0, -2, 1f))
                addView(text(rarity(value.vehicle.rarity), 15, rarityColor(value.vehicle.rarity), bold = true).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat(); setStroke(dp(2), rarityColor(value.vehicle.rarity)) }
                    setPadding(dp(10), dp(2), dp(10), dp(2))
                }, LayoutParams(-2, -2))
            }, LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(activityBadge(value), LayoutParams(-2, -2))
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
            }.apply { tag = value.vehicle.ownedVehicleId; isSelected = selected; contentDescription = "$title, ${s(R.string.garage_select)}" })
        }
    }

    /** HTML 차량 상세: 왼쪽 큰 차 카드(이름·등급·상태·레벨·숫자 3칸), 오른쪽 관리 목록(설명 + 버튼 한 줄씩). */
    private fun detail(): LinearLayout = with(style) {
        column().apply {
            if (interaction.selectedId == null) {
                add(this, message(s(R.string.garage_select_prompt), s(R.string.garage_select_body)))
                return@apply
            }
            val value = interaction.selectedVehicle
            if (value == null) {
                add(this, button(s(R.string.garage_close)) { closeDetail() })
                add(this, message(s(R.string.garage_removed), s(R.string.garage_removed_body)))
                return@apply
            }
            val wideDetail = widthClass == 2
            val panes = if (wideDetail) row() else column()
            add(this, panes)
            val left = card()
            left.addView(button("← " + s(R.string.garage_title)) { closeDetail() }.apply {
                contentDescription = s(R.string.garage_close); textSize = 17f; minHeight = dp(48); minimumHeight = dp(48)
            }, LayoutParams(-2, -2))
            left.addView(artwork.view(value.vehicle.vehicleId, value.vehicle.rarity, 190), LayoutParams(-1, dp(190)).apply { topMargin = dp(10) })
            if (!artwork.hasSprite(value.vehicle.vehicleId)) left.addView(text(s(R.string.garage_art_placeholder), 16, muted))
            left.addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(vehicleDisplayName(value.name, value.instanceLabel), 28, bold = true).apply { setPadding(0, 0, dp(10), 0) })
                addView(text(rarity(value.vehicle.rarity), 15, rarityColor(value.vehicle.rarity), bold = true).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setStroke(dp(2), rarityColor(value.vehicle.rarity)) }
                    setPadding(dp(10), dp(2), dp(10), dp(2))
                })
                addView(activityBadge(value), LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }, LayoutParams(-1, -2).apply { topMargin = dp(8) })
            if (value.activityDetail != null) left.addView(text(value.activityDetail, color = muted))
            if (value.informationNote != null) left.addView(text(value.informationNote, color = muted))
            left.addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(context.getString(R.string.garage_level, value.vehicle.level), 20, bold = true))
                addView(progressTrack(value.vehicle.levelProgressPercent), LayoutParams(0, dp(10), 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
                addView(text("${value.vehicle.levelProgressPercent}%", 16, muted))
            }, LayoutParams(-1, -2).apply { topMargin = dp(6) })
            left.addView(row().apply {
                fun tile(label: String, number: String) = column().apply {
                    background = shape(inset); setPadding(dp(14), dp(10), dp(14), dp(10))
                    addView(text(label, 15, muted)); addView(text(number, 20, bold = true))
                }
                addView(tile(s(R.string.garage_tile_accidents), value.accidentLabel ?: "-"), LayoutParams(0, -2, 1f))
                addView(tile(s(R.string.garage_tile_acquisition), "${value.vehicle.acquisitionCost.units}"), LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                addView(tile(s(R.string.garage_tile_model_price), value.modelPriceLabel ?: "-"), LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            }, LayoutParams(-1, -2).apply { topMargin = dp(10) })
            panes.addView(left, if (wideDetail) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2))
            val right = card()
            if (interaction.requestPending) right.addView(text(s(R.string.garage_pending), 20, bold = true))
            listOf(
                VehicleAction.LEVEL_UP to R.string.garage_action_level,
                VehicleAction.USE_MATERIAL to R.string.garage_action_material,
                VehicleAction.REPAIR to R.string.garage_action_repair,
                VehicleAction.SELL to R.string.garage_action_sell,
                VehicleAction.SCRAP to R.string.garage_action_scrap,
            ).forEachIndexed { index, (action, label) ->
                val info = value.actions[action]
                val enabled = info?.enabled == true && !interaction.requestPending
                val short = when (action) { VehicleAction.LEVEL_UP -> "레벨업"; VehicleAction.USE_MATERIAL -> "재료 강화"
                    VehicleAction.REPAIR -> "수리"; VehicleAction.SELL -> "판매"; else -> "폐차" }
                right.addView(row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = shape(inset); setPadding(dp(16), dp(10), dp(12), dp(10))
                    addView(column().apply {
                        addView(text(short, 19, bold = true))
                        addView(text(info?.explanation ?: s(R.string.garage_action_unknown), 15, muted))
                    }, LayoutParams(0, -2, 1f))
                    addView(button(if (action == VehicleAction.USE_MATERIAL) "재료 고르기" else short, enabled, primary = enabled && action == VehicleAction.LEVEL_UP) {
                        interaction.request(action, value.vehicle.ownedVehicleId, value.vehicle.revision); drawScreen()
                    }.apply {
                        tag = action; contentDescription = s(label); textSize = 17f; minHeight = dp(52); minimumHeight = dp(52)
                        if (action == VehicleAction.SCRAP) setTextColor(if (enabled) 0xffff9a8a.toInt() else muted)
                    }, LayoutParams(-2, -2).apply { marginStart = dp(10) })
                }, LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(10) })
            }
            right.addView(text(s(R.string.garage_action_note), 14, muted), LayoutParams(-1, -2).apply { topMargin = dp(8) })
            panes.addView(right, if (wideDetail) LayoutParams(0, -2, 1.1f).apply { marginStart = dp(16) } else LayoutParams(-1, -2).apply { topMargin = dp(12) })
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
