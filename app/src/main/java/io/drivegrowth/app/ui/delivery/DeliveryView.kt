package io.drivegrowth.app.ui.delivery

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.delivery.DeliveryOfferId

/** 전달받은 탁송 상태를 표시한다. 목적지 조회·경제 계산·저장·도착 판정은 호스트 책임이다. */
class DeliveryView(
    context: Context,
    private val onRequest: (DeliveryRequest) -> Unit,
    restored: DeliveryInteractionState = DeliveryInteractionState(),
    private val navigationControls: () -> View? = { null },
    private val hostStatus: () -> View? = { null },
) : LinearLayout(context) {
    private val style = GarageStyle(context)
    private val session = DeliveryInteraction(restored)
    private var selectedOfferId = restored.selectedOfferId
    private var selectedFrameVersion = restored.selectedFrameVersion
    private var drawGeneration = 0L
    private var scroll: ScrollView? = null
    private var savedY = 0
    private var drawnVersion: Long? = null
    private var wide = false

    init { orientation = VERTICAL; setBackgroundColor(style.base) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val next = w >= style.dp(760)
        if (next != wide) { wide = next; post { drawScreen() } }
    }

    fun render(frame: DeliveryFrame, controlsEnabled: Boolean): Boolean {
        val accepted = session.render(frame, controlsEnabled)
        validateSelection()
        drawScreen()
        return accepted
    }

    fun complete(request: DeliveryRequest, frame: DeliveryFrame): Boolean {
        val accepted = session.complete(request, frame)
        validateSelection()
        drawScreen()
        return accepted
    }

    fun saveUiState() = session.save().copy(
        selectedOfferId = selectedOfferId, selectedFrameVersion = selectedFrameVersion,
    )

    /** 상세 닫기는 로컬 선택만 닫는다. 검토 이탈은 거래 취소와 다른 요청이다. */
    fun handleBack(): Boolean {
        if (!session.controlsEnabled || session.pending != null) return false
        if (session.frame?.screen is DeliveryScreen.Offers && selectedOfferId != null) {
            closeDetail()
            return true
        }
        return when (session.frame?.screen) {
            is DeliveryScreen.ChangeReview, is DeliveryScreen.AbandonReview -> send(DeliveryCommand.ExitReview)
            else -> false
        }
    }

    fun release() {
        session.release()
        selectedOfferId = null
        selectedFrameVersion = null
        drawScreen()
    }

    private fun validateSelection() {
        val frame = session.frame
        val offers = (frame?.screen as? DeliveryScreen.Offers)?.proposal?.proposed?.offers
        if (selectedFrameVersion != frame?.version || offers?.count { it.id == selectedOfferId } != 1) {
            selectedOfferId = null
            selectedFrameVersion = null
        }
    }

    private fun drawScreen(): Unit = with(style) {
        val version = session.frame?.version
        val sameFrame = version == drawnVersion
        if (sameFrame) scroll?.takeIf { it.isLaidOut }?.let { savedY = it.scrollY }
        else savedY = 0
        val focused = if (sameFrame) findFocus()?.tag else null
        drawnVersion = version
        drawGeneration++
        removeAllViews()
        scroll = null
        setPadding(dp(24), dp(16), dp(24), dp(16))
        if (!session.controlsEnabled) {
            addView(text(s(R.string.delivery_restricted), 28, bold = true))
            addView(text(s(R.string.delivery_restricted_body), color = muted))
            return@with
        }
        addView(text(s(R.string.delivery_title), 32, bold = true))
        val body = column()
        scroll = style.scroll(body, savedY)
        addView(scroll, LayoutParams(-1, 0, 1f))
        if (session.pending != null) note(body, R.string.delivery_pending)
        val state = session.frame?.screen
        hostStatus()?.let { body.addView(it) }
        if (state !is DeliveryScreen.Offers) navigationControls()?.let { body.addView(it) }
        when (state) {
            null, DeliveryScreen.Loading -> note(body, R.string.delivery_loading)
            is DeliveryScreen.Unsupported -> {
                heading(body, R.string.delivery_unavailable)
                info(body, present(state.message, R.string.delivery_information_missing))
            }
            is DeliveryScreen.Failure -> {
                heading(body, R.string.delivery_failure)
                info(body, present(state.message, R.string.delivery_information_missing))
                if (state.retryAllowed) action(body, R.string.delivery_retry, "delivery:refresh", DeliveryCommand.Refresh)
            }
            is DeliveryScreen.Destination -> {
                heading(body, R.string.delivery_destination_title)
                info(body, present(state.name, R.string.delivery_destination_missing))
                info(body, present(state.estimatedDistanceLabel, R.string.delivery_distance_missing))
                info(body, present(state.sourceLabel, R.string.delivery_source_missing), muted = true)
                action(body, R.string.delivery_choose_destination, "delivery:destination", DeliveryCommand.ChooseDestination(null))
                if (!state.chooseAllowed) info(body, present(state.reason, R.string.delivery_action_unavailable), muted = true)
            }
            is DeliveryScreen.Offers -> offers(body, state.proposal)
            is DeliveryScreen.Active -> active(body, state.delivery, actions = true)
            is DeliveryScreen.ChangeReview -> changeReview(body, state)
            is DeliveryScreen.AbandonReview -> abandonReview(body, state)
            is DeliveryScreen.AwaitingResult -> {
                heading(body, R.string.delivery_awaiting)
                info(body, present(state.message, R.string.delivery_information_missing))
                note(body, R.string.delivery_awaiting_note)
                active(body, state.delivery, actions = false)
            }
        }
        if (state is DeliveryScreen.Offers) navigationControls()?.let { body.addView(it) }
        if (focused != null) findViewWithTag<View>(focused)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun offers(parent: LinearLayout, value: DeliveryProposalUi) {
        destination(parent, value.destinationName, value.proposed.estimated.meters, value.proposed.estimated.source)
        val selected = value.proposed.offers.singleOrNull { it.id == selectedOfferId }
        if (selected != null) {
            localAction(parent, R.string.delivery_close_detail, "delivery:back") { closeDetail() }
            offerCard(parent, selected, value.vehicleNames[selected.vehicleId], detailed = true)
            info(parent, present(value.confirmationTerms, R.string.delivery_terms_missing), muted = true)
            val permission = value.availability[selected.id]
            if (permission?.allowed != true) info(parent, present(permission?.reason, R.string.delivery_action_unavailable), muted = true)
            action(parent, if (selected is DeliveryOffer.Purchase) R.string.delivery_confirm_purchase else R.string.delivery_confirm_income,
                "delivery:confirm", DeliveryCommand.Select(value.proposed, selected.id), primary = true)
            return
        }
        if (value.proposed.offers.isEmpty()) {
            heading(parent, R.string.delivery_empty)
            note(parent, R.string.delivery_empty_body)
            return
        }
        listOf(true, false).forEach { income ->
            val group = value.proposed.offers.filter { (it is DeliveryOffer.Income) == income }
            if (group.isNotEmpty()) heading(parent, if (income) R.string.delivery_income else R.string.delivery_purchase)
            group.forEach { offer ->
                offerCard(parent, offer, value.vehicleNames[offer.vehicleId])
                localAction(parent, R.string.delivery_details, "delivery:offer:${offer.id.value}",
                    enabled = value.proposed.offers.count { it.id == offer.id } == 1) {
                    selectedOfferId = offer.id
                    selectedFrameVersion = session.frame?.version
                    resetScroll()
                    drawScreen()
                }
                val permission = value.availability[offer.id]
                if (permission?.allowed != true) info(parent, present(permission?.reason, R.string.delivery_action_unavailable), muted = true)
            }
        }
    }

    private fun active(parent: LinearLayout, value: DeliveryActiveUi, actions: Boolean) {
        heading(parent, R.string.delivery_active)
        destination(parent, value.destinationName, value.active.proposed.estimated.meters, value.active.proposed.estimated.source)
        offerCard(parent, value.active.offer, value.vehicleName, active = true)
        info(parent, context.getString(R.string.delivery_observed, present(value.observedDistanceLabel, R.string.delivery_information_missing)))
        info(parent, present(value.progressLabel, R.string.delivery_progress_missing))
        if (!actions) return
        heading(parent, R.string.delivery_change_opportunity)
        info(parent, present(value.change.reason, if (value.change.allowed) R.string.delivery_change_available else R.string.delivery_change_unavailable), muted = true)
        action(parent, R.string.delivery_change_destination, "delivery:destination", DeliveryCommand.ChooseDestination(value.active))
        action(parent, R.string.delivery_review_abandon, "delivery:review-abandon", DeliveryCommand.ReviewAbandon(value.active))
        if (!value.abandon.allowed) info(parent, present(value.abandon.reason, R.string.delivery_action_unavailable), muted = true)
    }

    private fun changeReview(parent: LinearLayout, value: DeliveryScreen.ChangeReview) {
        heading(parent, R.string.delivery_change_review)
        val comparison = if (wide) style.row() else style.column()
        val before = style.column()
        val after = style.column()
        comparison.addView(before, if (wide) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2))
        comparison.addView(after, (if (wide) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2)).apply {
            if (wide) marginStart = style.dp(24) else topMargin = style.dp(16)
        })
        style.add(parent, comparison)
        heading(before, R.string.delivery_before)
        active(before, value.before, actions = false)
        heading(after, R.string.delivery_after)
        destination(after, value.after.destinationName, value.after.proposed.estimated.meters, value.after.proposed.estimated.source)
        val next = value.after.proposed.offers.singleOrNull { it.id == value.offerId }
        if (next == null) note(after, R.string.delivery_offer_missing)
        else offerCard(after, next, value.after.vehicleNames[next.vehicleId])
        info(parent, present(value.rarityNotice, R.string.delivery_rarity_missing))
        val difference = value.difference
        if (difference == null) note(parent, R.string.delivery_difference_missing)
        else money(parent, if (difference.direction == MoneyDirection.CREDIT) R.string.delivery_refund_planned else R.string.delivery_additional_payment,
            difference.amount.units)
        note(parent, R.string.delivery_difference_note)
        info(parent, present(value.after.confirmationTerms, R.string.delivery_terms_missing), muted = true)
        if (!value.before.change.allowed) info(parent, present(value.before.change.reason, R.string.delivery_change_unavailable), muted = true)
        if (!value.permission.allowed) info(parent, present(value.permission.reason, R.string.delivery_action_unavailable), muted = true)
        action(parent, R.string.delivery_change_confirm, "delivery:change",
            DeliveryCommand.Change(value.before.active, value.after.proposed, value.offerId), primary = true)
        action(parent, R.string.delivery_exit_review, "delivery:exit-review", DeliveryCommand.ExitReview)
    }

    private fun abandonReview(parent: LinearLayout, value: DeliveryScreen.AbandonReview) {
        heading(parent, R.string.delivery_abandon_review)
        active(parent, value.delivery, actions = false)
        if (value.fee == null) note(parent, R.string.delivery_fee_missing)
        else money(parent, R.string.delivery_abandon_fee, value.fee.units)
        if (value.refund == null) note(parent, R.string.delivery_refund_missing)
        else money(parent, R.string.delivery_refund_planned, value.refund.units)
        info(parent, present(value.consequence, R.string.delivery_consequence_missing))
        if (!value.delivery.abandon.allowed) info(parent, present(value.delivery.abandon.reason, R.string.delivery_action_unavailable), muted = true)
        if (!value.permission.allowed) info(parent, present(value.permission.reason, R.string.delivery_action_unavailable), muted = true)
        action(parent, R.string.delivery_abandon_confirm, "delivery:abandon", DeliveryCommand.Abandon(value.delivery.active), primary = true)
        action(parent, R.string.delivery_exit_review, "delivery:exit-review", DeliveryCommand.ExitReview)
    }

    private fun destination(parent: LinearLayout, name: String?, meters: Long, source: InputSource) {
        info(parent, present(name, R.string.delivery_destination_missing))
        info(parent, context.getString(R.string.delivery_estimated, meters))
        info(parent, s(if (source == InputSource.SIMULATION) R.string.delivery_simulation else R.string.delivery_vehicle_source), muted = true)
    }

    private fun offerCard(parent: LinearLayout, offer: DeliveryOffer, name: String?, detailed: Boolean = false, active: Boolean = false) = with(style) {
        add(parent, card(detailed).apply {
            addView(text(s(if (offer is DeliveryOffer.Income) R.string.delivery_income else R.string.delivery_purchase), color = muted))
            addView(text(present(name, R.string.delivery_vehicle_missing), if (detailed) 30 else 24, bold = true))
            when (offer) {
                is DeliveryOffer.Income -> {
                    addView(text(context.getString(R.string.delivery_money, s(R.string.delivery_completion_reward), offer.reward.units), 24))
                    addView(text(s(R.string.delivery_income_note), color = muted))
                }
                is DeliveryOffer.Purchase -> {
                    addView(text(rarity(offer.rarity), color = rarityColor(offer.rarity), bold = true))
                    addView(text(context.getString(R.string.delivery_money,
                        s(if (active) R.string.delivery_selected_purchase_amount else R.string.delivery_purchase_payment), offer.price.units), 24))
                    addView(text(s(if (active) R.string.delivery_active_purchase_note else R.string.delivery_purchase_note), color = muted))
                }
            }
        })
    }

    private fun money(parent: LinearLayout, label: Int, amount: Long) {
        info(parent, context.getString(R.string.delivery_money, s(label), amount))
    }
    private fun heading(parent: LinearLayout, label: Int) { style.add(parent, style.text(s(label), 28, bold = true), 20) }
    private fun note(parent: LinearLayout, label: Int) { info(parent, s(label), muted = true) }
    private fun info(parent: LinearLayout, value: String, muted: Boolean = false) {
        style.add(parent, style.text(value, color = if (muted) style.muted else style.ink), 4)
    }
    private fun s(id: Int) = context.getString(id)
    private fun present(value: String?, fallback: Int) = value?.takeIf { it.isNotBlank() } ?: s(fallback)

    private fun action(parent: LinearLayout, label: Int, tag: String, command: DeliveryCommand, primary: Boolean = false) {
        val generation = drawGeneration
        style.add(parent, style.button(s(label), session.canRequest(command), primary) {
            if (generation == drawGeneration) send(command)
        }.apply { this.tag = tag })
    }

    private fun localAction(parent: LinearLayout, label: Int, tag: String, enabled: Boolean = true, action: () -> Unit) {
        val generation = drawGeneration
        style.add(parent, style.button(s(label), enabled && session.controlsEnabled && session.pending == null) {
            if (generation == drawGeneration && session.controlsEnabled && session.pending == null) action()
        }.apply { this.tag = tag })
    }

    private fun send(command: DeliveryCommand): Boolean {
        val request = session.request(command) ?: return false
        drawScreen()
        onRequest(request)
        return true
    }

    private fun closeDetail() {
        selectedOfferId = null
        selectedFrameVersion = null
        resetScroll()
        drawScreen()
    }
    private fun resetScroll() { savedY = 0; scroll = null }
}
