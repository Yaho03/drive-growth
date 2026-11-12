package io.drivegrowth.app.ui.delivery

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
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
    private val style = DeliveryStyle(context)
    private val vehicleLabels = GarageStyle(context)
    private val sprites = VehicleSprites(context)
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
        val horizontalPadding = dp(if (wide) 22 else 14)
        setPadding(horizontalPadding, dp(10), horizontalPadding, dp(10))
        if (!session.controlsEnabled) {
            addView(text(s(R.string.delivery_restricted), 28, bold = true))
            addView(text(s(R.string.delivery_restricted_body), color = muted))
            return@with
        }
        addView(text(s(R.string.delivery_title), 26, bold = true))
        val body = column()
        scroll = style.scroll(body, savedY)
        addView(scroll, LayoutParams(-1, 0, 1f))
        if (session.pending != null) add(body, panel(fill = PixelStyle.inset).apply {
            note(this, R.string.delivery_pending)
        })
        val state = session.frame?.screen
        hostStatus()?.let { status -> add(body, panel(border = PixelStyle.focus).apply { addView(status) }) }
        if (state !is DeliveryScreen.Offers) addNavigationControls(body)
        when (state) {
            null, DeliveryScreen.Loading -> add(body, panel().apply { note(this, R.string.delivery_loading) })
            is DeliveryScreen.Unsupported -> add(body, panel().apply {
                heading(this, R.string.delivery_unavailable)
                info(this, present(state.message, R.string.delivery_information_missing))
            })
            is DeliveryScreen.Failure -> add(body, panel().apply {
                heading(this, R.string.delivery_failure)
                info(this, present(state.message, R.string.delivery_information_missing))
                if (state.retryAllowed) action(this, R.string.delivery_retry, "delivery:refresh", DeliveryCommand.Refresh)
            })
            is DeliveryScreen.Destination -> add(body, panel().apply {
                heading(this, R.string.delivery_destination_title)
                info(this, present(state.name, R.string.delivery_destination_missing))
                info(this, present(state.estimatedDistanceLabel, R.string.delivery_distance_missing))
                info(this, present(state.sourceLabel, R.string.delivery_source_missing), muted = true)
                action(this, R.string.delivery_choose_destination, "delivery:destination", DeliveryCommand.ChooseDestination(null), primary = true)
                if (!state.chooseAllowed) info(this, present(state.reason, R.string.delivery_action_unavailable), muted = true)
            })
            is DeliveryScreen.Offers -> offers(body, state.proposal)
            is DeliveryScreen.Active -> active(body, state.delivery, actions = true)
            is DeliveryScreen.ChangeReview -> changeReview(body, state)
            is DeliveryScreen.AbandonReview -> abandonReview(body, state)
            is DeliveryScreen.AwaitingResult -> {
                add(body, panel(fill = PixelStyle.inset).apply {
                    heading(this, R.string.delivery_awaiting)
                    info(this, present(state.message, R.string.delivery_information_missing))
                    note(this, R.string.delivery_awaiting_note)
                })
                active(body, state.delivery, actions = false)
            }
        }
        if (state is DeliveryScreen.Offers) addNavigationControls(body)
        if (focused != null) findViewWithTag<View>(focused)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun addNavigationControls(parent: LinearLayout) {
        navigationControls()?.let { controls -> style.add(parent, style.panel().apply { addView(controls) }) }
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
        val summary = style.column()
        heading(summary, R.string.delivery_active)
        destination(summary, value.destinationName, value.active.proposed.estimated.meters, value.active.proposed.estimated.source)
        offerCard(summary, value.active.offer, value.vehicleName, detailed = actions, active = true)
        style.add(summary, style.panel(fill = PixelStyle.inset).apply {
            info(this, context.getString(R.string.delivery_observed, present(value.observedDistanceLabel, R.string.delivery_information_missing)))
            info(this, present(value.progressLabel, R.string.delivery_progress_missing))
        })
        if (!actions) {
            style.add(parent, summary, gap = 0)
            return
        }
        val controls = style.panel()
        heading(controls, R.string.delivery_change_opportunity)
        info(controls, present(value.change.reason, if (value.change.allowed) R.string.delivery_change_available else R.string.delivery_change_unavailable), muted = true)
        action(controls, R.string.delivery_change_destination, "delivery:destination", DeliveryCommand.ChooseDestination(value.active), primary = true)
        action(controls, R.string.delivery_review_abandon, "delivery:review-abandon", DeliveryCommand.ReviewAbandon(value.active))
        if (!value.abandon.allowed) info(controls, present(value.abandon.reason, R.string.delivery_action_unavailable), muted = true)
        pair(parent, summary, controls)
    }

    private fun changeReview(parent: LinearLayout, value: DeliveryScreen.ChangeReview) {
        heading(parent, R.string.delivery_change_review)
        val before = style.panel(padding = 12)
        val after = style.panel(padding = 12, border = PixelStyle.focus)
        heading(before, R.string.delivery_before)
        active(before, value.before, actions = false)
        heading(after, R.string.delivery_after)
        destination(after, value.after.destinationName, value.after.proposed.estimated.meters, value.after.proposed.estimated.source)
        val next = value.after.proposed.offers.singleOrNull { it.id == value.offerId }
        if (next == null) note(after, R.string.delivery_offer_missing)
        else offerCard(after, next, value.after.vehicleNames[next.vehicleId])
        pair(parent, before, after)
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

    private fun pair(parent: LinearLayout, before: View, after: View) = with(style) {
        val pair = if (wide) row() else column()
        pair.addView(before, if (wide) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2))
        pair.addView(after, (if (wide) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2)).apply {
            if (wide) marginStart = dp(PixelStyle.gapDp) else topMargin = dp(PixelStyle.gapDp)
        })
        add(parent, pair)
    }

    private fun destination(parent: LinearLayout, name: String?, meters: Long, source: InputSource) = with(style) {
        add(parent, panel(padding = 12).apply {
            addView(text(present(name, R.string.delivery_destination_missing), 18, bold = true))
            addView(text(context.getString(R.string.delivery_estimated, meters), 16))
            addView(text(s(if (source == InputSource.SIMULATION) R.string.delivery_simulation else R.string.delivery_vehicle_source),
                14, color = PixelStyle.focus, bold = true))
        })
    }

    private fun offerCard(parent: LinearLayout, offer: DeliveryOffer, name: String?, detailed: Boolean = false, active: Boolean = false) = with(style) {
        val content = column().apply {
            addView(text(s(if (offer is DeliveryOffer.Income) R.string.delivery_income else R.string.delivery_purchase),
                14, color = PixelStyle.focus, bold = true))
            addView(text(present(name, R.string.delivery_vehicle_missing), if (detailed) 23 else 19, bold = true))
            when (offer) {
                is DeliveryOffer.Income -> {
                    addView(text(context.getString(R.string.delivery_money, s(R.string.delivery_completion_reward), offer.reward.units),
                        18, color = PixelStyle.gold, bold = true))
                    addView(text(s(R.string.delivery_income_note), 14, color = muted))
                }
                is DeliveryOffer.Purchase -> {
                    addView(text(vehicleLabels.rarity(offer.rarity), 14, color = PixelStyle.rarityColor(offer.rarity), bold = true))
                    addView(text(context.getString(R.string.delivery_money,
                        s(if (active) R.string.delivery_selected_purchase_amount else R.string.delivery_purchase_payment), offer.price.units),
                        18, color = PixelStyle.gold, bold = true))
                    addView(text(s(if (active) R.string.delivery_active_purchase_note else R.string.delivery_purchase_note), 14, color = muted))
                }
            }
        }
        val card = panel(padding = 12, border = if (detailed) PixelStyle.focus else PixelStyle.outline)
        val sprite = sprites.drawable(offer.vehicleId)
        if (sprite == null) {
            card.addView(content)
        } else {
            val scale = if (detailed) 2 else 1
            val art = panel(padding = 6, fill = PixelStyle.inset).apply {
                gravity = Gravity.CENTER
                val image = ImageView(context).apply {
                    setImageDrawable(sprite)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                addView(image, LayoutParams(sprite.intrinsicWidth * scale, sprite.intrinsicHeight * scale))
            }
            if (detailed && !wide) {
                card.addView(art, LayoutParams(-1, -2))
                add(card, content, gap = 8)
            } else {
                val line = row().apply { gravity = Gravity.CENTER_VERTICAL }
                line.addView(art, LayoutParams(-2, -2))
                line.addView(content, LayoutParams(0, -2, 1f).apply { marginStart = dp(PixelStyle.gapDp) })
                card.addView(line)
            }
        }
        add(parent, card)
    }

    private fun money(parent: LinearLayout, label: Int, amount: Long) = with(style) {
        add(parent, panel(padding = 12, fill = PixelStyle.inset).apply {
            addView(text(context.getString(R.string.delivery_money, s(label), amount), 20, color = PixelStyle.gold, bold = true))
        })
    }
    private fun heading(parent: LinearLayout, label: Int) { style.add(parent, style.text(s(label), 21, bold = true), 4) }
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
