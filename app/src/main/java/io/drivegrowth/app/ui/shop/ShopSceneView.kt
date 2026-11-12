package io.drivegrowth.app.ui.shop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.core.contract.OwnedVehicleId
import io.drivegrowth.core.contract.Rarity
import io.drivegrowth.core.progression.VehicleActivity
import io.drivegrowth.core.rental.Facility
import kotlin.math.roundToInt

/** #229 원본 좌표를 Canvas와 차량 선택 View에 공통으로 적용한다. */
@SuppressLint("ViewConstructor")
internal class ShopSceneView(
    context: Context,
    private val content: ShopScreenState.Content,
    mode: ShopMode,
    private val background: Bitmap,
    sprites: VehicleSprites,
    onVehicle: ((OwnedVehicleId) -> Unit)?,
) : ViewGroup(context) {
    internal val model = shopSceneModel(content)
    private val scene = if (mode == ShopMode.OVERALL) RectF(0f, 0f, 1536f, 1024f)
        else RectF(100f, 546f, 1450f, 986f)
    private val placed = model.vehicles.zip(townSlots).sortedBy { it.second.second }
    private val bitmaps = placed.associate { (vehicle, _) -> vehicle.id to vehicle.vehicleId?.let(sprites::bitmap) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val dashed = DashPathEffect(floatArrayOf(7f, 5f), 0f)
    private val area = RectF()
    private val fullTown = RectF(0f, 0f, 1536f, 1024f)
    private val shape = Path()
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    init {
        setWillNotDraw(false)
        tag = "shop:scene"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = buildString {
            append(context.getString(R.string.shop_scene_description, content.appearance.sign))
            append(". ")
            append(content.capacity?.let { context.getString(R.string.shop_scene_spaces, it) }
                ?: context.getString(R.string.shop_scene_spaces_unknown))
            model.facilityStates.forEach { (facility, status) ->
                append(". ${facilityName(facility)}: ${facilityStatus(status)}")
            }
        }
        if (onVehicle != null) placed.forEach { (vehicle, _) ->
            addView(View(context).apply {
                tag = "shop:map-vehicle:${vehicle.id.value}"
                contentDescription = context.getString(R.string.shop_scene_vehicle_detail, vehicle.name, vehicle.status)
                isFocusable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        setStroke((resources.displayMetrics.density * 2).roundToInt(), PixelStyle.focus)
                    })
                    addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(0x449cc2ff) })
                }
                setOnClickListener { onVehicle(vehicle.id) }
            })
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec)
        val height = resolveSize((width * scene.height() / scene.width()).roundToInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
        scale = minOf(width / scene.width(), height / scene.height())
        offsetX = (width - scene.width() * scale) / 2 - scene.left * scale
        offsetY = (height - scene.height() * scale) / 2 - scene.top * scale
        val hitWidth = MeasureSpec.makeMeasureSpec((70.656f * scale).roundToInt(), MeasureSpec.EXACTLY)
        val hitHeight = MeasureSpec.makeMeasureSpec((71.68f * scale).roundToInt(), MeasureSpec.EXACTLY)
        for (index in 0 until childCount) getChildAt(index).measure(hitWidth, hitHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) {
            val (_, slot) = placed[index]
            val child = getChildAt(index)
            val x = (offsetX + slot.first * scale - child.measuredWidth / 2f).roundToInt()
            val y = (offsetY + (slot.second - 7) * scale - child.measuredHeight / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(PixelStyle.background)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.clipRect(scene)
        canvas.drawBitmap(background, null, fullTown, paint)
        model.facilityStates.forEach { (facility, status) -> drawFacility(canvas, facility, status) }
        drawSign(canvas)
        townSlots.forEachIndexed { index, (x, y) ->
            if (index >= (model.slots ?: 0)) drawClosedSlot(canvas, x.toFloat(), y.toFloat())
        }
        if (model.slots == null) {
            badge(canvas, context.getString(R.string.shop_scene_spaces_unknown), 770f, 652f, 360f)
        }
        placed.forEach { (vehicle, slot) -> drawVehicle(canvas, vehicle, slot.first.toFloat(), slot.second.toFloat()) }
        canvas.restore()
    }

    private fun drawFacility(canvas: Canvas, facility: Facility, status: ShopAvailability) {
        val x: Float
        val y: Float
        if (status != ShopAvailability.INSTALLED) {
            // 합성 원본에 그려진 건물의 외곽과 간판을 덮고 주변 마을은 보존한다.
            val outline = when (facility) {
                Facility.FUEL_STATION -> fuelCover
                Facility.INSURANCE -> insuranceCover
                Facility.CAR_WASH -> washCover
            }
            polygon(canvas, outline, PixelStyle.panel)
        }
        when (facility) {
            Facility.FUEL_STATION -> { x = 295f; y = 154f }
            Facility.INSURANCE -> { x = 377f; y = 434f }
            Facility.CAR_WASH -> { x = 1355f; y = 546f }
        }
        badge(canvas, "${facilityName(facility)} · ${facilityStatus(status)}", x, y, 248f,
            if (status == ShopAvailability.AVAILABLE) PixelStyle.focus else PixelStyle.text)
    }

    private fun facilityName(facility: Facility) = context.getString(when (facility) {
        Facility.FUEL_STATION -> R.string.shop_facility_fuel
        Facility.INSURANCE -> R.string.shop_facility_insurance
        Facility.CAR_WASH -> R.string.shop_facility_wash
    })

    private fun facilityStatus(status: ShopAvailability) = context.getString(when (status) {
        ShopAvailability.LOCKED -> R.string.shop_locked
        ShopAvailability.AVAILABLE -> R.string.shop_available
        ShopAvailability.INSTALLED -> R.string.shop_installed
        ShopAvailability.UNKNOWN -> R.string.shop_unknown
    })

    private fun drawSign(canvas: Canvas) {
        canvas.save()
        canvas.translate(729f, 284f)
        canvas.skew(0f, .40f)
        val fill = content.appearance.accentArgb or Color.BLACK
        paint.color = fill
        canvas.drawRect(0f, 0f, 205f, 45f, paint)
        val luminance = .299f * Color.red(fill) + .587f * Color.green(fill) + .114f * Color.blue(fill)
        drawText(canvas, content.appearance.sign, 102.5f, 22.5f, 189f, 28f,
            if (luminance > 152) PixelStyle.background else PixelStyle.text)
        canvas.restore()
    }

    private fun drawClosedSlot(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        polygon(canvas, closedSlot, 0xff32394a.toInt())
        if (model.slots != null) badge(canvas, context.getString(R.string.shop_scene_slot_closed), 0f, 0f, 105f)
        canvas.restore()
    }

    private fun drawVehicle(canvas: Canvas, vehicle: ShopVehicle, x: Float, y: Float) {
        val bitmap = bitmaps[vehicle.id]
        val parked = vehicle.activity == VehicleActivity.IDLE || vehicle.activity == VehicleActivity.NEEDS_REPAIR
        if (bitmap != null && parked) {
            vehicle.rarity?.let { drawRarity(canvas, it, x, y + 13f) }
            val drawWidth = if (bitmap.width == 56) 88f else 100f
            val drawHeight = drawWidth * bitmap.height / bitmap.width
            area.set(x - drawWidth / 2, y - 7f - drawHeight / 2, x + drawWidth / 2, y - 7f + drawHeight / 2)
            canvas.save()
            canvas.scale(-1f, 1f, x, y)
            canvas.drawBitmap(bitmap, null, area, paint)
            canvas.restore()
        }
        val label = if (vehicle.activity == VehicleActivity.IDLE && bitmap != null) vehicle.name
            else "${vehicle.name} · ${vehicle.status}"
        badge(canvas, label, x - 36f, y + 25f, 155f)
    }

    private fun drawRarity(canvas: Canvas, rarity: Rarity, x: Float, y: Float) {
        canvas.save()
        canvas.rotate(-29f, x, y)
        paint.color = PixelStyle.rarityColor(rarity)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (rarity == Rarity.LEGEND) 4f else 2.5f
        paint.pathEffect = if (rarity == Rarity.COMMON) dashed else null
        area.set(x - 42f, y - 12f, x + 42f, y + 12f)
        canvas.drawOval(area, paint)
        if (rarity == Rarity.RARE) {
            area.inset(4f, 4f)
            canvas.drawOval(area, paint)
        }
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        canvas.restore()
    }

    private fun polygon(canvas: Canvas, coords: FloatArray, color: Int) {
        shape.reset()
        shape.moveTo(coords[0], coords[1])
        for (i in 2 until coords.size step 2) shape.lineTo(coords[i], coords[i + 1])
        shape.close()
        paint.color = color
        canvas.drawPath(shape, paint)
    }

    private fun badge(canvas: Canvas, text: String, x: Float, y: Float, width: Float, ink: Int = PixelStyle.text) {
        paint.color = PixelStyle.panel
        canvas.drawRoundRect(x - width / 2, y - 18f, x + width / 2, y + 18f, 9f, 9f, paint)
        drawText(canvas, text, x, y, width - 12f, 23f, ink)
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, width: Float, size: Float, ink: Int) {
        textPaint.textSize = size
        textPaint.color = ink
        val value = TextUtils.ellipsize(text, textPaint, width, TextUtils.TruncateAt.END).toString()
        canvas.drawText(value, x, y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    companion object {
        private val fuelCover = floatArrayOf(
            72f, 0f, 536f, 0f, 562f, 102f, 487f, 151f, 487f, 238f, 395f, 292f, 322f, 296f, 80f, 237f,
        )
        private val insuranceCover = floatArrayOf(
            225f, 347f, 386f, 281f, 573f, 365f, 590f, 512f, 539f, 554f, 418f, 613f, 170f, 514f, 170f, 475f,
        )
        private val washCover = floatArrayOf(
            1159f, 472f, 1373f, 359f, 1536f, 432f, 1536f, 658f, 1370f, 726f, 1148f, 631f,
        )
        private val closedSlot = floatArrayOf(-108f, 26f, 40f, -62f, 108f, -29f, -39f, 58f)
    }
}
