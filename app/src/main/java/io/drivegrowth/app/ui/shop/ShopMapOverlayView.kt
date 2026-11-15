package io.drivegrowth.app.ui.shop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.app.ui.pixel.ReferenceHudStyle
import io.drivegrowth.core.contract.GameMoney
import io.drivegrowth.core.rental.Facility
import java.text.NumberFormat
import java.util.Locale

internal sealed interface ShopMapTarget {
    data class FacilityLot(val facility: Facility) : ShopMapTarget
    data object Expansion : ShopMapTarget
    data object Decorations : ShopMapTarget
}

internal fun ShopMapTarget.focusRegion(): ShopFocusRegion? = when (this) {
    is ShopMapTarget.FacilityLot -> when (facility) {
        Facility.FUEL_STATION -> ShopFocusRegion(92f, 0f, 513f, 309f)
        Facility.INSURANCE -> ShopFocusRegion(205f, 282f, 590f, 604f)
        Facility.CAR_WASH -> ShopFocusRegion(1150f, 365f, 1536f, 700f)
    }
    ShopMapTarget.Expansion -> ShopFocusRegion(1135f, 245f, 1370f, 425f)
    ShopMapTarget.Decorations -> null
}

/** ShopView의 선택·보관 요청을 그대로 사용하고 지도 위에 확인 내용을 표시한다. */
@SuppressLint("ViewConstructor")
internal class ShopMapOverlayView(
    context: Context,
    private val onClose: () -> Unit,
    private val onChanged: () -> Unit,
    private val town: Bitmap? = null,
) : FrameLayout(context) {
    private val style = ShopStyle(context)
    private val signs = ReferenceHudStyle(context)
    private val format = NumberFormat.getIntegerInstance(Locale.KOREA)
    private var interaction: ShopInteraction? = null
    private var target: ShopMapTarget? = null
    private var generation = 0L
    private var scroll: ScrollView? = null
    private var scrollY = 0
    private var focusRegion: ShopFocusRegion? = null

    init {
        visibility = GONE
        isClickable = true
        setBackgroundColor(style.scrim)
        tag = "shop:map-overlay"
    }

    fun scrollPosition(): Pair<ShopMapTarget, Int>? =
        target?.let { it to (scroll?.takeIf { it.isLaidOut }?.scrollY ?: scrollY) }

    fun render(
        target: ShopMapTarget,
        interaction: ShopInteraction,
        projectedBalance: Map<String, GameMoney> = emptyMap(),
        initialScrollY: Int = 0,
        focus: ShopFocusRegion? = null,
    ) = with(style) {
        val sameTarget = this@ShopMapOverlayView.target == target && this@ShopMapOverlayView.interaction === interaction
        scrollY = if (sameTarget) scroll?.takeIf { it.isLaidOut }?.scrollY ?: scrollY else initialScrollY
        this@ShopMapOverlayView.target = target
        this@ShopMapOverlayView.interaction = interaction
        focusRegion = focus
        setBackgroundColor(if (focus == null) scrim else 0x16101931)
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
            ShopMapTarget.Expansion -> "주차장 확장"
            ShopMapTarget.Decorations -> s(R.string.shop_decorations)
        }
        val option = when (target) {
            is ShopMapTarget.FacilityLot -> content?.facilities?.firstOrNull { it.facility == target.facility }?.option
            ShopMapTarget.Expansion -> content?.expansion
            ShopMapTarget.Decorations -> null
        }
        val pending = interaction.uiState.pending != null
        var confirmButton: Button? = null
        val body = column().apply {
            if (focus != null) {
                addView(row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(lotArtwork(target).apply { background = infoSurface() },
                        LinearLayout.LayoutParams(0, dp(112), 1f))
                    addView(column().apply {
                        setPadding(dp(12), 0, 0, 0)
                        addView(text(title, 30, bold = true).apply { isAccessibilityHeading = true })
                        option?.let {
                            addView(text(availabilityLabel(it), 16, ink, bold = true).apply {
                                background = infoSurface()
                                setPadding(dp(12), dp(6), dp(12), dp(7))
                            }, LinearLayout.LayoutParams(-2, -2))
                        }
                    }, LinearLayout.LayoutParams(0, -2, 1.2f))
                })
            } else {
                addView(lotArtwork(target), LinearLayout.LayoutParams(dp(116), dp(64)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                })
                addView(text(title, 24, bold = true).apply {
                    gravity = Gravity.CENTER
                    isAccessibilityHeading = true
                })
            }
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
                            val shopName = s(R.string.shop_scene_default_sign)
                            addView(signPreview(s(R.string.shop_before, shopName), content.appearance))
                            addView(signPreview(s(R.string.shop_after, shopName), command.appearance))
                        }
                        describe(this, content, selected.option, projectedBalance)
                        confirmButton = confirm(interaction, selected, ::current, alreadySelected = true)
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
                    if (option == null) addView(text(s(if (target == ShopMapTarget.Expansion)
                        R.string.shop_no_expansion else R.string.shop_unknown), 18))
                    else {
                        if (focus == null) describe(this, content, option, projectedBalance)
                        else describeFocused(this, content, option, projectedBalance)
                        if (option.availability == ShopAvailability.AVAILABLE && option.command != null)
                            confirmButton = confirm(interaction, ShopSelection(content.revision, option), ::current)
                    }
                }
            }
        }
        val panel = column().apply {
            background = surface()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            val contentScroll = scroll(body, this@ShopMapOverlayView.scrollY).apply {
                tag = "shop:map-scroll"
                isFillViewport = false
            }
            this@ShopMapOverlayView.scroll = contentScroll
            addView(contentScroll, LinearLayout.LayoutParams(-1, -2, 1f))
            val footer = row()
            val confirmation = confirmButton
            val closeLabel = when {
                pending -> "닫기"
                confirmation != null -> "취소"
                interaction.uiState.selection != null -> s(R.string.shop_cancel)
                else -> "닫기"
            }
            val close = button(closeLabel) {
                if (current()) handleBack()
            }.apply { tag = "shop:map-close" }
            footer.addView(close, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (confirmation != null) marginEnd = dp(8)
            })
            if (confirmation != null) footer.addView(confirmation, LinearLayout.LayoutParams(0, -2, 1f))
            if (focus != null) {
                close.textSize = 20f
                close.minimumHeight = dp(60)
                confirmation?.textSize = 20f
                confirmation?.minimumHeight = dp(60)
            }
            add(this, footer, 8)
        }
        addView(panel, LayoutParams(-1, -2, Gravity.CENTER))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (childCount != 0) {
            val panel = getChildAt(0)
            val gap = style.dp(12)
            val region = focusRegion
            val fit = region?.let { ShopFocusLayout.fit(width, height, resources.displayMetrics.density) }
            val panelWidth = fit?.panelWidth ?: minOf(style.dp(CENTERED_WIDTH_DP), (width - gap * 2).coerceAtLeast(0))
            val maxHeight = fit?.maxPanelHeight ?: minOf(style.dp(540), (height - gap * 2).coerceAtLeast(0))
            panel.measure(MeasureSpec.makeMeasureSpec(panelWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxHeight, if (fit?.side == true) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
            (panel.layoutParams as LayoutParams).apply {
                this.width = panelWidth
                this.height = panel.measuredHeight
                gravity = when {
                    fit == null -> Gravity.CENTER
                    !fit.side -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    region.panelOnLeft -> Gravity.CENTER_VERTICAL or Gravity.LEFT
                    else -> Gravity.CENTER_VERTICAL or Gravity.RIGHT
                }
                val inset = if (fit == null) 0 else gap
                setMargins(inset, inset, inset, inset)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun handleBack(): Boolean {
        val current = interaction ?: return false
        if (visibility == GONE || !current.controlsEnabled) return false
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

    private fun availabilityLabel(option: ShopOption) = s(when (option.availability) {
        ShopAvailability.LOCKED -> R.string.shop_locked
        ShopAvailability.AVAILABLE -> R.string.shop_available
        ShopAvailability.INSTALLED -> R.string.shop_installed
        ShopAvailability.UNKNOWN -> R.string.shop_unknown
    })

    private fun describeFocused(
        parent: LinearLayout, content: ShopScreenState.Content, option: ShopOption,
        projectedBalance: Map<String, GameMoney>,
    ) = with(style) {
        fun fact(label: String, value: String, icon: ReferenceHudIcon) = add(parent, row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = infoSurface()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(View(context).apply {
                background = signs.icon(icon, 36)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(14) })
            addView(column().apply {
                addView(text(label, 18, accent, bold = true))
                addView(text(value, 22, bold = true))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }, 12)
        val expansion = (option.command as? ShopCommand.Expand)?.offer
        if (expansion != null && content.capacity == expansion.currentCapacity) {
            fact("차량 공간", "${expansion.currentCapacity} → ${expansion.nextCapacity}칸", ReferenceHudIcon.GARAGE)
        }
        fact("해금 조건", option.condition, ReferenceHudIcon.COLLECTION)
        fact("효과", option.effect, ReferenceHudIcon.RECORD)
        if (option.availability != ShopAvailability.INSTALLED) fact("요청 비용", money(option.price), ReferenceHudIcon.COIN)
        if (option.availability == ShopAvailability.AVAILABLE) {
            add(parent, text(s(R.string.shop_balance, money(content.balance)), 16), 7)
            parent.addView(text("적용 후 예상 잔액 · ${money(projectedBalance[option.id])}", 16))
        }
    }

    private fun describe(
        parent: LinearLayout,
        content: ShopScreenState.Content,
        option: ShopOption,
        projectedBalance: Map<String, GameMoney>,
    ) = with(style) {
        val expansion = (option.command as? ShopCommand.Expand)?.offer
        if (expansion != null && content.capacity == expansion.currentCapacity) {
            add(parent, text("차량 공간 ${expansion.currentCapacity} → ${expansion.nextCapacity}칸", 16, bold = true).apply {
                background = infoSurface()
                gravity = Gravity.CENTER
            }, 5)
        }
        if (option.availability != ShopAvailability.INSTALLED) {
            add(parent, text(s(R.string.shop_price, money(option.price)), 16, color = accent, bold = true).apply {
                background = infoSurface()
                gravity = Gravity.CENTER
            }, 5)
        }
        add(parent, text(option.title, 15, bold = true), 6)
        parent.addView(text(availabilityLabel(option), 14, color = accent))
        parent.addView(text(s(R.string.shop_condition, option.condition), 14))
        parent.addView(text(s(R.string.shop_effect, option.effect), 14))
        if (option.availability == ShopAvailability.AVAILABLE) {
            parent.addView(text(s(R.string.shop_balance, money(content.balance)), 14))
            parent.addView(text("적용 후 예상 잔액 · ${money(projectedBalance[option.id])}", 14))
        }
    }

    private fun confirm(
        interaction: ShopInteraction,
        selection: ShopSelection,
        current: () -> Boolean,
        alreadySelected: Boolean = false,
    ): Button = with(style) {
        val label = when (selection.option.command) {
            is ShopCommand.Expand -> "확장하기"
            is ShopCommand.BuyFacility -> "건설하기"
            else -> "적용하기"
        }
        button(label, interaction.canSelect(selection), primary = true) {
            if (current() && interaction.canSelect(selection)) {
                if (!alreadySelected) interaction.select(selection)
                if (interaction.confirm(selection)) onChanged()
            }
        }.apply { tag = "shop:map-confirm" }
    }

    private fun lotArtwork(target: ShopMapTarget) = object : View(context) {
        private val paint = Paint()
        private val path = Path()
        private val facility = (target as? ShopMapTarget.FacilityLot)?.focusRegion()?.let {
            Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }
        private val image = RectF()
        private val roof = when (target) {
            is ShopMapTarget.FacilityLot -> when (target.facility) {
                Facility.FUEL_STATION -> 0xffe46a58.toInt()
                Facility.INSURANCE -> 0xff50cbb5.toInt()
                Facility.CAR_WASH -> 0xfff4af45.toInt()
            }
            ShopMapTarget.Decorations -> 0xff629af0.toInt()
            ShopMapTarget.Expansion -> 0xff64758c.toInt()
        }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (facility != null && town != null) {
                val inset = style.dp(8) * 2
                val scale = minOf((width - inset).coerceAtLeast(0).toFloat() / facility.width(),
                    (height - inset).coerceAtLeast(0).toFloat() / facility.height())
                val w = facility.width() * scale
                val h = facility.height() * scale
                image.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                canvas.drawBitmap(town, facility, image, paint)
                return
            }
            val scale = minOf(width / 132f, height / 70f)
            canvas.save()
            canvas.translate((width - 132 * scale) / 2, (height - 70 * scale) / 2)
            canvas.scale(scale, scale)
            canvas.polygon(0xff102037.toInt(), 9, 46, 67, 67, 123, 45, 123, 51, 67, 70, 9, 51)
            canvas.polygon(0xff5e9c45.toInt(), 9, 46, 63, 21, 123, 45, 67, 67)
            if (target == ShopMapTarget.Expansion) {
                canvas.polygon(roof, 20, 46, 62, 26, 112, 45, 67, 63)
                val line = 0xffe2edff.toInt()
                canvas.polygon(line, 24, 46, 63, 28, 109, 45, 106, 46, 63, 30, 27, 47)
                canvas.polygon(line, 39, 52, 79, 34, 82, 35, 42, 53)
                canvas.polygon(line, 53, 58, 94, 39, 97, 40, 56, 59)
                paint.color = 0xfff2f6ff.toInt()
                canvas.drawRect(89f, 12f, 92f, 40f, paint)
                paint.color = 0xff4d9ff6.toInt()
                canvas.drawRect(83f, 9f, 100f, 24f, paint)
                paint.color = 0xffeff7ff.toInt()
                paint.textSize = 13f
                paint.isFakeBoldText = true
                canvas.drawText("P", 87f, 21f, paint)
            } else {
                canvas.polygon(0xffdbc29b.toInt(), 28, 29, 71, 44, 71, 61, 28, 45)
                canvas.polygon(0xff879eb8.toInt(), 71, 44, 105, 29, 105, 46, 71, 61)
                canvas.polygon(roof, 24, 27, 60, 10, 111, 29, 72, 47)
                canvas.polygon(0xff293d58.toInt(), 39, 38, 58, 45, 58, 56, 39, 49)
                canvas.polygon(0xff5588b7.toInt(), 77, 48, 99, 38, 99, 44, 77, 54)
            }
            canvas.restore()
        }

        private fun Canvas.polygon(color: Int, vararg xy: Int) {
            path.reset()
            path.moveTo(xy[0].toFloat(), xy[1].toFloat())
            for (i in 2 until xy.size step 2) path.lineTo(xy[i].toFloat(), xy[i + 1].toFloat())
            path.close()
            paint.color = color
            drawPath(path, paint)
        }
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

    companion object { const val CENTERED_WIDTH_DP = 376 }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun money(value: GameMoney?) = value?.let { s(R.string.shop_money, format.format(it.units)) } ?: s(R.string.shop_unknown)
}
