package io.drivegrowth.app.ui.gacha

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.drivegrowth.app.R
import io.drivegrowth.app.ui.garage.GarageStyle
import io.drivegrowth.app.ui.garage.VehicleArtwork
import io.drivegrowth.app.ui.pixel.CleanUi
import io.drivegrowth.app.ui.pixel.ReferenceHudIcon
import io.drivegrowth.app.ui.pixel.VehicleSprites
import io.drivegrowth.app.ui.presentation.ReferenceSceneArt
import io.drivegrowth.app.ui.presentation.ReferenceSceneFrame
import io.drivegrowth.app.ui.presentation.ReferenceSceneTypography
import io.drivegrowth.app.ui.presentation.RewardCelebrationKind
import io.drivegrowth.app.ui.presentation.RewardCelebrationView
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.garage.CollectionState
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale

/** 복원은 새 View 생성 시만 받는다. 현재 Pending을 과거 상태로 덮는 API는 제공하지 않는다. */
class GachaView(context: Context, onEvent: (GachaEvent) -> Unit, restored: GachaUiState = GachaUiState()) : LinearLayout(context) {
    private val sceneArt = ReferenceSceneArt(context)
    private val sceneText = ReferenceSceneTypography(context)
    private val style = GarageStyle(context, pixelPanel = true, featureWindow = true)
    private val sceneStyle = GarageStyle(context, pixelPanel = true, creamActions = true)
    private val referenceBadgeStyle = GarageStyle(context, pixelPanel = true, creamPanel = true)
    private val artwork = VehicleArtwork(context, style)
    private val sprites = VehicleSprites(context)
    internal var onCelebration: ((RewardCelebrationKind) -> Unit)? = null
    internal var onGachaAudio: ((Rarity, Boolean) -> Unit)? = null
    internal var onGachaAudioCancelled: (() -> Unit)? = null
    private val celebration = RewardCelebrationView(context).apply {
        onStarted = { onCelebration?.invoke(it) }
        onGachaAudio = { rarity, opening -> this@GachaView.onGachaAudio?.invoke(rarity, opening) }
        onGachaAudioCancelled = { this@GachaView.onGachaAudioCancelled?.invoke() }
    }
    internal var takeNewGachaResult: (Settlement) -> Boolean = { false }
    private var newResult: Settlement? = null
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
    private var composedResult: SettlementId? = null
    private var savedY = 0
    private var drawnFrame: Long? = null
    private var drawnMotionEnabled: Boolean? = null
    internal var animationsEnabled: () -> Boolean = ValueAnimator::areAnimatorsEnabled
    private var foreignCheckpoint = false
    val uiState: GachaUiState get() = session.uiState
    internal var showHeading = true
    internal val fillsAppStage: Boolean get() = getChildAt(0) is ReferenceSceneFrame
    init { orientation = VERTICAL; setBackgroundColor(style.base) }
    fun render(value: GachaFrame, controlsEnabled: Boolean): Boolean {
        val previous = session.uiState
        val previouslyEnabled = session.controlsEnabled
        val accepted = session.render(value, controlsEnabled)
        if (childCount == 0 || previous != session.uiState || previouslyEnabled != session.controlsEnabled ||
            drawnMotionEnabled != animationsEnabled()) drawScreen()
        return accepted
    }
    internal fun setForeignCheckpoint(value: Boolean) {
        if (foreignCheckpoint == value) return
        foreignCheckpoint = value
        if (session.controlsEnabled) drawScreen()
    }
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
        celebration.onGachaReady = null
        celebration.onGachaOpened = null
        celebration.onReferenceFrame = null
        celebration.finishGacha()
    }
    fun release() { session.release(); drawScreen() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val next = w >= style.dp(650) && resources.configuration.fontScale <= 1.3f
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
        val motionEnabled = animationsEnabled()
        drawnMotionEnabled = motionEnabled
        val focused = findFocus()?.tag
        generation++; celebration.rebuildContent { removeAllViews() }; scroll = null; setPadding(dp(16),dp(10),dp(16),dp(12))
        resultStage = null
        setBackgroundColor(base)
        if (!session.controlsEnabled) {
            endReveal()
            celebration.render(null, RewardCelebrationKind.GACHA)
            submittedRequest = null
            newResult = null
            composedResult = null
            addView(card().apply { addView(text(s(R.string.gacha_restricted), 24, bold = true)) })
            return@with
        }
        val result = (session.frame?.screen as? GachaScreen.Result)?.value
        val receipt = result?.settlement
        if (newResult != receipt) newResult = null
        if (revealResult != null && (revealResult != receipt?.settlementId || !motionEnabled)) {
            endReveal()
        }
        if (result?.origin == GachaOrigin.STORED && receipt != null && receipt.transaction.requestId == submittedRequest) {
            submittedRequest = null
            if (takeNewGachaResult(receipt)) newResult = receipt
            if (motionEnabled &&
                receipt.changedVehicle?.vehicleId?.let(sprites::bitmap) != null
            ) revealResult = receipt.settlementId
        }
        if (receipt?.settlementId != composedResult) composedResult = null
        if (result != null && revealResult == receipt?.settlementId && revealResult != null && showReveal(result)) return@with
        if (result != null && composedResult != null && motionEnabled && showComposedResult(result)) return@with
        if (showHeading) addView(heading(s(R.string.gacha_title), ReferenceHudIcon.GACHA))
        val body = column()
        scroll = scroll(body, savedY)
        addView(scroll, LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        if (uiState.pending != null) add(body,text(s(R.string.gacha_pending),color = CleanUi.SEA_LIGHT))
        val screen = session.frame?.screen
        if (screen !is GachaScreen.Failure && screen !is GachaScreen.Missing) {
            uiState.failure?.let { add(body, text(error(it), 24)) }
        }
        when (screen) {
            null, GachaScreen.Loading -> statusCard(body, s(R.string.gacha_loading))
            is GachaScreen.Unsupported -> statusCard(body, s(R.string.gacha_unsupported))
            is GachaScreen.Missing -> statusCard(body,
                s(if (screen.message == "현재 해금 후보가 없습니다.") R.string.gacha_empty else R.string.gacha_error_unavailable),
                retryAllowed = screen.retryAllowed)
            is GachaScreen.Failure -> {
                if (foreignCheckpoint && screen.error == ContractError.STATE_CONFLICT) {
                    statusCard(body, s(R.string.gacha_foreign_checkpoint))
                } else statusCard(body, error(screen.error), retryAllowed = screen.retryAllowed)
            }
            is GachaScreen.Offers -> offers(body, screen.value)
            is GachaScreen.Result -> result(body, screen.value)
        }
        if (focused != null) findViewWithTag<View>(focused)?.takeIf { it.isEnabled }?.requestFocus()
    }
    private fun statusCard(parent: LinearLayout, title: String, retryAllowed: Boolean = false) = with(style) {
        add(parent, card().apply {
            addView(text(title, 24, bold = true))
            retry(this, retryAllowed)
        }, 0)
    }

    private fun showReveal(result: GachaConfirmedResult) = with(sceneStyle) {
        val receipt = result.settlement
        val vehicle = checkNotNull(receipt.changedVehicle)
        val id = receipt.settlementId
        val tierColor = rarityColor(vehicle.rarity)
        val token = generation
        val sprite = sprites.bitmap(vehicle.vehicleId) ?: return@with false
        val held = celebration.holdGacha(id.value, tierColor, sprite, vehicle.rarity) { completed ->
            if (revealResult == id) {
                endReveal()
                composedResult = id.takeIf { completed }
                drawScreen()
            }
        }
        if (!held) {
            revealResult = null
            return@with false
        }
        setPadding(0, 0, 0, 0)
        setBackgroundColor(base)
        val scene = ReferenceSceneFrame(context)
        val stage = celebration.over(View(context), fillHeight = true)
        scene.place(stage, 0, 0, 1360, 948)
        val details = referenceCard(result, interactive = false).apply {
            visibility = View.INVISIBLE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        scene.place(details, 800, 196, 480, -2)
        val glow = details.background as? LegendCardFrame
        var skipControl: View? = null
        celebration.onReferenceFrame = { seconds ->
            val progress = (seconds / .35f).coerceIn(0f, 1f)
            val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
            details.visibility = if (progress > 0f) View.VISIBLE else View.INVISIBLE
            glow?.update(seconds.coerceAtLeast(0f))
            details.alpha = eased
            details.translationX = (1f - eased) * 60f
            val band = details.getChildAt(0)
            val bandProgress = ((seconds - .15f) / .30f).coerceIn(0f, 1f)
            band.scaleX = bandProgress
            band.scaleY = bandProgress
            skipControl?.alpha = 1f - (seconds / .2f).coerceIn(0f, 1f)
            skipControl?.isClickable = seconds < 0f
            skipControl?.isFocusable = seconds < 0f
            if (seconds >= .2f) skipControl?.visibility = View.INVISIBLE
            val body = details.getChildAt(1) as LinearLayout
            body.getChildAt(2).alpha = ((seconds - .25f) / .30f).coerceIn(0f, 1f)
            body.getChildAt(body.childCount - 1).alpha = ((seconds - .5f) / .15f).coerceIn(0f, 1f)
        }
        scene.place(referenceResultHud(result), 28, 28, -2, 48)
        fun canUseScene() = token == generation && session.controlsEnabled && revealResult == id && hasWindowFocus()
        val reveal = button("화면을 눌러 셔터 올리기", primary = true) {
            if (!canUseScene() || !celebration.canOpenGacha) return@button
            if (!ValueAnimator.areAnimatorsEnabled()) {
                endReveal()
                drawScreen()
            } else {
                celebration.openGacha()
            }
        }.apply {
            tag = "gacha:reveal"
            isEnabled = celebration.canOpenGacha
            visibility = if (isEnabled) View.VISIBLE else View.GONE
        }
        scene.tag = "gacha:reveal-scene"
        scene.setOnClickListener {
            if (canUseScene() && celebration.canOpenGacha) reveal.performClick()
        }
        scene.isClickable = reveal.isEnabled
        celebration.onGachaReady = {
            if (canUseScene() && celebration.canOpenGacha) {
                reveal.isEnabled = true
                reveal.visibility = View.VISIBLE
                scene.isClickable = true
            }
        }
        celebration.onGachaOpened = {
            if (canUseScene()) {
                scene.isClickable = false
                reveal.isEnabled = false
                reveal.visibility = View.GONE
            }
        }
        reveal.referenceSize(30f, bold = false)
        reveal.background = null
        reveal.setTextColor(0xfff4f5fa.toInt())
        scene.place(reveal, 420, 806, 520, 64)
        val skip = button("건너뛰기") {
            if (canUseScene()) {
                endReveal()
                drawScreen()
            }
        }.apply { tag = "gacha:skip-reveal" }
        skip.text = "건너뛰기 ▶▶"
        skip.referenceSize(20f, bold = false)
        skip.setPadding(12, 0, 12, 0)
        skip.background = GradientDrawable().apply {
            setColor(0xeb1d2547.toInt()); setStroke(2, 0xff3b4a85.toInt()); cornerRadius = 14f
        }
        scene.place(skip, 1184, 28, 148, 48)
        skipControl = skip
        addView(scene, LayoutParams(-1, 0, 1f))
        true
    }

    private fun retry(parent: LinearLayout, allowed: Boolean) {
        if (allowed) button(parent,s(if(uiState.reloadToken == null) R.string.gacha_retry else R.string.gacha_retry_pending),"gacha:reload",uiState.reloadToken == null) { session.reload(); drawScreen() }
    }
    private fun offers(parent: LinearLayout, value: GachaOffers) = with(style) {
        if (value.origin == GachaOrigin.FIXTURE)
            add(parent, text(s(R.string.gacha_fixture), color = CleanUi.SUN))
        add(parent,text(s(R.string.gacha_balance,money(value.balance)),24,bold=true))
        value.displayProblem()?.let { add(parent,text(status(it),24,bold=true)) }
        if(session.awaitingConfirmation) add(parent,text(s(R.string.gacha_awaiting)))
        val selected = value.options.singleOrNull { it.id == uiState.selected }
        if (selected != null) {
            add(parent, card(selected = true).apply {
                addView(text(s(R.string.gacha_review), 24, bold = true))
                optionFacts(this, selected)
                addView(text(s(R.string.gacha_no_debit), 15, muted))
            }, gap = 0)
            add(this@GachaView, row().apply {
                tag = "gacha:review-actions"
                fun place(label: Int, tag: String, enabled: Boolean, primary: Boolean, action: () -> Unit) {
                    val token = generation
                    addView(style.button(s(label), enabled, primary) {
                        if (token == generation && session.controlsEnabled) action()
                    }.apply { this.tag = tag }, LayoutParams(0, -2, 1f).apply {
                        if (primary) marginStart = dp(8)
                    })
                }
                place(R.string.gacha_cancel, "gacha:cancel", uiState.pending == null, false) {
                    session.cancel(); drawScreen()
                }
                place(R.string.gacha_confirm, "gacha:confirm", session.canSelect(selected.id), true) {
                    session.confirm(); drawScreen()
                }
            })
        }
        val panels = if(wide) row() else column()
        add(parent,panels)
        val actions = card()
        val facts = card()
        panels.addView(actions, if (wide) LayoutParams(0, -2, 1f) else LayoutParams(-1, -2))
        panels.addView(facts, if (wide) LayoutParams(0, -2, 1f).apply { marginStart = dp(14) }
            else LayoutParams(-1, -2).apply { topMargin = dp(14) })
        if (value.options.isEmpty()) add(actions, text(s(R.string.gacha_missing)))
        else {
            val optionRow = row().apply { gravity = Gravity.TOP }
            val options = value.options.sortedBy { it.kind != GachaOptionKind.FREE }
            for ((index, option) in options.withIndex()) {
                val box = column().apply {
                    background = shape(inset)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    val free = option.kind == GachaOptionKind.FREE
                    addView(text(if (free) "무료 뽑기권 ${option.ownedTickets?.toString() ?: s(R.string.gacha_missing)}장"
                        else s(R.string.gacha_money_cost, money(option.moneyCost)), 15, bold = true))
                    option.displayProblem()?.let { addView(text(status(it), 13, muted)) }
                    if (option.displayProblem() == null && !session.canSelect(option.id) &&
                        uiState.pending == null && value.displayProblem() == null
                    ) addView(text(s(R.string.gacha_previous_request), 13, muted))
                    button(this, if (free) "무료권으로 뽑기" else "${money(option.moneyCost)}로 뽑기",
                        "gacha:option:${option.id}", session.canSelect(option.id), primary = free) {
                        session.select(option.id)
                        savedY = 0
                        scroll = null
                        drawScreen()
                    }
                }
                if (options.size <= 2) optionRow.addView(box, LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                }) else add(actions, box)
            }
            if (options.size <= 2) add(actions, optionRow)
        }
        add(actions, text(s(R.string.gacha_purchase_price, money(value.ticketPurchasePrice)), 14, muted))
        add(actions, text(s(R.string.gacha_purchase_unsupported), 13, muted))
        val ids = value.candidates
        actions.apply {
            val mystery = ids?.firstOrNull()
            addView(artwork.view(mystery, null, 144, CollectionState.UNDISCOVERED),
                LayoutParams(-1, dp(150)))
            val rules = value.content?.gacha
            val guarantee = value.guarantee
            add(this, column().apply {
                background = shape(inset)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(text(s(R.string.gacha_threshold,
                    rules?.let { rarity(it.guaranteedFrom) } ?: s(R.string.gacha_missing),
                    rules?.pity?.toString() ?: s(R.string.gacha_missing)), 15))
                val remaining = guarantee?.remaining
                addView(text(s(R.string.gacha_remaining, remaining?.toString() ?: s(R.string.gacha_missing)), 17, bold = true))
                if (rules != null && rules.pity > 0 && remaining != null && remaining in 0..rules.pity) {
                    addView(progressTrack(rules.pity - remaining, rules.pity, CleanUi.SEA),
                        LayoutParams(-1, dp(10)))
                }
                addView(text(s(when (guarantee?.nextGuaranteed) {
                    true -> R.string.gacha_next_guaranteed
                    false -> R.string.gacha_next_normal
                    null -> R.string.gacha_missing
                }), 15, color = if (guarantee?.nextGuaranteed == true) CleanUi.SEA_LIGHT else muted))
                if (guarantee?.available == false) addView(text(s(R.string.gacha_status_guarantee), 15))
                guarantee?.description?.let { addView(text(it, 14, muted)) }
            })
            addView(text(s(R.string.gacha_space_value, value.space?.owned?.toString() ?: s(R.string.gacha_missing),
                value.space?.capacity?.toString() ?: s(R.string.gacha_missing)), 15))
            value.space?.let { if (it.availability != GachaAvailability.READY) addView(text(status(it.availability), 16, CleanUi.SUN)) }
        }
        facts.apply {
            addView(text(s(R.string.gacha_rates), 22, bold = true))
            if (!value.ratesValid()) addView(text(s(R.string.gacha_rates_missing)))
            else Rarity.entries.forEach { tier ->
                val percent = value.appliedPercent?.getValue(tier)
                val ratio = value.appliedRatio
                val rate: String
                val ticks: Int
                // 막대만 표시용 정수 눈금으로 변환한다. 함께 읽히는 수치는 원래 퍼센트나 정확한 분수를 사용한다.
                if (percent != null) {
                    rate = s(R.string.gacha_rate, rarity(tier), percent.stripTrailingZeros().toPlainString())
                    ticks = percent.movePointRight(1).toInt()
                } else {
                    val fraction = checkNotNull(ratio)
                    val numerator = fraction.numerators.getValue(tier)
                    rate = "${rarity(tier)} · $numerator/${fraction.denominator}"
                    ticks = BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(1000))
                        .divide(BigInteger.valueOf(fraction.denominator)).toInt()
                }
                add(this, row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(rarityChip(tier), LayoutParams(dp(66), -2))
                    addView(progressTrack(ticks, 1000, when (tier) {
                        Rarity.COMMON -> muted
                        Rarity.ADVANCED -> 0xffb9df8b.toInt()
                        Rarity.RARE -> CleanUi.SEA_LIGHT
                        Rarity.LEGEND -> CleanUi.SUN
                    }), LayoutParams(0, dp(10), 1f).apply {
                        marginStart = dp(8)
                        marginEnd = dp(8)
                    })
                    addView(text(rate, 14, bold = true).apply {
                        gravity = Gravity.END
                        maxWidth = dp(120)
                    }, LayoutParams(-2, -2))
                }, 5)
            }
            add(this, text(s(R.string.gacha_pool) + (ids?.let { " · ${it.size}종" } ?: ""), 18, bold = true))
            addView(text(s(R.string.gacha_pool_note), 14, muted))
            when {
                ids == null -> addView(text(s(R.string.gacha_missing)))
                ids.isEmpty() -> addView(text(s(R.string.gacha_empty)))
                else -> ids.chunked(2).forEach { group ->
                    add(this, row().apply {
                        group.forEachIndexed { index, id ->
                            val name = value.content?.catalog?.vehicles?.singleOrNull { it.vehicleId == id }?.displayName
                            val chip = row().apply {
                                gravity = Gravity.CENTER_VERTICAL
                                background = shape(inset)
                                setPadding(dp(5), 0, dp(7), 0)
                                addView(artwork.view(id, null, 30).apply { background = null },
                                    LayoutParams(dp(38), dp(30)))
                                addView(text(name ?: s(R.string.gacha_missing), 14), LayoutParams(0, -2, 1f))
                            }
                            addView(chip, LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) })
                        }
                        if (group.size == 1) addView(View(context), LayoutParams(0, 0, 1f))
                    }, 6)
                }
            }
        }
    }
    private fun optionFacts(parent: LinearLayout, option: GachaOption) = with(style) {
        parent.addView(text(option.title,26,bold=true))
        parent.addView(text(s(if(option.kind == GachaOptionKind.FREE) R.string.gacha_free else R.string.gacha_paid),color=muted))
        parent.addView(text(s(R.string.gacha_money_cost,money(option.moneyCost))))
        parent.addView(text(s(R.string.gacha_ticket_cost,option.ticketCost?.toString() ?: s(R.string.gacha_missing))))
        parent.addView(text(s(R.string.gacha_ticket_owned,option.ownedTickets?.toString() ?: s(R.string.gacha_missing))))
        parent.addView(text(option.description,color=muted))
    }
    private fun showComposedResult(value: GachaConfirmedResult): Boolean = with(sceneStyle) {
        val receipt = value.settlement
        val vehicle = receipt.changedVehicle ?: return@with false
        val sprite = sprites.bitmap(vehicle.vehicleId) ?: return@with false
        setPadding(0, 0, 0, 0)
        setBackgroundColor(base)
        val scene = ReferenceSceneFrame(context).apply { tag = "gacha:result-stage" }
        scene.place(celebration.referenceResult(sprite, vehicle.rarity), 0, 0, 1360, 948)
        scene.place(referenceCard(value), 800, 196, 480, -2)
        scene.place(referenceResultHud(value), 28, 28, -2, 48)
        addView(scene, LayoutParams(-1, 0, 1f))
        true
    }

    private fun referenceCard(value: GachaConfirmedResult, interactive: Boolean = true): LinearLayout = with(sceneStyle) {
        val receipt = value.settlement
        val vehicle = checkNotNull(receipt.changedVehicle)
        val tier = vehicle.rarity
        val tierColor = when (tier) {
            Rarity.LEGEND -> 0xffffc83d.toInt()
            Rarity.RARE -> 0xffff4fa3.toInt()
            Rarity.ADVANCED -> 0xff4f8cff.toInt()
            Rarity.COMMON -> 0xffc3cce6.toInt()
        }
        val creamText = 0xfff4f5fa.toInt()
        val navy = 0xff1d2547.toInt()
        val secondary = 0xff2a3463.toInt()
        val enabled = uiState.garagePending == null && uiState.reloadToken == null
        fun shape(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            if (stroke != null) setStroke(4, stroke)
        }
        column().apply {
            tag = "gacha:reference-card"
            background = if (tier == Rarity.LEGEND) LegendCardFrame().apply { update(.9f) } else shape(navy, 24f, tierColor)
            val bandText = if (tier == Rarity.LEGEND) "★ 전설 등급! ★" else rarity(tier)
            addView(text(bandText, 21, 0xff0b0e1f.toInt()).apply {
                gravity = Gravity.CENTER
                background = shape(tierColor, 12f)
            }.referenceSize(30f, bold = false), LayoutParams(-1, 64).apply {
                setMargins(18, 18, 18, 18)
            })
            addView(column().apply {
                setPadding(36, 6, 36, 34)
                fun append(view: View, gap: Int = 18) {
                    addView(view, LayoutParams(-1, -2).apply { if (childCount > 0) topMargin = gap })
                }
                append(text(s(R.string.gacha_result), 16, 0xff8796c4.toInt()).apply {
                    setPadding(0, 0, 0, 0)
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = 26
                }.referenceSize(20f, bold = false))
                append(text(value.vehicleName ?: s(R.string.gacha_missing), 27, creamText, bold = true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }.referenceSize(44f))
                getChildAt(1).minimumHeight = 54
                append(row().apply {
                    fun badge(label: String, fill: Int, textColor: Int) {
                        addView(text(label, 15, textColor).apply {
                            gravity = Gravity.CENTER
                            setPadding(14, 0, 14, 0)
                            background = shape(fill, 10f)
                        }.referenceSize(18f, bold = false), LayoutParams(-2, 34).apply { marginEnd = 10 })
                    }
                    badge(rarity(tier), tierColor, 0xff0b0e1f.toInt())
                    badge("Lv${vehicle.level}", secondary, creamText)
                })
                if (newResult == receipt) append(newCollectionBadge(referenceBadgeStyle))
                addView(View(context).apply { setBackgroundColor(secondary) }, LayoutParams(-1, 2).apply {
                    topMargin = 24
                })
                append(row().apply {
                    val token = generation
                    fun resultButton(label: String, tag: String, primary: Boolean, action: () -> Unit) {
                        val face = if (primary) 0xff2f6bff.toInt() else secondary
                        val shadow = if (primary) 0xff1b3fb0.toInt() else 0xff0b0e1f.toInt()
                        addView(android.widget.Button(context).apply {
                            text = label
                            isAllCaps = false
                            setTextColor(creamText)
                            setPadding(8, 0, 8, 4)
                            setSingleLine(true)
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            background = android.graphics.drawable.StateListDrawable().apply {
                                fun layer(fill: Int, edge: Int, focused: Boolean = false) =
                                    android.graphics.drawable.LayerDrawable(arrayOf(
                                        shape(edge, 16f), shape(fill, 16f, if (focused) creamText else null),
                                    )).apply { setLayerInset(1, 0, 0, 0, 6) }
                                addState(intArrayOf(-android.R.attr.state_enabled), layer(0xff454545.toInt(), 0xff2b2b2b.toInt()))
                                addState(intArrayOf(android.R.attr.state_focused), layer(face, shadow, focused = true))
                                addState(intArrayOf(android.R.attr.state_pressed), layer(shadow, shadow))
                                addState(intArrayOf(), layer(face, shadow))
                            }
                            isEnabled = enabled
                            if (interactive) {
                                this.tag = tag
                                setOnClickListener { if (token == generation && session.controlsEnabled) action() }
                            } else {
                                isClickable = false
                                isFocusable = false
                            }
                        }.referenceSize(22f), LayoutParams(0, 64, if (primary) 1.3f else 1f).apply {
                            if (primary) marginEnd = 12
                        })
                    }
                    resultButton(s(if (uiState.reloadToken == null) R.string.gacha_draw_again else R.string.gacha_retry_pending),
                        "gacha:draw-again", true) { session.reloadAfterResult(); drawScreen() }
                    resultButton(s(if (uiState.garagePending == null) R.string.gacha_garage else R.string.gacha_garage_pending),
                        "gacha:garage", false) { session.openGarage(); drawScreen() }
                }, 24)
            })
        }
    }

    private class LegendCardFrame : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var time = 0f

        fun update(seconds: Float) {
            time = seconds
            invalidateSelf()
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val glow = .5f + .5f * kotlin.math.sin(time * 3f)
            fun layer(outset: Float, color: Int) {
                paint.color = color
                val radius = 24f + outset
                canvas.drawRoundRect(bounds.left - outset, bounds.top - outset,
                    bounds.right + outset, bounds.bottom + outset, radius, radius, paint)
            }
            layer(16f + glow * 8f, (((.18f + glow * .12f) * 255f).toInt() shl 24) or 0xffc83d)
            layer(8f, 0xffd98a1c.toInt())
            layer(4f, 0xffffc83d.toInt())
            layer(0f, 0xff1d2547.toInt())
            layer(-8f, 0xffffe38a.toInt())
            layer(-10f, 0xff1d2547.toInt())
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun referenceResultHud(value: GachaConfirmedResult): View = with(sceneStyle) {
        val receipt = value.settlement
        row().apply {
            receipt.transaction.money?.takeIf { it.direction == MoneyDirection.DEBIT }?.let { change ->
                val amount = NumberFormat.getIntegerInstance(Locale.KOREA).format(change.amount.units)
                addView(referenceHud(amount, color = 0xffffe38a.toInt()).apply {
                    setCompoundDrawables(sceneArt.coinDrawable(30), null, null, null)
                    compoundDrawablePadding = 10
                    contentDescription = s(R.string.gacha_result_debit, money(change.amount))
                }, LayoutParams(-2, 48).apply { marginEnd = 12 })
            }
            if (value.origin == GachaOrigin.FIXTURE) {
                addView(referenceHud("시험 입력"), LayoutParams(-2, 48))
            }
            addView(referenceHud(s(R.string.gacha_balance, money(receipt.balance))).referenceSize(17f, bold = false),
                LayoutParams(-2, 48).apply { marginStart = 12 })
        }
    }

    private fun referenceHud(label: String, color: Int = 0xffc3cce6.toInt()) =
        sceneStyle.text(label, 16, color).apply {
            gravity = Gravity.CENTER
            setPadding(18, 0, 18, 0)
            background = GradientDrawable().apply {
                setColor(0xeb1d2547.toInt())
                setStroke(2, 0xff3b4a85.toInt())
                cornerRadius = 14f
            }
        }.referenceSize(20f, bold = false)

    private fun <T : TextView> T.referenceSize(size: Float, bold: Boolean = true): T =
        sceneText.apply(this, size, if (bold) sceneText.plexBold else sceneText.plexMedium)

    private fun newCollectionBadge(badgeStyle: GarageStyle) = with(badgeStyle) {
        text(s(R.string.gacha_new_collection), 16, bold = true).apply {
            tag = "gacha:new-collection"
            background = shape(panel, selected = true)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
    }

    private fun result(parent: LinearLayout, value: GachaConfirmedResult) = with(style) {
        val receipt=value.settlement; val vehicle=receipt.changedVehicle!!
        if (value.origin == GachaOrigin.FIXTURE) add(parent, text(s(R.string.gacha_fixture), color = muted))
        val panels = if (wide) row() else column()
        add(parent, panels)
        val sprite = sprites.bitmap(vehicle.vehicleId)
        val art = if (sprite == null) artwork.view(vehicle.vehicleId, vehicle.rarity, 168) else ImageView(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageDrawable(sprites.drawable(vehicle.vehicleId))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(24), dp(22), dp(24), dp(34))
        }
        val tierColor = sceneStyle.rarityColor(vehicle.rarity)
        celebration.render(null, RewardCelebrationKind.GACHA, tierColor)
        val stage = celebration.over(art, fillHeight = true, stage = true).apply { tag = "gacha:result-stage" }
        resultStage = stage
        val stageHeight = resultStageHeight(height)
        panels.addView(stage, if (wide) LayoutParams(0, stageHeight, 1.2f) else LayoutParams(-1, stageHeight))
        val details = card().apply {
            addView(rarityChip(vehicle.rarity).apply { gravity = Gravity.CENTER })
            addView(text(s(R.string.gacha_result), 28, bold = true))
            addView(text(s(R.string.gacha_vehicle,value.vehicleName ?: s(R.string.gacha_missing),rarity(vehicle.rarity)),30,color = ink, bold = true))
            addView(text(s(R.string.gacha_growth,vehicle.level,vehicle.levelProgressPercent)))
            if (newResult == receipt) addView(newCollectionBadge(style))
            receipt.transaction.money?.takeIf { it.direction == MoneyDirection.DEBIT }?.let { change ->
                addView(text(s(R.string.gacha_result_debit, money(change.amount))))
            }
            addView(text(s(R.string.gacha_balance,money(receipt.balance))))
            val resultActionAvailable = uiState.garagePending == null && uiState.reloadToken == null
            button(this, s(if (uiState.reloadToken == null) R.string.gacha_draw_again else R.string.gacha_retry_pending),
                "gacha:draw-again", resultActionAvailable, true) { session.reloadAfterResult(); drawScreen() }
            button(this, s(if (uiState.garagePending == null) R.string.gacha_garage else R.string.gacha_garage_pending),
                "gacha:garage", resultActionAvailable) { session.openGarage(); drawScreen() }
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
