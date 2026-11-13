package io.drivegrowth.app.ui.shop

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.VehiclePresentation
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
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
    private val back = style.button("닫기", controlsEnabled, action = onBack).apply {
        tag = "shop:focus-back"
        textSize = 16f
        minHeight = style.dp(48)
        minimumHeight = style.dp(48)
        setPadding(style.dp(12), 0, style.dp(12), 0)
    }
    private val card = style.column().apply {
        background = style.shape(style.panel)
        setPadding(style.dp(8), style.dp(4), style.dp(8), style.dp(8))
        isClickable = true
    }
    private val actions = management ?: style.scroll(style.column().apply {
        addView(facts(vehicle, selected, queryError))
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
        addView(card, LayoutParams(-1, -1))
        card.addView(back, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        card.addView(actions, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val gap = style.dp(8)
        val wide = width >= style.dp(720)
        (card.layoutParams as LayoutParams).apply {
            this.width = if (wide) minOf(style.dp(490), (width * .42f).toInt()) else (width - gap * 2).coerceAtLeast(0)
            this.height = if (wide) (height - gap * 2).coerceAtLeast(0) else (height * .64f).toInt()
            gravity = (if (wide) Gravity.CENTER_VERTICAL else Gravity.BOTTOM) or Gravity.END
            marginEnd = gap
            bottomMargin = if (wide) 0 else gap
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun facts(value: VehiclePresentation?, selected: ShopVehicle, queryError: String?): LinearLayout = with(style) {
        column().apply {
            val sprite = (value?.vehicle?.vehicleId ?: selected.vehicleId)?.let(VehicleSprites(context)::drawable)
            if (sprite != null) addView(ImageView(context).apply {
                setImageDrawable(sprite)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, dp(78)))
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
