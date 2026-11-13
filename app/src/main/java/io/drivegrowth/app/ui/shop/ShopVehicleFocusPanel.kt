package io.drivegrowth.app.ui.shop

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.VehiclePresentation
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.vehicleDisplayName
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.progression.VehicleAction
import io.drivegrowth.core.progression.VehicleActivity

/** 지도와 같은 영역에서 읽기 정보와 기존 차량 관리의 진입 버튼을 표시한다. */
internal class ShopVehicleFocusPanel(
    context: Context,
    map: ShopSceneView,
    vehicle: VehiclePresentation?,
    choices: List<ShopVehicle>,
    selected: ShopVehicle,
    recovery: View?,
    management: View?,
    restingLabel: String?,
    controlsEnabled: Boolean,
    managementEnabled: Boolean,
    actionsY: Int,
    queryError: String?,
    onBack: () -> Unit,
    onAction: (VehicleAction) -> Unit,
    onRest: () -> Unit,
    onSelect: (ShopVehicle) -> Unit,
) : FrameLayout(context) {
    private val style = ShopStyle(context)
    private val back = style.button("← 가게", controlsEnabled, action = onBack).apply { tag = "shop:focus-back" }
    private val facts = style.scroll(facts(vehicle, selected, queryError), 0)
    private val actions = management ?: style.scroll(style.card().apply {
        if (vehicle != null) {
            val sharedExplanation = managementActions.mapNotNull { (action, _) ->
                vehicle.actions[action]?.takeIf { it.enabled }?.explanation
            }.distinct().singleOrNull()
            if (sharedExplanation != null) addView(style.text(sharedExplanation, 16, style.muted))
            for ((action, label) in managementActions) {
                val info = vehicle.actions[action]
                addView(style.button(context.getString(label), managementEnabled && info?.enabled == true) {
                    onAction(action)
                }.apply { tag = action })
                if (info?.enabled != true || info.explanation != sharedExplanation) {
                    addView(style.text(info?.explanation ?: context.getString(R.string.garage_action_unknown), 16, style.muted))
                }
            }
        }
        if (recovery != null) addView(recovery)
        else addView(style.button(restingLabel ?: "영업 쉬기 확인 불가", managementEnabled && restingLabel != null, action = onRest)
            .apply { tag = "shop:focus-rest" })
        if (choices.size > 1) addView(style.text("다른 차량 선택", 16, style.muted, bold = true))
        for (choice in choices) {
            if (choice.id == selected.id) continue
            addView(style.button(choice.name, controlsEnabled) { onSelect(choice) }
                .apply { tag = "shop:focus-choose:${choice.id.value}" })
        }
    }, actionsY).apply { tag = "shop:focus-actions" }
    val actionScroll: ScrollView? get() = actions as? ScrollView

    init {
        tag = "shop:focus-panel"
        addView(map, LayoutParams(-1, -1))
        addView(back)
        addView(facts)
        addView(actions)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val gap = style.dp(8)
        val wide = width >= style.dp(720)
        val side = if (wide) (width * .42f).toInt() else (width - gap * 2).coerceAtLeast(0)
        val backHeight = minOf(style.dp(56), height / 3)
        val factsHeight = minOf(style.dp(208), (height / 2 - backHeight - gap * 2).coerceAtLeast(0))
        (back.layoutParams as LayoutParams).apply {
            this.width = -2; this.height = backHeight; gravity = Gravity.TOP or Gravity.START
            marginStart = gap; topMargin = gap
        }
        (facts.layoutParams as LayoutParams).apply {
            this.width = side; this.height = factsHeight; gravity = Gravity.TOP or Gravity.START
            marginStart = gap; topMargin = gap * 2 + backHeight
        }
        (actions.layoutParams as LayoutParams).apply {
            this.width = side; this.height = if (wide) (height - gap * 2).coerceAtLeast(0) else height / 3
            gravity = (if (wide) Gravity.TOP else Gravity.BOTTOM) or Gravity.END
            marginEnd = gap; topMargin = gap; bottomMargin = gap
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun facts(value: VehiclePresentation?, selected: ShopVehicle, queryError: String?): LinearLayout = with(style) {
        card().apply {
            addView(text(value?.let { vehicleDisplayName(it.name, it.instanceLabel) } ?: selected.name, 22, bold = true))
            if (value == null) {
                addView(text(queryError ?: "차량 관리 정보를 확인하고 있어요", 16, muted)
                    .apply { tag = "shop:focus-query-status" })
                return@apply
            }
            val car = value.vehicle
            val rarity = context.getString(when (car.rarity) {
                Rarity.COMMON -> R.string.garage_rarity_common
                Rarity.ADVANCED -> R.string.garage_rarity_advanced
                Rarity.RARE -> R.string.garage_rarity_rare
                Rarity.LEGEND -> R.string.garage_rarity_legend
            })
            val activity = context.getString(when (value.activity) {
                VehicleActivity.IDLE -> R.string.garage_idle
                VehicleActivity.RENTED -> R.string.garage_rented
                VehicleActivity.RETURNING -> R.string.garage_returning
                VehicleActivity.NEEDS_REPAIR -> R.string.garage_repair_needed
                null -> R.string.garage_activity_missing
            })
            addView(text("$rarity · $activity · Lv.${car.level} · ${car.levelProgressPercent}%", 16,
                PixelStyle.rarityColor(car.rarity), bold = true))
            addView(text(value.accidentLabel?.let { context.getString(R.string.garage_accidents, it) }
                ?: context.getString(R.string.garage_accidents_missing), 16))
            addView(text(context.getString(R.string.garage_acquisition, "${car.acquisitionCost.units} 게임머니"), 16))
            addView(text(value.modelPriceLabel?.let { context.getString(R.string.garage_model_price, it) }
                ?: context.getString(R.string.garage_model_price_missing), 16))
        }
    }

    private companion object {
        val managementActions = listOf(
            VehicleAction.LEVEL_UP to R.string.garage_action_level,
            VehicleAction.USE_MATERIAL to R.string.garage_action_material,
            VehicleAction.REPAIR to R.string.garage_action_repair,
            VehicleAction.SELL to R.string.garage_action_sell,
            VehicleAction.SCRAP to R.string.garage_action_scrap,
        )
    }
}
