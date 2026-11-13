package io.drivegrowth.app.ui.gacha

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.garage.VehicleArtwork
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.presentation.RewardCelebrationKind
import io.drivegrowth.app.ui.presentation.RewardCelebrationView
import io.drivegrowth.core.contract.*
import java.text.NumberFormat
import java.util.Locale

/** 복원은 새 View 생성 시만 받는다. 현재 Pending을 과거 상태로 덮는 API는 제공하지 않는다. */
class GachaView(context: Context, onEvent: (GachaEvent) -> Unit, restored: GachaUiState = GachaUiState()) : LinearLayout(context) {
    private val style = GarageStyle(context, pixelPanel = true)
    private val artwork = VehicleArtwork(context, style)
    private val sprites = VehicleSprites(context)
    internal var onCelebration: ((RewardCelebrationKind) -> Unit)? = null
    private val celebration = RewardCelebrationView(context).apply {
        onStarted = { onCelebration?.invoke(it) }
    }
    private var submittedRequest: RequestId? = null
    private var revealResult: SettlementId? = null
    private val session = GachaInteraction({ event ->
        if (event is GachaEvent.Draw) submittedRequest = event.request.input.option.requestId
        onEvent(event)
    }, restored)
    private var wide = false
    private var generation = 0L
    private var scroll: ScrollView? = null
    private var resultStage: View? = null
    private var savedY = 0
    private var drawnFrame: Long? = null
    val uiState: GachaUiState get() = session.uiState
    init { orientation = VERTICAL; setBackgroundColor(style.base) }
    fun render(value: GachaFrame, controlsEnabled: Boolean): Boolean { val accepted = session.render(value, controlsEnabled); drawScreen(); return accepted }
    fun complete(request: GachaRequest, result: ContractResult<Unit>) { session.complete(request, result); drawScreen() }
    fun completeReload(token: String) { session.completeReload(token); drawScreen() }
    fun completeGarageNavigation(request: GachaGarageRequest) { session.completeGarageNavigation(request); drawScreen() }
    fun resolveFailedRequest(key: RequestId): Boolean { val resolved = session.resolveFailedRequest(key); drawScreen(); return resolved }
    fun handleBack(): Boolean {
        if (closeReveal()) return true
        val closed = session.cancel()
        if (closed) drawScreen()
        return closed
    }

    fun closeReveal(): Boolean {
        if (revealResult == null || !session.controlsEnabled) return false
        endReveal()
        drawScreen()
        return true
    }

