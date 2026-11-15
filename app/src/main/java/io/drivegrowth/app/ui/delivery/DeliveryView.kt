package io.drivegrowth.app.ui.delivery

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import io.drivegrowth.app.ui.presentation.ReferenceSceneArt
import io.drivegrowth.app.ui.presentation.ReferenceSceneFrame
import io.drivegrowth.app.ui.presentation.ReferenceSceneTypography
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.pixel.CleanUi
import io.drivegrowth.app.host.delivery.DeliveryHostRead
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.pixel.PixelStyle
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.core.contract.InputSource
import io.drivegrowth.core.contract.MoneyDirection
import io.drivegrowth.core.delivery.DeliveryOffer
import io.drivegrowth.core.delivery.DeliveryOfferId
import java.math.BigDecimal

internal fun deliveryDistance(meters: Long) =
    if (meters < 1_000L) "$meters m" else "${BigDecimal.valueOf(meters, 3).stripTrailingZeros().toPlainString()} km"

/** 전달받은 탁송 상태를 표시한다. 목적지 조회·경제 계산·저장·도착 판정은 호스트 책임이다. */
class DeliveryView(
    context: Context,
    private val onRequest: (DeliveryRequest) -> Unit,
    restored: DeliveryInteractionState = DeliveryInteractionState(),
    private val navigationControls: () -> View? = { null },
    private val simulationDestinationControls: (Boolean) -> View? = { null },
    private val hostStatus: () -> View? = { null },
    private val canConfirmOffer: () -> Boolean = { true },
    private val onPresentationCue: (DeliveryPresentationCue) -> Unit = {},
    private val onPresentationCancelled: () -> Unit = {},
) : LinearLayout(context) {
    private val sceneArt = ReferenceSceneArt(context)
    private val sceneText = ReferenceSceneTypography(context)
    private val style = DeliveryStyle(context)
    private val sceneStyle = DeliveryStyle(context, scene = true)
    private val offerLabels = GarageStyle(context, pixelPanel = true, featureWindow = true)
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
    internal val hasActiveRoad: Boolean
        get() = session.controlsEnabled && presentation.scene == null &&
            session.frame?.screen is DeliveryScreen.Active && presentation.highway != null

    private val displayedPurchaseCards = mutableMapOf<DeliveryOfferId, View>()
    private var purchaseTab: Boolean? = null
    private var offerPage = 0
    internal var showHeading = true
    internal var onReturnToShop: (() -> Unit)? = null
    internal var onConfirmSceneResult: (() -> Unit)? = null
    internal val headingRes: Int
        get() = when (val state = session.frame?.screen) {
            is DeliveryScreen.ChangeReview -> R.string.delivery_change_review
            is DeliveryScreen.AbandonReview -> R.string.delivery_abandon_review
            is DeliveryScreen.Offers -> when (selectedOffer(state)) {
                is DeliveryOffer.Purchase -> R.string.delivery_purchase_review
                is DeliveryOffer.Income -> R.string.delivery_income_review
                null -> R.string.delivery_offers_title
            }
            is DeliveryScreen.Active -> R.string.delivery_active
            else -> R.string.delivery_title
        }

    private fun selectedOffer(state: DeliveryScreen.Offers) =
        state.proposal.proposed.offers.singleOrNull { it.id == selectedOfferId }

    internal fun displayedPurchaseIds(): Set<DeliveryOfferId> {
        val visible = android.graphics.Rect()
        return displayedPurchaseCards.asSequence().filter { (_, card) ->
            card.isShown && card.getGlobalVisibleRect(visible)
        }.mapTo(mutableSetOf()) { it.key }
    }

    internal val fillsAppStage: Boolean get() = presentation.scene != null && session.controlsEnabled

    init { orientation = VERTICAL; setBackgroundColor(style.base) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        wide = w >= style.dp(760) && resources.configuration.fontScale <= 1.3f
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
        displayedPurchaseCards.clear()
        (motion.parent as? ViewGroup)?.removeView(motion)
        removeAllViews()
        scroll = null
        setBackgroundColor(base)
        val horizontalPadding = dp(if (wide) 14 else 10)
        setPadding(horizontalPadding, dp(10), horizontalPadding, dp(10))
        if (!session.controlsEnabled) {
            motion.clear()
            addView(panel().apply {
                heading(this, R.string.delivery_restricted)
                note(this, R.string.delivery_restricted_body)
            })
            return@with
        }
        if (scene != null) {
            setBackgroundColor(style.base)
            setPadding(0, 0, 0, 0)
            addView(scenePanel(scene), LayoutParams(-1, 0, 1f))
            if (focused != null) findViewWithTag<View>(focused)?.takeIf { it.isEnabled }?.requestFocus()
            return@with
        }
        val state = session.frame?.screen
        val selected = (state as? DeliveryScreen.Offers)?.let(::selectedOffer)
        if (showHeading) addView(style.heading(s(headingRes)))
        val body = column()
        scroll = style.scroll(body, savedY)
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        if (session.pending != null) add(body, panel(fill = inset).apply {
            note(this, R.string.delivery_pending)
        })
        if (selected == null && state !is DeliveryScreen.Active) {
            hostStatus()?.let { status -> add(body, panel(selected = true).apply { addView(status) }) }
        }
        when (state) {
            null, DeliveryScreen.Loading -> add(body, panel().apply { note(this, R.string.delivery_loading) })
            is DeliveryScreen.Unsupported -> add(body, panel().apply {
                heading(this, R.string.delivery_unavailable)
                info(this, present(state.message, R.string.delivery_information_missing))
            })
            is DeliveryScreen.Failure -> add(body, panel().apply {
                heading(this, R.string.delivery_failure)
                info(this, present(state.message, R.string.delivery_information_missing))
            }).also {
                val buttons = reviewActions()
                addShopReturn(buttons)
                if (state.retryAllowed) action(buttons, R.string.delivery_retry, "delivery:refresh",
                    DeliveryCommand.Refresh, primary = true, inline = true)
            }
            is DeliveryScreen.Destination -> {
                val simulationControls = simulationDestinationControls(!state.chooseAllowed)
                if (simulationControls == null) {
                    showDestination(body, state)
                } else {
                    add(body, panel().apply {
                        heading(this, R.string.delivery_destination_title)
                        if (session.pending == null) addView(simulationControls)
                    })
                }
            }
            is DeliveryScreen.Offers -> {
                if (selected == null) offers(body, state.proposal)
                else {
                    offerReview(body, state.proposal, selected)
                    hostStatus()?.let { status -> add(body, panel(selected = true).apply { addView(status) }) }
                }
            }
            is DeliveryScreen.Active -> {
                val road = presentation.highway
                if (road != null) highwayPanel(body, road, state.delivery)
                hostStatus()?.let { status -> add(body, panel(selected = true).apply { addView(status) }) }
                active(body, state.delivery, showDetails = road == null)
                activeActions(state.delivery)
            }
            is DeliveryScreen.ChangeReview -> changeReview(body, state)
            is DeliveryScreen.AbandonReview -> abandonReview(body, state)
            is DeliveryScreen.AwaitingResult -> {
                val simulationControls = simulationDestinationControls(true)
                add(body, panel(fill = inset).apply {
                    heading(this, R.string.delivery_awaiting)
                    if (simulationControls == null) {
                        info(this, present(state.message, R.string.delivery_information_missing))
                    }
                    note(this, R.string.delivery_awaiting_note)
                    if (session.pending == null && simulationControls != null) addView(simulationControls)
                })
                active(body, state.delivery)
                if (simulationControls == null) addNavigationControls()
            }
        }
        if (motion.parent == null) motion.clear()
        if (focused != null) findViewWithTag<View>(focused)?.takeIf { it.isEnabled }?.requestFocus()
    }

    private fun scenePanel(scene: DeliveryScene): View = with(sceneStyle) {
        val generation = drawGeneration
        val departure = scene.kind == DeliverySceneKind.DEPARTURE
        val frame = ReferenceSceneFrame(context)
        val overlay = FrameLayout(context).apply {
            tag = "delivery:scene:${scene.kind.name}"
            addView(frame, FrameLayout.LayoutParams(-1, -1))
        }
        motion.showScene(scene)
        frame.place(motion, 0, 0, 1360, 948)
        val heldCount = if (scene.kind == DeliverySceneKind.HELD) {
            text("0", 20, 0xffffd36b.toInt(), bold = true).referenceSize(20f)
        } else null
        val summary = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = insetSurface()
            setPadding(18, 0, 18, 0)
            if (heldCount != null) {
                addView(sceneArt.view(sceneArt.box), LayoutParams(45, 39).apply { marginEnd = 10 })
                addView(text("임시 보관함", 20, bold = true).referenceSize(20f))
                addView(heldCount, LayoutParams(-2, -2).apply { marginStart = 10 })
            } else {
                val type = s(if (scene.details.purchase) R.string.delivery_purchase else R.string.delivery_income)
                val name = if (departure) scene.details.vehicleName else scene.details.destinationName
                val purchase = scene.details.purchase
                val chipFill = if (purchase) 0xff3d3216.toInt() else CleanUi.SEA_DEEP
                val chipInk = if (purchase) CleanUi.SUN else CleanUi.SEA_LIGHT
                addView(text(type, 17, chipInk, bold = true).apply {
                    background = offerLabels.shape(chipFill)
                    gravity = Gravity.CENTER
                    setPadding(12, 0, 12, 0)
                }.referenceSize(17f), LayoutParams(-2, 30).apply { marginEnd = 10 })
                addView(text(name.orEmpty(), 20, bold = true).apply {
                    maxWidth = 320
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }.referenceSize(20f))
            }
        }
        frame.place(summary, 28, 28, -2, 48)
        val close = {
            if (generation == drawGeneration && session.controlsEnabled && session.pending == null &&
                presentation.scene?.key == scene.key) dismissScene()
        }
        val skip = button("건너뛰기 ▶▶", session.pending == null, onClick = close).apply {
            tag = "delivery:skip-motion"
        }
        overlay.addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(8)
            bottomMargin = dp(8)
        })
        val confirm = button(s(R.string.delivery_scene_confirm), session.pending == null, primary = true) {
            if (generation == drawGeneration && session.controlsEnabled && session.pending == null &&
                presentation.scene?.key == scene.key) {
                dismissScene()
                onConfirmSceneResult?.invoke()
            }
        }
            .apply {
                tag = "delivery:confirm-scene"
            }
        overlay.addView(confirm, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(8), 0, dp(8), dp(8))
        })
        val confirmed = scene.details.confirmedReward?.let { reward ->
            val description = context.getString(R.string.delivery_money, s(R.string.delivery_scene_reward), reward.units)
            text("완료 보상 +${referenceAmount(reward.units)}", 20, 0xffffd36b.toInt(), bold = true).apply {
                gravity = Gravity.CENTER
                tag = "delivery:confirmed-reward"
                contentDescription = description
                background = insetSurface()
                setPadding(18, 0, 18, 0)
            }.referenceSize(28f, weight = 900).also { frame.place(it, 540, 222, 280, 48) }
        }
        val balance = scene.details.confirmedBalance?.let { amount ->
            val label = text(referenceAmount(amount.units), 28, ink, bold = true).apply {
                gravity = Gravity.CENTER
                tag = "delivery:confirmed-balance"
                contentDescription = context.getString(R.string.delivery_money, s(R.string.delivery_scene_balance), amount.units)
                background = insetSurface()
                setPadding(22, 0, 22, 0)
                setCompoundDrawables(sceneArt.coinDrawable(36), null, null, null)
                compoundDrawablePadding = 12
            }.referenceSize(28f, weight = 900)
            frame.place(label, 1120, 28, 212, 60)
            label
        }
        val balanceEnd = scene.details.confirmedBalance?.units
        val credit = scene.details.confirmedReward?.units
        val balanceStart = if (balanceEnd != null && credit != null && balanceEnd >= credit) balanceEnd - credit else null
        var displayedBalance: Long? = null
        motion.onSceneFrame = { time ->
            val end = if (departure) 2.3f else 1.8f
            confirm.visibility = if (time >= end) View.VISIBLE else View.INVISIBLE
            summary.alpha = ((time / .2f).coerceIn(0f, 1f)) *
                if (departure) 1f - ((time - 1.95f) / .15f).coerceIn(0f, 1f) else 1f
            skip.alpha = if (departure) 1f - ((time - 1.95f) / .15f).coerceIn(0f, 1f)
                else if (balance == null) 1f else 1f - ((time - 1.55f) / .18f).coerceIn(0f, 1f)
            if (time >= end) {
                skip.visibility = View.INVISIBLE
            }
            confirmed?.alpha = ((time - 1.3f) / .15f).coerceIn(0f, 1f)
            if (heldCount != null) {
                val placed = time >= 1.45f
                val shown = if (placed) "1" else "0"
                if (heldCount.text.toString() != shown) heldCount.text = shown
                val bump = 1f + .18f * kotlin.math.sin(kotlin.math.PI.toFloat() *
                    ((time - 1.45f) / .15f).coerceIn(0f, 1f))
                summary.scaleX = bump
                summary.scaleY = bump
            }
            if (balance != null && balanceEnd != null) {
                val portion = ((time - 1.7f) / .25f).coerceIn(0f, 1f)
                val shown = if (balanceStart == null || credit == null || portion >= 1f) balanceEnd
                    else (balanceStart + (credit.toDouble() * portion).toLong()).coerceAtMost(balanceEnd)
                if (shown != displayedBalance) {
                    displayedBalance = shown
                    balance.text = referenceAmount(shown)
                }
                val lastCoin = 1.7f + ((time - 1.7f) / .05f).toInt().coerceIn(0, 5) * .05f
                val bump = if (time < 1.7f) 0f else .12f * (1f - ((time - lastCoin) / .14f).coerceIn(0f, 1f))
                balance.scaleX = 1f + bump
                balance.scaleY = 1f + bump
            }
        }
        motion.onSceneFrame?.invoke(0f)
        overlay
    }

    private fun highwayPanel(parent: LinearLayout, road: DeliveryHighway, delivery: DeliveryActiveUi) = with(style) {
        val destination = present(road.details.destinationName, R.string.delivery_destination_missing)
        val observed = road.observedMeters
        val estimated = road.details.estimatedMeters
        val distance = context.getString(R.string.delivery_observed,
            observed?.let(::deliveryDistance) ?: s(R.string.delivery_information_missing))
        val percentage = if (observed != null && estimated != null && estimated > 0L)
            ((observed.coerceIn(0L, estimated).toDouble() / estimated) * 100).toInt() else null
        val status = panel(padding = 8).apply {
            tag = "delivery:highway-status"
            addView(chip(s(R.string.delivery_active), inset, accent), LayoutParams(-2, -2))
            addView(text(destination, 22, bold = true))
            val routeFacts = if (wide) row() else column()
            for (fact in listOf(estimatedDistance(road.details), distance)) {
                routeFacts.addView(text(fact), if (wide) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2))
            }
            addView(routeFacts)
            val source = s(if (road.details.source == InputSource.SIMULATION) R.string.delivery_source_simulation
                else R.string.delivery_source_vehicle)
            addView(text(source, 16, color = muted))
            addView(text(present(delivery.progressLabel, R.string.delivery_progress_missing), color = muted))
            if (percentage != null) {
                val track = offerLabels.progressTrack(percentage)
                val marker = sceneArt.view(sceneArt.truck).apply {
                    tag = "delivery:progress-truck"
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                addView(object : android.widget.FrameLayout(context) {
                    init {
                        addView(track, LayoutParams(-1, dp(12), Gravity.BOTTOM))
                        addView(marker, LayoutParams(dp(46), dp(25)))
                    }
                    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                        super.onLayout(changed, left, top, right, bottom)
                        marker.translationX = (width - marker.width).coerceAtLeast(0) * (percentage / 100f)
                    }
                }, LinearLayout.LayoutParams(-1, dp(28)))
                addView(text(s(R.string.delivery_highway_distance_note), 16, color = muted))
            }
        }
        motion.onSceneFrame = null
        motion.showHighway(road)
        val roadHeight = if (height > 0) (height * (if (wide) .30f else .23f)).toInt().coerceIn(dp(72), dp(216)) else dp(144)
        val roadPanel = panel(padding = 5).apply {
            tag = "delivery:highway"
            addView(motion, LinearLayout.LayoutParams(-1, roadHeight))
        }
        if (wide) pair(parent, roadPanel, status)
        else {
            add(parent, status, gap = 0)
            add(parent, roadPanel, gap = 8)
        }
    }

    private fun <T : TextView> T.referenceSize(size: Float, weight: Int = 700): T =
        sceneText.apply(this, size, when (weight) {
            500 -> sceneText.notoMedium
            900 -> sceneText.notoBlack
            else -> sceneText.notoBold
        })

    private fun referenceAmount(units: Long) = java.text.NumberFormat.getIntegerInstance(java.util.Locale.KOREA).format(units)

    private fun estimatedDistance(details: DeliverySceneDetails) = details.estimatedMeters
        ?.let { context.getString(R.string.delivery_estimated, deliveryDistance(it)) } ?: s(R.string.delivery_distance_missing)

    private fun dismissScene() {
        onPresentationCancelled()
        presentation.dismiss()
        motion.clear()
        drawScreen()
    }

    private fun showDestination(body: LinearLayout, state: DeliveryScreen.Destination) = with(style) {
        add(body, panel().apply {
            heading(this, R.string.delivery_destination_title)
            val name = state.name?.takeIf { it.isNotBlank() }
            if (name == null) info(this, s(R.string.delivery_destination_missing))
            else {
                info(this, name)
                info(this, present(state.estimatedDistanceLabel, R.string.delivery_distance_missing))
            }
            val source = state.sourceLabel
            if (source != "모의 입력" && source != "차량 입력 프로필 · 실제 내비 증거는 별도 확인") {
                info(this, present(source, R.string.delivery_source_missing), muted = true)
            }
            if (!state.chooseAllowed) info(this, present(state.reason, R.string.delivery_action_unavailable), muted = true)
        }).also {
            addNavigationControls()
            val buttons = reviewActions()
            action(buttons, R.string.delivery_choose_destination, "delivery:destination",
                DeliveryCommand.ChooseDestination(null), inline = true)
        }
    }

    private fun addNavigationControls() {
        if (session.pending == null) navigationControls()?.let { controls ->
            addView(controls, LayoutParams(-1, -2).apply { topMargin = style.dp(8) })
        }
    }

    private fun offers(parent: LinearLayout, value: DeliveryProposalUi) = with(style) {
        val offers = value.proposed.offers
        val purchases = purchaseTab ?: offers.none { it is DeliveryOffer.Income }
        val candidates = offers.filter { (it is DeliveryOffer.Purchase) == purchases }
        val count = if (wide && width >= dp(1120)) 3 else if (wide) 2 else 1
        val maxPage = (candidates.size - 1).coerceAtLeast(0) / count
        offerPage = offerPage.coerceIn(0, maxPage)

        val tabs = row()
        fun tab(isPurchase: Boolean, label: Int, tag: String) {
            val generation = drawGeneration
            addAction(tabs, true, button(s(label), session.pending == null, primary = isPurchase == purchases) {
                if (generation == drawGeneration && session.controlsEnabled && session.pending == null) {
                    purchaseTab = isPurchase
                    offerPage = 0
                    resetScroll()
                    drawScreen()
                }
            }.apply { this.tag = tag; isSelected = isPurchase == purchases })
        }
        tab(false, R.string.delivery_income, "delivery:tab:income")
        tab(true, R.string.delivery_purchase, "delivery:tab:purchase")
        add(parent, tabs, gap = 0)
        add(parent, destinationFacts(value.destinationName, value.proposed.estimated.meters))
        val proposals = column()
        if (candidates.isEmpty()) {
            heading(proposals, R.string.delivery_empty)
            if (offers.isEmpty()) note(proposals, R.string.delivery_empty_body)
        } else {
            val batch = candidates.drop(offerPage * count).take(count)
            val tiles = row().apply { isBaselineAligned = false }
            batch.forEachIndexed { index, offer ->
                val card = panel(padding = 8, fill = inset).apply { tag = "delivery:offer-card:${offer.id.value}" }
                if (offer is DeliveryOffer.Purchase) displayedPurchaseCards[offer.id] = card
                offerCard(card, offer, value.vehicleNames[offer.vehicleId], tile = true) {
                    val permission = value.availability[offer.id]
                    if (permission?.allowed != true) info(this, present(permission?.reason,
                        R.string.delivery_action_unavailable), muted = true)
                    localAction(this, R.string.delivery_select_offer, "delivery:offer:${offer.id.value}",
                        enabled = offers.count { it.id == offer.id } == 1, primary = true) {
                        selectedOfferId = offer.id
                        selectedFrameVersion = session.frame?.version
                        resetScroll()
                        drawScreen()
                    }
                }
                tiles.addView(card, LayoutParams(0, -2, 1f).apply {
                    if (index != 0) marginStart = dp(10)
                })
            }
            repeat(count - batch.size) {
                tiles.addView(View(context), LayoutParams(0, 0, 1f).apply { marginStart = dp(10) })
            }
            proposals.addView(tiles)
        }
        add(parent, proposals, gap = 8)
        if (maxPage > 0) {
            val pages = reviewActions()
            fun page(label: Int, tag: String, destination: Int) {
                localAction(pages, label, tag, enabled = destination in 0..maxPage, inline = true) {
                    offerPage = destination
                    resetScroll()
                    drawScreen()
                }
            }
            page(R.string.delivery_previous_page, "delivery:offers:prev", offerPage - 1)
            pages.addView(text(context.getString(R.string.delivery_page, offerPage + 1, maxPage + 1)).apply {
                gravity = Gravity.CENTER
            }, LayoutParams(0, -1, .65f))
            page(R.string.delivery_next_page, "delivery:offers:next", offerPage + 1)
        }
    }

    private fun offerReview(parent: LinearLayout, value: DeliveryProposalUi, selected: DeliveryOffer) = with(style) {
        val vehicle = panel(padding = 8).apply {
            tag = "delivery:review-vehicle"
            offerCard(this, selected, value.vehicleNames[selected.vehicleId], tile = true, review = true)
        }
        val facts = panel(padding = 8).apply {
            tag = "delivery:review-facts"
            addView(text(present(value.destinationName, R.string.delivery_destination_missing), 21, bold = true))
            addView(text(context.getString(R.string.delivery_estimated, deliveryDistance(value.proposed.estimated.meters))))
            info(this, present(value.confirmationTerms, R.string.delivery_terms_missing), muted = true)
            if (selected is DeliveryOffer.Purchase && value.garageFull)
                addView(text("차고에 빈자리가 없으면 도착 후 임시 보관함에 보관돼요. 24시간 안에 수령해 주세요.", bold = true))
            val permission = value.availability[selected.id]
            if (permission?.allowed != true) {
                info(this, present(permission?.reason, R.string.delivery_action_unavailable), muted = true)
            } else if (!canConfirmOffer()) {
                note(this, R.string.delivery_saving_discovery)
            }
        }
        pair(parent, vehicle, facts)
        val buttons = reviewActions()
        localAction(buttons, R.string.delivery_close_detail, "delivery:back", inline = true) { closeDetail() }
        action(buttons, if (selected is DeliveryOffer.Purchase) R.string.delivery_confirm_purchase else R.string.delivery_confirm_income,
            "delivery:confirm", DeliveryCommand.Select(value.proposed, selected.id), primary = true, inline = true)
    }

    private fun reviewActions() = style.row().also { buttons ->
        addView(buttons, LayoutParams(-1, -2).apply { topMargin = style.dp(8) })
    }

    private fun addShopReturn(buttons: LinearLayout) {
        if (onReturnToShop != null) localAction(buttons, R.string.delivery_return_shop, "delivery:shop", inline = true) {
            onReturnToShop?.invoke()
        }
    }

    private fun active(parent: LinearLayout, value: DeliveryActiveUi, showDetails: Boolean = true) {
        val summary = style.panel()
        if (showDetails) {
            destination(summary, value.destinationName, value.active.proposed.estimated.meters)
        }
        offerCard(summary, value.active.offer, value.vehicleName, active = true)
        if (showDetails) {
            style.add(summary, style.panel(fill = style.inset).apply {
                info(this, context.getString(R.string.delivery_observed, present(value.observedDistanceLabel, R.string.delivery_information_missing)))
                info(this, present(value.progressLabel, R.string.delivery_progress_missing))
            })
        }
        if (!value.change.allowed) info(summary, present(value.change.reason, R.string.delivery_change_unavailable), muted = true)
        if (!value.abandon.allowed) info(summary, present(value.abandon.reason, R.string.delivery_action_unavailable), muted = true)
        style.add(parent, summary, gap = 0)
    }

    private fun activeActions(value: DeliveryActiveUi) {
        val buttons = reviewActions()
        addShopReturn(buttons)
        action(buttons, R.string.delivery_change_destination, "delivery:destination",
            DeliveryCommand.ChooseDestination(value.active), inline = true)
        action(buttons, R.string.delivery_review_abandon, "delivery:review-abandon",
            DeliveryCommand.ReviewAbandon(value.active), inline = true)
    }

    private fun changeReview(parent: LinearLayout, value: DeliveryScreen.ChangeReview) {
        val before = style.panel(padding = 10)
        val after = style.panel(padding = 10, selected = true)
        heading(before, R.string.delivery_before)
        destination(before, value.before.destinationName, value.before.active.proposed.estimated.meters)
        info(before, context.getString(R.string.delivery_observed,
            present(value.before.observedDistanceLabel, R.string.delivery_information_missing)))
        offerCard(before, value.before.active.offer, value.before.vehicleName, active = true)
        heading(after, R.string.delivery_after)
        destination(after, value.after.destinationName, value.after.proposed.estimated.meters)
        val next = value.after.proposed.offers.singleOrNull { it.id == value.offerId }
        if (next == null) note(after, R.string.delivery_offer_missing)
        else offerCard(after, next, value.after.vehicleNames[next.vehicleId])
        pair(parent, before, after)
        info(parent, present(value.rarityNotice, R.string.delivery_rarity_missing))
        val difference = value.difference
        if (difference == null) note(parent, R.string.delivery_difference_missing)
        else money(parent, if (difference.direction == MoneyDirection.CREDIT) R.string.delivery_refund_planned else R.string.delivery_additional_payment,
            difference.amount.units)
        info(parent, present(value.after.confirmationTerms, R.string.delivery_terms_missing), muted = true)
        if (!value.before.change.allowed) info(parent, present(value.before.change.reason, R.string.delivery_change_unavailable), muted = true)
        if (!value.permission.allowed) info(parent, present(value.permission.reason, R.string.delivery_action_unavailable), muted = true)
        val buttons = reviewActions()
        action(buttons, R.string.delivery_exit_review, "delivery:exit-review", DeliveryCommand.ExitReview, inline = true)
        action(buttons, R.string.delivery_change_confirm, "delivery:change",
            DeliveryCommand.Change(value.before.active, value.after.proposed, value.offerId), primary = true, inline = true)
    }

    private fun abandonReview(parent: LinearLayout, value: DeliveryScreen.AbandonReview) {
        val current = style.panel(padding = 10).apply {
            destination(this, value.delivery.destinationName, value.delivery.active.proposed.estimated.meters)
            offerCard(this, value.delivery.active.offer, value.delivery.vehicleName, active = true)
        }
        val terms = style.panel(padding = 10).apply {
            if (value.fee == null) note(this, R.string.delivery_fee_missing)
            else addView(offerMoney(R.string.delivery_abandon_fee, value.fee.units))
            if (value.refund == null) note(this, R.string.delivery_refund_missing)
            else addView(offerMoney(R.string.delivery_refund_planned, value.refund.units))
            info(this, present(value.consequence, R.string.delivery_consequence_missing))
            if (!value.delivery.abandon.allowed) {
                info(this, present(value.delivery.abandon.reason, R.string.delivery_action_unavailable), muted = true)
            }
            if (!value.permission.allowed) {
                info(this, present(value.permission.reason, R.string.delivery_action_unavailable), muted = true)
            }
        }
        pair(parent, current, terms)
        val buttons = reviewActions()
        action(buttons, R.string.delivery_exit_review, "delivery:exit-review", DeliveryCommand.ExitReview, inline = true)
        action(buttons, R.string.delivery_abandon_confirm, "delivery:abandon",
            DeliveryCommand.Abandon(value.delivery.active), primary = true, inline = true)
    }

    private fun pair(parent: LinearLayout, before: View, after: View, firstWeight: Float = 1f, secondWeight: Float = 1f) = with(style) {
        val pair = if (wide) row().apply { isBaselineAligned = false } else column()
        pair.addView(before, if (wide) LayoutParams(0, -2, firstWeight) else LayoutParams(-1, -2))
        pair.addView(after, (if (wide) LayoutParams(0, -2, secondWeight) else LayoutParams(-1, -2)).apply {
            if (wide) marginStart = dp(PixelStyle.gapDp) else topMargin = dp(PixelStyle.gapDp)
        })
        add(parent, pair)
    }

    private fun destination(parent: LinearLayout, name: String?, meters: Long) = with(style) {
        add(parent, destinationFacts(name, meters))
    }

    private fun destinationFacts(name: String?, meters: Long) = with(style) {
        (if (wide) row() else column()).apply {
            background = insetSurface()
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val destination = text(present(name, R.string.delivery_destination_missing), 20, bold = true)
            val estimate = text(context.getString(R.string.delivery_estimated, deliveryDistance(meters)), 18, bold = true)
            if (wide) {
                gravity = Gravity.CENTER_VERTICAL
                addView(destination, LayoutParams(0, -2, 1f))
                addView(estimate, LayoutParams(-2, -2).apply { marginStart = dp(10) })
            } else {
                addView(destination)
                addView(estimate)
            }
        }
    }

    private fun offerCard(parent: LinearLayout, offer: DeliveryOffer, name: String?,
        active: Boolean = false, tile: Boolean = false, review: Boolean = false, footer: (LinearLayout.() -> Unit)? = null,
    ) = with(style) {
        val content = column().apply {
            addView(text(present(name, R.string.delivery_vehicle_missing), if (review) 23 else 20, bold = true))
            val income = offer is DeliveryOffer.Income
            if (!review && (!tile || income)) {
                addView(chip(s(if (income) R.string.delivery_income else R.string.delivery_purchase),
                    if (income) CleanUi.SEA_DEEP else 0xff3d3216.toInt(),
                    if (income) CleanUi.SEA_LIGHT else CleanUi.SUN), LayoutParams(-2, -2))
            }
            when (offer) {
                is DeliveryOffer.Income -> {
                    addView(offerMoney(R.string.delivery_completion_reward, offer.reward.units))
                    if (!tile) addView(text(s(R.string.delivery_income_note), 16, color = muted))
                }
                is DeliveryOffer.Purchase -> {
                    addView(offerLabels.rarityChip(offer.rarity), LayoutParams(-2, -2))
                    addView(offerMoney(if (active) R.string.delivery_selected_purchase_amount else R.string.delivery_purchase_payment,
                        offer.price.units))
                    if (!tile) addView(text(s(if (active) R.string.delivery_active_purchase_note else R.string.delivery_purchase_note), 16, color = muted))
                }
            }
        }
        val card = if (tile) parent else panel(padding = 10)
        val sprite = sprites.drawable(offer.vehicleId)
        if (sprite == null) {
            card.addView(content)
        } else {
            val art = column().apply {
                gravity = Gravity.CENTER
                val image = ImageView(context).apply {
                    setImageDrawable(sprite)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                addView(image, LayoutParams(dp(if (tile && wide) 76 else 56), dp(if (tile && wide) 56 else 44)))
            }
            val line = row().apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(art, LayoutParams(-2, -2))
            line.addView(content, LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            card.addView(line)
        }
        footer?.invoke(card)
        if (!tile) add(parent, card)
    }

    private fun offerMoney(label: Int, amount: Long) =
        style.text(context.getString(R.string.delivery_money, s(label), amount), 20, style.moneyInk, bold = true)

    private fun money(parent: LinearLayout, label: Int, amount: Long) = with(style) {
        add(parent, panel(padding = 12, fill = inset).apply {
            addView(text(context.getString(R.string.delivery_money, s(label), amount), 20, color = moneyInk, bold = true))
        })
    }
    private fun heading(parent: LinearLayout, label: Int) { style.add(parent, style.text(s(label), 21, bold = true), 4) }
    private fun note(parent: LinearLayout, label: Int) { info(parent, s(label), muted = true) }
    private fun info(parent: LinearLayout, value: String, muted: Boolean = false) {
        style.add(parent, style.text(value, color = if (muted) style.muted else style.ink), 4)
    }
    private fun s(id: Int) = context.getString(id)
    private fun present(value: String?, fallback: Int) = value?.takeIf { it.isNotBlank() } ?: s(fallback)

    private fun action(parent: LinearLayout, label: Int, tag: String, command: DeliveryCommand,
        primary: Boolean = false, inline: Boolean = false,
    ) {
        val generation = drawGeneration
        fun canSubmit() = command !is DeliveryCommand.Select || canConfirmOffer()
        addAction(parent, inline, style.button(s(label), canSubmit() && session.canRequest(command), primary) {
            if (generation == drawGeneration && canSubmit()) send(command)
        }.apply { this.tag = tag })
    }

    private fun localAction(parent: LinearLayout, label: Int, tag: String, enabled: Boolean = true,
        inline: Boolean = false, primary: Boolean = false, action: () -> Unit,
    ) {
        val generation = drawGeneration
        addAction(parent, inline, style.button(s(label), enabled && session.controlsEnabled && session.pending == null, primary) {
            if (generation == drawGeneration && session.controlsEnabled && session.pending == null) action()
        }.apply { this.tag = tag })
    }

    private fun addAction(parent: LinearLayout, inline: Boolean, button: View) {
        if (!inline) style.add(parent, button)
        else parent.addView(button, LayoutParams(0, -2, 1f).apply {
            if (parent.childCount > 0) marginStart = style.dp(8)
        })
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
