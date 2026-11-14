package io.drivegrowth.app.ui.garage

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.app.ui.vehicleDisplayName
import io.drivegrowth.core.contract.ContractResult
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 호스트가 허용한 영역에 삽입하는 표시 전용 View. 탐색·저장·차량 SDK를 호출하지 않는다. */
class GarageView(context: Context, onEvent: (GarageEvent) -> Unit) : LinearLayout(context) {
    private val style = GarageStyle(context, pixelPanel = true, creamPanel = true)
    private val artwork = VehicleArtwork(context, style)
    internal var showHeading = true
    private val heldDeadlineFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREA)
        .withZone(ZoneId.of("Asia/Seoul"))
    private val interaction = GarageInteraction(onEvent)
    private var columnCount = 1
    private var wide = false
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
        val previous = interaction.state
        val previouslyEnabled = interaction.controlsEnabled
        val retryWasPending = interaction.retryPending
        interaction.render(state, controlsEnabled)
        if (childCount == 0 || previous != state || previouslyEnabled != controlsEnabled || retryWasPending) drawScreen()
    }

    /** 호스트의 준비/확인/명령 흐름이 끝났을 때 호출한다. 성공 상태는 새 render 입력으로만 반영한다. */
    fun completeRequest() { interaction.completeRequest(); drawScreen() }

    /** 공통 Back 처리기가 상세를 닫았으면 true. Activity Back 정책 자체는 호스트가 소유한다. */
    fun closeDetail(): Boolean {
        if (interaction.selectedId == null || !interaction.controlsEnabled) return false
        interaction.closeDetail(); detailY = 0; detailScroll = null; drawScreen(); return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val largeText = resources.configuration.fontScale > 1.3f
        val next = if (largeText) 1 else ((w - style.dp(16)) / style.dp(226)).coerceIn(1, 6)
        val split = w >= style.dp(760) && !largeText
        if (next != columnCount || split != wide) {
            columnCount = next
            wide = split
            post { drawScreen() }
        }
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun drawScreen(): Unit = with(style) {
        listScroll?.takeIf { it.isLaidOut }?.let { listY = it.scrollY }
        detailScroll?.takeIf { it.isLaidOut }?.let { detailY = it.scrollY }
        val focusedTag = findFocus()?.tag
        listScroll = null; detailScroll = null
        removeAllViews()
        setPadding(dp(16), dp(10), dp(16), dp(12))
        if (!interaction.controlsEnabled) {
            addView(message(s(R.string.garage_restricted), s(R.string.garage_restricted_body)))
            return@with
        }
        if (showHeading) addView(heading(s(R.string.garage_title), ReferenceHudIcon.GARAGE))
        when (val state = interaction.state) {
            GarageScreenState.Loading -> addView(message(s(R.string.garage_loading)))
            is GarageScreenState.Error -> addView(message(s(R.string.garage_error), state.message).apply {
                add(this, button(s(R.string.garage_retry), !interaction.retryPending) {
                    interaction.retry(); drawScreen()
                })
            })
            is GarageScreenState.Content -> {
                val showDetail = interaction.selectedId != null
                val showList = wide || !showDetail
                val area = if (wide) row() else column()
                addView(area, LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
                if (showList) {
                    listScroll = scroll(vehicleList(state.data, compact = wide), listY).apply { tag = "garage:list" }
                    area.addView(listScroll, if (wide) LayoutParams(0, -1, 2f) else LayoutParams(-1, -1))
                }
                if (wide || showDetail) {
                    detailScroll = scroll(detail(), detailY).apply {
                        tag = "garage:detail"
                        isVerticalScrollBarEnabled = true
                        isScrollbarFadingEnabled = false
                    }
                    area.addView(detailScroll, if (wide) LayoutParams(0, -1, 3f).apply { marginStart = dp(12) }
                        else LayoutParams(-1, -1))
                    if (showDetail) addView(row().apply {
                        if (wide) addView(View(context), LayoutParams(0, 0, 2f))
                        addView(button(s(R.string.garage_close)) { closeDetail() }.apply {
                            tag = "garage:detail-close"
                        }, LayoutParams(0, -2, if (wide) 3f else 1f).apply { if (wide) marginStart = dp(12) })
                    }, LayoutParams(-1, -2).apply { topMargin = dp(6) })
                }
            }
        }
        if (focusedTag != null) findViewWithTag<View>(focusedTag)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun message(title: String, body: String? = null) = style.card().apply {
        addView(style.text(title, 28, bold = true))
        if (body != null) addView(style.text(body, color = style.muted))
    }

    private fun vehicleList(data: GarageContent, compact: Boolean): LinearLayout = with(style) {
        column().apply {
            addView(text(data.capacityLabel ?: s(R.string.garage_capacity_unknown), 17, bold = true).apply {
                background = shape(panel)
                setPadding(dp(16), dp(6), dp(16), dp(6))
            }, LayoutParams(-2, -2))
            sourceText(data.sourceLabel)?.let { addView(text(it, 14, muted)) }
            addView(text(s(R.string.garage_growth_note), 14, muted))
            if (interaction.selectedId == null) {
                heldVehiclesCard(data)?.let { add(this, it, gap = 10) }
            }
            if (data.vehicles.isEmpty()) {
                add(this, message(s(R.string.garage_empty), s(R.string.garage_empty_body)).apply {
                    gravity = Gravity.CENTER
                    minimumHeight = dp(160)
                })
            }
            val columns = if (compact) 1 else columnCount
            data.vehicles.chunked(columns).forEach { vehicles ->
                val line = row()
                vehicles.forEachIndexed { index, vehicle ->
                    line.addView(vehicleCard(vehicle, compact), LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(16) })
                }
                repeat(columns - vehicles.size) {
                    line.addView(View(context), LayoutParams(0, 1, 1f).apply { marginStart = dp(16) })
                }
                add(this, line, 16)
            }
        }
    }

    private fun heldVehiclesCard(data: GarageContent): LinearLayout? = with(style) {
        when (val held = data.heldVehicles) {
            is ContractResult.Failure -> card().apply {
                addView(text(s(R.string.garage_held_unavailable), 16, muted))
            }
            is ContractResult.Success -> {
                if (held.value.isEmpty()) return@with null
                card().apply {
                    background = shape(inset)
                    addView(text(s(R.string.garage_held_count, held.value.size), 18, bold = true))
                    for (record in held.value) {
                        val name = vehicleDisplayName(
                            record.modelName ?: s(R.string.garage_held_model_missing), record.instanceLabel)
                        val deadline = heldDeadlineFormat.format(Instant.ofEpochMilli(record.deadline.value))
                        addView(text(s(R.string.garage_held_vehicle, name, rarity(record.vehicle.rarity)), 16, bold = true))
                        addView(text(s(R.string.garage_held_deadline, deadline), 14, muted))
                    }
                    add(this, button(s(R.string.garage_held_open_delivery), primary = true) {
                        interaction.openHeldDeliveries(data)
                    })
                }
            }
        }
    }

    private fun vehicleCard(value: VehiclePresentation, compact: Boolean): LinearLayout = with(style) {
        val selected = interaction.selectedId == value.vehicle.ownedVehicleId
        val selectionEnabled = !interaction.requestPending
        val title = vehicleDisplayName(value.name, value.instanceLabel)
        card(selected).apply {
            val artHeight = if (compact) 66 else 116
            val art = artwork.view(value.vehicle.vehicleId, value.vehicle.rarity, artHeight, showRing = false)
                .apply { background = null }
            val labels = column().apply {
                addView(text(title, 20, bold = true))
                addView(rarityChip(value.vehicle.rarity), LayoutParams(-2, -2))
                if (compact && selected) {
                    addView(text(s(R.string.garage_selected), 14, bold = true), LayoutParams(-2, -2))
                }
            }
            if (compact) addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(art, LayoutParams(dp(76), dp(artHeight)).apply { marginEnd = dp(8) })
                addView(labels, LayoutParams(0, -2, 1f))
            }) else {
                addView(art, LayoutParams(-1, dp(artHeight)))
                addView(labels)
            }
            if (!artwork.hasSprite(value.vehicle.vehicleId))
                addView(text(s(R.string.garage_art_placeholder), 14, muted))
            addView(progressRow(value))
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(activityBadge(value), LayoutParams(-2, -2))
                value.accidentLabel?.takeIf { it != "0" }?.let { count ->
                    addView(text(context.getString(R.string.garage_accidents, count), 14, muted),
                        LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                }
            })
            if (value.activityDetail != null) addView(text(value.activityDetail, 14, muted))
            if (value.informationNote != null) addView(text(value.informationNote, 14, muted))
            add(this, button(s(R.string.garage_select), selectionEnabled, primary = selected) {
                if (interaction.requestPending) return@button
                if (interaction.selectedId != value.vehicle.ownedVehicleId) { detailY = 0; detailScroll = null }
                interaction.select(value.vehicle.ownedVehicleId); drawScreen()
            }.apply {
                tag = value.vehicle.ownedVehicleId
                isSelected = selected
                contentDescription = "$title, ${s(R.string.garage_select)}"
            })
        }
    }

    private fun detail(): LinearLayout = with(style) {
        column().apply {
            if (interaction.selectedId == null) {
                add(this, message(s(R.string.garage_select_prompt), s(R.string.garage_select_body)))
                return@apply
            }
            val value = interaction.selectedVehicle
            if (value == null) {
                add(this, message(s(R.string.garage_removed), s(R.string.garage_removed_body)))
                return@apply
            }
            val facts = card().apply {
                addView(artwork.view(value.vehicle.vehicleId, value.vehicle.rarity, 166).apply { background = null },
                    LayoutParams(-1, dp(166)))
                if (!artwork.hasSprite(value.vehicle.vehicleId))
                    addView(text(s(R.string.garage_art_placeholder), 14, muted))
                addView(text(vehicleDisplayName(value.name, value.instanceLabel), 26, bold = true))
                addView(row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(rarityChip(value.vehicle.rarity), LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                    addView(activityBadge(value), LayoutParams(-2, -2))
                })
                addView(progressRow(value))
                value.activityDetail?.let { addView(text(it, 14, muted)) }
                value.informationNote?.let { addView(text(it, 14, muted)) }
                val fields = listOf(
                    "사고" to (value.accidentLabel?.let { context.getString(R.string.garage_accidents, it) }
                        ?: s(R.string.garage_accidents_missing)),
                    "구매가" to "${value.vehicle.acquisitionCost.units} 게임머니",
                    "차종 가격" to (value.modelPriceLabel ?: s(R.string.garage_model_price_missing)),
                )
                add(this, row().apply {
                    fields.forEachIndexed { index, (label, number) ->
                        addView(column().apply {
                            background = shape(inset)
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                            addView(text(label, 13, muted))
                            addView(text(number, 15, bold = true))
                        }, LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) })
                    }
                })
            }
            val actions = card().apply {
                if (interaction.requestPending) add(this, text(s(R.string.garage_pending), 18, bold = true))
                add(this, text(s(R.string.garage_action_note), 14, muted), 0)
                val sharedReasons = sharedGarageRestrictions(value)
                sharedReasons.forEach { reason -> add(this, text(reason, 16, muted), 4) }
                garageManagementActions.forEach { (action, label) ->
                    val info = value.actions[action]
                    val enabled = info?.enabled == true && !interaction.requestPending
                    val reason = if (interaction.requestPending) s(R.string.garage_pending)
                        else info?.takeUnless { it.enabled }?.explanation?.takeIf { it.isNotBlank() }
                            ?: s(R.string.garage_action_unknown)
                    add(this, button(s(label), enabled,
                        primary = action == VehicleAction.LEVEL_UP || action == VehicleAction.REPAIR) {
                        interaction.request(action, value.vehicle.ownedVehicleId, value.vehicle.revision)
                        drawScreen()
                    }.apply {
                        tag = action
                        if (!enabled) contentDescription = s(R.string.garage_action_with_reason, s(label), reason)
                        if (action == VehicleAction.SCRAP && enabled) setTextColor(0xff7d3026.toInt())
                    }, 6)
                    if (info?.enabled != true && reason !in sharedReasons && !interaction.requestPending) {
                        add(this, text(reason, 14, muted), 0)
                    }
                }
            }
            addView(facts, LayoutParams(-1, -2))
            add(this, actions, gap = 10)
        }
    }

    private fun progressRow(value: VehiclePresentation) = with(style) {
        row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(context.getString(R.string.garage_level, value.vehicle.level), 16, bold = true))
            addView(experienceTrack(value.vehicle.levelProgressPercent),
                LayoutParams(0, dp(16), 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            })
            addView(text("${value.vehicle.levelProgressPercent}%", 15, muted).apply {
                contentDescription = progress(value)
            })
        }
    }

    private fun activityBadge(value: VehiclePresentation) = with(style) {
        val (fill, foreground) = when (value.activity) {
            VehicleActivity.IDLE -> 0xffe4edbf.toInt() to 0xff28551f.toInt()
            VehicleActivity.RENTED -> 0xffdbeaf4.toInt() to 0xff254e6f.toInt()
            VehicleActivity.RETURNING -> 0xffd7ebd9.toInt() to 0xff285a44.toInt()
            VehicleActivity.NEEDS_REPAIR -> 0xfff8dcc5.toInt() to 0xff7d3026.toInt()
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

internal val garageManagementActions = listOf(
    VehicleAction.LEVEL_UP to R.string.garage_action_level,
    VehicleAction.USE_MATERIAL to R.string.garage_action_material,
    VehicleAction.REPAIR to R.string.garage_action_repair,
    VehicleAction.SELL to R.string.garage_action_sell,
    VehicleAction.SCRAP to R.string.garage_action_scrap,
)

internal fun sharedGarageRestrictions(vehicle: VehiclePresentation): Set<String> =
    garageManagementActions.mapNotNull { (action, _) ->
        vehicle.actions[action]?.takeUnless { it.enabled }?.explanation?.takeIf { it.isNotBlank() }
    }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