    private fun endReveal() {
        revealResult = null
        celebration.finishGacha()
    }
    fun release() { session.release(); drawScreen() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val next = w >= style.dp(900)
        if (next != wide) { wide = next; post { drawScreen() } }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        resultStage?.let { stage ->
            val height = resultStageHeight(MeasureSpec.getSize(heightMeasureSpec))
            if (stage.layoutParams.height != height) {
                stage.layoutParams = stage.layoutParams.apply { this.height = height }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun resultStageHeight(availableHeight: Int): Int =
        if (availableHeight > 0) (availableHeight * .73f).toInt().coerceIn(style.dp(168), style.dp(420))
        else style.dp(264)

    private fun s(id: Int, vararg args: Any) = context.getString(id,*args)
    private fun money(value: GameMoney?) = value?.let { s(R.string.gacha_money,NumberFormat.getIntegerInstance(Locale.KOREA).format(it.units)) } ?: s(R.string.gacha_missing)
    private fun drawScreen(): Unit = with(style) {
        if (drawnFrame == session.frame?.generation) scroll?.takeIf { it.isLaidOut }?.let { savedY = it.scrollY } else savedY = 0
        drawnFrame = session.frame?.generation
        val focused = findFocus()?.tag
        generation++; celebration.rebuildContent { removeAllViews() }; scroll = null; setPadding(dp(24),dp(16),dp(24),dp(16))
        resultStage = null
        if (!session.controlsEnabled) {
            endReveal()
            celebration.render(null, RewardCelebrationKind.GACHA)
            submittedRequest = null
            addView(text(s(R.string.gacha_restricted), 28))
            return@with
        }
        val result = (session.frame?.screen as? GachaScreen.Result)?.value
        val receipt = result?.settlement
        if (revealResult != null && (revealResult != receipt?.settlementId || !ValueAnimator.areAnimatorsEnabled())) {
            endReveal()
        }
        if (result?.origin == GachaOrigin.STORED && receipt != null && receipt.transaction.requestId == submittedRequest) {
            submittedRequest = null
            if (ValueAnimator.areAnimatorsEnabled() &&
                receipt.changedVehicle?.vehicleId?.let(sprites::bitmap) != null
            ) revealResult = receipt.settlementId
        }
        if (result != null && revealResult == result.settlement.settlementId && showReveal(result)) return@with
        addView(text(s(R.string.gacha_title),32,bold=true))
        val body = column(); scroll = scroll(body,savedY); addView(scroll,LayoutParams(-1,0,1f))
        if (uiState.pending != null) add(body,text(s(R.string.gacha_pending),color=rarityColor(Rarity.RARE)))
        uiState.failure?.let { add(body,text(error(it),24)) }
        when(val screen = session.frame?.screen) {
            null, GachaScreen.Loading -> add(body,text(s(R.string.gacha_loading)))
            is GachaScreen.Unsupported -> { add(body,text(s(R.string.gacha_unsupported),28,bold=true)); add(body,text(screen.message)) }
            is GachaScreen.Missing -> { add(body,text(s(R.string.gacha_missing),28,bold=true)); add(body,text(screen.message)); retry(body,screen.retryAllowed) }
            is GachaScreen.Failure -> { add(body,text(error(screen.error),28,bold=true)); add(body,text(screen.message)); retry(body,screen.retryAllowed) }
            is GachaScreen.Offers -> offers(body,screen.value)
            is GachaScreen.Result -> result(body,screen.value)
        }
        if (focused != null) findViewWithTag<View>(focused)?.takeIf { it.isEnabled }?.requestFocus()
    }
    private fun showReveal(result: GachaConfirmedResult) = with(style) {
        val receipt = result.settlement
        val vehicle = checkNotNull(receipt.changedVehicle)
        val id = receipt.settlementId
        val tierColor = rarityColor(vehicle.rarity)
        val token = generation
        val held = celebration.holdGacha(id.value, tierColor, sprites.bitmap(vehicle.vehicleId)) {
            if (revealResult == id) {
                endReveal()
                drawScreen()
            }
        }
        if (!held) {
            revealResult = null
            return@with false
        }
        setPadding(0, 0, 0, 0)
        val scene = FrameLayout(context)
        val stage = celebration.over(View(context), fillHeight = true)
        scene.addView(stage, FrameLayout.LayoutParams(-1, -1))
        val reveal = button("화면을 눌러 셔터 올리기", primary = true) {
            if (token != generation || !session.controlsEnabled || revealResult != id || !hasWindowFocus()) return@button
            if (!ValueAnimator.areAnimatorsEnabled()) {
                endReveal()
                drawScreen()
            } else if (celebration.openGacha()) {
                scene.isClickable = false
                scene.findViewWithTag<View>("gacha:reveal")?.visibility = View.GONE
            }
        }.apply {
            tag = "gacha:reveal"
            visibility = if (celebration.waitingForReveal) View.VISIBLE else View.GONE
        }
        if (celebration.waitingForReveal) scene.setOnClickListener { reveal.performClick() }
        scene.addView(reveal, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(12) })
        val skip = button("건너뛰기") {
            if (token == generation && session.controlsEnabled && revealResult == id && hasWindowFocus()) {
                endReveal()
                drawScreen()
            }
        }.apply { tag = "gacha:skip-reveal" }
        scene.addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
            .apply { topMargin = dp(8); marginEnd = dp(8) })
        addView(scene, LayoutParams(-1, 0, 1f))
        true
    }

    private fun retry(parent: LinearLayout, allowed: Boolean) {
        if (allowed) button(parent,s(if(uiState.reloadToken == null) R.string.gacha_retry else R.string.gacha_retry_pending),"gacha:reload",uiState.reloadToken == null) { session.reload(); drawScreen() }
    }
    /** HTML 시제품의 뽑기 화면 배치: 왼쪽 뽑기 카드(보장 막대·비용·버튼), 오른쪽 확률·나올 수 있는 차. */
    private fun offers(parent: LinearLayout, value: GachaOffers) = with(style) {
        if(value.origin == GachaOrigin.FIXTURE) add(parent,text(s(R.string.gacha_fixture),color=rarityColor(Rarity.LEGEND)))
        if(session.awaitingConfirmation) add(parent,text(s(R.string.gacha_awaiting)))
        val panels = if(wide) row() else column()
        add(parent,panels)
        val main = column(); val side = column()
        panels.addView(main,if(wide) LayoutParams(0,-2,1f) else LayoutParams(-1,-2))
        panels.addView(side,(if(wide) LayoutParams(0,-2,1f) else LayoutParams(-1,-2)).apply { if(wide) marginStart=dp(16) })
        fun chip(label: String, fg: Int, fill: Int) = text(label, 16, fg, bold = true).apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = dp(16).toFloat() }
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val rules = value.content?.gacha; val g = value.guarantee
        val paid = value.options.firstOrNull { it.kind == GachaOptionKind.PAID }
        val free = value.options.firstOrNull { it.kind == GachaOptionKind.FREE }
        add(main,card().apply {
            addView(artwork.view(null, null, 150), LayoutParams(-1, dp(150)))
            // 보장: "30회 안에 희귀 이상 1대 보장 · 남은 횟수 N회" + 막대
            addView(row().apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = shape(inset); setPadding(dp(14), dp(10), dp(14), dp(10))
                val remaining = g?.remaining
                addView(text(s(R.string.gacha_pity_line, rules?.pity?.toString() ?: s(R.string.gacha_missing),
                    rules?.let { rarity(it.guaranteedFrom) } ?: s(R.string.gacha_missing), remaining?.toString() ?: s(R.string.gacha_missing)), 17), LayoutParams(0, -2, 2.4f))
                if (rules != null && rules.pity > 0 && remaining != null && remaining in 0..rules.pity)
                    addView(progressTrack(rules.pity - remaining, rules.pity, rarityColor(Rarity.RARE)), LayoutParams(0, dp(10), 1f).apply { marginStart = dp(12) })
            }, LayoutParams(-1, -2).apply { topMargin = dp(12) })
            if (g?.nextGuaranteed == true) addView(text(s(R.string.gacha_next_guaranteed), 17, rarityColor(Rarity.RARE), bold = true))
            // 비용: "뽑기 1회 ● 500" + [무료 뽑기권 N장]
            addView(row().apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(text(s(R.string.gacha_one_pull), 18, muted))
                addView(text("● " + (paid?.moneyCost?.let { java.text.NumberFormat.getIntegerInstance(Locale.KOREA).format(it.units) } ?: s(R.string.gacha_missing)), 20, PixelStyle.gold, bold = true).apply { setPadding(dp(8), 0, dp(12), 0) })
                addView(chip(s(R.string.gacha_free_tickets, (free?.ownedTickets ?: paid?.ownedTickets)?.toString() ?: s(R.string.gacha_missing)), PixelStyle.focus, 0xff1e2f57.toInt()))
            }, LayoutParams(-1, -2).apply { topMargin = dp(10) })
            value.space?.let { sp ->
                if (sp.availability != GachaAvailability.READY) addView(text(s(R.string.gacha_space_value, sp.owned.toString(), sp.capacity.toString()) + " · " + status(sp.availability), 17, 0xffffd36b.toInt(), bold = true).apply {
                    background = shape(0xff3d3216.toInt()); setPadding(dp(14), dp(10), dp(14), dp(10)) }, LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
            value.displayProblem()?.let { addView(text(status(it), 17, 0xffff9a8a.toInt(), bold = true)) }
            // 버튼: [무료권으로 뽑기] [500으로 뽑기]
            val buttons = row()
            listOfNotNull(free, paid).forEachIndexed { index, option ->
                val enabled = session.canSelect(option.id)
                val label = if (option.kind == GachaOptionKind.FREE) s(R.string.gacha_free_button)
                    else s(R.string.gacha_paid_button, option.moneyCost?.let { java.text.NumberFormat.getIntegerInstance(Locale.KOREA).format(it.units) } ?: "")
                val primary = enabled && (option.kind == GachaOptionKind.FREE || free?.let { !session.canSelect(it.id) } != false)
                val token = generation
                buttons.addView(button(label, enabled, primary) {
                    if (token == generation && session.controlsEnabled) { session.select(option.id); savedY = 0; scroll = null; drawScreen() }
                }.apply { tag = "gacha:option:${option.id}"; contentDescription = "${option.title} · $label" },
                    LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(10) })
            }
            addView(buttons, LayoutParams(-1, -2).apply { topMargin = dp(14) })
            listOfNotNull(free, paid).forEach { option ->
                option.displayProblem()?.let { addView(text("${option.title} · ${status(it)}", 15, muted)) }
            }
            addView(text(s(R.string.gacha_balance,money(value.balance)), 15, muted))
        })
        val selected = value.options.singleOrNull { it.id == uiState.selected }
        if (selected != null) add(main,card(true).apply {
            addView(text(s(R.string.gacha_review),24,bold=true)); optionFacts(this,selected)
            addView(text(s(R.string.gacha_no_debit),color=muted))
            button(this,s(R.string.gacha_confirm),"gacha:confirm",session.canSelect(selected.id),true) { session.confirm(); drawScreen() }
            button(this,s(R.string.gacha_cancel),"gacha:cancel",uiState.pending == null) { session.cancel(); drawScreen() }
        })
        add(side,card().apply {
            addView(text(s(R.string.gacha_rates),24,bold=true))
            if (!value.ratesValid()) addView(text(s(R.string.gacha_rates_missing)))
            else Rarity.entries.forEach { r ->
                val rate = value.appliedPercent?.let { s(R.string.gacha_rate,rarity(r),it.getValue(r).stripTrailingZeros().toPlainString()) }
                    ?: value.appliedRatio!!.let { "${rarity(r)} · ${it.numerators.getValue(r)}/${it.denominator}" }
                val share = value.appliedPercent?.getValue(r)?.toDouble() ?: value.appliedRatio!!.let { it.numerators.getValue(r).toDouble() * 100 / it.denominator.toDouble() }
                addView(row().apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(text(rate,18,color=rarityColor(r),bold=true), LayoutParams(dp(150), -2))
                    addView(progressTrack((share * 10).toInt().coerceIn(if (share > 0) 4 else 0, 1000), 1000, rarityColor(r)), LayoutParams(0, dp(10), 1f))
                }, LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            addView(text(s(R.string.gacha_pool),20,bold=true), LayoutParams(-1, -2).apply { topMargin = dp(14) })
            val ids = value.candidates
            when { ids == null -> addView(text(s(R.string.gacha_missing)))
                ids.isEmpty() -> addView(text(s(R.string.gacha_empty)))
                else -> ids.chunked(if (wide) 3 else 2).forEach { group ->
                    add(this, row().apply {
                        group.forEachIndexed { index, id ->
                            val name = value.content?.catalog?.vehicles?.singleOrNull { it.vehicleId == id }?.displayName
                            addView(row().apply {
                                gravity = android.view.Gravity.CENTER_VERTICAL
                                background = shape(inset)
                                setPadding(dp(6), dp(2), dp(10), dp(2))
                                sprites.bitmap(id)?.let { bmp -> addView(android.widget.ImageView(context).apply {
                                    setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                }, LayoutParams(dp(40), dp(28)).apply { marginEnd = dp(4) }) }
                                addView(text(name ?: s(R.string.gacha_missing), 15).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                            }, LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) })
                        }
                        repeat((if (wide) 3 else 2) - group.size) { addView(View(context), LayoutParams(0, 1, 1f).apply { marginStart = dp(6) }) }
                    }, 6)
                }
            }
        })
    }
    private fun optionFacts(parent: LinearLayout, option: GachaOption) = with(style) {
        parent.addView(text(option.title,26,bold=true))
        parent.addView(text(s(if(option.kind == GachaOptionKind.FREE) R.string.gacha_free else R.string.gacha_paid),color=muted))
        parent.addView(text(s(R.string.gacha_money_cost,money(option.moneyCost))))
        parent.addView(text(s(R.string.gacha_ticket_cost,option.ticketCost?.toString() ?: s(R.string.gacha_missing))))
        parent.addView(text(s(R.string.gacha_ticket_owned,option.ownedTickets?.toString() ?: s(R.string.gacha_missing))))
        parent.addView(text(option.description,color=muted))
    }
    private fun result(parent: LinearLayout, value: GachaConfirmedResult) = with(style) {
        val receipt=value.settlement; val vehicle=receipt.changedVehicle!!
        add(parent,text(s(if(value.origin == GachaOrigin.FIXTURE) R.string.gacha_fixture else R.string.gacha_stored),color=rarityColor(Rarity.LEGEND)))
        val panels = if (wide) row() else column()
        add(parent, panels)
        val sprite = sprites.bitmap(vehicle.vehicleId)
        val art = if (sprite == null) artwork.view(vehicle.vehicleId, vehicle.rarity, 168) else ImageView(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageDrawable(sprites.drawable(vehicle.vehicleId))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(24), dp(22), dp(24), dp(34))
        }
        val tierColor = rarityColor(vehicle.rarity)
        celebration.render(null, RewardCelebrationKind.GACHA, tierColor)
        val stage = celebration.over(art, fillHeight = true, stage = true).apply { tag = "gacha:result-stage" }
        resultStage = stage
        val stageHeight = resultStageHeight(height)
        panels.addView(stage, if (wide) LayoutParams(0, stageHeight, 1.2f) else LayoutParams(-1, stageHeight))
        val details = card().apply {
            background = GradientDrawable().apply {
                setColor(0xff1c2342.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), tierColor)
            }
            addView(text(rarity(vehicle.rarity), 28, color = 0xff111628.toInt(), bold = true).apply {
                gravity = Gravity.CENTER
                background = shape(tierColor)
            })
            addView(text(s(R.string.gacha_result), 28, bold = true))
            addView(text(value.summary))
            addView(text(s(R.string.gacha_vehicle,value.vehicleName ?: s(R.string.gacha_missing),rarity(vehicle.rarity)),30,color=tierColor,bold=true))
            addView(text(vehicle.ownedVehicleId.value,color=muted))
            addView(text(s(R.string.gacha_growth,vehicle.level,vehicle.levelProgressPercent)))
            addView(text(s(R.string.gacha_acquisition,money(vehicle.acquisitionCost))))
            addView(text(s(R.string.gacha_balance,money(receipt.balance))))
            addView(text(s(R.string.gacha_settlement,receipt.settlementId.value),color=muted))
            button(this,s(if(uiState.garagePending == null) R.string.gacha_garage else R.string.gacha_garage_pending),"gacha:garage",uiState.garagePending == null,true) { session.openGarage(); drawScreen() }
        }
        panels.addView(details, if (wide) LayoutParams(0, -2, 1f).apply { marginStart = dp(16) }
            else LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    private fun button(parent: LinearLayout, label: String, tag: String, enabled: Boolean, primary: Boolean=false, action: () -> Unit) {
        val token=generation
        style.add(parent,style.button(label,enabled,primary) { if(token == generation && session.controlsEnabled) action() }.apply { this.tag=tag })
    }
    private fun status(value: GachaAvailability) = s(when(value) {
        GachaAvailability.READY -> R.string.gacha_status_ready; GachaAvailability.EMPTY_CANDIDATES -> R.string.gacha_status_empty
        GachaAvailability.GUARANTEE_UNAVAILABLE -> R.string.gacha_status_guarantee; GachaAvailability.NO_SPACE -> R.string.gacha_status_space
        GachaAvailability.INSUFFICIENT_MONEY -> R.string.gacha_status_money; GachaAvailability.INSUFFICIENT_TICKETS -> R.string.gacha_status_tickets
        GachaAvailability.MISSING -> R.string.gacha_status_missing; GachaAvailability.UNSUPPORTED -> R.string.gacha_status_unsupported
        GachaAvailability.INVALID_DISPLAY -> R.string.gacha_status_invalid
    })
    private fun error(value: ContractError) = s(when(value) {
        ContractError.INSUFFICIENT_FUNDS -> R.string.gacha_status_money; ContractError.CAPACITY_EXCEEDED -> R.string.gacha_status_space
        ContractError.STATE_CONFLICT -> R.string.gacha_error_conflict; ContractError.STORAGE_FAILURE -> R.string.gacha_error_storage
        ContractError.POLICY_UNRESOLVED -> R.string.gacha_error_policy; ContractError.PERMISSION_DENIED -> R.string.gacha_error_permission
        ContractError.UX_RESTRICTED -> R.string.gacha_error_restricted; ContractError.INVALID_INPUT -> R.string.gacha_error_invalid
        ContractError.DATA_UNAVAILABLE -> R.string.gacha_error_unavailable
    })
}
