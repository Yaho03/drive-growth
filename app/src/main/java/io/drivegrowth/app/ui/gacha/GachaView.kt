package io.drivegrowth.app.ui.gacha

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.core.contract.*
import java.text.NumberFormat
import java.util.Locale

/** 복원은 새 View 생성 시만 받는다. 현재 Pending을 과거 상태로 덮는 API는 제공하지 않는다. */
class GachaView(context: Context, onEvent: (GachaEvent) -> Unit, restored: GachaUiState = GachaUiState()) : LinearLayout(context) {
    private val style = GarageStyle(context)
    private val session = GachaInteraction(onEvent, restored)
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
    fun completeGarageNavigation(id: SettlementId) { session.completeGarageNavigation(id); drawScreen() }
    fun resolveFailedRequest(key: RequestId): Boolean { val resolved = session.resolveFailedRequest(key); drawScreen(); return resolved }
    fun handleBack(): Boolean { val closed = session.cancel(); if (closed) drawScreen(); return closed }
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
        generation++; removeAllViews(); scroll = null; setPadding(dp(24),dp(16),dp(24),dp(16))
        if (!session.controlsEnabled) { addView(text(s(R.string.gacha_restricted),28)); return@with }
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
            addView(ImageView(context).apply { setImageResource(R.drawable.garage_vehicle_placeholder); contentDescription=s(R.string.gacha_neutral) },LayoutParams(-1,dp(100)))
            addView(text(s(R.string.gacha_pool),28,bold=true)); addView(text(s(R.string.gacha_pool_note),color=muted))
            val ids = value.candidates
            when { ids == null -> addView(text(s(R.string.gacha_missing)))
                ids.isEmpty() -> addView(text(s(R.string.gacha_empty)))
                else -> ids.forEach { id -> addView(text(value.content?.catalog?.vehicles?.singleOrNull { it.vehicleId == id }?.displayName ?: s(R.string.gacha_missing))) }
            }
        })
        add(facts,card().apply {
            addView(text(s(R.string.gacha_rates),28,bold=true))
            if (!value.ratesValid()) addView(text(s(R.string.gacha_rates_missing)))
            else Rarity.entries.forEach { r -> addView(text(s(R.string.gacha_rate,rarity(r),value.appliedPercent!!.getValue(r).stripTrailingZeros().toPlainString()),24,color=rarityColor(r))) }
            addView(text(s(R.string.gacha_versions,value.content?.catalog?.contentVersion?.value ?: s(R.string.gacha_missing),value.content?.ruleVersion?.value ?: s(R.string.gacha_missing)),color=muted))
        })
        add(actions,card().apply {
            addView(text(s(R.string.gacha_guarantee),28,bold=true))
            val rules=value.content?.gacha; val g=value.guarantee
            addView(text(s(R.string.gacha_threshold,rules?.let { rarity(it.guaranteedFrom) } ?: s(R.string.gacha_missing),rules?.pity?.toString() ?: s(R.string.gacha_missing))))
            addView(text(s(R.string.gacha_remaining,g?.remaining?.toString() ?: s(R.string.gacha_missing))))
            addView(text(s(when(g?.nextGuaranteed) { true -> R.string.gacha_next_guaranteed; false -> R.string.gacha_next_normal; null -> R.string.gacha_missing })))
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
        add(parent,card().apply {
            addView(text(s(R.string.gacha_result),28,bold=true)); addView(text(value.summary))
            addView(ImageView(context).apply { setImageResource(R.drawable.garage_vehicle_placeholder); contentDescription=s(R.string.gacha_neutral) },LayoutParams(-1,dp(100)))
            addView(text(s(R.string.gacha_vehicle,value.vehicleName ?: s(R.string.gacha_missing),rarity(vehicle.rarity)),30,color=rarityColor(vehicle.rarity),bold=true))
            addView(text(vehicle.ownedVehicleId.value,color=muted))
            addView(text(s(R.string.gacha_growth,vehicle.level,vehicle.levelProgressPercent)))
            addView(text(s(R.string.gacha_acquisition,money(vehicle.acquisitionCost))))
            addView(text(s(R.string.gacha_balance,money(receipt.balance))))
            addView(text(s(R.string.gacha_settlement,receipt.settlementId.value),color=muted))
            button(this,s(if(uiState.garagePending == null) R.string.gacha_garage else R.string.gacha_garage_pending),"gacha:garage",uiState.garagePending == null,true) { session.openGarage(); drawScreen() }
        })
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
