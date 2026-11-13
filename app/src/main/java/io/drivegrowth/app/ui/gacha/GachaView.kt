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
    private fun s(id: Int, vararg args: Any) = context.getString(id,*args)
    private fun money(value: GameMoney?) = value?.let { s(R.string.gacha_money,NumberFormat.getIntegerInstance(Locale.KOREA).format(it.units)) } ?: s(R.string.gacha_missing)
    private fun drawScreen(): Unit = with(style) {
        if (drawnFrame == session.frame?.generation) scroll?.takeIf { it.isLaidOut }?.let { savedY = it.scrollY } else savedY = 0
        drawnFrame = session.frame?.generation
        val focused = findFocus()?.tag
        generation++; celebration.rebuildContent { removeAllViews() }; scroll = null; setPadding(dp(24),dp(16),dp(24),dp(16))
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
    private fun offers(parent: LinearLayout, value: GachaOffers) = with(style) {
        add(parent,text(s(if(value.origin == GachaOrigin.FIXTURE) R.string.gacha_fixture else R.string.gacha_stored),color=rarityColor(Rarity.LEGEND)))
        add(parent,text(s(R.string.gacha_balance,money(value.balance)),24,bold=true))
        value.displayProblem()?.let { add(parent,text(status(it),24,bold=true)) }
        if(session.awaitingConfirmation) add(parent,text(s(R.string.gacha_awaiting)))
        val panels = if(wide) row() else column()
        add(parent,panels)
        val facts = column(); val actions = column()
        panels.addView(facts,if(wide) LayoutParams(0,-2,1.1f) else LayoutParams(-1,-2))
        panels.addView(actions,(if(wide) LayoutParams(0,-2,1f) else LayoutParams(-1,-2)).apply { if(wide) marginStart=dp(24) })
        add(facts,card().apply {
            addView(text(s(R.string.gacha_pool),28,bold=true))
            addView(text(s(R.string.gacha_pool_note),color=muted))
            addView(artwork.view(null, null, 116), LayoutParams(-1, dp(116)))
            val ids = value.candidates
            when { ids == null -> addView(text(s(R.string.gacha_missing)))
                ids.isEmpty() -> addView(text(s(R.string.gacha_empty)))
                else -> ids.chunked(if (wide) 2 else 1).forEach { group ->
                    add(this, row().apply {
                        group.forEachIndexed { index, id ->
                            val name = value.content?.catalog?.vehicles?.singleOrNull { it.vehicleId == id }?.displayName
                            addView(text(name ?: s(R.string.gacha_missing), 18).apply {
                                background = shape(inset)
                                setPadding(dp(10), dp(6), dp(10), dp(6))
                            }, LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(8) })
                        }
                    }, 8)
                }
            }
        })
        add(facts,card().apply {
            addView(text(s(R.string.gacha_rates),28,bold=true))
            if (!value.ratesValid()) addView(text(s(R.string.gacha_rates_missing)))
            else Rarity.entries.forEach { r ->
                val rate = value.appliedPercent?.let { s(R.string.gacha_rate,rarity(r),it.getValue(r).stripTrailingZeros().toPlainString()) }
                    ?: value.appliedRatio!!.let { "${rarity(r)} · ${it.numerators.getValue(r)}/${it.denominator}" }
                addView(text(rate,24,color=rarityColor(r)))
            }
            addView(text(s(R.string.gacha_versions,value.content?.catalog?.contentVersion?.value ?: s(R.string.gacha_missing),value.content?.ruleVersion?.value ?: s(R.string.gacha_missing)),color=muted))
        })
        add(actions,card().apply {
            addView(text(s(R.string.gacha_guarantee),28,bold=true))
            val rules=value.content?.gacha; val g=value.guarantee
            addView(text(s(R.string.gacha_threshold,rules?.let { rarity(it.guaranteedFrom) } ?: s(R.string.gacha_missing),rules?.pity?.toString() ?: s(R.string.gacha_missing))))
            val remaining = g?.remaining
            addView(text(s(R.string.gacha_remaining,remaining?.toString() ?: s(R.string.gacha_missing)), 20, bold = true))
            if (rules != null && rules.pity > 0 && remaining != null && remaining in 0..rules.pity) {
                addView(progressTrack(rules.pity - remaining, rules.pity, rarityColor(rules.guaranteedFrom)),
                    LayoutParams(-1, dp(8)))
            }
            addView(text(s(when(g?.nextGuaranteed) { true -> R.string.gacha_next_guaranteed; false -> R.string.gacha_next_normal; null -> R.string.gacha_missing }),
                color = if (g?.nextGuaranteed == true) rarityColor(Rarity.RARE) else ink))
            if (g?.available == false) addView(text(s(R.string.gacha_status_guarantee)))
            g?.description?.let { addView(text(it,color=muted)) }
            addView(text(s(R.string.gacha_space),24,bold=true))
            addView(text(s(R.string.gacha_space_value,value.space?.owned?.toString() ?: s(R.string.gacha_missing),value.space?.capacity?.toString() ?: s(R.string.gacha_missing))))
            value.space?.let { if(it.availability != GachaAvailability.READY) addView(text(status(it.availability))) }
        })
        val selected = value.options.singleOrNull { it.id == uiState.selected }
        if (selected != null) add(actions,card(true).apply {
            addView(text(s(R.string.gacha_review),28,bold=true)); optionFacts(this,selected)
            addView(text(s(R.string.gacha_no_debit),color=muted))
            button(this,s(R.string.gacha_confirm),"gacha:confirm",session.canSelect(selected.id),true) { session.confirm(); drawScreen() }
            button(this,s(R.string.gacha_cancel),"gacha:cancel",uiState.pending == null) { session.cancel(); drawScreen() }
        })
        add(actions,text(s(R.string.gacha_options),28,bold=true))
        if (value.options.isEmpty()) add(actions,text(s(R.string.gacha_missing)))
        value.options.forEach { option -> add(actions,card().apply {
            optionFacts(this,option)
            if(option.displayProblem() != null) addView(text(status(option.displayProblem()!!)))
            else if(!session.canSelect(option.id) && uiState.pending == null && value.displayProblem() == null) addView(text(s(R.string.gacha_previous_request)))
            button(this,s(R.string.gacha_open_review),"gacha:option:${option.id}",session.canSelect(option.id)) {
                session.select(option.id); savedY=0; scroll=null; drawScreen()
            }
        }) }
        add(actions,text(s(R.string.gacha_purchase_price,money(value.ticketPurchasePrice)),color=muted))
        add(actions,text(s(R.string.gacha_purchase_unsupported),color=muted))
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
        val artHeight = if (height > 0) {
            (height * .73f / resources.displayMetrics.density).toInt().coerceIn(168, 420)
        } else 264
        val sprite = sprites.bitmap(vehicle.vehicleId)
        val art = if (sprite == null) artwork.view(vehicle.vehicleId, vehicle.rarity, artHeight) else ImageView(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageDrawable(sprites.drawable(vehicle.vehicleId))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(24), dp(22), dp(24), dp(34))
        }
        val tierColor = rarityColor(vehicle.rarity)
        celebration.render(null, RewardCelebrationKind.GACHA, tierColor)
        panels.addView(celebration.over(art, fillHeight = true, stage = true),
            if (wide) LayoutParams(0, dp(artHeight), 1.2f) else LayoutParams(-1, dp(artHeight)))
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
