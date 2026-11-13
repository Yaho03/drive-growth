package io.drivegrowth.app.ui.delivery

import io.drivegrowth.app.ui.pixel.PlayerText
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.host.delivery.DeliveryHostRead
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
    private val canConfirmOffer: () -> Boolean = { true },
    private val onPresentationCue: (DeliveryPresentationCue) -> Unit = {},
) : LinearLayout(context) {
    private val style = DeliveryStyle(context)
    private val vehicleLabels = GarageStyle(context)
    private val sprites = VehicleSprites(context)
    private val session = DeliveryInteraction(restored)
    private val presentation = DeliveryPresentation(restored.presentationKeys)
    private val artwork = DeliveryArtwork(context, sprites)
    private val motion = DeliveryMotionView(context, artwork, { session.controlsEnabled }) { key, cue ->
        if (session.controlsEnabled && presentation.scene?.key == key) onPresentationCue(cue)
    }
    private var readVersion: Long? = null
    private var presentationRead: DeliveryHostRead? = null
    private var arrivalPresentation: DeliveryArrivalPresentation? = null
    private var selectedOfferId = restored.selectedOfferId
    private var selectedFrameVersion = restored.selectedFrameVersion
    private var drawGeneration = 0L
    private var scroll: ScrollView? = null
    private var savedY = 0
    private var drawnVersion: Long? = null
    private var drawnScene: DeliverySceneKey? = null
    private var wide = false

    init { orientation = VERTICAL; setBackgroundColor(style.base) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        wide = w >= style.dp(760)
        post { drawScreen() }
    }

    fun render(frame: DeliveryFrame, controlsEnabled: Boolean): Boolean =
        render(frame, controlsEnabled, null)

    /** 호스트 render가 받은 같은 프레임의 원결과를 전달한다. 일반 다시 그리기는 기존 결과를 재사용한다. */
    fun render(
        frame: DeliveryFrame,
        controlsEnabled: Boolean,
        read: DeliveryHostRead?,
        arrival: DeliveryArrivalPresentation? = null,
    ): Boolean {
        val accepted = session.render(frame, controlsEnabled)
        if (accepted && (readVersion != frame.version || read != null)) {
            readVersion = frame.version
            presentationRead = read?.takeIf { it.screen == frame.screen }
            arrivalPresentation = arrival.takeIf { presentationRead != null }
        }
        if (accepted || !session.controlsEnabled) {
            session.frame?.let {
                val matchingRead = readVersion == it.version
                presentation.accept(it, presentationRead.takeIf { matchingRead }, session.controlsEnabled,
                    arrivalPresentation.takeIf { matchingRead })
            }
        }
        validateSelection()
        drawScreen()
        return accepted
    }

    fun complete(request: DeliveryRequest, frame: DeliveryFrame): Boolean {
        val accepted = session.complete(request, frame)
        if (accepted) presentation.accept(frame, null, session.controlsEnabled)
        validateSelection()
        drawScreen()
        return accepted
    }

    fun saveUiState() = session.save().copy(
        selectedOfferId = selectedOfferId, selectedFrameVersion = selectedFrameVersion, presentationKeys = presentation.save(),
    )

    /** 상세 닫기는 로컬 선택만 닫는다. 검토 이탈은 거래 취소와 다른 요청이다. */
    fun handleBack(): Boolean {
        if (!session.controlsEnabled || session.pending != null) return false
        if (presentation.scene != null) {
            dismissScene()
            return true
        }
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
        presentation.dismiss()
        presentationRead = null
        arrivalPresentation = null
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
        val scene = presentation.scene
        val sameFrame = version == drawnVersion && scene?.key == drawnScene
        if (sameFrame) scroll?.takeIf { it.isLaidOut }?.let { savedY = it.scrollY }
        else savedY = 0
        val focused = if (sameFrame) findFocus()?.tag else null
        drawnVersion = version
        drawnScene = scene?.key
        drawGeneration++
        (motion.parent as? LinearLayout)?.removeView(motion)
        removeAllViews()
        scroll = null
        val horizontalPadding = dp(if (wide) 22 else 14)
        setPadding(horizontalPadding, dp(10), horizontalPadding, dp(10))
        if (!session.controlsEnabled) {
            motion.clear()
            addView(text(s(R.string.delivery_restricted), 28, bold = true))
            addView(text(s(R.string.delivery_restricted_body), color = muted))
            return@with
        }
        val header = scene?.let(::sceneHeader) ?: text(s(R.string.delivery_title), 26, bold = true)
        addView(header)
        val body = column()
        scroll = style.scroll(body, savedY)
        addView(scroll, LayoutParams(-1, 0, 1f))
        if (scene != null) {
            add(body, scenePanel(scene, header), gap = 6)
            if (focused != null) findViewWithTag<View>(focused)?.takeIf { it.isEnabled }?.requestFocus()
            return@with
        }
        if (session.pending != null) add(body, panel(fill = PixelStyle.inset).apply {
            note(this, R.string.delivery_pending)
        })
        val state = session.frame?.screen
        val highway = (state as? DeliveryScreen.Active)?.let { active ->
            presentation.highway?.let { highwayPanel(it, active.delivery, header) }
        }
        if (highway != null) body.addView(highway, LayoutParams(-1, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
        })
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
        if (state is DeliveryScreen.Offers && canConfirmOffer()) addNavigationControls(body)
        if (motion.parent == null) motion.clear()
        if (focused != null) findViewWithTag<View>(focused)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun sceneHeader(scene: DeliveryScene): View = with(style) {
        panel(padding = 8).apply {
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(column().apply {
                    val title = when (scene.kind) {
                        DeliverySceneKind.DEPARTURE -> R.string.delivery_scene_departure
                        DeliverySceneKind.INCOME_ARRIVAL -> R.string.delivery_scene_income_arrival
                        DeliverySceneKind.VEHICLE_ARRIVAL -> R.string.delivery_scene_vehicle_arrival
                        DeliverySceneKind.HELD -> R.string.delivery_scene_held
                    }
                    addView(text(s(title), 18, bold = true))
                    scene.details.confirmedReward?.let { reward ->
                        addView(text(context.getString(R.string.delivery_money, s(R.string.delivery_scene_reward), reward.units),
                            18, color = PixelStyle.gold, bold = true).apply { tag = "delivery:confirmed-reward" })
                    }
                    val destination = present(scene.details.destinationName, R.string.delivery_destination_missing)
                    addView(text("${sourceLabel(scene.details)} · $destination", 14, color = muted).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }, LayoutParams(0, -2, 1f))
                val generation = drawGeneration
                addView(button(s(R.string.delivery_scene_skip), session.pending == null, primary = true) {
                    if (generation == drawGeneration && session.controlsEnabled && session.pending == null &&
                        presentation.scene?.key == scene.key) dismissScene()
                }.apply { tag = "delivery:skip-motion" }, LayoutParams(-2, -2).apply { marginStart = dp(8) })
            })
        }
    }

    private fun scenePanel(scene: DeliveryScene, header: View): View = with(style) {
        column().apply {
            tag = "delivery:scene:${scene.kind.name}"
            motion.showScene(scene)
            addMotion(this, availableMotionHeight(header))
            add(this, panel(padding = 8).apply {
                addView(text(present(scene.details.destinationName, R.string.delivery_destination_missing), 16, bold = true))
                info(this, present(scene.details.vehicleName, R.string.delivery_vehicle_missing))
                scene.details.rarity?.let {
                    add(this, text(vehicleLabels.rarity(it), 14, color = PixelStyle.rarityColor(it), bold = true), gap = 2)
                }
                info(this, estimatedDistance(scene.details), muted = true)
                if (scene.kind == DeliverySceneKind.HELD) note(this, R.string.delivery_scene_held_note)
                scene.details.expectedReward?.takeIf { scene.details.confirmedReward == null }?.let { reward ->
                    info(this, context.getString(R.string.delivery_money, s(R.string.delivery_completion_reward), reward.units))
                }
            }, gap = 6)
        }
    }

    private fun highwayPanel(road: DeliveryHighway, delivery: DeliveryActiveUi, header: View): View = with(style) {
        column().apply {
            tag = "delivery:highway"
            // HTML 정보 띠: "학교까지 · 12.4 / 18km" + 보상 칩, 아래에 우리 가게 → 목적지 진행 막대(확인된 거리만)
            val status = panel(padding = 14).apply {
                tag = "delivery:highway-status"
                val observed = road.observedMeters
                val estimated = road.details.estimatedMeters
                val destination = present(road.details.destinationName, R.string.delivery_destination_missing)
                addView(row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    val done = observed?.let { String.format("%.1f", it / 1000.0) } ?: "0"
                    val total = estimated?.let { PlayerText.km(it) } ?: "-"
                    addView(text("${destination}까지 · $done / $total", 22, bold = true).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    val reward = road.details.expectedReward?.let { "보상 +${java.text.NumberFormat.getIntegerInstance(java.util.Locale.KOREA).format(it.units)}" }
                    val chipText = if (road.details.purchase) s(R.string.delivery_purchase) else listOfNotNull(s(R.string.delivery_income), reward).joinToString(" · ")
                    addView(text(chipText, 15, color = if (road.details.purchase) PixelStyle.gold else 0xff7ee2a6.toInt(), bold = true).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(if (road.details.purchase) 0xff3d3216.toInt() else 0xff1f3a2c.toInt()); cornerRadius = dp(16).toFloat() }
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                    })
                })
                val progressPercent = if (observed != null && estimated != null && estimated > 0L)
                    ((observed.coerceIn(0L, estimated).toDouble() / estimated) * 100).toInt() else 0
                addView(row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(s(R.string.delivery_from_shop), 14, color = muted))
                    addView(vehicleLabels.progressTrack(progressPercent, color = 0xff68a0f0.toInt()),
                        LinearLayout.LayoutParams(0, dp(10), 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
                    addView(text(destination, 14, color = muted).apply { maxLines = 1 })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                val note = when {
                    observed == null -> s(R.string.delivery_wait_first_drive)
                    else -> delivery.progressLabel
                }
                if (note != null) addView(text("$note · ${sourceLabel(road.details)}", 13, color = muted))
            }
            addView(status, LayoutParams(motionWidth(), -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            motion.showHighway(road)
            addMotion(this, minOf(motionHeightLimit(), availableMotionHeight(header, status)))
        }
    }

    private fun sourceLabel(details: DeliverySceneDetails) =
        s(if (details.source == InputSource.SIMULATION) R.string.delivery_simulation else R.string.delivery_vehicle_source)

    private fun estimatedDistance(details: DeliverySceneDetails) = details.estimatedMeters
        ?.let { context.getString(R.string.delivery_estimated, PlayerText.km(it)) } ?: s(R.string.delivery_distance_missing)

    private fun availableMotionHeight(header: View, status: View? = null): Int {
        val availableWidth = width - paddingStart - paddingEnd
        if (availableWidth <= 0 || height <= 0) return motionHeightLimit()
        val flexibleHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        header.measure(View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.EXACTLY), flexibleHeight)
        status?.measure(View.MeasureSpec.makeMeasureSpec(motionWidth(), View.MeasureSpec.EXACTLY), flexibleHeight)
        val contentHeight = height - paddingTop - paddingBottom - header.measuredHeight - (status?.measuredHeight ?: 0)
        return (contentHeight - style.dp(6) * 2).coerceAtLeast(style.dp(48))
    }

    private fun motionWidth(heightLimit: Int = motionHeightLimit()): Int {
        val availableWidth = width - paddingStart - paddingEnd
        return if (availableWidth > 0) minOf(availableWidth, heightLimit * 16 / 9) else -1
    }

    private fun motionHeightLimit() = if (height > 0) (height / 2).coerceIn(style.dp(100), style.dp(380)) else style.dp(152)

    private fun addMotion(parent: LinearLayout, heightLimit: Int = motionHeightLimit()) = with(style) {
        val artWidth = motionWidth(heightLimit)
        val artHeight = if (artWidth > 0) artWidth * 9 / 16 else heightLimit
        parent.addView(motion, LayoutParams(artWidth, artHeight).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
        })
    }

    private fun dismissScene() {
        presentation.dismiss()
        motion.clear()
        drawScreen()
    }

    private fun addNavigationControls(parent: LinearLayout) {
        navigationControls()?.let { controls -> style.add(parent, style.panel().apply { addView(controls) }) }
    }

    private fun offers(host: LinearLayout, value: DeliveryProposalUi) {
        val selected = value.proposed.offers.singleOrNull { it.id == selectedOfferId }
        // HTML 탁송 화면: 넓은 창에서는 왼쪽에 목적지, 오른쪽에 의뢰 카드 격자
        val split = wide && selected == null && value.proposed.offers.isNotEmpty()
        val parent: LinearLayout
        if (split) {
            val columns = style.row().apply { isBaselineAligned = false }
            val left = style.column(); parent = style.column()
            columns.addView(left, LayoutParams(0, -2, 1f))
            columns.addView(parent, LayoutParams(0, -2, 2.6f).apply { marginStart = style.dp(PixelStyle.gapDp) })
            style.add(host, columns)
            destination(left, value.destinationName, value.proposed.estimated.meters, value.proposed.estimated.source)
        } else {
            parent = host
            destination(parent, value.destinationName, value.proposed.estimated.meters, value.proposed.estimated.source)
        }
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
        (if (split) listOf(null) else listOf(true, false)).forEach { income ->
            val group = value.proposed.offers.filter { income == null || (it is DeliveryOffer.Income) == income }
            if (group.isNotEmpty()) { if (income == null) heading(parent, R.string.delivery_offers_all) else heading(parent, if (income) R.string.delivery_income else R.string.delivery_purchase) }
            val perRow = if (split) 4 else if (wide) 2 else 1
            group.chunked(perRow).forEach { batch ->
                val row = style.row().apply { isBaselineAligned = false }
                batch.forEachIndexed { index, offer ->
                    val card = style.panel(padding = 12)
                    offerCard(card, offer, value.vehicleNames[offer.vehicleId], tile = true) {
                        val permission = value.availability[offer.id]
                        if (permission?.allowed != true) info(this, present(permission?.reason, R.string.delivery_action_unavailable), muted = true)
                        localAction(this, R.string.delivery_details, "delivery:offer:${offer.id.value}",
                            enabled = value.proposed.offers.count { it.id == offer.id } == 1) {
                            selectedOfferId = offer.id
                            selectedFrameVersion = session.frame?.version
                            resetScroll()
                            drawScreen()
                        }
                    }
                    row.addView(card, LayoutParams(0, if (wide) -1 else -2, 1f).apply { if (index != 0) marginStart = style.dp(PixelStyle.gapDp) })
                }
                repeat(if (wide) perRow - batch.size else 0) { row.addView(View(context), LayoutParams(0, 0, 1f).apply {
                    marginStart = style.dp(PixelStyle.gapDp)
                }) }
                style.add(parent, row)
            }
        }
    }

    private fun active(parent: LinearLayout, value: DeliveryActiveUi, actions: Boolean) {
        val summary = style.column()
        heading(summary, R.string.delivery_active)
        destination(summary, value.destinationName, value.active.proposed.estimated.meters, value.active.proposed.estimated.source)
        offerCard(summary, value.active.offer, value.vehicleName, detailed = actions, active = true)
        if (!actions) {
            style.add(summary, style.panel(fill = PixelStyle.inset).apply {
                info(this, context.getString(R.string.delivery_observed, present(value.observedDistanceLabel, R.string.delivery_information_missing)))
                info(this, present(value.progressLabel, R.string.delivery_progress_missing))
            })
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
            addView(text(context.getString(R.string.delivery_estimated, PlayerText.km(meters)), 16))
            addView(text(s(if (source == InputSource.SIMULATION) R.string.delivery_simulation else R.string.delivery_vehicle_source),
                14, color = PixelStyle.focus, bold = true))
        })
    }

    private fun offerCard(parent: LinearLayout, offer: DeliveryOffer, name: String?, detailed: Boolean = false,
        active: Boolean = false, tile: Boolean = false, footer: (LinearLayout.() -> Unit)? = null,
    ) = with(style) {
        // HTML 카드처럼: 차 이름 옆에 탁송 종류·희귀도 칩
        fun chip(label: String, fg: Int, fill: Int, stroke: Int? = null) = text(label, 14, color = fg, bold = true).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(fill); cornerRadius = dp(14).toFloat(); stroke?.let { setStroke(dp(2), it) }
            }
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        val content = column().apply {
            if (tile) addView(text(present(name, R.string.delivery_vehicle_missing), 19, bold = true).apply { maxLines = 1 })
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                if (!tile) addView(text(present(name, R.string.delivery_vehicle_missing), if (detailed) 23 else 19, bold = true).apply { setPadding(0, 0, dp(8), 0) })
                if (offer is DeliveryOffer.Purchase) addView(chip(vehicleLabels.rarity(offer.rarity), PixelStyle.rarityColor(offer.rarity), 0, PixelStyle.rarityColor(offer.rarity)),
                    LayoutParams(-2, -2).apply { marginEnd = dp(6) })
                addView(if (offer is DeliveryOffer.Income) chip(s(R.string.delivery_income), 0xff7ee2a6.toInt(), 0xff1f3a2c.toInt())
                    else chip(s(R.string.delivery_purchase), PixelStyle.gold, 0xff3d3216.toInt()))
            }, LayoutParams(-1, -2).apply { topMargin = dp(6) })
            when (offer) {
                is DeliveryOffer.Income -> {
                    if (tile) addView(amountRow(s(R.string.delivery_tile_reward), "+${java.text.NumberFormat.getIntegerInstance(java.util.Locale.KOREA).format(offer.reward.units)}"))
                    else addView(text(context.getString(R.string.delivery_money, s(R.string.delivery_completion_reward), offer.reward.units),
                        18, color = PixelStyle.gold, bold = true))
                    if (!tile) addView(text(s(R.string.delivery_income_note), 14, color = muted))
                }
                is DeliveryOffer.Purchase -> {
                    if (tile) addView(amountRow(s(R.string.delivery_tile_price), java.text.NumberFormat.getIntegerInstance(java.util.Locale.KOREA).format(offer.price.units)))
                    else addView(text(context.getString(R.string.delivery_money,
                        s(if (active) R.string.delivery_selected_purchase_amount else R.string.delivery_purchase_payment), offer.price.units),
                        18, color = PixelStyle.gold, bold = true))
                    if (!tile) addView(text(s(if (active) R.string.delivery_active_purchase_note else R.string.delivery_purchase_note), 14, color = muted))
                }
            }
        }
        val card = if (tile) parent else panel(padding = 12, border = if (detailed) PixelStyle.focus else PixelStyle.outline)
        val sprite = sprites.drawable(offer.vehicleId)
        if (sprite == null && !tile) {
            card.addView(content)
        } else {
            val scale = if (detailed || tile) 2 else 1
            val art = panel(padding = 6, fill = PixelStyle.inset).apply {
                gravity = Gravity.CENTER
                val image = ImageView(context).apply {
                    setImageDrawable(sprite)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                if (tile) addView(image, LayoutParams(-1, dp(82)))
                else if (sprite != null) addView(image, LayoutParams(sprite.intrinsicWidth * scale, sprite.intrinsicHeight * scale))
            }
            if (tile || detailed && !wide) {
                card.addView(art, LayoutParams(-1, -2))
                if (tile && wide) card.addView(content, LayoutParams(-1, -2, 1f).apply { topMargin = dp(8) })
                else add(card, content, gap = 8)
            } else {
                val line = row().apply { gravity = Gravity.CENTER_VERTICAL }
                line.addView(art, LayoutParams(-2, -2))
                line.addView(content, LayoutParams(0, -2, 1f).apply { marginStart = dp(PixelStyle.gapDp) })
                card.addView(line)
            }
        }
        footer?.invoke(card)
        if (!tile) add(parent, card)
    }

    private fun money(parent: LinearLayout, label: Int, amount: Long) = with(style) {
        add(parent, panel(padding = 12, fill = PixelStyle.inset).apply {
            addView(text(context.getString(R.string.delivery_money, s(label), amount), 20, color = PixelStyle.gold, bold = true))
        })
    }
    /** HTML 의뢰 카드의 "완료 보상 +220" / "가격 1,000" 줄 */
    private fun amountRow(label: String, amount: String) = with(style) {
        row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(label, 15, color = muted), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("● $amount", 19, color = PixelStyle.gold, bold = true))
        }
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
        fun canSubmit() = command !is DeliveryCommand.Select || canConfirmOffer()
        style.add(parent, style.button(s(label), canSubmit() && session.canRequest(command), primary) {
            if (generation == drawGeneration && canSubmit()) send(command)
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
